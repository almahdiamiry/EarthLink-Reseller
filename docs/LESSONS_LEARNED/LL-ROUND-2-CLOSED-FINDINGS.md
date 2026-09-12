# Lesson Learned: Round-2 Forensic Analysis of Non-Actionable Findings

**Identifier:** `LL-ROUND-2-CLOSED-FINDINGS`  
**Status:** Historical forensic knowledge; non-authoritative practical engineering note.

---

## 1. Authority Boundary & Purpose

This note captures practical forensic engineering knowledge from the Round-2 investigation cycle. 

> **Important Boundary:** This document is historical forensic knowledge and prior investigation evidence. It is **NOT** a replacement for current code, `AGENTS.md`, the Target Product Contract, architectural authority, or active verification. Current code and authoritative project documents remain primary. If future code changes materially contradict this note, the note does not override the code; the finding must be re-verified.

Its primary purpose is to prevent future agents from repeatedly misclassifying already-investigated mechanisms as defects because they inspect only an isolated local code fragment without tracing the full production path.

---

## 2. The Core Forensic Principle

> **A finding is not a production defect until the full production path is traced from source state to observable consequence.**
>
> Do not infer database corruption or financial data loss from an in-memory map, a theoretical string collision, a dead helper, or a `LIMIT 1` query without proving a legitimate production input and a demonstrably harmful outcome.

A rigorous audit distinguishes **code smells, theoretical possibilities, invalid states, dead/obsolete code, and presentation-only behaviors** from **confirmed production defects**.

In Round-2, six findings were investigated:
* **R3** was confirmed as a real production defect (recycled username collision across the historical boundary) and was surgically fixed and verified in [`ec57835`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1).
* **R2, R5, N2, R4, and N3** were thoroughly investigated against full production call graphs, database schemas, and archive data, and were conclusively proven to be **non-actionable, false, or dead**.

---

## 3. Confirmed Defect Context: R3

### R3 — Historical Account Matching and Username Recycling
* **Final Status:** `CONFIRMED PRODUCTION DEFECT — FIXED IN ec57835`
* **Durable Architectural Mandate:**
  1. Historical accounts can never be hijacked by a new subscriber after ISP username recycling.
  2. Historical financial state (debt, opening debt, ledger entries, provenance ID) is strictly immutable to incoming different entities.
  3. Exact provenance equality (same `sourceExternalId`) remains supported for legitimate replay without reactivating the account.
  4. Phone and name fallback matching stages can never cross the historical boundary.
* **Rule for Future Agents:** Historical username recycling requires provenance-aware matching. A new subscriber sharing a recycled username with an old account must be created as a distinct new account unless the incoming `sourceExternalId` exactly matches the historical account.

---

## 4. Closed Round-2 Findings Registry

| Finding | Target Component | Final Status | Key Production Mechanism | Future-Agent Prevention Rule |
|:---|:---|:---|:---|:---|
| **R2** | `TransactionDeduplicator.buildMatchKey` | `CLOSED — THEORETICAL / NON-ACTIONABLE` | Fallback key only; `sourceExternalId` is evaluated first. Real uTower archives have 100% non-empty keys (2,690/2,690). | Do not report delimiter collisions without proving two legitimate transactions reach fallback and alter persistence. |
| **R5** | `DataOperationCoordinator` (`Job.hashCode`) | `CLOSED — FALSE / NON-ISSUE` | Independent operations are serialized by `Mutex.withLock`. Hash code is only used for direct re-entrancy within same job context. | Do not claim hash collision risk without proving independent jobs can enter hash check and bypass `Mutex`. |
| **N2** | Active account lookup (`LIMIT 1`) | `CLOSED — FALSE / INVALID-STATE CONCERN` | Query restricts to `isHistoryOnlySubscriber = 0`. Active usernames are unique in production; historical reuse is intentional. | Do not propose table-wide `UNIQUE(earthlinkUsername)`; table allows historical recycling (`isHistoryOnlySubscriber = 1`). |
| **R4** | `BalanceCalculator.revertTransaction` | `CLOSED — DEAD / OBSOLETE / NON-ACTIONABLE` | Zero production callers. Production corrections use strictly additive contra-entries (`correctsEntryId`). | Do not "repair" dead legacy arithmetic; cleanup is out of scope unless an active production caller exists. |
| **N3** | `LocalAccountMatcher.putIfAbsent` | `CLOSED — FALSE / NON-ISSUE` | Ephemeral UI cache only; explicitly skips `isHistoryOnlySubscriber == true` at line 545. Zero financial mutations. | Do not label map-entry dropping as data loss without proving legitimate duplicate input and critical downstream harm. |

---

