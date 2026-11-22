# LiveSub AI v1.1 — Gemini Live API WebSocket (< 1 Detik)

Aplikasi penerjemah subtitle video real-time lintas aplikasi Android dengan **Gemini Live API WebSocket** (`BidiGenerateContent`), efek **Solid Gelap / Glass Hitam / Glass Bening**, dan ikon **Themed Adaptive (Material You)**.

## Pembaruan v1.1 (Ultra-Rendah Latensi)

- **Gemini Live API WebSocket (`GeminiLiveWebSocketClient.kt`):** Streaming frame PCM 16kHz per **100 ms** secara real-time tanpa menunggu potongan 3 detik.
- **Auto-Discovery Model Live (`auto`):** Otomatis memindai model `bidiGenerateContent` aktif di API Key Anda dan otomatis menegosiasikan modality `TEXT` atau `AUDIO + outputAudioTranscription`.
- **Auto-Fallback ke REST Cepat Paralel:** Jika server Live penuh atau belum aktif di akun, otomatis pindah ke pipeline REST paralel (`Channel` non-blocking, chunk 1,8 detik + early VAD flush, `thinkingBudget: 0`, dan auto-fallback `gemini-flash-lite-latest` → `gemini-flash-latest`).

## Cara Build APK di GitHub

1. Ekstrak ZIP ini, lalu timpa isi repository GitHub Anda dengan isi folder `LiveSubAI/`.
2. Push ke branch `main` (atau unggah lewat web GitHub).
3. Buka tab **Actions** → tunggu **Build LiveSub AI APK** selesai → unduh **LiveSubAI-debug-apk** di bagian **Artifacts**.
4. Instal `app-debug.apk` di HP, masukkan **Gemini API Key** di tab **Tampilan & AI** (biarkan Model Live WebSocket di `auto`), lalu klik **Aktifkan overlay live**.
