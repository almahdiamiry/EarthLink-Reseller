# Earthlink Reseller V1 — G8 Certification Implementation Plan

> **For agentic workers:** This is a Phase-6 / G8 certification plan. Execute it as bounded packets only. Do not modify production/runtime architecture to satisfy certification. Certification is external evidence infrastructure and must not become a runtime governance, synchronization, registry, or business-state mechanism.

**Goal:** Implement and independently verify the external G8 machine-verifiable certification boundary so that one certification run produces one evidence bundle bound to the immutable post-Phase-5 product artifact, the separate G8 certification artifact and certification corpus, actual execution, instrumentation, exact release artifact, safe certification environment, and machine-derived final states.

**Architecture:** G8 remains outside the production application/runtime. The verifier consumes the exact final source tree/build/test artifacts and derives certification states from executable evidence; it never becomes a production dependency. The implementation must preserve the frozen distinction between architecture, implementation, verification, and production authorization and must not add rejected mechanisms such as a runtime governance registry or generic reconciliation/synchronization state machine.

**Tech Stack:** Python 3 verifier scripts, existing `run_verified_command.py`, YAML/JSON machine contracts, Gradle/Android test tooling, Android instrumentation, release build/signing, SHA-256 artifact hashing, existing closure/evidence infrastructure.

## G8 Activation Gate — MANDATORY

This document may be authored, reviewed, and stored before Phase 1–5 execution. **It is LOCKED and must not be executed until the post-Phase-5 activation preconditions below are all satisfied.

```text
G8 execution state = LOCKED

until:
  G1 final PASS
  G2 final PASS
  G3 final PASS
  G4 final PASS
  G5 final PASS
  G6 final PASS
  G7 final PASS
  final post-Phase-5 artifact closure sweep PASS
  integrated Phase-1–5 adversarial review PASS
  final post-Phase-5 artifact identity exists
```

Before those conditions hold, an agent may review this document only. It must not execute G8-00 through G8-12, modify certification suites/contracts for G8, or claim any G8 certification state. After activation, G8-01 is the mandatory first executable action. Only after G8-01 PASS may G8-00 through G8-10 execute.

### Activation evidence required

The activation record must contain exact evidence references for:
- final G1–G7 closure;
- final Phase-1–5 artifact identity;
- final-artifact evidence invalidation/re-run results;
- integrated adversarial review;
- the final `PRODUCT_TEST_CORPUS_ID` identity from Phase 1–5; any `CERTIFICATION_TEST_CORPUS_ID` field in the activation record is optional pre-activation context only and MUST NOT be required before G8-00 creates the certification corpus;
- successful pre-activation consistency of the frozen INV-16 certification-suite identity across authority text, canonical contract, test map, and bootstrap minimum; disk existence and certification-test-corpus creation are post-activation G8-00 responsibilities, not activation preconditions;
- the external `G8_BOOTSTRAP_*` activation record and its independent approval/identity, including plan and external-bootstrap-verifier identity;
- the externally trusted frozen-authority snapshot identity/hash.

If any activation precondition is missing, stale, report-only, bound to an earlier artifact, or inconsistent with the bootstrap record, G8 remains LOCKED. G8 MUST NOT repair upstream INV-16 drift as part of activation.


## Global Constraints

- Certification remains **external to the production application/runtime**.
- The verifier must derive results from executable evidence; narrative reports, agent-declared PASS values, historical manifests, and historical reports are never final proof.
- G8 must satisfy `P6-G8-REQ-01` through `P6-G8-REQ-04` in `contract/phase_requirements.yaml`.
- `INV-15` release signing is fail-closed: missing/invalid production signing credentials must fail the release build; debug signing, placeholder keys, and unsigned release artifacts are prohibited.
- `INV-16` certification evidence is immutable/frozen; required certification tests must not be weakened or rewritten to fit buggy runtime behavior.
- `INV-17` closure is machine-verified and fail-closed through bounded execution and the existing meta-gate controls.
- Final certification must execute against the exact post-Phase-5 implementation artifact, not ZIP 71, ZIP 75, historical evidence, or an intermediate phase artifact.
- `PRODUCT_TEST_CORPUS_ID` must be derived from the actual current Phase-1–5 product test source tree and its upstream matrix; `CERTIFICATION_TEST_CORPUS_ID` must be derived from that product corpus plus the G8 certification matrix/suites. Historical test manifests are provenance only.
- G8 must not create or rely on `dataset_id`, `published_dataset_id`, staging databases, identity registries, generic reconciliation engines, generic synchronization state machines, or runtime governance registries.
- All project test/build/certification verification commands MUST execute through the fail-closed bounded runner; a successful exit code alone is insufficient if the runner detects `NO-SOURCE`, timeout, blocked instrumentation, or equivalent invalid evidence. Trusted bootstrap authentication, filesystem-trust primitives, Git identity checks, cryptographic hashing, signing-certificate inspection, and equivalent external trust primitives MUST use independently trusted fixed harnesses and MUST NOT depend on G8-created tooling.
- G8 must not authorize production by itself. `PRODUCTION_READY` is a machine-derived evidence state; the final production authorization decision remains the later independent audit/authorization boundary.
- A certification run is uniquely identified by a fresh `certification_run_id`. A failed, partial, or superseded run must never be overwritten by a later run, even when the final source SHA is identical.
- The certification infrastructure itself has a separate identity from the application artifact. The final run identity must bind: product `PRODUCT_ARTIFACT_ID`, certification-infrastructure manifest, current test-corpus manifest, governing-contract hashes, effective execution environment, and the exact release artifact.
- The final production-signing certificate fingerprint is a trusted external input to G8. It must be explicitly supplied by the authorized certification/release process and recorded without private key material. G8 must never infer the expected production fingerprint from the artifact being certified.
- `PRODUCTION_READY` requires execution evidence from the exact signed release artifact itself, not only from debug/unit/instrumentation artifacts built before signing.
- `PRODUCTION_READY` also requires an explicit **Release Certification Environment Contract** proving that the exact signed artifact was executed only against an approved certification-safe/non-production environment, approved device/emulator, approved fixture identity, credential fixture, and network mode. A production endpoint or real subscriber/financial mutation must never be reached implicitly by certification. The installed artifact must be deployed from the sealed release file after a final pre-install hash check, and the installed package/artifact identity must be re-verified after installation.
- The trusted expected production signing certificate fingerprint must come from an external authorized release/certification source (for example a CI-protected secret/environment value with independent release-owner approval), never from the artifact under test; the fingerprint itself may be recorded in evidence, but private signing material must never be recorded.
- `certification_run_id` must be generated with a collision-resistant method such as UUID4 and must have creation metadata (`created_at`, tool identity/version, parent candidate identity); a run directory is write-once and cannot be reused or overwritten.

---

# G8 Identity Domains — Mandatory

G8 must not collapse product implementation identity, certification infrastructure identity, and test-corpus identity into one hash. The following domains are distinct and must be machine-readable in the G8 evidence model:

```text
PRODUCT_ARTIFACT_ID
  = deterministic identity of the final post-Phase-5 application/runtime implementation
    manifest used to evaluate G1–G7 closure. It does not by itself claim that the
    release binary is identical across build environments.

PRODUCT_BUILD_INPUT_MANIFEST_ID
  = deterministic identity of the complete effective non-secret build-input set
    capable of materially changing the release artifact.

PRODUCT_RELEASE_INPUT_ID
  = deterministic identity of the release-build input closure:
      PRODUCT_ARTIFACT_ID
      + PRODUCT_BUILD_INPUT_MANIFEST_ID
      + governing product-contract/configuration identities required by the release build.
    The exact release APK/AAB MUST be bound to this composite identity.

PRODUCT_TEST_CORPUS_ID
  = current Phase-1–5 product verification corpus identity, excluding
    G8-only certification suites/fixtures/scripts.

CERTIFICATION_ARTIFACT_ID
  = final G8 certification infrastructure identity, including G8-only
    scripts, verifier/control-plane changes, and certification suites.

CERTIFICATION_TEST_CORPUS_ID
  = Product test corpus + G8 certification suites/fixtures required by G8.

**Execution-domain separation rule:** Corpus identity separation is insufficient unless execution selectors are also disjoint. `g8_production_gate.*` MUST execute only explicit selectors belonging to `PRODUCT_TEST_CORPUS`; broad Gradle tasks that implicitly execute G8-only certification suites are prohibited unless machine-verifiable exclusion rules prove the suites cannot run. The G8 certification matrix MUST execute certification-only suites explicitly. A test file MUST NOT be counted as product evidence merely because a broad Gradle task happened to discover it.

CERTIFICATION_RUN_ID
  = one unique sealed execution of G8 over the above identities plus
    effective environment and exact release artifact identity.

UPSTREAM_CLOSURE_SNAPSHOT_ID
  = immutable snapshot of the final G1–G7 closure evidence, contract
    hashes, product test-corpus identity, and product artifact identity
    accepted at G8 activation.

FROZEN_AUTHORITY_SNAPSHOT_SHA256
  = canonical identity of exactly the three Frozen Authority files.

TRANSITION_GUIDANCE_SNAPSHOT_SHA256
  = separate subordinate identity for the two transition/implementation guidance files; it is provenance only and never overrules Frozen Authority.
```

Rules:

- G8-only tests or certification tooling MUST NOT retroactively invalidate G1–G7 evidence merely because `CERTIFICATION_TEST_CORPUS_ID` differs from `PRODUCT_TEST_CORPUS_ID`.
- G8 changes to subordinate certification-control surfaces MUST NOT be misrepresented as changes to the product artifact. The release APK/AAB build inputs must resolve from the sealed `PRODUCT_RELEASE_INPUT_ID`, which is anchored to the immutable `PRODUCT_ARTIFACT_ID`; G8-only tests/scripts/contracts are certification overlay inputs only.
- If a G8 task changes a product/runtime source, a Phase-1–5 governing contract, or any input included in `PRODUCT_ARTIFACT_ID`, the G8 run MUST stop and require a new post-Phase-5 closure cycle before certification continues.
- Upstream G1–G7 evidence remains valid only through `UPSTREAM_CLOSURE_SNAPSHOT_ID`; G8 must never overwrite or silently reinterpret that snapshot after certification activation.
- The final certification bundle must bind `PRODUCT_ARTIFACT_ID`, `PRODUCT_BUILD_INPUT_MANIFEST_ID`, `PRODUCT_RELEASE_INPUT_ID`, `PRODUCT_TEST_CORPUS_ID`, `CERTIFICATION_ARTIFACT_ID`, `CERTIFICATION_TEST_CORPUS_ID`, `UPSTREAM_CLOSURE_SNAPSHOT_ID`, and `CERTIFICATION_RUN_ID`. `PRODUCT_RELEASE_INPUT_ID` is the SHA-256 of a deterministic canonical serialization of `PRODUCT_ARTIFACT_ID` + `PRODUCT_BUILD_INPUT_MANIFEST_ID` + governing release-build identities; all composite identities MUST use deterministic canonical serialization.
- Use two logically separate workspaces after G8 activation:
  1. **Product Release Workspace** = clean checkout/worktree of `PRODUCT_ARTIFACT_ID`; used to build the exact production APK/AAB.
  2. **Certification Overlay Workspace** = product artifact plus G8 certification-only tests/scripts/contracts; used to run certification-specific tests and verifier tooling.
  Certification-only files must never be allowed to alter the release build inputs.


**Identity naming rule:** The canonical identities are `PRODUCT_ARTIFACT_ID`, `PRODUCT_BUILD_INPUT_MANIFEST_ID`, `PRODUCT_RELEASE_INPUT_ID`, and `CERTIFICATION_ARTIFACT_ID`. The plan does not use a generic `F` identity as an authority token; every evidence, test, or release reference MUST name the relevant domain explicitly.

**Release-format rule:** This current product is certified against the release format actually supported by the final Phase-5 build configuration. The current repository is APK-oriented; the AAB branch is conditional and MUST NOT be considered a required certification path unless the final `PRODUCT_BUILD_INPUT_MANIFEST_ID` and release configuration prove that AAB is an actual supported release format.

# G8 Domain Ownership Rule — Mandatory

No path may belong to both `PRODUCT_ARTIFACT_ID` and `CERTIFICATION_ARTIFACT_ID` unless it is explicitly classified as `SHARED_CONTROL_INPUT` in `contract/g8_certification_scope.yaml`. For every shared control input, the scope manifest must define:

```text
owner = PRODUCT | CERTIFICATION | SHARED_CONTROL_INPUT
change_policy = IMMUTABLE | CERTIFICATION_ONLY | REVALIDATE_UPSTREAM
evidence_impact = NONE | G8_ONLY | G1_G7_AND_G8
```

**Product/test identity separation invariant:** `PRODUCT_ARTIFACT_ID` excludes product verification-test inputs. `PRODUCT_TEST_CORPUS_ID` owns product verification tests/fixtures. `CERTIFICATION_TEST_CORPUS_ID` owns G8-only tests/fixtures. A path classified CERTIFICATION or PRODUCT_TEST must not enter `PRODUCT_ARTIFACT_ID` merely because it matches a wildcard discovery root.

