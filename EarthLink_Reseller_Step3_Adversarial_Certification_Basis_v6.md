# EARTHLINK RESELLER V1 — STEP 3 ADVERSARIAL CERTIFICATION BASIS
## Authority-Locked Certification Basis + Agent Execution Runbook — Final v6

**Date:** 2026-08-21  
**Document revision:** v6 — final pre-agent certification handoff  
**Project:** Earthlink Reseller V1  
**Repository:** `https://github.com/almahdiamiry/EarthLink-Reseller`  
**Current branch:** `main`  
**Current implementation HEAD:** `e060039238448e5bb0145feca618b9cd540c1f2f` (certification baseline; exact-HEAD freeze)  
**Parent implementation commits:** `d52170d` → `c810198` → `a6314b9`  
**Primary authority:** `Earthlink-Reseller_Wave1_Report_v3.md`  
**Implementation specification:** `EarthLink-Reseller_Wave1_Step1-3_Final.md`  
**Purpose:** This document is the controlled handoff from completed Step 1–3 implementation into **Adversarial Certification**. Sections 1–18 define the authority, frozen contract, verified baseline, adversarial objectives, and decision rules. Section 19 is the execution runbook; it does not create new product behavior or new durable state.

---

# 1. EXECUTIVE DECISION

## 1.1 Current State

Step 1, Step 2, and Step 3 implementation work are now treated as **implementation-complete** for the Wave 1 financial-dispatch scope.

The final cold-start evidence gate is now closed by commit `e060039`, which is on `main` and extends the financial-correctness patch `c810198` and the recovery-state evidence patch `d52170d`.

The final durable lifecycle evidence now proves the following sequence across a real Room instance boundary:

```text
PENDING(count=0)
    ↓ atomic claim
DISPATCHING(count=1)
    ↓ external success
local materialization fails
    ↓
DISPATCHING(count=1)
    ↓ close Room instance
NEW Room instance / same database file
    ↓ persisted state verified
DISPATCHING(count=1)
    ↓ orphan reset
PENDING(count=1)
    ↓ verification / statement correlation
VERIFIED_SUCCESS
    ↓ atomic materialization
COMPLETED(count=1) + ledger entry
```

The evidence is test-only at the final gate; no production code was changed by `e060039`.

## 1.2 Certification Decision Boundary

The project should now **move to Adversarial Certification**. This is a certification-entry decision, not a claim that adversarial certification has already passed.

Adversarial Certification is **not another implementation phase**. It is a hostile verification phase whose purpose is to attempt to break the already-implemented invariants without changing the design merely because an adversarial test is difficult.

The certification question is not:

> “Does the happy path work?”

It is:

> “Can any realistic concurrency, restart, cancellation, transport, recovery-state, malformed-data, or evidence-ambiguity scenario violate the frozen Step 3 safety and financial invariants?”

---

# 2. AUTHORITY HIERARCHY FOR CERTIFICATION

Certification must evaluate the current repository against the frozen authority stack.

Priority order:

1. `AGENTS.md`
2. Frozen Wave 1 authority (`Earthlink-Reseller_Wave1_Report_v3.md`)
3. `Earthlink-Reseller_Wave1_Step1-3_Final.md`
4. Frozen authority / architecture / contract files
5. Current source code on `main`
6. Tests and generated evidence
7. Historical drafts only as context

A historical design or reviewer suggestion must not override the current frozen authority.

Certification may reopen a frozen decision **only** if an adversarial test demonstrates a concrete contradiction with the frozen invariant or a safety-critical implementation defect.

---


## 2.1 CERTIFICATION AUTHORITY RULE

This document is a **certification companion** to the frozen `Earthlink-Reseller_Wave1_Step1-3_Final.md`. It does not replace, supersede, or extend the Step 1–3 product contract.

Every `ADV-*` item is a **verification probe** derived from:
- a frozen Step 1–3 invariant;
- an explicit owner/business decision;
- a required regression;
- or a previously recorded implementation/evidence boundary.

An `ADV-*` failure must first be classified as exactly one of:

1. **Production invariant violation** — current implementation contradicts the frozen contract.
2. **Test/evidence defect** — the probe does not actually prove what it claims.
3. **Unsupported scenario** — scenario is outside the frozen V1 contract and must not silently become a new requirement.
4. **Evidence gap** — implementation may be correct, but the available evidence is insufficient.

No production design change is authorized solely because an adversarial scenario is difficult to construct or observe.

# 3. CERTIFICATION SCOPE

## 3.1 In Scope

The adversarial gate covers:

- durable dispatch claim correctness;
- duplicate dispatch prevention;
- operation-intent idempotency;
- process death / restart durability;
- cold-start orphan recovery;
- runtime sweep isolation;
- cancellation semantics;
- transport uncertainty;
- explicit business rejection;
- authentication failure handling;
- definitive success materialization;
- failure to materialize after external success;
- financial amount guards;
- activation G1-F verification;
- strict accountStatement 4-tuple correlation;
- ambiguous / missing evidence handling;
- non-financial recovery safety;
- terminal-state authority;
- same-ID divergent-payload protection;
- backup/restore regression after shared coordination changes;
- migration safety for `dispatchClaimCount`;
- absence of production bypasses to canonical financial completion.

## 3.2 Explicitly Out of Scope

Do not reopen or redesign during this gate:

- portable backup architecture;
- Demo Mode removal;
- `Double -> Long` full-domain migration;
- Audit Log API discovery POC;
- generic reconciliation architecture;
- staging database architecture;
- identity registry;
- `dataset_id` / `published_dataset_id`;
- Wave 2 UI simplification;
- broad ViewModel extraction unless a certification failure directly proves it necessary.

---

# 4. FROZEN STEP 3 CONTRACT

## 4.1 Durable Claim Contract

The only first-dispatch authorization is the SQLite conditional update:

```sql
UPDATE pending_external_operations
SET status = 'DISPATCHING',
    dispatchClaimCount = dispatchClaimCount + 1,
    updatedAt = :now
WHERE businessTransactionId = :id
  AND status = 'PENDING'
  AND dispatchClaimCount = 0;
```

Acceptance:

```text
rowsAffected == 1
```

is the sole local authorization to invoke any external mutation covered by the Step 3 dispatch paths (financial and non-financial).

## 4.2 Claim Discriminator

```text
0 = fresh / never claimed
1 = claimed at least once / recovery-blocked after unknown outcome
>1 = forbidden
```

There is no claim reset to zero.

## 4.3 Durable Lifecycle

```text
PENDING(0)
   → DISPATCHING(1)
   → COMPLETED(1)

FAILED may be produced from either a fresh pre-dispatch path or a claimed path:
PENDING(0 or 1)
   → FAILED(0 or 1)

Unknown-after-dispatch:
DISPATCHING(1)
   → recovery boundary
   → PENDING(1)
   → RESOLVING(1)
   → COMPLETED(1) / FAILED(0 or 1)
   or INCONCLUSIVE transient result
   → PENDING(1)
```

`INCONCLUSIVE` is not a durable database state.

## 4.4 Core Prohibition

A recovered operation with `dispatchClaimCount = 1` must never become automatically eligible for a second external dispatch under the same `operationIntentId`.

---

# 5. FROZEN FINANCIAL OUTCOME SEMANTICS

Every external mutation must resolve to exactly one of these categories:

| Category | Meaning | Durable Result | Ledger |
|---|---|---|---|
| `NOT_DISPATCHED` | No external mutation authorized | fresh `PENDING(0)` or no record | none |
| `BUSINESS_FAILURE` | Explicit rejection / authenticated non-execution | `FAILED` | none |
| `SUCCESS` | Definitive success or approved historical verification | `COMPLETED` | atomic materialization where financial |
| `UNKNOWN_AFTER_DISPATCH` | Network ambiguity, crash, process death, cancellation after claim | `PENDING(1)` / resolution path | none until verified |

