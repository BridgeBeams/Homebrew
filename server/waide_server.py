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
import sys
import json
import math
import time
import uuid
import base64
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
AGENT_DIR = DATA_DIR / "agents"
PREMIUM_DIR = DATA_DIR / "premium"
EMB_PATH = DATA_DIR / "embeddings.json"
CONFIG_PATH = DATA_DIR / "config.json"
PREMIUM_CONFIG_PATH = DATA_DIR / "premium_config.json"
AUTH_PATH = DATA_DIR / "auth_state.json"
FILES_DIR = DATA_DIR / "convfiles"     # per-conversation file folders (the "files folder")
# Premium agents live in their own store and have their own vector index, kept strictly
# separate from the local/private side so private conversations are never retrieved or
# sent to OpenRouter.
PREMIUM_AGENT_DIR = DATA_DIR / "premium_agents"
PREMIUM_EMB_PATH = DATA_DIR / "premium_embeddings.json"
# Projects: lightweight folders that group conversations. Kept in two separate stores so a
# Saved-chat project can never hold a premium (OpenRouter) conversation and vice versa,
# preserving the wall between private and cloud chats.
PROJ_DIR = DATA_DIR / "projects"
PREMIUM_PROJ_DIR = DATA_DIR / "premium_projects"

# Premium AI (paid cloud models) goes through OpenRouter: one key, many models
# (Claude, GPT, Grok, etc). The key is stored on this PC only, never returned to the
# phone. All of this processing happens in OpenRouter's cloud, not on your machines.
OPENROUTER_URL = "https://openrouter.ai/api/v1"

OLLAMA = os.environ.get("WAIDE_OLLAMA", "http://127.0.0.1:11434").rstrip("/")
PORT = int(os.environ.get("WAIDE_PORT", "11435"))
HOST = os.environ.get("WAIDE_HOST", "0.0.0.0")
# The default UNLOCK code for Saved Chats (the code you type to get in). You can change
# it in Settings. It is deliberately different from the master reset code below.
DEFAULT_PIN = "923331"
# The master RESET code. It never unlocks Saved Chats; it only authorizes a password
# reset (Settings > Change password > Forgot password). It always works and is fixed, so
# a lost unlock code can always be recovered. Keep it private.
MASTER_RESET_PIN = "313131"
# Model used to embed conversations so agents can retrieve the relevant bits.
EMBED_MODEL = os.environ.get("WAIDE_EMBED_MODEL", "nomic-embed-text")
RETRIEVE_K = 8            # how many conversation chunks to feed an agent per question

# ---- conversation compaction (keep long chats fast by summarizing old turns) ----
# Budgets are rough token estimates (~4 chars per token). When the history sent to a
# model would exceed the budget, the oldest turns are folded into a running summary and
# only the most recent KEEP turns are sent verbatim. The full history is always kept on
# disk and still shown in the app; only what we SEND to the model shrinks.
LOCAL_CTX_BUDGET = 5000   # Saved Chats / agents run on local Ollama (small context)
LOCAL_KEEP_RECENT = 6     # keep this many recent user/assistant msgs verbatim (local)
PREMIUM_CTX_BUDGET = 6000 # Premium runs in the cloud; budget is about token cost
PREMIUM_KEEP_RECENT = 8   # keep this many recent msgs verbatim (premium)
DEFAULT_SUMMARY_MODEL = "openai/gpt-4o-mini"  # cheap model used to summarize premium chats

SESSION_TTL = 3600
MAX_LOCK = 24 * 3600
FAIL_LIST_CAP = 50

CONV_DIR.mkdir(parents=True, exist_ok=True)
AGENT_DIR.mkdir(parents=True, exist_ok=True)
PREMIUM_DIR.mkdir(parents=True, exist_ok=True)
FILES_DIR.mkdir(parents=True, exist_ok=True)
PREMIUM_AGENT_DIR.mkdir(parents=True, exist_ok=True)
PROJ_DIR.mkdir(parents=True, exist_ok=True)
PREMIUM_PROJ_DIR.mkdir(parents=True, exist_ok=True)


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
# Short-lived tokens granted by the master reset code. They authorize setting a new
# unlock password (reset-complete) but nothing else, and are cleared once used.
_reset_sessions = set()


def request_reset(master):
    """Forgot-password: the master code grants a reset session (it never unlocks chats).
    The caller then sets a new unlock password with reset_complete."""
    if (master or "") != MASTER_RESET_PIN:
        raise ApiError(401, "wrong master code")
    tok = issue_token()
    _reset_sessions.add(tok)
    return {"ok": True, "token": tok, "must_change": True}


def reset_complete(token, new_pin):
    if token not in _reset_sessions:
        raise ApiError(403, "not a reset session")
    if not ((new_pin or "").isdigit() and len(new_pin) == 6):
        raise ApiError(400, "pin must be 6 digits")
    if new_pin == MASTER_RESET_PIN:
        raise ApiError(400, "that code is reserved; choose a different password")
    set_pin(new_pin)
    _reset_sessions.discard(token)
    return {"ok": True}


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
        # The master code never unlocks; it only resets (see request_reset). So a login
        # must match the stored unlock code AND not be the master code.
        if verify_pin(pin) and pin != MASTER_RESET_PIN:
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
            return {"token": issue_token(), "must_change": False, **report}
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
    if new_pin == MASTER_RESET_PIN:
        raise ApiError(400, "that code is reserved; choose a different password")
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


def ollama_chat_stream(payload, on_piece, on_image=None):
    base = ollama_base()
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(f"{base}/api/chat", data=data,
                                 headers={"Content-Type": "application/json"})
    seen = set()
    with urllib.request.urlopen(req, timeout=600) as r:
        for raw in r:
            line = raw.decode("utf-8").strip()
            if not line:
                continue
            obj = json.loads(line)
            msg = obj.get("message", {}) or {}
            piece = msg.get("content", "")
            if piece:
                on_piece(piece)
            # Image-capable local models return images as base64 strings on the message.
            if on_image:
                for b64 in (msg.get("images") or []):
                    if isinstance(b64, str) and b64 and b64 not in seen:
                        seen.add(b64)
                        on_image(b64)
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