**Filesystem trust rule:** Every manifest builder and scope resolver MUST canonicalize paths before hashing/classification; reject symlinks/junctions or equivalent links that resolve outside the declared workspace root; reject duplicate canonical paths; reject case-colliding paths that could resolve differently across filesystems; and reject any path whose resolved target is outside the authorized root/scope. Path classification MUST be deterministic and MUST never depend on platform-specific case or link resolution behavior.

`contract/g8_certification_scope.yaml` is the machine-readable **derived** authority for this domain classification after the external bootstrap root of trust has been verified. A shared verification tool or control-plane file that was used to establish G1–G7 evidence cannot be changed by G8 without either a pinned certification copy or explicit upstream revalidation.

## G8 Bootstrap Root of Trust — Mandatory

Before G8 implementation begins, an external activation record MUST supply and cryptographically bind the following immutable bootstrap inputs:

```text
G8_BOOTSTRAP_SCOPE_ID
G8_BOOTSTRAP_SCOPE_SHA256
G8_BOOTSTRAP_REQUIRED_SUITES_SHA256
G8_BOOTSTRAP_REQUIRED_TASKS_SHA256
G8_BOOTSTRAP_AUTHORIZED_PATHS_SHA256
G8_BOOTSTRAP_PLAN_ID
G8_BOOTSTRAP_PLAN_SHA256
G8_BOOTSTRAP_VERIFIER_ID
G8_BOOTSTRAP_VERIFIER_SHA256
G8_BOOTSTRAP_SCHEMA_VERSION
G8_BOOTSTRAP_MIN_SUPPORTED_VERSION
G8_BOOTSTRAP_ACTIVATION_SEQUENCE
G8_BOOTSTRAP_EXPECTED_PRODUCTION_CERT_FINGERPRINT
FROZEN_AUTHORITY_SNAPSHOT_SHA256
TRANSITION_GUIDANCE_SNAPSHOT_SHA256
G8_BOOTSTRAP_AUTHENTICITY_ID
```

The bootstrap record is an activation input, not a product/runtime authority and not a file created by G8 itself. Its authenticity MUST be verified against an external authorized activation identity or CI-protected signing/trust mechanism, and the record MUST bind the expected frozen-authority snapshot. It authorizes creation of `g8_certification_scope.yaml`, `g8_certification_contract.yaml`, and `g8_certification_test_matrix.yaml`, and it contains the immutable minimum certification scope. The bootstrap must also bind the exact approved G8 plan identity (`G8_BOOTSTRAP_PLAN_ID` + `G8_BOOTSTRAP_PLAN_SHA256`), the approved **external bootstrap verifier** identity (`G8_BOOTSTRAP_VERIFIER_ID` + `G8_BOOTSTRAP_VERIFIER_SHA256`), a bootstrap schema version/minimum supported version, and a monotonic activation sequence to prevent plan/verifier rollback. G8-01 must verify the bytes of the approved plan and the externally supplied bootstrap verifier from the trusted activation inputs before executing any mutable G8 work. `g8_verify_certification_bundle.py` and other G8 verifier scripts are created later and MUST NOT be treated as the bootstrap verifier or assumed to match its hash. The external bootstrap verifier is the root used to authenticate the plan/bootstrap inputs; the post-freeze G8 verifier is then self-tested, manifest-bound, and independently revalidated using that bootstrap trust anchor. G8 MUST fail closed if the bootstrap record is absent, stale, modified, or self-derived from the files it authorizes. The derived G8 scope/matrix MUST preserve the bootstrap minimum and MUST never reduce or silently replace required certification rows.

# G8 Build-Input Identity — Mandatory

`PRODUCT_ARTIFACT_ID` MUST be derived only from immutable product/runtime implementation inputs and MUST exclude all product verification-test inputs (`app/src/test/**`, `app/src/androidTest/**`) unless a path is explicitly classified `SHARED_CONTROL_INPUT`. Certification-only tests, G8 fixtures, certification scripts, certification contracts, and G8 evidence/reporting infrastructure are always excluded from `PRODUCT_ARTIFACT_ID`. At minimum the build-input manifest MUST classify and bind:

```text
Gradle build scripts and settings
version catalogs / plugin configuration
google-services / Firebase configuration actually consumed by the build
manifest/resources/build configuration inputs
non-secret Gradle properties and environment-derived build values
product variant/build-type identity
resolved dependency/plugin graph + repository configuration
signing configuration identity/fingerprint (never the private key)
NDK/toolchain/build flags where applicable
```

Secret values MUST NOT be copied into the manifest; instead record an approved secret/config fingerprint or external identity. The release artifact MUST be reproducible from the sealed `PRODUCT_RELEASE_INPUT_ID` and its component `PRODUCT_BUILD_INPUT_MANIFEST_ID`. Certification overlay inputs MUST be proven not to alter this manifest before `G8-FREEZE`.

**Hermetic build boundary:** Certification release builds MUST execute with an isolated `GRADLE_USER_HOME`, no unapproved user/global `init.gradle` or `init.d` injection, a controlled environment-variable allowlist, fixed repository configuration, and verified Gradle-wrapper distribution integrity/checksum where the wrapper mechanism supports it. Any unapproved Gradle init script, global property injection, alternate repository, or wrapper-distribution mismatch is a blocking build-provenance failure. This control applies to the exact release build, not merely to test execution.


# 0. Certification Boundary and State Model

G8 is the final external certification boundary after G1–G7 implementation and final-artifact revalidation.

The verifier derives exactly these distinct states:

```text
ARCHITECTURE_COMPLETE
IMPLEMENTATION_COMPLETE
VERIFIED
PRODUCTION_READY
```

State semantics:

```text
ARCHITECTURE_COMPLETE
    = frozen authority/architecture set is intact and no forbidden architecture drift is detected.

IMPLEMENTATION_COMPLETE
    = all blocking G1–G7 implementation requirements have final-artifact-bound evidence,
      and G8 certification implementation itself is present.

VERIFIED
    = all required executable verification, adversarial fixtures, instrumentation,
      structural checks, evidence-integrity checks, and contract rows pass over the sealed
      PRODUCT_ARTIFACT_ID using the sealed CERTIFICATION_ARTIFACT_ID and certification corpus.

PRODUCTION_READY
    = VERIFIED plus the required signed release artifact exists, is bound to `PRODUCT_ARTIFACT_ID`,
      and all fail-closed release checks pass.
```

`PRODUCTION_READY` is **not** a runtime flag and must not be written into application state, Firebase, Room, or production business data.

## G8 final closure rule

The canonical bundle for the run is:

```text
evidence/g8/<PRODUCT_ARTIFACT_ID>/<CERTIFICATION_RUN_ID>/closure_bundle.json
```

All other G8 reports/results are derived outputs and cannot act as an independent source of truth.

The source `PRODUCT_ARTIFACT_ID` is frozen before this bundle is generated. Evidence files generated afterward are excluded from `PRODUCT_ARTIFACT_ID` and from the source/test manifests; they must not modify the certified source inputs.

```text
ALL blocking P6 requirements PASS
AND
all mandatory G1–G7 final evidence remains valid for `PRODUCT_ARTIFACT_ID` through `UPSTREAM_CLOSURE_SNAPSHOT_ID`
AND
`PRODUCT_TEST_CORPUS_ID` and `CERTIFICATION_TEST_CORPUS_ID` are complete and mapped according to their domain rules
AND
all required test/instrumentation executions PASS
AND
all required adversarial/meta-gate fixtures PASS
AND
release artifact is generated and cryptographically identified
AND
release signing is fail-closed
AND
G8 verifier self-tests PASS
```

Any missing, stale, partial, ambiguous, substituted, or report-only evidence means `NOT_READY_FOR_CLOSURE` or `FAIL`; the verifier must never infer PASS from absence of a known failure.

---

# 1. G8 Requirement Matrix

| Requirement | Plan task(s) | Mandatory proof |
|---|---|---|
| `P6-G8-REQ-01` External machine certification independent of runtime | G8-01, G8-02, G8-07, G8-10 | verifier runs outside app/runtime; machine-derived closure bundle; self-tests |
| `P6-G8-REQ-02` Exact artifact binding | G8-03, G8-04, G8-06, G8-09, G8-FREEZE, G8-11 | product/certification artifact, corpus, contract, run, and release identity consistency |
| `P6-G8-REQ-03` Full actual test corpus + adversarial fixtures | G8-05, G8-06, G8-07 | 100% PASS, 0 failures, 0 skips, 0 NO-SOURCE; instrumentation executed |
| `P6-G8-REQ-04` Release artifact + fail-closed signing | G8-08, G8-FREEZE, G8-11, G8-12 | exact signed release artifact hash, trusted production certificate fingerprint match, exact-artifact release execution, signing failure fixtures |

Cross-gate dependencies inherited from G1–G7 remain mandatory:

```text
G5 → G2
G5 → G3
G4 → G3
G5 → G7
G7 → G8
G2/G3/G4 runtime evidence → G8
```

G8 cannot repair an upstream G1–G7 defect. It may only detect and fail closed on missing or invalid upstream evidence.

---

# 2. G8 Execution Protocol

G8 must itself obey the project’s bounded execution discipline.

## 2.1 One packet per agent run

```text
PRECHECK
→ execute one certification task/packet
→ targeted verification
→ checkpoint/evidence capture
→ STOP
```

Do not execute the full G8 plan in one unbounded run.

For certification reproducibility, Gradle execution must use the project wrapper (`./gradlew` / `gradlew.bat`) rather than an arbitrary system Gradle installation. If the wrapper is unavailable or not executable in the trusted certification workspace, fail closed.

## 2.2 Stop-the-line rules

Immediately stop when any of these occurs:

```text
command failure
verification timeout
NO-SOURCE
missing test
unmapped current test file
stale result
source/evidence identity mismatch
instrumentation unavailable
release signing failure
unexpected artifact
schema/contract mismatch
vacuous test detected
forbidden pattern violation
secret leakage in evidence
```

Do not edit tests, validators, contracts, or manifests simply to turn the failing condition into PASS.

## 2.3 Evidence checkpoint identity

Every successful packet records:

```text
packet ID
baseline product/certification artifact identities
product artifact manifest identity
certification artifact manifest identity
contract hashes
PRODUCT_TEST_CORPUS_ID / CERTIFICATION_TEST_CORPUS_ID as applicable
toolchain identity
execution environment identity
changed files
executed commands
results
artifact identity
next packet
```

Secrets must never appear in checkpoint or evidence output.

---

# 3. Task G8-00 — Resolve INV-16 Certification-Suite Authority Before Freeze

**Purpose:** Prevent G8 from certifying against a contradictory or stale INV-16 test identity. This is a control-plane prerequisite, not a runtime feature.

**Files:**
- Inspect: `PRODUCTION_INVARIANTS.md`
- Inspect: `DESIGN_DECISIONS.md`
- Inspect: `contract/invariant_contract.yaml`
- Inspect: `contract/invariant_test_map.yaml`
- Inspect: `contract/test_environment_matrix.yaml`
- Inspect: `contract/closure_contract.yaml`
- Create: `contract/g8_certification_contract.yaml`
- Create: `contract/g8_certification_scope.yaml`
- Create: `contract/g8_certification_test_matrix.yaml`
- Create: `contract/g8_adversarial_checks.yaml`
- Inspect: `scripts/verify_invariant_contract.py`
- Inspect: `docs/authority/authority_manifest.sha256`
- Create: `app/src/test/java/com/example/FinalTestMatrixCertificationTest.kt`
- Create: `app/src/test/java/com/example/ProductionCertificationPipelineTest.kt`
- Create: `app/src/test/java/com/example/ProductionExecutableInvariantsTest.kt`
- Create: `app/src/test/java/com/example/DeepCrossLayerInvariantsTest.kt`
- Test/verify: actual files under `app/src/test/java/com/example/`

**Required invariant:**

`contract/g8_certification_scope.yaml` is a certification-only machine scope manifest. It classifies G8-created/owned files and test suites and is not a product/runtime authority source. It MUST NOT override `AGENTS.md`, the frozen authority bundle, or Phase-1–5 contracts.

The four frozen certification-suite names currently asserted by INV-16 are: `FinalTestMatrixCertificationTest`, `ProductionCertificationPipelineTest`, `ProductionExecutableInvariantsTest`, and `DeepCrossLayerInvariantsTest`. G8 must not silently substitute the four currently-existing `required_behavior_tests` for those canonical suite names.

