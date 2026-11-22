# LiveSub AI — Android 17 (API 37)

Aplikasi penerjemah subtitle video real-time lintas aplikasi dengan efek **Solid Gelap**, **Glass Hitam**, dan **Glass Bening** + ikon **Themed Adaptive (Material You)**.

## Cara Build APK di GitHub (Tanpa Android Studio)

1. Ekstrak file ZIP ini ke komputer/HP Anda.
2. Buat repository baru di **GitHub** (Public atau Private).
3. Unggah seluruh isi folder `LiveSubAI` (pastikan file `.github/workflows/build.yml` ikut terunggah) ke branch `main`:
   ```bash
   cd LiveSubAI
   git init
   git add .
   git commit -m "Initial LiveSub AI Android starter"
   git branch -M main
   git remote add origin https://github.com/USERNAME/NAMA-REPO.git
   git push -u origin main
   ```
4. Buka tab **Actions** di repository GitHub → tunggu workflow **Build LiveSub AI APK** selesai (~2–3 menit).
5. Klik run yang sudah hijau → buka bagian **Artifacts** di bawah → unduh **LiveSubAI-debug-apk** → ekstrak dan pasang `app-debug.apk` di HP Android Anda.

> **Tips:** Anda juga dapat menjalankan build manual lewat tab **Actions → Build LiveSub AI APK → Run workflow** dan memilih target API (`35`, `36`, atau `37`).

## Fitur Utama

- **Themed Adaptive Icon (Material You):** Dilengkapi layer `<monochrome>` di `mipmap-anydpi-v26` agar ikon otomatis menyesuaikan warna tema launcher Android.
- **3 Gaya Kotak Subtitle:** Solid Gelap, Glass Hitam (`setBlurBehindRadius`), dan Glass Bening + 3 pelindung teks (Halo Bayangan, Outline Tipis, Scrim Mikro).
- **AudioPlaybackCapture + Fallback Mikrofon:** Menangkap audio video internal (YouTube, TikTok, Reels, browser) dan otomatis menawarkan tombol **Pakai mikrofon** jika aplikasi ber-DRM memblokir audio.
- **Gemini AI Siap Pakai:** Masukkan Gemini API Key di tab **Tampilan & AI** di dalam aplikasi (tersimpan lokal di HP, aman tidak masuk ke GitHub).
- **Adaptive Layout (600dp+):** Mendukung ponsel, split-screen, dan foldable/tablet tanpa mengunci orientasi layar.
