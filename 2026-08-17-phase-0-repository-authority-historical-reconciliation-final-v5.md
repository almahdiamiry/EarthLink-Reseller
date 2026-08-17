# Phase 0 — Repository Authority & Historical Reconciliation (Revision 5 — Final Pre-Execution)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to execute this plan task-by-task. Do not skip checkpoints. Do not treat historical repository documentation as authority over the Implementation Reference Bundle. Frozen Authority Set = the first three documents; Transition Guidance Set = the last two.

## Goal

Turn the full `earthlink-reseller-v1 (71)` repository into a clean implementation control-plane baseline whose active governance, architecture documentation, machine contracts, roadmap, verification configuration, and evidence handling all point to the frozen Earthlink target before any production-runtime implementation changes begin.

Phase 0 is deliberately **not** a code-fix phase. Its job is to remove the repository's ability to steer a future implementation agent back toward superseded architecture, stale roadmaps, dead-letter transport semantics, broad coordinator ownership, or other historical control-plane assumptions. Useful historical knowledge is preserved through explicit lessons/history artifacts; obsolete instructions are removed from active discovery.

## Final Phase-0 success condition

At Phase-0 exit, the repository must satisfy all of the following:

```text
Implementation Reference Bundle is traceable
        ↓
Fresh Git control plane is valid
        ↓
Exact extracted source tree has a reproducible manifest
        ↓
Exact current test corpus has a reproducible inventory
        ↓
All human-readable control-plane guidance is classified and reconciled
        ↓
All machine-enforced control surfaces are classified and neutralized/rebased
        ↓
Exactly one canonical implementation sequence exists
        ↓
G1 implementation/verification ownership is explicit
        ↓
No obsolete guidance remains active/authoritative
        ↓
Phase-0 changes are bounded by an explicit allowlist
        ↓
Final evidence proves source/test/authority identity
```

The final Phase-0 statement must be evidence-bounded:

> **Repository control plane is aligned and the exact source/test baseline is recorded. Production implementation and verification remain outstanding.**

Do not claim production readiness, implementation completeness, or verification closure from Phase 0 alone.

---

# Authority and Scope

## Implementation Reference Bundle

1. `Target Product Contract v0.6`
2. `G1–G8 Consolidated Architecture Summary`
3. `Final Independent Adjudication Memo`
4. `EARTHLINK_V1_HANDOVER.md`
5. `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`

Authority semantics:

```text
Target Product Contract
    = product / business authority

G1–G8 Consolidated Architecture Summary
    = engineering interpretation of accepted product target

Final Independent Adjudication Memo
    = final architectural judgment / implementation boundary

EARTHLINK_V1_HANDOVER.md
EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md
    = transition guidance subordinate to the three frozen authorities

The first three files are the Frozen Authority Set. The last two are the Transition Guidance Set. Together they form the Implementation Reference Bundle.

Current source/artifact
    = implementation-state evidence

Executable tests / build / instrumentation / release artifacts
    = verification evidence
```

The five Implementation Reference Bundle artifact hashes for this implementation session are:

```text
Target Product Contract v0.6
75fab09e21c25f3871b9a4cee081a0b769d8a7dbda020fbc175e6be613b86fc5

G1–G8 Consolidated Architecture Summary
fa93cf02c5c9216690f44aba6bc09ac63ea11a7e02efe97598b52ccdefe44442

Final Independent Adjudication Memo
431463bd3dc8f1202f45574ee89240273a479e9799e0652e9dd91bee4d56a904

EARTHLINK_V1_HANDOVER.md
4ad730df10aeb903af18f278b91eaa38445345936ddf32f05f260dee522a559b

EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md
b55748b9d292a77970d6120a8c4be7c6cdb637ef44b07d3ea2418ddc21b81a2b
```

## Forensic artifact provenance

```text
Forensic artifact:
earthlink-reseller-v1 (71)(1).zip
SHA-256:
746f8c049c7c8b31208fbfab2ddcfb75daa7d2a7930d36ff912462d357e8c233
```

The ZIP hash is provenance/reference metadata for the full repository artifact reviewed before execution. The implementation agent is expected to receive and operate on an already-extracted repository workspace; Phase 0 does **not** require the agent to download, locate, or extract a ZIP. Do not claim ZIP-to-workspace cryptographic equivalence unless a separate transfer manifest proving that equivalence is supplied. Phase 0 instead proves the actual implementation workspace identity with a complete file-level manifest.

The corrupted historical `.git` database is intentionally discarded. The source tree is preserved; the Git control plane is reinitialized. Git history created after reset is explicitly forensic/control-plane history and must never become a source of implementation, architecture, roadmap, or task-selection authority.

## Frozen architecture boundaries that Phase 0 must preserve

- Direct Atomic Room remains the V1 business-state serialization boundary.
- Local ledger/history remains business authority; financial history is protected/additive.
- ISP-side deletion must not physically delete local financial history.
- Stable transaction identity is required for new ledger transactions and historical import identity where source IDs are absent.
- G4 generation is a lineage/session invalidation mechanism, not a universal mutation counter.
- `remoteVersion` / `updatedAt` are not G4 generation.
- Restore Merge conflicting baselines require complete snapshot-lineage selection.
- No UI, network wait, or network-dependent decision-making inside the final Room business transaction.
- Outbox is transport-only; failed obligations remain recoverable; no terminal `DEAD_LETTER` business state.
- Historical backup transport metadata is not automatically current transport authority.
- Certification remains outside production runtime.
- Do not introduce `dataset_id`, `published_dataset_id`, staging databases, identity registries, generic reconciliation engines, generic synchronization state machines, or runtime governance registries merely because implementation is difficult.

## Phase-0 evidence handling boundary

Temporary Phase-0 manifests, inventories, reports, allowlists, and final gate outputs are execution evidence, not permanent product governance. Store them outside the active repository control plane under `/tmp/earthlink-phase0-evidence/` during execution or as CI/review artifacts after execution. Do not create a permanent `docs/superpowers/evidence/` subtree merely to record Phase-0 activity.

Permanent repository control-plane files should remain limited to artifacts that future agents genuinely need for implementation or verification, such as `AGENTS.md`, the active current-phase plan, the authority bundle, current invariant/contract files, and the operational roadmap.

## Phase-0 scope boundary

### Allowed in Phase 0

- Git metadata reset and new repository initialization.
- Creation/update/removal/archive of documentation and control-plane files.
- Creation/update of machine-readable governance/contract metadata when required to remove obsolete authority or make current control-plane semantics explicit.
- Creation of temporary execution manifests and evidence inventories outside the active repository control plane. These artifacts are produced under `/tmp/earthlink-phase0-evidence/` (or an equivalent external/CI artifact location) and are not permanent repository governance files.
- Minimal changes to repository validators or control-plane scripts when a current validator itself encodes obsolete authority and must be corrected to make the repository self-consistent.
- Updating the active roadmap / transition guidance to one canonical implementation sequence.

### Forbidden in Phase 0

- Kotlin/Java production behavior changes.
- Room schema/runtime changes.
- Firebase/Firestore runtime behavior changes.
- ISP API behavior changes.
- Runtime concurrency fixes.
- G1/G2/G3/G4/G5/G6/G7 implementation corrections themselves.
- Removing or weakening tests merely to satisfy Phase-0 checks.
- Architectural redesign.

### Control-plane exception

A validator, manifest generator, CI rule, or other control-plane executable may be changed **only** when the audit proves that it still enforces an obsolete authority or prevents the repository from expressing the frozen target correctly. Such a change must:

1. be explicitly listed in the Phase-0 change inventory;
2. touch only control-plane/tooling code;
3. be minimal;
4. have a dedicated verification step;
5. remain separate from production-runtime changes.

---

# Canonical implementation sequence after Phase 0

