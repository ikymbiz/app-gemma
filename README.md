# gemma-bridge

A local OpenAI-compatible API gateway in front of [llama.cpp](https://github.com/ggerganov/llama.cpp),
designed to run on **PC (Windows / macOS / Linux)** and **Android**, exposing the same
endpoint shape and API-key authentication on both.

```
   ┌──────────────────────────────────────────────────────────┐
   │  OpenAI-compatible HTTP API (loopback only by default)   │
   │    POST /v1/chat/completions   (SSE streaming supported) │
   │    GET  /v1/models                                       │
   │    Auth: Authorization: Bearer gma_live_xxx              │
   └──────────────────────────────────────────────────────────┘
                ▲                              ▲
       ┌────────┘                              └────────┐
       │                                                │
  ┌────┴─────┐                                    ┌─────┴────┐
  │   PC     │                                    │ Android  │
  │ Python   │                                    │ Kotlin   │
  │ proxy +  │                                    │ Service +│
  │ llama-   │                                    │ Ktor +   │
  │ server   │                                    │ llama.cpp│
  │          │                                    │  (JNI)   │
  └────┬─────┘                                    └─────┬────┘
       │                                                │
       └──────────► (optional) Cloudflare Tunnel ◄──────┘
                          (only when explicitly opened)
```

## Design goals

1. **Same API surface** on PC and Android. Clients (CLI, browser, IDE plugin, web service)
   set `baseURL` + `apiKey` once and don't care which device hosts the model.
2. **Loopback by default**. Listens on `127.0.0.1` only. Internet exposure is an explicit,
   reversible action via a tunnel.
3. **User-supplied model**. Gemma `.gguf` files live in `models/`. They are downloaded
   by the user — nothing is bundled in the binary or APK.
4. **API key as the single auth primitive**. Same key authenticates local apps, browser
   pages, and (when a tunnel is opened) internet-deployed tools.

## Layout

```
gemma-bridge/
├── README.md                this file
├── .gitignore               excludes model files and build artefacts
├── docs/
│   └── API.md               endpoint reference
├── models/                  user-supplied GGUF files live here
│   └── README.md            download instructions
├── pc/                      Python proxy for desktops
│   ├── README.md
│   ├── gemma_bridge.py      main script
│   ├── requirements.txt
│   └── config.example.toml
├── android/                 Kotlin Android app
│   ├── README.md
│   ├── settings.gradle.kts
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/...
└── client/
    └── test.html            browser test page
```

## Quick start

The project assumes you already have:

- Python 3.10+
- `llama.cpp` installed (`winget install llama.cpp` on Windows, `brew install llama.cpp`
  on macOS, or a build from <https://github.com/ggml-org/llama.cpp/releases>)
- A Gemma 4 GGUF file in `models/` (see `models/README.md` for download links)

### Windows (PowerShell)

```powershell
cd gemma-bridge

# 1. Start llama-server on the model in ./models/
llama-server -m .\models\gemma-4-E2B-it-Q4_K_M.gguf --host 127.0.0.1 --port 8081 --ctx-size 8192

# 2. In a second PowerShell window: install Python deps once
cd pc
pip install -r requirements.txt

# 3. Issue an API key (one-time setup, copy the value!)
python gemma_bridge.py key add --name laptop

# 4. Start the bridge
python gemma_bridge.py serve
# → listening on http://127.0.0.1:11434
```

### macOS / Linux

```bash
cd gemma-bridge

# 1. Start llama-server
llama-server -m ./models/gemma-4-E2B-it-Q4_K_M.gguf --host 127.0.0.1 --port 8081 --ctx-size 8192

# 2. Bridge setup (in another shell)
cd pc
pip install -r requirements.txt
python gemma_bridge.py key add --name laptop
python gemma_bridge.py serve
```

### Android

Open `android/` in Android Studio, build, run. See `android/README.md` for the llama.cpp
integration paths (proxy-to-local-llama-server for first test, JNI native build for
production).

### Smoke test

The simplest check — uses a local key automatically, no copy-paste needed:

```powershell
python gemma_bridge.py chat "こんにちは"
```

Or open `client/test.html` in a browser for an interactive page (paste the base URL and
API key once, then chat).

## Which model file?

See `models/README.md` for the full table. The 30-second version:

- **`gemma-4-E2B-it-Q4_K_M.gguf`** (~2 GB, needs 6 GB RAM) — start here.
- **`gemma-4-E4B-it-Q4_K_M.gguf`** (~4 GB, needs 8 GB RAM) — upgrade once E2B works.
- Anything bigger (26B-A4B, 31B) is only realistic on workstations or servers.

## Exposing to the internet

For internet-deployed tools (Open WebUI, Continue.dev, your own services) to reach
`gemma-bridge`, run a tunnel as a **separate process** — the bridge itself never binds
anything but loopback:

```powershell
# Cloudflare quick tunnel (no account required for testing)
cloudflared tunnel --url http://127.0.0.1:11434
```

The tunnel prints a public HTTPS URL. Use it as `baseURL` + the same `gma_live_*` key
on the remote side. Stop the tunnel when you no longer need it.

## License

Code in this repository is provided as-is. The Gemma weights you run through it are
governed by the [Gemma Terms of Use](https://ai.google.dev/gemma/terms) — using
`gemma-bridge` does not modify that. If you expose the API to the internet via a tunnel,
**you** are the operator on record for that endpoint.
