package com.grouprecents

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

/** Pengaturan: status izin, kelola grup, dan log debug. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: GroupStore
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = GroupStore(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()

        section("Status")
        val a11y = isAccessibilityEnabled()
        text(if (a11y) "✅ Layanan aksesibilitas aktif" else "❌ Layanan aksesibilitas belum aktif")
        if (!a11y) {
            button("Aktifkan di pengaturan Aksesibilitas") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            hint("Kalau tombolnya abu-abu/terkunci (Android 13+): buka Info aplikasi → menu ⋮ di pojok kanan atas → \"Izinkan setelan terbatas\", lalu coba lagi.")
            button("Buka Info aplikasi") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        val usage = RecentsStore.hasUsageAccess(this)
        text(if (usage) "✅ Akses penggunaan aktif" else "⚪ Akses penggunaan belum aktif (opsional)")
        if (!usage) {
            hint("Opsional, tapi disarankan: biar daftar aplikasi terakhir langsung terisi, termasuk aplikasi yang dibuka sebelum layanan aktif.")
            button("Beri akses penggunaan") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }

        section("Pengaturan")
        checkbox("Ganti layar Recents dengan tampilan berkelompok", prefs.enabled) { prefs.enabled = it }
        checkbox("Urutkan grup berdasarkan pemakaian terakhir", prefs.sortByRecency) { prefs.sortByRecency = it }
        hint("Kalau dimatikan, grup tampil sesuai urutan di bawah, \"Lainnya\" paling akhir.")
        button("Tes tampilan sekarang") {
            val s = GroupedRecentsService.instance
            if (s == null) toast("Aktifkan layanan aksesibilitas dulu") else s.showOverlayForTest()
        }

        section("Grup")
        hint("Aplikasi yang tidak masuk grup mana pun muncul di \"Lainnya\". Tap grup untuk mengubahnya.")
        val labels = launchableApps().toMap()
        val groups = store.load()
        if (groups.isEmpty()) text("Belum ada grup.")
        groups.forEach { groupRow(it, labels) }
        button("+ Tambah grup") { editName(null) }

        section("Debug")
        checkbox("Catat log deteksi Recents", prefs.debug) { prefs.debug = it }
        hint("Kalau overlay tidak muncul di Recents, atau malah muncul di layar lain: nyalakan ini, buka Recents & home beberapa kali, lalu salin log-nya.")
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(smallButton("Refresh") { render() })
        buttons.addView(smallButton("Salin") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", prefs.log))
            toast("Log disalin")
        })
        buttons.addView(smallButton("Hapus") { prefs.clearLog(); render() })
        content.addView(buttons)
        content.addView(TextView(this).apply {
            text = prefs.log.ifEmpty { "(log kosong)" }
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun groupRow(g: AppGroup, labels: Map<String, String>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0x33000000)
            }
            isClickable = true
            setOnClickListener { groupMenu(g.id) }
        }
        row.addView(TextView(this).apply {
            text = g.name
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = if (g.packages.isEmpty()) "(belum ada aplikasi)"
            else g.packages.joinToString(", ") { labels[it] ?: it }
            textSize = 13f
            alpha = 0.7f
        })
        content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun groupMenu(id: String) {
        val g = store.load().firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(arrayOf("Pilih aplikasi", "Ganti nama", "Naikkan", "Turunkan", "Hapus grup")) { _, which ->
                when (which) {
                    0 -> pickApps(id)
                    1 -> editName(id)
                    2 -> move(id, -1)
                    3 -> move(id, 1)
                    4 -> AlertDialog.Builder(this)
                        .setMessage("Hapus grup \"${g.name}\"? Aplikasinya akan pindah ke \"Lainnya\".")
                        .setPositiveButton("Hapus") { _, _ ->
                            store.save(store.load().filter { it.id != id })
                            render()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
            .show()
    }

    private fun editName(id: String?) {
        val groups = store.load()
        val g = groups.firstOrNull { it.id == id }
        val input = EditText(this).apply {
            setText(g?.name ?: "")
            hint = "mis. Kerja, Sosmed, Game"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(if (g == null) "Grup baru" else "Ganti nama")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (g == null) {
                    val created = AppGroup(UUID.randomUUID().toString(), name, mutableListOf())
                    store.save(groups + created)
                    render()
                    pickApps(created.id)
                } else {
                    g.name = name
                    store.save(groups)
                    render()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pickApps(id: String) {
        val groups = store.load()
        val g = groups.firstOrNull { it.id == id } ?: return
        val apps = launchableApps()
        val owner = HashMap<String, String>()
        groups.filter { it.id != id }.forEach { other -> other.packages.forEach { owner[it] = other.name } }
        val items = apps.map { (p, label) -> owner[p]?.let { "$label   · $it" } ?: label }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in g.packages }

        AlertDialog.Builder(this)
            .setTitle("Aplikasi di \"${g.name}\"")
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Simpan") { _, _ ->
                val selected = apps.indices.filter { checked[it] }.map { apps[it].first }
                val selectedSet = selected.toSet()
                // Satu aplikasi hanya boleh ada di satu grup
                groups.forEach { if (it.id != id) it.packages.removeAll(selectedSet) }
                g.packages.clear()
                g.packages.addAll(selected)
                store.save(groups)
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun move(id: String, delta: Int) {
        val groups = store.load()
        val i = groups.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in groups.indices) return
        groups[i] = groups[j].also { groups[j] = groups[i] }
        store.save(groups)
        render()
    }

    private fun launchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val me = ComponentName(this, GroupedRecentsService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ---- helper tampilan ----

    private fun section(label: String) = content.addView(TextView(this).apply {
        text = label
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF3F51B5.toInt())
        setPadding(0, dp(20), 0, dp(6))
    })

    private fun text(s: String) = content.addView(TextView(this).apply {
        text = s
        textSize = 15f
        setPadding(0, dp(4), 0, dp(4))
    })

    private fun hint(s: String) = content.addView(TextView(this).apply {
        text = s
        textSize = 13f
        alpha = 0.7f
        setPadding(0, 0, 0, dp(4))
    })

    private fun button(label: String, onClick: () -> Unit) = content.addView(Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    })

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun checkbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        content.addView(CheckBox(this).apply {
            text = label
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
