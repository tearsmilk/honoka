# honoka — Minecraft CVE regression harness

A **defensive** regression-testing harness for verifying that fixes for
**known, already-public, already-patched** Minecraft vulnerabilities still hold
on newer client/server versions. Built for responsible disclosure: the output is
a per-CVE verdict (`INERT` / `TRIGGERED` / `INCONCLUSIVE`) and a report ready to
file with Mojang.

## What this is NOT

- It contains **no weaponized exploits** and no payloads that execute code.
- It does **not** hunt for new (0-day) vulnerabilities.
- Every detector observes a *passive indicator* (an outbound connection to a
  local canary, a crashed/hung process, a file written outside a target dir).
  That is all that is needed to answer the only question regression testing
  asks: *does the patch still hold?*

The controlled inputs that drive each probe are **not shipped**. The researcher
supplies them from the public CVE write-up, inside an isolated lab. The harness
only orchestrates versions and reads indicators.

## Rules of engagement

1. Run **only** against versions and instances you own, in a network-isolated VM
   with no route to real players or the public internet (except the local canary).
2. Only test **published** CVEs. Report findings through official channels:
   - Non-security bugs: https://bugs.mojang.com
   - Security vulnerabilities: https://hackerone.com/mojang (Mojang Bug Bounty)
3. Snapshot the VM before/after each run. Never reuse a tainted instance.

## Layout

```
config/cve_matrix.yml      # public CVEs -> affected versions -> expected indicator
harness/run.py             # orchestrator: pick version, drive probe, read indicator, verdict
harness/canary.py          # isolated listener that records inbound connections (network indicator)
harness/detectors/         # indicator readers (network / crash / filesystem) — no payloads
reports/templates/         # report skeletons for bugs.mojang.com and HackerOne
docs/METHODOLOGY.md        # how a regression run is structured and judged
```

## Quick start

```bash
python3 harness/run.py --list                 # show configured CVEs
python3 harness/run.py --cve CVE-2021-44228    # dry-run plan (no probe input configured -> INCONCLUSIVE)
```

See `docs/METHODOLOGY.md` before running anything.
