# Lesson Learned: Round-3 Forensic Analysis — Two Rejected Candidates

**Identifier:** `LL-ROUND-3-REJECTED-FINDINGS`
**Status:** Historical forensic knowledge; non-authoritative practical engineering note.
**Baseline commit:** `c648a1e`
**Outcome:** Two candidates investigated. **Zero confirmed bugs.** Both rejected at the gates.

---

## 1. Authority Boundary & Purpose

> **Important Boundary:** This document is historical forensic knowledge and prior investigation evidence. It is **NOT** a replacement for current code, `AGENTS.md`, the Target Product Contract, architectural authority, or active verification. Current code and authoritative project documents remain primary. If future code changes materially contradict this note, the note does not override the code; the finding must be re-verified.

Its purpose is to stop future agents from re-investigating these two specific patterns and re-deriving the same wrong conclusion, which is what happened at least once in this round.

---

## 2. Round Summary

| Candidate | Target | Verdict | Classification |
|:---|:---|:---|:---|
| **A** | Uncapped `LIMIT 100000` ledger read (`AppDatabase.kt:156-163`) | **REJECTED** | `UNPROVEN` (constructibility) |
| **B** | `sourceKey` empty-string collision in uTower JSON subscriber branch (`UtowerImporter.kt:457-459`) | **REJECTED** | `FALSE ALARM` (refuted by executing the real import) |

**No production code was modified. Working tree left clean at `c648a1e`.**

Candidate B was investigated twice: first rejected on an incorrect "dead code" argument, then re-investigated and **refuted empirically** with a throwaway Robolectric diagnostic that exercised the real `importFromFile` path. The diagnostic passed and was deleted.

---

## 3. Rejected — Candidate A: Uncapped Ledger Read

**Target:** `app/src/main/java/com/example/core/database/AppDatabase.kt:156-163`
**Failed Gate:** 4 — Constructibility
**Classification:** `UNPROVEN`

**Mechanism (accurate, but not a defect):** `getByAccountId(accountId, limit: Int = 100000)` and `getByAccountIds(...)` apply a hard `LIMIT` with no pagination continuation. Reached in production via `LocalLedgerRepositoryImpl.getLedgerForAccount` / `getLedgerForAccounts` (`Repositories.kt:1322-1330`).

**Why rejected:** This is genuinely the same class as **BUG-39** (fixed in `3e8bd72`), but BUG-39's fix paginated the **backup-clone seam** only (`BackupManager.kt`, using `getAllPersistedOneShot(limit, offset)`). This read seam was not part of that fix and remains uncapped.

However, real-data scale makes the trigger unreachable: the forensic backup holds **2,761 ledger rows across 216 accounts** (~70 max per account). Reaching `100,000` rows for a single account — or across the multi-account `getByAccountIds` set — is not constructible under any observed or documented workload. No authoritative rule in the Target Product Contract or the identity inventory mandates unbounded reads here.

**Rule for future agents:** Do not report this as a defect on symmetry with BUG-39 alone. BUG-39 was proven against a *real* dataset that exceeded its limit. If a future dataset can produce >100k ledger rows for one account (or >100k across a queried account set), re-open with that data. Do not "fix" it speculatively — accepted V1 debt covers large working files, and an unnecessary rewrite of a hot read path carries its own risk.

---

## 4. Rejected — Candidate B: `sourceKey` Empty-String Collision

**Target:** `app/src/main/java/com/example/core/sync/UtowerImporter.kt:457-459` (and the twin at `:474-476`)
**Failed Gate:** 3 — Negative Twin (refuted empirically) + Gate 2/4
**Classification:** `FALSE ALARM` (proven by execution, not by argument)

> **Correction (second pass).** An earlier draft of this note rejected Candidate B as `DEAD CODE (real data)`. **That reasoning was wrong and has been withdrawn.** The JSON branch is reachable in production — see §4.2. The correct rejection is empirical and is recorded below.

### 4.1 The observed pattern (real, but harmless)

```kotlin
// Subscribers (:457-459) — no empty guard
val sourceKey = sub.optString("source_key").takeIf { it.isNotEmpty() }
    ?: sub.optString("source_path").split("/").lastOrNull()
    ?: UUID.randomUUID().toString()

// Transactions (:544-545) — HAS empty guard
val sourceKey = tx.optString("source_key").takeIf { it.isNotEmpty() }
    ?: tx.optString("source_path").split("/").lastOrNull().takeIf { !it.isNullOrEmpty() }
```

`optString` on a missing key returns `""`, and `"".split("/").lastOrNull()` returns `""` (**not** `null`) — verified. So the elvis chain short-circuits, `UUID.randomUUID()` at `:459` is unreachable, and subscribers lacking both fields do receive `sourceKey = ""`. This part of the observation is **correct**.

### 4.2 Reachability — the branch IS live (withdrawn claim)

Real uTower archives normally ship a Firebase SQLite server cache, which wins the weight comparison in `extractDatabaseFromTgz` (`:869-880`): `.firebaseio`/`servercache` = `1_000_000_000_000_000L` vs `.json` = `1_000_000_000_000L`.

**But that only decides the winner when both files are present.** `.json` is given an explicit high weight, so JSON-only archives are a deliberately supported import format. Reachability is proven by the existing permanent test `LocalAccountsViewModelTgzSyncTriggerTest`, which builds a tgz containing `utower_backup.json` (no Firebase cache) and asserts a successful import.

### 4.3 Why it is still a false alarm — empirical refutation