def ollama_summarize(model, prompt):
    """One-shot (non-streaming) summary with a local model. Returns summary text."""
    base = ollama_base()
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": False,
        "options": {"num_ctx": 8192},
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(f"{base}/api/chat", data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        obj = json.loads(r.read().decode("utf-8"))
    return obj.get("message", {}).get("content", "").strip()


def ollama_embed(text):
    """Return the embedding vector for a piece of text (nomic-embed-text by default)."""
    base = ollama_base()
    payload = {"model": EMBED_MODEL, "prompt": text}
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(f"{base}/api/embeddings", data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        obj = json.loads(r.read().decode("utf-8"))
    vec = obj.get("embedding") or []
    if not vec:
        raise ApiError(502, f"no embedding returned (is '{EMBED_MODEL}' pulled?)")
    return vec


# ---------------------------------------------------------------- OpenRouter (Premium AI)
_premium_lock = threading.Lock()


def _load_premium_config():
    if PREMIUM_CONFIG_PATH.exists():
        try:
            return json.loads(PREMIUM_CONFIG_PATH.read_text("utf-8"))
        except Exception:
            pass
    return {}


def get_openrouter_key():
    return (_load_premium_config().get("openrouter_key") or "").strip()


def set_openrouter_key(key):
    with _premium_lock:
        cfg = _load_premium_config()
        cfg["openrouter_key"] = (key or "").strip()
        PREMIUM_CONFIG_PATH.write_text(json.dumps(cfg), "utf-8")
    return {"ok": True, "has_key": bool(get_openrouter_key())}


def get_summary_model():
    return (_load_premium_config().get("summary_model") or DEFAULT_SUMMARY_MODEL).strip()


def _openrouter_headers(key):
    return {
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://waide.local",
        "X-Title": "wAide",
    }


def openrouter_models():
    key = get_openrouter_key()
    req = urllib.request.Request(f"{OPENROUTER_URL}/models",
                                 headers=_openrouter_headers(key) if key else {"User-Agent": "waide"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, f"OpenRouter models error {e.code}")
    except Exception as e:
        raise ApiError(502, f"cannot reach OpenRouter: {e}")
    out = []
    for m in data.get("data", []):
        mid = m.get("id")
        if not mid:
            continue
        out.append({"id": mid, "name": m.get("name", mid),
                    "context": m.get("context_length", 0)})
    out.sort(key=lambda x: x["name"].lower())
    return out


def openrouter_usage():
    """This key's budget, read with the stored key via OpenRouter's /key endpoint. Returns
    the percent of the current period's credit limit still available (and the raw figures),
    so the Premium header shows a gauge that resets when the key's limit resets (monthly on
    the 1st, in Wade's setup). Falls back to a $150 limit if the key has none set."""
    key = get_openrouter_key()
    if not key:
        return {"has_key": False}
    req = urllib.request.Request(f"{OPENROUTER_URL}/key", headers=_openrouter_headers(key))
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            data = json.loads(r.read().decode("utf-8")).get("data", {})
    except urllib.error.HTTPError as e:
        raise ApiError(e.code, f"OpenRouter key error {e.code}")
    except Exception as e:
        raise ApiError(502, f"cannot reach OpenRouter: {e}")
    # Pick the usage window that matches how this key's limit resets.
    reset = (data.get("limit_reset") or "").lower()
    used = float({
        "daily": data.get("usage_daily"),
        "weekly": data.get("usage_weekly"),
        "monthly": data.get("usage_monthly"),
    }.get(reset, data.get("usage")) or 0)
    lim = data.get("limit")
    limit = float(lim) if lim not in (None, "") else 150.0
    remaining = max(0.0, limit - used)
    pct_left = round((remaining / limit) * 100.0, 4) if limit > 0 else 0.0
    return {"has_key": True, "reset": reset or "total",
            "limit": round(limit, 4), "used": round(used, 4),
            "remaining": round(remaining, 4), "percent_left": pct_left,
            # kept for backward compatibility with older app builds
            "usage": round(used, 4), "credits": round(limit, 4)}


# ---------------------------------------------------------------- document rendering
# Professional Word/PDF rendering on the PC (where real document tooling lives), so the
# phone doesn't have to imitate it. Needs: pip install python-docx reportlab.
# If either library is missing, the matching endpoint returns 501 and the app falls back
# to its built-in on-device renderer.
import io as _io
import re as _re

try:
    import docx as _docx
    from docx.shared import Pt as _Pt, Inches as _In, RGBColor as _RGB
    from docx.enum.text import WD_ALIGN_PARAGRAPH as _WDALIGN
    _HAS_DOCX = True
except Exception:
    _HAS_DOCX = False
try:
    from reportlab.lib.pagesizes import LETTER as _LETTER
    from reportlab.lib.units import inch as _inch
    from reportlab.lib.styles import ParagraphStyle as _PS
    from reportlab.lib.colors import HexColor as _Hex
    from reportlab.platypus import SimpleDocTemplate as _SDT, Paragraph as _Para, HRFlowable as _HR, Spacer as _Spacer
    _HAS_RL = True
except Exception:
    _HAS_RL = False
try:
    import openpyxl as _openpyxl
    from openpyxl.styles import Font as _XlFont
    _HAS_XLSX = True
except Exception:
    _HAS_XLSX = False
try:
    from pptx import Presentation as _Presentation
    _HAS_PPTX = True
except Exception:
    _HAS_PPTX = False
try:
    from ebooklib import epub as _epub
    _HAS_EPUB = True
except Exception:
    _HAS_EPUB = False

_INLINE_RE = _re.compile(r"\*\*(.+?)\*\*|`([^`]+?)`|\*(.+?)\*|_(.+?)_")


def _md_blocks(text):
    lines = text.replace("\r\n", "\n").split("\n")
    blocks = []
    para = []

    def flush():
        nonblank = [p.strip() for p in para if p.strip()]
        s = " ".join(nonblank).strip()
        if s:
            # Promote a standalone, short, fully-bold line (e.g. **Primary Responsibilities**)
            # to a heading, since models often use bold instead of ## for section titles.
            m = _re.match(r"^\*\*(.+)\*\*$", s)
            inner = m.group(1).strip() if m else ""
            if (m and len(nonblank) == 1 and "**" not in inner and len(inner) <= 40
                    and ":" not in inner and not _re.search(r"[.!?]$", inner)):
                blocks.append(("H2", inner, ""))
            else:
                blocks.append(("PARA", s, ""))
        para.clear()

    i = 0
    while i < len(lines):
        line = lines[i].rstrip()
        t = line.lstrip()
        if not t:
            flush()
        elif t.startswith("```"):
            flush(); i += 1; code = []
            while i < len(lines) and not lines[i].lstrip().startswith("```"):
                code.append(lines[i]); i += 1
            blocks.append(("CODE", "\n".join(code), ""))
        elif _re.match(r"^(-{3,}|\*{3,}|_{3,})$", t):
            flush()
        elif t.startswith("### "):
            flush(); blocks.append(("H3", t[4:], ""))
        elif t.startswith("## "):
            flush(); blocks.append(("H2", t[3:], ""))
        elif t.startswith("# "):
            flush(); blocks.append(("H1", t[2:], ""))
        elif _re.match(r"^[-*+]\s+", t):
            flush(); blocks.append(("BULLET", _re.sub(r"^[-*+]\s+", "", t), ""))
        elif _re.match(r"^\d+\.\s+", t):
            flush()
            m = _re.match(r"^(\d+)\.\s+", t)
            blocks.append(("NUMBER", _re.sub(r"^\d+\.\s+", "", t), m.group(1)))
        elif t.startswith("> "):
            flush(); blocks.append(("QUOTE", t[2:], ""))
        else:
            para.append(t)
        i += 1
    flush()
    return blocks


def _inline_tokens(s):
    out = []; i = 0
    for m in _INLINE_RE.finditer(s):
        if m.start() > i:
            out.append((s[i:m.start()], False, False, False))
        if m.group(1):
            out.append((m.group(1), True, False, False))
        elif m.group(2):
            out.append((m.group(2), False, False, True))
        elif m.group(3):
            out.append((m.group(3), False, True, False))
        elif m.group(4):
            out.append((m.group(4), False, True, False))
        i = m.end()
    if i < len(s):
        out.append((s[i:], False, False, False))
    return out


def _pdf_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _inline_pdf(s):
    out = ""
    for (tx, b, it, co) in _inline_tokens(s):
        e = _pdf_escape(tx)
        if co:
            e = '<font face="Courier">%s</font>' % e
        if b:
            e = "<b>%s</b>" % e
        if it:
            e = "<i>%s</i>" % e
        out += e
    return out or "&nbsp;"


def render_pdf_doc(text, title):
    if not _HAS_RL:
        raise ApiError(501, "PDF rendering not installed on the server (pip install reportlab)")
    buf = _io.BytesIO()
    doc = _SDT(buf, pagesize=_LETTER, topMargin=0.8 * _inch, bottomMargin=0.8 * _inch,
               leftMargin=1 * _inch, rightMargin=1 * _inch, title=(title or "wAide"))
    INK = _Hex("#1a1a1a"); HEADINK = _Hex("#111111"); RULE = _Hex("#cfcfcf")
    body = _PS("body", fontName="Helvetica", fontSize=10.5, leading=15, spaceAfter=8, textColor=INK)
    titlep = _PS("titlep", fontName="Helvetica-Bold", fontSize=20, leading=24, alignment=1,
                 textColor=HEADINK, spaceBefore=0, spaceAfter=2)
    h1 = _PS("h1", fontName="Helvetica-Bold", fontSize=15, leading=19, spaceBefore=13, spaceAfter=5, textColor=HEADINK)
    h2 = _PS("h2", fontName="Helvetica-Bold", fontSize=13.5, leading=17, spaceBefore=13, spaceAfter=4, textColor=HEADINK)
    h3 = _PS("h3", fontName="Helvetica-Bold", fontSize=11.5, leading=15, spaceBefore=9, spaceAfter=3, textColor=HEADINK)
    code = _PS("code", fontName="Courier", fontSize=9.5, leading=12, leftIndent=12, spaceAfter=6, textColor=_Hex("#333333"))
    bullet = _PS("bullet", parent=body, leftIndent=20, bulletIndent=6, spaceAfter=4)
    quote = _PS("quote", parent=body, leftIndent=18, fontName="Helvetica-Oblique", textColor=_Hex("#555555"))
    flow = []
    used_title = False
    for (t, s, marker) in _md_blocks(text):
        if t == "CODE":
            for ln in (s.split("\n") or [""]):
                flow.append(_Para(_pdf_escape(ln) or "&nbsp;", code))
        elif t == "H1" and not used_title:
            # First top-level heading becomes a centered, colored document title with a rule.
            used_title = True
            flow.append(_Para(_inline_pdf(s), titlep))
            flow.append(_Spacer(1, 3))
            flow.append(_HR(width="100%", thickness=0.8, color=RULE, spaceBefore=1, spaceAfter=11))
        elif t == "H1":
            flow.append(_Para(_inline_pdf(s), h1))
        elif t == "H2":
            flow.append(_Para(_inline_pdf(s), h2))
        elif t == "H3":
            flow.append(_Para(_inline_pdf(s), h3))
        elif t == "BULLET":
            flow.append(_Para(_inline_pdf(s), bullet, bulletText="•"))
        elif t == "NUMBER":
            flow.append(_Para(_inline_pdf(s), bullet, bulletText=marker + "."))
        elif t == "QUOTE":
            flow.append(_Para(_inline_pdf(s), quote))
        else:
            flow.append(_Para(_inline_pdf(s), body))
    if not flow:
        flow.append(_Para("&nbsp;", body))
    doc.build(flow)
    return buf.getvalue()


def _docx_add_inline(p, s, size, bold=False, italic=False, mono=False, color=None):
    toks = _inline_tokens(s)
    if not toks:
        r = p.add_run(""); r.font.size = _Pt(size); return
    for (tx, b, it, co) in toks:
        r = p.add_run(tx)
        r.font.size = _Pt(size)
        r.bold = bold or b
        r.italic = italic or it
        if color is not None:
            r.font.color.rgb = color
        if mono or co:
            r.font.name = "Consolas"


def render_docx_doc(text, title):
    if not _HAS_DOCX:
        raise ApiError(501, "Word rendering not installed on the server (pip install python-docx)")
    d = _docx.Document()
    for sec in d.sections:
        sec.top_margin = _In(0.8); sec.bottom_margin = _In(0.8)
        sec.left_margin = _In(1.0); sec.right_margin = _In(1.0)
    st = d.styles["Normal"]
    st.font.name = "Calibri"; st.font.size = _Pt(11)
    st.paragraph_format.space_after = _Pt(6); st.paragraph_format.line_spacing = 1.08
    INK = _RGB(0x11, 0x11, 0x11)
    hsz = {"H1": 15, "H2": 13.5, "H3": 11.5}
    hbefore = {"H1": 13, "H2": 13, "H3": 9}
    hcolor = {"H1": INK, "H2": INK, "H3": INK}
    used_title = False
    for (t, s, marker) in _md_blocks(text):
        if t == "CODE":
            for ln in (s.split("\n") or [""]):
                p = d.add_paragraph(); p.paragraph_format.space_after = _Pt(0)
                _docx_add_inline(p, ln if ln else " ", 10, mono=True)
        elif t == "H1" and not used_title:
            # First top-level heading becomes a centered, colored document title.
            used_title = True
            p = d.add_paragraph(); p.alignment = _WDALIGN.CENTER
            p.paragraph_format.space_before = _Pt(0); p.paragraph_format.space_after = _Pt(10)
            _docx_add_inline(p, s, 20, bold=True, color=INK)
        elif t in ("H1", "H2", "H3"):
            p = d.add_paragraph()
            p.paragraph_format.space_before = _Pt(hbefore[t]); p.paragraph_format.space_after = _Pt(4)
            _docx_add_inline(p, s, hsz[t], bold=True, color=hcolor[t])
        elif t == "BULLET":
            p = d.add_paragraph(style="List Bullet"); p.paragraph_format.space_after = _Pt(2)
            _docx_add_inline(p, s, 11)
        elif t == "NUMBER":
            p = d.add_paragraph(style="List Number"); p.paragraph_format.space_after = _Pt(2)
            _docx_add_inline(p, s, 11)
        elif t == "QUOTE":
            p = d.add_paragraph(); p.paragraph_format.left_indent = _In(0.3)
            _docx_add_inline(p, s, 11, italic=True)
        else:
            p = d.add_paragraph()
            _docx_add_inline(p, s, 11)
    buf = _io.BytesIO(); d.save(buf)
    return buf.getvalue()


def _plain(s):
    return "".join(t[0] for t in _inline_tokens(s))


def _inline_html(s):
    out = ""
    for (tx, b, it, co) in _inline_tokens(s):
        e = _pdf_escape(tx)
        if co:
            e = "<code>%s</code>" % e
        if b:
            e = "<strong>%s</strong>" % e
        if it:
            e = "<em>%s</em>" % e
        out += e
    return out


def _parse_rows(text):
    lines = [x.strip() for x in text.split("\n") if x.strip()]
    tbl = [x for x in lines if x.startswith("|")]
    if len(tbl) >= 2:
        rows = []
        for x in tbl:
            if not x.replace("|", "").replace("-", "").replace(":", "").replace(" ", ""):
                continue
            rows.append([c.strip() for c in x.strip().strip("|").split("|")])
        return rows
    out = []
    for x in lines:
        if "\t" in x:
            out.append(x.split("\t"))
        elif "," in x:
            out.append([c.strip() for c in x.split(",")])
        else:
            out.append([x])
    return out


def render_xlsx_doc(text, title):
    if not _HAS_XLSX:
        raise ApiError(501, "Excel rendering not installed on the server (pip install openpyxl)")
    rows = _parse_rows(text or "") or [[""]]
    wb = _openpyxl.Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    for ri, row in enumerate(rows, 1):
        for ci, val in enumerate(row, 1):
            c = ws.cell(row=ri, column=ci, value=val)
            if ri == 1:
                c.font = _XlFont(bold=True)
    for col in ws.columns:
        w = max((len(str(c.value)) for c in col if c.value is not None), default=8)
        ws.column_dimensions[col[0].column_letter].width = min(max(w + 2, 8), 60)
    buf = _io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def render_pptx_doc(text, title):
    if not _HAS_PPTX:
        raise ApiError(501, "PowerPoint rendering not installed on the server (pip install python-pptx)")
    prs = _Presentation()
    blocks = _md_blocks(text or "")
    slides = []
    cur_title = title or "Slide"
    cur_body = []
    started = False

    def push():
        slides.append((cur_title, list(cur_body)))
        cur_body.clear()

    for (t, s, marker) in blocks:
        if t in ("H1", "H2"):
            if started:
                push()
            cur_title = _plain(s)
            started = True
        elif t == "BULLET":
            cur_body.append(("b", _plain(s))); started = True
        elif t == "NUMBER":
            cur_body.append(("b", marker + ". " + _plain(s))); started = True
        elif t == "CODE":
            for ln in s.split("\n"):
                cur_body.append(("p", ln))
            started = True
        else:
            cur_body.append(("p", _plain(s))); started = True
    push()

    layout = prs.slide_layouts[1]
    for (stitle, body) in slides:
        sl = prs.slides.add_slide(layout)
        if sl.shapes.title is not None:
            sl.shapes.title.text = stitle
        tf = sl.placeholders[1].text_frame
        tf.clear()
        first = True
        for (kind, txt) in body:
            p = tf.paragraphs[0] if first else tf.add_paragraph()
            first = False
            p.text = txt
            p.level = 1 if kind == "b" else 0
    buf = _io.BytesIO()
    prs.save(buf)
    return buf.getvalue()


def _html_body(text):
    """The inner HTML (block elements) for a message. Shared by HTML and EPUB output."""
    out = []
    mode = [None]

    def close_list():
        if mode[0] == "ul":
            out.append("</ul>")
        elif mode[0] == "ol":
            out.append("</ol>")
        mode[0] = None

    for (t, s, marker) in _md_blocks(text or ""):
        if t == "BULLET":
            if mode[0] != "ul":
                close_list(); out.append("<ul>"); mode[0] = "ul"
            out.append("<li>" + _inline_html(s) + "</li>")
        elif t == "NUMBER":
            if mode[0] != "ol":
                close_list(); out.append("<ol>"); mode[0] = "ol"
            out.append("<li>" + _inline_html(s) + "</li>")
        else:
            close_list()
            if t == "H1":
                out.append("<h1>" + _inline_html(s) + "</h1>")
            elif t == "H2":
                out.append("<h2>" + _inline_html(s) + "</h2>")
            elif t == "H3":
                out.append("<h3>" + _inline_html(s) + "</h3>")
            elif t == "CODE":
                out.append("<pre>" + _pdf_escape(s) + "</pre>")
            elif t == "QUOTE":
                out.append("<blockquote>" + _inline_html(s) + "</blockquote>")
            else:
                out.append("<p>" + _inline_html(s) + "</p>")
    close_list()
    return "".join(out)


def render_html_doc(text, title):
    style = ("body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;max-width:7.5in;"
             "margin:1in auto;line-height:1.6;color:#1a1a1a;padding:0 1em}"
             "h1,h2,h3{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;line-height:1.25;color:#111}"
             "h1{font-size:1.8em;text-align:center;margin:0 0 .15em;"
             "border-bottom:1px solid #cfcfcf;padding-bottom:.25em}"
             "h2{font-size:1.35em;margin:1.2em 0 .3em}"
             "h3{font-size:1.1em;margin:.9em 0 .25em}"
             "code{font-family:Consolas,monospace;background:#f4f4f4;padding:1px 3px}"
             "pre{font-family:Consolas,monospace;background:#f4f4f4;padding:10px;overflow:auto}"
             "blockquote{color:#555;border-left:3px solid #ccc;padding-left:12px;font-style:italic}")
    return ('<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" '
            'content="width=device-width, initial-scale=1"><title>' + _pdf_escape(title or "wAide") +
            '</title><style>' + style + '</style></head><body>' + _html_body(text) + '</body></html>')


def render_epub_doc(text, title):
    if not _HAS_EPUB:
        raise ApiError(501, "EPUB rendering not installed on the server (pip install EbookLib)")
    ttl = title or "wAide"
    book = _epub.EpubBook()
    book.set_identifier("waide-" + uuid.uuid4().hex)
    book.set_title(ttl)
    book.set_language("en")
    ch = _epub.EpubHtml(title=ttl, file_name="chapter1.xhtml", lang="en")
    ch.content = ('<html xmlns="http://www.w3.org/1999/xhtml"><head><title>' + _pdf_escape(ttl) +
                  '</title></head><body>' + _html_body(text) + '</body></html>')
    book.add_item(ch)
    book.toc = [_epub.Link("chapter1.xhtml", ttl, "ch1")]
    book.add_item(_epub.EpubNcx())
    book.spine = [ch]
    buf = _io.BytesIO()
    _epub.write_epub(buf, book)
    return buf.getvalue()


_DOC_MIME = {
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "pdf": "application/pdf",
    "html": "text/html",
    "epub": "application/epub+zip",
}


def render_document(text, fmt, title):
    fmt = (fmt or "pdf").lower()
    if fmt == "docx":
        return render_docx_doc(text or "", title), _DOC_MIME["docx"]
    if fmt == "xlsx":
        return render_xlsx_doc(text or "", title), _DOC_MIME["xlsx"]
    if fmt == "pptx":
        return render_pptx_doc(text or "", title), _DOC_MIME["pptx"]
    if fmt == "html":
        return render_html_doc(text or "", title).encode("utf-8"), _DOC_MIME["html"]
    if fmt == "epub":
        return render_epub_doc(text or "", title), _DOC_MIME["epub"]
    return render_pdf_doc(text or "", title), _DOC_MIME["pdf"]


def _premium_content(text, attachments):
    """Build an OpenAI-compatible content value. Plain string when there are no
    attachments; otherwise a parts array with images and files inline (base64)."""
    if not attachments:
        return text
    parts = []
    if text:
        parts.append({"type": "text", "text": text})
    for a in attachments:
        mime = a.get("mime") or "application/octet-stream"
        b64 = a.get("data") or ""
        if not b64:
            continue
        data_url = f"data:{mime};base64,{b64}"
        if a.get("kind") == "image" or mime.startswith("image/"):
            parts.append({"type": "image_url", "image_url": {"url": data_url}})
        else:
            parts.append({"type": "file",
                          "file": {"filename": a.get("name", "file"), "file_data": data_url}})
    if not parts:
        parts.append({"type": "text", "text": text or ""})
    return parts


def _image_url_to_attach(url):
    """Turn a generated image (a data: URL of base64) into a stored attachment dict.
    http(s) URLs are fetched and inlined so the phone can show them without a round trip."""
    try:
        if url.startswith("data:"):
            head, _, b64 = url.partition(",")
            mime = head[5:].split(";")[0] or "image/png"
            if not b64:
                return None
            return {"kind": "image", "name": "generated.png", "mime": mime, "data": b64}
        if url.startswith("http"):
            with urllib.request.urlopen(url, timeout=60) as r:
                raw = r.read()
                mime = r.headers.get("Content-Type", "image/png").split(";")[0]
            return {"kind": "image", "name": "generated.png", "mime": mime,
                    "data": base64.b64encode(raw).decode("ascii")}
    except Exception:
        return None
    return None


def _collect_images(choice, on_image, seen):
    """Pull any generated images out of a streaming choice. OpenRouter returns image
    outputs as message/delta 'images': [{image_url:{url:'data:image/png;base64,...'}}]."""
    for where in ((choice.get("delta") or {}), (choice.get("message") or {})):
        for im in (where.get("images") or []):
            url = None
            if isinstance(im, dict):
                url = (im.get("image_url") or {}).get("url") or im.get("url")
            elif isinstance(im, str):
                url = im
            if url and url not in seen:
                seen.add(url)
                if on_image:
                    on_image(url)


def openrouter_chat_stream(model, messages, on_piece, on_image=None):
    key = get_openrouter_key()
    if not key:
        raise ApiError(400, "no OpenRouter key set (add it in Settings)")
    payload = {"model": model, "messages": messages, "stream": True}
    # Image-capable models (e.g. google/gemini-2.5-flash-image) need image output asked for.
    if "image" in model.lower():
        payload["modalities"] = ["image", "text"]
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(f"{OPENROUTER_URL}/chat/completions",
                                 data=data, headers=_openrouter_headers(key))
    try:
        resp = urllib.request.urlopen(req, timeout=600)
    except urllib.error.HTTPError as e:
        body = runCatching_read(e)
        raise ApiError(e.code, f"OpenRouter error {e.code}: {body[:300]}")
    except Exception as e:
        raise ApiError(502, f"cannot reach OpenRouter: {e}")
    seen = set()
    with resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").strip()
            if not line or line.startswith(":"):
                continue
            if not line.startswith("data:"):
                continue
            data_str = line[5:].strip()
            if data_str == "[DONE]":
                break
            try:
                obj = json.loads(data_str)
            except Exception:
                continue
            if obj.get("error"):
                err = obj["error"]
                msg = err.get("message") if isinstance(err, dict) else str(err)
                raise ApiError(502, f"OpenRouter: {msg}")
            choices = obj.get("choices") or []
            if not choices:
                continue
            piece = (choices[0].get("delta") or {}).get("content") or ""
            if piece:
                on_piece(piece)
            if on_image:
                _collect_images(choices[0], on_image, seen)


def openrouter_complete(model, messages):
    """One-shot (non-streaming) completion. Returns the reply text. Used for summaries."""
    key = get_openrouter_key()
    if not key:
        raise ApiError(400, "no OpenRouter key set")
    payload = {"model": model, "messages": messages, "stream": False}
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(f"{OPENROUTER_URL}/chat/completions",
                                 data=data, headers=_openrouter_headers(key))
    with urllib.request.urlopen(req, timeout=120) as r:
        obj = json.loads(r.read().decode("utf-8"))
    choices = obj.get("choices") or []
    if not choices:
        return ""
    return (choices[0].get("message") or {}).get("content", "").strip()


def runCatching_read(e):
    try:
        return e.read().decode("utf-8", "replace")
    except Exception:
        return ""


# ---------------------------------------------------------------- compaction
def estimate_tokens(s):
    return max(1, len(s or "") // 4)


def _compaction_prompt(prior_summary, fold_msgs):
    convo = []
    for m in fold_msgs:
        who = "User" if m.get("role") == "user" else "Assistant"
        convo.append(f"{who}: {m.get('text', '')}")
    body = "\n\n".join(convo)
    prior = (f"Here is the running summary so far:\n{prior_summary}\n\n"
             if prior_summary else "")
    return (
        "You are compacting a chat so it stays within the model's context. "
        + prior +
        "Summarize the conversation below into a concise but complete running summary. "
        "Preserve names, numbers, decisions, open questions, and any facts needed to keep "
        "answering later. Write it as notes, not prose, and keep it under 400 words.\n\n"
        "Conversation:\n" + body
    )


def maybe_compact(rec, keep_recent, budget, summarize_fn):
    """Fold the oldest user/assistant turns of rec into rec['summary'] when the history
    would exceed `budget` tokens, keeping the last `keep_recent` turns verbatim. Mutates
    rec (summary, compacted_count, compacted_at) and returns True if it compacted.
    summarize_fn(prompt)->str does the actual summary; its failure is swallowed (we just
    skip compacting that turn). Caller saves rec afterward."""
    msgs = [m for m in rec.get("messages", []) if m.get("role") in ("user", "assistant")]
    done = min(int(rec.get("compacted_count", 0)), len(msgs))
    summary = rec.get("summary", "")
    tail = msgs[done:]
    total = estimate_tokens(summary) + sum(estimate_tokens(m.get("text", "")) for m in tail)
    if total <= budget or len(tail) <= keep_recent:
        return False
    fold = tail[:len(tail) - keep_recent]
    if not fold:
        return False
    try:
        new_summary = summarize_fn(_compaction_prompt(summary, fold))
    except Exception:
        return False
    if not new_summary:
        return False
    rec["summary"] = new_summary
    rec["compacted_count"] = done + len(fold)
    rec["compacted_at"] = time.time()
    return True


def compaction_prefix(rec):
    """The summary as a system message (or empty list) to prepend to what we send."""
    s = rec.get("summary")
    if not s:
        return []
    return [{"role": "system",
             "content": "Summary of earlier parts of this conversation (compacted to save space):\n" + s}]


# ---------------------------------------------------------------- wAide instructions
# Who wAide is and how it should write, prepended to every PREMIUM (cloud) chat so the
# model returns finished, professional work instead of fill-in-the-blank templates.
# Edit BUSINESS_CONTEXT if the company details ever change.
BUSINESS_CONTEXT = (
    "The user is Wade Thomson, President of Thomson Prestress, LLC, a PCI-certified precast "
    "and prestressed concrete manufacturer at 1631 Ashport Road, Jackson, TN 38305. Wade is "
    "also President of Omega Concrete, LLC (ready-mix concrete) and Universal Hauling, LLC "
    "(heavy-haul trucking), both in Jackson, TN. When you write a letter, memo, or other "
    "document for one of these companies, use that company's name and the Ashport Road "
    "address as the letterhead and sign as Wade Thomson, President, unless told otherwise."
)


def premium_system():
    """System message prepended to premium chats so output is finished and professional."""
    lt = time.localtime()
    today = time.strftime("%B ", lt) + str(lt.tm_mday) + time.strftime(", %Y", lt)
    prompt = (
        "You are wAide, a sharp and capable AI assistant. Match the quality and polish of the "
        "best AI assistants: finished, specific, and ready to use.\n\n"
        f"Today's date is {today}.\n\n"
        + BUSINESS_CONTEXT + "\n\n"
        "When asked to produce a document (letter, memo, proposal, email, policy, report, "
        "agenda, and the like), deliver the COMPLETE, final, ready-to-send version:\n"
        "- Never leave fill-in-the-blank placeholders such as [Date], [Name], [Address], "
        "[Supervisor], or [Start Date]. Fill every field using today's date and the business "
        "details above, and make reasonable professional choices for small specifics rather "
        "than bracketing them.\n"
        "- If one essential fact is genuinely unknown and cannot be reasonably inferred, ask a "
        "single short question before writing instead of inserting a placeholder.\n"
        "- Format with clean structure the document renderer can make look sharp: a title or "
        "letterhead at the top, short paragraphs, bold for key labels, and bulleted or "
        "numbered lists where they help. Use proper Markdown (#, ##, **bold**, - bullets).\n"
        "- Keep a standard business letter to a single page. Be complete, but do not pad.\n\n"
        "For anything that will go out under Wade's name (letters, emails), do not use em "
        "dashes; use commas, periods, semicolons, parentheses, or short hyphens instead, and "
        "avoid hyphenated compound words, since that can read as AI assisted.\n\n"
        "In normal back-and-forth conversation, be direct and useful and skip filler."
    )
    return [{"role": "system", "content": prompt}]


# ---------------------------------------------------------------- conversation files
# A per-conversation "files folder": every image/file uploaded to a chat is copied here
# with its name and upload time, so even after the model stops re-reading it you can open
# the folder, download it again, or reattach it. scope is "premium" or "conv".
_files_lock = threading.Lock()


def _files_dir(scope, cid):
    if scope not in ("premium", "conv") or not cid.isalnum():
        raise ApiError(400, "bad file path")
    d = FILES_DIR / scope / cid
    d.mkdir(parents=True, exist_ok=True)
    return d


def _files_index_path(scope, cid):
    return _files_dir(scope, cid) / "index.json"


def _files_load_index(scope, cid):
    p = _files_index_path(scope, cid)
    if not p.exists():
        return []
    try:
        return json.loads(p.read_text("utf-8"))
    except Exception:
        return []


def conv_files_add(scope, cid, name, mime, kind, b64):
    """Store one uploaded file's bytes + metadata. Returns the metadata record."""
    if not b64:
        return None
    try:
        raw = base64.b64decode(b64)
    except Exception:
        return None
    fid = uuid.uuid4().hex
    with _files_lock:
        d = _files_dir(scope, cid)
        (d / (fid + ".bin")).write_bytes(raw)
        meta = {"id": fid, "name": name or "file", "mime": mime or "application/octet-stream",
                "kind": kind or "file", "size": len(raw), "added": time.time()}
        idx = _files_load_index(scope, cid)
        idx.append(meta)
        _files_index_path(scope, cid).write_text(json.dumps(idx), "utf-8")
    return meta


def conv_files_list(scope, cid):
    idx = _files_load_index(scope, cid)
    idx.sort(key=lambda x: x.get("added", 0))
    return idx


def conv_files_get(scope, cid, fid):
    """Return (meta, base64 data) for one stored file, or raise 404."""
    if not fid.isalnum():
        raise ApiError(400, "bad file id")
    idx = _files_load_index(scope, cid)
    meta = next((m for m in idx if m.get("id") == fid), None)
    p = _files_dir(scope, cid) / (fid + ".bin")
    if not meta or not p.exists():
        raise ApiError(404, "file not found")
    return meta, base64.b64encode(p.read_bytes()).decode("ascii")


def _store_attachments(scope, cid, attachments):
    """Copy a turn's attachments into the conversation files folder (best-effort)."""
    for a in attachments or []:
        try:
            conv_files_add(scope, cid, a.get("name"), a.get("mime"),
                           a.get("kind"), a.get("data"))
        except Exception:
            pass


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
                        "pinned": bool(c.get("pinned")),
                        "project": c.get("project", "") or "",
                        "updated": c.get("updated", 0),
                        "count": len(c.get("messages", []))})
        except Exception:
            pass
    out.sort(key=lambda x: (0 if x["pinned"] else 1, -x["updated"]))
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
                        "pinned": bool(c.get("pinned")),
                        "project": c.get("project", "") or "",
                        "updated": c.get("updated", 0),
                        "count": len(msgs), "snippet": snippet})
        except Exception:
            pass
    out.sort(key=lambda x: (0 if x["pinned"] else 1, -x["updated"]))
    return out


