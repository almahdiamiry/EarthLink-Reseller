# AGENTS.md — EARTHLINK RESELLER V1 OPERATING GOVERNMENT
## Single Operational Authority for EarthLink Reseller V1

---

## 1. Sole Operational Authority Mandate

> ### ⚠️ SOLE OPERATIONAL AUTHORITY
> **`AGENTS.md` IS THE ONLY OPERATIONAL GOVERNING DOCUMENT FOR EARTHLINK RESELLER V1.**
>
> There is no second operating standard, no separate implementation governance authority, no separate simplification rulebook, and no plan governing another plan.
>
> Every AI agent and human maintainer working in this repository is governed exclusively by the rules, safety invariants, verification standards, and navigation procedures defined in this document.

---

## 2. System Identity & Business Purpose

* **What is EarthLink Reseller V1:** A local-first, offline-capable Android application designed for authorized EarthLink resellers to manage subscriber accounts, dispatch operational requests (Activation, Renewal, Refill) via EarthLink Gateway APIs, and record resulting financial debts and payments locally.
* **Core Business Mission:** Protect subscriber financial history. The application must prevent and minimize financial data loss, corruption, duplicate charges, and incorrect balance materialization under all network and crash conditions according to the product contract.

---

## 3. Canonical Navigation Router (Where Truth Lives)

The **"Owner ≠ Router"** principle is strictly enforced: `AGENTS.md` routes to truth but does not duplicate mutable domain facts.

```text
                                [AGENTS.md](AGENTS.md)
                                          │
                                          ▼
                               "Start Here & Rules"
                                          │
                    ┌─────────────────────┴─────────────────────┐
                    ▼                                           ▼
      [PROJECT_ROADMAP.md](PROJECT_ROADMAP.md)      [Target Product Contract](docs/authority/Target%20Product%20Contract%20v0.6.md)
                    │                                           │
                    ▼                                           ▼
          "Where is the project                       "What is the frozen
           and what is current?"                       product truth?"
```

| Fact / Question Type | Canonical Owner | Link | Router Role |
|:---|:---|:---|:---|
| **Operational Rules & Invariants** | `AGENTS.md` | [AGENTS.md](AGENTS.md) | **OWNS DIRECTLY** |
| **Product & Business Requirements**| `Target Product Contract v0.6` | [Target Product Contract](docs/authority/Target%20Product%20Contract%20v0.6.md) | Points to contract |
| **Architectural Rulings & Boundaries**| `Final Adjudication Memo` | [Final Adjudication Memo](docs/authority/Final%20Independent%20Adjudication%20Memo.md) | Points to memo |
| **Engineering Models & Gate Summaries**| `G1-G8 Architecture Summary`| [G1-G8 Summary](docs/authority/G1-G8%20Consolidated%20Architecture%20Summary.md) | Points to summary |
| **Database Field Authority (Room vs Cloud)**| `Account Field Classification`| [Field Authority](docs/authority/account_field_authority_classification.md) | Points to classification |
| **Ledger Creation Provenance (10 Paths)**| `Ledger Identity Inventory` | [Ledger Inventory](docs/authority/ledger_identity_inventory.md) | Points to inventory |
| **Current Milestone & Operating Mode**| `PROJECT_ROADMAP.md` | [PROJECT_ROADMAP.md](PROJECT_ROADMAP.md) | Points to roadmap |
| **Plan Status & Execution Records**| `docs/authority/PLAN_STATUS.md` | [PLAN_STATUS.md](docs/authority/PLAN_STATUS.md) | Points to plan status |
| **Machine Invariant Contracts**| `contract/invariant_contract.yaml` | [Invariant Contract](contract/invariant_contract.yaml) | Points to contract/ |
| **Certification Truth & Machine Proofs**| `evidence/` | [Evidence Directory](evidence/) | Points to evidence/ |
| **Audit History & Commit Ledger**| `CHANGELOG.md` | [CHANGELOG.md](CHANGELOG.md) | Points to changelog |

---

## 4. The 10 Permanent RED Invariants (Must Never Break)

These ten core guarantees represent the permanent, non-negotiable safety foundation of the application:

1. **Financial Correctness:** Additive ledger math from baseline (`derivedCurrentPosition`); whole-IQD and 250-IQD multiple validation; zero fractional currency drift.
2. **History Preservation:** No physical row deletion on `local_ledger_entries`; `ON DELETE CASCADE` eliminated in migration 14; ISP-side subscriber deletion transitions accounts to `isHistoryOnlySubscriber` without erasing historical debt or payments.
3. **Atomic & Idempotent Dispatch:** Single-writer SQLite hardware claim (`WHERE status = 'PENDING' AND dispatchClaimCount = 0`) before gateway call; zero duplicate charges.
4. **Canonical Financial Materialization:** Exactly one verified-success materializer (`resolvePendingOperationVerifiedSuccess`); non-financial API calls create zero debt.
5. **Uncertain-Operation Recovery:** 4-tuple correlation `(userID, operation, amount, timestamp ±90s)` against gateway statement upon app restart; no blind redispatch.
6. **Restore / Import Atomicity & Lineage:** Direct Atomic Room write transaction; pre-restore safety backup in `BackupManager.kt`; complete-lineage conflict resolution.
7. **Stale-Sync Protection:** Local generation counter `g4_local_generation` checked and updated in Room write transaction on dataset clear/restore to reject delayed cloud sync writes.
8. **Durable Outbox Safety:** Per-item outbox processing; poison-pill isolation; lost-ACK retry; zero terminal `DEAD_LETTER` state.
9. **Identity & Provenance Integrity:** Deterministic source-row coordinate identity for uTower imports; immutable runtime UUIDs for local entries.
10. **Release & Signing Integrity:** Fail-closed production release signing in Gradle; no fallback to placeholder or debug keys in release builds.

---

## 5. Accepted YELLOW Technical Debt (V1 Baseline)

The following items are **officially accepted V1 technical debt**. They are **NOT a backlog** and must not be refactored without an explicit user-facing requirement:
* **Double Currency Representation:** Legacy `REAL` columns in SQLite are safely guarded by runtime validation.
* **16 Sequential Room Migrations:** Fully tested against kill-points; SQLite executes the full chain in <15ms.
* **Large Source Files:** `Repositories.kt` (~3,200 lines) and `UserDetailScreenV2.kt` (~2,600 lines) function reliably and compile cleanly.
* **Gated Demo Mode:** Historical demo code is safely isolated behind `BuildConfig.DEBUG`.
* **Legacy Semantic Fields:** `loanIqd` and `isLegacy` are retained as read-only historical context.

---

## 6. GREEN Optional Conveniences (Not a Backlog)

> **RULE:** **GREEN items are NOT a backlog.** They are optional conveniences addressed only if naturally touched during related work:
* Cosmetic refactoring, private helper renaming, or formatting.
* Extra Markdown styling, diagram polish, or badge updates.
* Minor Jetpack Compose transition animations.
* Consolidating duplicated test assertion helpers.

---

## 7. The Scope Shield (What is Strictly Forbidden)

1. **Do NOT reopen closed G-areas:** G1 through G8 are completed/frozen release-boundary work areas. Reopening them without a failing test proof is strictly forbidden.
2. **Do NOT revive rejected concepts:** Staging databases, Web Admin scraping, and Identity Registries were permanently rejected by the Product Contract.
3. **Do NOT perform "while-we're-here" expansions:** Implement ONLY the minimal required scope for the current authorized task.
4. **Do NOT refactor working code solely for line count:** Large files that work reliably and pass tests are acceptable V1 debt.
5. **Do NOT touch path-locked historical records:** Files `PRODUCTION_INVARIANTS.md`, `ARCHITECTURE.md`, `PRODUCTION_CONTRACT_MATRIX.md`, and all files in `evidence/` are frozen for cryptographic hash integrity.

---

## 8. Proportional Verification Model (Verification Follows Risk)