The Implementation Handover Appendix is the canonical implementation sequence. The Phase-0 repository roadmap must be reconciled to exactly this order:

```text
PHASE 0 — Repository / Documentation / Governance Alignment
        ↓
PHASE 1 — G2 / Transport
        ↓
PHASE 2 — G3 / Restore & Import
        ↓
PHASE 3 — G4 / Concurrency & Lineage
        ↓
PHASE 4 — G5 / Identity
        ↓
PHASE 5 — G6/G7 / Semantics + Migration
        ↓
PHASE 6 — G8 / Certification
```

G1 is architecturally closed but is **not implementation-complete by implication**. Its implementation and verification ownership must be explicitly tracked inside the later implementation plan. Because G1's implementation boundary is tightly coupled to stable local transaction identity and durable outbox materialization, Phase 1/G2 is the primary implementation owner for the G1 durability lane, while G5 remains the authority for historical/import identity. G1 must not disappear merely because its architecture decision is closed.

The Phase-0 plan must not create a competing `Phase 1 P0 financial-history` sequence. Financial-history protection is a frozen invariant and must be attached to the later G6/G7 deletion/migration verification work, with its required source protections tracked in the implementation backlog.

---

# Task 0 — Discard Corrupted Git Control Plane and Create Fresh Repository Baseline

**Files / surfaces:**

- Delete: `.git/` from the extracted forensic repository.
- Create: fresh Git metadata with `git init`.
- Create during execution under `/tmp/earthlink-phase0-evidence/`:
  - `phase0_baseline_inventory.md`
  - `phase0_source_manifest.sha256`

**Purpose:** Establish a valid repository control plane without attempting to recover obsolete/corrupt historical Git state.

### Step 0.1 — Verify the actual implementation workspace before reset

The agent works on the already-extracted repository workspace. Do not require a ZIP download or extraction step. Verify the expected repository root and generate the source-tree identity from the workspace itself:

```bash
pwd
find . -type f -not -path './.git/*' | sort > /tmp/phase0_forensic_file_list.txt
find . -type f -not -path './.git/*' -print0 | sort -z | xargs -0 sha256sum > /tmp/phase0_forensic_source_manifest.sha256
wc -l /tmp/phase0_forensic_file_list.txt
wc -l /tmp/phase0_forensic_source_manifest.sha256

test -f "EARTHLINK_ROOT_CAUSE_LOOP_BREAKING_PLAN_UPDATED_FINAL.md"
test -f "DESIGN_DECISIONS.md"
test -f "PRODUCTION_INVARIANTS.md"
test -d ".git"
test -d "contract"
test -d "docs"
test -d "evidence"
```

Expected:

- The full repository tree is present in the actual workspace.
- Historical root plans/reports supplied in the full artifact are present.
- `.git` exists before reset.

The recorded ZIP SHA-256 is provenance only; it is not a runtime input to Phase 0. Do not claim a mathematical ZIP-to-workspace equivalence unless a separate transfer manifest is available. The `/tmp/phase0_forensic_source_manifest.sha256` file is the authoritative pre-reset source-tree identity proof for the workspace actually being changed.

### Step 0.2 — Demonstrate historical Git corruption

Run:

```bash
git status --short || true
git fsck --full || true
git rev-parse HEAD || true
```

Expected: Git reports the known corruption. Do not attempt object recovery or use old Git history as an authority source.

Record the observed corruption once, then stop relying on the old Git database.

### Step 0.3 — Remove the corrupted Git database

```bash
rm -rf .git
```

Do not run object recovery tools after this step except for ordinary filesystem verification.

### Step 0.4 — Initialize new Git

```bash
git init
git branch -M main
git status --short
```

Expected: Git is operational and the extracted tree is visible as untracked content.

### Step 0.5 — Establish the provisional Phase-0 scope boundary

Create: `/tmp/earthlink-phase0-evidence/phase0_scope_boundary.md`.

This file is an execution artifact only; it must not be added to the permanent repository control plane.

This is a **scope rule**, not yet the final path allowlist. It must state: production runtime/source changes are forbidden; control-plane changes may be authorized only when justified by the repository inventory; architecture redesign is forbidden; the frozen authority files are immutable during normal work.

Do not freeze `phase0_allowed_paths.txt` yet. The exact path allowlist is created only after the repository-wide control-plane and historical-artifact inventory in Tasks 2–3.5 is complete, and before the first substantive cleanup edit.

### Step 0.6 — Prove source completeness against Git ignore rules before the baseline commit

After `git init` and before the first baseline commit:

```bash
git status --ignored --short > /tmp/phase0_git_ignored_before_baseline.txt

python3 - <<'PY'
from pathlib import Path
import subprocess
root = Path('.')
files = [Path(x) for x in subprocess.check_output(['git','ls-files','--others','--exclude-standard','-z']).decode('utf-8', 'replace').split('\x00') if x]
for f in files:
    print(f)
PY
```

For every file in the pre-reset source manifest, prove one of:

```text
TRACKED IN THE BASELINE COMMIT
OR
EXPLICITLY APPROVED NON-SOURCE / GENERATED EXCLUSION
```

Before committing, stage the complete candidate baseline and compare the staged path set to the pre-reset manifest. An ignored file that is part of the implementation, build, test, governance, or verification surface must be force-added or explicitly classified as non-source/generated with a recorded rationale. Any source-manifest path missing from the staged baseline is a failure.

An ignored file may not silently disappear from the baseline. Record every ignored path that is relevant to governance, verification, build/release behavior, or implementation source. Any such path must be force-added or explicitly excluded with a recorded reason.

Create/update `/tmp/earthlink-phase0-evidence/phase0_baseline_inventory.md` with the ignored-file disposition before committing.

### Step 0.7 — Create the pre-cleanup baseline commit

Before any Phase-0 cleanup, commit the extracted source tree exactly as supplied and verified by the manifest/ignore audit:

```bash
git add -A
git commit -m "chore: establish forensic baseline non-authoritative"
git tag -a FORENSIC_BASELINE_NON_AUTHORITATIVE -m "Forensic baseline only; never implementation, architecture, roadmap, or task-selection authority"
git rev-parse HEAD
```

The first commit is intentionally allowed to contain obsolete plans, reports, ADRs, SDD, and other historical material. Its purpose is traceability, not authority. `AGENTS.md` must explicitly prohibit retrieving implementation instructions from this historical baseline or later Git history except for a task that explicitly requests forensic archaeology.

Capture the new commit hash immediately.

This commit must represent the source tree **before** documentation/governance cleanup, so later diff evidence can distinguish forensic source content from Phase-0 control-plane changes.

### Step 0.8 — Preserve the pre-reset source manifest as external execution evidence

The source manifest was already generated in `/tmp/phase0_forensic_source_manifest.sha256` before the old Git database was removed. Move that exact manifest into the temporary Phase-0 evidence bundle:

```bash
mkdir -p /tmp/earthlink-phase0-evidence
mv /tmp/phase0_forensic_source_manifest.sha256 /tmp/earthlink-phase0-evidence/phase0_source_manifest.sha256
```

Do not copy this manifest into the permanent repository control plane.

This ordering matters: the manifest proves the extracted ZIP tree independently of the new Git history, and the first Git commit remains the exact extracted source tree before Phase-0 edits. `.git/` is excluded because Git metadata is the control plane being reset.

Also generate a machine-readable inventory containing at least:

```text
path
sha256
file type
size
```

Record the explicit exclusion rule:

```text
.git/** is excluded from the source-tree manifest because Git metadata is the control plane being reset.
```

### Step 0.9 — Record baseline metadata

`phase0_baseline_inventory.md` must record:

- forensic ZIP filename;
- forensic ZIP SHA-256;
- old Git HEAD if readable;
- old Git corruption status;
- new Git baseline commit;
- source-tree file count;
- source manifest path/hash;
- Phase-0 scope boundary.

### Step 0.10 — Checkpoint

```bash
git status --short
git log --oneline --max-count=3
```

Expected: the new baseline commit exists and the external Phase-0 evidence bundle contains the baseline inventory and source manifest. These execution artifacts are intentionally not committed to the permanent repository.

---

# Task 1 — Verify and Vendor the Five Governing Authority Artifacts

**Files:**

- Create: `docs/authority/Target Product Contract v0.6.md`
- Create: `docs/authority/G1-G8 Consolidated Architecture Summary.md`
- Create: `docs/authority/Final Independent Adjudication Memo.md`
- Create: `docs/authority/EARTHLINK_V1_HANDOVER.md`
- Create: `docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
- Create: `docs/authority/README.md`
- Modify: `/tmp/earthlink-phase0-evidence/phase0_baseline_inventory.md`

### Step 1.1 — Verify the five supplied inputs

From the session-provided authority artifacts, verify their SHA-256 values before copying them. Use the exact filenames below; the executor may adapt the source directory, but must not substitute another revision:

```bash
sha256sum "Earthlink_Target_Product_Contract_v0.6.md"
sha256sum "EARTHLINK_G1_G8_CONSOLIDATED_ARCHITECTURE_SUMMARY_FINAL_v2.md"
sha256sum "EARTHLINK_FINAL_INDEPENDENT_ADJUDICATION_MEMO_FINAL.md"
sha256sum "EARTHLINK_V1_HANDOVER.md"
sha256sum "EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md"
```

Expected values are the five hashes recorded in this plan.

If an expected hash differs, **stop Phase 0 authority installation and record the mismatch**. Do not silently substitute another revision.

### Step 1.2 — Copy without semantic edits

Copy the five documents byte-for-byte into `docs/authority/`.

Do not normalize wording, dates, headings, or terminology.

### Step 1.3 — Create authority README

State explicitly:

```text
Product/business authority:
    Target Product Contract v0.6

Engineering interpretation:
    G1–G8 Consolidated Architecture Summary

Final architectural judgment:
    Final Independent Adjudication Memo

Implementation-transition guidance:
    EARTHLINK_V1_HANDOVER.md
    EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md

Current implementation/artifact evidence:
    exact current source/artifact

Verification evidence:
    executable tests / instrumentation / build / release artifacts