- [ ] **Step 1: Parse every authoritative INV-16 declaration and extract the named certification suites.**
- [ ] **Step 2: Compare the four-suite identity across `PRODUCTION_INVARIANTS.md`, ADR-031, `invariant_contract.yaml` (`canonical_definition`), and `invariant_test_map.yaml` (`tests`).**
- [ ] **Step 3: Compare those names against the actual source tree. Before Step 5, an absent canonical suite is an expected pre-create condition only when the external bootstrap explicitly authorizes G8 to create that exact suite path; a suite is a blocking discrepancy only if it is unauthorized, unexpectedly substituted, or still absent after the creation step.**
- [ ] **Step 4: Verify the external `G8_BOOTSTRAP_*` record before creating any G8 control surface. The bootstrap record is the independent authorization for the G8 scope and required certification-suite minimum. If its expected suite/task/path identities do not match current frozen authority and upstream machine contracts, STOP at activation; G8-00 must not repair the mismatch.**
- [ ] **Step 4a: Create G8-only machine-readable certification controls: `contract/g8_certification_contract.yaml`, `contract/g8_certification_scope.yaml`, and `contract/g8_certification_test_matrix.yaml`. The certification test matrix must enumerate every G8 certification suite, its execution tier, exact Gradle/task selector, required evidence output, invariant/requirement binding, and `environment_sensitivity` classification (`OFFLINE_SAFE`, `SANDBOX_EXTERNAL`, or `PRODUCTION_ENDPOINT_FORBIDDEN`). `INV-16.certification_suites` in the G8 contract must exactly record the four frozen suite names. The resulting scope and matrix MUST preserve the full bootstrap minimum; they may refine, never reduce, the authorized set. The **mandatory certification execution set** MUST be derived as the union of immutable bootstrap minimum + current `phase_requirements.yaml`/`invariant_test_map.yaml` certification bindings + frozen G8 requirements, then intersected with the explicit domain scope; `g8_certification_test_matrix.yaml` is a selector/execution description, not the authority that decides which mandatory rows exist. A matrix row cannot remove or downgrade a derived mandatory execution. Do not mutate `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, or any other G1–G7 closure contract merely to add G8 metadata after upstream closure. The G8 contract is certification infrastructure identity, not product authority.**
- [ ] **Step 5: Create four **real, non-placeholder** certification suites at the exact frozen paths named by the authority: `FinalTestMatrixCertificationTest.kt`, `ProductionCertificationPipelineTest.kt`, `ProductionExecutableInvariantsTest.kt`, and `DeepCrossLayerInvariantsTest.kt`. Recover intended coverage from current frozen requirements/contracts and implement executable assertions; do not merely rename or wrap an empty test class.**
- [ ] **Step 6: Do not edit frozen authority merely to reconcile certification-suite drift. Treat exactly these three files as the immutable Frozen Authority Set:
  1. `docs/authority/Target Product Contract v0.6.md`
  2. `docs/authority/G1-G8 Consolidated Architecture Summary.md`
  3. `docs/authority/Final Independent Adjudication Memo.md`
Their combined canonical bytes MUST match the externally trusted `FROZEN_AUTHORITY_SNAPSHOT_SHA256`. The two transition/implementation guidance files (`EARTHLINK_V1_HANDOVER` and `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0`) remain subordinate and MUST be tracked separately as `TRANSITION_GUIDANCE_SNAPSHOT_SHA256`; they may not overrule the Frozen Authority Set. If the frozen authority, G1–G7 machine contracts, and the G8 certification contract disagree, STOP and escalate through the authority-change process; do not manufacture consistency by rewriting the product authority/contracts.**
- [ ] **Step 7: Treat the existing `invariant_test_map.yaml` INV-16 `tests` list as an upstream input. It must match the four frozen names already declared there; if it does not, STOP. `required_behavior_tests` remains a separate supporting behavior list and cannot substitute for the frozen certification suites.**
- [ ] **Step 8: Add a machine consistency check that, after Step 5, fails if any authoritative declaration lists a different certification-suite set, if any authorized canonical suite path is still absent on disk, if `g8_certification_contract.yaml` disagrees with the frozen declarations, or if `required_behavior_tests` is silently treated as the certification suite.**
- [ ] **Step 8a: Re-run the suite-existence check after creation and prove that every bootstrap-authorized certification suite now exists at its exact required path and is executable.**
- [ ] **Step 9: Detect duplicate/conflicting governance identifiers that could create the same drift pattern; specifically fail on duplicate ADR IDs unless the duplicate is explicitly classified by an existing authoritative governance process as historical/superseded.**

**Exit criteria:**
- INV-16 has one canonical certification-suite identity.
- The canonical suite paths exist and are current executable tests.
- Supporting behavior tests may remain separately mapped, but their semantic role is explicit and cannot masquerade as the frozen certification suite.
- No conflicting ADR/test identity remains unresolved.
- `contract/g8_certification_test_matrix.yaml` contains an executable entry for every frozen INV-16 certification suite and every additional mandatory G8-only suite; each entry names an exact task/selector, expected result artifact, and requirement/invariant binding.

---

# 4. Task G8-01 — Certification Preflight and Candidate Baseline

**Purpose:** Establish the certification preconditions and a candidate baseline. Do not declare the sealed certification identities until every G8 implementation task and G8 self-test is complete.

**Files:**
- Inspect: `AGENTS.md`
- Inspect: `docs/authority/Target Product Contract v0.6.md`
- Inspect: `docs/authority/G1-G8 Consolidated Architecture Summary.md`
- Inspect: `docs/authority/Final Independent Adjudication Memo.md`
- Inspect: `docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
- Inspect: `contract/phase_requirements.yaml`
- Inspect: `contract/invariant_contract.yaml`
- Inspect: `contract/closure_contract.yaml`
- Inspect: `contract/closure_schema.json`
- Inspect: `contract/test_environment_matrix.yaml`
- Inspect: `contract/invariant_test_map.yaml`
- Inspect: `contract/forbidden_patterns.yaml`
- Test/verification: `scripts/verify_invariant_contract.py`, `scripts/verify_test_environment_matrix.py`

**Interfaces:**
- Consumes: final Phase-5 source tree and final G1–G7 evidence inventory.
- Produces: candidate baseline identity, preflight state, and a certification-run context. Final `PRODUCT_ARTIFACT_ID` is created only by the dedicated G8-FREEZE task after all G8 implementation changes are complete.

- [ ] **Step 1: Verify that G1–G7 final closure is complete before G8 implementation/creation begins.**
  - Require final-artifact-bound evidence for every blocking `phase_requirements.yaml` row through Phase 5.
  - Reject historical completion JSON, old reports, or prior artifact evidence as substitutes.
- [ ] **Step 2: Verify the actual certification workspace has a healthy trusted Git repository and a single exact `HEAD` identity; the ZIP-exported `.git` is never used as certification history.**
- [ ] **Step 3: Require a clean candidate working tree; record `git rev-parse HEAD`, `git status --porcelain`, branch/ref, and the candidate source/test-corpus manifests.**
- [ ] **Step 4: Verify the frozen-authority snapshot against the externally trusted `FROZEN_AUTHORITY_SNAPSHOT_SHA256` before running mutable G8 work. A matching hash from inside the candidate workspace is not sufficient.**
- [ ] **Step 4a: Run current authority/contract validators through `scripts/run_verified_command.py`.**
- [ ] **Step 5: Preflight certification capabilities without assuming the final environment contract has already been built. Verify the required Android SDK/platform tools, `apksigner`, project Gradle wrapper execution, supported device/emulator connectivity, instrumentation prerequisites, and the externally authorized certification-environment bootstrap input. The fully machine-enforced Release Certification Environment Contract is created/verified by G8-08A; missing prerequisite capability or missing external environment authorization is an immediate STOP, not a later SKIP/PASS.**
- [ ] **Step 6: Mint a fresh `certification_run_id` and establish the candidate certification context. Generate it with UUID4 (or an explicitly equivalent collision-resistant mechanism) and record `created_at`, creator/tool identity, and parent candidate identity.**
  - Require a valid 40-hex `HEAD` commit identity and an empty `git status --porcelain` before freeze.
  - No source, test, contract, script, Gradle, manifest, or release configuration changes are permitted after `PRODUCT_ARTIFACT_ID` is declared without invalidating the certification run and starting a new run.
  - Generated evidence under `evidence/g8/<PRODUCT_ARTIFACT_ID>/<CERTIFICATION_RUN_ID>/` is output, not source input; evidence generation must not change `PRODUCT_ARTIFACT_ID` or be included in the source manifest.
- [ ] **Step 7: Freeze the upstream closure snapshot as `UPSTREAM_CLOSURE_SNAPSHOT_ID`.**
  - Record the final Phase-5 `PRODUCT_ARTIFACT_ID`, `PRODUCT_TEST_CORPUS_ID`, all G1–G7 final evidence references/hashes, governing contract hashes, and the final-artifact invalidation/re-run results.
  - This snapshot is read-only for the G8 run and is never updated by later certification-only changes.
- [ ] **Step 8: Define the machine-readable authorized-change scope for G8.**
  - Consume the aggregated Phase-1–5 authorized implementation scope plus the explicitly authorized G8 certification scope.
  - Require every changed path in the candidate workspace to resolve to one of those scopes; unclassified changes are blocking.
- [ ] **Step 9: Record the forensic/historical origin separately.**
  - Preserve ZIP-71 provenance only where the authority requires it; never use ZIP-71 identity as current certification identity.

**Exit criteria:**
- Candidate baseline/context is identified; final `PRODUCT_ARTIFACT_ID` is not yet declared.
- All blocking G1–G7 evidence is present and artifact-bound.
- No stale evidence is silently reused.

---

# 5. Task G8-02 — Build Domain-Specific Product and Certification Artifact Manifests

**Purpose:** Replace historical-manifest dependence with two machine-generated manifests that distinguish the immutable product release inputs from the G8 certification implementation inputs.

**Files:**
- Create: `scripts/build_g8_source_manifest.py`
- Test: `scripts/test_g8_certifier.py`
- Inspect: `evidence/baseline_manifest.json`
- Inspect: `contract/closure_schema.json`

**Manifest rules and domain precedence:**

Candidate discovery may begin from broad include roots, but **domain ownership is authoritative**. A wildcard include can discover a path; it can never override the path's explicit domain classification.

```text
DOMAIN SCOPE PRECEDENCE (highest → lowest)
1. explicit path classification in contract/g8_certification_scope.yaml
2. explicit shared-control classification
3. deterministic fallback rule
4. broad wildcard discovery

If a path resolves ambiguously at any stage:
    FAIL / STOP

A path classified CERTIFICATION or SHARED_CONTROL_INPUT MUST NOT enter PRODUCT manifests merely because it matches app/src/test/**, app/src/androidTest/**, scripts/**, contract/**, or any other wildcard.
A path classified PRODUCT MUST NOT enter certification-only manifests unless explicitly classified as SHARED_CONTROL_INPUT.
```

Broad discovery roots used only for candidate enumeration may include:

```text
app/src/main/**
app/src/test/**
app/src/androidTest/**
app/build.gradle.kts
settings.gradle.kts
gradle/**
gradlew
gradlew.bat
contract/**
PRODUCTION_INVARIANTS.md
PRODUCTION_CONTRACT_MATRIX.md
ARCHITECTURE.md
AGENTS.md
docs/authority/**
scripts/**
```

The final product/certification manifest membership is determined only after the domain classifier runs; wildcard membership alone is never sufficient.

Exclude generated outputs that are produced by the run itself:

```text
.git/**
app/build/**
*.class
__pycache__/**
evidence/<current-run-output>/**
```

Do not silently use `evidence/baseline_manifest.json` as the current manifest.

- [ ] **Step 1: Write manifest fixture tests.**
  - Verify deterministic path ordering.
  - Verify SHA-256 of every included file.
  - Verify excluded generated files are not included.
  - Verify a single source modification changes the manifest hash.
- [ ] **Step 2: Implement the manifest generator with explicit domain selectors. Produce `product_artifact_manifest.json` from the immutable product/runtime inputs and `certification_artifact_manifest.json` from G8-only scripts/contracts/certification tests/fixtures. Produce a combined workspace manifest only as a derived convenience artifact; it is not a substitute for either identity domain.**
- [ ] **Step 3: Emit candidate manifests under the run-scoped evidence directory. After `G8-FREEZE`, `PRODUCT_ARTIFACT_ID` remains the upstream product identity while `CERTIFICATION_ARTIFACT_ID` is sealed from the G8 implementation workspace.**
- [ ] **Step 4: Record both manifest SHA-256 values and the exact include/exclude rules in the G8 evidence bundle.**
- [ ] **Step 5: Generate `PRODUCT_BUILD_INPUT_MANIFEST_ID` from the effective product release build inputs and compare a release-build resolution from the Certification Overlay Workspace against the sealed Product Release Workspace. Any G8-only file that changes the resolved product build-input manifest is a blocking cross-domain contamination.**
- [ ] **Step 6: Verify the bootstrap authorized-path manifest before accepting any G8-owned file into the certification domain. A G8-owned path that is not authorized by the external bootstrap is a blocking scope violation.**

**Exit criteria:**
- Product and certification identities are independently reproducible from their respective workspaces/manifests.
- Domain ownership is the final authority over manifest membership; wildcard discovery can never override explicit PRODUCT/CERTIFICATION/SHARED_CONTROL_INPUT classification, and ambiguity is blocking.
- Product-release changes are compared against the authorized Phase-1–5 implementation scope; certification-only changes are compared against the authorized G8 certification scope. Cross-domain changes are blocking unless explicitly re-authorized before freeze.
- A historical manifest cannot satisfy the current-manifest requirement.

---

# 6. Task G8-03 — Build Product and Certification Test-Corpus Manifests and Coverage Binding

**Purpose:** Prove that the tests G8 claims to run are the tests that actually exist now, while preserving the distinction between the final Phase-1–5 product corpus and G8-only certification corpus.