def search_premium(q):
    """Keyword search over premium conversation titles and message bodies. Hidden
    premium conversations are skipped entirely, so search never exposes hidden content."""
    needle = (q or "").strip().lower()
    out = []
    if not needle:
        return out
    for p in PREMIUM_DIR.glob("*.json"):
        try:
            c = json.loads(p.read_text("utf-8"))
            if c.get("hidden"):
                continue
            title = c.get("title", "Untitled")
            msgs = c.get("messages", [])
            if needle in title.lower():
                snippet = title
            else:
                for m in msgs:
                    t = m.get("text", "")
                    if needle in t.lower():
                        snippet = _snippet(t, needle)
                        break
                else:
                    continue
            out.append({"id": c["id"], "title": title, "mode": c.get("mode", "single"),
                        "models": c.get("models", []), "model": c.get("model", ""),
                        "pinned": bool(c.get("pinned")),
                        "project": c.get("project", "") or "",
                        "updated": c.get("updated", 0), "count": len(msgs), "snippet": snippet})
        except Exception:
            pass
    out.sort(key=lambda x: (0 if x["pinned"] else 1, -x["updated"]))
    return out


def create_conv(title, project=""):
    cid = uuid.uuid4().hex
    save_conv({"id": cid, "title": title or "New conversation", "messages": [],
               "hidden": False, "pinned": False, "project": (project or "").strip()})
    return {"id": cid}


