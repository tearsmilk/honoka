"""Indicator detectors. Each returns one of: FIRED, NOT_FIRED, ERROR.

A detector observes a benign side-effect only. It never executes probe input
and never confirms "code ran" — just whether the passive indicator appeared.
"""

FIRED = "FIRED"
NOT_FIRED = "NOT_FIRED"
ERROR = "ERROR"