**Files:**
- Create/Modify: `scripts/build_g8_test_corpus_manifest.py`
- Modify: `scripts/verify_test_environment_matrix.py` only if an existing reusable helper is present; otherwise keep the logic in the new G8 script.
- Inspect/verify: `contract/test_environment_matrix.yaml`
- Inspect/verify: `contract/invariant_test_map.yaml`
- Test: `scripts/test_g8_certifier.py`

**Current-corpus identity must cover:**

```text
app/src/test/**/*.kt
app/src/androidTest/**/*.kt
registered structural/adversarial verification scripts
mandatory test/fixture mappings from phase_requirements.yaml
```

`HISTORICAL` in `test_environment_matrix.yaml` is a test/fixture tier, not permission to treat historical manifests as the current product/certification test corpus as explicitly identified by domain. Historical fixtures execute through their current mapped test suites and do not create a separate authority source.

- [ ] **Step 1: Inventory actual test files from the current source tree.**
- [ ] **Step 1a: Classify every current test/fixture as either `PRODUCT_TEST_CORPUS` or `CERTIFICATION_ONLY_TEST_CORPUS` using the explicit G8 certification-scope manifest; do not classify by filename alone.**
- [ ] **Step 1b: Compute separate `PRODUCT_TEST_CORPUS_ID` and `CERTIFICATION_TEST_CORPUS_ID`. Adding G8 certification suites must not invalidate the upstream G1–G7 product evidence solely because the overall test-corpus hash changed.**
- [ ] **Step 2: Inventory actual product-mapped suites from `test_environment_matrix.yaml` and certification-mapped suites from `contract/g8_certification_test_matrix.yaml`. G8 certification suites must be executable through the G8 matrix even when they are intentionally absent from the upstream product matrix. The two selector sets MUST be execution-disjoint; if a broad Gradle task would discover both domains, the G8 gate must use explicit per-suite selectors or machine-proven exclusions.**
- [ ] **Step 3: Reject any of these conditions:**
  - current test file exists but is not mapped;
  - mapped test path does not exist;
  - duplicate mapping;
  - mandatory requirement has no executable test/fixture;
  - current contract names a test suite that no longer exists;
  - a test is marked `INSTRUMENTED` but is not under `app/src/androidTest/`;
  - current adversarial fixture is absent.
- [ ] **Step 4: Generate a deterministic current test-corpus manifest and SHA-256.**
- [ ] **Step 5: Bind the corpus manifest to the current candidate source manifest identity during pre-freeze work; after `G8-FREEZE`, bind it to the sealed `PRODUCT_ARTIFACT_ID` and `CERTIFICATION_ARTIFACT_ID` identities.**
- [ ] **Step 6: Verify that `g8_certification_test_matrix.yaml` is a superset-equivalent of the immutable `G8_BOOTSTRAP_REQUIRED_SUITES_SHA256` minimum and that every mandatory row has a deterministic executable selector. A reduction, omission, or weakening of the bootstrap matrix is FAIL.**
- [ ] **Step 7: Freeze a deterministic list of the complete current `PRODUCT_TEST_CORPUS` that G8-05 MUST execute at least once through the hardened G8 gate, independently of prior Phase-1–5 PASS claims. This re-execution is mandatory specifically to prevent known upstream gate defects from becoming permanently trusted merely because historical evidence exists.**

**Exit criteria:**
- `PRODUCT_TEST_CORPUS_ID` is complete against the Phase-1–5 product matrix.
- `CERTIFICATION_TEST_CORPUS_ID` is complete against the G8 certification matrix and includes every mandatory certification suite.
- No historical test manifest is treated as current test identity.

---

# 7. Task G8-04 — Implement Independent Evidence-Bundle Verifier

**Purpose:** Implement the external machine verifier that derives certification state exclusively from evidence.

**Files:**
- Create: `scripts/g8_certify.py`
- Create: `scripts/g8_verify_certification_bundle.py`
- Inspect/execute unchanged: `scripts/collect_closure_evidence.py`
- Inspect/execute unchanged: `scripts/verify_closure_evidence.py` for upstream G1–G7 evidence only
- Create: `contract/g8_closure_schema.json`
- Test: `scripts/test_g8_certifier.py`

**Verifier inputs:**

```text
final product artifact (`PRODUCT_ARTIFACT_ID`)
product artifact manifest
certification artifact manifest
PRODUCT_TEST_CORPUS manifest
CERTIFICATION_TEST_CORPUS manifest
phase_requirements.yaml
invariant_contract.yaml
invariant_test_map.yaml
closure_contract.yaml
closure_schema.json
forbidden_patterns.yaml
test_environment_matrix.yaml
actual test outputs
actual instrumentation outputs
release artifact
```

**Verifier outputs:**

```text
G8 certification evidence bundle
machine-derived states
closure result
artifact hashes
execution summaries
exact failure reasons when blocked
```

- [ ] **Step 1: Define the G8 evidence schema with required fields for:**
  - PRODUCT_ARTIFACT_ID;
  - product artifact manifest identity;
  - CERTIFICATION_ARTIFACT_ID;
  - certification artifact manifest identity;
  - contract identities/hashes;
  - PRODUCT_TEST_CORPUS_ID;
  - CERTIFICATION_TEST_CORPUS_ID;
  - toolchain/environment identity;
  - resolved Gradle dependency/plugin graph identity and repository configuration identity;
  - unit/Robolectric execution;
  - instrumentation execution;
  - structural/adversarial results;
  - release artifact identity/hash;
  - derived states;
  - failure/block reasons;
  - per-requirement/per-state executable evidence references;
  - evidence artifact manifest with producer command and SHA-256.
- [ ] **Step 2: Implement fail-closed evidence loading.**
- [ ] **Step 3: Require exact identity equality within each identity domain, and require the explicit cross-domain relationship:**
  - G1–G7 product evidence → `PRODUCT_ARTIFACT_ID` + `PRODUCT_TEST_CORPUS_ID` + `UPSTREAM_CLOSURE_SNAPSHOT_ID`;
  - G8 certification evidence → `PRODUCT_ARTIFACT_ID` + `CERTIFICATION_ARTIFACT_ID` + `CERTIFICATION_TEST_CORPUS_ID` + `CERTIFICATION_RUN_ID`.
- [ ] **Step 4: Do not rewrite the upstream G1–G7 collector/verifier semantics after closure. Invoke the existing `verify_closure_evidence.py` only to validate the sealed upstream snapshot; do not recollect upstream closure evidence after activation, with their exact tool identity recorded. G8-specific evidence must be collected and verified by `g8_certify.py` + `g8_verify_certification_bundle.py` using `contract/g8_closure_schema.json` and the run-scoped path `evidence/g8/<PRODUCT_ARTIFACT_ID>/<CERTIFICATION_RUN_ID>/`. Reject legacy `evidence/<source_sha>/` artifacts as current G8 proof.**
- [ ] **Step 5: Reject any evidence generated before final product closure or against a different product/certification/build identity.**
- [ ] **Step 6: Derive states; never accept state fields supplied by an agent/report as authority.**
- [ ] **Step 7: Make the G8 evidence verifier fail closed on missing result directories, malformed XML/JSON, unreadable evidence files, and zero-test mandatory suites; parse warnings must never be treated as successful execution.**
- [ ] **Step 8: Ensure `g8_certify.py` is orchestration only for the G8-specific bundle; it MUST NOT invoke `scripts/collect_closure_evidence.py` after activation to regenerate, replace, or refresh upstream G1–G7 closure evidence. It may invoke `scripts/verify_closure_evidence.py` only to validate the sealed `UPSTREAM_CLOSURE_SNAPSHOT_ID`. G8-specific evidence is collected and verified only by G8-owned collection/orchestration logic using `g8_verify_certification_bundle.py` and `contract/g8_closure_schema.json`. It must not import production application classes. These verifier roles are domain-separated, not competing.**

**Exit criteria:**
- Same evidence + same inputs always produces the same derived state.
- Missing evidence fails closed.

---

# 8. Task G8-05 — Implement Full Test-Matrix Execution and NO-SOURCE/Skip Rejection

**Purpose:** Execute the pre-freeze eligible test corpus and prove there are no silent omissions before the environment-sensitive final certification run. This task is not the final sealed execution; G8-11 performs the final 100% product + certification corpus execution.

**Files:**
- Create: `contract/g8_certification_test_matrix.yaml`
- Create: `scripts/g8_production_gate.sh`
- Create: `scripts/g8_production_gate.ps1`
- Create/Modify: `scripts/test_g8_certifier.py`
- Inspect: `contract/test_environment_matrix.yaml`
- Inspect unchanged: `scripts/production_gate.sh` / `scripts/production_gate.ps1`
- Inspect unchanged: `scripts/run_verified_command.py`

**Required execution tiers:**

```text
JVM
ROBOLECTRIC
INSTRUMENTED
STRUCTURAL
ADVERSARIAL / META-GATE fixtures
```

The exact G8 command set must be derived from the current product test corpus plus `contract/g8_certification_test_matrix.yaml`, not hardcoded from an old phase. Every executable suite must have a deterministic `environment_sensitivity` classification before execution; unknown/unclassified sensitivity is FAIL. Sensitivity classification is conservative: if static/runtime inspection cannot prove `OFFLINE_SAFE`, the suite MUST be treated as `SANDBOX_EXTERNAL` or more restrictive; an agent cannot downgrade a network-capable suite to `OFFLINE_SAFE` merely to run it pre-environment. Network-capable suites cannot execute before G8-08A mechanically enforces the certification allowlist. The existing `production_gate.sh` / `.ps1` are upstream control-plane inputs and MUST NOT be modified merely to make G8 pass; G8 uses dedicated certification gate orchestrators that select the current G8 matrix and invoke the existing bounded runner. Any shared runner/tooling modification requires explicit revalidation of affected upstream evidence. The project Gradle wrapper is mandatory.

- [ ] **Step 1: Start from isolated execution outputs: clear prior Gradle test/result/output directories or execute from a fresh certification workspace so no prior XML/APK/result can be mistaken for current evidence.**
- [ ] **Step 2: Execute the complete `PRODUCT_TEST_CORPUS` JVM/Robolectric selectors using the verified runner; do not invoke a broad test task that silently includes G8-only certification suites.**
- [ ] **Step 3: Parse all result XMLs and require:**
  - total tests > 0 where a suite is mandatory;
  - failures = 0;
  - errors = 0;
  - skipped = 0;
  - no `NO-SOURCE` result;
  - result files correspond to `PRODUCT_ARTIFACT_ID`.
- [ ] **Step 4: Execute only JVM/Robolectric/structural/adversarial product tests in the pre-freeze candidate environment unless the Release Certification Environment Contract from G8-08A is already machine-enforced. No network-bound or externally mutating instrumentation may run before environment isolation is enforced.**
- [ ] **Step 5: Defer all network-bound or externally sensitive Android instrumentation revalidation to G8-11 after G8-08A environment enforcement; require instrumentation evidence proving the task actually ran, and treat unavailable device/instrumentation as FAIL, not SKIP or PASS. Any suite whose `environment_sensitivity` is not `OFFLINE_SAFE` is blocked from pre-freeze execution unless the environment contract is already active.**
- [ ] **Step 6: Execute all registered structural/adversarial/meta-gate fixtures through the verified runner.**
- [ ] **Step 7: Verify every mandatory result directory exists after execution; a missing result directory, empty result set, malformed XML/JSON, or parser warning is FAIL, not “no findings” and not a warning-only condition.**
- [ ] **Step 8: Force all G8 certification Gradle execution through the project wrapper; `g8_production_gate.sh` / `.ps1` must reject any system Gradle selection. Existing upstream `production_gate.*` behavior is evidence input, not a G8 modification target.**
- [ ] **Step 9: Pre-freeze, execute 100% of the current `PRODUCT_TEST_CORPUS` suites classified `OFFLINE_SAFE` plus all structural/adversarial fixtures through `g8_production_gate.sh` / `.ps1`, using the explicit product-domain selectors only. Do not execute network-sensitive product instrumentation until G8-08A is mechanically enforced. This establishes a candidate revalidation and does not replace the sealed final run.**
- [ ] **Step 10: Record the exact command, environment, result paths, counts, and hashes in the evidence bundle, including the tool identity used to produce the fresh product revalidation evidence.**

**Exit criteria:**

```text
100% of pre-freeze eligible/offline-safe product selectors executed
100% of required structural/adversarial/meta-gate fixtures executed
0 failures
0 errors
0 skips
0 NO-SOURCE
0 missing mandatory fixtures
0 unmapped current tests
network-sensitive suites deferred until G8-08A enforcement
final 100% product + certification corpus execution remains mandatory in G8-11
```

---

# 9. Task G8-06 — Certification Self-Test / Mutation-Sensitivity Gate

**Purpose:** Prove that the verifier itself detects false confidence.

**Files:**
- Create/Modify: `scripts/test_g8_certifier.py`
- Create: `scripts/test_g8_certifier_fixtures.py`
- Inspect: `scripts/test_verified_runner_fixtures.py`
- Inspect: `scripts/test_meta_gate_fixtures.py`
- Inspect: `scripts/test_gate_adversarial_failures.py`

**Required G8 self-tests:**

