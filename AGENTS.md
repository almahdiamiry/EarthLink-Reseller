# AGENTS.md — EARTHLINK RESELLER V1 PROJECT OPERATING MODEL
## Primary Operational Guide for EarthLink Reseller V1

---

## 1. Primary Operational Guide Mandate

> ### ⚠️ PRIMARY OPERATIONAL GUIDE
> **`AGENTS.md` IS THE PRIMARY OPERATIONAL GUIDE FOR EARTHLINK RESELLER V1.**
>
> There is no second operating standard, no separate implementation governance authority, no separate simplification rulebook, and no plan governing another plan.
>
> Every AI agent and human maintainer working in this repository is guided by the operational rules, safety invariants, verification standards, and navigation procedures defined in this document.

---

## 2. System Identity & Business Purpose

* **What this project is:** A small, local-first, offline-capable Android application designed for authorized EarthLink resellers to manage subscriber accounts, record financial activity, and dispatch operational requests (Activation, Renewal, Refill) via EarthLink Gateway APIs.
* **Core Business Mission:** Protect subscriber financial history. The application must prevent financial data loss, corruption, duplicate charges, and incorrect balance materialization under all network and crash conditions according to the product contract.
* **Operating Pattern:**
  ```text
  UNDERSTAND → DECIDE → MINIMAL CHANGE → VERIFY WHAT MATTERS → STOP
  ```

> **Lessons Learned:** [`docs/LESSONS_LEARNED/`](docs/LESSONS_LEARNED/) — practical historical engineering knowledge; consult only when relevant to the current task.

---

## 3. Canonical Navigation Router (Where Truth Lives)

The **"Owner ≠ Router"** principle is strictly enforced: `AGENTS.md` routes to truth and operationalizes it, but does not duplicate mutable domain facts.

```text
                                [AGENTS.md]
                        (V1 Project Operating Model)
                                    │
           ┌────────────────────────┼────────────────────────┐
           ▼                        ▼                        ▼
  [PROJECT_ROADMAP.md]     [docs/authority/*]          [contract/ & evidence/]
   (Dynamic State GPS)     (Strategic Authorities)     (Machine Proofs & Tools)
```

### Authority Directory & Truth Ownership

| Authority Level | Document | Canonical Ownership | Router Role |
|:---|:---|:---|:---|
| **Project Operating Model** | [`AGENTS.md`](AGENTS.md) | **Operational Rules, Invariants & Router** | **OWNS DIRECTLY** |
| **Dynamic State GPS** | [`PROJECT_ROADMAP.md`](PROJECT_ROADMAP.md) | **Current Operating Mode & Active Task** | Points to GPS state |
| **Primary Strategic Authority** | [`Target Product Contract v0.6`](docs/authority/Target%20Product%20Contract%20v0.6.md) | **Business Truth, Product Rules & Contracts** | Points to product truth |
| **Primary Strategic Authority** | [`Final Independent Adjudication Memo`](docs/authority/Final%20Independent%20Adjudication%20Memo.md) | **Architectural Boundaries & Concurrency** | Points to architecture |
| **Static Supporting Authority** | [`G1-G8 Architecture Summary`](docs/authority/G1-G8%20Consolidated%20Architecture%20Summary.md) | **Engineering Models & Bounded Recovery** | Points to summary |
| **Static Supporting Authority** | [`Account Field Classification`](docs/authority/account_field_authority_classification.md) | **Room vs Firestore vs ISP Field Ownership** | Points to field authority |
| **Static Supporting Authority** | [`Ledger Identity Inventory`](docs/authority/ledger_identity_inventory.md) | **Canonical 10 Ledger Creation Paths** | Points to ID inventory |
| **Technical API Reference** | [`docs/earthlink_reseller_app_api_documentation_v0_7_0.md`](docs/earthlink_reseller_app_api_documentation_v0_7_0.md) | **Gateway API Endpoints & Payload Specs** | Points to API reference |
| **Machine Invariant Contracts** | [`contract/invariant_contract.yaml`](contract/invariant_contract.yaml) | **Executable Invariant Definitions** | Points to contracts |
| **Historical Evidence** | [`evidence/`](evidence/) | **Historical Certification Evidence & Test Runs** | Points to evidence |
| **Historical Audit Log** | [`CHANGELOG.md`](CHANGELOG.md) | **Commit & Milestone History** | Points to changelog |

---

## 4. Operational RED Invariants (Must Never Break)