Golden rule:

> **Unknown transport outcome is never business failure.**

---

# 6. CURRENT IMPLEMENTATION BASELINE — WHAT IS ACTUALLY VERIFIED

## 6.1 Durable Claim and Recovery

The current implementation contains:

- `dispatchClaimCount`;
- atomic `claimDispatch`;
- `transitionToResolving`;
- `getOrphanedInFlightOperations`;
- `resetOrphanedInFlightToPending`;
- `getUnresolvedClaimedOperations`;
- migration `16 -> 17`;
- cold-start recovery from `EarthlinkApp`;
- runtime sweep restricted to recovery-blocked claimed operations.

The current `AppDatabase` version is 17.

## 6.2 Financial Materializer

`resolvePendingOperationVerifiedSuccess` is the canonical verified-success financial materializer.

The current implementation now enforces:

```text
REFILL / RENEWAL / ACTIVATION
    amountIqd > 0
```

and refuses missing local financial targets.

Existing identical ledger rows are accepted idempotently only when the payload is identical; divergent same-ID payloads raise conflict.

## 6.3 Legacy Completion Bypass

`completePendingOperation` has no production callers according to the completed repository-wide audit and is deprecated in the interface and implementation.

It remains a legacy test/helper capability and should not be used as a production external-operation completion path.

## 6.4 Gateway Outcome Classification

The typed outcome boundaries explicitly established during Step 2 include the affected Gateway mutation paths and their consumers. Certification must verify each production Gateway mutation path independently; the existence of the exception hierarchy alone is not evidence that every path propagates through `safeApiCall`.

For each affected path, verify: transport uncertainty → `EarthlinkTransportException`; explicit business rejection → `EarthlinkBusinessException`; authentication failure → `EarthlinkAuthException`; cancellation propagates unchanged.

String matching on exception messages is not the intended authority.

## 6.5 G1-F

Activation / renewal recovery must not infer historical success merely from current subscriber existence or active state.

Approved recovery evidence is the strict compound `accountStatement` correlation:

```text
exact userID
+ exact operation semantics
+ exact withdrawal/deposit amount
+ ±90 second window
```

Unique match → `VERIFIED_SUCCESS`.
Ambiguous or missing → `INCONCLUSIVE`.

---

# 7. FINAL COLD-START DURABILITY EVIDENCE

## 7.1 Evidence Source

Final evidence is in:

`Step3DurableDispatchTest.test19_refillSuccessNotReportedWhenLocalMaterializationFails`

at current `main` HEAD `e060039`.

## 7.2 What the Test Now Proves

The test creates a claimed operation, causes external success followed by local materialization failure, then:

1. verifies `DISPATCHING / count=1 / lastError=null`;
2. closes the existing Room database instance;
3. constructs a **new `AppDatabase` instance** against the **same database file**;
4. constructs new repository instances attached to the new database;
5. verifies persisted `DISPATCHING / count=1` after reopen;
6. resets the orphan to `PENDING / count=1`;
7. observes that intermediate state directly;
8. executes verification from the new repository / new DB;
9. proves `VERIFIED_SUCCESS`;
10. proves `COMPLETED` and a 35,000 IQD ledger entry in the new DB.

This closes the previous evidence gap in which a single in-memory Room instance was used throughout the test.

---

# 8. ADVERSARIAL CERTIFICATION OBJECTIVES

The adversarial phase should attack each invariant independently and then in combinations.

## Objective A — Prove at-most-one dispatch authorization

Attempt concurrent first claims from multiple actors using the same `businessTransactionId` and/or `operationIntentId`.

Expected:

```text
exactly one claim succeeds
all other claims return false / zero rows
external mutation invocation count = 1
```

## Objective B — Prove no redispatch after recovery

Force:

```text
PENDING(0)
→ DISPATCHING(1)
→ durability boundary
→ reopen
```

The minimum mandatory durability boundary is: close the old Room instance, open a new `AppDatabase` instance against the same physical SQLite database file, and create a new repository instance. Do not report OS/process death as tested unless actual process termination was exercised in the test environment.

Expected:

```text
external mutation invocation count remains 1
claim count remains 1
```

## Objective C — Prove runtime sweep cannot steal an active dispatch

Start a foreground dispatch and execute background sweep while the HTTP request is still in flight.

Expected:

```text
active DISPATCHING is not inspected or resolved by the runtime sweep
```

## Objective D — Prove cancellation safety

Inject cancellation:

1. before claim;
2. during local pending creation;
3. immediately after claim;
4. during external transport;
5. immediately after external success;
6. during verification;
7. during local materialization.

Expected:

- cancellation is not swallowed;
- claim count is never decremented;
- no blind redispatch becomes enabled;
- no false `FAILED` is produced from transport uncertainty;
- no false financial `COMPLETED` is produced.

## Objective E — Prove typed outcome correctness

Inject each outcome independently:

```text
HTTP 200 + business rejection
HTTP 400
HTTP 401
HTTP 500
socket timeout
connection reset
DNS failure
malformed response
CancellationException
successful response
```

Expected mapping must exactly match the frozen outcome table.

## Objective F — Prove G1-F cannot be bypassed

Attempt to resolve activation/renewal success using:

- active subscriber state only;
- existing subscriber only;
- changed expiration with no statement proof;
- unrelated statement;
- wrong userID;
- wrong amount;
- wrong operation;
- timestamp outside ±90 seconds;
- two matching statements.

Expected:

```text
only one unique valid 4-tuple match can produce VERIFIED_SUCCESS
```

## Objective G — Prove amount integrity at both boundaries

Verify two separate guards:

1. **Pre-dispatch boundary:** finite, positive, whole-IQD, valid 250-IQD denomination; invalid values abort before persistence/claim/dispatch.
2. **Canonical materialization boundary:** invalid persisted financial amount can never produce `COMPLETED` or a financial ledger entry.

Inject:

```text
0
negative
fractional
NaN
Infinity
non-250 denomination
missing persisted amount
```

Expected:

```text
pre-dispatch invalid input → NOT-DISPATCHED
invalid persisted financial amount → fail-closed, no COMPLETED, no ledger
```

## Objective H — Prove same-ID divergent payload protection

Create an existing ledger entry with the same transaction identity, then retry materialization with:

- different account;
- different amount;
- different type.

Expected:

```text
DivergentPayloadConflictException
no overwrite
no duplicate ledger entry
no false COMPLETED transition
```

## Objective I — Prove fresh vs recovery-blocked PENDING discrimination

Test:

```text
PENDING(0)
PENDING(1)
```

Expected:

- `PENDING(0)` may be claimed once;
- `PENDING(1)` must never be claimed again;
- runtime verification may inspect only claimed unresolved records.

## Objective J — Prove migration safety

Start from a pre-17 database fixture containing unresolved pending rows.

Run migration 16 → 17.

Expected:

- column exists;
- legacy unresolved records become `dispatchClaimCount=1`;
- no historical unresolved record becomes fresh-dispatchable;
- existing ledger/account data remains intact;
- migration is schema-valid and preserves business data.

## Objective K — Verify frozen backup/restore regression (not a new Step 3 invariant)

Because the Final Specification explicitly requires backup/restore regression after shared coordinator/database changes, rerun:

- backup no-password;
- backup password-protected;
- restore checkpoint;
- backup/restore concurrent with attempted sync.

Expected:

- maintenance exclusion remains intact;
- restore safety checkpoint remains intact;
- Step 3 changes do not weaken backup/restore behavior.

---


## 8.1 ADVERSARIAL PROBE CATEGORIES

To prevent scope creep, every probe must carry one category:

