"""Crash/hang indicator: did the target process die or hang on malformed input?

Given a started subprocess handle and a timeout, returns FIRED if the process
exited abnormally or had to be killed for exceeding the timeout (a hang).
Used for DoS / parser-robustness regressions.
"""
import subprocess

from . import FIRED, NOT_FIRED, ERROR


def check(proc: subprocess.Popen, timeout: float) -> str:
    try:
        rc = proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        # Still running past the window -> treat as hang (indicator fired).
        proc.kill()
        return FIRED
    except Exception:
        return ERROR
    # Non-zero / signal exit on the probe input counts as fired.
    return FIRED if rc != 0 else NOT_FIRED