## 5. Detailed Mechanism Analysis for Closed Findings

### R2 — `TransactionDeduplicator.buildMatchKey`
* **Final Status:** `CLOSED — THEORETICAL / NON-ACTIONABLE`
* **Correct Mechanism:** `buildMatchKey(...)` is strictly an advisory secondary fallback. The production deduplication path checks `sourceExternalId` first. In real uTower imports, transaction external IDs are populated from source keys with deterministic fallback generation (inspection of real archive data confirmed 2,690 of 2,690 transactions possess non-empty source keys). While delimiter collision can be mathematically fabricated with synthetic strings, no legitimate production transactions reach fallback deduplication or experience transaction loss/merging.
* **Rule for Future Agents:** Do **NOT** classify delimiter construction as a production financial defect without proving all three: (1) two legitimate production transactions can collide, (2) they can actually reach the fallback key, and (3) the collision alters persisted financial history. Do not alter delimiters solely for cosmetic hygiene.

### R5 — `DataOperationCoordinator / Job.hashCode()`
* **Final Status:** `CLOSED — FALSE / NON-ISSUE`
* **Correct Mechanism:** `DataOperationCoordinator` serializes independent concurrent operations using a standard coroutine `Mutex`. `CoordinatorOwnershipToken` and `Job.hashCode()` are used strictly for same-job re-entrancy detection within the same coroutine context. Independent concurrent jobs never share tokens and must acquire the mutex. Child coroutines (with new Job instances) cannot bypass the mutex. Deterministic concurrency tests prove independent operations remain strictly serialized.
* **Rule for Future Agents:** Do **NOT** classify `Job.hashCode()` as a concurrency bug merely because hash collisions are theoretically possible in 32-bit integers. Verify first whether the job is used as a global identifier (it is not) and whether a collision can bypass the mutex (it cannot).

### N2 — `LocalAccount` Active Lookup with `LIMIT 1`
* **Final Status:** `CLOSED — FALSE / INVALID-STATE CONCERN`
* **Correct Mechanism:** Active subscriber lookups query `WHERE isHistoryOnlySubscriber = 0`. EarthLink ISP gateway rules enforce that an active subscriber has a unique username. The reseller app intentionally allows historical accounts (`isHistoryOnlySubscriber = 1`) to retain a past username when an ISP recycles it to a new active subscriber. Therefore, a global SQLite `UNIQUE(earthlinkUsername)` constraint would break valid historical preservation. Because legitimate production paths create at most one active row per username, `LIMIT 1` never arbitrarily selects between two valid active records.
* **Rule for Future Agents:** Do **NOT** propose table-level `UNIQUE(earthlinkUsername)`. Do **NOT** classify `LIMIT 1` as a defect without demonstrating that two valid active rows can be created by legitimate production flows. Distinguish legitimate historical username recycling from invalid duplicate-active states.

### R4 — `BalanceCalculator.revertTransaction`
* **Final Status:** `CLOSED — DEAD / OBSOLETE / NON-ACTIONABLE`
* **Correct Mechanism:** `revertTransaction(...)` has zero production callers anywhere in the codebase. Production transaction corrections use the additive contra-entry model (`correctsEntryId` in `Repositories.kt:2380`), evaluating rolling balances forward from baselines via `reconstructCurrentPosition(...)`. While `revertTransaction` contains legacy rollback arithmetic inconsistent with the additive model, its complete reachability isolation prevents any runtime financial impact.
* **Rule for Future Agents:** Do **NOT** delete or refactor `revertTransaction` merely because its internal logic looks outdated. Dead-code pruning is accepted legacy debt (`AGENTS.md §5`) and must not be touched during routine defect verification.

### N3 — `LocalAccountMatcher.putIfAbsent`
* **Final Status:** `CLOSED — FALSE / NON-ISSUE`
* **Correct Mechanism:** `LocalAccountMatcher` is an ephemeral presentation helper used exclusively in Jetpack Compose UI (for subscriber card badges and status counts). Its `init` loop unconditionally executes `if (acc.isHistoryOnlySubscriber) continue` at line 545, guaranteeing that historical accounts never compete with or drop active accounts. It performs no financial calculations, no database updates, and no API dispatches.
* **Rule for Future Agents:** Do **NOT** confuse in-memory lookup omission with persistent database deletion. Dropping an entry from a temporary UI map does not alter Room database records or ledger rows.

---

## 6. Reopening Guidance

These findings are closed based on concrete production path tracing and empirical evidence. Future agents may reopen an investigation **only** if new authoritative specifications or demonstrable repository changes introduce direct counter-evidence (such as a new production caller to `revertTransaction` or a legitimate flow producing duplicate active usernames).
