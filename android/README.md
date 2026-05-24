# gemma-bridge (Android)

Android app exposing the same OpenAI-compatible API as the PC side, listening on
`127.0.0.1:11434`. Two engines are supported:

- **Proxy** — forwards to an external OpenAI-compatible server (Termux + llama-server
  on the device, or a PC reachable from the phone). Easiest to test.
- **MediaPipe (on-device)** — runs Gemma natively on the phone via Google's MediaPipe
  LLM Inference API. Requires a `.task` model file.

The engine is selectable at runtime in the app's settings screen.

## Build options

You can produce the APK in three ways. **Option A (GitHub Actions) is the easiest** —
no local toolchain installation required.

### Option A — GitHub Actions (recommended)

The repository contains `.github/workflows/android-build.yml` which builds a debug APK
on every push and on demand. To use it:

1. Push this repository to GitHub (your own fork or a fresh repo).
2. Open the **Actions** tab on GitHub. Approve workflow runs if prompted.
3. Either push a commit to `main`/`master`, or open **Actions → Android build →
   Run workflow** to trigger it manually.
4. Wait ~5–8 minutes for the build to finish.
5. Open the completed run → scroll to the **Artifacts** section at the bottom →
   download `gemma-bridge-debug-apk.zip`.
6. Unzip → you get `app-debug.apk`. Transfer it to the phone (USB, Drive, email)
   and tap to install. You'll need to enable "Install unknown apps" for whichever
   file manager you use.

That's it — no Android Studio, no SDK install, no Gradle setup on your machine.

### Option B — Android Studio (if you want to develop locally)

## Setup (step by step)

### 1. Install Android Studio

Download from <https://developer.android.com/studio>. The latest stable
(Iguana or newer) is required for AGP 8.5. Choose default install — it will pull the
SDK and command-line tools automatically.

### 2. Prepare a phone (or emulator)

**Physical phone (recommended for real performance):**
- Enable Developer Options: Settings → About phone → tap "Build number" 7 times.
- Settings → System → Developer options → enable **USB debugging**.
- Connect via USB. When prompted on the phone, allow the debugging fingerprint.

**Emulator (works but slow for LLMs):**
- In Android Studio: Device Manager → Create Device → pick Pixel 7 → System Image API 34.

### 3. Open the project

Android Studio → Open → select the `android/` folder of `gemma-bridge`. Wait for the
Gradle sync to finish (first run downloads ~500 MB).

### 4. Get a model

You have two paths depending on the engine you'll use.

#### Path A — Proxy engine (no on-device model needed)

If you only want the proxy engine for now, skip the download. Run `llama-server` in
Termux on the phone, or point at your PC's `gemma-bridge` over Wi-Fi.

#### Path B — MediaPipe engine

Download a `.task` Gemma bundle to your **phone**. Recommended:

- `gemma-3n-E2B-it-int4.task` (~3 GB) — for phones with 6 GB+ RAM
- `gemma-3n-E4B-it-int4.task` (~5 GB) — for phones with 8 GB+ RAM

Source: <https://huggingface.co/litert-community> (look for `Gemma-3n-E2B-it-litert`
or similar). You will need a Hugging Face account and to accept Google's Gemma terms.

The file must end up on the phone's storage (typically `/sdcard/Download/`). You can
either download directly on the phone, or `adb push` from the PC:

```powershell
adb push gemma-3n-E2B-it-int4.task /sdcard/Download/
```

### 5. Build and install

In Android Studio: select the connected device in the toolbar, click **Run** (▶). The
APK will install and launch.

Or from a terminal:

```powershell
cd gemma-bridge\android
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.gemmabridge/.MainActivity
```

### 6. Configure inside the app

Once the app launches:

1. **Engine**: pick "Proxy" (uses external server) or "MediaPipe (on-device)".
2. If MediaPipe, tap **Pick .task model file** and choose the file you downloaded.
   It will be copied into app-private storage (this takes ~30 seconds).
3. Tap **Generate new key**. Copy the displayed key.
4. Tap **Start**. The status changes to "Running on http://127.0.0.1:11434".
5. A persistent notification appears. The service stays alive while you switch to
   other apps.

### 7. Use it

Open Chrome (or any browser) on the phone and visit a page that knows how to talk to
the API. Easiest: serve the `client/test.html` from anywhere reachable, paste the
base URL `http://127.0.0.1:11434` and the API key, and chat.

Or `adb forward` the port to your PC and use the PC's tools:

```powershell
adb forward tcp:11434 tcp:11434
# Now http://127.0.0.1:11434 on the PC hits the phone's bridge.
```

## Architecture recap

```
┌───────────────────────── Android app ─────────────────────────┐
│                                                               │
│  MainActivity (Compose)                                       │
│    └─ engine selector, model picker, key management           │
│                                                               │
│  GemmaService (Foreground Service)                            │
│    └─ ApiServer (Ktor on 127.0.0.1:11434)                     │
│           └─ LlamaEngine ──┬─ ProxyEngine                     │
│                            └─ MediaPipeEngine                 │
│                               (Google MediaPipe LLM Inference)│
│                                                               │
│  KeyManager (EncryptedSharedPreferences)                      │
└───────────────────────────────────────────────────────────────┘
```

## Performance expectations

Rough numbers on a Pixel 8 (Tensor G3, 8 GB RAM) with Gemma 3n E2B int4:

- Cold load: 10–30 seconds
- First token: 2–5 seconds
- Steady-state throughput: 10–20 tokens/sec

On older devices (4 GB RAM, 2020-era chipsets) expect 2–3× slower or out-of-memory
crashes. Use the E2B model rather than E4B if memory is tight.

## Internet exposure

For internet-deployed tools to reach the Android instance, start a tunnel pointing at
`127.0.0.1:11434`:

- `cloudflared` ARM64 binary, run from Termux
- `tailscale` Android app with Funnel enabled

The bridge itself only binds to loopback — internet exposure is always an explicit,
user-visible action.

## Battery and lifecycle

The service runs as `foregroundServiceType="specialUse"` (Android 14+). On many vendor
ROMs the OS will still aggressively kill background processes. For reliable operation:

1. Direct the user to **Settings → Apps → Gemma Bridge → Battery → Unrestricted**.
2. Stop the service when no longer needed — the persistent notification has a tap
   target to open the app, from which you can hit Stop.

## Permissions

Declared in `AndroidManifest.xml`:

- `INTERNET` — required for any socket bind, even loopback
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`
- `POST_NOTIFICATIONS` — 13+, for the persistent status notification
- `WAKE_LOCK` — optional, for long-running generations

## Troubleshooting

### Gradle sync fails on first open
Check `File → Project Structure → SDK Location`. Android Studio sometimes needs a
manual path to a JDK 17 installation.

### `INSTALL_FAILED_NO_MATCHING_ABIS`
The phone is too old (32-bit only). `arm64-v8a` is required — phones from 2017 onward
are fine.

### MediaPipe engine fails to initialize
- Confirm the `.task` file is fully downloaded (size matches Hugging Face).
- Some `.task` files are tied to a specific MediaPipe version. If you see "unsupported
  model version", update the `tasks-genai` dependency in `app/build.gradle.kts`.
- Watch `adb logcat | grep -i gemma` for the actual error.

### Status notification doesn't appear (Android 13+)
The first launch should prompt for notification permission. If you denied it, go to
Settings → Apps → Gemma Bridge → Notifications and allow.

### App killed in background
Some OEMs (Xiaomi, OPPO, vivo, Samsung in default mode) aggressively kill foreground
services. Disable battery optimization for the app and/or pin it to recents.
