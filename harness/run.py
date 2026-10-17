#!/usr/bin/env python3
"""Regression orchestrator.

Picks a CVE from config/cve_matrix.yml, plans the three-point run
(control / first-patched / target), and — when the researcher has supplied the
controlled probe input in the isolated lab — reads the indicator and prints a
verdict: INERT / TRIGGERED / INCONCLUSIVE.

This file ships no vulnerability payloads. If the local input for a CVE is
absent, the run is reported INCONCLUSIVE by design.

    python3 harness/run.py --list
    python3 harness/run.py --cve CVE-2021-44228
"""
import argparse
import datetime
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
LAB_INPUTS = os.path.join(ROOT, "lab", "inputs")


def _load_matrix(path):
    """Minimal YAML reader for this fixed-shape config (no PyYAML dependency)."""
    try:
        import yaml  # type: ignore
        with open(path, "r", encoding="utf-8") as fh:
            return yaml.safe_load(fh)
    except ImportError:
        return _parse_simple_yaml(path)


def _parse_simple_yaml(path):
    """Tiny fallback parser for the known structure of cve_matrix.yml."""
    data = {"defaults": {}, "cves": []}
    section = None
    current = None
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if line.startswith("defaults:"):
                section = "defaults"
                continue
            if line.startswith("cves:"):
                section = "cves"
                continue
            if section == "defaults" and line.startswith("  ") and ":" in line:
                k, v = line.strip().split(":", 1)
                data["defaults"][k.strip()] = v.strip().strip('"')
            elif section == "cves":
                if line.lstrip().startswith("- "):
                    current = {}
                    data["cves"].append(current)
                    k, v = line.lstrip()[2:].split(":", 1)
                    current[k.strip()] = v.strip().strip('"')
                elif current is not None and ":" in line:
                    k, v = line.strip().split(":", 1)
                    current[k.strip()] = v.strip().strip('"')
    return data


def _input_present(cve_id):
    d = os.path.join(LAB_INPUTS, cve_id)
    return os.path.isdir(d) and bool(os.listdir(d))


def _verdict(control_fired, target_fired):
    if not control_fired:
        return "INCONCLUSIVE", "control did not fire — fix the probe/indicator, not the conclusion"
    if target_fired:
        return "TRIGGERED", "regression: the fix does NOT hold on target — report to Mojang"
    return "INERT", "fix holds on target — expected good result"


def cmd_list(matrix):
    print(f"{'CVE':<28} {'INDICATOR':<11} {'AFFECTED':<22} FIXED_IN")
    for c in matrix["cves"]:
        print(f"{c.get('id',''):<28} {c.get('indicator',''):<11} "
              f"{c.get('affected',''):<22} {c.get('fixed_in','')}")


def cmd_plan(matrix, cve_id):
    target = matrix["defaults"].get("target", "1.21.11")
    entry = next((c for c in matrix["cves"] if c.get("id") == cve_id), None)
    if not entry:
        print(f"unknown CVE id: {cve_id}", file=sys.stderr)
        return 2

    has_input = _input_present(cve_id)
    print(f"== {entry.get('id')} — {entry.get('name','')} ==")
    print(f"component : {entry.get('component','')}")
    print(f"indicator : {entry.get('indicator','')}")
    print(f"input_ref : {entry.get('input_ref','')}")
    print(f"expectation: {entry.get('expectation','')}")
    print()
    print("three-point run:")
    print(f"  control (vuln)  : {entry.get('affected','?'):<22} expect FIRES")
    print(f"  first patched   : {entry.get('fixed_in','?'):<22} expect does-not-fire")
    print(f"  target          : {target:<22} expect does-not-fire")
    print()
    if not has_input:
        print(f"[plan only] no controlled input at lab/inputs/{cve_id}/")
        print("verdict   : INCONCLUSIVE — supply input in the isolated lab, then rerun")
        print()
        print("See docs/METHODOLOGY.md. This repo ships no payloads by design.")
        return 0

    # With input present, the researcher wires the probe driver + indicator read
    # here (per docs/METHODOLOGY.md). Left as an explicit lab step so no payload
    # orchestration lives in the committed tree.
    print(f"[input present] lab/inputs/{cve_id}/ — drive probe per methodology,")
    print("then record control_fired / target_fired to compute the verdict:")
    print("  _verdict(control_fired, target_fired)")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Minecraft CVE regression orchestrator")
    ap.add_argument("--matrix", default=os.path.join(ROOT, "config", "cve_matrix.yml"))
    ap.add_argument("--list", action="store_true", help="list configured CVEs")
    ap.add_argument("--cve", help="plan a regression run for one CVE id")
    args = ap.parse_args()

    matrix = _load_matrix(args.matrix)
    if args.list:
        cmd_list(matrix)
        return 0
    if args.cve:
        return cmd_plan(matrix, args.cve)
    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