def set_pinned(cid, val):
    c = load_conv(cid)
    c["pinned"] = bool(val)
    save_conv(c)
    return {"ok": True}


def assign_conv_project(cid, project):
    """Move a conversation into a project (empty string = unfiled)."""
    c = load_conv(cid)
    c["project"] = (project or "").strip()
    save_conv(c)
    return {"ok": True}


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


# ---------------------------------------------------------------- embeddings index
# An agent answers by retrieving the most relevant pieces of your saved conversations.
# We keep an incremental vector index: each non-hidden message is embedded once, and
# hidden/deleted messages are pruned, so a hidden conversation never feeds an agent.
_index_lock = threading.Lock()


def _load_index():
    if EMB_PATH.exists():
        try:
            return json.loads(EMB_PATH.read_text("utf-8"))
        except Exception:
            pass
    return {"model": EMBED_MODEL, "entries": {}}


def _save_index(idx):
    try:
        EMB_PATH.write_text(json.dumps(idx), "utf-8")
    except Exception:
        pass


def reindex():
    """Bring the vector index up to date with the current non-hidden conversations.
    Only new messages are embedded, so this is cheap after the first build."""
    with _index_lock:
        idx = _load_index()
        if idx.get("model") != EMBED_MODEL:
            idx = {"model": EMBED_MODEL, "entries": {}}
        entries = idx["entries"]

        desired = {}
        for p in CONV_DIR.glob("*.json"):
            try:
                c = json.loads(p.read_text("utf-8"))
            except Exception:
                continue
            if c.get("hidden"):
                continue
            title = c.get("title", "Untitled")
            for m in c.get("messages", []):
                if m.get("role") not in ("user", "assistant"):
                    continue
                txt = (m.get("text") or "").strip()
                if not txt:
                    continue
                key = f"{c['id']}:{m.get('id', '')}"
                desired[key] = (title, txt, c["id"], m.get("id", ""))

        # drop anything hidden/deleted since last time
        for k in list(entries.keys()):
            if k not in desired:
                del entries[k]

        # embed anything new
        for k, (title, txt, cid, mid) in desired.items():
            if k in entries:
                entries[k]["title"] = title
                continue
            vec = ollama_embed(txt[:2000])
            entries[k] = {"conv_id": cid, "msg_id": mid, "title": title,
                          "text": txt, "vec": vec}

        idx["entries"] = entries
        _save_index(idx)
        return idx


def _cosine(a, b):
    n = min(len(a), len(b))
    dot = na = nb = 0.0
    for i in range(n):
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    if na == 0 or nb == 0:
        return 0.0
    return dot / (math.sqrt(na) * math.sqrt(nb))