Strategic domain authorities ([`Target Product Contract v0.6`](docs/authority/Target%20Product%20Contract%20v0.6.md) and [`Final Adjudication Memo`](docs/authority/Final%20Independent%20Adjudication%20Memo.md)) define the underlying business and architectural truth. `AGENTS.md` operationalizes and protects those guarantees through these ten mandatory RED invariants:

1. **Financial Correctness:** Additive ledger math from baseline (`derivedCurrentPosition`); whole-IQD and 250-IQD multiple validation; zero fractional currency drift.
2. **History Preservation:** No physical row deletion on `local_ledger_entries`; `ON DELETE CASCADE` eliminated in migration 14; ISP-side subscriber deletion transitions accounts to `isHistoryOnlySubscriber` without erasing historical debt or payments.
3. **Atomic & Idempotent Dispatch:** Single-writer SQLite hardware claim (`WHERE status = 'PENDING' AND dispatchClaimCount = 0`) before gateway call; zero duplicate charges.
4. **Canonical Financial Materialization:** Exactly one verified-success materializer (`resolvePendingOperationVerifiedSuccess`); non-financial API calls (`TEST_USER`, `EXTEND`) create zero debt.
5. **Uncertain-Operation Recovery:** 4-tuple correlation `(userID, operation, amount, timestamp ±90s)` against gateway statement upon app restart; no blind redispatch.
6. **Restore / Import Atomicity & Lineage:** Direct Atomic Room write transaction; pre-restore safety backup in `BackupManager.kt`; complete-lineage conflict resolution.
7. **Stale-Sync Protection:** Local generation counter `g4_local_generation` checked and updated in Room write transaction on dataset clear/restore to reject delayed cloud sync writes.
8. **Durable Outbox Safety:** Per-item outbox processing; poison-pill isolation; lost-ACK retry; zero terminal `DEAD_LETTER` state.
9. **Identity & Provenance Integrity:** Deterministic source-row coordinate identity for uTower imports; immutable runtime UUIDs for local entries.
10. **Release & Signing Integrity:** Fail-closed production release signing in Gradle; no fallback to placeholder or debug keys in release builds.

---

## 5. Accepted YELLOW Technical Debt (Engineering Judgment)

The following items are **officially accepted V1 technical debt**. They are **NOT a backlog** and must not be refactored without an explicit user-facing requirement:
* **Double Currency Representation:** Legacy `REAL` columns in SQLite are safely guarded by runtime validation.
* **16 Sequential Room Migrations:** Fully tested against kill-points; SQLite executes the full chain in <15ms.
* **Large Source Files:** `Repositories.kt` (~3,200 lines) and `UserDetailScreenV2.kt` (~2,600 lines) function reliably and compile cleanly.
* **Gated Demo Mode:** Historical demo code is safely isolated behind `BuildConfig.DEBUG`.
* **Legacy Semantic Fields:** `loanIqd` and `isLegacy` are retained as read-only historical context.

> **RULE:** *Do not create a major refactor merely because the code could look nicer. Touch YELLOW debt only if the current task directly benefits from it.*

---

## 6. GREEN Optional Conveniences (Not an Obligation)

* Cosmetic refactoring, private helper renaming, or formatting.
* Extra Markdown styling, diagram polish, or badge updates.
* Minor Jetpack Compose transition animations.
* Consolidating duplicated test assertion helpers.

> **RULE:** *GREEN is not an obligation. Address it only if naturally touched during related work; otherwise ignore it.*

---

## 7. The Scope Shield (Anti-Overengineering)

1. **Small Single-Maintainer Product:** Build for the actual product and its actual scale. Do not turn a small reseller app into an enterprise platform.
2. **Do NOT build generic infrastructure:** Staging databases, Web Admin scraping, and Identity Registries were permanently rejected by the Product Contract.
3. **Do NOT reopen closed G-areas:** G1–G8 are settled historical work. Do not reopen them merely because a cleaner idea exists. Reopening requires direct new evidence such as:
   - authoritative contradiction,
   - demonstrable current repository defect,
   - failed invariant/test,
   - direct product, safety, architectural, or repository problem.
4. **Do NOT perform "while-we're-here" expansions:** Implement ONLY the minimal required scope for the current authorized task.
5. **Do NOT refactor working code solely for line count:** Large files that work reliably and pass tests are acceptable V1 debt.
6. **Historical certification records:** Files `PRODUCTION_INVARIANTS.md`, `ARCHITECTURE.md`, `PRODUCTION_CONTRACT_MATRIX.md`, `ISSUE_LOG.md`, `docs/historical/g8/G8_Plan.md`, and all files in `evidence/` are historical certification records. Do not modify them during routine maintenance unless the active task explicitly concerns those records.

