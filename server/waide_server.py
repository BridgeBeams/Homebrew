#!/usr/bin/env python3
"""
wAide Conversations service  (runs on your PC, next to Ollama)

Standard library only. No pip installs, no external packages, so it runs on any
Python 3 (including brand-new alpha builds). Stores your conversations on this PC
and talks to Ollama locally. Your phone reaches it over Tailscale.

Privacy model (honest):
  - Conversations live here, on your own machine, as plain JSON files.
  - Every endpoint except /ping requires the PIN (default 313131), stored only as a
    slow salted hash.
  - After 10 wrong PINs it locks out and the wait doubles each time (capped 24h), so
    a stolen phone can't brute force it over your tailnet.
  - On a good login it reports your previous login time and every failed attempt
    since, so you'd spot a 3am try.
  - Delete is a real delete; the model is stateless, so nothing "remembers" it.

Run:  py C:\\waide\\waide_server.py
"""

import os
import json
import time
import uuid
import hmac
import hashlib
import secrets
import threading
import urllib.request
import urllib.error
from pathlib import Path
from urllib.parse import urlsplit
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------- config / paths
DATA_DIR = Path(os.environ.get("WAIDE_DATA", Path.home() / ".waide"))
CONV_DIR = DATA_DIR / "conversations"
CONFIG_PATH = DATA_DIR / "config.json"
AUTH_PATH = DATA_DIR / "auth_state.json"

OLLAMA = os.environ.get("WAIDE_OLLAMA", "http://127.0.0.1:11434").rstrip("/")
PORT = int(os.environ.get("WAIDE_PORT", "11435"))
HOST = os.environ.get("WAIDE_HOST", "0.0.0.0")
DEFAULT_PIN = "313131"

SESSION_TTL = 3600
MAX_LOCK = 24 * 3600
FAIL_LIST_CAP = 50

CONV_DIR.mkdir(parents=True, exist_ok=True)


class ApiError(Exception):
    def __init__(self, code, msg):
        self.code = code
        self.msg = msg


# ---------------------------------------------------------------- PIN / config
def _hash_pin(pin, salt):
    return hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, 200_000).hex()


def _load_config():
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


def verify_pin(pin):
    salt = bytes.fromhex(_config["salt"])
    return hmac.compare_digest(_hash_pin(pin, salt), _config["pin_hash"])


def set_pin(new_pin):
    with _config_lock:
        salt = secrets.token_bytes(16)
        _config["salt"] = salt.hex()
        _config["pin_hash"] = _hash_pin(new_pin, salt)
        CONFIG_PATH.write_text(json.dumps(_config), "utf-8")


# ---------------------------------------------------------------- sessions
_sessions = {}
_sessions_lock = threading.Lock()


def issue_token():
    tok = secrets.token_urlsafe(24)
    with _sessions_lock:
        _sessions[tok] = time.time() + SESSION_TTL
    return tok


def require_token(authz):
    if not authz or not authz.startswith("Bearer "):
        raise ApiError(401, "auth required")
    tok = authz[7:]
    now = time.time()
    with _sessions_lock:
        exp = _sessions.get(tok)
        if not exp or exp < now:
            _sessions.pop(tok, None)
            raise ApiError(401, "session expired")
        _sessions[tok] = now + SESSION_TTL


# ---------------------------------------------------------------- login audit / lockout
_auth_lock = threading.Lock()


def _load_auth():
    base = {"last_login": None, "failed": [], "failed_count": 0,
            "fail_streak": 0, "lock_until": 0.0}
    if AUTH_PATH.exists():
        try:
            base.update(json.loads(AUTH_PATH.read_text("utf-8")))
        except Exception:
            pass
    return base


_auth_state = _load_auth()


def _save_auth():
    try:
        AUTH_PATH.write_text(json.dumps(_auth_state), "utf-8")
    except Exception:
        pass


def _record_fail(now):
    _auth_state["failed"].append(now)
    if len(_auth_state["failed"]) > FAIL_LIST_CAP:
        _auth_state["failed"] = _auth_state["failed"][-FAIL_LIST_CAP:]
    _auth_state["failed_count"] = _auth_state.get("failed_count", 0) + 1


def do_auth(pin):
    now = time.time()
    with _auth_lock:
        st = _auth_state
        if st.get("lock_until", 0) > now:
            _record_fail(now)
            _save_auth()
            raise ApiError(429, f"locked out, wait {int(st['lock_until'] - now)}s")
        if verify_pin(pin):
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
        _record_fail(now)
        st["fail_streak"] = st.get("fail_streak", 0) + 1
        if st["fail_streak"] >= 10:
            n = min(st["fail_streak"] - 10, 20)
            st["lock_until"] = now + min(60 * (2 ** n), MAX_LOCK)
        _save_auth()
        wait = int(st.get("lock_until", 0) - now)
        if wait > 0:
            raise ApiError(429, f"locked out, wait {wait}s")
        raise ApiError(401, "wrong pin")


