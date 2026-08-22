# AGENTS.md

## Purpose

This file establishes the **mandatory operational rules and invariant behaviors** for any AI coding agent working on this project.

*Note: This file contains Rules of Behavior. For project navigation, current state, and next authorized gates, consult `PROJECT_ROADMAP.md`.*

---

## ACTIVE IMPLEMENTATION ENTRY POINT

1. **Read `AGENTS.md` first for operational rules.**
2. **Read `PROJECT_ROADMAP.md` to determine the project's current state and navigation.**
3. **Use `docs/authority/Target Product Contract v0.6.md` as the ultimate product/business authority.**
4. **Use `docs/authority/Final Independent Adjudication Memo.md` as final architectural judgment / implementation boundary.**
5. **Use `docs/authority/G1-G8 Consolidated Architecture Summary.md` as engineering interpretation.**
6. **Inspect the current source/artifact for implementation state.**
7. **Use executable tests/evidence as verification proof.**
8. **Do not select tasks from Git history, historical plans, P0/P1/P2 matrices, ADRs, reports, SDD artifacts, or lessons learned.**
9. **Use historical material only when the current task explicitly requests forensic archaeology or historical rationale.**

> **CRITICAL RULE**: Do not infer implementation scope from historical documents. The only valid implementation scope is the minimum gap derived through a dedicated candidate scope assessment routed through `PROJECT_ROADMAP.md` and explicitly authorized by the frozen authority bundle. `DESIGN_DECISIONS.md` is a technical ADR and historical context layer subordinate to the frozen authority bundle.

---

## Information Architecture & Authority Hierarchy

1. **Rules of Behavior**: `AGENTS.md` (How to act)
2. **Current Scope & Navigation**: `PROJECT_ROADMAP.md` (Where the project is + where it is going)
3. **Frozen Product / Business Authority**: `docs/authority/Target Product Contract v0.6.md` (What is actually allowed/required)
4. **Frozen Architectural Authority**:
   - `docs/authority/Final Independent Adjudication Memo.md`
   - `docs/authority/G1-G8 Consolidated Architecture Summary.md`
5. **Implementation Transition Guidance (Subordinate)**:
   - `docs/authority/EARTHLINK_V1_HANDOVER.md`
   - `docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
6. **Implementation State Evidence**: Exact current source tree and build configuration.
7. **Verification Proof**: Executable automated tests, test matrix, and verification commands.

---

## Mandatory Non-Negotiable Invariants

### 1. No Deleting, Weakening, or Skipping Tests
- NEVER delete or skip tests.
- NEVER modify or weaken assertions/expected results in existing certification tests (`FinalTestMatrixCertificationTest`, `ProductionCertificationPipelineTest`, `ProductionExecutableInvariantsTest`, `DeepCrossLayerInvariantsTest`, and existing golden/regression tests).
- A failing test is proof of a production defect until the production code is proven otherwise. Never make a test pass by changing the test.

### 2. One State, One Authority
Every synchronized business state must have:
- one authoritative meaning;
- one version domain;
- one mutation policy;
- one synchronization path.

Do not introduce a second mechanism that independently writes the same state to Firebase.

### 3. Remote Version Semantics
Server version MUST be represented explicitly and compared only against the same semantic version domain.
Do not use `createdAt`, `occurredAt`, device clock, or local business timestamps as substitutes for server version.

### 4. Mutation Channel Rule
Any code capable of mutating synchronized application state or performing destructive database operations MUST pass through the canonical synchronization / mutation architecture defined in the frozen architecture bundle.

### 5. Snapshot & History Rule
Historical data, Snapshot state, and Runtime state are different concepts:
- Historical records (e.g., uTower import archives) must remain immutable.
- Snapshot state is the authoritative starting baseline.
- Runtime calculations must not re-apply historical snapshot records.

### 6. Fail-Closed Security
- **In Release Build:** missing or invalid production signing credentials => build failure. No fallback to debug signing, placeholder keys, or unsigned artifacts.
- **Android Keystore Failure:** fail closed immediately.
- **Existing Encrypted DB with Unrecoverable Key:** stop safely; never generate a replacement key over an existing database.

### 7. Certification Freeze
Existing certification tests are immutable during implementation and certification runs.

---

## API Documentation Reference

When working with network requests and API integrations, consult:
1. `/docs/earthlink_reseller_app_api_documentation_v0_7_0.md`: Primary API specification.
2. `/docs/earthlink_app_api_poc_v0_6_48.py`: Python reference implementation for payload unwrapping and edge cases.

---

## Mandatory Workflow

For every development session:

1. Follow the **ACTIVE IMPLEMENTATION ENTRY POINT** above.
2. Implement ONLY the minimal required scope as explicitly authorized through a dedicated candidate scope assessment mapped by `PROJECT_ROADMAP.md`.
3. Do not modify unrelated files.
4. Verify the implementation with tests and verified runners.
5. Update `CHANGELOG.md` and active phase tracking/roadmap milestones.
6. Stop and wait for user approval.

---

## Permanent Verification & Compliance Rules

1. **Requirement-by-Requirement Compliance Reviews:** Every phase or task closure MUST prove every single blocking requirement in `contract/phase_requirements.yaml` through explicit machine evidence.
2. **No Phase Closure from Narrative Reports:** Narrative claims are strictly ignored. Only machine-verified compliance matrix status (`ALL BLOCKING ROWS PASS`) authorizes closure.
3. **No NO-SOURCE Pass:** Any test task or compilation step returning `NO-SOURCE` MUST fail closed with exit code 2.
4. **No Unbounded Verification Commands:** All verification commands MUST execute through `run_verified_command.py` enforcing strict timeouts, process-tree termination, and heartbeat emission.
5. **No Deleting, Skipping, or Weakening Tests:** Tests MUST NEVER be deleted, skipped, or weakened.

---

## Implementation Plan Lifecycle

Before executing any implementation plan:

1. Read `docs/authority/PLAN_STATUS.md`.
2. Locate the exact plan path.
3. Execute the plan only if its status is `ACTIVE`.
4. Do not execute plans marked `CLOSED`, `SUPERSEDED`, `NOT-YET-EXECUTED`, or `STATUS-UNKNOWN`.

### On Plan Completion

When the implementation plan's authorized Definition of Done / closure criteria are actually satisfied:

1. Update the exact plan row in `docs/authority/PLAN_STATUS.md`: `ACTIVE` → `CLOSED`.
2. Add concise evidence to the plan-status note, such as the completion commit, verification result, or certification identifier.
3. Update `CHANGELOG.md` only when the completed work represents a meaningful repository milestone and is not already recorded.
4. Do not change the status of any other plan.
5. Do not resolve `STATUS-UNKNOWN` plans as part of completing another plan.
6. Do not rename, move, delete, or rewrite historical plans merely because the plan has closed.

### Completion Rule

A plan is CLOSED only when its actual authorized closure criteria are satisfied by repository evidence.

Do not mark a plan CLOSED because:
- only part of the plan was implemented;
- some tests passed;
- a subtask appears complete;
- the agent believes the work is finished.

### Failure / Blocked Rule

If the plan cannot be completed:

- keep the plan `ACTIVE` unless repository authority explicitly requires another status;
- record the blocking condition in the final report;
- do not silently mark the plan `CLOSED`.

### Status Integrity

`PLAN_STATUS.md` is the tracking authority for implementation-plan execution status only. It does not override stronger product, architecture, contract, or historical authority.

If the plan, `PLAN_STATUS.md`, and repository evidence disagree:

STOP and report the conflict. Do not guess.