def retrieve(query, k=RETRIEVE_K):
    """Top-k conversation chunks most relevant to the query (hidden ones excluded)."""
    idx = reindex()
    entries = list(idx["entries"].values())
    if not entries:
        return []
    qv = ollama_embed(query[:2000])
    scored = [(_cosine(qv, e["vec"]), e) for e in entries]
    scored.sort(key=lambda x: x[0], reverse=True)
    return [e for (s, e) in scored[:k]]


# ---------------------------------------------------------------- agent store
def _agent_path(aid):
    if not aid.isalnum():
        raise ApiError(400, "bad id")
    return AGENT_DIR / f"{aid}.json"


def load_agent(aid):
    p = _agent_path(aid)
    if not p.exists():
        raise ApiError(404, "agent not found")
    return json.loads(p.read_text("utf-8"))


def save_agent(a):
    a["updated"] = time.time()
    _agent_path(a["id"]).write_text(json.dumps(a), "utf-8")


def list_agents():
    out = []
    for p in AGENT_DIR.glob("*.json"):
        try:
            a = json.loads(p.read_text("utf-8"))
            hidden = bool(a.get("hidden"))
            out.append({
                "id": a["id"],
                "name": "hidden" if hidden else a.get("name", "Agent"),
                "job": "" if hidden else a.get("job", ""),
                "read_premium": bool(a.get("read_premium")),
                "hidden": hidden,
                "updated": a.get("updated", 0),
                "count": len(a.get("messages", [])),
            })
        except Exception:
            pass
    out.sort(key=lambda x: x["updated"], reverse=True)
    return out


def create_agent(name, job, read_premium=False):
    aid = uuid.uuid4().hex
    save_agent({"id": aid, "name": (name or "Agent").strip()[:60],
                "job": (job or "").strip()[:200], "read_premium": bool(read_premium),
                "hidden": False, "messages": []})
    return {"id": aid}


def edit_agent(aid, name, job, read_premium=None):
    a = load_agent(aid)
    if name is not None:
        a["name"] = (name or "").strip()[:60] or a.get("name", "Agent")
    if job is not None:
        a["job"] = (job or "").strip()[:200]
    if read_premium is not None:
        a["read_premium"] = bool(read_premium)
    save_agent(a)
    return {"ok": True}


def delete_agent(aid):
    p = _agent_path(aid)
    if p.exists():
        p.unlink()
    return {"ok": True}


def delete_agent_msg(aid, mid):
    a = load_agent(aid)
    a["messages"] = [m for m in a["messages"] if m.get("id") != mid]
    save_agent(a)
    return {"ok": True}


def set_agent_hidden(aid, val):
    a = load_agent(aid)
    a["hidden"] = bool(val)
    save_agent(a)
    return {"ok": True}


def unhide_all_agents():
    n = 0
    for p in AGENT_DIR.glob("*.json"):
        try:
            a = json.loads(p.read_text("utf-8"))
            if a.get("hidden"):
                a["hidden"] = False
                save_agent(a)
                n += 1
        except Exception:
            pass
    return {"ok": True, "unhidden": n}


def _agent_persona(name, job, context_block):
    who = f"You are {name}"
    if job:
        who += f", {job}"
    who += "."
    return (who + " You are a private assistant for one person, and you draw on their "
            "saved conversations to answer. Context retrieved from those conversations is "
            "below. Use it when it helps; when it does not contain the answer, rely on "
            "what you know and say so rather than inventing details about the user.\n\n"
            "--- Retrieved from saved conversations ---\n" + context_block +
            "\n--- End of retrieved context ---")


# ------------------------------------------------- premium agents (walled off)
# A SECOND vector index, built only from premium conversations. It never reads the
# local/private conversations, so a premium agent can never retrieve private content
# and therefore can never send it to OpenRouter. Embedding still runs locally on Ollama.
_premium_index_lock = threading.Lock()


def _load_premium_index():
    if PREMIUM_EMB_PATH.exists():
        try:
            return json.loads(PREMIUM_EMB_PATH.read_text("utf-8"))
        except Exception:
            pass
    return {"model": EMBED_MODEL, "entries": {}}


def _save_premium_index(idx):
    try:
        PREMIUM_EMB_PATH.write_text(json.dumps(idx), "utf-8")
    except Exception:
        pass


def premium_reindex():
    """Bring the premium vector index up to date with non-hidden PREMIUM conversations."""
    with _premium_index_lock:
        idx = _load_premium_index()
        if idx.get("model") != EMBED_MODEL:
            idx = {"model": EMBED_MODEL, "entries": {}}
        entries = idx["entries"]

        desired = {}
        for p in PREMIUM_DIR.glob("*.json"):
            try:
                c = json.loads(p.read_text("utf-8"))
            except Exception:
                continue
            if c.get("hidden"):
                continue
            title = c.get("title", "Untitled")
            for m in c.get("messages", []):
                if m.get("role") not in ("user", "assistant"):
                    continue
                txt = (m.get("text") or "").strip()
                if not txt:
                    continue
                key = f"{c['id']}:{m.get('id', '')}"
                desired[key] = (title, txt, c["id"], m.get("id", ""))

        for k in list(entries.keys()):
            if k not in desired:
                del entries[k]
        for k, (title, txt, cid, mid) in desired.items():
            if k in entries:
                entries[k]["title"] = title
                continue
            vec = ollama_embed(txt[:2000])
            entries[k] = {"conv_id": cid, "msg_id": mid, "title": title, "text": txt, "vec": vec}

        idx["entries"] = entries
        _save_premium_index(idx)
        return idx


def premium_retrieve(query, k=RETRIEVE_K):
    """Top-k PREMIUM conversation chunks for a query (private side is never touched)."""
    idx = premium_reindex()
    entries = list(idx["entries"].values())
    if not entries:
        return []
    qv = ollama_embed(query[:2000])
    scored = [(_cosine(qv, e["vec"]), e) for e in entries]
    scored.sort(key=lambda x: x[0], reverse=True)
    return [e for (s, e) in scored[:k]]


def retrieve_combined(query, include_premium, k=RETRIEVE_K):
    """Retrieval for a LOCAL agent. Always reads the private/saved index; when the agent
    has opted in, ALSO reads the premium index. This stays private because local agents
    run entirely on the local model and never call OpenRouter (the data only flows the one
    way: a local agent may read premium, but a premium agent never reads the private side)."""
    entries = []
    for e in reindex()["entries"].values():
        d = dict(e)
        d["source"] = "saved"
        entries.append(d)
    if include_premium:
        for e in premium_reindex()["entries"].values():
            d = dict(e)
            d["source"] = "premium"
            entries.append(d)
    if not entries:
        return []
    qv = ollama_embed(query[:2000])
    scored = [(_cosine(qv, e["vec"]), e) for e in entries]
    scored.sort(key=lambda x: x[0], reverse=True)
    return [e for (s, e) in scored[:k]]


def _premium_agent_path(aid):
    if not aid.isalnum():
        raise ApiError(400, "bad id")
    return PREMIUM_AGENT_DIR / f"{aid}.json"


def load_premium_agent(aid):
    p = _premium_agent_path(aid)
    if not p.exists():
        raise ApiError(404, "agent not found")
    return json.loads(p.read_text("utf-8"))


def save_premium_agent(a):
    a["updated"] = time.time()
    _premium_agent_path(a["id"]).write_text(json.dumps(a), "utf-8")


def list_premium_agents():
    out = []
    for p in PREMIUM_AGENT_DIR.glob("*.json"):
        try:
            a = json.loads(p.read_text("utf-8"))
            hidden = bool(a.get("hidden"))
            out.append({
                "id": a["id"],
                "name": "hidden" if hidden else a.get("name", "Agent"),
                "job": "" if hidden else a.get("job", ""),
                "model": "" if hidden else a.get("model", ""),
                "hidden": hidden,
                "updated": a.get("updated", 0),
                "count": len(a.get("messages", [])),
            })
        except Exception:
            pass
    out.sort(key=lambda x: x["updated"], reverse=True)
    return out


def create_premium_agent(name, job, model):
    aid = uuid.uuid4().hex
    save_premium_agent({"id": aid, "name": (name or "Agent").strip()[:60],
                        "job": (job or "").strip()[:200], "model": (model or "").strip(),
                        "hidden": False, "messages": []})
    return {"id": aid}


def edit_premium_agent(aid, name, job, model):
    a = load_premium_agent(aid)
    if name is not None:
        a["name"] = (name or "").strip()[:60] or a.get("name", "Agent")
    if job is not None:
        a["job"] = (job or "").strip()[:200]
    if model is not None and (model or "").strip():
        a["model"] = model.strip()
    save_premium_agent(a)
    return {"ok": True}


def delete_premium_agent(aid):
    p = _premium_agent_path(aid)
    if p.exists():
        p.unlink()
    return {"ok": True}


def delete_premium_agent_msg(aid, mid):
    a = load_premium_agent(aid)
    a["messages"] = [m for m in a["messages"] if m.get("id") != mid]
    save_premium_agent(a)
    return {"ok": True}


def set_premium_agent_hidden(aid, val):
    a = load_premium_agent(aid)
    a["hidden"] = bool(val)
    save_premium_agent(a)
    return {"ok": True}


def unhide_all_premium_agents():
    n = 0
    for p in PREMIUM_AGENT_DIR.glob("*.json"):
        try:
            a = json.loads(p.read_text("utf-8"))
            if a.get("hidden"):
                a["hidden"] = False
                save_premium_agent(a)
                n += 1
        except Exception:
            pass
    return {"ok": True, "unhidden": n}


def _premium_agent_persona(name, job, context_block):
    who = f"You are {name}"
    if job:
        who += f", {job}"
    who += "."
    return (who + " You are a private assistant for one person. You draw ONLY on their "
            "premium (paid cloud) conversations, shown below. Use the context when it helps; "
            "when it does not contain the answer, rely on what you know and say so rather than "
            "inventing details about the user.\n\n"
            "--- Retrieved from premium conversations ---\n" + context_block +
            "\n--- End of retrieved context ---")


# ---------------------------------------------------------------- premium store
def _premium_path(pid):
    if not pid.isalnum():
        raise ApiError(400, "bad id")
    return PREMIUM_DIR / f"{pid}.json"


def load_premium(pid):
    p = _premium_path(pid)
    if not p.exists():
        raise ApiError(404, "conversation not found")
    return json.loads(p.read_text("utf-8"))


def save_premium(p):
    p["updated"] = time.time()
    _premium_path(p["id"]).write_text(json.dumps(p), "utf-8")


def list_premium():
    out = []
    for p in PREMIUM_DIR.glob("*.json"):
        try:
            c = json.loads(p.read_text("utf-8"))
            hidden = bool(c.get("hidden"))
            mode = c.get("mode", "single")
            out.append({"id": c["id"],
                        "title": "hidden" if hidden else c.get("title", "Untitled"),
                        "model": "" if hidden else c.get("model", ""),
                        "mode": mode,
                        "models": [] if hidden else c.get("models", []),
                        "hidden": hidden,
                        "pinned": bool(c.get("pinned")),
                        "project": c.get("project", "") or "",
                        "updated": c.get("updated", 0),
                        "count": len(c.get("messages", []))})
        except Exception:
            pass
    out.sort(key=lambda x: (0 if x["pinned"] else 1, -x["updated"]))
    return out


