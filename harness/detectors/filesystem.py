"""Filesystem indicator: did a file appear outside the allowed sandbox dir?

Used for path-traversal regressions (e.g. pack extraction). Snapshot the set of
paths under `watch_root` before the probe, call `check` after; any new path that
resolves outside `sandbox_dir` means the traversal fix did NOT hold.
"""
import os

from . import FIRED, NOT_FIRED, ERROR


def snapshot(watch_root: str) -> set:
    found = set()
    for base, _dirs, files in os.walk(watch_root):
        for name in files:
            found.add(os.path.realpath(os.path.join(base, name)))
    return found


def check(before: set, watch_root: str, sandbox_dir: str) -> str:
    try:
        sandbox = os.path.realpath(sandbox_dir)
        after = snapshot(watch_root)
        new_paths = after - before
        for path in new_paths:
            # A new file whose real path is not under the sandbox = escape.
            if not (path == sandbox or path.startswith(sandbox + os.sep)):
                return FIRED
        return NOT_FIRED
    except OSError:
        return ERROR