Verification scales directly with the blast radius of the change:

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        PROPORTIONAL VERIFICATION MATRIX                                │
├──────────────────────────────┬──────────────────────────────┬──────────────────────────┤
│ CHANGE CATEGORY              │ REQUIRED VALIDATION          │ RUNNER / COMMAND         │
├──────────────────────────────┼──────────────────────────────┼──────────────────────────┤
│ 1. Documentation / Notes     │ • Git diff review (0 code)   │ git diff --stat          │
│                              │ • Link & scope validation    │                          │
│                              │ • (No test execution needed) │                          │
├──────────────────────────────┼──────────────────────────────┼──────────────────────────┤
│ 2. UI Layout / Formatting    │ • Targeted component test    │ ./gradlew testDebugUnitTest│
│                              │                              │ --tests "*SpecificTest*" │
├──────────────────────────────┼──────────────────────────────┼──────────────────────────┤
│ 3. Business Logic / Sync     │ • Relevant feature tests     │ ./gradlew test           │
│                              │ • Outbox & sync test suites  │                          │
├──────────────────────────────┼──────────────────────────────┼──────────────────────────┤
│ 4. RED Domain (Ledger,       │ • Strong targeted test proof │ ./gradlew test           │
│    Room, Lineage, Recovery)  │ • Broader regression only if │                          │
│                              │   blast radius requires it   │                          │
├──────────────────────────────┼──────────────────────────────┼──────────────────────────┤
│ 5. Release Build /           │ • Targeted tool test         │ python scripts/tool.py   │
│    Certification Semantics   │ • Full G8 Gate ONLY if       │ bash scripts/g8_gate.sh  │
│                              │   certification boundary changes                        │
└──────────────────────────────┴──────────────────────────────┴──────────────────────────┘
```

---

## 9. Lean Planning Model (Planning Follows Complexity)

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                LEAN PLANNING RULES                                     │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • TRIVIAL / DOC / TYPO (< 15 mins):                                                    │
│   → No formal plan. Execute directly; validate with git diff.                          │
│                                                                                        │
│ • STANDARD BUG FIX / UI TWEAK (< 2 hours):                                             │
│   → No formal plan file. Define task in prompt; execute; verify with targeted test.    │
│                                                                                        │
│ • MAJOR ARCHITECTURAL / DATA / RELEASE MILESTONE:                                      │
│   → Concise working plan. Register in PLAN_STATUS.md (ACTIVE → CLOSED).                │
│   → Verify against relevant regression tests.                                          │
│                                                                                        │
│ • WORKING PLAN DISCIPLINE:                                                             │
│   → Plans are disposable working tools. If new evidence contradicts a plan, update or  │
│     discard it immediately without ceremonial resistance.                              │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Curated Lessons Learned (Knowledge to Prevent Mistakes)

1. **File existence is NOT execution authorization:** The mere presence of a plan or script in the repository does not authorize running it.
2. **Never confuse producer claims with independent verification:** A test or report claiming "PASS" is meaningless unless evaluated by independent assertions in a clean environment.
3. **One State, One Authority:** Bugs emerge when multiple modules treat different fields (`remoteVersion`, `updatedAt`, local flags) as competing sources of truth.
4. **Business data and transport state must never mix:** The Room ledger is business meaning; outbox queues and sync cursors are technical transport. Never promote transport state to business history.
5. **A ledger is additive history:** Immutable ledgers do not use Last-Write-Wins (LWW) conflict resolution; they accumulate additions (T1 + T2 + T3).
6. **Valid fixture before adversarial mutation:** Never run an adversarial probe against an already-broken baseline; prove the fixture passes first.
7. **"While we're here" is NOT scope justification:** Never attach unrelated refactorings or cleanups to an active task.
8. **Simpler architecture wins:** When a simple mechanism (e.g., Direct Room) provides the exact same safety guarantee as a complex mechanism (e.g., Staging DB), always choose the simpler mechanism.

---

## 11. The 30-Second Agent Startup Loop

When starting any new task, follow this exact 3-step loop:

1. **Read `AGENTS.md`:** Review system purpose, RED invariants, Scope Shield, and Navigation Router.
2. **Check `PROJECT_ROADMAP.md`:** Check current operating milestone (`POST-V1 / STABLE MAINTENANCE`) and active task.
3. **Work & Proportionally Verify:** Execute task, run proportional verification, and stop.
