# Grouped Recents

Pengganti tampilan Recents Android: saat kamu membuka Recents (lewat gesture atau tombol), muncul overlay yang berisi aplikasi terakhir, **dikelompokkan sesuai grup buatanmu** (misalnya Kerja, Sosmed, Game). Aplikasi yang tidak masuk grup mana pun tampil di "Lainnya".

Tanpa root. Cara kerjanya: Accessibility Service mendeteksi layar Recents bawaan, lalu menimpanya dengan overlay.

## Build

1. Install [Android Studio](https://developer.android.com/studio). SDK dan JDK sudah termasuk di dalamnya.
2. Pilih **File → Open**, lalu buka folder `GroupedRecents` ini. Tunggu proses Gradle sync selesai.
3. Sambungkan HP lewat USB dengan *USB debugging* aktif, lalu klik **Run ▶**.
   Kalau mau file APK saja: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. Hasilnya ada di `app/build/outputs/apk/debug/app-debug.apk`.

### Lewat GitHub Actions (tanpa install apa-apa)

1. Upload isi folder ini ke repo GitHub baru. Folder `.github` harus ikut.
2. Workflow **Build APK** akan jalan otomatis setiap kali ada push (bisa juga dijalankan manual dari tab **Actions → Build APK → Run workflow**).
3. Kalau sudah hijau, buka run-nya, lalu unduh artifact **GroupedRecents-debug**. Isinya zip berisi `app-debug.apk`.

## Setup di HP

1. Buka aplikasi **Grouped Recents**.
2. Tap **Aktifkan di pengaturan Aksesibilitas**, lalu nyalakan "Grouped Recents".
   - Kalau tombolnya terkunci (Android 13+, terutama kalau APK di-install manual): buka **Info aplikasi → ⋮ → Izinkan setelan terbatas**, lalu coba lagi.
3. (Disarankan) Tap **Beri akses penggunaan**, supaya daftar aplikasi langsung terisi.
4. Buat grup dan pilih aplikasi untuk masing-masing grup.
5. Buka Recents seperti biasa.

## Cara pakai overlay

| Aksi | Hasil |
|---|---|
| Tap ikon | Pindah ke aplikasi itu |
| Tahan ikon | Hapus dari daftar (dan hentikan proses latarnya) |
| **Tutup** di judul grup | Tutup semua aplikasi di grup itu |
| **Tutup semua** | Tutup semuanya, lalu ke home |
| **Bawaan** | Sembunyikan overlay dan pakai Recents bawaan untuk sesi ini |
| Back / tap area kosong | Ke home |

## Keterbatasan

- Yang ditampilkan ikon aplikasi, bukan thumbnail layar. Aplikasi lain memang tidak diizinkan mengambil thumbnail.
- Satu aplikasi = satu item. Kalau aplikasinya punya beberapa jendela, yang terbuka adalah task terakhirnya.
- Recents bawaan sempat terlihat sekilas sebelum overlay muncul (sekitar 150 ms).
- Cara mendeteksi Recents berbeda di tiap merek HP. Kalau overlay tidak muncul, atau malah muncul di layar yang salah, nyalakan **Debug → Catat log deteksi**, buka Recents dan home beberapa kali, lalu salin log-nya. Penanda deteksinya ada di `GroupedRecentsService.kt` (`LAUNCHER_ID_MARKERS` dan `TEXT_MARKERS`).
- Beberapa HP (Xiaomi, Oppo, Vivo, dll.) suka mematikan layanan aksesibilitas di latar. Kalau begitu, matikan optimasi baterai untuk aplikasi ini.