- [ ] missing required test → FAIL;
- [ ] stale test result → FAIL;
- [ ] current test path replaced/renamed → FAIL;
- [ ] vacuous assertion fixture → FAIL;
- [ ] report-only PASS with no executable evidence → FAIL;
- [ ] instrumentation unavailable → FAIL;
- [ ] wrong source manifest → FAIL;
- [ ] wrong source/build identity → FAIL;
- [ ] wrong release artifact hash → FAIL;
- [ ] blocked mandatory requirement → FAIL;
- [ ] `NO-SOURCE` masked by exit code 0 → FAIL;
- [ ] skipped mandatory test masked as success → FAIL;
- [ ] release build falls back to debug/unsigned → FAIL;
- [ ] stale historical evidence substituted for current evidence → FAIL;
- [ ] evidence contains secret-like values → FAIL;
- [ ] certification timeout leaves a child process alive → FAIL and process tree is cleaned up;
- [ ] certification runner timeout leaves a child process alive → FAIL;
- [ ] unexpected source/control-plane file outside the authorized Phase-1–5/G8 scope → FAIL;
- [ ] duplicate certification run attempts to overwrite an existing bundle → FAIL;

Fixtures must run against temporary copies/temporary evidence structures and must not mutate the production repository baseline.

**Exit criteria:** Every known-bad mutation is detected by the verifier with a non-PASS outcome.

---

# 10. Task G8-07 — Harden Evidence Integrity, Contract Hashing, and Environment Identity

**Purpose:** Bind certification to the effective execution environment, not only source SHA.

**Files:**
- Modify: `scripts/g8_certify.py`
- Modify: `contract/g8_closure_schema.json`
- Inspect: `contract/test_environment_matrix.yaml`
- Inspect: `contract/g8_closure_schema.json`
- Test: `scripts/test_g8_certifier.py`

**Certification-infrastructure manifest scope:** it is the deterministic manifest of the G8-specific scripts/contracts/verifiers/orchestrator actually used to produce and verify this run. It is a named subset of the broader source manifest, not a competing source manifest, and overlapping files are intentionally referenced by both identities.

**Required identity tuple:**

```text
source identity
source manifest SHA-256
build configuration identity
current test-corpus manifest SHA-256
contract hashes
OS identity
Python version
Java version
Gradle version
Kotlin version
Android/SDK/device identity where instrumentation is required
network/mock/external fixture configuration identity where relevant
certification infrastructure manifest SHA-256
certification_run_id
expected production certificate fingerprint (trusted external input)
```

- [ ] **Step 1: Hash every governing contract used by the run.**
- [ ] **Step 2: Reject contract hash drift between execution and evidence sealing.**
- [ ] **Step 3: Record the exact release build configuration, resolved Gradle dependency/plugin graph, repository configuration, and toolchain versions.**
- [ ] **Step 4: Include external fixture/config identity without storing secrets.**
- [ ] **Step 5: Ensure generated evidence does not contain passwords, tokens, keystore credentials, or secret environment values.**
- [ ] **Step 6: Apply the Shared Verification Tool Rule: `run_verified_command.py`, `collect_closure_evidence.py`, `verify_closure_evidence.py`, and other pre-existing verifier/runner scripts remain upstream-controlled. G8 must not silently change their semantics after G1–G7 closure. If a change is unavoidable, create a G8-pinned certification copy or re-run every affected upstream verification under the new tool identity before accepting the snapshot. The fresh full-product revalidation in G8-05 is mandatory even when no tool change occurs because known upstream gate defects are already documented.**
- [ ] **Step 7: Run self-tests proving the G8 gate fails on the known upstream defect classes: missing result directory and system-Gradle selection.**

**Exit criteria:** Two runs with different effective environments cannot be represented as the same certification identity.

---

# 11. Task G8-08 — Release Artifact Build and Fail-Closed Signing Proof

**Purpose:** Prove and harden the release-signing pipeline and its fail-closed behavior. The actual final production release artifact is built only after G8-FREEZE during G8-11.

**Files:**
- Inspect/verify: `app/build.gradle.kts`
- Create/Modify: `scripts/g8_production_gate.sh`
- Create/Modify: `scripts/g8_production_gate.ps1`
- Inspect unchanged: `scripts/production_gate.sh` / `scripts/production_gate.ps1`
- Modify: `contract/g8_certification_contract.yaml` only for G8-specific release rules; do not mutate upstream release contracts after closure.
- Modify: `scripts/g8_certify.py`
- Test: `scripts/test_g8_certifier_fixtures.py`
- Test: `app/src/test/java/com/example/ProductionCertificationPipelineTest.kt`

- [ ] **Step 1: Verify release build configuration uses the real `release` signing configuration.**
- [ ] **Step 2: Prove missing/invalid production signing credentials fail the release build.**
- [ ] **Step 3: Prove debug signing is not used as a release fallback.**
- [ ] **Step 4: Run release-pipeline negative fixtures through the verified runner; prove missing/invalid credentials, debug fallback, unsigned output, and wrong-variant output all fail.**
- [ ] **Step 5: Verify the signing configuration is wired to the real `release` signing config and that final artifact selection is deterministic.**
- [ ] **Step 5a: Build the final release artifact only from the clean Product Release Workspace at `PRODUCT_ARTIFACT_ID`; the Certification Overlay Workspace must not contribute runtime/build inputs to the APK/AAB.**
- [ ] **Step 5b: Verify release artifact identity fields before signing: package/application ID, versionCode, versionName, variant, and expected signing certificate identity. A correctly signed unrelated artifact is not acceptable.**
- [ ] **Step 6: Define the trusted expected production certificate fingerprint input and verify it is present before any final certification run. Do not infer it from the artifact under test.**
- [ ] **Step 7: Defer final artifact hash/signature evidence to the frozen G8-11 run.**

**Exit criteria:**
- Release pipeline behavior is fail-closed and all negative signing fixtures pass.
- Upstream `production_gate.*`, `run_verified_command.py`, `collect_closure_evidence.py`, and `verify_closure_evidence.py` semantics remain unchanged unless the Shared Verification Tool Rule is satisfied.
- Trusted expected production certificate identity is available for the final certification run.
- No final production artifact is treated as certified before G8-FREEZE and G8-11.

---

# 11A. Task G8-08A — Build and Enforce the Release Certification Environment Contract

**Purpose:** Establish and mechanically validate candidate certification-environment feasibility before the final artifact is frozen, then re-bind the same environment to the final `PRODUCT_ARTIFACT_ID`/`PRODUCT_RELEASE_INPUT_ID` immediately before exact-artifact execution. A declaration-only “certification environment” is not sufficient.

**Files / infrastructure:**
- Inspect: `app/build.gradle.kts`
- Inspect: `app/google-services.json`
- Inspect: `.env.example` and the actual certified build configuration source available only through the trusted release environment
- Inspect: `app/src/main/java/com/example/core/network/EarthlinkNetwork.kt`
- Inspect: `app/src/main/java/com/example/EarthlinkApp.kt`
- Inspect: `app/src/main/res/xml/network_security_config.xml`
- Create/define: G8 release-certification environment manifest/fixture configuration outside production runtime authority
- Create: `scripts/verify_g8_release_environment.py`
- Test: `scripts/test_g8_certifier.py`, `scripts/test_g8_certifier_fixtures.py`

**Mandatory contract:**

```text
certification device/emulator identity
Firebase/project identity or approved offline mode
ISP endpoint policy
network egress allow/deny policy
credential fixture identity
production endpoint denylist / allowlist policy
release artifact package/version identity
ADB/device state baseline

```

- [ ] **Step 1: Inspect the current candidate product build/runtime configuration for every external endpoint/configuration actually embedded or reachable at runtime. Hardcoded production endpoints count; they cannot be assumed to become non-production because the environment is labeled “certification”. The final `PRODUCT_ARTIFACT_ID` is bound later, after `G8-FREEZE`.**
- [ ] **Step 2: Define one of the only two accepted certification-safe strategies:**
  1. an actual non-production/sandbox fixture with machine-enforced endpoint isolation; or
  2. a network-isolated release smoke mode in which all production external endpoints are denied and the mandatory release smoke suite is explicitly limited to flows that require no external production side effect.
- [ ] **Step 3: If the final artifact requires external network access that cannot be routed to an approved non-production endpoint, and no safe offline smoke is sufficient, STOP at the G8 feasibility gate. Do not invent a fake ISP status endpoint, DNS trick, or insecure proxy that could reach production.**
- [ ] **Step 4: Enforce the environment mechanically with an explicit production-denying certification-destination allowlist. Default-deny plus explicit allow rules are mandatory for certification; a denylist-only policy is insufficient. A YAML declaration alone is insufficient.**
- [ ] **Step 5: Verify the approved Firebase/project identity, endpoint policy, credential fixture identity, device/emulator identity, and network policy before installation.**
- [ ] **Step 6: Require a clean device/emulator state: no prior app data, no stale debug install, no prior authentication/session, and no previous certification app state. Record the reset/wipe evidence.**
- [ ] **Step 7: Preflight only the installation/identity capability of the approved certification device/emulator. Verify that the environment can install the final supported release format, identify package/version/variant/signing identity, and produce the required installation receipt or split-set evidence when `G8-11E` runs. Do not require or inspect the final release artifact here; no final artifact exists yet. Actual artifact installation and deployed-artifact identity verification are performed exclusively in `G8-11E`.**
- [ ] **Step 8: Add negative probes proving production Firebase and `rapi.earthlink.iq` (and every other production external endpoint discovered at runtime) are unreachable from the certification environment. Also record the effective endpoint/configuration identity actually embedded in the release artifact.**
- [ ] **Step 9: Seal the candidate environment feasibility manifest and enforcement evidence. Do not treat this candidate identity as final certification evidence.**

**Exit criteria:**
- A machine-verified, non-production certification environment exists, or the run stops with an explicit external infrastructure feasibility failure.
- The environment cannot silently reach production endpoints.
- Candidate environment feasibility is sealed for later final binding; final certification environment identity is revalidated after `G8-FREEZE` and before exact signed-artifact execution.

# 12. Task G8-09 — Final Evidence Bundle Sealing and Independent Revalidation

**Purpose:** Seal one evidence bundle and prove it is internally consistent before deriving final certification state.

**Files:**
- Modify: `scripts/g8_certify.py`
- Inspect/execute unchanged: `scripts/collect_closure_evidence.py` for upstream snapshot evidence
- Inspect/execute unchanged: `scripts/verify_closure_evidence.py` for upstream snapshot verification
- Modify: `scripts/g8_certify.py`
- Modify: `scripts/g8_verify_certification_bundle.py`
- Modify: `contract/g8_closure_schema.json`
- Create: `scripts/g8_generate_compliance_matrix.py` so G8 derives requirement IDs/status from current G8 machine inputs without mutating the upstream compliance generator.
- Create: `scripts/g8_render_certification_report.py`
- Test: `scripts/test_g8_certifier.py`

**Required sealing checks:**

```text
PRODUCT_ARTIFACT_ID matches product artifact manifest
CERTIFICATION_ARTIFACT_ID matches certification artifact manifest
PRODUCT_TEST_CORPUS_ID / CERTIFICATION_TEST_CORPUS_ID match their respective manifests
source identified by `PRODUCT_ARTIFACT_ID` matches all current execution evidence
contract hashes match current contracts
instrumentation evidence matches `PRODUCT_ARTIFACT_ID`
release artifact matches sealed `PRODUCT_RELEASE_INPUT_ID`
release artifact SHA-256 matches sealed bundle
all required requirements are represented
all blocking rows are PASS
no UNKNOWN closure state
```

- [ ] **Step 1: Generate exactly one canonical `evidence/g8/<PRODUCT_ARTIFACT_ID>/<CERTIFICATION_RUN_ID>/closure_bundle.json` for the run. Refuse to overwrite an existing run directory or bundle.**
- [ ] **Step 2: Emit `closure_bundle.sha256` plus an artifact manifest containing every evidence artifact's path, type, producer command, SHA-256, source/build identity, and certification run ID.**
- [ ] **Step 3: Require every PASS requirement/state to contain at least one exact executable `evidence_ref` that resolves to an artifact in the same sealed run and whose hash matches. PASS with empty evidence references is invalid.**
- [ ] **Step 3a: Reject circular/derived-only evidence references. A PASS `evidence_ref` must point to a primary executable artifact such as JUnit XML, instrumentation result, runner metadata, source/test manifest, release artifact hash/signing output, or known-bad mutation result. It must not point only to `closure_bundle.json`, a rendered report, a compliance matrix, or another derived summary that merely repeats the PASS claim.**
- [ ] **Step 4: Run the verifier a second time against the sealed bundle.**
- [ ] **Step 5: Modify one fixture/hash/result in a temporary copy and prove revalidation fails.**
- [ ] **Step 6: Reject any evidence path not bound to the same `PRODUCT_ARTIFACT_ID`, `CERTIFICATION_ARTIFACT_ID`, `CERTIFICATION_TEST_CORPUS_ID`, and `CERTIFICATION_RUN_ID`; upstream G1–G7 evidence must additionally resolve through `UPSTREAM_CLOSURE_SNAPSHOT_ID`.**
- [ ] **Step 6a: Recompute the mandatory execution sets through one canonical derivation function and record its identity in the bundle:**
  - `MANDATORY_PRODUCT_EXECUTION_SET = DERIVE(PRODUCT_TEST_CORPUS_ID, phase_requirements.yaml, invariant_test_map.yaml, test_environment_matrix.yaml, frozen G1–G7 bindings)`.
  - `MANDATORY_CERTIFICATION_EXECUTION_SET = DERIVE(G8_BOOTSTRAP_REQUIRED_SUITES, G8_BOOTSTRAP_REQUIRED_TASKS, phase_requirements.yaml certification bindings, invariant_test_map.yaml certification bindings, g8_certification_scope.yaml, frozen G8 requirement bindings)`.
  - `g8_certification_test_matrix.yaml` is selector/execution mapping only. It may describe additional diagnostics, but any `MANDATORY` row must be present in the derived set; no matrix row may remove, downgrade, or redefine a mandatory requirement.
  - The exact derivation algorithm/version and its input hashes MUST be recorded as `MANDATORY_EXECUTION_DERIVATION_ID`.