A throwaway Robolectric diagnostic (since deleted) imported a **JSON-only** tgz through the real production `importFromFile` with **two subscribers, neither having `source_key` nor `source_path`**, each with a distinct name/username/phone and one 25,000 payment.

**Actual result — import succeeded, no collapse:**

```text
success=true  subsRead=2  subsInserted=2  subsMerged=0
accounts=2
  ACC user=alpha_user name=Alpha Subscriber srcExtId=        (empty, as predicted)
  ACC user=beta_user  name=Beta Subscriber  srcExtId=        (empty, as predicted)
ledgers=2, on 2 DISTINCT accountIds
```

Two distinct subscribers produced two distinct accounts and two ledgers on separate accounts. The empty `sourceKey` caused **no** financial or identity misattribution. The diagnostic passed and was removed.

**Why the predicted collision does not occur:**

- `subscriberMap[""]` is written at `:1360`, but it is never *read* with that key: `subscriberRef` (`:1447-1457`) is null-or-non-empty by construction, so `subscriberMap[subscriberRef]` (`:1485`) can never look up `""`.
- `SubscriberMatcher.matchSubscriber` (`:64`) sanitizes `""` to `null`, so the empty extId is skipped in Stage 2 and matching correctly falls through to username/phone/name — which is precisely why the two subscribers stayed distinct.
- Identity resolution therefore uses the *real* discriminators, not the corrupted key. The empty `sourceExternalId` persists (confirmed above) but the index on it is non-unique (`Models.kt:382`) and is only consulted through the sanitized matcher.

**Rule for future agents:** Do not report this as cross-entity identity contamination. An empty `sourceExternalId` on imported accounts is **expected and benign** for source JSON lacking both fields. If a future change makes the empty key load-bearing (e.g. removing the `takeIf` at `:64`, or resolving accounts by raw `sourceExternalId` without sanitization), this must be re-investigated.


---

## 5. Process Defects — Recorded for Future Rounds

Three separate reasoning errors occurred in this round. All three were caught by re-verification rather than by initial review, which is why they are recorded here.

### 5.1 Candidate B was reported as "strong" before Gate 1 was run

The initial pass characterized Candidate B as a strong lead based on correct Kotlin semantics (`"".split("/").lastOrNull() == ""`) and a genuine code asymmetry. But the import branch that real data selects was never established first, so the conclusion rested on an unverified premise.

**Contributing error:** An early constructibility check was run against `.forensic-bug02/real_data.json`, which is the **app's own DB export** (`accounts` / `ledgers` top-level keys), **not** uTower source JSON. It reported "2,979 subs missing both `source_key` and `source_path`" — a meaningless number derived from the wrong artifact.

### 5.2 The first "dead code" rejection was itself wrong

Having found the real archive ships a Firebase SQLite cache, Candidate B was re-classified as `DEAD CODE (real data)`. That was an **over-correction**: the weight comparison in `extractDatabaseFromTgz` only decides the winner when both a `.firebaseio` file *and* a `.json` file are present. A JSON-only archive is a deliberately supported format (weight `1_000_000_000_000L` at `:875`), and reachability is proven by the existing permanent test `LocalAccountsViewModelTgzSyncTriggerTest`.

**Lesson:** rejecting a candidate requires a *demonstrated* absence of a production path, not a plausible one. "The common case doesn't hit it" is not "dead code."

### 5.3 Argument was not sufficient — execution settled it

Even with the branch confirmed reachable, the collision theory remained unproven. Reading the code showed the downstream guards *plausibly* neutralizing it, which is exactly the kind of reasoning that produces confident false positives. A throwaway Robolectric diagnostic importing a JSON-only tgz with two unkeyed subscribers **disproved the theory directly**: 2 accounts, 2 ledgers, no collapse.

**Mandatory rules going forward:**

> 1. **Establish which production branch real data selects BEFORE characterizing any candidate as strong.** In a multi-format importer, "the code is reachable" and "the code is reachable *with real data*" are different claims, and only the second survives Gate 1.
> 2. **Verify a fixture's provenance before using it as evidence.** Confirm the artifact is what the production path actually consumes, not merely that it parses.
> 3. **When a finding hinges on a single value's downstream fate, execute the production path rather than reasoning about it.** A one-off diagnostic is cheap; a confident false positive is expensive. Scope it as `TEMPORARY` and delete it after the verdict.
> 4. **A guard that neutralizes a defect is not an argument for its absence — it is a hypothesis to test.** Read the guard, then prove the guard actually runs on the path in question.

This extends `LL-ROUND-2-CLOSED-FINDINGS`: *"A finding is not a production defect until the full production path is traced from source state to observable consequence."* The additional requirement here is that the trace must terminate in an **executed** result, not in a plausible reading of the code.

---

## 6. Round Result

**Zero confirmed bugs.** Two candidates investigated; both rejected. Candidate B was investigated twice and ultimately refuted by executing the real import path. No production code, contract, configuration, or permanent test infrastructure was modified. The temporary diagnostic was deleted. Working tree clean at `c648a1e`.

Per the Round-3 calibration: *a subsystem may legitimately produce zero confirmed bugs, and ZERO is a valid result.* Manufacturing findings to justify a round is the primary failure mode this methodology exists to prevent.

**Unaudited seams remaining** (candidates for a future round, not findings):
- `SyncRepositoryImpl` (90 KB) — sync lineage, remote-version authority, outbox convergence.
- `RemoteSyncCoordinator` (36 KB) — generation-counter and stale-write rejection (`g4_local_generation`).
- Uncapped read seams beyond the ledger query in Candidate A (e.g. `BackupManager` restore-side reads).
