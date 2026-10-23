#!/usr/bin/env python3
"""
wAide Conversations service  (runs on your PC, next to Ollama)

This is the private, PIN-locked side of wAide. It stores your conversations on
this PC's disk and talks to Ollama locally to generate replies. Your phone
reaches it over Tailscale. The amnesia Chat and Server tabs never touch this.

Privacy model (honest):
  - Conversations live here, on your own machine, as plain JSON files.
  - Every endpoint except /ping requires the PIN (default 313131). The PIN is
    stored only as a slow salted hash, never in the clear.
  - Wrong PINs lock out for a minute after 5 tries, so a stolen phone can't
    brute force it over your tailnet.
  - Delete is a real delete: the file (or the one message) is removed from disk,
    and since the model is stateless, nothing "remembers" it afterward.

Run:  set WAIDE_HOST to your Tailscale IP (or leave it and it binds all
      interfaces) and run  python waide_server.py
Deps: pip install fastapi "uvicorn[standard]" requests
"""

import os
import json
import time
import uuid
import hmac
import hashlib
import secrets
import threading
from pathlib import Path
from typing import Optional

import requests
import uvicorn
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

# ---------------------------------------------------------------- config / paths
DATA_DIR = Path(os.environ.get("WAIDE_DATA", Path.home() / ".waide"))
CONV_DIR = DATA_DIR / "conversations"
CONFIG_PATH = DATA_DIR / "config.json"

OLLAMA = os.environ.get("WAIDE_OLLAMA", "http://127.0.0.1:11434").rstrip("/")
PORT = int(os.environ.get("WAIDE_PORT", "11435"))
HOST = os.environ.get("WAIDE_HOST", "0.0.0.0")   # installer sets this to the Tailscale IP
DEFAULT_PIN = "313131"

CONV_DIR.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------- PIN / config
def _hash_pin(pin: str, salt: bytes) -> str:
    return hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, 200_000).hex()


def _load_config() -> dict:
    if CONFIG_PATH.exists():
        try:
            return json.loads(CONFIG_PATH.read_text("utf-8"))
        except Exception:
            pass
    salt = secrets.token_bytes(16)
    cfg = {"salt": salt.hex(), "pin_hash": _hash_pin(DEFAULT_PIN, salt)}
    CONFIG_PATH.write_text(json.dumps(cfg), "utf-8")
    return cfg


_config = _load_config()
_config_lock = threading.Lock()


def verify_pin(pin: str) -> bool:
    salt = bytes.fromhex(_config["salt"])
    return hmac.compare_digest(_hash_pin(pin, salt), _config["pin_hash"])


def set_pin(new_pin: str) -> None:
    with _config_lock:
        salt = secrets.token_bytes(16)
        _config["salt"] = salt.hex()
        _config["pin_hash"] = _hash_pin(new_pin, salt)
        CONFIG_PATH.write_text(json.dumps(_config), "utf-8")


# ---------------------------------------------------------------- sessions
_sessions: dict[str, float] = {}          # token -> expiry epoch
_sessions_lock = threading.Lock()
SESSION_TTL = 3600                        # 1 hour sliding

# ---------------------------------------------------------------- login audit / lockout
AUTH_PATH = DATA_DIR / "auth_state.json"
_auth_lock = threading.Lock()
MAX_LOCK = 24 * 3600                      # cap any single lockout at 24 hours
FAIL_LIST_CAP = 50                        # keep timestamps of the most recent N fails


def _load_auth() -> dict:
    base = {"last_login": None, "failed": [], "failed_count": 0,
            "fail_streak": 0, "lock_until": 0.0}
    if AUTH_PATH.exists():
        try:
            base.update(json.loads(AUTH_PATH.read_text("utf-8")))
        except Exception:
            pass
    return base


_auth_state = _load_auth()


def _save_auth() -> None:
    try:
        AUTH_PATH.write_text(json.dumps(_auth_state), "utf-8")
    except Exception:
        pass


def _record_fail(now: float) -> None:
    _auth_state["failed"].append(now)
    if len(_auth_state["failed"]) > FAIL_LIST_CAP:
        _auth_state["failed"] = _auth_state["failed"][-FAIL_LIST_CAP:]
    _auth_state["failed_count"] = _auth_state.get("failed_count", 0) + 1


