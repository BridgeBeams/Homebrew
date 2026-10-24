#!/usr/bin/env python3
"""
Keeps waide_server.py alive, completely invisibly.

Launched by pythonw.exe (which has no console window), this starts the service
and relaunches it a few seconds after it ever exits. The service is started with
the CREATE_NO_WINDOW flag too, so nothing ever flashes on screen.
"""
import os
import sys
import time
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
SVC = os.path.join(HERE, "waide_server.py")
CREATE_NO_WINDOW = 0x08000000  # Windows: start the child with no console

while True:
    try:
        # sys.executable is pythonw.exe when this watchdog is run by pythonw,
        # so the service inherits the windowless interpreter.
        p = subprocess.Popen([sys.executable, SVC], creationflags=CREATE_NO_WINDOW)
        p.wait()
    except Exception:
        pass
    time.sleep(3)
