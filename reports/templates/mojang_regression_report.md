# Regression report — <CVE-ID>

**Filed to:** <https://bugs.mojang.com | https://hackerone.com/mojang>
**Reporter:** <name / handle>
**Date:** <YYYY-MM-DD>

## Summary

One line: does the fix for <CVE-ID> still hold on <target version>?

**Verdict:** `INERT` | `TRIGGERED` | `INCONCLUSIVE`

## Scope & isolation

- Tested only versions/instances I own, in a network-isolated VM.
- CVE is already public; this is a regression check of the shipped fix.
- No new payload developed; probe input taken from the public advisory
  (`input_ref`).

## Three-point result

| Class          | Version        | Indicator | Fired? |
|----------------|----------------|-----------|--------|
| Control (vuln) | <affected>     | <net/crash/fs> | yes (expected) |
| First patched  | <fixed_in>     | same      | no (expected)  |
| Target         | <target>       | same      | <observed>     |

## Indicator detail

- Type: network / crash / filesystem
- What was observed: <e.g. no connection to local canary 127.0.0.1:18444>
- Measurement window: <timestamps>

## Reproduction (regression only)

1. Restore clean VM snapshot.
2. Start canary / set up watch dir (if applicable).
3. Launch <version>.
4. Apply the controlled input described in <input_ref>.
5. Read indicator; record fired/not-fired.

## Conclusion

<e.g. "Fix holds on 1.21.11; indicator did not fire. No action needed." or
"Regression: indicator fired on 1.21.11 — fix appears to have regressed.">

## Attachments

- canary log excerpt / process exit record / fs diff
- VM snapshot id
