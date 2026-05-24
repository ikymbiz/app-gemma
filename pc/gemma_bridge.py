#!/usr/bin/env python3
"""
gemma-bridge: a thin OpenAI-compatible API gateway that sits in front of
llama-server (from llama.cpp), adding API-key authentication and a stable
endpoint shape for clients.

Run llama-server separately, e.g.:

    llama-server -m ./gemma-3-4b-it-Q5_K_M.gguf --host 127.0.0.1 --port 8081

Then:

    python gemma_bridge.py key add --name laptop
    python gemma_bridge.py serve
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import secrets
import sys
import time
from pathlib import Path
from typing import Optional

import httpx
import uvicorn
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse

try:
    import tomllib  # Python 3.11+
except ModuleNotFoundError:  # pragma: no cover
    import tomli as tomllib  # type: ignore


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

def _config_dir() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", str(Path.home())))
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config")))
    return base / "gemma-bridge"


CONFIG_DIR = _config_dir()
CONFIG_DIR.mkdir(parents=True, exist_ok=True)
CONFIG_FILE = CONFIG_DIR / "config.toml"
KEYS_FILE = CONFIG_DIR / "keys.json"


DEFAULT_CONFIG = """\
# gemma-bridge configuration

[listen]
# Bind to loopback only. Use Cloudflare Tunnel / ngrok for internet exposure.
host = "127.0.0.1"
port = 11434

[upstream]
# Where your llama-server is listening. Start it separately, for example:
#
#   Windows:
#     llama-server -m .\\models\\gemma-4-E2B-it-Q4_K_M.gguf --host 127.0.0.1 --port 8081
#
#   macOS / Linux:
#     llama-server -m ./models/gemma-4-E2B-it-Q4_K_M.gguf --host 127.0.0.1 --port 8081
base_url = "http://127.0.0.1:8081"