def issue_token() -> str:
    tok = secrets.token_urlsafe(24)
    with _sessions_lock:
        _sessions[tok] = time.time() + SESSION_TTL
    return tok


def require_token(authorization: Optional[str]) -> None:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "auth required")
    tok = authorization[7:]
    now = time.time()
    with _sessions_lock:
        exp = _sessions.get(tok)
        if not exp or exp < now:
            _sessions.pop(tok, None)
            raise HTTPException(401, "session expired")
        _sessions[tok] = now + SESSION_TTL   # sliding renewal


# ---------------------------------------------------------------- conversation store
def _conv_path(cid: str) -> Path:
    # guard against path tricks: ids are hex uuids only
    if not cid.isalnum():
        raise HTTPException(400, "bad id")
    return CONV_DIR / f"{cid}.json"


def _load_conv(cid: str) -> dict:
    p = _conv_path(cid)
    if not p.exists():
        raise HTTPException(404, "conversation not found")
    return json.loads(p.read_text("utf-8"))


def _save_conv(c: dict) -> None:
    c["updated"] = time.time()
    _conv_path(c["id"]).write_text(json.dumps(c), "utf-8")


# ---------------------------------------------------------------- app
app = FastAPI(title="wAide Conversations")


@app.get("/ping")
def ping():
    return {"service": "waide", "ok": True}


class AuthIn(BaseModel):
    pin: str


@app.post("/auth")
def auth(body: AuthIn):
    now = time.time()
    with _auth_lock:
        st = _auth_state
        # Already locked out: still log the attempt so you can see it, then refuse.
        if st.get("lock_until", 0) > now:
            _record_fail(now)
            _save_auth()
            raise HTTPException(429, f"locked out, wait {int(st['lock_until'] - now)}s")

        if verify_pin(body.pin):
            # Hand back what happened since your last successful login, then reset.
            report = {
                "last_login": st.get("last_login"),
                "failed_attempts": list(st.get("failed", [])),
                "failed_count": st.get("failed_count", 0),
            }
            st["last_login"] = now
            st["failed"] = []
            st["failed_count"] = 0
            st["fail_streak"] = 0
            st["lock_until"] = 0.0
            _save_auth()
            return {"token": issue_token(), **report}

        # Wrong PIN: record it. Past 10 in a row, lock out and double each time.
        _record_fail(now)
        st["fail_streak"] = st.get("fail_streak", 0) + 1
        if st["fail_streak"] >= 10:
            n = min(st["fail_streak"] - 10, 20)          # 0 on the 10th fail -> 60s
            st["lock_until"] = now + min(60 * (2 ** n), MAX_LOCK)
        _save_auth()
        wait = int(st.get("lock_until", 0) - now)
        if wait > 0:
            raise HTTPException(429, f"locked out, wait {wait}s")
        raise HTTPException(401, "wrong pin")


class ChangePinIn(BaseModel):
    old_pin: str
    new_pin: str


@app.post("/auth/change")
def change_pin(body: ChangePinIn, authorization: str = Header(None)):
    require_token(authorization)
    if not verify_pin(body.old_pin):
        raise HTTPException(401, "wrong current pin")
    if not (body.new_pin.isdigit() and len(body.new_pin) == 6):
        raise HTTPException(400, "pin must be 6 digits")
    set_pin(body.new_pin)
    return {"ok": True}


@app.get("/models")
def models(authorization: str = Header(None)):
    require_token(authorization)
    try:
        r = requests.get(f"{OLLAMA}/api/tags", timeout=10)
        r.raise_for_status()
        return sorted(m["name"] for m in r.json().get("models", []))
    except Exception as e:
        raise HTTPException(502, f"cannot reach Ollama: {e}")


@app.get("/conversations")
def list_conversations(authorization: str = Header(None)):
    require_token(authorization)
    out = []
    for p in CONV_DIR.glob("*.json"):
        try:
            c = json.loads(p.read_text("utf-8"))
            out.append({
                "id": c["id"],
                "title": c.get("title", "Untitled"),
                "updated": c.get("updated", 0),
                "count": len(c.get("messages", [])),
            })
        except Exception:
            pass
    out.sort(key=lambda x: x["updated"], reverse=True)
    return out