def do_change_pin(old_pin, new_pin):
    if not verify_pin(old_pin):
        raise ApiError(401, "wrong current pin")
    if not (new_pin.isdigit() and len(new_pin) == 6):
        raise ApiError(400, "pin must be 6 digits")
    set_pin(new_pin)
    return {"ok": True}


# ---------------------------------------------------------------- Ollama (via urllib)
# Ollama is often bound to the Tailscale address rather than localhost, so try a
# few addresses and remember whichever one answers.
_ollama_cache = {"base": None}


def _ollama_candidates():
    cands = []
    w = os.environ.get("WAIDE_OLLAMA", "").strip()
    if w:
        cands.append(w.rstrip("/"))
    cands.append("http://127.0.0.1:11434")
    cands.append("http://localhost:11434")
    oh = os.environ.get("OLLAMA_HOST", "").strip()
    if oh and not oh.startswith("0.0.0.0"):
        if not oh.startswith("http"):
            oh = "http://" + oh
        cands.append(oh.rstrip("/"))
    seen, out = set(), []
    for c in cands:
        if c not in seen:
            seen.add(c)
            out.append(c)
    return out


def ollama_base():
    if _ollama_cache["base"]:
        return _ollama_cache["base"]
    cands = _ollama_candidates()
    for c in cands:
        try:
            with urllib.request.urlopen(f"{c}/api/version", timeout=3) as r:
                r.read()
            _ollama_cache["base"] = c
            return c
        except Exception:
            continue
    return cands[0]


def ollama_tags():
    base = ollama_base()
    try:
        with urllib.request.urlopen(f"{base}/api/tags", timeout=10) as r:
            data = json.loads(r.read().decode("utf-8"))
        return sorted(m["name"] for m in data.get("models", []))
    except Exception as e:
        _ollama_cache["base"] = None   # re-probe next time
        raise ApiError(502, f"cannot reach Ollama ({base}): {e}")