- [ ] **Step 7: Derive the four final states from the verified bundle.**

**Exit criteria:** One certification run produces one self-consistent evidence bundle with deterministic derived states.

---

# 13. Task G8-10 — Final Certification Report and Non-Recursive Closure

**Purpose:** Render a human-readable certification report from machine evidence without allowing the report to become authority.

**Files:**
- Create/Modify: `scripts/g8_render_certification_report.py`
- Create/Modify: `scripts/g8_generate_compliance_matrix.py` to derive requirement counts/statuses only from current machine evidence.
- Modify: `scripts/g8_certify.py`
- Test: `scripts/test_g8_certifier.py`
- Inspect: `contract/closure_contract.yaml`

- [ ] **Step 1: Generate the compliance matrix from machine evidence.**
- [ ] **Step 2: Render the certification report exclusively from the machine-verified bundle.**
- [ ] **Step 3: Explicitly prevent hardcoded CLOSED/PASS/PRODUCTION_READY strings from becoming authority without machine evidence.**
- [ ] **Step 4: Ensure the report is descriptive output, not an input to certification.**
- [ ] **Step 5: Ensure G8 does not register itself as a runtime governance layer or create recursive governance dependencies.**

**Exit criteria:** Report rendering is deterministic and report-only; deleting the report cannot change machine-derived certification state.

---

# 14. Task G8-FREEZE — Seal the Certification Implementation Against the Immutable Phase-5 `PRODUCT_ARTIFACT_ID`

**Purpose:** Bind certification to the immutable Phase-5 product identity without creating a new product identity. This task occurs **after all G8 implementation and self-test changes are complete**.

**Files:**
- Inspect: every G8 script/contract/test modified by G8-00 through G8-10
- Execute: current G8 self-tests and structural validators
- Verify/seal: inherited Phase-5 `PRODUCT_ARTIFACT_ID` and `PRODUCT_TEST_CORPUS_ID`
- Create/freeze: `CERTIFICATION_ARTIFACT_ID` manifest and `CERTIFICATION_TEST_CORPUS_ID`
- Create/freeze: `PRODUCT_BUILD_INPUT_MANIFEST_ID` and `PRODUCT_RELEASE_INPUT_ID` derived from the immutable product identity and approved build inputs
- Do not create a new `PRODUCT_ARTIFACT_ID` in G8-FREEZE; it is inherited from Phase 5 and only re-sealed

- [ ] **Step 1: Verify all G8 implementation tasks G8-00 through G8-10 are complete and no G8 source/control-plane changes remain uncommitted.**
- [ ] **Step 2: Run the complete G8 self-test suite, including all known-bad evidence mutations and runner timeout/process-tree fixtures.**
- [ ] **Step 3: Re-run current authority, contract, and test-corpus structural checks against the candidate workspace.**
- [ ] **Step 4: Generate product-domain manifests from the clean Product Release Workspace only, and certification-domain manifests from the Certification Overlay Workspace only. Domain ownership rules override all wildcard discovery.**
- [ ] **Step 5: Verify that the inherited Phase-5 `PRODUCT_ARTIFACT_ID` and `PRODUCT_TEST_CORPUS_ID` exactly match the final-artifact-bound Phase-5 closure snapshot and have not changed. G8 MUST NOT create, replace, or reinterpret the product identity.**
- [ ] **Step 6: Freeze `CERTIFICATION_ARTIFACT_ID`, `CERTIFICATION_TEST_CORPUS_ID`, `PRODUCT_BUILD_INPUT_MANIFEST_ID`, and `PRODUCT_RELEASE_INPUT_ID` around the inherited immutable product identity. Record all identities plus `UPSTREAM_CLOSURE_SNAPSHOT_ID`, governing certification-contract hashes, toolchain/environment identity, and `CERTIFICATION_RUN_ID` as the sealed certification target.**
- [ ] **Step 6a: Prohibit G8 from changing any product/runtime source or any governing G1–G7 contract input that is part of `PRODUCT_ARTIFACT_ID`. If G8-00 or any later task requires such a change, STOP and require a new Phase-1–5 closure cycle.**
- [ ] **Step 7: After the product and certification identities are declared, prohibit all changes to product-release inputs or sealed certification infrastructure inputs. Any such change invalidates the run and requires a new `CERTIFICATION_RUN_ID` and new certification identities.**

**Exit criteria:**
- `PRODUCT_ARTIFACT_ID` remains the inherited immutable Phase-5 product artifact; G8 never creates a replacement product artifact identity.
- `PRODUCT_RELEASE_INPUT_ID` remains the immutable release-build identity derived from the sealed product artifact, build-input manifest, and governing release-build inputs.
- `CERTIFICATION_ARTIFACT_ID` is the immutable post-G8 certification implementation artifact.
- No final evidence refers to a product/certification identity different from the sealed identities.
- G8-11 and G8-12 execute only against the sealed product/certification identities and their unique run identity.

---

# 15. Task G8-11 — End-to-End G8 Certification Run

**Final environment re-bind:** The candidate environment built in G8-08A MUST be re-bound to the sealed `PRODUCT_ARTIFACT_ID` and `PRODUCT_RELEASE_INPUT_ID` before any network-capable final test or release smoke. Re-scan final artifact endpoints/configuration, re-verify the exact destination allowlist, and emit the final environment manifest identity.
This final binding occurs after `G8-FREEZE`; the candidate environment feasibility identity from G8-08A is never treated as final certification evidence by itself.



**Purpose:** Execute the completed and frozen G8 certification implementation against immutable `PRODUCT_ARTIFACT_ID` using sealed `CERTIFICATION_ARTIFACT_ID` and a fresh certification environment. G8-11 is an execution group, not a single 480-second agent packet.

**Bounded sub-packets:**
```text
G8-11A  Identity + environment lock
G8-11B  Full final PRODUCT_TEST_CORPUS revalidation
G8-11C  Full CERTIFICATION_TEST_CORPUS execution
G8-11D  Release build + signing
Before starting G8-11D, verify the hermetic build boundary: isolated `GRADLE_USER_HOME`, controlled environment-variable allowlist, no unapproved user/global Gradle `init.gradle`/`init.d`, fixed repositories, and verified wrapper distribution integrity. Any violation blocks the build.
G8-11E  Exact signed-artifact install + release smoke
G8-11F  Evidence sealing
G8-11G  Independent revalidation + final state derivation
```
Each sub-packet ends with targeted verification and a checkpoint; no later sub-packet may consume an unverified partial state. The execution steps are mapped as: 11A=Steps 1–2, 11B=Step 4, 11C=Step 5, 11D=Steps 6–8, 11E=Step 9, 11F=Step 10, 11G=Steps 11–12.

**Files:**
- Execute: `scripts/g8_certify.py`
- Execute: `scripts/verify_closure_evidence.py` through the orchestrator for the immutable upstream snapshot only
- Do not re-run `scripts/collect_closure_evidence.py` to regenerate or overwrite upstream G1–G7 closure evidence after activation; final G8 proof must consume the sealed `UPSTREAM_CLOSURE_SNAPSHOT_ID`.
- Execute: `scripts/verify_invariant_contract.py`
- Execute: `scripts/verify_test_environment_matrix.py`
- Execute: all mandatory current test suites and adversarial fixtures selected by the current matrix.
- Execute: release artifact build/signing.

- [ ] **Step 1: Assert `PRODUCT_ARTIFACT_ID`, `CERTIFICATION_ARTIFACT_ID`, `PRODUCT_TEST_CORPUS_ID`, `CERTIFICATION_TEST_CORPUS_ID`, and `UPSTREAM_CLOSURE_SNAPSHOT_ID`; reject any unsealed product/certification change.**
- [ ] **Step 2: Re-assert and execute `scripts/verify_g8_release_environment.py`; bind the exact environment/fixture/network/device identity to the run before any release smoke or network-capable instrumentation against the signed artifact. A missing, declaration-only, or non-enforced environment contract is FAIL. This is the first point at which external/network-sensitive instrumentation may execute.**
- [ ] **Step 3: Run all structural and adversarial self-tests.**
- [ ] **Step 4: Run the complete final `PRODUCT_TEST_CORPUS` through the sealed G8 gate: all JVM/Robolectric suites plus all product instrumentation suites, including network-sensitive suites, now that G8-08A environment enforcement is active. This is the final upstream revalidation and is mandatory regardless of prior Phase-1–5 or pre-freeze candidate results.**
- [ ] **Step 5: Run the complete `CERTIFICATION_TEST_CORPUS` through the sealed G8 certification matrix using explicit certification-domain selectors, including all four INV-16 certification suites and all mandatory certification-only fixtures.**
- [ ] **Step 5a: Reset any mutable device/emulator/session state produced by Steps 4–5 before release-artifact installation; certification tests must not contaminate the subsequent exact-signed-artifact smoke environment.**
- [ ] **Step 6: Build and sign the exact production release artifact from the clean Product Release Workspace at `PRODUCT_ARTIFACT_ID`. Certification-only files are not build inputs for the release artifact.**
- [ ] **Step 7: Verify the exact signed artifact with `apksigner verify --verbose --print-certs` and compare the actual certificate fingerprint with the separately trusted expected production certificate fingerprint.** If no trusted expected fingerprint is available, STOP. Record the `apksigner` version and the independent source/approval identity of the expected fingerprint.
- [ ] **Step 8: Seal the signed artifact identity and certification-environment identity before installation. No rebuild or product-input change is permitted after this point.**
- [ ] **Step 9: Execute release-variant validation against the exact signed artifact itself, not a debug artifact. For APK output, first wipe/uninstall any previous app state, install the exact SHA-identified APK on the approved certification device, verify package/version/variant identity and installed signing identity; for a single-file APK, record the installation receipt and, when the platform exposes a stable byte hash, record it; for split/AAB-derived installs, use the sealed derivation manifest and installed split-set hashes. Then execute the bounded release smoke/instrumentation suite. No rebuild is permitted between signing, hashing, installation, and release smoke. For any other release format, use the project-supported installable derivation without rebuilding the application. For AAB output, record the bundle hash, derivation command/version, generated APK-set manifest and each installed APK hash so the derivation chain is part of the evidence identity.
- [ ] **Step 10: Seal and independently revalidate the evidence bundle.**
- [ ] **Step 11: Derive final states.**
- [ ] **Step 12: Stop immediately if any blocking row is not PASS.**

**Expected result for G8 closure:**

```text
P6-G8-REQ-01 = PASS
P6-G8-REQ-02 = PASS
P6-G8-REQ-03 = PASS
P6-G8-REQ-04 = PASS
ARCHITECTURE_COMPLETE = PASS
IMPLEMENTATION_COMPLETE = PASS
VERIFIED = PASS
PRODUCTION_READY = PASS
```

`PRODUCTION_READY = PASS` is an evidence state only. The later independent final audit and production authorization decision remain outside G8.

---
# 16. Task G8-12 — Final Adversarial / Frozen-Spec Review of the Certification Boundary

**Purpose:** Prove that G8 itself has not introduced authority drift or evidence bypasses.

**Files:**
- Inspect/verify: `contract/g8_adversarial_checks.yaml`
- Inspect: `docs/authority/*`
- Inspect: `contract/*`
- Inspect: `scripts/g8_certify.py`
- Inspect: `scripts/production_gate.sh`
- Inspect: `scripts/production_gate.ps1`
- Inspect: final evidence bundle

- [ ] **Step 1: Confirm no G8 code is imported by production runtime code.**
- [ ] **Step 2: Confirm G8 cannot mutate Room/Firebase business state.**
- [ ] **Step 3: Confirm G8 cannot convert a report or manifest into authority without executable evidence.**
- [ ] **Step 4: Confirm all required current tests are still executable and mapped.**
- [ ] **Step 5: Confirm forbidden architectural patterns remain absent.**
- [ ] **Step 6: Confirm G8 does not create a generic governance/compliance registry.**
- [ ] **Step 7: Confirm final evidence remains bound to `PRODUCT_ARTIFACT_ID` and not a prior artifact.**
- [ ] **Step 8: Confirm any failure results in `NOT_READY_FOR_CLOSURE`/`FAIL`, never implicit PASS.**

**Exit criteria:** No G8-specific authority or bypass is found.

---