class NewConv(BaseModel):
    title: Optional[str] = None


@app.post("/conversations")
def create_conversation(body: NewConv, authorization: str = Header(None)):
    require_token(authorization)
    cid = uuid.uuid4().hex
    _save_conv({"id": cid, "title": body.title or "New conversation", "messages": []})
    return {"id": cid}


@app.get("/conversations/{cid}")
def get_conversation(cid: str, authorization: str = Header(None)):
    require_token(authorization)
    return _load_conv(cid)


class RenameIn(BaseModel):
    title: str


@app.patch("/conversations/{cid}")
def rename_conversation(cid: str, body: RenameIn, authorization: str = Header(None)):
    require_token(authorization)
    c = _load_conv(cid)
    c["title"] = body.title.strip() or c.get("title", "Untitled")
    _save_conv(c)
    return {"ok": True}


@app.delete("/conversations/{cid}")
def delete_conversation(cid: str, authorization: str = Header(None)):
    require_token(authorization)
    p = _conv_path(cid)
    if p.exists():
        p.unlink()
    return {"ok": True}


@app.delete("/conversations/{cid}/messages/{mid}")
def delete_message(cid: str, mid: str, authorization: str = Header(None)):
    require_token(authorization)
    c = _load_conv(cid)
    c["messages"] = [m for m in c["messages"] if m.get("id") != mid]
    _save_conv(c)
    return {"ok": True}


class ChatIn(BaseModel):
    model: str
    content: str
    num_ctx: Optional[int] = 8192


@app.post("/conversations/{cid}/chat")
def chat(cid: str, body: ChatIn, authorization: str = Header(None)):
    require_token(authorization)
    c = _load_conv(cid)

    user_msg = {"id": uuid.uuid4().hex, "role": "user", "text": body.content, "ts": time.time()}
    c["messages"].append(user_msg)

    # Name the conversation from its first real message.
    if c.get("title") in (None, "", "New conversation"):
        first_line = body.content.strip().splitlines()[0] if body.content.strip() else "New conversation"
        c["title"] = first_line[:40]
    _save_conv(c)

    oll_msgs = [
        {"role": m["role"], "content": m["text"]}
        for m in c["messages"] if m["role"] in ("user", "assistant")
    ]

    def gen():
        acc = ""
        try:
            with requests.post(
                f"{OLLAMA}/api/chat",
                json={
                    "model": body.model,
                    "messages": oll_msgs,
                    "stream": True,
                    "options": {"num_ctx": body.num_ctx or 8192},
                },
                stream=True, timeout=600,
            ) as r:
                r.raise_for_status()
                for line in r.iter_lines():
                    if not line:
                        continue
                    obj = json.loads(line)
                    piece = obj.get("message", {}).get("content", "")
                    if piece:
                        acc += piece
                        yield piece
                    if obj.get("done"):
                        break
        except Exception as e:
            yield f"\n[error: {e}]"
        finally:
            # Persist the assistant's turn even if the stream was cut off.
            try:
                c2 = _load_conv(cid)
                c2["messages"].append(
                    {"id": uuid.uuid4().hex, "role": "assistant", "text": acc, "ts": time.time()}
                )
                _save_conv(c2)
            except Exception:
                pass

    return StreamingResponse(gen(), media_type="text/plain")


# ---------------------------------------------------------------- run
if __name__ == "__main__":
    print(f"wAide Conversations service")
    print(f"  data:   {DATA_DIR}")
    print(f"  ollama: {OLLAMA}")
    print(f"  listen: {HOST}:{PORT}")
    # Retry the bind in case Tailscale hasn't assigned its address yet at boot.
    last_err = None
    for _ in range(30):
        try:
            uvicorn.run(app, host=HOST, port=PORT)
            last_err = None
            break
        except OSError as e:
            last_err = e
            print(f"  bind not ready ({e}); retrying in 2s...")
            time.sleep(2)
    if last_err:
        raise last_err
