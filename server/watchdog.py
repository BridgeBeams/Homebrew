"""wAide watchdog: keeps the server running, headless, and restarts it if it dies
or when a restart is requested. Launched at login by the 'wAide Server' scheduled
task (via pythonw, so there is no console window)."""
import os
import sys
import time
import subprocess

WAIDE  = r"C:\waide"
SERVER = os.path.join(WAIDE, "waide_server.py")
WLOG   = os.path.join(WAIDE, "watchdog.log")
SLOG   = os.path.join(WAIDE, "server.log")
FLAG   = os.path.join(WAIDE, "restart.flag")   # create this file to request a restart
NO_WINDOW = 0x08000000                           # CREATE_NO_WINDOW


def wlog(msg):
    try:
        with open(WLOG, "a", encoding="utf-8") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n")
    except Exception:
        pass


def ensure_tailscale():
    """Bring Tailscale up if it isn't already. Harmless when already connected."""
    for exe in ("tailscale", r"C:\Program Files\Tailscale\tailscale.exe"):
        try:
            subprocess.run([exe, "up"], timeout=30, creationflags=NO_WINDOW)
            return
        except Exception:
            continue


def main():
    wlog("watchdog started")
    ensure_tailscale()
    py = sys.executable or "pythonw.exe"
    try:
        if os.path.exists(FLAG):
            os.remove(FLAG)
    except Exception:
        pass

    while True:
        try:
            wlog("starting server")
            log = open(SLOG, "a", encoding="utf-8", buffering=1)
            log.write(time.strftime("\n==== %Y-%m-%d %H:%M:%S server start ====\n"))
            p = subprocess.Popen([py, SERVER], cwd=WAIDE,
                                 stdout=log, stderr=log, creationflags=NO_WINDOW)
            # Watch it: exit the inner loop when the server dies or a restart is asked for.
            while p.poll() is None:
                if os.path.exists(FLAG):
                    wlog("restart requested")
                    try:
                        os.remove(FLAG)
                    except Exception:
                        pass
                    try:
                        p.terminate()
                        p.wait(timeout=5)
                    except Exception:
                        try:
                            p.kill()
                        except Exception:
                            pass
                    break
                time.sleep(1)
            wlog("server stopped (code %s); relaunching in 2s" % p.returncode)
        except Exception as e:
            wlog("launch error: %s" % e)
        time.sleep(2)


if __name__ == "__main__":
    main()
