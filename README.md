# BGAP Speaker (Android, Kotlin murni)

Aplikasi kontrol untuk firmware ESP32 + TAS5805M **2.9.26 tanpa OTA**. Jetpack Compose, tanpa Java/XML layout,
tanpa library pihak ketiga. Terhubung lewat **Bluetooth Classic SPP** (RFCOMM, UUID 00001101-…) — protokol teks yang sama
dengan `docs/PROTOCOL.md` di firmware.

## Build lewat GitHub Actions
1. Buat repo baru, unggah isi zip ini apa adanya (termasuk folder `.github`).
2. Tab **Actions → Build Android APK → Run workflow** (atau push ke `main`).
3. Unduh artifact **bgap-speaker-debug-apk** → pasang `app-debug.apk` di ponsel.

Tidak perlu gradle wrapper: workflow memasang Gradle 8.9 sendiri. Lokal: `gradle :app:assembleDebug` (JDK 17, Android SDK 34).

## Pakai
1. Pairing `BGAP-SPEAKER` di Pengaturan Bluetooth Android.
2. Buka app → izinkan Bluetooth → tab **Koneksi** → **Sambung**. App mengingat perangkat terakhir dan menyambung otomatis.
3. App mengirim `dump`; UI selalu mencerminkan state firmware (nilai bawaan tidak pernah didorong ke speaker).

Tab: Koneksi · Kontrol (mode, volume, crossover, gain, loudness sub, bass virtual) · EQ (15 band, L/R + Center/Sub di mode 3)
· DSP (mixer kanal: preset + lebar stereo + LL/LR/RL/RR, clipper chip; di mode 4 ditambah wireless: scan/pair, trim, voicing, diagnostik) · Monitor (level meter chip) · Diagnosa (fault, caps, terminal, simpan/hapus/reboot).

## Cara kerja sinkron
- Slider: perintah digabung dan dikirim 120 ms setelah geseran terakhir; toggle/chip langsung.
- Setelah tiap perubahan app menunggu ±0,8 dtk lalu meminta `status` agar UI mengikuti nilai sebenarnya (termasuk bila firmware menolak dengan `ERR …`).
- Balasan status diabaikan selama 0,7 dtk setelah perubahan lokal supaya slider tidak "melompat".

## Sengaja tidak ada
- **OTA** (dihapus di app dan firmware). Update firmware lewat USB.
- Setelan modulasi/switching/PBTL (`modset`, `dacset`): salah wiring bisa merusak amplifier — pakai terminal bila perlu.

## Struktur
`Protocol.kt` model + parser + pembangun perintah (diuji di `ProtocolTest`) · `SppLink.kt` transport · `BgapViewModel.kt` logika
· `Screens.kt` UI · `Theme.kt` 4 tema (Gelap, Terang, AMOLED, Nord).