```

State that the two handover documents are subordinate to the three frozen documents whenever terminology or priority appears to differ.

### Step 1.4 — Create the immutable authority manifest

Create: `docs/authority/authority_manifest.sha256`.

The manifest must contain the exact five expected SHA-256 values plus the authority role for each file. Add an `AUTHORITY_BUNDLE_IMMUTABLE` rule to `docs/authority/README.md` stating that normal implementation work may not edit these files.

### Step 1.5 — Verify byte identity

```bash
sha256sum docs/authority/*.md
sha256sum -c docs/authority/authority_manifest.sha256
```

Match every result to the recorded authority hash.

### Step 1.6 — Define authority-change protection

`AGENTS.md` and the final Phase-0 gate must state:

```text
Frozen authority files are immutable during normal implementation.
Any change requires an explicit authority-change review, a new externally supplied artifact identity, updated hashes, and a documented product/architecture decision.
```

Do not implement automatic rewriting of frozen authority content.

### Step 1.7 — Commit

```bash
git add docs/authority
git commit -m "docs: establish frozen implementation authority bundle"
```

---

# Task 2 — Inventory ALL Human and Executable Control-Plane Surfaces

**Files / surfaces to inventory:**

```text
root *.md / *.txt / *.yaml / *.json
AGENTS.md
AI_DEVELOPMENT_GUIDE.md
CONTRIBUTING.md
ARCHITECTURE.md
DESIGN_DECISIONS.md
PRODUCTION_INVARIANTS.md
PRODUCTION_CONTRACT_MATRIX.md
PROJECT_ROADMAP.md
PROJECT_LESSONS_LEARNED.md
HOUSEKEEPING_REPORT.md
docs/**
.superpowers/**
contract/**
evidence/**
app/evidence/**
scripts/**
.github/**
gradle/**
Gradle/CI task definitions
shell/Python/JS helper scripts
.gitignore
build/release helpers
validator configuration
```

**Create:** `/tmp/earthlink-phase0-evidence/phase0_control_plane_inventory.md`

### Step 2.1 — Enumerate everything

The inventory must explicitly include the active agent entry point (`AGENTS.md`), the current-phase plan, the five Implementation Reference Bundle files, and all executable/control-plane surfaces. Historical materials are not automatically active merely because they live under `docs/` or `.superpowers/`.


Use filesystem-based discovery instead of a hard-coded list:

```bash
find . -type f -not -path './.git/*' | sort > /tmp/phase0_all_files.txt
```

Create classifications for at least:

```text
CURRENT / ALIGNED
UPDATE / REBASE
SUPERSEDED / HISTORICAL
SUMMARIZE THEN REMOVE
ARCHIVE OUTSIDE ACTIVE DISCOVERY
REMOVE
MACHINE-CONTRACT RECONCILIATION REQUIRED
EXECUTABLE CONTROL SURFACE REQUIRES REVIEW
```

### Step 2.2 — Search for authority-seeking language

Search all files for terms that indicate a file attempts to direct future implementation:

```bash
grep -RniE \
  "authoritative|source of truth|highest-priority|select the.*task|implementation sequence|governing plan|must implement|canonical architecture|current architecture" \
  --exclude-dir=.git .
```

Every hit must receive a disposition. Do not assume documentation is harmless because it is historical.

### Step 2.3 — Search for rejected architectural mechanisms

Search the entire repository, but classify matches by context rather than using raw substring absence as the exit rule:

```bash
grep -RniE \
  "DEAD_LETTER|dead_letter|dataset_id|published_dataset_id|staging database|identity registry|generic reconciliation|generic synchronization|runtime governance|DataOperationCoordinator" \
  --exclude-dir=.git .
```

For each match record whether it is:

```text
ACTIVE INSTRUCTION → must be corrected
FROZEN AUTHORITY MENTION OF A REJECTED CONCEPT → allowed
HISTORICAL EVIDENCE → must be explicitly non-authoritative
TEST/DETECTION RULE → evaluate semantics
SOURCE IMPLEMENTATION → implementation-gap inventory, not Phase-0 code fix
```

### Step 2.4 — Search executable/control surfaces

Explicitly inspect:

```bash
find scripts .github -type f 2>/dev/null | sort
find . -type f \( -name '*.sh' -o -name '*.py' -o -name '*.ps1' -o -name '*.js' \) -not -path './.git/*' | sort
```

Inspect Gradle tasks and build/release helpers that can influence certification, gate closure, or CI decisions.

Record any script that:

- selects roadmap tasks;
- asserts old architecture requirements;
- interprets `DEAD_LETTER` as accepted;
- reads old plan files as governing input;
- declares PASS/production readiness from stale artifacts.

### Step 2.5 — Check ignore rules

Inspect `.gitignore` and related ignore configuration. Any ignored control-plane file that can affect governance or verification must be listed explicitly so that a clean-tree gate cannot silently omit it.

### Step 2.6 — Build the historical-artifact disposition candidate list before any cleanup

Perform a repository-wide discovery of historical plans, reports, ADRs, SDD artifacts, evidence narratives, and housekeeping remnants, including the full root set present in the actual implementation workspace. Record every candidate path and its proposed disposition in `/tmp/earthlink-phase0-evidence/phase0_control_plane_inventory.md`.

At minimum include: root historical `EARTHLINK_*PLAN*` files, readiness/audit reports, `PRODUCTION_CONTRACT_RECONCILIATION_FINAL_PLAN.md`, `P1.md`, `p66.md`, `REPORT.md`, `REPORT_TEMP.md`, `docs/**`, `.superpowers/sdd/**`, `evidence/**`, and `app/evidence/**`. Do not assume the exact filenames from earlier reviews are exhaustive; filesystem discovery is authoritative.

No deletion/archive/rewrite is performed in this step. The purpose is to ensure every likely cleanup target is known before the Phase-0 path allowlist is frozen.

### Step 2.7 — Preserve the inventory externally

```bash
ls -l /tmp/earthlink-phase0-evidence/phase0_control_plane_inventory.md
```

Do not commit the temporary control-plane inventory; retain it in the external Phase-0 evidence bundle.

---

# Task 3 — Create the Exact Test-Corpus Baseline

**Create:** `/tmp/earthlink-phase0-evidence/phase0_test_corpus_inventory.md`

### Step 3.1 — Inventory test files

Enumerate:

```bash
find . -type f \
  \( -path '*/src/test/*' \
  -o -path '*/src/androidTest/*' \
  -o -name '*Test.kt' \
  -o -name '*Test.java' \
  -o -name '*Test.py' \
  -o -name '*test*.sh' \
  \) \
  -not -path './.git/*' | sort
```

Also inspect test infrastructure and test-running scripts.

### Step 3.2 — Inventory contract-linked tests

For every test ID referenced by:

```text
contract/phase_requirements.yaml
contract/invariant_contract.yaml
contract/invariant_test_map.yaml
contract/closure_contract.yaml
contract/test_environment_matrix.yaml
```

record:

```text
requirement/test ID
referenced test path
referenced method/class if specified
present/absent
current/stale
blocking/non-blocking
```

### Step 3.3 — Inventory stale test/evidence artifacts

Do not classify a test as historical merely because it is inconvenient, currently failing, or tied to a superseded architecture mechanism. A test/requirement may lose mandatory status only if it is: (a) proven obsolete by a frozen product/architecture decision, or (b) explicitly rebased to a current frozen invariant. Any de-authorized test must record its replacement mapping or explicit obsolete rationale.


Identify test outputs, reports, fixtures, golden files, old PASS files, and manifests that are historical rather than current.

Mark them explicitly rather than allowing them to be consumed as current verification evidence.

### Step 3.4 — Produce a test corpus hash manifest

Generate the manifest in `/tmp` first so it cannot hash itself, then place it in the external Phase-0 evidence bundle:

```bash
find . -type f \
  \( -path '*/src/test/*' -o -path '*/src/androidTest/*' -o -name '*Test.kt' -o -name '*Test.java' -o -name '*Test.py' -o -name '*test*.sh' \) \
  -not -path './.git/*' -print0 \
  | sort -z \
  | xargs -0 sha256sum \
  | sort > /tmp/phase0_test_corpus_manifest.sha256
mv /tmp/phase0_test_corpus_manifest.sha256 /tmp/earthlink-phase0-evidence/phase0_test_corpus_manifest.sha256
```

The inventory and manifest must refer to the same current test corpus. Do not add these temporary evidence files to the permanent repository; retain them in the external Phase-0 evidence bundle.

---

# Task 3.5 — Freeze the Machine-Checkable Phase-0 Change Allowlist Before Cleanup

**Create:** `/tmp/earthlink-phase0-evidence/phase0_allowed_paths.txt`

This is the final pre-authorization checkpoint for repository modifications. It must happen **before** Task 4 and before any substantive governance/archive cleanup.
Tasks 0 and 1 are exempt from this pre-authorization gate: their paths (`.git` reset/init and the five fixed `docs/authority/` files) are fully deterministic and named explicitly in their own task definitions, not discovered through inventory. The allowlist frozen below formally records these already-known paths; it does not retroactively authorize them.

## Step 3.5.1 — Derive the exact allowlist from Tasks 0–3

Use the control-plane inventory, test inventory, authority installation plan, and historical disposition candidate list to enumerate every path that may be modified, created, deleted, or archived during Phase 0.

The final file must contain exact paths, one per line, rather than relying on broad globs for enforcement. It may contain documentation directories only when the individual paths have been discovered and classified. It may include a specific validator/control-plane path only if the inventory records why it may need modification.

Production runtime/source paths are never allowed.

## Step 3.5.2 — Record the rationale for exceptional control-plane paths

For every allowed path under `contract/**`, `scripts/**`, `.github/**`, Gradle/build tooling, or other executable/control surfaces, record: path, reason, expected behavior change, and related finding/task. This becomes the pre-authorization record.

## Step 3.5.3 — Freeze the allowlist

```bash
sha256sum /tmp/earthlink-phase0-evidence/phase0_allowed_paths.txt > /tmp/earthlink-phase0-evidence/phase0_allowed_paths.sha256
```

Do not commit the temporary allowlist/scope files. Record their exact hashes and freeze status in the external Phase-0 evidence bundle before substantive cleanup begins.

From this checkpoint onward, the allowlist is immutable. A newly discovered path that was not pre-authorized does not get added retroactively; the current Phase-0 execution stops for an explicit review/authorization decision.

## Step 3.5.4 — Define the non-retroactivity rule

`AGENTS.md` and the final gate must state that a path may not be added to the allowlist after its modification. Any exceptional control-plane change discovered later requires a new review decision before the edit occurs.

## Step 3.5.5 — Checkpoint

```bash
sha256sum -c /tmp/earthlink-phase0-evidence/phase0_allowed_paths.sha256
git status --short
```

Expected: the authorization boundary hash and scope record are preserved in the external Phase-0 evidence bundle, and no substantive cleanup edit has begun.

---

# Task 4 — Rebase Human Governance and Decision Documentation

**Files:**

- Modify: `AGENTS.md`
- Modify: `AI_DEVELOPMENT_GUIDE.md`
- Modify: `CONTRIBUTING.md`
- Modify: `ARCHITECTURE.md`
- Modify: `DESIGN_DECISIONS.md`
- Modify: `PROJECT_ROADMAP.md`
- Modify: `README.md` if it contains conflicting onboarding/governance claims
- Modify: `CHANGELOG.md` only if needed to record the Phase-0 governance transition

## Step 4.1 — Rewrite the authority hierarchy

All active governance files must point to the five Implementation Reference Bundle artifacts installed under `docs/authority/`, while making clear that the first three are the frozen product/architecture authorities and the last two are subordinate transition guidance.

Remove language that makes any repository roadmap or ADR file the highest authority for implementation.

## Step 4.2 — Reconcile `AGENTS.md`

The required read order must no longer permit a historical file to supersede the frozen authority bundle.

The active agent instructions must explicitly state:

```text
ACTIVE IMPLEMENTATION ENTRY POINT
1. Read AGENTS.md first.
2. Read only the explicitly named current-phase plan for task execution.
3. Use docs/authority/Target Product Contract v0.6.md as product/business authority.
4. Use docs/authority/G1-G8 Consolidated Architecture Summary.md as engineering interpretation.
5. Use docs/authority/Final Independent Adjudication Memo.md as final architectural judgment / implementation boundary.
6. Inspect the current source/artifact for implementation state.
7. Use executable tests/evidence as verification proof.
8. Do not select tasks from Git history, historical plans, ADRs, reports, SDD artifacts, or lessons learned.
9. Use historical material only when the current task explicitly requests forensic archaeology or historical rationale.
```

Also state explicitly:

```text
Only AGENTS.md and the explicitly named current-phase plan are active implementation instructions. Other repository documents are context, evidence, or history unless explicitly designated current by the frozen authority chain.
```

If `DESIGN_DECISIONS.md` remains on the required-reading list, it must explicitly say it is a technical ADR/history layer subordinate to the frozen authority bundle.

## Step 4.3 — Reconcile `AI_DEVELOPMENT_GUIDE.md`

Remove the old “choose the highest-priority roadmap task” model as the primary authority mechanism.

Replace it with a frozen-target implementation workflow:

```text
authority → current artifact → implementation plan → executable evidence
```

## Step 4.4 — Reconcile `CONTRIBUTING.md`

Remove any “trust repository documentation/code first” rule that can override the frozen product/architecture authorities.

State instead:

```text
Frozen product/architecture requirements outrank historical repository guidance.
Current source determines implementation state, not product authority.
Executable evidence determines verification state.
```

## Step 4.5 — Rebase `ARCHITECTURE.md`

Keep useful technical details, but remove its status as a parallel architectural authority.

Specifically mark superseded architectural mechanisms such as:

- terminal `DEAD_LETTER` semantics;
- staging as default architecture;
- global CRUD ownership claims where they exceed the frozen minimum;
- broad coordinator/state-machine language that conflicts with the frozen architecture.

Do not rewrite the production source here.

## Step 4.6 — Reconcile `DESIGN_DECISIONS.md`

Keep this file, but classify each ADR as one of:

```text
CURRENT TECHNICAL DECISION
FROZEN-TARGET COMPATIBLE
SUPERSEDED BY FROZEN AUTHORITY
HISTORICAL RATIONALE
```

At minimum review and reconcile:

- ADR-012 authority/source-of-truth language;
- ADR-013 and ADR-014 roadmap-first workflow;
- ADR-023 global maintenance barrier language;
- ADR-028 `DataOperationCoordinator` exclusivity language;
- ADR-029 exact-snapshot Restore language.

For superseded ADRs, preserve the historical decision/rationale but mark them explicitly `Superseded` and point to the relevant frozen authority.

Do not create `docs/architecture/DECISION_HISTORY.md` merely to satisfy an old plan. Unless a real current repository need is discovered, keep the decision history in `DESIGN_DECISIONS.md`.

## Step 4.7 — Reconcile active roadmap

`PROJECT_ROADMAP.md` must use exactly one implementation sequence, matching the Implementation Handover Appendix:

```text
Phase 0 — Repository / Documentation / Governance Alignment
Phase 1 — G2 / Transport
Phase 2 — G3 / Restore & Import
Phase 3 — G4 / Concurrency & Lineage
Phase 4 — G5 / Identity
Phase 5 — G6/G7 / Semantics + Migration
Phase 6 — G8 / Certification
```

Historical completed work may remain in a separate historical section, but must not create a second active roadmap.

Explicitly track:

```text
G1 Architecture = CLOSED
G1 Implementation = OPEN / tracked in Phase 1 G2/G1 durability lane
G1 Verification = REQUIRED
G1 limitation = ACCEPTED
```

## Step 4.8 — Commit governance rebase

```bash
git add AGENTS.md AI_DEVELOPMENT_GUIDE.md CONTRIBUTING.md \
        ARCHITECTURE.md DESIGN_DECISIONS.md PROJECT_ROADMAP.md README.md CHANGELOG.md

git commit -m "docs: rebase active repository governance to frozen target"
```

---

# Task 5 — Reconcile `PRODUCTION_INVARIANTS.md` and `PRODUCTION_CONTRACT_MATRIX.md`

**Files:**

- Modify: `PRODUCTION_INVARIANTS.md`
- Modify: `PRODUCTION_CONTRACT_MATRIX.md`
- Create/update: `/tmp/earthlink-phase0-evidence/phase0_invariant_reconciliation.md`

## Step 5.1 — Reclassify authority

These files may remain as machine-readable/current implementation contracts, but they are not allowed to redefine product or architecture authority.

Their authority source must resolve upward to:

```text
Target Product Contract
→ G1–G8 Summary
→ Final Adjudication
```

## Step 5.2 — Reconcile INV-11

Inspect the current wording around `DataOperationCoordinator`.

The result must distinguish:

```text
implementation mechanism
```

from:

```text
frozen architectural requirement
```

If the invariant is retained because current implementation correctness genuinely requires it, state that it is an implementation constraint under the frozen architecture, not a new architecture authority.

If an invariant requires a broad global CRUD coordinator merely because that class exists, mark it superseded or redefine the invariant to the actual frozen correctness boundary.

Do not change production code in Phase 0.

## Step 5.3 — Reconcile INV-13

Verify Restore/Import/CLEAR semantics against the frozen requirements:

- full dataset clear/replacement establishes G4 lineage invalidation;
- Restore Merge conflict resolution happens before final Room transaction;
- no network/UI wait inside the final transaction;
- no second staging architecture is implied.

## Step 5.4 — Record every changed invariant

For every changed invariant, record:

```text
old identifier
old meaning
new/current meaning
frozen authority source
reason for change
whether implementation impact is deferred
```

## Step 5.5 — Commit the permanent control-plane changes

```bash
git add PRODUCTION_INVARIANTS.md PRODUCTION_CONTRACT_MATRIX.md
git commit -m "docs: reconcile invariant contracts to frozen architecture"
```

Retain `/tmp/earthlink-phase0-evidence/phase0_invariant_reconciliation.md` outside the permanent repository control plane.

---

# Task 6 — Reconcile Machine Contract Authority Without Rebuilding the Verification System

**Files:**

- `contract/phase_requirements.yaml`
- `contract/test_environment_matrix.yaml`
- `contract/invariant_contract.yaml`
- `contract/invariant_test_map.yaml`
- `contract/closure_contract.yaml`
- `contract/forbidden_patterns.yaml`
- any validator configuration consumed by these files
- Create: `/tmp/earthlink-phase0-evidence/phase0_machine_contract_reconciliation.md`

## Step 6.1 — Inventory every external/reference authority field

Search:

```bash
grep -RniE \
  "governing_plan|authoritative_source|source_of_truth|phase_requirements|closure_contract|invariant_contract" \
  contract scripts .github evidence app/evidence 2>/dev/null
```

Record every field that points to a document, plan, or architecture artifact.

## Step 6.2 — Neutralize orphaned historical plan references

The known historical reference:

```text
EARTHLINK_ROOT_CAUSE_LOOP_BREAKING_PLAN_UPDATED_FINAL.md
```

must not remain an active authority reference merely because validators still parse its field.

For every such legacy reference choose exactly one:

```text
NON-AUTHORITATIVE / NON-BLOCKING / HISTORICAL CORPUS
```

or

```text
FULLY REBASELINED AGAINST FROZEN TARGET
```

Do not leave “legacy but potentially closure-blocking” semantics.

If a machine contract's functional requirement body remains useful, preserve the requirement content but sever its authority from the obsolete plan and document its current frozen-spec mapping.

## Step 6.3 — Prevent obsolete validators from blocking closure

Inspect the validators that consume the contract files. Every retained legacy corpus must be explicitly in exactly one state:

```text
NON-AUTHORITATIVE / NON-BLOCKING / HISTORICAL CORPUS
OR
FULLY REBASED AGAINST THE FROZEN TARGET
```

Add a control-plane check that fails if Phase-0 or later closure can be blocked solely because an obsolete architecture mechanism is present in a corpus marked non-authoritative.

This is a control-plane fix only.

## Step 6.4 — Prohibit suppression-by-reclassification

No Phase-0 reconciliation may reduce mandatory correctness coverage merely by reclassifying a requirement or test as historical. Every de-authorized mandatory check must either:

1. map to a current frozen invariant and be rebased/remapped; or
2. be explicitly proven obsolete by the frozen authority, with the obsolete reason recorded.

There is no third state of “historical because inconvenient.”

## Step 6.5 — Keep functional test corpus intact unless an actual requirement contradiction is proven

Do not delete tests merely because an old manifest references them.

Instead classify:

```text
current and valid
current but needs remapping
historical but valuable
obsolete and removable
missing
```

## Step 6.6 — Context-aware forbidden-pattern semantics

`forbidden_patterns.yaml` must distinguish:

- active forbidden implementation pattern;
- allowed mention inside frozen authority documentation;
- historical evidence mention;
- negative test that intentionally searches for the pattern.

Do not implement raw substring absence as the sole correctness rule.

## Step 6.7 — Self-test the forbidden-pattern validator

Before relying on the updated scanner, run it against temporary adversarial fixtures that are not part of the repository:

```text
1. active guidance containing a forbidden mechanism → MUST FAIL
2. frozen authority mentioning a forbidden mechanism as rejected → MUST PASS
3. historical document marked historical and mentioning it → MUST PASS as historical
4. actual source/control-plane active forbidden pattern → MUST FAIL
5. legitimate use of the string inside a negative test fixture → MUST NOT be misclassified as an implementation violation
```

Record the exact fixture-to-result matrix in `phase0_machine_contract_reconciliation.md`. A validator change without these self-tests is not considered verified.

## Step 6.8 — Commit

```bash
git add contract

git commit -m "chore: reconcile machine contract authority to frozen target"
```

---

# Task 7 — Inventory and Classify All Historical Plans, Reports, SDD, and Evidence Artifacts

**Files / surfaces:**

- root historical plans/reports
- `docs/**`
- `.superpowers/sdd/**`
- `evidence/**`
- `app/evidence/**`

**Create:** `/tmp/earthlink-phase0-evidence/phase0_historical_artifact_disposition.md`

## Step 7.1 — Classify every historical artifact

Every plan/report/SDD artifact must receive one of:

```text
KEEP — CURRENT / ALIGNED
KEEP — HISTORICAL / NON-AUTHORITATIVE
UPDATE / REBASE
SUMMARIZE → PROJECT_LESSONS_LEARNED.md → REMOVE
ARCHIVE OUTSIDE ACTIVE DISCOVERY
REMOVE
```

No file may remain unclassified.

## Step 7.2 — Review historical plans for useful rationale

For old implementation plans such as:

```text
EARTHLINK_EXIT_LOOP_EVIDENCE_LOCKED_CLOSURE_PLAN(fix-after-10).md
EARTHLINK_FINAL_REMEDIATION_AND_TASK19_RE_CLOSURE_PLAN.md
EARTHLINK_HOTFIX_REQUIREMENT_CLOSURE_AND_PHASE2_RECOVERY_PLAN.md
EARTHLINK_ROOT_CAUSE_LOOP_BREAKING_PLAN_UPDATED_FINAL.md
ROOT-CAUSE STABILIZATION GATE #U2014 FINAL EXECUTION.md
PRODUCTION_CONTRACT_RECONCILIATION_FINAL_PLAN.md
P1.md
p66.md
REPORT.md
REPORT_TEMP.md
FORENSIC_AUDIT_REPORT.md
INDEPENDENT_PRODUCTION_READINESS_AUDIT.md
BASELINE_AND_CHANGESET_AUDIT.md
```

record:

- historical purpose;
- useful lesson/rationale;
- frozen-authority conflicts;
- whether it can misdirect a future agent;
- final disposition.

If useful knowledge is not already captured, summarize it into `PROJECT_LESSONS_LEARNED.md` before removal.

## Step 7.3 — Review `.superpowers/sdd/**`

Treat SDD artifacts as historical execution records unless explicitly reclassified as current.

They must not become an alternate active roadmap or architecture authority merely because they contain task briefs or progress reports.

## Step 7.4 — Review `docs/` housekeeping contradictions

Cross-check `HOUSEKEEPING_REPORT.md` against actual filesystem state.

Any report that says a file was deleted while the file remains present must be updated during Phase 0.

Known classes include obsolete outbox state-machine documentation and merged audit/hardening documents.

## Step 7.5 — Preserve useful evidence but remove current-authority ambiguity

Historical evidence can remain if:

- it is clearly labeled historical;
- its source/artifact identity is retained where known;
- it cannot be mistaken for current verification;
- it does not participate as a blocking active contract unless rebaselined.

## Step 7.6 — Commit disposition

```bash
git add PROJECT_LESSONS_LEARNED.md
git add $(grep -E '^(docs/|evidence/|app/evidence/|\.superpowers/sdd/)' /tmp/earthlink-phase0-evidence/phase0_allowed_paths.txt)
git commit -m "docs: retire and classify historical implementation guidance"
```

---

# Task 8 — Establish a Single Canonical Implementation Roadmap and G1 Ownership

**Files:**

- Modify: `PROJECT_ROADMAP.md`
- Modify: `EARTHLINK_V1_HANDOVER.md` only if an active repository-local copy exists and conflicts
- Modify: `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md` only if repository-local transition guidance conflicts; do not alter the externally supplied authority copies in `docs/authority/`
- Create: `/tmp/earthlink-phase0-evidence/phase0_sequence_reconciliation.md`

## Step 8.1 — Canonical sequence

Make the Appendix sequence the only active implementation sequence. `PROJECT_ROADMAP.md` is an operational index derived from that sequence, not a second authority. Any change to the active phase order must first be made against the Appendix-controlled sequence and then reflected in the roadmap only as a derived index.


```text
Phase 0 — Repository / Documentation / Governance Alignment
Phase 1 — G2 / Transport
Phase 2 — G3 / Restore & Import
Phase 3 — G4 / Concurrency & Lineage
Phase 4 — G5 / Identity
Phase 5 — G6/G7 / Semantics + Migration
Phase 6 — G8 / Certification
```

## Step 8.2 — Machine-check roadmap/Appendix sequence equality

Extract the active phase sequence from `PROJECT_ROADMAP.md` and compare it to the Appendix sequence recorded in `/tmp/earthlink-phase0-evidence/phase0_sequence_reconciliation.md`. Any mismatch is a Phase-0 failure. Historical roadmap entries may differ only when clearly outside the active sequence.

## Step 8.3 — Reconcile dependencies

Record the known cross-gate relationships:

```text
G5 identity → G2 cloud idempotency
G5 identity → G3 Merge
G3 Restore/Import publication → G4 lineage invalidation
G4 stale-result protection → G3 closure
G5 identity preservation → G7 migration
G2/G3/G4 runtime evidence → G8 verification
```

Do not create a new phase solely to house a frozen invariant unless the Appendix sequence is genuinely insufficient.

## Step 8.4 — Explicitly track G1

Add a roadmap entry/table with:

```text
G1 Architecture = CLOSED
G1 Implementation = OPEN
Implementation owner = Phase 1 G2/G1 durability lane
G1 Verification = REQUIRED
G1 Limitation = ACCEPTED bounded recovery limitation
```

This is tracking only in Phase 0. No G1 runtime changes are performed.

## Step 8.5 — Map financial-history protection to the correct later phase

Do not create a competing “P0 financial-history phase.”

Record the frozen production deletion invariant as a required later implementation/verification item in the G6/G7 lane, because the adjudication ties it to profile/identity semantics and migration safety.

## Step 8.6 — Validate uniqueness of the active sequence

Search for competing sequences:

```bash
grep -RniE \
  "Phase 1|Phase 2|implementation sequence|implementation order|next phase|highest-priority task" \
  AGENTS.md AI_DEVELOPMENT_GUIDE.md CONTRIBUTING.md ARCHITECTURE.md \
  DESIGN_DECISIONS.md PROJECT_ROADMAP.md docs .superpowers contract \
  --exclude-dir=.git
```

Every non-historical conflicting sequence must be reconciled or marked explicitly historical.

---

# Task 9 — Reconcile Repository Housekeeping and Active Discovery Paths

**Files:**

- Modify: `HOUSEKEEPING_REPORT.md`
- Modify/delete/archive files according to the disposition inventory
- Update active documentation indexes/links as required

## Step 9.1 — Make housekeeping factual

The report must describe actual filesystem state after Phase-0 cleanup, not what a prior cleanup was intended to do.

## Step 9.2 — Ensure active discovery paths are clean

A historical file may be retained physically, but it must not sit in an active path where `AGENTS.md`, scripts, validators, or onboarding instructions tell an agent to consult it as current guidance.

Preferred strategy:

```text
Current guidance → active root/docs paths
Historical evidence → explicit archive/history path
Superseded ADRs → marked within ADR file
Removed plans → lessons captured before deletion
```

## Step 9.3 — Re-run filesystem discovery

Confirm no orphaned references point to removed files.

```bash
grep -RniE \
  "EARTHLINK_ROOT_CAUSE_LOOP_BREAKING_PLAN_UPDATED_FINAL|PRODUCTION_CONTRACT_RECONCILIATION_FINAL_PLAN|OUTBOX_STATE_MACHINE|FINAL_HARDENING_BASELINE" \
  . --exclude-dir=.git
```

Remaining matches must either be:

- explicit historical references; or
- frozen/test documentation explaining why the artifact is retired.

---

# Task 10 — Enforce the Frozen Phase-0 Change Allowlist

The final allowlist was created and frozen in Task 3.5. This task must **not** create, expand, or rewrite it.

## Step 10.1 — Verify the frozen authorization file

```bash
sha256sum -c /tmp/earthlink-phase0-evidence/phase0_allowed_paths.sha256
```

A mismatch is a Phase-0 failure.

## Step 10.2 — Compare changed files against the frozen allowlist

At every checkpoint after cleanup:

```bash
git diff --name-only <phase0-baseline-commit>..HEAD | sort
git status --short
```

Any path shown by either git diff --name-only or git status --short (including untracked files) that falls outside the frozen allowlist is an immediate Phase-0 failure. Do not continue the edit and do not add the path retroactively.

## Step 10.3 — Compare source manifest changes

Use the source manifest from Task 0 to detect content changes in files that were not expected to change.

A renamed/copied runtime implementation file must not bypass the allowlist.

No path may be added to the allowlist after the corresponding modification. If a necessary path is missing, stop and perform an explicit pre-edit review; do not retroactively authorize the completed change.

---

# Task 11 — Define the Baseline Database / Recovery Artifact Precisely

**Create/update:** `/tmp/earthlink-phase0-evidence/phase0_database_baseline.md`

## Step 11.1 — Inspect the implementation workspace for database artifacts

Search the already-extracted repository workspace for actual SQLite/database artifacts:

```bash
find . -type f \
  \( -name '*.db' -o -name '*.sqlite' -o -name '*.sqlite3' \) \
  -not -path './.git/*' | sort
```

Also inspect evidence describing extracted databases.

## Step 11.2 — Classify what exists

For each database artifact record:

```text
path
size
SHA-256
schema/version if known
source (repository artifact / fixture / generated)
purpose
safe to use as baseline? yes/no
```

## Step 11.3 — If no valid forensic/repository DB exists

Record:

```text
N/A — no valid baseline database artifact exists in the supplied forensic repository.
```

Do not invent a synthetic replacement merely to satisfy a checklist.

A synthetic fixture may be used later by tests, but must be explicitly labeled synthetic and not called the forensic database baseline.

---

# Task 12 — Final Context-Aware Governance and Authority Verification

**Create:** `/tmp/earthlink-phase0-evidence/phase0_final_gate_report.md`

## Step 12.1 — Verify active guidance

Run targeted searches over active governance/control-plane files only.

The gate must fail when rejected architecture mechanisms are presented as current requirements.

Examples:

```text
DEAD_LETTER as accepted target state → FAIL
staging as default V1 architecture → FAIL
dataset_id as required architecture → FAIL
generic reconciliation as required architecture → FAIL
identity registry as required architecture → FAIL
global CRUD coordinator as frozen architecture requirement → FAIL
roadmap-first authority overriding frozen docs → FAIL
```

## Step 12.2 — Verify frozen authority integrity

The same terms may legitimately appear in the five Implementation Reference Bundle files because they describe rejected concepts and boundaries.

Therefore the check must be context-aware and must not require literal absence from frozen authority documents.

Verify `docs/authority/authority_manifest.sha256` and confirm all five bundle files match it exactly. Any authority-file content change is a Phase-0 failure unless an explicit authority-change review has been invoked.

## Step 12.3 — Verify historical labeling

Search the historical corpus for documents that still speak as if they are current.

Every retained historical artifact must have an explicit status/disposition path.

## Step 12.4 — Verify machine-contract authority

Confirm:

```text
No obsolete plan is a blocking authority.
No historical requirement corpus can silently define a current architecture.
All current closure rules map to the frozen target or are explicitly implementation-level constraints.
```

## Step 12.5 — Verify canonical phase sequence

There must be exactly one active sequence, matching the Appendix:

```text
Phase 0
→ G2
→ G3
→ G4
→ G5
→ G6/G7
→ G8
```

Historical documents may contain old sequences only when explicitly classified as historical.

## Step 12.6 — Verify G1 state tracking

Ensure there is one current declaration:

```text
G1 architecture closed
G1 implementation open/tracked
G1 verification required
G1 limitation accepted
```

## Step 12.7 — Verify authority hashes

Run all five SHA-256 checks again and compare to the recorded expected values.

## Step 12.8 — Verify source/test manifests

Confirm:

- source manifest exists and is internally consistent;
- test corpus inventory exists;
- test manifest exists;
- no unexpected test additions/removals are hidden by ignore rules;
- historical evidence is not being counted as current tests.

## Step 12.9 — Verify Phase-0 change allowlist and source completeness

First verify the frozen allowlist hash:

```bash
sha256sum -c /tmp/earthlink-phase0-evidence/phase0_allowed_paths.sha256
```

Then run:

```bash
git diff --name-only <phase0-baseline-commit>..HEAD | sort
git status --short
```

Every changed path must be present in the pre-authorized allowlist frozen in Task 3.5. Do not add paths to the allowlist after the fact. Any production source/runtime change is an immediate Phase-0 failure.

Also compare the pre-cleanup source manifest to the final workspace and confirm that every changed path is explicitly explained by the allowlist. Any file that disappeared from the source tree without an allowlisted removal is a failure. Any file added outside the allowlist is a failure.

## Step 12.10 — Verify active agent entry point

Confirm `AGENTS.md` is the only mandatory repository entry point and explicitly routes the agent to the current Phase-0 plan and frozen authority bundle. Confirm no historical plan, ADR, report, SDD artifact, lessons file, or Git history is included in the default task-selection path.

## Step 12.11 — Build control-plane verification if applicable

Run the repository's existing documentation/contract/forbidden-pattern validators after their authority semantics have been reconciled.

If a validator fails because it still assumes an obsolete authority, fix the validator only under the Phase-0 control-plane exception and record the exact reason.

Do not relax a validator simply to make Phase 0 pass.

## Step 12.12 — Verify validator self-tests

Re-run the adversarial fixture matrix from Task 6.7 and record the results. A scanner that passes only its real repository scan but fails its known-bad fixture is not trusted.

## Step 12.13 — Verify Git history non-authority

Confirm the repository contains the `FORENSIC_BASELINE_NON_AUTHORITATIVE` tag and that `AGENTS.md` explicitly forbids using Git history for implementation/architecture/roadmap/task selection. This is a governance assertion, not a request to purge the new repository's traceability history.

---

# Task 13 — Produce the Final Evidence-Bounded Phase-0 Completion Record

**Create/update:** `/tmp/earthlink-phase0-evidence/phase0_final_gate_report.md`

The final report is an external execution artifact at `/tmp/earthlink-phase0-evidence/phase0_final_gate_report.md`. It must not become permanent repository governance unless a later governance decision explicitly establishes it as required. It must contain:

## Artifact identity

- forensic ZIP filename + SHA-256;
- five governing artifact hashes;
- new repository baseline commit;
- final Phase-0 HEAD commit;
- source manifest hash;
- test corpus manifest hash.

## Control-plane result

```text
Human governance aligned: PASS/FAIL
ADR reconciliation: PASS/FAIL
Machine-contract authority: PASS/FAIL
Historical artifact disposition: PASS/FAIL
Canonical implementation sequence: PASS/FAIL
G1 ownership tracking: PASS/FAIL
Executable control surfaces: PASS/FAIL
Housekeeping accuracy: PASS/FAIL
Phase-0 allowlist: PASS/FAIL
```

## Remaining implementation gaps

Explicitly list, without fixing them in Phase 0:

- G1 implementation/verification lane;
- G2 transport correctness;
- G3 Restore Merge/import verification;
- G4 generation/stale-result protection;
- G5 historical identity;
- G6/G7 semantics/migration/deletion protection;
- G8 external certification.

## Final statement

Use exactly this meaning:

> **Repository control plane is aligned and the exact source/test baseline is recorded. Production implementation and verification remain outstanding.**

Do not write `production ready`, `implementation complete`, or `verification complete`.

---

# Phase-0 Final Verification Matrix

| Gate | Required evidence | Failure condition |
|---|---|---|
| Forensic artifact | ZIP hash + file inventory | artifact mismatch |
| Git reset | fresh valid Git + `FORENSIC_BASELINE_NON_AUTHORITATIVE` tag | corrupted old Git remains authoritative or historical commits are treated as current |
| Authority bundle | five exact hashes | missing/mismatched authority artifact |
| Source identity | full file-level manifest + ignored-file completeness proof | source tree not reproducible or omitted by ignore rules |
| Test identity | current test inventory + hash manifest | test corpus not bound |
| Governance | classified active documents | historical guidance still active |
| ADRs | explicit current/superseded status | ADR competes with frozen architecture |
| Invariants | reconciled authority mapping | invariant redefines frozen architecture |
| Machine contracts | explicit non-authoritative or fully rebased state + no suppression-by-reclassification | obsolete contract can block closure or tests are silenced merely by relabeling |
| Control surfaces | scripts/CI/Gradle/config reviewed | executable old authority remains |
| Roadmap | one active sequence | multiple legitimate implementation orders |
| G1 | explicit implementation/verification ownership | G1 disappears because architecture is closed |
| History | disposition inventory | old plans/reports remain unclassified |
| Housekeeping | report matches filesystem | false deletion claims remain |
| Allowlist | changed paths all Phase-0 approved | runtime/source change detected |
| Database baseline | explicit DB status | arbitrary fixture called forensic baseline |
| Active forbidden-pattern scan | context-aware + adversarial self-tests | false pass/fail from raw grep or modified scanner without proof |
| Final evidence | hashes/manifests/commits/gates | narrative-only PASS |

---

# Non-Regression Rules

1. Do not reopen Direct Atomic Room.
2. Do not add staging, dataset identity, identity registry, generic reconciliation, generic synchronization state machine, or runtime governance registry.
3. Do not turn `DataOperationCoordinator` into a new frozen architecture authority. Its existence or removal is a later implementation question governed by the actual invariants.
4. Do not treat `DEAD_LETTER` as an accepted business state.
5. Do not delete valid current cloud obligations merely to make historical transport metadata disappear.
6. Do not regenerate reliable historical transaction IDs.
7. Do not physically delete subscriber financial history because the ISP deleted a remote profile.
8. Do not use `remoteVersion`/`updatedAt` as G4 generation.
9. Do not make normal ledger mutations automatically increment G4 generation without executable concurrency evidence.
10. Do not put UI/network waiting inside final Restore/Import Room business transactions.
11. Do not treat old PASS reports as current verification.
12. Do not claim Phase-0 completion means implementation completion.
13. Do not treat Git history as current authority.
14. Do not modify frozen authority files during normal implementation.
15. Do not suppress a mandatory test/requirement by relabeling it historical unless its obsolescence is explicitly proven by the frozen authority.
16. Do not change or extend the Phase-0 allowlist retroactively.

---

# Implementation Notes for the Next Session

After Phase 0, implementation proceeds in the Appendix order:

```text
Phase 1 — G2 / Transport
    + explicit G1 durability implementation/verification lane

Phase 2 — G3 / Restore & Import

Phase 3 — G4 / Concurrency & Lineage

Phase 4 — G5 / Identity

Phase 5 — G6/G7 / Semantics + Migration

Phase 6 — G8 / Certification
```

The exact source artifact after Phase 0 cleanup becomes the implementation artifact under verification. The old ZIP 71 findings remain historical baseline evidence and must not be automatically re-applied to the cleaned source without current-source confirmation.

The architecture reopening rule remains unchanged: only direct evidence of a frozen-product contradiction or an invariant that cannot be satisfied within the frozen architecture justifies reopening architecture.

---

# Final Hidden-Regression Checks

Before execution approval, explicitly verify the six hidden vectors identified by the final zero-trust review:

```text
[ ] Git history is explicitly forensic/non-authoritative and the baseline tag says so.
[ ] Authority files have an immutable hash manifest and normal implementation may not edit them.
[ ] The implementation workspace is the execution source; ZIP presence is not required.
[ ] Every source-manifest file is tracked or explicitly approved as non-source/generated; ignored source does not silently disappear.
[ ] No requirement/test loses mandatory status merely by being labeled historical; every de-authorized check is mapped to a frozen invariant or explicitly proven obsolete.
[ ] Forbidden-pattern validators have adversarial self-tests that prove both rejection and allowed-context behavior.
[ ] AGENTS.md is the sole mandatory repository entry point, followed by the explicitly named current-phase plan.
[ ] The Phase-0 allowlist is frozen before substantive cleanup and cannot be expanded retroactively.
[ ] PROJECT_ROADMAP.md is a derived operational index and its active sequence matches the Appendix.
```

---

# Review Sign-Off Criteria for This Plan

Before this plan is considered final for execution, confirm:

```text
[ ] All F-01 through F-12 findings from the supplied Zero-Trust review are addressed.
[ ] The Appendix implementation sequence is the only active sequence.
[ ] G1 implementation/verification ownership is explicit.
[ ] Source-tree and test-corpus manifests are first-class Phase-0 outputs.
[ ] Machine contracts cannot silently retain obsolete authority.
[ ] Active forbidden-pattern checks are context-aware.
[ ] Executable control surfaces are inventoried.
[ ] DESIGN_DECISIONS.md is retained only with explicit subordinate/superseded semantics.
[ ] No obsolete plan is treated as current authority.
[ ] Housekeeping reports match actual filesystem state.
[ ] Phase-0 changes are machine-bounded by an allowlist frozen before substantive cleanup edits.
[ ] Database baseline handling is evidence-backed and does not invent artifacts.
[ ] Final wording does not overclaim implementation/verification readiness.
[ ] Git history is explicitly forensic/non-authoritative.
[ ] Frozen authority files are hash-locked and immutable during normal implementation.
[ ] Source completeness and ignored-file handling are machine-checked.
[ ] No requirement/test is demoted solely to silence a gate.
[ ] Forbidden-pattern validator has adversarial self-tests.
[ ] AGENTS.md is the sole mandatory agent entry point.
[ ] Phase-0 allowlist was frozen before substantive edits and was not retroactively expanded.
[ ] Phase 0 remains a control-plane cleanup, not an architecture or runtime implementation phase.
```
