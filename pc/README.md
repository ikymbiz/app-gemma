# gemma-bridge (PC)

OpenAI-compatible local API for desktops, in front of `llama-server`. Adds API-key
authentication and a stable endpoint shape that matches the Android side bit-for-bit.

## Prerequisites

- **Python** 3.10 or newer
- **llama.cpp** (`llama-server` on your PATH). Install options:
  - Windows: `winget install llama.cpp`
  - macOS: `brew install llama.cpp`
  - Linux / any: prebuilt zips at <https://github.com/ggml-org/llama.cpp/releases>
- A **Gemma 4 GGUF** file under `../models/` (see `../models/README.md`)

Recommended starter model for a laptop with 16 GB RAM:
`gemma-4-E2B-it-Q4_K_M.gguf` (~2 GB).

## Install Python deps

```powershell
# Windows
pip install -r requirements.txt
```

```bash
# macOS / Linux
pip install -r requirements.txt
```

## Run llama-server

`gemma-bridge` does not start `llama-server` for you; run it as a separate process so
you control the model, quantization, and runtime flags.

### Windows (PowerShell)

```powershell
# From the project root (the directory that contains `models\` and `pc\`)
llama-server -m .\models\gemma-4-E2B-it-Q4_K_M.gguf `
             --host 127.0.0.1 --port 8081 `
             --ctx-size 8192
```

GPU offload (Intel Iris Xe / AMD / NVIDIA via Vulkan or CUDA build):

```powershell
llama-server -m .\models\gemma-4-E2B-it-Q4_K_M.gguf `
             --host 127.0.0.1 --port 8081 `
             --ctx-size 8192 `
             -ngl 99
```

`-ngl 99` tells llama.cpp to put as many layers as possible on the GPU. If VRAM is
insufficient it falls back to CPU automatically.

### macOS / Linux

```bash
llama-server -m ./models/gemma-4-E2B-it-Q4_K_M.gguf \
             --host 127.0.0.1 --port 8081 \
             --ctx-size 8192 \
             -ngl 99      # Metal on macOS, CUDA / Vulkan on Linux
```

When you see `server is listening on http://127.0.0.1:8081`, the model is ready.

## Configure and run gemma-bridge

```powershell
python gemma_bridge.py config init           # write default config
python gemma_bridge.py key add --name laptop # issue an API key — copy it now!
python gemma_bridge.py serve                 # → http://127.0.0.1:11434
```

Default ports:

| Process       | Port  | Binding   |
|---------------|-------|-----------|
| `llama-server`  | 8081  | 127.0.0.1 |
| `gemma-bridge`  | 11434 | 127.0.0.1 |

Only `gemma-bridge` should be the surface that clients talk to. `llama-server` stays
behind it, unprotected, on loopback.

## Smoke test

The fastest way to confirm everything works (no copy-pasting keys):

```powershell
python gemma_bridge.py chat "こんにちは"
```

This reads a local API key from `keys.json` automatically, streams the model's reply
to your terminal, and exits. Useful for quick sanity checks.

To pick a specific key by name:

```powershell
python gemma_bridge.py chat "hello" --name laptop
```

### Curl / Invoke-RestMethod

If you want to drive the API yourself, the cleanest PowerShell approach is
`Invoke-RestMethod` — it avoids JSON escaping headaches:

```powershell
$key = "gma_live_xxxxxxxx"   # or use $env:GEMMA_BRIDGE_KEY

Invoke-RestMethod `
    -Uri http://127.0.0.1:11434/v1/chat/completions `
    -Method Post `
    -Headers @{ "Authorization" = "Bearer $key" } `
    -ContentType "application/json; charset=utf-8" `
    -Body (@{
        model    = "gemma"
        messages = @(@{ role = "user"; content = "こんにちは" })
    } | ConvertTo-Json -Depth 5)
```

For `curl.exe`, the safest pattern is to put the JSON body in a file:

```powershell
@'
{"model":"gemma","messages":[{"role":"user","content":"hi"}]}
'@ | Out-File -Encoding utf8 -NoNewline body.json

curl.exe http://127.0.0.1:11434/v1/chat/completions `
  -H "Authorization: Bearer $key" `
  -H "Content-Type: application/json" `
  --data-binary "@body.json"
```

### bash

```bash
KEY=gma_live_xxxxxxxx

curl http://127.0.0.1:11434/v1/chat/completions \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma","messages":[{"role":"user","content":"hi"}]}'
```

### Browser

For an interactive page, open `../client/test.html` in a browser, paste the base URL and
key into the sidebar (saved in localStorage for next time), and chat.

## Key management

```powershell
python gemma_bridge.py key list
python gemma_bridge.py key add --name "open-webui"
python gemma_bridge.py key revoke gma_live_xxxxxxxx
```

Key store location:

- Linux / BSD: `~/.config/gemma-bridge/keys.json`
- macOS:    `~/Library/Application Support/gemma-bridge/keys.json`
- Windows:  `%APPDATA%\gemma-bridge\keys.json`

File mode is set to `0600` where supported. For stronger protection, replace this with
your OS keychain (macOS Keychain / Windows Credential Manager / libsecret).

## Exposing to the internet (optional)

The bridge binds to `127.0.0.1`. Run a tunnel as a separate process when you want
internet-deployed tools to use the same API:

```powershell
# Cloudflare quick tunnel (no account needed for testing)
cloudflared tunnel --url http://127.0.0.1:11434

# or ngrok
ngrok http 11434
```

The tunnel prints a public URL. Plug that URL + your `gma_live_*` key into the remote
tool's OpenAI configuration and you're done. The bridge sees only loopback traffic; the
tunnel handles TLS termination.

**Stop the tunnel when you don't need it.** The API key is your only barrier.

## Run as a background service

### Windows (Task Scheduler)

Trigger "At log on", action:

```
pythonw.exe C:\path\to\gemma-bridge\pc\gemma_bridge.py serve
```

`pythonw.exe` (note the `w`) runs without opening a console window.

### macOS (launchd)

Save as `~/Library/LaunchAgents/com.gemma-bridge.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.gemma-bridge</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/python3</string>
    <string>/absolute/path/to/gemma_bridge.py</string>
    <string>serve</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/com.gemma-bridge.plist
```

### Linux (systemd --user)

Save as `~/.config/systemd/user/gemma-bridge.service`:

```ini
[Unit]
Description=gemma-bridge local API

[Service]
ExecStart=/usr/bin/python3 /absolute/path/to/gemma_bridge.py serve
Restart=on-failure

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now gemma-bridge
```

## Troubleshooting

### `'llama-server' is not recognized` (Windows)
The PATH update from `winget install` only applies to **new** PowerShell sessions —
close and reopen it.

### `failed to open GGUF file ... (No such file or directory)`
The path in `-m` does not exist. Check with `dir .\models\` (Windows) or
`ls ./models/` and copy the exact filename. Spaces in paths need quoting:
`-m ".\models\name with spaces.gguf"`.

### `error loading model: unknown architecture 'gemma3' / 'gemma4'`
Your `llama-server` is too old for that Gemma version. Update with
`winget upgrade llama.cpp` or download a newer prebuilt from GitHub Releases.

### Bridge starts but `/v1/chat/completions` returns 503
The bridge is reaching for `upstream.base_url` (default `http://127.0.0.1:8081`) and
not finding `llama-server` there. Make sure llama-server is running and the port
matches the `[upstream]` setting in your config.