| Category | Meaning | Pass Requirement |
|---|---|---|
| `FROZEN-INVARIANT` | Directly attacks a contract/invariant | Failure is a certification blocker |
| `REQUIRED-REGRESSION` | Protects a previously closed requirement | Failure reopens the affected closure item |
| `DEFENSE-IN-DEPTH` | Stronger-than-required hardening probe | Failure is not automatically a product defect |
| `EVIDENCE-ONLY` | Proves an implementation boundary that must be visible | Missing proof is an evidence gap, not a design failure |

`ADV-*` probes must not be promoted from `DEFENSE-IN-DEPTH` or `EVIDENCE-ONLY` to product invariants without explicit owner approval.

# 9. ADVERSARIAL OBJECTIVE SUMMARY MATRIX — NON-EXECUTING

> **NON-EXECUTING SUMMARY:** This section is a compact architectural summary only. It is not an independent test list and must not be executed, counted, or treated as a second certification matrix. **Section 19 is the sole executable ADV-C certification runbook.**


| ID | Attack | Expected Invariant | Severity if Broken |
|---|---|---|---|
| Summary-01 | Claim rejected / missing claim, then dispatch attempted | gateway is never invoked | P0 |
| Summary-02 | Same operationIntentId repeated/concurrent initiation | one durable identity; no second dispatch | P0 |
| Summary-03 | Claim without coordinator serialization | SQLite claim remains sole correctness gate | P0 |
| Summary-04 | Two concurrent claims same transaction | one claim only | P0 |
| Summary-05 | Two concurrent claims same intent through different local actors | one external dispatch | P0 |
| Summary-06 | Claim replay after `PENDING(1)` | claim rejected | P0 |
| Summary-07 | Crash immediately after claim | no blind retry | P0 |
| Summary-08 | Crash after server success, before local materialization | recoverable without redispatch | P0 |
| Summary-09 | Runtime sweep during active `DISPATCHING` | no sweep interference | P0 |
| Summary-10 | Cancellation immediately after claim | no decrement/reset to 0 | P0 |
| Summary-11 | Transport timeout | `INCONCLUSIVE`, no ledger | P0 |
| Summary-12 | HTTP 500 | unknown, no blind retry | P0 |
| Summary-13 | Explicit business rejection | `FAILED`, zero ledger | P0 |
| Summary-14 | 401/auth rejection | `FAILED`, token/session handling | P1 |
| Summary-15 | Malformed mutation response | unknown, no blind retry | P1 |
| Summary-16 | Active subscriber without statement | `INCONCLUSIVE` | P0 |
| Summary-17 | Wrong userID statement | `INCONCLUSIVE` | P0 |
| Summary-18 | Wrong amount statement | `INCONCLUSIVE` | P0 |
| Summary-19 | Ambiguous duplicate statement matches | `INCONCLUSIVE` | P0 |
| Summary-20 | Statement outside ±90 sec | `INCONCLUSIVE` | P0 |
| Summary-21 | Zero financial amount materialization | reject | P0 |
| Summary-22 | Negative/fractional amount | reject | P1 |
| Summary-23 | Same-ID divergent ledger payload | conflict / no overwrite | P0 |
| Summary-24 | Fresh `PENDING(0)` runtime sweep | must be excluded from verification sweep | P0 |
| Summary-25 | Recovery `PENDING(1)` verification | may enter verification/resolution, never re-enter first-dispatch authorization | P0 |
| Summary-26 | Migration legacy row | unresolved historical rows become recovery-blocked, never fresh-dispatchable | P1 |
| Summary-27 | Backup during sync/maintenance | maintenance exclusion preserved | P1 |
| Summary-28 | Restore during sync/remote apply | restore guard preserved | P1 |
| Summary-29 | Cold-start recovery rerun twice | idempotent/no redispatch | P0 |
| Summary-30 | Recovery race with foreground caller | no second claim | P0 |
| Summary-31 | Completion retry after already completed | canonical materializer returns same ledger entry/idempotent terminal result | P1 |
| Summary-32 | Unknown failure after materialization exception | no false success UI | P0 |
| Summary-33 | Production call-site audit bypass | zero live canonical bypass | P0 |
| Summary-34 | Durable claim/state writer audit | no unauthorized production writers or claim-count reset | P0 |
| Summary-35 | Activation `SUSPENDED` recovery | `FAILED(0 or 1)`, zero ledger, no redispatch | P0 |

---
# 10. REQUIRED COMBINED / COMPOSITION ATTACKS

Single-condition tests are not enough. The following combinations are mandatory.

## 10.1 Crash + Duplicate Caller

```text
Actor A claims
→ dispatches
→ process dies
→ Actor B starts
→ recovery begins
→ Actor B attempts new claim
```

Expected:

```text
A claim = 1
B claim = 0
external calls = 1
```

## 10.2 Recovery + Runtime Sweep Race

```text
Cold-start recovery discovers orphan
while SyncWorker starts concurrently
```

Expected:

- active previous-process orphan has one recovery owner;
- runtime sweep does not treat active `DISPATCHING` as a current-process verification target;
- no duplicate resolution causing divergent writes.

## 10.3 Definitive Success + Materialization Failure + Restart

```text
external success
→ local materialization throws
→ DB remains DISPATCHING(1)
→ DB close
→ new DB instance
→ PENDING(1)
→ verification
```

Expected:

```text
no redispatch
statement correlation only
one ledger entry
COMPLETED once
```

## 10.4 Ambiguous Statement + Repeated Recovery

```text
DISPATCHING(1)
→ PENDING(1)
→ ambiguous statement
→ INCONCLUSIVE
→ PENDING(1)
→ repeat recovery
```

Expected:

```text
never claim again
never external-dispatch again
remain resolvable
```

---

# 11. ADVERSARIAL CERTIFICATION METHOD

The certification agent should follow this order:

### Phase A — Structural inspection

Read:

```text
Models.kt
AppDatabase.kt
Repositories.kt
EarthlinkSearchViewModel.kt
Interfaces.kt
EarthlinkNetwork.kt
EarthlinkApp.kt
SyncWorker.kt
Step3DurableDispatchTest.kt
```

Then verify the implementation against this document and the Step 1–3 specification.

### Phase B — Call-site audit

Search for all production references to:

```text
recordPendingOperation
claimDispatchAuthorization
completePendingOperation
resolvePendingOperationVerifiedSuccess
resolvePendingOperationVerifiedFailure
resolvePendingOperationInconclusive
gateway.createTestUser
gateway.createUserUsingDeposit
gateway.refillUserDeposit
gateway.extendUser
```

The objective is to detect production bypasses, not count textual occurrences only.

### Phase C — Focused adversarial execution

Run the complete Step 3 adversarial matrix.

### Phase D — Full regression

Run:

```text
:app:testDebugUnitTest
```

and the relevant Step 2 / Step 3 / backup / restore regression groups.

### Phase E — Evidence capture

Every failed adversarial scenario must capture:

- exact commit SHA;
- exact test name;
- initial DB state;
- operation state transition;
- external invocation count;
- final DB state;
- ledger count/content;
- exception classification;
- whether failure is production bug, test defect, or evidence gap.

---

# 12. PASS / FAIL RULES

## 12.1 P0 Failure

Any of the following is immediate **NO-GO**:

- duplicate external dispatch for one `operationIntentId`;
- `dispatchClaimCount` can return from 1 to 0;
- recovery can issue a second external mutation automatically;
- transport uncertainty becomes `FAILED` incorrectly;
- active subscriber state alone produces financial `VERIFIED_SUCCESS`;
- ambiguous statement produces success;
- invalid persisted amount reaches `COMPLETED` with financial mutation;
- financial success UI is reported without canonical local materialization;
- production code can bypass claim authorization and still dispatch externally;
- runtime sweep can steal an active `DISPATCHING` operation;
- stale/recovered state can overwrite a newer financial result.

