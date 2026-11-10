# PhoneLink untuk Wuling Cortez

Bahasa Indonesia · [English](README.en.md)

Tampilkan layar HP Android modern di headunit Wuling Cortez, dan kendalikan HP lewat sentuhan di layar mobil.

Aplikasi PhoneLink bawaan headunit hanya jalan di HP lama (sekitar Android 6 sampai 9), karena kode native-nya hanya 32-bit dan memakai bagian internal Android yang sudah dihapus di versi baru. Proyek ini menulis ulang aplikasinya dalam Java biasa dengan protokol USB yang sama, jadi headunit menganggapnya aplikasi asli. Tidak ada yang diubah di headunit.

> Proyek tidak resmi. Tidak berafiliasi dengan atau didukung oleh Wuling, SGMW, maupun LinkTech. Gunakan dengan risiko sendiri, dan jangan mengoperasikan HP saat menyetir.

## Unduh

Ambil `phonelink-v4.0.apk` dari [Releases](https://github.com/arymaulana/phonelink-wuling-cortez/releases), lalu pasang di HP.

## Yang sudah jalan

- Layar HP tampil di headunit 1024x600, dalam mode lanskap
- Ketuk dan geser di layar mobil untuk mengendalikan HP
- Tersambung lagi tanpa ditanya ulang kalau kabel dicolok kembali dalam 15 detik
- Suara lewat Bluetooth biasa yang dipasangkan ke mobil, bukan lewat aplikasi ini
- Tanpa izin internet: tidak ada data yang keluar dari HP selain sambungan USB ke mobil

Sudah dites di:

| HP | Android |
|---|---|
| TECNO LJ7 | 15 |
| Xiaomi 14T (HyperOS) | 14 |

## Kebutuhan

- Android 8.0 atau lebih baru
- **USB debugging** aktif. Headunit memakai ADB untuk membuka aplikasi, jadi tanpa ini tidak akan tersambung.
- Kabel USB yang bisa transfer data

## Cara pakai

Buka PhoneLink dan ikuti tiga langkah di layar:

1. **Allow landscape**: izinkan aplikasi memutar HP ke posisi lanskap agar pas di layar mobil.
2. **Turn on touch**: di Aksesibilitas, aktifkan **PhoneLink touch**. Kalau tombolnya abu-abu, buka Info aplikasi PhoneLink, buka menunya, lalu pilih **Izinkan setelan terbatas**.
3. **Start mirroring**: colok ke mobil. PhoneLink terbuka sendiri. Pilih **Seluruh layar** kalau ditanya, lalu ketuk **Mulai sekarang**.

Android meminta izin rekam layar setiap kali tersambung baru. Itu aturan Android yang tidak bisa dilewati aplikasi.

### Xiaomi, Redmi, POCO

Aktifkan **Mulai otomatis** (Autostart) untuk PhoneLink di Setelan › Aplikasi. Tanpa ini, sistem bisa menghentikan layanan sentuh dan tidak menyalakannya lagi.

### Opsional: sentuh hanya aktif saat tercolok

Beberapa aplikasi bank menolak dibuka selama ada layanan aksesibilitas yang aktif. Dengan komputer, jalankan ini sekali:

```bash
adb shell pm grant com.phonelink.phonelinkserver android.permission.WRITE_SECURE_SETTINGS
```

Setelah itu PhoneLink menyalakan sentuh saat dicolok dan mematikannya saat dicabut. Izin ini membuat aplikasi bisa mengubah setelan sistem yang dilindungi, jadi berikan hanya ke build yang kamu percaya. Di Xiaomi, aktifkan dulu **USB debugging (Setelan keamanan)**.

## Batasan

- Maksimal sekitar 20 pembaruan layar penuh per detik, karena headunit hanya menerima gambar tanpa kompresi. Geser peta cepat bisa terasa lambat.
- Aplikasi yang melindungi tampilannya, seperti Netflix atau aplikasi bank, bisa tampil hitam.
- Gambar ditarik 17% lebih tinggi dari bentuk aslinya agar pita kosong lebih kecil. Ubah `STRETCH_PERCENT` di `MirrorService.java` untuk mengaturnya.
- Banyak layar beranda HP tidak mendukung lanskap, jadi sebagian layar beranda bisa hitam dan tidak bisa diketuk. Aplikasi lain normal. Launcher yang mendukung lanskap bisa mengatasinya.

## Build

Windows, dengan Android SDK terpasang lewat Android Studio:

- Build tools 36.1.0 dan platform `android-36`
- JDK bawaan Android Studio

```bash
app\build.cmd
```

Perintah ini menjalankan pengecekan protokol, lalu menghasilkan `app\build\phonelink.apk`. Kalau `app\test.keystore` belum ada, file itu dibuat otomatis. Simpan baik-baik: APK yang ditandatangani dengan kunci lain tidak bisa memperbarui yang sudah terpasang, dan harus di-uninstall dulu.

## Cara kerja

Headunit berperan sebagai aksesori USB (`LinkTech` / `LinkServer`) sekaligus host ADB. Headunit menjalankan `am start` untuk membuka aplikasi, lalu mengubah USB ke mode aksesori. Aplikasi menjawab handshake, merekam layar dengan `MediaProjection`, dan mengirim area yang berubah sebagai gambar RGB565 mentah. Sentuhan datang sebagai koordinat dan diputar ulang lewat layanan aksesibilitas.

Detail protokol (bahasa Inggris): [docs/phonelink-protocol.md](docs/phonelink-protocol.md).

## Struktur proyek

```
app/
  AndroidManifest.xml
  build.cmd                 skrip build, tanpa Gradle
  check/ProtocolCheck.java  pengecekan yang dijalankan saat build
  res/                      ikon, teks, filter aksesori
  src/com/phonelink/phonelinkserver/
    MainActivity.java       layar setup
    MirrorService.java      sambungan USB dan rekam layar
    Protocol.java           format pesan, hitungan piksel dan sentuhan
    TouchService.java       sentuhan dari layar mobil
    Illustration.java       gambar di layar setup
docs/
  phonelink-protocol.md     catatan protokol hasil reverse engineering
```

## Lisensi

MIT. Lihat [LICENSE](LICENSE).

---

arydev.id