def create_premium(title, model, models=None, project=""):
    pid = uuid.uuid4().hex
    rec = {"id": pid, "title": title or "New chat", "model": (model or "").strip(),
           "hidden": False, "pinned": False, "project": (project or "").strip(),
           "messages": []}
    clean = [m.strip() for m in (models or []) if m and m.strip()]
    if len(clean) > 1:
        rec["mode"] = "multi"
        rec["models"] = clean[:3]
        rec["model"] = clean[0]
    save_premium(rec)
    return {"id": pid}


def set_premium_pinned(pid, val):
    c = load_premium(pid)
    c["pinned"] = bool(val)
    save_premium(c)
    return {"ok": True}


def assign_premium_project(pid, project):
    c = load_premium(pid)
    c["project"] = (project or "").strip()
    save_premium(c)
    return {"ok": True}


# ---------------------------------------------------------------- projects
# A project is just {id, name}. Conversations point at one via their "project" field.
# Deleting a project never deletes conversations; it only un-files them.
def _proj_path(base, pjid):
    if not pjid.isalnum():
        raise ApiError(400, "bad id")
    return base / f"{pjid}.json"


def _list_projects(base):
    out = []
    for p in base.glob("*.json"):
        try:
            c = json.loads(p.read_text("utf-8"))
            out.append({"id": c["id"], "name": c.get("name", "Untitled"),
                        "created": c.get("created", 0), "updated": c.get("updated", 0)})
        except Exception:
            pass
    out.sort(key=lambda x: x.get("name", "").lower())
    return out


def _create_project(base, name):
    pjid = uuid.uuid4().hex
    now = time.time()
    _proj_path(base, pjid).write_text(json.dumps(
        {"id": pjid, "name": (name or "New project").strip() or "New project",
         "created": now, "updated": now}), "utf-8")
    return {"id": pjid}


def _rename_project(base, pjid, name):
    p = _proj_path(base, pjid)
    if not p.exists():
        raise ApiError(404, "project not found")
    c = json.loads(p.read_text("utf-8"))
    c["name"] = (name or "").strip() or c.get("name", "Untitled")
    c["updated"] = time.time()
    p.write_text(json.dumps(c), "utf-8")
    return {"ok": True}


def _delete_project(base, pjid, conv_dir):
    """Remove the project. Member conversations are kept and returned to unfiled
    (their updated time is preserved so ordering does not jump)."""
    p = _proj_path(base, pjid)
    if p.exists():
        p.unlink()
    for cp in conv_dir.glob("*.json"):
        try:
            c = json.loads(cp.read_text("utf-8"))
            if (c.get("project") or "") == pjid:
                c["project"] = ""
                cp.write_text(json.dumps(c), "utf-8")
        except Exception:
            pass
    return {"ok": True}


def list_projects():         return _list_projects(PROJ_DIR)
def create_project(name):    return _create_project(PROJ_DIR, name)
def rename_project(i, name): return _rename_project(PROJ_DIR, i, name)
def delete_project(i):       return _delete_project(PROJ_DIR, i, CONV_DIR)

def list_premium_projects():         return _list_projects(PREMIUM_PROJ_DIR)
def create_premium_project(name):    return _create_project(PREMIUM_PROJ_DIR, name)
def rename_premium_project(i, name): return _rename_project(PREMIUM_PROJ_DIR, i, name)
def delete_premium_project(i):       return _delete_project(PREMIUM_PROJ_DIR, i, PREMIUM_DIR)


def rename_premium(pid, title):
    c = load_premium(pid)
    c["title"] = (title or "").strip() or c.get("title", "Untitled")
    save_premium(c)
    return {"ok": True}


def delete_premium(pid):
    p = _premium_path(pid)
    if p.exists():
        p.unlink()
    return {"ok": True}


def delete_premium_msg(pid, mid):
    c = load_premium(pid)
    c["messages"] = [m for m in c["messages"] if m.get("id") != mid]
    save_premium(c)
    return {"ok": True}


def set_premium_hidden(pid, val):
    c = load_premium(pid)
    c["hidden"] = bool(val)
    save_premium(c)
    return {"ok": True}