## 12.2 P1 Failure

Blocks certification unless explicitly classified as non-safety follow-up with authority approval, for example:

- missing hardening tests;
- redundant but dangerous completion helper still exposed;
- weak observability without state-corruption consequence;
- migration evidence incomplete but structurally safe.

## 12.3 Evidence Gap

An evidence gap is not automatically a production bug.

It must be recorded as:

```text
EVIDENCE GAP
```

and closed by a focused test or direct repository inspection before final certification.

---

# 13. ZERO-CHANGE RULE DURING CERTIFICATION

The adversarial agent must not immediately modify production code when a test fails.

For every failure:

1. reproduce;
2. inspect actual state;
3. determine whether the failure is:
   - real invariant violation;
   - incorrect test assumption;
   - unsupported scenario;
   - evidence gap;
4. only then propose a production patch.

No speculative abstractions are allowed.

No new durable state is allowed unless a frozen invariant is shown to be impossible to satisfy without it.

---

# 14. REQUIRED CERTIFICATION ARTIFACTS

The final adversarial certification package should contain:

```text
1. Adversarial certification report (.md)
2. Exact certified commit SHA
3. Test matrix with PASS/FAIL per ADV-* test
4. Full unit-test result
5. Step 3 focused test result
6. Backup/restore regression result
7. Production call-site audit result
8. Migration validation result
9. Explicit list of any remaining deferred items
10. Final GO / NO-GO decision
```

The evidence must always identify the repository commit under which it was generated.

---

# 15. FINAL CERTIFICATION ACCEPTANCE CRITERIA

Step 3 may receive final adversarial certification only when all of the following are true:

```text
[ ] main HEAD is the certified commit
[ ] atomic claim is verified under concurrency
[ ] no external mutation can execute without rowsAffected == 1 from the durable claim
[ ] repeated/concurrent same-operationIntentId initiation cannot create a second durable dispatch authorization
[ ] no duplicate external dispatch exists
[ ] claim count never resets to 0 after claim
[ ] restart durability is proven with a new Room instance
[ ] cold-start orphan recovery is proven
[ ] runtime sweep excludes active DISPATCHING
[ ] PENDING(0) and PENDING(1) are distinguished
[ ] cancellation preserves claim safety
[ ] transport uncertainty remains unknown/inconclusive
[ ] explicit business failure becomes FAILED
[ ] G1-F state-only success fallback is absent
[ ] strict 4-tuple correlation is proven
[ ] ambiguous/missing evidence remains INCONCLUSIVE
[ ] invalid financial amount cannot materialize
[ ] financial success cannot be shown before local materialization
[ ] same-ID divergent payload is rejected
[ ] no production bypass to canonical financial completion exists
[ ] migration 16→17 is validated
[ ] backup/restore regression is green
[ ] full unit suite is green
[ ] evidence package identifies exact commit SHA
```

All mandatory boxes must be green before `GO`.

---

# 16. EXPECTED FINAL OUTCOME

## GO

Issue:

```text
STEP 3 ADVERSARIAL CERTIFICATION = PASS
```

Then:

- freeze the certified commit as the Wave 1 G1 implementation baseline;
- record remaining G1 closure backlog items separately;
- do not reopen Step 3 without contradiction;
- proceed to the next explicitly authorized phase.

## NO-GO

Issue:

```text
STEP 3 ADVERSARIAL CERTIFICATION = BLOCKED
```

The report must identify:

```text
Invariant
↓
Failure scenario
↓
Concrete evidence
↓
Root cause
↓
Minimal corrective action
↓
Required regression
```

No broad refactor is authorized by a single adversarial failure.

---

# 17. CURRENT CERTIFICATION BASELINE

At the time this document is issued:

```text
Step 1                 CLOSED
Step 2                 CLOSED
Step 3 implementation  CLOSED
Step 3 correction      CLOSED
Cold-start durability  CLOSED
Current main           e060039238448e5bb0145feca618b9cd540c1f2f
Adversarial gate       NEXT
Certification document status  ENTRY BASIS + EXECUTION RUNBOOK (not a product/implementation authority)
```

The current final durability test is explicitly test-only and does not alter production code.

---

# 18. HANDOFF TO THE ADVERSARIAL AGENT

The next agent must read this document together with:

```text
EarthLink-Reseller_Wave1_Report_v3.md
EarthLink-Reseller_Wave1_Step1-3_Final.md
```

and the frozen authority / contract files.

Then:

> Treat `main` at the exact certified HEAD as implementation truth. Do not reopen Step 1–3 design decisions. Attack the invariants adversarially, not the architecture philosophically. Use the ADV-* matrix and combined attacks above. Any failure must first be classified as a production defect, test defect, unsupported scenario, or evidence gap before any code change is proposed.

**Certification objective:** prove that no realistic adversarial sequence can produce duplicate external dispatch, false financial success, unsafe recovery, invalid ledger materialization, or stale cross-process state corruption.

---


# 19. ADVERSARIAL CERTIFICATION EXECUTION RUNBOOK

## 19.1 Purpose

This section converts the Certification Basis into an executable agent runbook.

It defines **how to attack, what to observe, what evidence to capture, and when to stop**.

It is not a new implementation plan.

The agent must assume:

```text
Step 1 implementation = CLOSED
Step 2 implementation = CLOSED
Step 3 implementation = CLOSED
Current main = certification baseline
Adversarial work = verification only
```

---

## 19.2 Preconditions

Before executing any `ADV-*` probe, the agent must verify:

| Precondition | Required Evidence |
|---|---|
| Repository identity | `almahdiamiry/EarthLink-Reseller` |
| Branch | `main` |
| HEAD | `e060039238448e5bb0145feca618b9cd540c1f2f` |
| Parent chain | `d52170d -> c810198 -> a6314b9` |
| Step 1–3 spec present | `EarthLink-Reseller_Wave1_Step1-3_Final.md` |
| Frozen Wave 1 authority present | `Earthlink-Reseller_Wave1_Report_v3.md` |
| Relevant contracts readable | `contract/*` and G8 authority artifacts |
| Test environment known | JDK / Gradle / Android environment recorded |
| Production code freeze | No certification-run production edits before failure classification |

If HEAD differs from the certified baseline SHA at any time, **STOP certification immediately**. Do not continue against a changed HEAD and do not self-declare equivalence. A changed HEAD becomes a new certification baseline: record the new SHA, re-run baseline verification, then rerun all mandatory certification gates.

**Exact-HEAD freeze rule:** A certification result is valid only for the exact recorded baseline SHA. No agent may substitute a later or different commit based on semantic equivalence, a small diff, or test-only changes.

---


**Migration-fixture rule:** Any migration probe must start from a real compatible V16 fixture/database produced under the V16 schema. Faking a V16 version on a V17 schema is prohibited.

**Environment command rule:** Windows/Gradle invocation conventions (for example `gradlew.bat` or helper scripts) are operational guidance only unless explicitly required by `AGENTS.md` or another frozen environment authority. They are not product invariants.

## 19.3 Existing-Test Reuse Rule

Before creating a new adversarial test:

1. Search for an existing test that already exercises the same invariant.
2. Prefer extending the existing test over creating a new test class.
3. Create a new test only when the existing test cannot express the adversarial condition without weakening clarity.
4. Do not create duplicate test suites for the same invariant.

Known reusable suites include:

```text
Step3DurableDispatchTest
Step2OutcomeResolutionTest
Phase1UnknownOutcomeResolutionTest
Phase1DuplicateInitiationProtectionTest
Phase3SameLineageFinancialMutationTest
Workstream13G1RealRestartCertificationTest
```

Every test modification must record:

```text
old assertion
→ new assertion
reason for change
authority / invariant being proved
```

---

## 19.3.1 ADV-* to Existing Test / Evidence Mapping

Before creating any new test, map the probe to the strongest existing evidence source. Extend/reuse first; create a new focused test only when the existing suite cannot prove the exact adversarial condition without weakening clarity.

| Probe | Preferred existing source | Action |
|---|---|---|
| ADV-C01 | `Step3DurableDispatchTest` / claim race coverage | Extend existing claim test if needed |
| ADV-C02 | claim authorization tests | Extend focused dispatch-gate assertion |
| ADV-C03 | `Phase1DuplicateInitiationProtectionTest` + repository identity path | Reuse + add exact-intent assertion if absent |
| ADV-C04 | `Workstream13G1RealRestartCertificationTest` / `Step3DurableDispatchTest` | Extend existing restart evidence |
| ADV-C05 | `Step3DurableDispatchTest.test19` + `e060039` | Reuse exact real-Room boundary test |
| ADV-C06 | runtime sweep implementation / `SyncWorker` coverage | New focused test only if no existing proof |
| ADV-C07 | unresolved claimed-operations DAO/recovery coverage | Focused query-state assertion |
| ADV-C08 | `Step3DurableDispatchTest` | Extend existing resolving/claim tests |
| ADV-C09 | `Step2OutcomeResolutionTest` | Reuse |
| ADV-C10 | Step 2 business-failure tests | Reuse/extend |
| ADV-C11 | `Step2OutcomeResolutionTest` / ViewModel transport test | Reuse |
| ADV-C12 | cancellation-after-claim coverage | Reuse/extend |
| ADV-C13–C17 | `Step2OutcomeResolutionTest` + statement fixtures | Extend existing tests |
| ADV-C18 | `Step3DurableDispatchTest` amount validation | Reuse/extend |
| ADV-C19 | canonical materializer tests / `test20` | Reuse/extend |
| ADV-C20 | `Phase3SameLineageFinancialMutationTest` | Reuse |
| ADV-C21 | canonical completion retry coverage | Focused extension if absent |
| ADV-C22 | failure-transition tests | Focused extension if absent |
| ADV-C23 | migration tests / `MIGRATION_16_17` coverage | Reuse/extend |
| ADV-C24 | `Workstream13G1RealRestartCertificationTest` + cold-start tests | Extend snapshot-boundary proof |
| ADV-C25 | `Phase1UnknownOutcomeResolutionTest` | Reuse/extend |
| ADV-C26 | TEST-12 backup/restore regression | Reuse |
| ADV-C27 | repository-wide production call-site audit | Inspection evidence |
| ADV-C28 | typed Gateway outcome tests | Reuse/extend |
| ADV-C29 | existing manual-verification tests | Reuse; keep unsupported assertions evidence-only |
| ADV-C30 | repository/lock-hierarchy inspection | Evidence-first; add focused test only if needed |
| ADV-C31 | `Phase1DuplicateInitiationProtectionTest` | Reuse/extend |
| ADV-C32 | `Step3DurableDispatchTest.test19` + `e060039` | **Must use fresh Room instance against same DB file** |
| ADV-C33 | all four production Gateway mutation call-sites | Structural dominance audit; add focused call-site tests where needed |
| ADV-C34 | repository-wide state/claim writer audit | Inspection evidence; add focused test only where writer behavior cannot be proven statically |
| ADV-C35 | `testActivationSuspendedResolvesFailure` / activation recovery tests | Reuse; add explicit zero-ledger + FAILED + no-redispatch assertion if any element is not already proved |

If a mapping cannot be demonstrated from the repository, record the missing evidence before creating a new test.

---

## 19.4 One-Probe / One-Contract Evidence Rule

Every `ADV-*` execution record must contain all six fields:

```text
1. Setup
2. Trigger
3. Durable state before
4. Expected durable state after
5. External invocation count / duplication evidence
6. Ledger + outcome evidence
```

A statement such as:

```text
"test passed"
```

is not sufficient certification evidence by itself.

---

## 19.4.1 Mandatory Execution Gate Order

Run adversarial certification in this order. Do not begin a later gate while an unresolved P0 or frozen-contract P1 failure remains in an earlier gate.

```text
GATE-0 — Baseline / SHA / environment
GATE-1 — Claim, identity, and dispatch authorization
GATE-2 — Outcome classification and cancellation
GATE-3 — Recovery, cold-start, process-start boundary, and runtime sweep isolation
GATE-4 — G1-F and statement evidence
GATE-5 — Financial materialization, idempotency, ledger integrity, and completion writers
GATE-6 — Backup / restore regression
GATE-7 — Composition attacks
GATE-8 — Full unit regression and certification evidence packaging
GATE-9 — Final GO / NO-GO decision
```

Within each gate, prefer existing tests mapped in §19.3.1.

---

## 19.5 Mandatory Core Probes

The following probes are mandatory because they directly cover the highest-risk Step 3 invariants.

### ADV-C01 — Atomic Dual-Claim Race

**Setup**
- One fresh operation: `PENDING`, `dispatchClaimCount=0`.
- Two concurrent actors.

**Trigger**
- Both call `claimDispatchAuthorization()` for the same `businessTransactionId`.

**Expected — claim-race proof**
```text
one result = rowsAffected == 1
one result = rowsAffected == 0
final state = DISPATCHING(1)
```

**Separate call-site dispatch proof**
- In a production caller-path test, only the actor that receives successful claim authorization may invoke the Gateway mutation.
- Gateway invocation count must be at most one for the two-actor scenario.

Do not infer Gateway call-count correctness from a DAO-only claim race.

**Category:** `FROZEN-INVARIANT`

---


**Execution separation:** First prove the DAO-level race (`rowsAffected == 1` for exactly one claimant). Separately prove that only the successful claim winner is allowed to invoke the Gateway. A claim-only test cannot by itself prove external invocation count.

### ADV-C02 — No External Dispatch Without Claim

**Setup**
- Fresh `PENDING(0)` operation.
- Force claim rejection or simulate a competing winner.

**Trigger**
- Attempt caller path after `claimDispatchAuthorization()` returns false.

**Expected**
```text
gateway invocation count = 0
no external mutation
no second claim
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C03 — Same Intent Replay / Durable Identity Protection

**Setup**
- Exercise the repository/database path with an exact existing `operationIntentId` and/or `businessTransactionId`.
- Separately exercise two identical same-action UI attempts through the production ViewModel coalescer.

**Trigger**
- Repository path: repeat/concurrently submit the exact same durable intent identity.
- UI path: trigger the same logical `(account, operationType)` action twice concurrently.

**Expected**
```text
Durable identity path:
  one durable operation identity
  at most one first-dispatch claim
  no blind second external dispatch

UI path:
  second same-action tap rejected/coalesced before generating a second intent
  one logical intent generated
  one pending row
  one claim
  one external dispatch
```

A deliberately new user intent or a different logical operation must remain distinguishable from replay of an existing intent.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C04 — Crash/Restart Boundary After Claim / Before Outcome

**Setup**
```text
PENDING(0)
→ DISPATCHING(1)
```

**Trigger**
- Mandatory portable test boundary: close the current Room instance before the outcome is persisted, then open a new `AppDatabase` instance against the same physical SQLite file with a new repository instance.
- OS/process termination may be added only when the environment actually exercises it. The minimum mandatory durability boundary is: close the old Room instance → create a new AppDatabase instance → create a new repository instance → reopen the same physical SQLite file. Never report this minimum boundary as OS process-death testing.

**Expected**
```text
new Room instance sees DISPATCHING(1)
no automatic redispatch
cold-start recovery only
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C05 — Definitive External Success + Local Materialization Failure

