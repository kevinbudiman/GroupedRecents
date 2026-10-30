package com.grouprecents

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class AppGroup(val id: String, var name: String, val packages: MutableList<String>)

/** Grup buatan user, disimpan sebagai JSON di SharedPreferences. */
class GroupStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("groups", Context.MODE_PRIVATE)

    fun load(): MutableList<AppGroup> {
        val out = mutableListOf<AppGroup>()
        try {
            val arr = JSONArray(sp.getString("groups", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.getJSONArray("packages")
                out += AppGroup(o.getString("id"), o.getString("name"), MutableList(p.length()) { p.getString(it) })
            }
        } catch (_: JSONException) {
        }
        return out
    }

    fun save(groups: List<AppGroup>) {
        val arr = JSONArray()
        groups.forEach { g ->
            arr.put(JSONObject().put("id", g.id).put("name", g.name).put("packages", JSONArray(g.packages)))
        }
        sp.edit().putString("groups", arr.toString()).apply()
    }
}

/**
 * Daftar aplikasi terakhir. Sumber utama: aplikasi yang tercatat masuk foreground oleh
 * layanan aksesibilitas. Kalau izin Usage Access diberikan, riwayat UsageStats ikut digabung.
 */
class RecentsStore(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("recents", Context.MODE_PRIVATE)
    private val used = loadMap("used")
    private val dismissed = loadMap("dismissed")

    fun touch(pkg: String) {
        used[pkg] = System.currentTimeMillis()
        dismissed.remove(pkg)
        if (used.size > MAX_TRACKED) {
            used.entries.sortedBy { it.value }.take(used.size - MAX_TRACKED).forEach { used.remove(it.key) }
        }
        save()
    }

    fun dismiss(pkgs: Collection<String>) {
        val now = System.currentTimeMillis()
        pkgs.forEach { used.remove(it); dismissed[it] = now }
        save()
    }

    /** Paket terurut dari yang paling baru dipakai. */
    fun recent(filter: (String) -> Boolean, limit: Int = 40): List<String> {
        val merged = HashMap(used)
        if (hasUsageAccess(ctx)) {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - USAGE_WINDOW_MS, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                // 1 = MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                if (e.eventType == 1) {
                    val prev = merged[e.packageName]
                    if (prev == null || e.timeStamp > prev) merged[e.packageName] = e.timeStamp
                }
            }
        }
        return merged.entries
            .filter { (p, t) -> filter(p) && (dismissed[p]?.let { t > it } ?: true) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    private fun loadMap(key: String): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        try {
            val o = JSONObject(sp.getString(key, "{}") ?: "{}")
            o.keys().forEach { out[it] = o.getLong(it) }
        } catch (_: JSONException) {
        }
        return out
    }

    private fun save() {
        sp.edit()
            .putString("used", JSONObject(used as Map<*, *>).toString())
            .putString("dismissed", JSONObject(dismissed as Map<*, *>).toString())
            .apply()
    }

    companion object {
        private const val MAX_TRACKED = 80
        private const val USAGE_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var sortByRecency: Boolean
        get() = sp.getBoolean("sortByRecency", true)
        set(v) = sp.edit().putBoolean("sortByRecency", v).apply()

    var debug: Boolean
        get() = sp.getBoolean("debug", false)
        set(v) = sp.edit().putBoolean("debug", v).apply()

    val log: String
        get() = sp.getString("log", "") ?: ""

    fun appendLog(line: String) {
        val lines = log.lines().filter { it.isNotBlank() } + line
        sp.edit().putString("log", lines.takeLast(80).joinToString("\n")).apply()
    }

    fun clearLog() = sp.edit().remove("log").apply()
}
