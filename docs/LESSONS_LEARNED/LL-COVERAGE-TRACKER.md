# Bug-Hunt Coverage Tracker

**Identifier:** `LL-COVERAGE-TRACKER`
**Status:** Session artifact, **not** project truth. It records which files a human/agent
audit has covered, and it is expected to go stale as the code changes.
**Baseline:** commit `ee1517a` (Rounds 4-12, 2026-09-29).
**Totals:** 61 / 61 Kotlin source files audited — 100% by file and by line.

> **Use this to avoid re-auditing clean code, not to prove correctness.**
> A file marked `SCANNED` was read and gated at the time; if it has changed since the
> round listed, treat the audit as expired.

## Authority

`LL-BUG-HUNT-METHODOLOGY.md` (how to audit) and `LL-ROUND-4-12-RESULTS.md` (what was found)
sit in `docs/LESSONS_LEARNED/`. This file is only the coverage index.

## Round-to-date result

| Round | Confirmed | Note |
|:---|:---|:---|
| 4-12 | 12 bugs | 6 High, 5 Medium, 1 Low — 0 false positives shipped |
| | ~127 candidates | rejected on real-data measurement or execution |

Rounds 5, 11 and 12 returned **zero**, which was the correct result.

## Remaining unaudited work

None at this baseline. Two known items were **examined and deliberately not fixed**; both are
recorded in `LL-ROUND-4-12-RESULTS.md` §5 and §7 rather than here:

- The unencrypted pre-restore safety backup — a **product decision**, ruled UNPROVEN because
  Target Product Contract v0.6 line 357 states encryption architecture is a technical question.
- The `searchAccounts(limit = 200)` identity page — real and measured, but UNPROVEN because no
  production path supplies a null `userId`.

## Known gaps this audit could NOT close

Per `AGENTS.md` §9.1 these sit in the `INSTRUMENTED` execution tier and **were never run**
(no device or emulator available in the audit environment):

- `app/src/androidTest/` — 4 files, 437 lines (`CoordinatorInstrumentedTest`,
  `RestoreImportInstrumentedTest`, `WorkManagerRetryInstrumentedTest`, `ExampleInstrumentedTest`).
  These cover SQLCipher, real on-device concurrency and WorkManager scheduling — precisely the
  risks the JVM/ROBOLECTRIC tiers could not reach.

Also unverified, and stated as such rather than assumed safe:

- Live Firestore behaviour and composite-index ordering.
- Real multi-device convergence.
- `UtowerDebtResolver` Priority-3 double-count: **CLOSED — FALSE POSITIVE** (verified against
  the real `utower_data_c.tgz` at HEAD; see `LL-STAGE-FAITHFUL-PROBES.md`). The earlier
  "UNRESOLVED, the export lacks `lastDebtResetDate`" blocker was itself wrong — the field is
  present in 57 accounts and `parseBghDate` parses epoch millis. The real import reaches
  Priority 3 for **0** accounts: all 15 qualifying accounts short-circuit at Priority 2
  because every post-reset snapshot row carries an explicit `debtAfter`. The asymmetry at
  `UtowerImporter.kt:1696` (no `isSnapshotHistory` exclusion, unlike
  `BalanceCalculator.reconstructCurrentPosition:70-74`) is real but **unreachable from real
  data**; treat it as hardening, not a defect.
- The 4-tuple `operation` correlation in `verifyRenewalViaStatement`: **UNPROVEN**. No ISP
  statement artifact exists in any dataset — statements are fetched live and never persisted.

## Format

```
status  file  lines  rounds  notes
```

`SCANNED` = deep-read and gated. `NOTSCANNED` = never read.