**Setup**
- Gateway returns definitive success.
- Canonical materializer is forced to fail locally.

**Expected immediately**
```text
_actionSuccess = null
status != COMPLETED
no false success message
```

**Expected after real Room restart**
```text
DISPATCHING(1)
→ PENDING(1)
→ verification
→ COMPLETED + one ledger entry
```

**Category:** `REQUIRED-REGRESSION`

---

### ADV-C06 — Runtime Sweep Must Ignore Active DISPATCHING

**Setup**
```text
DISPATCHING(1)
```

**Trigger**
- Start the real foreground dispatch path with a deterministic barrier/latch in the fake Gateway so the external mutation remains blocked/in-flight.
- While that barrier is held, run the runtime verification sweep.
- Release the barrier only after the sweep has completed.

The probe must demonstrate an actual in-flight overlap, not merely a pre-created `DISPATCHING` row.

**Expected**
```text
DISPATCHING row is untouched
no FAILED
no COMPLETED
no verification decision from the runtime sweep
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C07 — Fresh PENDING(0) Must Not Enter Runtime Recovery

**Setup**
```text
PENDING(0)
```

**Trigger**
- Run runtime sweep.

**Expected**
```text
fresh operation remains PENDING(0)
no verification claim
no gateway mutation
```

The runtime sweep is a verification mechanism, not a dispatch authorizer.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C08 — Recovery-Blocked PENDING(1) May Resolve but Can Never Reclaim First Dispatch

**Setup**
```text
PENDING(1)
```

**Trigger**
- Run verification/resolution.
- Attempt `claimDispatchAuthorization()` afterward.

**Expected**
```text
verification allowed
claimDispatchAuthorization = false
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C09 — Definitive Success Must Bypass RESOLVING

**Setup**
- Gateway returns definitive successful result.

**Expected**
```text
DISPATCHING(1)
→ COMPLETED(1)
```

without an unnecessary persistent `RESOLVING` write.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C10 — Definitive Business Failure Must Not Materialize Ledger

**Setup**
- Gateway emits typed `EarthlinkBusinessException`.

**Expected**
```text
FAILED
no ledger mutation
no success UI
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C11 — Transport Uncertainty Must Preserve Unknown Outcome

**Setup**
- Gateway throws `EarthlinkTransportException`.

**Expected**
```text
PENDING(1)
no ledger mutation
no FAILED classification
no blind retry
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C12 — Cancellation After Claim

**Setup**
```text
PENDING(0)
→ DISPATCHING(1)
```

**Trigger**
- Cancellation occurs after claim.

**Expected immediately**
```text
CancellationException rethrown
claimCount remains 1
no FAILED
no blind redispatch
```

The durable state may remain `DISPATCHING(1)` immediately after cancellation.

**Expected at recovery boundary**
```text
DISPATCHING(1) → PENDING(1)
no redispatch
verification-only recovery
```

Do not require `DISPATCHING → PENDING` to occur synchronously in the cancellation handler.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C13 — Activation ACTIVE Without Matching Statement

**Setup**
- Recovered activation.
- ISP state indicates username exists/active.
- No unique accountStatement match.

**Expected**
```text
INCONCLUSIVE
PENDING(1)
zero ledger mutation
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C14 — Activation Wrong UserID

**Setup**
- Statement has matching amount/time/operation but wrong `userID`.

**Expected**
```text
INCONCLUSIVE
```

No note/text fallback is allowed.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C15 — Activation Ambiguous Statement

**Setup**
- Two candidate statement rows satisfy the broad window.

**Expected**
```text
INCONCLUSIVE
```

No automatic `COMPLETED`.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C16 — ±90 Second Boundary

Execute at least:

```text
-90s
+90s
-90s - offset
+90s + offset
```

Where `offset` is deterministic and greater than the timestamp parser resolution; use a documented 1 ms or 1 s offset consistent with the repository timestamp format.

**Expected**
```text
boundary included
outside excluded
```

Use the immutable `createdAt` correlation anchor.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C17 — Anti-Repeat Is Not Execution Proof

**Setup**
- Simulate repeated renewal rejection/acceptance behavior.

**Expected**
- Rate-limit behavior may be recorded as a supporting signal.
- It must never independently produce `VERIFIED_SUCCESS`.

**Category:** `DEFENSE-IN-DEPTH`

Failure is not blocking unless it demonstrates a violation of the frozen outcome/evidence contract.

---

### ADV-C18 — Financial Amount Invalid at Pre-Dispatch Boundary

Test at minimum:

```text
0
negative
fractional
non-250 denomination
NaN
+Infinity
-Infinity
```

Test `null` only at a nullable price/cost input boundary (for example `refillUser(price: Double?)`). Do not construct impossible `Double` nulls in a non-nullable API merely to satisfy the matrix. Test IEEE invalid values only where the source type is `Double`.

**Expected**
```text
NOT-DISPATCHED
no pending intent
no claim
no external dispatch
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C19 — Financial Amount Invalid at Canonical Materialization Boundary

Construct persisted financial operations with invalid `amountIqd`.

**Expected**
```text
materializer throws/fails closed
never COMPLETED
never creates zero-value financial ledger entry
```

Also verify explicit non-financial `TEST_USER` / `EXTEND` records remain valid at zero.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C20 — Same-ID Divergent Ledger Payload

**Setup**
- Existing ledger entry under the same business transaction ID.
- Second materialization attempt with different account/type/amount.