---

## 8. Minimum-Change Rule

1. Inspect what already exists.
2. Determine whether the needed capability already exists.
3. Reuse it if practical.
4. Change only what the active task strictly needs.
5. Add infrastructure only when a demonstrated capability gap remains.

---

## 9. Operational Testing Playbook (Universal Risk-Proportional Verification)

The **Testing Playbook** is the single operational testing method for all maintenance and feature work. Risk determines **depth of verification**, not a separate methodology.

### 9.1 Core Reasoning (The 8 Questions)
Use these questions to guide verification. Apply and record only the questions relevant to the material risk and scope of the actual change:
* **Low-risk work:** Does not require explicit written answers to all eight questions.
* **Higher-risk work:** Should record the questions that materially affect the verification decision.

1. **What is the claim?** (Exact behavioral guarantee being asserted)
2. **What is the correct business meaning?** (Domain truth per Target Product Contract v0.6)
3. **What is the real production path?** (Actual runtime code executed in production)
4. **What assumption am I relying on, and where is it proven?** (No unproven implicit assumptions)
5. **What could falsify the claim?** (Adversarial conditions, edge cases, failure paths)
6. **Is the expected behavior independently defined?** (Never derive expected values from code under test)
7. **What exactly did I prove?** (Boundaries of the executed test)
8. **What does this NOT prove?** (Explicit non-coverage and unexecuted layers)

### 9.2 Material Risk Depths
Risk depth is determined by the **material impact of the actual change**, not by class name, file size, or historical labels:

| Risk Level | Typical Change Scope | Required Verification Depth | Example Scenarios |
|:---|:---|:---|:---|
| **LOW** | Documentation, comments, UI logging, visual layout tweaks, pure ViewModel thinning with preserved semantics | • Basic verification<br>• Git diff review (0 code for docs)<br>• Targeted component / unit test | • Logging in `Repositories.kt`<br>• Search bar padding adjustment<br>• Refactoring ViewModel without changing state flow |
| **MEDIUM** | Standard business logic, presentation orchestration, non-financial API call typing, retry policies | • Business semantics validation<br>• Real production-path tracing<br>• Targeted adversarial probe when useful | • Outbox queue prioritization<br>• Non-financial network DTO parsing<br>• UI error banner display logic |
| **HIGH** | RED domain: Ledger math, balance calculation, Room schema/migrations, atomic dispatch claim, sync lineage/generation, restore merge | • Business semantics validation<br>• Real production-path tracing<br>• Adversarial verification<br>• Broader relevant regression suite<br>• Independent review when data integrity is impacted | • `BalanceCalculator.kt` changes<br>• Room migration 16→17<br>• Single-writer dispatch claim query<br>• `g4_local_generation` counter logic<br>• Restore merge lineage reconciliation |

### 9.3 Semantic Safety & Failure Classification
* **No Circular Assertions:** Expected test outcomes must be derived independently from product rules, never by mirroring implementation code.
* **No Assertion Weakening:** Never relax an assertion or mock away invariants to force a green test.
* **Semantic Failure Taxonomy:** When a test fails, classify the root cause explicitly before acting:
  - `PRODUCT DEFECT`: Production code violates domain contract $\rightarrow$ fix production code.
  - `TEST DEFECT`: Test assertion or scenario incorrectly specified $\rightarrow$ fix test.
  - `FIXTURE / SETUP DEFECT`: Test fixture or precondition broken $\rightarrow$ fix fixture.
  - `SEMANTIC-ASSUMPTION ERROR`: Mismatch on domain meaning $\rightarrow$ consult Product Contract.
  - `ENVIRONMENT / TOOLING FAILURE`: Gradle/JVM runtime failure $\rightarrow$ fix environment.

### 9.4 EarthLink Domain Semantic Lessons
Maintainers must uphold these domain testing truths:
1. `dispatchClaimCount = 0` signifies the local operation was **not authorized** for external dispatch.
2. `dispatchClaimCount = 1` signifies dispatch authorization was acquired and external execution may have occurred.
3. For statement-based financial verification, use the applicable authoritative correlation contract. The defined 4-tuple `(userID, operation, amount, timestamp ±90s)` applies where that verification contract governs the operation. Other operation types must use their own authoritative verification contract. A positive external observation does not prove execution without satisfying the applicable correlation contract.
4. Transport tombstone $\neq$ user-level financial deletion (ledger history is immutable).
5. uTower snapshot baseline $\neq$ complete imported ledger history.
6. History-only subscriber state $\neq$ financial debt deletion.

