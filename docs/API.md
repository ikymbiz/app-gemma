# API reference

`gemma-bridge` implements a subset of the OpenAI API. The same shape applies on PC and
Android — only the host changes.

## Authentication

All endpoints except `/health` require:

```
Authorization: Bearer gma_live_xxxxxxxxxxxxxxxxxxxxxxxx
```

Keys are issued locally on each device. Keys generated on the PC do **not** work on the
Android side and vice versa — they are independent stores.

## Endpoints

### `GET /health`

Liveness probe. No auth required.

```json
{"ok": true, "upstream": "http://127.0.0.1:8081"}
```

### `GET /v1/models`

Lists the models the upstream `llama-server` is currently serving.

```json
{
  "object": "list",
  "data": [
    {"id": "gemma-4-E2B-it", "object": "model"}
  ]
}
```

### `POST /v1/chat/completions`

OpenAI-compatible chat endpoint. Request:

```json
{
  "model": "gemma",
  "messages": [
    {"role": "system", "content": "You are concise."},
    {"role": "user",   "content": "What is the Riemann hypothesis?"}
  ],
  "temperature": 0.7,
  "max_tokens": 512,
  "stream": false
}
```

Non-streaming response:

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "..."},
      "finish_reason": "stop"
    }
  ]
}
```

Streaming (`"stream": true`) returns `text/event-stream` with chunks:

```
data: {"choices":[{"delta":{"content":"The "}}]}

data: {"choices":[{"delta":{"content":"Riemann "}}]}

data: [DONE]
```

## Client examples

### JavaScript (browser or Node)

```javascript
const res = await fetch("http://127.0.0.1:11434/v1/chat/completions", {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    "Authorization": "Bearer gma_live_xxxxxxxx"
  },
  body: JSON.stringify({
    model: "gemma",
    messages: [{ role: "user", content: "hi" }]
  })
});
```

### OpenAI SDK

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:11434/v1",
    api_key="gma_live_xxxxxxxx",
)
resp = client.chat.completions.create(
    model="gemma",
    messages=[{"role": "user", "content": "hi"}],
)
```

### curl

```bash
curl http://127.0.0.1:11434/v1/chat/completions \
  -H "Authorization: Bearer gma_live_xxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma","messages":[{"role":"user","content":"hi"}],"stream":true}'
```

## When exposed over a tunnel

Once a tunnel (Cloudflare Tunnel / ngrok) is pointing at the local port, the same
endpoints become available at `https://<public-host>/v1/...` with the same API key.
Internet-deployed tools (Open WebUI, Continue.dev, your own services) only need:

- `baseURL`: the tunnel URL + `/v1`
- `apiKey`: any valid `gma_live_*` issued on the host device

The bridge itself never sees `https`; the tunnel terminates TLS and forwards plaintext
to `127.0.0.1`.
