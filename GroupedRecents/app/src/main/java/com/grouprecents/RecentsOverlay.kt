package com.grouprecents

import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.ContextThemeWrapper

/** Layar Recents pengganti: aplikasi terakhir, dikelompokkan per grup. */
class RecentsOverlay(private val service: GroupedRecentsService) {
    private val ctx = ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault)
    private val wm = service.getSystemService(WindowManager::class.java)
    private val pm = service.packageManager
    private var root: FrameLayout? = null
    private var list: LinearLayout? = null

    val isShowing get() = root != null

    fun show() {
        if (root != null) return
        val r = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) service.dismissOverlay()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        r.setBackgroundColor(BG)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(64))
            // Tap di area kosong = ke home, seperti Recents bawaan
            setOnClickListener { service.dismissOverlay() }
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        r.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root = r
        list = col
        populate()

        val lp = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        lp.windowAnimations = android.R.style.Animation_Toast
        try {
            wm.addView(r, lp)
        } catch (_: Exception) {
            root = null
            list = null
        }
    }

    fun hide() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        list = null
    }

    private fun populate() {
        val col = list ?: return
        col.removeAllViews()
        val entries = service.recentApps()
        col.addView(topBar(entries))
        col.addView(text("Tap untuk buka · tahan untuk tutup", 12f, MUTED).apply { setPadding(0, 0, 0, dp(8)) })

        if (entries.isEmpty()) {
            col.addView(text("Belum ada aplikasi terbaru.", 15f, MUTED).apply { setPadding(0, dp(32), 0, 0) })
            return
        }

        val groups = GroupStore(service).load()
        val groupOf = HashMap<String, Int>()
        groups.forEachIndexed { i, g -> g.packages.forEach { groupOf.putIfAbsent(it, i) } }

        // entries sudah terurut dari yang terbaru, jadi urutan insert = urutan pemakaian grup
        val buckets = LinkedHashMap<Int, MutableList<String>>()
        entries.forEach { buckets.getOrPut(groupOf[it] ?: OTHERS) { mutableListOf() }.add(it) }
        val ordered = if (Prefs(service).sortByRecency) {
            buckets.entries.toList()
        } else {
            buckets.entries.sortedBy { if (it.key == OTHERS) Int.MAX_VALUE else it.key }
        }

        val width = service.resources.displayMetrics.widthPixels - dp(32)
        val cols = (width / dp(84)).coerceAtLeast(3)
        val cell = width / cols
        for ((idx, pkgs) in ordered) {
            val name = if (idx == OTHERS) "Lainnya" else groups[idx].name
            col.addView(groupHeader(name, pkgs))
            val grid = GridLayout(ctx).apply { columnCount = cols }
            pkgs.forEach { grid.addView(tile(it, cell)) }
            col.addView(grid)
        }
    }

    private fun topBar(entries: List<String>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            text("Recents", 24f, TEXT).apply { setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        addView(chip("Bawaan") { service.useSystemRecents() })
        if (entries.isNotEmpty()) {
            addView(chip("Tutup semua") {
                service.closeApps(entries)
                service.dismissOverlay()
            })
        }
    }

    private fun groupHeader(name: String, pkgs: List<String>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(18), 0, dp(6))
        addView(
            text("$name  ·  ${pkgs.size}", 15f, ACCENT).apply { setTypeface(typeface, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        addView(chip("Tutup") {
            service.closeApps(pkgs)
            populate()
        })
    }

    private fun tile(pkg: String, cell: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        layoutParams = GridLayout.LayoutParams().apply {
            width = cell
            height = WRAP_CONTENT
        }
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)

        val (label, icon) = try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info)
        } catch (_: Exception) {
            pkg to pm.defaultActivityIcon
        }
        addView(ImageView(ctx).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(text(label, 12f, TEXT).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setOnClickListener { service.launchFromOverlay(pkg) }
        setOnLongClickListener {
            service.closeApps(listOf(pkg))
            populate()
            true
        }
    }

    private fun chip(label: String, onClick: () -> Unit) = text(label, 13f, TEXT).apply {
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(CHIP)
        }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun text(s: String, size: Float, color: Int) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val OTHERS = -1
        const val BG = 0xF2101216.toInt()
        const val TEXT = 0xFFF1F3F4.toInt()
        const val MUTED = 0xFF9AA0A6.toInt()
        const val ACCENT = 0xFF8AB4F8.toInt()
        const val CHIP = 0x33FFFFFF
    }
}
