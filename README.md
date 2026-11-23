# LiveSub AI v2.0 — Arsitektur 2 Mode (Hybrid Groq/Cloudflare + Full Offline)

Penerjemah subtitle video real-time lintas aplikasi Android dengan pemisahan total 3 tahap (**Perekam Audio → STT → Translate Offline ML Kit < 50 ms**).

## 2 Mode Pilihan

1. **Mode Hybrid (Cloud STT + Translate Offline < 50 ms):**
   - Mendukung **Groq Console** (`whisper-large-v3-turbo`) dan **Cloudflare Workers AI** (`@cf/openai/whisper-large-v3-turbo`).
   - Jika kedua kredensial diisi, otomatis saling *failover* saat terkena limit.
   - Menggunakan potongan audio tumpang-tindih (1,55 detik + 350 ms overlap) pada 2 worker paralel agar ucapan cepat tidak terpotong.
   - Hasil transkrip langsung diterjemahkan di HP lewat **Google ML Kit Translate** dalam 15–50 ms.

2. **Mode Full Offline (Vosk Streaming STT + Translate Offline < 50 ms):**
   - Streaming frame PCM 50 ms secara langsung ke model **Vosk** lokal — teks keluar kata per kata (~0,4 detik), 0% internet setelah diunduh.
   - Tersedia **16 bahasa model suara STT** + **20 bahasa kamus terjemahan ML Kit** yang dapat diunduh & dihapus langsung dari tab **Paket Offline**.

## Build APK di GitHub

1. Ekstrak ZIP ini lalu unggah seluruh isi folder `LiveSubAI/` ke branch `main` repository GitHub Anda (hapus file Gemini lama jika ada di folder `ai/`).
2. Buka tab **Actions** → tunggu **Build LiveSub AI APK** selesai → unduh artifact **LiveSubAI-debug-apk**.