**Expected**
```text
DivergentPayloadConflictException
existing ledger unchanged
no duplicate ledger entry
no COMPLETED rewrite with divergent data
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C21 — Terminal Completion Retry

**Setup**
- Operation already `COMPLETED` with ledger entry.

**Trigger**
- Call canonical verified-success materializer again.

**Expected**
```text
same existing ledger entry returned
ledger count remains 1
same account
same amount
status remains COMPLETED
```

**Category:** `FROZEN-INVARIANT`

---

### ADV-C22 — Failed State Must Remain Financially Terminal

Test both:

```text
FAILED(0)
FAILED(1)
```

**Expected**
- no financial materialization;
- no first-dispatch authorization;
- no transition back to fresh `PENDING(0)`;
- no blind redispatch.

Do not interpret this probe as requiring a stronger "immutable in every API sense" terminal-state rule than the frozen contract defines.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C23 — Migration 16→17 Safety

Start from a real compatible V16 fixture/database produced with the V16 schema and populated with unresolved historical rows. Do not fake the schema version on a current V17 database.

Use the actual Room `MIGRATION_16_17` path to open/migrate the fixture.

**Expected**
```text
migration succeeds
business data preserved
PENDING/DISPATCHING/RESOLVING unresolved rows become claimCount=1
no historical unresolved row becomes PENDING(0)
```

Do not require generic "repeat-safe migration" semantics unless separately authorized.
Do not satisfy the migration probe by manually editing the current database schema/version metadata.

**Category:** `REQUIRED-REGRESSION`

---

### ADV-C24 — Cold-Start Snapshot Boundary

Create the two timestamp populations through supported test setup/DAO fixtures. Direct SQL/database mutation is prohibited unless the probe is explicitly a migration/DAO-level probe and the mutation is part of that fixture construction.

Required states:
```text
old DISPATCHING.updatedAt < processStartMs
current-process DISPATCHING.updatedAt >= processStartMs
```

**Expected**
```text
old = eligible for orphan recovery
current = excluded
```

This proves startup race protection.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C25 — Non-Financial Recovery Is Fail-Closed

For `TEST_USER` and `EXTEND`:

```text
current state positive
```

must remain:

```text
INCONCLUSIVE
```

unless there is operation-specific historical proof.

Negative evidence may resolve `VERIFIED_FAILURE` where explicitly approved.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C26 — Backup / Restore Regression

Execute the existing TEST-12 smoke/regression coverage:

```text
backup no-password
backup password-protected
restore
SYNC during BACKUP/RESTORE
maintenance exclusion
```

This is a **required regression gate**, not a new Step 3 invariant.

**Category:** `REQUIRED-REGRESSION`

---

### ADV-C27 — Completion Writer Bypass Audit

Search all production code for:

```text
completePendingOperation(
pendingDao.updateStatus(..., "COMPLETED"
```

and classify every production caller.

Expected:
```text
no external financial production path bypasses
resolvePendingOperationVerifiedSuccess
```

Do not remove legitimate manual/offline financial helpers merely to make a grep count equal one.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C28 — Gateway Typed Outcome Boundary

For `createTestUser` and `createUserUsingDeposit`, prove:

```text
explicit API rejection → EarthlinkBusinessException
transport uncertainty → EarthlinkTransportException
401 → EarthlinkAuthException
CancellationException → rethrow
```

Verify typed exception propagation and the existing Step 2 consumer classification. Do not invent new authentication outcome semantics.
No string matching on `Exception.message`.

**Category:** `REQUIRED-REGRESSION`

---


Verify each production Gateway mutation path independently; do not infer coverage from the exception hierarchy alone.

### ADV-C29 — Manual Verification Integrity

Where the frozen implementation exposes manual verification:

```text
valid evidence
→ canonical materialization exactly once

invalid evidence
→ no financial materialization

duplicate/repeated evidence
→ no duplicate ledger
```

Keep this bounded to the existing manual-verification path; do not invent a new queue/UI. Only assertions explicitly supported by the existing manual-verification contract are blocking; additional hardening observations are evidence-only.

**Category:** `REQUIRED-REGRESSION`

---

### ADV-C30 — Claim Must Not Depend on DataOperationCoordinator

Attempt claim/replay/concurrency scenarios while independently exercising the coordinator.

Expected:
```text
SQLite conditional UPDATE is the correctness boundary
claim outcome does not depend on coordinator hash/lock ownership
no new synchronization layer is required
```

**Category:** `DEFENSE-IN-DEPTH`

A failure of this probe is not a certification blocker unless it demonstrates a concrete violation of the frozen SQLite claim-authority invariant or introduces an unapproved synchronization dependency affecting correctness.

---

### ADV-C31 — Same-Process UI Double-Tap Coalescing

Two identical UI actions must be coalesced before a second intent is generated.

Expected:
```text
one intent
one pending row
one claim
one external dispatch
```

A different logical operation or intentionally new user intent must remain possible.

**Category:** `REQUIRED-REGRESSION`

---

### ADV-C32 — Real Restart With Same SQLite File, No Same-Process Substitution

The test must prove:

```text
old Room instance closed
→ same physical DB file reopened
→ new AppDatabase instance
→ new repository
→ persisted operation still present
```

Do not satisfy this probe by simply invoking a recovery method on the old repository.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C33 — Four Production Dispatch Gate Audit

Perform a repository-wide structural audit of these four production mutation call-sites:
```text
createTestUser
createUserUsingDeposit
refillUserDeposit
extendUser
```

For each call-site prove the dominance chain:
```text
recordPendingOperation
    ↓
claimDispatchAuthorization()
    ↓ successful claim only
external Gateway mutation
```

Acceptance:
- no production mutation call occurs on the loser of a claim race;
- no legacy direct Gateway mutation path bypasses the claim;
- the durable claim is the only local first-dispatch authorization boundary.

This is a structural/call-graph proof, not merely a test that one known path behaves correctly.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C34 — Durable Claim/State Writer Audit

Perform a repository-wide production-writer audit for:
```text
dispatchClaimCount
"DISPATCHING"
"RESOLVING"
"PENDING"
"COMPLETED"
"FAILED"
```

For every writer, record:
```text
writer location
condition/transition
canonical or auxiliary purpose
whether it can bypass the claim
whether it can reset dispatchClaimCount to 0
```

Expected authority boundaries:
```text
dispatchClaimCount 0 → 1 : claim/migration only
dispatchClaimCount 1 → 0 : forbidden
DISPATCHING            : claimDispatch only
RESOLVING              : transitionToResolving only
PENDING(1)             : recovery/inconclusive paths only
```

For `COMPLETED`/`FAILED`, distinguish external-operation canonical writers from legitimate manual/offline financial helper paths; do not collapse semantically separate authorities merely to reduce grep counts.

**Category:** `FROZEN-INVARIANT`

---

### ADV-C35 — Activation SUSPENDED Recovery

**Setup**
- Claimed `ACTIVATION` operation reaches recovery.
- Authoritative ISP recovery state is `SUSPENDED` (or the explicitly approved equivalent non-execution signal for activation recovery).

**Trigger**
- Run activation recovery through the frozen `verifyAndResolvePendingOperation` path.

**Expected**
```text
VERIFIED_FAILURE
→ FAILED(0 or 1)
→ zero financial ledger mutation
→ no first-dispatch authorization
→ no redispatch
```

The probe must test activation-recovery semantics directly; do not substitute a generic HTTP 4xx/business-rejection test.

**Category:** `REQUIRED-REGRESSION`

## 19.6 Composition Attacks

After mandatory single probes, run only the highest-risk composed sequences.

### COMP-01 — Claim Race + Crash

```text
Caller A/B
→ one claim
→ external call
→ durability boundary / restart evidence
→ new Room instance
→ no redispatch
→ verification only
```

Expected:
```text
external invocation count <= 1
claimCount = 1
no duplicate ledger
```

### COMP-02 — Runtime Sweep + Active DISPATCHING + Startup Race

```text
foreground dispatch active
+
runtime sweep
+
application startup/recovery task
```

Expected:
- active current-process dispatch is not falsely failed;
- current-process operation is not classified as orphan;
- no duplicate verification or dispatch;
- after restart, only the true previous-process orphan is recovered.

### COMP-03 — Definitive Success + Materialization Failure + Restart

```text
external success
→ local materialization failure
→ DISPATCHING(1)
→ DB close
→ new Room instance
→ PENDING(1)
→ statement verification
→ COMPLETED + one ledger
```

This is the canonical recovery hard-case.

### COMP-04 — Ambiguous Statement + Repeated Recovery

```text
DISPATCHING(1)
→ PENDING(1)
→ ambiguous statement
→ INCONCLUSIVE
→ repeated verification
```

Expected:
```text
no ledger
no redispatch
status remains recovery-blocked
```

### COMP-05 — Transport Uncertainty + Repeated User Action

```text
dispatch
→ transport uncertainty
→ PENDING(1)
→ same UI action attempted again
```

Expected:
```text
no automatic same-intent redispatch
```

A new explicit user intent must remain a separate decision.

### COMP-06 — Same-ID Retry + Existing Ledger + Divergent Payload

```text
existing COMPLETED ledger
+
same businessTransactionId
+
different amount/account
```

Expected:
```text
conflict
no overwrite
no second ledger
```

---

## 19.7 Test-Data and Fault-Injection Rules

Adversarial tests must use deterministic fixtures.

### Allowed fault injection

- fake Gateway result/exception
- controlled coroutine cancellation
- controlled Room close/reopen
- deterministic statement fixtures
- deterministic timestamp offsets
- deterministic existing-ledger fixtures
- deterministic process-start timestamps

### Prohibited shortcuts

Do not:

- mutate the database row manually to create the expected result;
- bypass production repository logic unless the probe is explicitly a DAO-level test;
- replace the production resolver with a test implementation;
- inject a success result after claiming without recording the actual durable state being tested;
- use `Thread.sleep()` as the only synchronization proof where deterministic latches/barriers are available.

---

## 19.8 Evidence Capture Contract

For every failing or critical passing probe, capture:

```text
Repository:
Commit SHA:
Test:
Authority / invariant:
Setup:
Trigger:
DB before:
External invocation count before:
Observed exception/outcome:
DB after:
dispatchClaimCount:
Ledger before:
Ledger after:
External invocation count after:
UI success state (when applicable):
Evidence classification:
```

For restart tests also capture:

```text
Old DB instance identity / lifecycle event
New DB instance creation
Same SQLite file path/name
New repository creation
Persisted row observed before recovery
Intermediate PENDING(1), when contractually observable
Final resolution
```

---

## 19.9 Failure Classification Protocol

When an adversarial probe fails:

### Step 1 — Stop implementation changes

Do not patch production immediately.

### Step 2 — Reproduce

Run the probe twice to rule out nondeterministic test behavior.

### Step 3 — Compare against authority

Determine whether the expected result is actually frozen.

### Step 4 — Classify

Use exactly one:

```text
PRODUCTION DEFECT
TEST DEFECT
UNSUPPORTED SCENARIO
EVIDENCE GAP
```

### Step 5 — Report

```text
Invariant
Failure
Reproduction
Evidence
Root Cause
Impact
Minimal Corrective Action
Regression Test
```

### Step 6 — Patch only after classification

If it is a production defect, make the smallest possible correction and rerun the relevant regression set.

Never "fix" a failed adversarial probe by weakening its assertion merely because the implementation is inconvenient.

---

## 19.10 Production-Change Freeze and Patch Rule

Default rule:

```text
Certification run = ZERO production changes
```

If a real production defect is proven:

```text
STOP certification
→ document defect
→ make minimal production patch outside the certification run
→ increment baseline SHA
→ rerun affected Step 3 regressions
→ rerun the relevant ADV probes
→ resume certification
```

Do not mix a production patch and certification result in the same evidence record.

---

## 19.11 Required Existing Regression Gate

Before declaring GO, rerun at minimum:

```text
Step3DurableDispatchTest
Step2OutcomeResolutionTest
Phase1UnknownOutcomeResolutionTest
Phase1DuplicateInitiationProtectionTest
Phase3SameLineageFinancialMutationTest
Workstream13G1RealRestartCertificationTest
Backup/Restore smoke/regression (TEST-12)
:app:testDebugUnitTest
```

Record exact command, SHA, pass/fail count, and environment.

If the environment has known Mockito/ByteBuddy/JDK constraints, record them separately and do not relabel them as application failures without evidence.

---

## 19.12 Certification Final Report Format

The adversarial agent must end with a single report:

```text
EARTHLINK RESELLER V1 — STEP 3 ADVERSARIAL CERTIFICATION REPORT

Baseline SHA:
Environment:

Baseline regression:
  PASS / FAIL

ADV probes:
  ADV-C01  PASS
  ADV-C02  PASS
  ADV-C03  PASS
  ADV-C04  PASS
  ADV-C05  PASS
  ADV-C06  PASS
  ADV-C07  PASS
  ADV-C08  PASS
  ADV-C09  PASS
  ADV-C10  PASS
  ADV-C11  PASS
  ADV-C12  PASS
  ADV-C13  PASS
  ADV-C14  PASS
  ADV-C15  PASS
  ADV-C16  PASS
  ADV-C17  PASS
  ADV-C18  PASS
  ADV-C19  PASS
  ADV-C20  PASS
  ADV-C21  PASS
  ADV-C22  PASS
  ADV-C23  PASS
  ADV-C24  PASS
  ADV-C25  PASS
  ADV-C26  PASS
  ADV-C27  PASS
  ADV-C28  PASS
  ADV-C29  PASS
  ADV-C30  PASS
  ADV-C31  PASS
  ADV-C32  PASS
  ADV-C33  PASS
  ADV-C34  PASS
  ADV-C35  PASS

Composition attacks:
  COMP-01  PASS
  ...
  COMP-06  PASS

Production defects:
  None / listed

Evidence gaps:
  None / listed

Unsupported scenarios:
  None / listed

Required patches:
  None / listed

Final decision:
  GO
  or
  NO-GO
```

A `GO` requires:

```text
no unresolved P0 invariant violation
+
no unresolved frozen-contract P1 violation
+
all mandatory REQUIRED-REGRESSION gates pass
+
all mandatory FROZEN-INVARIANT probes pass
+
all mandatory evidence-only gates closed
+
no unresolved production-code bypass of the frozen contract
+
evidence artifacts tied to the certified SHA
```

A `DEFENSE-IN-DEPTH` failure alone does not automatically create a product blocker; it must be explicitly classified.

---

## 19.13 Scope-Control Rules

The adversarial agent must not use certification to introduce:

- a new durable state;
- a new generic reconciliation engine;
- a new sync state machine;
- a staging database;
- a new identity registry;
- a new governance registry;
- a Demo/Real production abstraction;
- a new manual queue/UI unless it is already in frozen scope;
- speculative device-attribution mechanisms;
- a new financial proof rule not contained in the frozen authority.

Certification attacks must remain within the frozen V1 contract.

---

## 19.14 Final Certification Gate

The agent may issue:

```text
STEP 3 ADVERSARIAL CERTIFICATION = GO
```

only after:

1. mandatory `FROZEN-INVARIANT` probes pass;
2. required regression probes pass;
3. no duplicate external dispatch is demonstrated;
4. no false financial success is demonstrated;
5. no invalid financial materialization is demonstrated;
6. no unsafe recovery path is demonstrated;
7. real Room restart evidence is present;
8. G1-F evidence remains strict 4-tuple;
9. Activation `SUSPENDED` recovery resolves to `FAILED(0 or 1)` with zero ledger and no redispatch;
10. fresh `PENDING(0)` remains excluded from runtime recovery;
11. `PENDING(1)` can never regain first-dispatch authorization;
12. certified evidence is tied to the exact HEAD.

---

# 20. REVIEW LESSONS LOCKED INTO CERTIFICATION

The following are now explicit certification lessons because they previously caused review churn:

1. **Prose is not evidence.** A report must be backed by source, test, or artifact.
2. **A restart test must cross a Room-instance boundary.** Calling a recovery method in the same instance is not equivalent.
3. **A matching statement must include exact `userID`, operation semantics, amount, and time.** Note/text alone is never identity proof.
4. **Test expectation changes require authority justification.** Never repair a production defect by silently changing the expected result.
5. **SQLite claim atomicity is the dispatch correctness boundary.** Do not substitute an application mutex.
6. **Active `DISPATCHING` is not a runtime-sweep target.**
7. **`INCONCLUSIVE` is a resolution result, not a new durable status.**
8. **Current-state existence is not historical execution proof.**
9. **Financial amount validation must fail closed before dispatch and again at canonical materialization.**
10. **Canonical external financial completion must not be bypassed by legacy completion helpers.**
11. **Backup/restore changes require regression even when the changed file appears unrelated, because `DataOperationCoordinator` is shared.**
12. **The certification agent must distinguish safety failures from evidence gaps before proposing code changes.**

---

# 21. CERTIFICATION COMPLETION HANDOFF

When `GO` is reached:

```text
Step 3 implementation = CLOSED
Step 3 adversarial certification = PASSED
Current certified main = exact recorded SHA
```

Then:

- freeze the certified Step 3 baseline;
- attach the certification report and test evidence;
- update the live G1 closure backlog with exact evidence references;
- keep deferred items separate from certified invariants;
- do not reopen Step 3 without a concrete contradiction or newly discovered production defect.

When `NO-GO` is reached:

- keep the current baseline intact;
- record the failing invariant and evidence;
- create the smallest authorized corrective patch;
- re-run only the affected Step 3 closure and adversarial gates;
- do not restart Phase 0.


# END OF STEP 3 ADVERSARIAL CERTIFICATION BASIS
