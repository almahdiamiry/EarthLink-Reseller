# Lesson Learned: Stage-Faithful Probes

**Identifier:** `LL-STAGE-FAITHFUL-PROBES`
**Status:** Practical forensic/testing technique; non-authoritative.

## Core Principle

> **A probe for a specific pipeline stage must use the artifact or state from that same
> stage. A post-import or post-runtime database cannot be used to verify what would have
> occurred during the import process.**

More generally: a probe must reconstruct **the population the stage under test actually
saw**, not a later or richer population that happens to exist on disk. Where a pipeline
writes, then keeps mutating its own output, "the current database" and "the database at
that moment" are different objects, and only the second one is admissible evidence for
that stage.

---

## What Happened

Investigating `UtowerDebtResolver` Priority-3 (candidate **B1**), a probe was run against
`.forensic-bug02/real_data.json` — a **post-import export of the production database**. It
computed, per account, `txs.filter { it.occurredAt > resetMs }` and reported:

```text
accounts with reset date + post-reset ledger rows : 42
reaching Priority 2 (short-circuit)               : 15
reaching Priority 3 (the suspected sum)           : 27
with a divergent value vs the baseline            : 19
maximum divergence                               : 200,000 IQD
```

That is a large, alarming, entirely fictional number. B1 was parked one round with a
200,000-IQD exposure.

Re-running the **real** production path — `UtowerImporter.importFromFile(real
utower_data_c.tgz, shouldReplace = true)` into a disposable in-memory `AppDatabase`, at
HEAD — produced:

```text
accounts / ledger entries : 216 / 2690   (all isSnapshotHistory = true)
accounts qualifying       : 15
reaching Priority 3       : 0
accounts whose openingDebtIqd changed     : 0
maximum divergence        : 0.0 IQD
```

### The root cause of the phantom

`reconcilePostResetDebts` executes at the end of `UtowerImporter.commitAll`, when the
database contains **only the 2690 imported snapshot rows**. The export used in the first
probe contained **2761** rows: the same 2690 plus **71 runtime rows the app created during
real use afterwards**.

The probe's date filter `occurredAt > resetMs` therefore swept those later runtime renewals
into `postResetTxs` and manufactured 27 Priority-3 candidates out of rows the reconcile pass
had never seen. The tell was present in the data and was simply not read:

```text
priority3WithSnapshotHistoryRows = 0
```

**All 27** phantom candidates had *zero* snapshot-history post-reset rows. Every one was
runtime-only. A single extra column in that probe would have refuted the finding before it
was ever written up.

### Two oracles, one conclusion

| Metric | Real import, real tgz, HEAD | Real EarthLink backup (independent) |
|:---|:---|:---|
| accounts / ledgers | 216 / 2690 (all snapshot) | 216 / 2761 (2690 snapshot + 71 runtime) |
| `UTOWER_CURRENT_STATE` | 201 | 201 |
| `UTOWER_SNAPSHOT_RESOLVED` | 15 | 15 |
| reaching Priority 3 | **0** | **0 with snapshot rows** (27 are runtime-only) |

The 71-row delta is exactly the runtime activity, and it is the *entire* source of the
phantom population. The independent backup confirmed it without sharing a code path.

---

## Why It Mattered

1. **It manufactures false positives at scale.** A post-stage database is *always* a superset
   of what the stage saw. Any filter the stage applied (`occurredAt > t`, `status = 'X'`,
   `isSnapshotHistory = false`) will appear to have been violated, because later rows were
   never bound by it.
2. **The error survives scrutiny because the numbers look real.** 42 / 27 / 19 / 200,000 IQD
   are all drawn from genuine production data. Nothing about the *output* was fabricated —
   only the *population* was wrong. Eyeballing the report cannot detect this; only
   reconstructing the stage does.
3. **It burns rounds and damages trust.** B1 had already been carried as a live financial
   finding with a quantified exposure. Every round spent defending it is a round not spent
   on real defects.
4. **It also inverts the historical record.** The pre-existing note claimed B1 was
   unreproducible because "the export lacks `lastDebtResetDate`". That blocker was itself
   false — the field is present in 57 accounts and `UtowerDateParser.parseBghDate` has an
   epoch-millis fallback. Both directions of that claim were wrong, and only running the
   real path corrected either.

