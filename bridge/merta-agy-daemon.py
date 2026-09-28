#!/usr/bin/env python3
"""merta-agy-daemon — мост Merta -> Antigravity CLI.

Живёт в proot (рядом с agy), слушает 127.0.0.1:18080, только stdlib.

  GET  /status  -> {"ok": true, "agy_version": "1.2.12",
                    "patch": {"code": 0, "output": "..."}}   (agy-autopatch check)
  POST /patch   -> {"ok": true, "code": 0, "output": "..."} (agy-autopatch patch)
  GET  /models  -> {"ok": true, "code": 0, "output": "<сырой вывод agy models>"}
  POST /run     {prompt, conversation_id?, model?, effort?,
                 yolo?, dirs?[], timeout_s?}
                -> application/x-ndjson: строки stdout
                   `agy -p --output-format stream-json ...` как есть.

Один прогон за раз (409 BUSY при занятом). Запуск:
  nohup python3 daemon.py >>daemon.log 2>&1 &
"""
import json
import os
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 18080
HOME = os.path.expanduser("~")
# Termux-home виден и внутри proot (bind), agy может жить там (musl-static).
THOME = "/data/data/com.termux/files/home"
HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "daemon.log")
CONFIG = os.path.join(HERE, "config.json")

DEFAULT_MODEL = "gemini-3.8-flash"
SAFE_MODEL = re.compile(r"^[a-zA-Z0-9._-]+$")

# Маркеры закэшированных credentials agy (лежат в HOME, где логинились).
CRED_MARKERS = (".config/antigravity", ".agy", ".config/agy", ".antigravity",
                ".config/google-antigravity")


def load_config():
    try:
        with open(CONFIG) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def first_existing(paths):
    for p in paths:
        if p and os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    return ""


def resolve_agy(cfg):
    if cfg.get("agy_bin") and os.path.isfile(cfg["agy_bin"]):
        return cfg["agy_bin"]
    if os.environ.get("AGY_BIN") and os.path.isfile(os.environ["AGY_BIN"]):
        return os.environ["AGY_BIN"]
    cands = [os.path.join(HOME, ".agy-autopatch", "bin", "antigravity"),
             os.path.join(HOME, ".agy-autopatch", "bin", "agy"),
             os.path.join(THOME, ".local", "bin", "agy"),
             os.path.join(THOME, ".local", "bin", "antigravity"),
             os.path.join(THOME, "bin", "agy")]
    found = first_existing(cands)
    if found:
        return found
    for name in ("antigravity", "agy"):
        for d in os.environ.get("PATH", "").split(os.pathsep):
            p = os.path.join(d, name)
            if os.path.isfile(p) and os.access(p, os.X_OK):
                return p
    return ""


def resolve_patcher(cfg):
    if cfg.get("patcher_bin") and os.path.isfile(cfg.get("patcher_bin")):
        return cfg["patcher_bin"]
    if os.environ.get("AGYA_PATCHER") and os.path.isfile(os.environ["AGYA_PATCHER"]):
        return os.environ["AGYA_PATCHER"]
    cands = [os.path.join(HOME, ".agy-autopatch", "bin", "agy-autopatch"),
             os.path.join(THOME, ".agy-autopatch", "bin", "agy-autopatch"),
             os.path.join(THOME, "bin", "agy-autopatch")]
    found = first_existing(cands)
    if found:
        return found
    for d in os.environ.get("PATH", "").split(os.pathsep):
        p = os.path.join(d, "agy-autopatch")
        if os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    return ""


def find_creds_home(cfg):
    """HOME с закэшированными credentials (там agy залогинен)."""
    if cfg.get("agy_home") and os.path.isdir(cfg["agy_home"]):
        return cfg["agy_home"]
    if os.environ.get("AGY_HOME_DIR") and os.path.isdir(os.environ["AGY_HOME_DIR"]):
        return os.environ["AGY_HOME_DIR"]
    for h in (HOME, THOME):
        for m in CRED_MARKERS:
            if os.path.exists(os.path.join(h, m)):
                return h
    return HOME


CFG = load_config()
AGY = resolve_agy(CFG)
PATCHER = resolve_patcher(CFG)
AGY_HOME = find_creds_home(CFG)

DEFAULT_MODEL = "gemini-3.8-flash"
SAFE_MODEL = re.compile(r"^[a-zA-Z0-9._-]+$")

run_lock = threading.Lock()


def log(msg):
    line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
    try:
        with open(LOG, "a") as f:
            f.write(line + "\n")
    except OSError:
        pass
    print(line, flush=True)


def agy_version():
    if not AGY:
        return "not found"
    try:
        env = dict(os.environ, HOME=AGY_HOME)
        p = subprocess.run([AGY, "--version"], capture_output=True, text=True,
                           timeout=30, stdin=subprocess.DEVNULL, env=env)
        return p.stdout.strip() or p.stderr.strip()
    except Exception as e:
        return "error: %s" % e


def run_patch_cmd(args, timeout=300):
    # Явный --agy: discovery патчера ищет имя `agy`, а релиз кладёт `antigravity`.
    if not PATCHER:
        return 99, "agy-autopatch not found (put path into %s as {\"patcher_bin\": \"...\"})" % CONFIG
    cmd = [PATCHER, "--agy", AGY] + args
    log("patch-cmd: %s" % " ".join(cmd))
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                           stdin=subprocess.DEVNULL)
        log("patch-cmd done rc=%d" % p.returncode)
        return p.returncode, (p.stdout + p.stderr)[-4000:]
    except FileNotFoundError:
        return 99, "agy-autopatch not found: %s" % PATCHER
    except Exception as e:
        return 98, "patch error: %s" % e