def unhide_all_premium():
    n = 0
    for p in PREMIUM_DIR.glob("*.json"):
        try:
            c = json.loads(p.read_text("utf-8"))
            if c.get("hidden"):
                c["hidden"] = False
                save_premium(c)
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

        # Forgot-password: the master code grants a reset session (it never unlocks).
        if method == "POST" and parts == ["auth", "reset-request"]:
            return self._send_json(200, request_reset(self._read_body().get("master", "")))

        # With a reset session, set a new unlock password (no old password needed).
        if method == "POST" and parts == ["auth", "reset-complete"]:
            self._auth()
            tok = self.headers.get("Authorization", "")[7:]
            return self._send_json(200, reset_complete(tok, self._read_body().get("new_pin", "")))

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
            b = self._read_body()
            return self._send_json(200, create_conv(b.get("title"), b.get("project", "")))

        # ---- projects (Saved Chats) ----
        if method == "GET" and parts == ["projects"]:
            self._auth()
            return self._send_json(200, list_projects())

        if method == "POST" and parts == ["projects"]:
            self._auth()
            return self._send_json(200, create_project(self._read_body().get("name", "")))

        # bulk-assign must come before /projects/{id}
        if method == "POST" and parts == ["projects", "assign"]:
            self._auth()
            b = self._read_body()
            pj = b.get("project", "")
            n = 0
            for cid in b.get("ids", []) or []:
                try:
                    assign_conv_project(cid, pj); n += 1
                except Exception:
                    pass
            return self._send_json(200, {"ok": True, "count": n})

        if (len(parts) == 3 and parts[0] == "projects"
                and parts[2] == "rename" and method == "POST"):
            self._auth()
            return self._send_json(200, rename_project(parts[1], self._read_body().get("name", "")))

        if len(parts) == 2 and parts[0] == "projects" and method == "DELETE":
            self._auth()
            return self._send_json(200, delete_project(parts[1]))

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
                and parts[2] == "pin" and method == "POST"):
            self._auth()
            return self._send_json(200, set_pinned(parts[1], self._read_body().get("pinned", True)))

        if (len(parts) == 3 and parts[0] == "conversations"
                and parts[2] == "project" and method == "POST"):
            self._auth()
            return self._send_json(200, assign_conv_project(parts[1], self._read_body().get("project", "")))

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

        if (len(parts) == 3 and parts[0] == "conversations"
                and parts[2] == "files" and method == "GET"):
            self._auth()
            if load_conv(parts[1]).get("hidden"):
                raise ApiError(403, "conversation is hidden")
            return self._send_json(200, conv_files_list("conv", parts[1]))

        if (len(parts) == 4 and parts[0] == "conversations"
                and parts[2] == "files" and method == "GET"):
            self._auth()
            if load_conv(parts[1]).get("hidden"):
                raise ApiError(403, "conversation is hidden")
            meta, data = conv_files_get("conv", parts[1], parts[3])
            return self._send_json(200, {**meta, "data": data})

        # ---- agents ----
        if method == "GET" and parts == ["agents"]:
            self._auth()
            return self._send_json(200, list_agents())

        if method == "POST" and parts == ["agents"]:
            self._auth()
            b = self._read_body()
            return self._send_json(200, create_agent(b.get("name"), b.get("job"), b.get("read_premium")))

        if method == "POST" and parts == ["agents", "unhide-all"]:
            if not verify_pin(self._read_body().get("pin", "")):
                raise ApiError(401, "wrong pin")
            return self._send_json(200, unhide_all_agents())

        if len(parts) == 2 and parts[0] == "agents":
            aid = parts[1]
            if method == "GET":
                self._auth()
                a = load_agent(aid)
                if a.get("hidden"):
                    raise ApiError(403, "agent is hidden")
                return self._send_json(200, a)
            if method == "DELETE":
                self._auth()
                return self._send_json(200, delete_agent(aid))

        if (len(parts) == 3 and parts[0] == "agents"
                and parts[2] == "edit" and method == "POST"):
            self._auth()
            b = self._read_body()
            return self._send_json(200, edit_agent(parts[1], b.get("name"), b.get("job"), b.get("read_premium")))

        if (len(parts) == 3 and parts[0] == "agents"
                and parts[2] == "hide" and method == "POST"):
            self._auth()
            return self._send_json(200, set_agent_hidden(parts[1], True))

        if (len(parts) == 3 and parts[0] == "agents"
                and parts[2] == "unhide" and method == "POST"):
            if not verify_pin(self._read_body().get("pin", "")):
                raise ApiError(401, "wrong pin")
            return self._send_json(200, set_agent_hidden(parts[1], False))

        if (len(parts) == 4 and parts[0] == "agents"
                and parts[2] == "messages" and method == "DELETE"):
            self._auth()
            return self._send_json(200, delete_agent_msg(parts[1], parts[3]))

        if (len(parts) == 3 and parts[0] == "agents"
                and parts[2] == "chat" and method == "POST"):
            self._auth()
            return self._agent_chat_stream(parts[1], self._read_body())

        # ---- premium (open over Tailscale; key-set and unhide need the PIN) ----
        if method == "GET" and parts == ["premium", "status"]:
            return self._send_json(200, {"has_key": bool(get_openrouter_key())})

        if method == "POST" and parts == ["premium", "key"]:
            b = self._read_body()
            if not verify_pin(b.get("pin", "")):
                raise ApiError(401, "wrong pin")
            return self._send_json(200, set_openrouter_key(b.get("key", "")))

        if method == "GET" and parts == ["premium", "models"]:
            return self._send_json(200, openrouter_models())

        if method == "GET" and parts == ["premium", "usage"]:
            return self._send_json(200, openrouter_usage())

        # Render a message's text into a professional document (Word/Excel/PowerPoint/PDF/HTML/EPUB).
        if method == "POST" and parts == ["render"]:
            b = self._read_body()
            data, mime = render_document(b.get("text", ""), b.get("format", "pdf"), b.get("title"))
            return self._send_json(200, {"mime": mime,
                                         "data": base64.b64encode(data).decode("ascii")})

        # Quick health check: which document renderers are installed. Open this in a browser
        # (http://127.0.0.1:11435/render/status) to confirm the new server + libraries are live.
        if method == "GET" and parts == ["render", "status"]:
            return self._send_json(200, {
                "server": "render-v2",
                "pdf": _HAS_RL, "docx": _HAS_DOCX, "xlsx": _HAS_XLSX,
                "pptx": _HAS_PPTX, "epub": _HAS_EPUB,
            })

        if method == "GET" and parts == ["premium", "conversations"]:
            return self._send_json(200, list_premium())

        # Must come before the /premium/conversations/{id} route below, or "search"
        # would be read as a conversation id.
        if method == "GET" and parts == ["premium", "conversations", "search"]:
            from urllib.parse import parse_qs
            q = parse_qs(urlsplit(self.path).query).get("q", [""])[0]
            return self._send_json(200, search_premium(q))

        if method == "POST" and parts == ["premium", "conversations"]:
            b = self._read_body()
            return self._send_json(200, create_premium(b.get("title"), b.get("model"),
                                                        b.get("models"), b.get("project", "")))

        # ---- premium projects (walled off from Saved-chat projects) ----
        if method == "GET" and parts == ["premium", "projects"]:
            return self._send_json(200, list_premium_projects())

        if method == "POST" and parts == ["premium", "projects"]:
            return self._send_json(200, create_premium_project(self._read_body().get("name", "")))

        if method == "POST" and parts == ["premium", "projects", "assign"]:
            b = self._read_body()
            pj = b.get("project", "")
            n = 0
            for pid in b.get("ids", []) or []:
                try:
                    assign_premium_project(pid, pj); n += 1
                except Exception:
                    pass
            return self._send_json(200, {"ok": True, "count": n})

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "projects"
                and parts[3] == "rename" and method == "POST"):
            return self._send_json(200, rename_premium_project(parts[2], self._read_body().get("name", "")))

        if (len(parts) == 3 and parts[0] == "premium" and parts[1] == "projects"
                and method == "DELETE"):
            return self._send_json(200, delete_premium_project(parts[2]))

        if method == "POST" and parts == ["premium", "conversations", "unhide-all"]:
            if not verify_pin(self._read_body().get("pin", "")):
                raise ApiError(401, "wrong pin")
            return self._send_json(200, unhide_all_premium())

        if len(parts) == 3 and parts[0] == "premium" and parts[1] == "conversations":
            pid = parts[2]
            if method == "GET":
                c = load_premium(pid)
                if c.get("hidden"):
                    raise ApiError(403, "conversation is hidden")
                return self._send_json(200, c)
            if method == "DELETE":
                return self._send_json(200, delete_premium(pid))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "rename" and method == "POST"):
            return self._send_json(200, rename_premium(parts[2], self._read_body().get("title", "")))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "pin" and method == "POST"):
            return self._send_json(200, set_premium_pinned(parts[2], self._read_body().get("pinned", True)))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "project" and method == "POST"):
            return self._send_json(200, assign_premium_project(parts[2], self._read_body().get("project", "")))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "hide" and method == "POST"):
            return self._send_json(200, set_premium_hidden(parts[2], True))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "unhide" and method == "POST"):
            if not verify_pin(self._read_body().get("pin", "")):
                raise ApiError(401, "wrong pin")
            return self._send_json(200, set_premium_hidden(parts[2], False))

        if (len(parts) == 5 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "messages" and method == "DELETE"):
            return self._send_json(200, delete_premium_msg(parts[2], parts[4]))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "chat" and method == "POST"):
            if load_premium(parts[2]).get("hidden"):
                raise ApiError(403, "conversation is hidden")
            return self._premium_chat_stream(parts[2], self._read_body())

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "multichat" and method == "POST"):
            if load_premium(parts[2]).get("hidden"):
                raise ApiError(403, "conversation is hidden")
            return self._premium_multichat_stream(parts[2], self._read_body())

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "files" and method == "GET"):
            if load_premium(parts[2]).get("hidden"):
                raise ApiError(403, "conversation is hidden")
            return self._send_json(200, conv_files_list("premium", parts[2]))

        if (len(parts) == 5 and parts[0] == "premium" and parts[1] == "conversations"
                and parts[3] == "files" and method == "GET"):
            if load_premium(parts[2]).get("hidden"):
                raise ApiError(403, "conversation is hidden")
            meta, data = conv_files_get("premium", parts[2], parts[4])
            return self._send_json(200, {**meta, "data": data})

        # ---- premium agents (walled off; open over Tailscale, unhide needs the PIN) ----
        if method == "GET" and parts == ["premium", "agents"]:
            return self._send_json(200, list_premium_agents())

        if method == "POST" and parts == ["premium", "agents"]:
            b = self._read_body()
            return self._send_json(200, create_premium_agent(b.get("name"), b.get("job"), b.get("model")))

        if method == "POST" and parts == ["premium", "agents", "unhide-all"]:
            if not verify_pin(self._read_body().get("pin", "")):
                raise ApiError(401, "wrong pin")
            return self._send_json(200, unhide_all_premium_agents())

        if len(parts) == 3 and parts[0] == "premium" and parts[1] == "agents":
            aid = parts[2]
            if method == "GET":
                a = load_premium_agent(aid)
                if a.get("hidden"):
                    raise ApiError(403, "agent is hidden")
                return self._send_json(200, a)
            if method == "DELETE":
                return self._send_json(200, delete_premium_agent(aid))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "agents"
                and parts[3] == "edit" and method == "POST"):
            b = self._read_body()
            return self._send_json(200, edit_premium_agent(parts[2], b.get("name"), b.get("job"), b.get("model")))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "agents"
                and parts[3] == "hide" and method == "POST"):
            return self._send_json(200, set_premium_agent_hidden(parts[2], True))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "agents"
                and parts[3] == "unhide" and method == "POST"):
            if not verify_pin(self._read_body().get("pin", "")):
                raise ApiError(401, "wrong pin")
            return self._send_json(200, set_premium_agent_hidden(parts[2], False))

        if (len(parts) == 5 and parts[0] == "premium" and parts[1] == "agents"
                and parts[3] == "messages" and method == "DELETE"):
            return self._send_json(200, delete_premium_agent_msg(parts[2], parts[4]))

        if (len(parts) == 4 and parts[0] == "premium" and parts[1] == "agents"
                and parts[3] == "chat" and method == "POST"):
            if load_premium_agent(parts[2]).get("hidden"):
                raise ApiError(403, "agent is hidden")
            return self._premium_agent_chat_stream(parts[2], self._read_body())

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

        # Keep the uploaded image in this conversation's files folder.
        if image:
            try:
                conv_files_add("conv", cid, f"image-{int(time.time())}.jpg",
                               "image/jpeg", "image", image)
            except Exception:
                pass

        # Fold old turns into a summary (local model) if the history got long.
        if maybe_compact(c, LOCAL_KEEP_RECENT, LOCAL_CTX_BUDGET,
                         lambda p: ollama_summarize(model, p)):
            with _store_lock:
                save_conv(c)

        # Fold each image turn's readout into its content so the main model "sees" the
        # image as plain text; skip turns already in the summary; prepend the summary.
        oll_msgs = list(compaction_prefix(c))
        done = int(c.get("compacted_count", 0))
        seen = 0
        for m in c["messages"]:
            if m["role"] not in ("user", "assistant"):
                continue
            idx = seen
            seen += 1
            if idx < done:
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
        gen_images = []

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
            def on_image(b64):
                gen_images.append(b64)
                try:
                    write_chunk("\n[generated an image]")
                except Exception:
                    pass
            ollama_chat_stream({"model": model, "messages": oll_msgs, "stream": True,
                                "options": {"num_ctx": num_ctx}}, on_piece, on_image)
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
                    am = {"id": uuid.uuid4().hex, "role": "assistant",
                          "text": acc["t"], "ts": time.time()}
                    if gen_images:
                        am["image"] = gen_images[0]   # shown in the bubble
                    c2["messages"].append(am)
                    save_conv(c2)
                if gen_images:
                    conv_files_add("conv", cid, f"generated-{int(time.time())}.png",
                                   "image/png", "image", gen_images[0])
            except Exception:
                pass

    def _agent_chat_stream(self, aid, body):
        model = body.get("model", "")
        content = (body.get("content") or "").strip()
        num_ctx = body.get("num_ctx") or 8192
        if not model:
            raise ApiError(400, "model required")
        if not content:
            raise ApiError(400, "empty message")

        a = load_agent(aid)
        if a.get("hidden"):
            raise ApiError(403, "agent is hidden")

        # Retrieve relevant conversation context first (hidden conversations excluded).
        # If this agent opted in, also read the premium index (stays local: this agent
        # runs on the local model and never calls OpenRouter). Clean error before streaming.
        read_premium = bool(a.get("read_premium"))
        try:
            hits = retrieve_combined(content, read_premium)
        except ApiError:
            raise
        except Exception as e:
            raise ApiError(502, f"retrieval failed: {e}")
        if hits:
            ctx = "\n\n".join(
                f"[from {'premium' if h.get('source') == 'premium' else 'saved'} chat \"{h['title']}\"]\n{h['text'][:800]}"
                for h in hits)
        else:
            ctx = "(no conversations matched this question yet)"
        system = _agent_persona(a.get("name", "Agent"), a.get("job", ""), ctx)

        with _store_lock:
            um = {"id": uuid.uuid4().hex, "role": "user", "text": content, "ts": time.time()}
            a["messages"].append(um)
            save_agent(a)

        # Compact the agent's own memory thread if it got long (local model).
        if maybe_compact(a, LOCAL_KEEP_RECENT, LOCAL_CTX_BUDGET,
                         lambda p: ollama_summarize(model, p)):
            with _store_lock:
                save_agent(a)

        oll_msgs = [{"role": "system", "content": system}] + list(compaction_prefix(a))
        done = int(a.get("compacted_count", 0))
        seen = 0
        for m in a["messages"]:
            if m.get("role") not in ("user", "assistant"):
                continue
            idx = seen
            seen += 1
            if idx < done:
                continue
            oll_msgs.append({"role": m["role"], "content": m["text"]})

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
                    a2 = load_agent(aid)
                    a2["messages"].append({"id": uuid.uuid4().hex, "role": "assistant",
                                           "text": acc["t"], "ts": time.time()})
                    save_agent(a2)
            except Exception:
                pass

    def _premium_chat_stream(self, pid, body):
        model = (body.get("model") or "").strip()
        content = (body.get("content") or "").strip()
        attachments = body.get("attachments") or []
        if not model:
            raise ApiError(400, "model required")
        if not content and not attachments:
            raise ApiError(400, "empty message")
        if not get_openrouter_key():
            raise ApiError(400, "no OpenRouter key set (add it in Settings)")

        c = load_premium(pid)  # 404 before streaming

        with _store_lock:
            # Store the user turn. Keep image bytes for display; for other files keep
            # only a marker so the conversation file stays small.
            stored = []
            for a in attachments:
                item = {"kind": a.get("kind"), "name": a.get("name"), "mime": a.get("mime")}
                if a.get("kind") == "image" or (a.get("mime") or "").startswith("image/"):
                    item["data"] = a.get("data")
                stored.append(item)
            um = {"id": uuid.uuid4().hex, "role": "user", "text": content, "ts": time.time()}
            if stored:
                um["attachments"] = stored
            c["messages"].append(um)
            if c.get("title") in (None, "", "New chat"):
                first = content.splitlines()[0] if content else "New chat"
                c["title"] = first[:40]
            c["model"] = model
            save_premium(c)

        # Keep a copy of every uploaded file in this chat's files folder.
        _store_attachments("premium", pid, attachments)

        # Fold old turns into a running summary if the history got long (cheap model).
        if maybe_compact(c, PREMIUM_KEEP_RECENT, PREMIUM_CTX_BUDGET,
                         lambda p: openrouter_complete(get_summary_model(),
                                                       [{"role": "user", "content": p}])):
            with _store_lock:
                save_premium(c)

        # Build the request. Only the current turn carries the actual attachment bytes;
        # earlier turns go as text with a note; folded turns are covered by the summary.
        msgs = premium_system() + list(compaction_prefix(c))
        done = int(c.get("compacted_count", 0))
        hist = c["messages"]
        seen = 0
        for i, m in enumerate(hist):
            if m.get("role") not in ("user", "assistant"):
                continue
            idx = seen
            seen += 1
            if idx < done:
                continue
            if i == len(hist) - 1 and m["role"] == "user" and attachments:
                msgs.append({"role": "user", "content": _premium_content(m["text"], attachments)})
            else:
                txt = m["text"]
                atts = m.get("attachments")
                if atts:
                    names = ", ".join(a.get("name", "file") for a in atts)
                    txt = (txt or "") + f"\n[earlier you attached: {names} (open the files folder to resend it)]"
                msgs.append({"role": m["role"], "content": txt})

        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        acc = {"t": ""}
        gen_image_urls = []

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
            def on_image(url):
                gen_image_urls.append(url)
                # Let the phone know an image is coming; it shows after the reload.
                try:
                    write_chunk("\n[generated an image]")
                except Exception:
                    pass
            openrouter_chat_stream(model, msgs, on_piece, on_image)
        except ApiError as e:
            msg = f"\n[error: {e.msg}]"
            acc["t"] += msg
            try:
                write_chunk(msg)
            except Exception:
                pass
        except Exception as e:
            msg = f"\n[error: {e}]"
            acc["t"] += msg
            try:
                write_chunk(msg)
            except Exception:
                pass
        # Turn any generated images into stored attachments on the assistant message.
        gen_atts = []
        for u in gen_image_urls:
            a = _image_url_to_attach(u)
            if a:
                gen_atts.append(a)
        # If the model streamed nothing and raised nothing AND produced no image, say so.
        if not acc["t"].strip() and not gen_atts:
            acc["t"] = "[no content returned by the model]"
            try:
                write_chunk(acc["t"])
            except Exception:
                pass
        try:
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except Exception:
            pass
        try:
            with _store_lock:
                c2 = load_premium(pid)
                am = {"id": uuid.uuid4().hex, "role": "assistant",
                      "text": acc["t"], "model": model, "ts": time.time()}
                if gen_atts:
                    am["attachments"] = gen_atts
                c2["messages"].append(am)
                save_premium(c2)
            if gen_atts:
                _store_attachments("premium", pid, gen_atts)   # also drop into the files folder
        except Exception:
            pass

    def _premium_multichat_stream(self, pid, body):
        """Ask-all: fan the question to every model in the conversation, in parallel.
        Streams newline-delimited JSON frames: {"m": idx, "d": "..."} for tokens and
        {"m": idx, "done": true} when a model finishes. The phone routes each to a column."""
        content = (body.get("content") or "").strip()
        attachments = body.get("attachments") or []
        if not content and not attachments:
            raise ApiError(400, "empty message")
        if not get_openrouter_key():
            raise ApiError(400, "no OpenRouter key set (add it in Settings)")

        c = load_premium(pid)
        models = c.get("models") or ([c.get("model")] if c.get("model") else [])
        models = [m for m in models if m][:3]
        if not models:
            raise ApiError(400, "no models selected")

        with _store_lock:
            stored = []
            for a in attachments:
                item = {"kind": a.get("kind"), "name": a.get("name"), "mime": a.get("mime")}
                if a.get("kind") == "image" or (a.get("mime") or "").startswith("image/"):
                    item["data"] = a.get("data")
                stored.append(item)
            um = {"id": uuid.uuid4().hex, "role": "user", "text": content, "ts": time.time()}
            if stored:
                um["attachments"] = stored
            c["messages"].append(um)
            if c.get("title") in (None, "", "New chat"):
                first = content.splitlines()[0] if content else "New chat"
                c["title"] = first[:40]
            save_premium(c)

        _store_attachments("premium", pid, attachments)

        if maybe_compact(c, PREMIUM_KEEP_RECENT, PREMIUM_CTX_BUDGET,
                         lambda p: openrouter_complete(get_summary_model(),
                                                       [{"role": "user", "content": p}])):
            with _store_lock:
                save_premium(c)

        hist = list(c["messages"])
        done = int(c.get("compacted_count", 0))
        prefix = premium_system() + list(compaction_prefix(c))

        # Each model sees the shared summary, the user turns, and ITS OWN prior replies.
        def build_for(model_id):
            out = list(prefix)
            seen = 0
            for i, m in enumerate(hist):
                role = m.get("role")
                if role not in ("user", "assistant"):
                    continue
                idx = seen
                seen += 1
                if idx < done:
                    continue
                if role == "user":
                    if i == len(hist) - 1 and attachments:
                        out.append({"role": "user", "content": _premium_content(m["text"], attachments)})
                    else:
                        txt = m["text"]
                        atts = m.get("attachments")
                        if atts:
                            names = ", ".join(a.get("name", "file") for a in atts)
                            txt = (txt or "") + f"\n[earlier you attached: {names} (open the files folder to resend it)]"
                        out.append({"role": "user", "content": txt})
                elif role == "assistant" and m.get("model") == model_id:
                    out.append({"role": "assistant", "content": m["text"]})
            return out

        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        wlock = threading.Lock()
        accs = [{"t": ""} for _ in models]
        img_urls = [[] for _ in models]

        def write_frame(obj):
            b = (json.dumps(obj) + "\n").encode("utf-8")
            with wlock:
                try:
                    self.wfile.write(("%X\r\n" % len(b)).encode("ascii"))
                    self.wfile.write(b)
                    self.wfile.write(b"\r\n")
                    self.wfile.flush()
                except Exception:
                    pass

        def run_model(idx, model_id):
            try:
                def on_piece(p):
                    accs[idx]["t"] += p
                    write_frame({"m": idx, "d": p})
                def on_image(url):
                    img_urls[idx].append(url)
                    write_frame({"m": idx, "d": "\n[generated an image]"})
                openrouter_chat_stream(model_id, build_for(model_id), on_piece, on_image)
            except ApiError as e:
                msg = f"\n[error: {e.msg}]"
                accs[idx]["t"] += msg
                write_frame({"m": idx, "d": msg})
            except Exception as e:
                msg = f"\n[error: {e}]"
                accs[idx]["t"] += msg
                write_frame({"m": idx, "d": msg})
            if not accs[idx]["t"].strip() and not img_urls[idx]:
                accs[idx]["t"] = "[no content returned by the model]"
                write_frame({"m": idx, "d": accs[idx]["t"]})
            write_frame({"m": idx, "done": True})

        threads = [threading.Thread(target=run_model, args=(i, m)) for i, m in enumerate(models)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        try:
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except Exception:
            pass
        try:
            with _store_lock:
                c2 = load_premium(pid)
                for idx, model_id in enumerate(models):
                    am = {"id": uuid.uuid4().hex, "role": "assistant",
                          "text": accs[idx]["t"], "model": model_id, "ts": time.time()}
                    atts = [a for a in (_image_url_to_attach(u) for u in img_urls[idx]) if a]
                    if atts:
                        am["attachments"] = atts
                        _store_attachments("premium", pid, atts)
                    c2["messages"].append(am)
                save_premium(c2)
        except Exception:
            pass

    def _premium_agent_chat_stream(self, aid, body):
        """A premium agent: reads ONLY premium conversations, answers on a cloud model."""
        content = (body.get("content") or "").strip()
        if not content:
            raise ApiError(400, "empty message")
        if not get_openrouter_key():
            raise ApiError(400, "no OpenRouter key set (add it in Settings)")

        a = load_premium_agent(aid)
        if a.get("hidden"):
            raise ApiError(403, "agent is hidden")
        model = (body.get("model") or a.get("model") or "").strip()
        if not model:
            model = get_summary_model()  # fall back to a known cheap model
        if not model:
            raise ApiError(400, "no model set for this agent")

        # Retrieve from the PREMIUM index only (private side is never read). Clean error
        # before we start streaming.
        try:
            hits = premium_retrieve(content)
        except ApiError:
            raise
        except Exception as e:
            raise ApiError(502, f"retrieval failed: {e}")
        if hits:
            ctx = "\n\n".join(f"[from \"{h['title']}\"]\n{h['text'][:800]}" for h in hits)
        else:
            ctx = "(no premium conversations matched this question yet)"
        system = _premium_agent_persona(a.get("name", "Agent"), a.get("job", ""), ctx)

        with _store_lock:
            um = {"id": uuid.uuid4().hex, "role": "user", "text": content, "ts": time.time()}
            a["messages"].append(um)
            a["model"] = model
            save_premium_agent(a)

        if maybe_compact(a, PREMIUM_KEEP_RECENT, PREMIUM_CTX_BUDGET,
                         lambda p: openrouter_complete(get_summary_model(),
                                                       [{"role": "user", "content": p}])):
            with _store_lock:
                save_premium_agent(a)

        msgs = [{"role": "system", "content": system}] + list(compaction_prefix(a))
        done = int(a.get("compacted_count", 0))
        seen = 0
        for m in a["messages"]:
            if m.get("role") not in ("user", "assistant"):
                continue
            idx = seen
            seen += 1
            if idx < done:
                continue
            msgs.append({"role": m["role"], "content": m["text"]})

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
            openrouter_chat_stream(model, msgs, on_piece)
        except ApiError as e:
            msg = f"\n[error: {e.msg}]"
            acc["t"] += msg
            try:
                write_chunk(msg)
            except Exception:
                pass
        except Exception as e:
            msg = f"\n[error: {e}]"
            acc["t"] += msg
            try:
                write_chunk(msg)
            except Exception:
                pass
        if not acc["t"].strip():
            acc["t"] = "[no content returned by the model]"
            try:
                write_chunk(acc["t"])
            except Exception:
                pass
        try:
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except Exception:
            pass
        try:
            with _store_lock:
                a2 = load_premium_agent(aid)
                a2["messages"].append({"id": uuid.uuid4().hex, "role": "assistant",
                                       "text": acc["t"], "model": model, "ts": time.time()})
                save_premium_agent(a2)
        except Exception:
            pass


# ---------------------------------------------------------------- run
class _QuietHTTPServer(ThreadingHTTPServer):
    """ThreadingHTTPServer that doesn't dump a traceback when the phone drops a connection
    mid-request (WinError 10054 / aborted / broken pipe). Those are normal and harmless."""
    def handle_error(self, request, client_address):
        et = sys.exc_info()[0]
        if et is not None and issubclass(et, (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)):
            return
        super().handle_error(request, client_address)


if __name__ == "__main__":
    print("wAide Conversations service")
    print(f"  data:   {DATA_DIR}")
    print(f"  ollama: {OLLAMA}")
    print(f"  listen: {HOST}:{PORT}")
    httpd = None
    for _ in range(30):
        try:
            httpd = _QuietHTTPServer((HOST, PORT), Handler)
            break
        except OSError as e:
            print(f"  bind not ready ({e}); retrying in 2s...")
            time.sleep(2)
    if httpd is None:
        raise SystemExit("could not bind to the address")
    print("  running. leave this window open.")
    httpd.serve_forever()