[cors]
# Origins allowed to call the API directly from a browser page.
# The API key is the real protection, so "*" is acceptable on loopback.
allow_origins = ["*"]
"""


# ---------------------------------------------------------------------------
# Config / keys
# ---------------------------------------------------------------------------

def ensure_config() -> None:
    if not CONFIG_FILE.exists():
        CONFIG_FILE.write_text(DEFAULT_CONFIG, encoding="utf-8")
        print(f"Wrote default config to {CONFIG_FILE}", file=sys.stderr)


def load_config() -> dict:
    ensure_config()
    with open(CONFIG_FILE, "rb") as f:
        return tomllib.load(f)


def load_keys() -> dict:
    if not KEYS_FILE.exists():
        return {}
    return json.loads(KEYS_FILE.read_text(encoding="utf-8"))


def save_keys(keys: dict) -> None:
    KEYS_FILE.write_text(json.dumps(keys, indent=2), encoding="utf-8")
    try:
        os.chmod(KEYS_FILE, 0o600)
    except OSError:
        pass  # Windows / unsupported FS


def new_key(name: str = "default") -> str:
    key = f"gma_live_{secrets.token_urlsafe(24)}"
    keys = load_keys()
    keys[key] = {"name": name, "created_at": int(time.time())}
    save_keys(keys)
    return key


def revoke(prefix: str) -> int:
    keys = load_keys()
    matches = [k for k in keys if k.startswith(prefix)]
    for k in matches:
        del keys[k]
    save_keys(keys)
    return len(matches)


def is_valid(key: str) -> bool:
    return key in load_keys()


# ---------------------------------------------------------------------------
# FastAPI application
# ---------------------------------------------------------------------------

def build_app(config: dict) -> FastAPI:
    upstream = config["upstream"]["base_url"].rstrip("/")
    origins = config.get("cors", {}).get("allow_origins", ["*"])

    app = FastAPI(title="gemma-bridge", version="0.1.0")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    def require_key(authorization: Optional[str]) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="missing bearer token")
        token = authorization[len("Bearer "):].strip()
        if not is_valid(token):
            raise HTTPException(status_code=401, detail="invalid api key")
        return token

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True, "upstream": upstream}

    @app.get("/v1/models")
    async def models(authorization: Optional[str] = Header(None)):
        require_key(authorization)
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                r = await client.get(f"{upstream}/v1/models")
                return JSONResponse(r.json(), status_code=r.status_code)
        except httpx.RequestError as e:
            raise HTTPException(status_code=503, detail=f"upstream unreachable: {e}")

    @app.post("/v1/chat/completions")
    async def chat_completions(
        request: Request,
        authorization: Optional[str] = Header(None),
    ):
        require_key(authorization)
        body = await request.body()
        try:
            payload = json.loads(body or b"{}")
        except json.JSONDecodeError:
            raise HTTPException(status_code=400, detail="invalid JSON body")

        is_stream = bool(payload.get("stream", False))

        if not is_stream:
            try:
                async with httpx.AsyncClient(timeout=300.0) as client:
                    r = await client.post(
                        f"{upstream}/v1/chat/completions",
                        content=body,
                        headers={"Content-Type": "application/json"},
                    )
                    return JSONResponse(r.json(), status_code=r.status_code)
            except httpx.RequestError as e:
                raise HTTPException(status_code=503, detail=f"upstream error: {e}")

        async def event_stream():
            timeout = httpx.Timeout(connect=10.0, read=None, write=10.0, pool=10.0)
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream(
                    "POST",
                    f"{upstream}/v1/chat/completions",
                    content=body,
                    headers={"Content-Type": "application/json"},
                ) as r:
                    async for chunk in r.aiter_raw():
                        yield chunk

        return StreamingResponse(event_stream(), media_type="text/event-stream")

    return app


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def cmd_serve(_args) -> None:
    cfg = load_config()
    app = build_app(cfg)
    host = cfg["listen"]["host"]
    port = int(cfg["listen"]["port"])

    if not load_keys():
        print("warning: no API keys configured. Run:", file=sys.stderr)
        print("    python gemma_bridge.py key add", file=sys.stderr)
        print(file=sys.stderr)

    print(f"gemma-bridge listening on http://{host}:{port}")
    print(f"  upstream: {cfg['upstream']['base_url']}")
    print(f"  config:   {CONFIG_FILE}")
    print(f"  keys:     {KEYS_FILE}")
    uvicorn.run(app, host=host, port=port, log_level="info")


def cmd_key_add(args) -> None:
    k = new_key(args.name)
    print(f"new key (name={args.name!r}):")
    print(f"  {k}")
    print()
    print("Store it now — only the prefix is shown again in `key list`.")


def cmd_key_list(_args) -> None:
    keys = load_keys()
    if not keys:
        print("(no keys)")
        return
    for k, meta in keys.items():
        masked = k[:18] + "..." + k[-4:]
        ts = time.strftime("%Y-%m-%d %H:%M", time.localtime(meta["created_at"]))
        print(f"  {masked}  name={meta['name']!r}  created={ts}")


def cmd_key_revoke(args) -> None:
    n = revoke(args.prefix)
    print(f"revoked {n} key(s)")


def cmd_config_init(_args) -> None:
    ensure_config()
    print(f"config at {CONFIG_FILE}")


def cmd_config_show(_args) -> None:
    ensure_config()
    print(CONFIG_FILE.read_text(encoding="utf-8"))


def cmd_chat(args) -> None:
    """Send a message to the running bridge, picking up a local API key automatically."""
    keys = load_keys()
    if not keys:
        print("no API key found. Run: python gemma_bridge.py key add", file=sys.stderr)
        sys.exit(1)

    # Choose a key: explicit --name match, else the first one.
    if args.name:
        match = [k for k, v in keys.items() if v.get("name") == args.name]
        if not match:
            print(f"no key with name {args.name!r}", file=sys.stderr)
            sys.exit(1)
        api_key = match[0]
    else:
        api_key = next(iter(keys))

    cfg = load_config()
    base = f"http://{cfg['listen']['host']}:{cfg['listen']['port']}"

    body = {
        "model": args.model,
        "messages": [{"role": "user", "content": args.message}],
        "stream": True,
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    try:
        with httpx.Client(timeout=None) as client:
            with client.stream(
                "POST", f"{base}/v1/chat/completions",
                json=body, headers=headers,
            ) as r:
                if r.status_code != 200:
                    print(f"HTTP {r.status_code}: {r.read().decode('utf-8', 'replace')}",
                          file=sys.stderr)
                    sys.exit(1)
                for line in r.iter_lines():
                    if not line or not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    try:
                        chunk = json.loads(data)
                        delta = chunk["choices"][0]["delta"].get("content", "")
                        if delta:
                            sys.stdout.write(delta)
                            sys.stdout.flush()
                    except (json.JSONDecodeError, KeyError, IndexError):
                        pass
        print()  # newline after stream
    except httpx.RequestError as e:
        print(f"request failed: {e}", file=sys.stderr)
        sys.exit(1)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="gemma_bridge")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("serve", help="run the API server").set_defaults(func=cmd_serve)

    key = sub.add_parser("key", help="manage API keys")
    ksub = key.add_subparsers(dest="key_cmd", required=True)
    add = ksub.add_parser("add")
    add.add_argument("--name", default="default")
    add.set_defaults(func=cmd_key_add)
    ksub.add_parser("list").set_defaults(func=cmd_key_list)
    rev = ksub.add_parser("revoke")
    rev.add_argument("prefix", help="full key or unique prefix")
    rev.set_defaults(func=cmd_key_revoke)

    cfg = sub.add_parser("config", help="manage configuration")
    csub = cfg.add_subparsers(dest="cfg_cmd", required=True)
    csub.add_parser("init").set_defaults(func=cmd_config_init)
    csub.add_parser("show").set_defaults(func=cmd_config_show)

    chat = sub.add_parser("chat", help="send a message to the running bridge (auto picks a local key)")
    chat.add_argument("message", help="the user message to send")
    chat.add_argument("--name", help="use the key with this name (defaults to first available)")
    chat.add_argument("--model", default="gemma", help="model id to pass through (default: gemma)")
    chat.set_defaults(func=cmd_chat)

    return p


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
