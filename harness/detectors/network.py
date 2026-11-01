"""Network indicator: did the target connect to the canary during the window?

Reads the canary log and checks for any CONNECT line newer than `since`.
Used for resolver-style regressions (e.g. JNDI). A connection means the fix
did NOT neutralize the marker; no connection means it held.
"""
import datetime
import os

from . import FIRED, NOT_FIRED, ERROR


def check(canary_log: str, since: datetime.datetime) -> str:
    if not os.path.exists(canary_log):
        # No log yet means nothing ever connected.
        return NOT_FIRED
    try:
        with open(canary_log, "r", encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if not line or "CONNECT" not in line:
                    continue
                stamp = line.split(" ", 1)[0].rstrip("Z")
                try:
                    when = datetime.datetime.fromisoformat(stamp)
                except ValueError:
                    continue
                if when >= since:
                    return FIRED
        return NOT_FIRED
    except OSError:
        return ERROR