---

## What to Do Differently

1. **Identify the stage, then name its artifact.** Before probing, write down the pipeline
   stage under test and the exact population that stage had available at that instant. If
   the stage's input is an archive (`*.tgz`) or a file, run the stage; do not substitute a
   database derived from a later run of it.
2. **Run the real entrypoint.** Prefer `importFromFile(realArchive, shouldReplace = true)`
   over calling an internal resolver directly. A direct call to the suspect function cannot
   answer *"did the production path actually reach it?"* — which was the whole question.
   Reuse an existing harness (e.g. `docs/historical/evidence/utower/UtowerDataCAuditTest.kt`)
   rather than inventing a new one.
3. **Diff the population against the stage's own output.** After a real run, compare the row
   and flag counts with the artifact used in any earlier probe. A mismatch is the signal
   that the earlier population was inadmissible.
4. **Always report the discriminating column.** For B1 that was `postSnapshot`. A candidate
   report must include a column that *could have refuted it*. If no reported column could
   have refuted the finding, the finding is not yet gated.
5. **Treat "it did not happen in production" and "it cannot happen" as different verdicts.**
   The correct close here was **CLOSED — FALSE POSITIVE** (the real path cannot produce the
   state), not "harmless". The asymmetry in `UtowerImporter:1696` — which omits the
   `isSnapshotHistory` exclusion that `BalanceCalculator.reconstructCurrentPosition:70-74`
   applies — remains real and remains unproven reachable. That is hardening, not a bug.
6. **Do not let a "blocked/unreproducible" historical note substitute for a run.** The
   cheapest next step was one import. It had been deferred across rounds on the strength of
   a blocker that turned out to be untrue.

---

## Anti-Patterns to Avoid

* **Stage-Adjacent Substitution.** Using a post-import/post-deploy database to answer a
  question about the import/deploy itself.
* **Derived-Artifact Substitution.** Using a JSON export, a test fixture, or a synthetic
  rebuild when the real source artifact is available. Convenient, and quietly wrong.
* **Orphaned Aggregate Assertions.** Reporting "27 accounts affected" without reporting the
  property that would have refuted it (`0` had snapshot rows).
* **Trusting a Script's Own Count.** A SQLite cursor-reuse bug (an inner `execute()`
  clobbering an outer iteration) reported a population of `0` where a direct query for one
  known account returned `4`. The count was only caught because it disagreed with a value
  established earlier. **Cross-check aggregate output against at least one known-good
  record.**
* **Confusing "no later overwrite" with "nothing happened".** When a finding appears to
  vanish between two points, the default hypothesis must be *population error*, not a
  hidden correcting stage. Both were checked here; only the first was true.

---

## Practical Rule of Thumb

For any forensic claim about stage **S**, ask:

```text
1. What population did S actually hold at the instant it ran?
2. Is my evidence drawn from that population, or from a later one?
3. What single column would refute my claim, and did I report it?
4. Does an independent artifact reproduce the same numbers?
5. Is my verdict "did not occur" (evidence) or "cannot occur" (reachability)?
```

If (2) is uncertain, the claim is not yet gated — run the stage.

---

## Cross-References

* `LL-BUG-HUNT-METHODOLOGY.md` — "valid fixture before adversarial mutation" and the
  executed-evidence gates. This lesson is the population-level companion to that rule.
* `LL-CAUSAL-VERIFICATION.md` — execute the real production target rather than a replica.
  This lesson adds: execute it against the *stage's own* input and observe *that stage's*
  state.
* `LL-ROUND-13-TEST-EVIDENCE-AUDIT.md` — the same discipline applied to tests rather than
  probes; 14 confirmed-fake tests there, and here one confirmed-fake *finding*.
* `LL-ROUND-2-CLOSED-FINDINGS.md`, `LL-ROUND-3-REJECTED-FINDINGS.md` — the standing
  practice of recording rejected candidates so they are not rediscovered.

> **Note for Developers & AI Agents:**
> This is a forensic/testing technique. It is not an administrative gate, certification
> requirement, or audit protocol, and it does not by itself authorise changing production
> behaviour.
