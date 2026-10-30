package com.grouprecents

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memantau window yang aktif. Saat launcher/SystemUI menampilkan layar Recents,
 * overlay berkelompok ditampilkan di atasnya.
 */
class GroupedRecentsService : AccessibilityService() {

    companion object {
        var instance: GroupedRecentsService? = null
            private set

        private const val SYSTEM_UI = "com.android.systemui"
        private const val MAX_NODES = 700
        private const val CHECK_DELAY_MS = 150L

        // Potongan ID view yang menandakan layar Recents di launcher (Pixel/AOSP, Samsung, MIUI, dll.)
        private val LAUNCHER_ID_MARKERS = listOf(
            "overview", "recents", "recent_apps", "clear_all", "clearanimview", "task_view",
        )

        // Di SystemUI hanya ID ini yang dipakai, supaya tombol "Clear all" notifikasi tidak ikut terdeteksi
        private val SYSTEMUI_ID_MARKERS = listOf("recents")

        // Teks tombol/label yang hanya ada di layar Recents (dicocokkan utuh, huruf kecil)
        private val TEXT_MARKERS = setOf(
            "clear all", "close all", "hapus semua", "tutup semua", "bersihkan semua",
            "no recent items", "tidak ada item terbaru", "tidak ada aplikasi terbaru",
            "recent apps", "aplikasi terbaru", "aplikasi terkini",
        )

        // Paket yang mendaftar sebagai HOME tapi bukan launcher sungguhan
        private val NOT_LAUNCHERS = setOf("com.android.settings", "com.android.tv.settings")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    lateinit var recents: RecentsStore
        private set
    private lateinit var overlay: RecentsOverlay

    private var homePackages = emptySet<String>()
    private var imePackages = emptySet<String>()
    private var packageSetsAt = 0L
    private val launchable = HashMap<String, Boolean>()

    private var checkPending = false
    private var lastEventClass: String? = null
    private var lastMarker: String? = null
    private var suppressUntil = 0L
    private var bypass = false

    private val checkRunnable = Runnable {
        checkPending = false
        runCheck()
    }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        recents = RecentsStore(this)
        overlay = RecentsOverlay(this)
        refreshPackageSets(force = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == null) return
                refreshPackageSets()
                if (pkg in homePackages || pkg == SYSTEM_UI) {
                    lastEventClass = event.className?.toString()
                    scheduleCheck(reset = true)
                } else if (isListable(pkg)) {
                    onAppForeground(pkg)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (pkg != null && pkg in homePackages) scheduleCheck(reset = false)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleCheck(reset = false)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        handler.removeCallbacks(checkRunnable)
        if (::overlay.isInitialized) overlay.hide()
        instance = null
    }

    // ---- dipanggil dari overlay / MainActivity ----

    fun recentApps(): List<String> = recents.recent(::isListable)

    fun launchFromOverlay(pkg: String) {
        overlay.hide()
        suppress(1000)
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun dismissOverlay() {
        overlay.hide()
        suppress(800)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** Sembunyikan overlay dan biarkan Recents bawaan dipakai sampai user keluar dari Recents. */
    fun useSystemRecents() {
        bypass = true
        overlay.hide()
    }

    fun closeApps(pkgs: Collection<String>) {
        recents.dismiss(pkgs)
        val am = getSystemService(ActivityManager::class.java)
        pkgs.forEach { try { am.killBackgroundProcesses(it) } catch (_: Exception) {} }
    }

    fun showOverlayForTest() {
        bypass = false
        overlay.show()
    }

    // ---- internal ----

    private fun onAppForeground(pkg: String) {
        recents.touch(pkg)
        bypass = false
        if (overlay.isShowing) overlay.hide()
        if (prefs.debug) log("app: $pkg")
    }

    private fun suppress(ms: Long) {
        suppressUntil = SystemClock.uptimeMillis() + ms
    }

    private fun scheduleCheck(reset: Boolean) {
        if (reset) {
            handler.removeCallbacks(checkRunnable)
            checkPending = false
        }
        if (checkPending) return
        checkPending = true
        handler.postDelayed(checkRunnable, CHECK_DELAY_MS)
    }

    private fun runCheck() {
        refreshPackageSets()
        val ids = if (prefs.debug) LinkedHashSet<String>() else null
        val marker = findRecentsMarker(ids)
        if (prefs.debug && marker != lastMarker) {
            if (marker != null) log("RECENTS terdeteksi: $marker")
            else log("bukan recents · kelas=$lastEventClass · id=${ids?.take(15)?.joinToString(",")}")
        }
        lastMarker = marker

        if (marker != null) {
            if (!overlay.isShowing && !bypass && prefs.enabled && SystemClock.uptimeMillis() >= suppressUntil) {
                overlay.show()
            }
        } else {
            bypass = false
            if (overlay.isShowing) overlay.hide()
        }
    }

    private fun findRecentsMarker(ids: MutableSet<String>?): String? {
        lastEventClass?.let { if (it.lowercase().contains("recents")) return "kelas $it" }

        val windowRoots = try {
            windows.mapNotNull { it.root }
        } catch (_: Exception) {
            emptyList()
        }
        val roots = windowRoots.ifEmpty { listOfNotNull(rootInActiveWindow) }

        for (root in roots) {
            val p = root.packageName?.toString() ?: continue
            val found = when {
                p in homePackages -> scan(root, LAUNCHER_ID_MARKERS, useText = true, ids = ids)
                p == SYSTEM_UI -> scan(root, SYSTEMUI_ID_MARKERS, useText = false, ids = null)
                else -> null
            }
            if (found != null) return "$p · $found"
        }
        return null
    }

    /** BFS lewat node yang terlihat saja; subtree yang tersembunyi dilewati. */
    private fun scan(
        root: AccessibilityNodeInfo,
        idMarkers: List<String>,
        useText: Boolean,
        ids: MutableSet<String>?,
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val n = queue.removeFirst()
            seen++
            if (!n.isVisibleToUser) continue
            val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase()
            if (id != null) {
                ids?.add(id)
                if (idMarkers.any { id.contains(it) }) return "id/$id"
            }
            if (useText) {
                val text = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
                if (text != null && text in TEXT_MARKERS) return "teks \"$text\""
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun isListable(pkg: String): Boolean {
        if (pkg == packageName || pkg == SYSTEM_UI || pkg in homePackages || pkg in imePackages) return false
        return launchable.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }
    }

    private fun refreshPackageSets(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - packageSetsAt < 60_000) return
        packageSetsAt = now
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homePackages = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it !in NOT_LAUNCHERS }
            .toSet()
        imePackages = getSystemService(InputMethodManager::class.java).inputMethodList
            .map { it.packageName }
            .toSet()
        launchable.clear()
    }

    private fun log(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        prefs.appendLog("$time  $line")
    }
}