# G8 Adversarial Proof-Binding Rule — Mandatory

Every mandatory `G8-ADV-*` row in `contract/g8_adversarial_checks.yaml` MUST resolve to exactly one executable proof binding during implementation. Each row contains a deterministic `proof_target_id` using the form `g8-adv-proof-<ID>`. `proof_target_id` values MUST be unique across all mandatory `G8-ADV-*` rows, and each such binding identity MUST belong to exactly one adversarial row even when implementation code is shared internally. Each row MUST also define a deterministic `proof_result_id` and `primary_evidence_artifact_id`. The implementation MUST create or identify an executable fixture, validator, mutation, or enforcement check matching that `proof_target_id`, produce the corresponding proof result, and record a primary hashed evidence artifact matching `primary_evidence_artifact_id` in the sealed run bundle. A mandatory adversarial row with no executable proof binding, duplicate proof binding, no execution result, no primary evidence reference, or an evidence artifact whose SHA-256/producer identity does not match the sealed run is a blocking failure. The human-readable adversarial checklist is only a rendered representation of the machine-readable source.

# 17. G8 Adversarial Checklist — Mandatory

The final certification run must answer these with executable evidence; **79 total mandatory adversarial checks**. The canonical machine source is `contract/g8_adversarial_checks.yaml`; the numbered list below is its rendered human-readable representation. The verifier MUST recompute the count from that YAML and reject any mismatch between machine count, rendered list, and declared count:

1. Can a historical test manifest produce PASS when the current test file is missing?
2. Can a stale JUnit result from source X be accepted against `PRODUCT_ARTIFACT_ID`?
3. Can a changed source file leave the source manifest unchanged?
4. Can a changed test file leave the test-corpus identity unchanged?
5. Can an unmapped current test file be silently ignored?
6. Can `NO-SOURCE` produce PASS?
7. Can a skipped mandatory test produce PASS?
8. Can an instrumentation suite be unavailable yet counted as PASS?
9. Can a report-only PASS be accepted without execution results?
10. Can a vacuous assertion fixture satisfy a mandatory test?
11. Can a debug-signed artifact satisfy the release gate?
12. Can an unsigned artifact satisfy the release gate?
13. Can an artifact hash be changed after evidence sealing without detection?
14. Can a different release artifact be paired with the same source identity?
15. Can contract files change after evidence collection without invalidating the bundle?
16. Can a different Gradle/JDK/Android environment be silently represented as the same evidence identity?
17. Can secrets appear in evidence, logs, reports, screenshots, or checkpoints?
18. Can a malformed evidence bundle produce a PASS state?
19. Can the renderer manufacture `CLOSED`/`PASS` without machine evidence?
20. Can G8 be imported into production runtime code?
21. Can G8 write to Room, Firebase, or business state?
22. Can G8 introduce a runtime governance registry?
23. Can one current certification run mix unit results from artifact identified by `PRODUCT_ARTIFACT_ID` with instrumentation results from artifact X?
24. Can final evidence reference a prior phase artifact after a later shared-surface change?
25. Can the verifier pass when a mandatory G1–G7 blocking requirement has no final executable evidence?
26. Can a failed release signing configuration be bypassed with `--dry-run`, debug signing, or a placeholder key?
27. Can report formatting alter the machine-derived certification state?
28. Can a malformed historical provenance record override current executable evidence?
29. Can a hardcoded historical certification-test list allow a newly required current test to remain unexecuted?
30. Can the production gate select a system Gradle installation different from the project wrapper and still claim reproducible certification?
31. Can a compliance matrix report fixed historical requirement counts/statuses instead of current `phase_requirements.yaml` evidence?
32. Can baseline manifest hashes satisfy current-artifact identity without a current source/test manifest?
33. Can a failed certification run be overwritten by a later run using the same final source SHA?
34. Can G8 implementation files change after `PRODUCT_ARTIFACT_ID` is declared while the verifier still treats `PRODUCT_ARTIFACT_ID` as unchanged?
35. Can a correctly signed but unauthorized certificate fingerprint pass `PRODUCTION_READY`?
36. Can a signed release artifact pass without the exact signed artifact being installed/launched/tested?
37. Can an unexpected production source/control-plane file be added within the sealed `PRODUCT_ARTIFACT_ID` without being covered by the authorized Phase-1–5/G8 change inventory?
38. Can a PASS requirement exist with no exact executable evidence reference?
39. Can a timed-out certification command leave child processes running and contaminate the next certification run?
40. Can the frozen INV-16 certification-suite names differ between authority text, canonical definition, test map, and disk without blocking?
41. Can `required_behavior_tests` silently substitute for the frozen INV-16 certification suites?
42. Can duplicate governance identifiers such as ADR IDs remain unresolved while the certification verifier claims authoritative consistency?
43. Can an existing evidence directory for the same `PRODUCT_ARTIFACT_ID` be silently reused rather than creating a fresh certification run identity?
44. Can the verifier infer the expected production signing certificate fingerprint from the artifact being verified?
45. Can final certification combine product `PRODUCT_ARTIFACT_ID` with certification-infrastructure scripts/contracts from a later revision?
46. Can a release smoke/instrumentation PASS be obtained from a debug artifact while the signed release artifact was never executed?
47. Can any PASS row point only to a rendered report rather than a hashed executable evidence artifact?
48. Can G8 execution start before final G1–G7 closure, final-artifact sweep, and integrated Phase-1–5 adversarial review are complete?
49. Can frozen authority or frozen ADR text be edited by G8 merely to manufacture certification-suite consistency?
50. Can the exact signed release artifact be executed against an unapproved production endpoint, real subscriber, or real financial/credential mutation path during certification?
51. Can the trusted expected production certificate fingerprint be derived from the artifact under test rather than supplied independently?
52. Can a certification run reuse or overwrite an existing run directory or run ID?
53. Can adding G8-only certification tests invalidate G1–G7 product evidence solely because the overall test-corpus hash changed?
54. Can G8 certification evidence combine a matching `PRODUCT_ARTIFACT_ID` with a different/unknown `CERTIFICATION_ARTIFACT_ID`, or vice versa?
55. Can the exact signed release artifact reach a real production Firebase project or ISP endpoint despite the declared certification environment?
56. Can stale app data, authentication state, or a prior debug installation contaminate the exact signed release smoke run?
57. Can a missing test-result directory, malformed result file, or collector parse warning be treated as successful execution?
58. Can the production gate choose system Gradle instead of the project wrapper while still claiming reproducible certification?
59. Can a correctly signed APK with the wrong application ID/version/variant pass `PRODUCTION_READY`?
60. Can the final G8 verifier still read legacy `evidence/<source_sha>/closure_bundle.json` as current proof?
61. Can G8 alter a product/runtime contract after G1–G7 closure and continue using the old upstream evidence snapshot without forcing a new closure?
62. Can an artifact/environment certificate fingerprint or endpoint allowlist be self-derived from the artifact or from an untrusted declaration rather than an independent trusted input?
63. Can `g8_certification_scope.yaml` authorize its own creation or classification without an independent `G8_BOOTSTRAP_*` root of trust?
64. Can `g8_certification_test_matrix.yaml` reduce, omit, or silently weaken a bootstrap-mandated certification suite after activation?
65. Can a certification overlay change the effective `PRODUCT_BUILD_INPUT_MANIFEST_ID` without invalidating the product/certification identity relationship?
66. Can an upstream G1–G7 PASS remain accepted without the mandatory fresh full-product re-execution through the hardened G8 gate?
67. Can a generic or combined source manifest be substituted for a domain-specific product or certification manifest without failing identity validation?
68. Can a certification environment reach any external destination not explicitly present in the approved certification allowlist?
69. Can a missing certification suite be treated as a blocking error before the G8 step authorized to create that suite can execute?
70. Can a network-sensitive test execute before its machine-readable environment_sensitivity classification and approved environment enforcement are present?
71. Can the frozen authority snapshot be self-hashed from a modified authority bundle and still be accepted as the trusted authority identity?
72. Can an unsigned or unauthenticated G8 bootstrap record authorize its own scope/matrix/fingerprint inputs?
73. Can network-capable instrumentation execute before the certification environment allowlist is mechanically enforced?
74. Can an upstream or G8 test that requires external access silently bypass the approved destination allowlist?
75. Can the external bootstrap verifier hash refer to a future G8 verifier that has not yet been created, creating a bootstrap deadlock?
76. Can `g8_certification_test_matrix.yaml` reduce the mandatory execution set because the matrix itself is treated as the source of required tests?
77. Can a network-capable test be classified `OFFLINE_SAFE` by declaration alone and execute before environment enforcement?
78. Can a broad Gradle task execute G8-only certification suites while the evidence is labeled `PRODUCT_TEST_CORPUS`?
79. Can G8 recollect or overwrite upstream G1–G7 closure evidence instead of consuming the sealed `UPSTREAM_CLOSURE_SNAPSHOT_ID`?

All 79 must have executable negative proof or explicit invariant enforcement; any failure blocks G8.

---

# 18. G8 Evidence Bundle Contents

One certification run must produce one bundle containing at minimum:

```text
PRODUCT_ARTIFACT_ID + product source/runtime manifest + SHA-256
CERTIFICATION_ARTIFACT_ID + certification-infrastructure manifest + SHA-256
PRODUCT_TEST_CORPUS_ID + product test corpus manifest + SHA-256
CERTIFICATION_TEST_CORPUS_ID + certification test corpus manifest + SHA-256
UPSTREAM_CLOSURE_SNAPSHOT_ID
CERTIFICATION_RUN_ID + creation metadata
G8_BOOTSTRAP_SCOPE_ID + G8_BOOTSTRAP_PLAN_ID + G8_BOOTSTRAP_PLAN_SHA256 + G8_BOOTSTRAP_VERIFIER_ID + G8_BOOTSTRAP_VERIFIER_SHA256 + bootstrap input hashes + external approval identity
FROZEN_AUTHORITY_SNAPSHOT_SHA256 + TRANSITION_GUIDANCE_SNAPSHOT_SHA256 + G8_BOOTSTRAP_AUTHENTICITY_ID + external trust/approval identity
PRODUCT_BUILD_INPUT_MANIFEST_ID + product build-input manifest SHA-256
PRODUCT_RELEASE_INPUT_ID + release-input closure manifest SHA-256
historical provenance reference where required
contract hashes
invariant/test-map identities
toolchain identity
resolved dependency/plugin graph identity
execution-environment identity
unit/Robolectric result summaries
instrumentation result summaries
structural/adversarial results
verified-runner metadata
release artifact path
release artifact SHA-256
release signing identity/fingerprint
machine-derived states
closure result
failure/block reasons when not closed
```

No secrets are permitted in the bundle.

The human-readable certification report is derived from this bundle and is not authoritative. Likewise, `scripts/production_gate.sh` and `.ps1` are execution orchestrators only; their printed PASS banners must never be consumed as certification truth.

---

# 19. G8 Definition of Done

G8 is complete only when all are true:

- [ ] `P6-G8-REQ-01` PASS.
- [ ] `P6-G8-REQ-02` PASS.
- [ ] `P6-G8-REQ-03` PASS.
- [ ] `P6-G8-REQ-04` PASS.
- [ ] External verifier runs independently of production runtime.
- [ ] Product artifact manifest and certification artifact manifest are generated from their exact domains and verified.
- [ ] `PRODUCT_TEST_CORPUS_ID` is generated from actual product source and completely mapped.
- [ ] `CERTIFICATION_TEST_CORPUS_ID` is generated from actual certification source/matrix and completely mapped.
- [ ] INV-16 canonical certification-suite identity is reconciled across authority text, canonical contract, test map, matrix, and disk.
- [ ] No duplicate/conflicting governance identifier remains unresolved in the certified control plane.
- [ ] All required JVM/Robolectric tests execute successfully.
- [ ] All required instrumentation tests execute successfully on real supported instrumentation.
- [ ] All required adversarial/meta-gate fixtures execute successfully.
- [ ] 0 failures, 0 errors, 0 skips, 0 NO-SOURCE for required suites.
- [ ] G8 self-tests detect all required known-bad evidence mutations.
- [ ] Every executable test/suite has a machine-readable `environment_sensitivity` classification and no network-sensitive suite runs before environment enforcement.
- [ ] Evidence identity includes `PRODUCT_ARTIFACT_ID`, `CERTIFICATION_ARTIFACT_ID`, `PRODUCT_TEST_CORPUS_ID`, `CERTIFICATION_TEST_CORPUS_ID`, `UPSTREAM_CLOSURE_SNAPSHOT_ID`, toolchain, contracts, and required execution environment.
- [ ] Release Certification Environment Contract is machine-enforced, not declaration-only; production endpoints are mechanically denied or an approved non-production endpoint is mechanically allowed.
- [ ] Exact signed artifact is installed only after a clean device/emulator reset and its deployed identity/hash is verified.
- [ ] No historical report/manifests are accepted as current proof.
- [ ] Final release artifact is actually built, SHA-256 identified, signed with the trusted production certificate, and the exact signed artifact is executed/validated.
- [ ] The exact signed artifact is executed only against the approved Release Certification Environment Contract; no implicit production subscriber/financial/credential mutation is permitted.
- [ ] Release signing is verified fail-closed under `INV-15`.
- [ ] Evidence contains no secrets.
- [ ] Every PASS row/state has at least one exact hashed executable evidence reference in the immutable certification run bundle.
- [ ] No certification run can overwrite or replace a previous run bundle.
- [ ] Final bundle is independently revalidated after sealing.
- [ ] `ARCHITECTURE_COMPLETE`, `IMPLEMENTATION_COMPLETE`, `VERIFIED`, and `PRODUCTION_READY` are machine-derived.
- [ ] Production authorization remains a later external decision.