def clean_model(raw):
    """Модель как в десктопной merta: strip префикса, safe-regex, дефолт."""
    m = (raw or "").strip()
    if m.startswith("antigravity/"):
        m = m[len("antigravity/"):]
    if not m or not SAFE_MODEL.match(m):
        return DEFAULT_MODEL
    return m


def needs_effort(model):
    """--effort понимают gemini/gpt-oss без суффикса; остальным не шлём."""
    if not (model.startswith("gemini") or model.startswith("gpt-oss")):
        return False
    return not (model.endswith("-high") or model.endswith("-medium")
                or model.endswith("-low"))


def build_agy_cmd(body):
    model = clean_model(body.get("model"))
    cmd = ["-p", body.get("prompt", ""),
           "--model", model,
           "--output-format", "stream-json"]
    cid = body.get("conversation_id") or ""
    if cid:
        cmd += ["--conversation", cid]
    effort = (body.get("effort") or "").strip().lower()
    if effort in ("low", "medium", "high", "max") and needs_effort(model):
        cmd += ["--effort", effort]
    if body.get("yolo"):
        cmd += ["--dangerously-skip-permissions"]
    for d in body.get("dirs") or []:
        if isinstance(d, str) and d.startswith("/") and ".." not in d:
            cmd += ["--add-dir", d]
    # '--' строго последним: всё после него agy считает позиционными.
    cmd = [AGY] + cmd + ["--"]
    return cmd


class Handler(BaseHTTPRequestHandler):
    server_version = "merta-agy/1.0"

    def _json(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > 1_000_000:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8", "replace"))
        except Exception:
            return {}

    def do_GET(self):
        if self.path == "/status":
            code, out = run_patch_cmd(["check", "--quiet"], timeout=120)
            self._json({"ok": True, "agy_version": agy_version(),
                        "agy_bin": AGY, "creds_home": AGY_HOME,
                        "patch": {"code": code, "output": out[-1500:]}})
        elif self.path == "/models":
            if not AGY:
                self._json({"ok": False, "error": "agy not found"}, 500)
                return
            try:
                env = dict(os.environ, HOME=AGY_HOME)
                p = subprocess.run([AGY, "models"], capture_output=True,
                                   text=True, timeout=120,
                                   stdin=subprocess.DEVNULL, env=env)
                self._json({"ok": True, "code": p.returncode,
                            "output": (p.stdout + p.stderr)[-20000:]})
            except Exception as e:
                self._json({"ok": False, "error": str(e)}, 500)
        else:
            self._json({"ok": False, "error": "unknown endpoint"}, 404)

    def do_POST(self):
        if self.path == "/patch":
            code, out = run_patch_cmd(["patch"], timeout=300)
            log("patch -> %d" % code)
            self._json({"ok": code == 0, "code": code, "output": out[-4000:]})
        elif self.path == "/run":
            body = self._read_json()
            if not (body.get("prompt") or "").strip():
                self._json({"ok": False, "error": "empty prompt"}, 400)
                return
            if not run_lock.acquire(blocking=False):
                self._json({"ok": False, "error": "busy"}, 409)
                return
            try:
                self._stream_run(body)
            finally:
                run_lock.release()
        else:
            self._json({"ok": False, "error": "unknown endpoint"}, 404)

    def _stream_run(self, body):
        if not AGY:
            self._json({"ok": False,
                        "error": "agy not found (searched proot + termux-home; " +
                                 "put path into %s as {\"agy_bin\": \"...\"})" % CONFIG}, 500)
            return
        cmd = build_agy_cmd(body)
        timeout = body.get("timeout_s") or 0
        try:
            timeout = max(0, int(timeout))
        except (ValueError, TypeError):
            timeout = 0
        log("run: model=%s conv=%s effort=%s yolo=%s dirs=%d home=%s prompt=%.60s" % (
            body.get("model") or "-", (body.get("conversation_id") or "-")[:8],
            body.get("effort") or "-", bool(body.get("yolo")),
            len(body.get("dirs") or []), AGY_HOME, body.get("prompt", "")))
        try:
            # HOME с credentials: agy ищет закэшированный логин там, где логинились.
            env = dict(os.environ, HOME=AGY_HOME)
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, text=True,
                                    bufsize=1, stdin=subprocess.DEVNULL, env=env)
        except FileNotFoundError:
            self._json({"ok": False, "error": "agy not found: %s" % AGY}, 500)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        try:
            assert proc.stdout is not None
            for line in proc.stdout:
                if line.strip():
                    chunk = (line if line.endswith("\n") else line + "\n").encode(
                        "utf-8", "replace")
                    self.wfile.write(chunk)
                    self.wfile.flush()
            rc = proc.wait(timeout=timeout or None)
            log("run done rc=%d" % rc)
        except BrokenPipeError:
            try:
                proc.kill()
            except OSError:
                pass
            log("run: client gone, killed")
        except Exception as e:
            log("run error: %s" % e)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    log("merta-agy-daemon on %s:%d (agy=%s)" % (HOST, PORT, AGY))
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    server.serve_forever()
