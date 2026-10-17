# Regression methodology

The harness answers one question per CVE: **does the shipped fix still hold on the
target version?** It does so by replaying the *observable signature* of a
published vulnerability and checking whether its passive indicator fires.

## The three-point matrix

For every CVE, run the probe against three version classes:

| Class            | Version                       | Expected indicator | Purpose                         |
|------------------|-------------------------------|--------------------|---------------------------------|
| Control (vuln)   | a version from the CVE's affected range | **FIRES**          | proves the probe + indicator work |
| First patched    | the `fixed in` version        | **does not fire**  | proves the fix landed           |
| Current target   | 1.21.11                       | **does not fire**  | the regression result you report |

If the control does not fire, the test is `INCONCLUSIVE` — the probe or indicator
is wrong, not the patch. Fix the harness, not the conclusion.

## Indicators (what "fired" means)

The harness never asks "did code execute?". It reads a benign side-effect:

- **network** — the client/server made an outbound connection to the local
  canary listener (`harness/canary.py`). Used for resolver-style issues
  (e.g. JNDI lookups). No code runs on the canary; it only logs the connect.
- **crash** — the target process died or hung past a timeout on a malformed
  input. Used for DoS / parser-robustness issues.
- **filesystem** — a file appeared outside the designated target directory.
  Used for path-traversal issues (e.g. resource-pack extraction).

A detector returns `FIRED`, `NOT_FIRED`, or `ERROR`.

## Verdicts

| Control | Target | Verdict        | Meaning                                        |
|---------|--------|----------------|------------------------------------------------|
| FIRES   | no     | `INERT`        | fix holds on target — the normal, good result  |
| FIRES   | yes    | `TRIGGERED`    | regression! fix does not hold — report it       |
| no      | —      | `INCONCLUSIVE` | harness problem — fix probe/indicator and rerun |

## Supplying probe input

The harness ships **no** vulnerability payloads. Each CVE entry in
`config/cve_matrix.yml` names an `input_ref` (the public advisory / tracker ID).
In the isolated lab the researcher drops the controlled input described there
into `lab/inputs/<cve>/` (git-ignored). The harness picks it up by reference and
feeds it to the probe. Without it, the run is `INCONCLUSIVE` by design — the repo
stays free of weaponized material.

## Isolation checklist (per run)

- [ ] VM has no network route except to the canary host/port
- [ ] clean snapshot restored before the run
- [ ] only versions/instances you own
- [ ] CVE is already public and patched
- [ ] report drafted from `reports/templates/`