### 9.5 Standardized Verification Reporting Contract
For all verification tasks, report results using this exact schema:
```text
Claim:                 [Exact behavior or invariant asserted]
Evidence:              [Executed test tasks, verification commands, or inspected diffs]
Verification scope:    [Focused (targeted tests) / Broader (subsystem regression) / Documentation / routing inspection]
Result:                [PASS / FAIL]
What this proves:      [Explicit verified invariant or inspection outcome]
What this does NOT prove: [Explicit unverified paths / boundaries / unexecuted layers]
Confidence:            [HIGH / MEDIUM / LOW]
```
> **Prohibition & Accuracy Rules:**
> - Never state "all tests passed" unless the full repository test suite was executed. A targeted run must be reported as *"Focused verification passed."*
> - For documentation or routing inspection, explicitly state that no application runtime behavior or test suites were executed. Do not report inspection as runtime verification.

### 9.6 G8 Operational Status & Release Gate Routing
* **G8 is permanently CLOSED.** All historical G8 certification contracts, scripts, plans, and tests are archived under `docs/historical/g8/`.
* **Canonical Production Gate:** `scripts/production_gate.sh` is the sole supported release gate. `production_gate.ps1` is a historical unsupported mirror in `docs/historical/operational-gates/`.
* Future agents and maintainers **must NOT** run G8 certification, require G8 artifacts for routine changes, recreate G8, or reopen G8 scope. The Testing Playbook above governs all ongoing development.

---

## 10. Lean Planning Model (Planning Follows Complexity)

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
│ • MAJOR ARCHITECTURAL / RELEASE MILESTONE:                                             │
│   → Concise working plan. Register in PLAN_STATUS.md (ACTIVE → CLOSED).                │
│   → Verify against relevant regression tests.                                          │
│                                                                                        │
│ • WORKING PLAN DISCIPLINE:                                                             │
│   → Plan files are temporary working tools, NOT governance documents.                  │
│   → PLAN_STATUS.md is a major-work registry, NOT daily operational governance.         │
│   → If new evidence contradicts a plan, update or discard it immediately.              │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 11. Curated Lessons Learned (Knowledge to Prevent Mistakes)

1. **File existence is NOT execution authorization:** The mere presence of a plan or script in the repository does not authorize running it.
2. **Never confuse producer claims with independent verification:** A test or report claiming "PASS" is meaningless unless evaluated by independent assertions in a clean environment.
3. **One State, One Authority:** Bugs emerge when multiple modules treat different fields (`remoteVersion`, `updatedAt`, local flags) as competing sources of truth.
4. **Business data and transport state must never mix:** The Room ledger is business meaning; outbox queues and sync cursors are technical transport. Never promote transport state to business history.
5. **A ledger is additive history:** Immutable ledgers do not use Last-Write-Wins (LWW) conflict resolution; they accumulate additions (T1 + T2 + T3).
6. **Valid fixture before adversarial mutation:** Never run an adversarial probe against an already-broken baseline; prove the fixture passes first.
7. **"While we're here" is NOT scope justification:** Never attach unrelated refactorings or cleanups to an active task.
8. **Simpler architecture wins:** When a simple mechanism (e.g., Direct Room) provides the exact same safety guarantee as a complex mechanism (e.g., Staging DB), always choose the simpler mechanism.

---

## 12. Fast Agent Startup Loop & Stop Rule

When starting any new task, follow this exact loop:

1. **Read `AGENTS.md`:** Review system purpose, RED invariants, Scope Shield, and Navigation Router.
2. **Check `PROJECT_ROADMAP.md`:** Check current operating milestone (`POST-V1 / STABLE MAINTENANCE`) and active task.
3. **Consult Relevant Truth:**
   * Business rules $\rightarrow$ [`Target Product Contract v0.6`](docs/authority/Target%20Product%20Contract%20v0.6.md)
   * Architecture $\rightarrow$ [`Final Independent Adjudication Memo`](docs/authority/Final%20Independent%20Adjudication%20Memo.md)
   * UI / Routine $\rightarrow$ Directly to code.
4. **Execute Minimal Change:** Apply smallest reasonable change.
5. **Proportionally Verify:** Run targeted verification.
6. **STOP:** When the task is complete and verified, stop. Do not expand scope.