def ollama_chat_stream(payload, on_piece):
    base = ollama_base()
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(f"{base}/api/chat", data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        for raw in r:
            line = raw.decode("utf-8").strip()
            if not line:
                continue
            obj = json.loads(line)
            piece = obj.get("message", {}).get("content", "")
            if piece:
                on_piece(piece)
            if obj.get("done"):
                break


def _vision_instruction(prompt):
    return ("You are reading an image for another assistant that cannot see it. "
            "Transcribe ALL visible text exactly, including headings, numbers, labels, "
            "and fine print. Then briefly describe any photos, objects, people, charts, "
            "and the layout. The user will ask: \"" + prompt + "\" so be sure to capture "
            "anything relevant. Do not answer the question yourself; only report what is "
            "in the image.")


def ollama_describe(model, image_b64, instruction):
    """One-shot (non-streaming) vision read of an image. Returns the readout text."""
    base = ollama_base()
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": instruction, "images": [image_b64]}],
        "stream": False,
        "options": {"num_ctx": 4096},
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(f"{base}/api/chat", data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        obj = json.loads(r.read().decode("utf-8"))
    return obj.get("message", {}).get("content", "").strip()


# ---------------------------------------------------------------- conversation store
_store_lock = threading.Lock()


def _conv_path(cid):
    if not cid.isalnum():
        raise ApiError(400, "bad id")
    return CONV_DIR / f"{cid}.json"


def load_conv(cid):
    p = _conv_path(cid)
    if not p.exists():
        raise ApiError(404, "conversation not found")
    return json.loads(p.read_text("utf-8"))


def save_conv(c):
    c["updated"] = time.time()
    _conv_path(c["id"]).write_text(json.dumps(c), "utf-8")


def list_convs():
    out = []
    for p in CONV_DIR.glob("*.json"):
        try:
            c = json.loads(p.read_text("utf-8"))
            hidden = bool(c.get("hidden"))
            out.append({"id": c["id"],
                        "title": "hidden" if hidden else c.get("title", "Untitled"),
                        "hidden": hidden,
                        "updated": c.get("updated", 0),
                        "count": len(c.get("messages", []))})
        except Exception:
            pass
    out.sort(key=lambda x: x["updated"], reverse=True)
    return out


def _snippet(text, needle, width=90):
    """A short excerpt of `text` centered on the first match of `needle`."""
    low = text.lower()
    i = low.find(needle)
    if i < 0:
        return text[:width].strip()
    start = max(0, i - width // 3)
    end = min(len(text), i + len(needle) + width)
    s = text[start:end].strip().replace("\n", " ")
    if start > 0:
        s = "..." + s
    if end < len(text):
        s = s + "..."
    return s


def search_convs(q):
    """Keyword search over titles and message bodies. Hidden conversations are
    skipped entirely, so search never exposes hidden content."""
    needle = (q or "").strip().lower()
    out = []
    if not needle:
        return out
    for p in CONV_DIR.glob("*.json"):
        try:
            c = json.loads(p.read_text("utf-8"))
            if c.get("hidden"):
                continue
            title = c.get("title", "Untitled")
            msgs = c.get("messages", [])
            hit_title = needle in title.lower()
            snippet = ""
            if hit_title:
                snippet = title
            else:
                for m in msgs:
                    t = m.get("text", "")
                    if needle in t.lower():
                        snippet = _snippet(t, needle)
                        break
                else:
                    continue   # no match in this conversation
            out.append({"id": c["id"], "title": title,
                        "updated": c.get("updated", 0),
                        "count": len(msgs), "snippet": snippet})
        except Exception:
            pass
    out.sort(key=lambda x: x["updated"], reverse=True)
    return out


def create_conv(title):
    cid = uuid.uuid4().hex
    save_conv({"id": cid, "title": title or "New conversation", "messages": [], "hidden": False})
    return {"id": cid}


def rename_conv(cid, title):
    c = load_conv(cid)
    c["title"] = (title or "").strip() or c.get("title", "Untitled")
    save_conv(c)
    return {"ok": True}


def delete_conv(cid):
    p = _conv_path(cid)
    if p.exists():
        p.unlink()
    return {"ok": True}


def delete_msg(cid, mid):
    c = load_conv(cid)
    c["messages"] = [m for m in c["messages"] if m.get("id") != mid]
    save_conv(c)
    return {"ok": True}


def set_hidden(cid, val):
    c = load_conv(cid)
    c["hidden"] = bool(val)
    save_conv(c)
    return {"ok": True}


def unhide_all():
    n = 0
    for p in CONV_DIR.glob("*.json"):
        try:
            c = json.loads(p.read_text("utf-8"))
            if c.get("hidden"):
                c["hidden"] = False
                save_conv(c)
                n += 1
        except Exception:
            pass
    return {"ok": True, "unhidden": n}


# ---------------------------------------------------------------- HTTP handler
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send_json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        raw = self.rfile.read(n)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            raise ApiError(400, "bad json")

    def _auth(self):
        require_token(self.headers.get("Authorization"))

    def _parts(self):
        return [p for p in urlsplit(self.path).path.split("/") if p]

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def do_PATCH(self):
        self._dispatch("PATCH")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def _dispatch(self, method):
        try:
            self._route(method, self._parts())
        except ApiError as e:
            self._send_json(e.code, {"detail": e.msg})
        except Exception as e:
            try:
                self._send_json(500, {"detail": str(e)})
            except Exception:
                pass

    def _route(self, method, parts):
        if method == "GET" and parts == ["ping"]:
            return self._send_json(200, {"service": "waide", "ok": True})

        if method == "POST" and parts == ["auth"]:
            return self._send_json(200, do_auth(self._read_body().get("pin", "")))

        if method == "POST" and parts == ["auth", "change"]:
            self._auth()
            b = self._read_body()
            return self._send_json(200, do_change_pin(b.get("old_pin", ""), b.get("new_pin", "")))

        if method == "GET" and parts == ["models"]:
            self._auth()
            return self._send_json(200, ollama_tags())

        if method == "GET" and parts == ["conversations"]:
            self._auth()
            return self._send_json(200, list_convs())

        if method == "GET" and parts == ["conversations", "search"]:
            self._auth()
            from urllib.parse import parse_qs
            q = parse_qs(urlsplit(self.path).query).get("q", [""])[0]
            return self._send_json(200, search_convs(q))

        if method == "POST" and parts == ["conversations"]:
            self._auth()
            return self._send_json(200, create_conv(self._read_body().get("title")))

        if method == "POST" and parts == ["conversations", "unhide-all"]:
            if not verify_pin(self._read_body().get("pin", "")):
                raise ApiError(401, "wrong pin")
            return self._send_json(200, unhide_all())

        if len(parts) == 2 and parts[0] == "conversations":
            cid = parts[1]
            if method == "GET":
                self._auth()
                c = load_conv(cid)
                if c.get("hidden"):
                    raise ApiError(403, "conversation is hidden")
                return self._send_json(200, c)
            if method == "PATCH":
                self._auth()
                return self._send_json(200, rename_conv(cid, self._read_body().get("title", "")))
            if method == "DELETE":
                self._auth()
                return self._send_json(200, delete_conv(cid))

        if (len(parts) == 3 and parts[0] == "conversations"
                and parts[2] == "rename" and method == "POST"):
            self._auth()
            return self._send_json(200, rename_conv(parts[1], self._read_body().get("title", "")))

        if (len(parts) == 3 and parts[0] == "conversations"
                and parts[2] == "hide" and method == "POST"):
            self._auth()
            return self._send_json(200, set_hidden(parts[1], True))

        if (len(parts) == 3 and parts[0] == "conversations"
                and parts[2] == "unhide" and method == "POST"):
            if not verify_pin(self._read_body().get("pin", "")):
                raise ApiError(401, "wrong pin")
            return self._send_json(200, set_hidden(parts[1], False))

        if (len(parts) == 4 and parts[0] == "conversations"
                and parts[2] == "messages" and method == "DELETE"):
            self._auth()
            return self._send_json(200, delete_msg(parts[1], parts[3]))

        if (len(parts) == 3 and parts[0] == "conversations"
                and parts[2] == "chat" and method == "POST"):
            self._auth()
            if load_conv(parts[1]).get("hidden"):
                raise ApiError(403, "conversation is hidden")
            return self._chat_stream(parts[1], self._read_body())

        raise ApiError(404, "not found")

    def _chat_stream(self, cid, body):
        model = body.get("model", "")
        content = (body.get("content") or "").strip()
        num_ctx = body.get("num_ctx") or 8192
        image = body.get("image")                      # base64 (downscaled) or None
        vision_model = (body.get("vision_model") or "").strip()
        if not model:
            raise ApiError(400, "model required")

        # These can raise 404/400 before we commit to a streaming response.
        c = load_conv(cid)

        # If a photo is attached, read it with the vision model first, so a failure
        # comes back as a clean error instead of a half-streamed reply.
        vision_note = None
        if image:
            if not vision_model:
                raise ApiError(400, "no image reader selected")
            if not content:
                content = "What's in this image?"
            try:
                vision_note = ollama_describe(vision_model, image, _vision_instruction(content))
            except Exception as e:
                raise ApiError(502, f"error reading image: {e}")
            if not vision_note:
                raise ApiError(502, "error reading image")

        with _store_lock:
            um = {"id": uuid.uuid4().hex, "role": "user", "text": content, "ts": time.time()}
            if image:
                um["image"] = image
            if vision_note:
                um["vision_note"] = vision_note
            c["messages"].append(um)
            if c.get("title") in (None, "", "New conversation"):
                first = content.splitlines()[0] if content else "New conversation"
                c["title"] = first[:40]
            save_conv(c)
            # Fold each image turn's readout into its content so the main model
            # "sees" the image as plain text.
            oll_msgs = []
            for m in c["messages"]:
                if m["role"] not in ("user", "assistant"):
                    continue
                txt = m["text"]
                if m.get("vision_note"):
                    txt = ("[An image was attached. A vision model read it as:]\n"
                           + m["vision_note"] + "\n\n[The user's message about the image:]\n"
                           + m["text"])
                oll_msgs.append({"role": m["role"], "content": txt})

        # From here on we stream; do not raise past this point.
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        acc = {"t": ""}

        def write_chunk(s):
            b = s.encode("utf-8")
            self.wfile.write(("%X\r\n" % len(b)).encode("ascii"))
            self.wfile.write(b)
            self.wfile.write(b"\r\n")
            self.wfile.flush()

        try:
            def on_piece(p):
                acc["t"] += p
                write_chunk(p)
            ollama_chat_stream({"model": model, "messages": oll_msgs, "stream": True,
                                "options": {"num_ctx": num_ctx}}, on_piece)
        except Exception as e:
            try:
                write_chunk(f"\n[error: {e}]")
            except Exception:
                pass
        finally:
            try:
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except Exception:
                pass
            try:
                with _store_lock:
                    c2 = load_conv(cid)
                    c2["messages"].append({"id": uuid.uuid4().hex, "role": "assistant",
                                           "text": acc["t"], "ts": time.time()})
                    save_conv(c2)
            except Exception:
                pass


# ---------------------------------------------------------------- run
if __name__ == "__main__":
    print("wAide Conversations service")
    print(f"  data:   {DATA_DIR}")
    print(f"  ollama: {OLLAMA}")
    print(f"  listen: {HOST}:{PORT}")
    httpd = None
    for _ in range(30):
        try:
            httpd = ThreadingHTTPServer((HOST, PORT), Handler)
            break
        except OSError as e:
            print(f"  bind not ready ({e}); retrying in 2s...")
            time.sleep(2)
    if httpd is None:
        raise SystemExit("could not bind to the address")
    print("  running. leave this window open.")
    httpd.serve_forever()