---

# 20. Explicit Non-Goals

G8 must not:

- implement or modify business/runtime architecture merely to satisfy certification;
- add a runtime certification flag or registry;
- add a generic compliance database;
- add a synchronization/reconciliation state machine;
- replace executable tests with report parsing alone;
- treat historical manifests as current source/test identity;
- weaken or rewrite frozen certification suites to obtain PASS;
- infer production authorization from G8 alone;
- silently convert missing instrumentation into a skip/pass;
- use debug signing as release fallback;
- create permanent generated evidence clutter under `docs/superpowers/evidence/`.

Temporary run evidence should remain under the designated external/current certification evidence output location and be retained only when it has genuine project value.

---

# 21. Required STOP Conditions

Stop G8 immediately and do not issue a certification PASS if any of these occurs:

```text
external verification capability required by an upstream invariant is absent
final source artifact identified by `PRODUCT_ARTIFACT_ID` changes after certification freeze
a current test is unmapped
required instrumentation is unavailable
required test reports are missing/stale
NO-SOURCE is detected
any mandatory test is skipped
any failure/error exists
release signing fails
release artifact is missing or hash-mismatched
contract hash changes after evidence collection
source/test corpus identity mismatch
filesystem manifest contains symlink/junction escape, duplicate canonical path, case-colliding path, or path resolving outside the authorized root
secret leakage is detected
known-bad verifier mutation is not detected
forbidden architecture pattern is detected
INV-16 certification-suite identity drift is detected
duplicate/conflicting governance identifiers remain unresolved
verifier depends on production runtime
historical evidence is used as current proof
product/certification identity domains are mixed or missing
upstream closure snapshot contract identity has changed after activation
G8 bootstrap scope/matrix/path/plan/verifier root-of-trust is missing, stale, modified, downgraded, or self-derived
FROZEN_AUTHORITY_SNAPSHOT_SHA256 mismatch or external authority-trust input is missing
G8_BOOTSTRAP_AUTHENTICITY_ID is missing or cannot be independently verified
G8_BOOTSTRAP_PLAN_SHA256/ID does not match the approved plan bytes used for execution
G8_BOOTSTRAP_VERIFIER_SHA256/ID does not match the externally supplied bootstrap verifier bytes
G8 attempts to use the future G8 verifier as its own bootstrap verifier
mandatory certification execution set is derived from the mutable G8 matrix alone
environment_sensitivity is unknown, non-conservative, or downgraded to OFFLINE_SAFE without executable justification
network-bound instrumentation is attempted before the Release Certification Environment Contract is mechanically enforced
G8 certification matrix is weaker than the immutable bootstrap minimum
adversarial checklist declared count does not equal the machine-derived checklist count (79)
PRODUCT_BUILD_INPUT_MANIFEST_ID or PRODUCT_RELEASE_INPUT_ID differs between Product Release Workspace and Certification Overlay Workspace
hermetic build boundary is violated: unapproved Gradle init script, global/user injection, alternate repository/configuration, or wrapper distribution integrity mismatch
full current PRODUCT_TEST_CORPUS revalidation through the hardened G8 gate is missing or incomplete
activation record incorrectly requires CERTIFICATION_TEST_CORPUS_ID or on-disk G8 suite existence before G8-00 creation authorization
G8-00 attempts to repair upstream INV-16 machine-contract drift instead of stopping
final sealed G8-11 run does not execute the complete PRODUCT_TEST_CORPUS and complete CERTIFICATION_TEST_CORPUS
release certification environment is declaration-only or production endpoint access is not mechanically denied
stale app/device state contaminates exact-artifact execution
mandatory result directory is missing, empty, malformed, or result parsing reports an error/warning that could hide absence
legacy evidence path is accepted as current G8 proof
product/certification execution selectors overlap or a broad test task silently crosses domain boundaries
G8 attempts to recollect or overwrite the sealed upstream G1–G7 closure snapshot
release package/version/variant identity does not match the intended artifact
```

The correct action is to preserve evidence, report the exact blocker, and stop. Do not reinterpret the frozen authority to manufacture a PASS.

---

# 22. Internal Review Protocol Applied to This G8 Plan

## Draft

Complete G8 implementation plan written against the exact current authority, contract, scripts, test environment, and current source/test corpus.

## Primary Review

Checked:

- every `P6-G8-REQ-01..04` has implementation, verification, evidence, and closure coverage;
- exact current paths are named;
- the existing closure collector/verifier remain the authoritative verifier for the sealed upstream G1–G7 closure snapshot, while G8-specific evidence is owned and verified by the G8 certification verifier;
- product and certification test-corpus identities are derived from current source rather than historical manifests;
- release signing and release artifact proof are executable;
- INV-16 certification-suite identity is checked across authority text, canonical contract, test map, matrix, and disk;
- final artifact freeze occurs only after all G8 infrastructure changes and self-tests are complete;
- certification runs are immutable and non-overwriting;
- exact signed release artifact execution and trusted certificate fingerprint are required for `PRODUCTION_READY`;
- instrumentation is treated as real evidence;
- G8 remains external to runtime.

## Secondary Review

Challenged:

- stale evidence acceptance;
- source/test manifest drift;
- mixed-artifact evidence;
- test skipping and NO-SOURCE masking;
- report-only PASS;
- verifier dependency on production code;
- debug-signing fallback;
- secret leakage;
- historical ZIP/report authority drift.

## Adversarial / Frozen-Spec Review

Confirmed that the plan does not:

- reopen the frozen architecture;
- add rejected mechanisms;
- turn certification into runtime governance;
- use G8 to compensate for an upstream G1–G7 defect;
- treat `PRODUCTION_READY` as production authorization;
- accept narrative PASS evidence.

## Higher-Order Destruction Review

An additional independent review was applied after the prior reviewer rounds, specifically targeting bootstrap self-authorization, activation deadlocks, final-vs-candidate test execution ordering, schema/contract domain confusion, shared-tool reinterpretation, stale-device contamination, and 480-second packet feasibility. Findings closed before release of this plan included:

- `G8_BOOTSTRAP_PLAN_ID` / `G8_BOOTSTRAP_PLAN_SHA256` and verifier identity are now part of the external bootstrap root of trust.
- Activation no longer requires `CERTIFICATION_TEST_CORPUS_ID` or on-disk certification-suite existence before G8-00 is authorized to create them.
- Upstream INV-16 mismatch is a precondition failure; G8-00 cannot repair upstream machine-contract drift.
- `closure_schema.json` (upstream) and `g8_closure_schema.json` (G8) are explicitly separated.
- Pre-freeze G8-05 execution is limited to `OFFLINE_SAFE` suites; the sealed G8-11 final run executes the complete product and certification corpora after environment enforcement.
- G8-11 is divided into bounded sub-packets to preserve the 480-second agent execution contract.

## Final Requirements Check

Verified coverage of:

```text
P6-G8-REQ-01
P6-G8-REQ-02
P6-G8-REQ-03
P6-G8-REQ-04
INV-15
INV-16
INV-17
G8 external-only boundary
exact current-artifact evidence binding
full product test corpus plus full G8 certification test corpus
real instrumentation
release artifact proof
machine-derived states
fail-closed self-tests
```

## Higher-Order Review — Additional Findings Beyond Reviewer 1 and Reviewer 2

A fresh review was performed after the external reviews, deliberately treating the possibility that both reviewers could share blind spots. The following additional controls were therefore added to this version:

1. **Upstream contract mutation hazard:** G8-00 previously risked changing machine-contract surfaces that were part of G1–G7 evidence. The plan now freezes an `UPSTREAM_CLOSURE_SNAPSHOT_ID`, uses a G8-only certification contract overlay, and explicitly forbids G8 from changing any input included in `PRODUCT_ARTIFACT_ID` without reopening Phase-1–5 closure.
2. **Collector/verifier legacy-path hazard:** the current repository collectors hardcode `evidence/<source_sha>/closure_bundle.json` and reference baseline manifests. G8 now requires run-scoped evidence paths and rejects legacy-path evidence as current proof.
3. **False PASS on missing/malformed test results:** the current `production_gate.sh` only checks JUnit failures if a result directory exists, and current XML parsing can warn on parse errors. G8 now requires missing result directories and parse errors to be blocking.
4. **System-Gradle reproducibility hazard:** the current `production_gate.sh` prefers a system `gradle` before `./gradlew`. G8 now requires wrapper-only certification execution.
5. **Exact deployed artifact hazard:** hashing an APK before `adb install` is not enough if stale application state or a different installed artifact is present. G8 now requires clean-device state plus deployed package/version/hash verification.
6. **Production-endpoint hazard discovered from the current source:** `EarthlinkNetwork.kt` contains the production `rapi.earthlink.iq` endpoint and the current Firebase configuration points at the production project. G8 now includes a real environment-build/enforcement task and a hard feasibility STOP if safe isolation cannot be established.
7. **Release identity substitution hazard:** a correctly signed but wrong package/version/variant artifact must not pass. G8 now records and verifies release identity fields.
8. **Certification-only corpus contamination:** G8-only test creation changes `app/src/test/**`; G8 therefore maintains separate product and certification test-corpus identities instead of invalidating upstream product evidence by hash accident.
9. **Self-authorizing scope/matrix hazard:** G8 now requires an independent activation bootstrap root for scope and mandatory-suite identity; G8-created scope/matrix files cannot authorize their own creation or reduce their mandatory set.
10. **Upstream verifier trust hazard:** G8 does not merely hash old G1–G7 evidence; it freshly re-executes the full current product test corpus and required executable invariant/structural fixtures through the hardened G8 gate so known upstream gate defects cannot remain trusted solely because they were produced earlier.
11. **Effective build-input leakage:** G8 now derives `PRODUCT_BUILD_INPUT_MANIFEST_ID` and proves that the certification overlay does not change the effective release build inputs.
12. **Environment over-permissiveness:** release certification now requires an explicit destination allowlist, not denylist-only control.
13. **G8-vs-product artifact conflation:** G8 scripts/tests/contracts may change after Phase-5 without changing the runtime product. G8 therefore separates `PRODUCT_ARTIFACT_ID` from `CERTIFICATION_ARTIFACT_ID` and binds the final run to both.

These controls are process/evidence safeguards only; they do not introduce or modify the frozen product architecture.

14. **Activation deadlock:** pre-activation state no longer requires certification-test files or `CERTIFICATION_TEST_CORPUS_ID`; those are created only after G8-01 authorization.
15. **Bootstrap-verifier deadlock:** the external bootstrap verifier is distinct from the G8 verifier created later; the future G8 verifier is never trusted as its own bootstrap root.
16. **Mandatory-execution self-authorization:** the G8 matrix cannot reduce required certification coverage because the mandatory execution set is derived from immutable bootstrap + frozen requirement/test-map bindings.
17. **Sensitivity-classification downgrade:** unprovable offline safety defaults to externally sensitive handling and cannot be downgraded by declaration alone.
18. **Corpus/execution overlap:** product and certification corpus IDs are now backed by execution-disjoint selector sets; broad Gradle discovery cannot silently merge the domains.
19. **Upstream snapshot contamination:** final G8 consumes and verifies the immutable upstream closure snapshot rather than recollecting it through the known legacy collector.
20. **Pre-freeze/final execution contradiction:** G8-05 exit criteria now explicitly describe only eligible pre-freeze execution; complete product + certification execution is reserved for sealed G8-11 after environment enforcement.

## Final Plan Decision

**G8 PLAN STATUS: FINAL INTERNAL CONSISTENCY CHECK PASSED — READY FOR IMPLEMENTATION WHEN ACTIVATED.**

This document is **LOCKED until the G8 Activation Gate passes**. The presence of a ready G8 plan before Phase 1–5 execution is intentional; it is not authorization to execute G8 early.

This document is a certification implementation plan only. It does not authorize production and does not replace the later independent final audit/production authorization decision.

## Final Hardening Record
- Product identity inheritance hardening: G8-FREEZE re-seals the immutable Phase-5 `PRODUCT_ARTIFACT_ID` and never creates a replacement product identity.
- Canonical adversarial checklist: `contract/g8_adversarial_checks.yaml` with declared count 79; rendered checklist must match exactly.
- Domain membership precedence: explicit ownership overrides wildcard discovery; ambiguous path classification is blocking.
- Canonical mandatory execution derivation: product/certification mandatory execution sets are derived through one named algorithm and sealed as `MANDATORY_EXECUTION_DERIVATION_ID`.
- Higher-Order Release/Build/Filesystem Hardening Round: product release identity composition formalized; candidate/final environment binding separated; filesystem canonicalization/link/case controls added; hermetic Gradle build boundary added; Frozen Authority and Transition Guidance snapshots separated.
