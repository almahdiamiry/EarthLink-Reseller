# AGENTS.md

## CRITICAL STATUS: G1 / WAVE 1 IS PERMANENTLY CLOSED & LOCKED

> **DO NOT REOPEN G1 / WAVE 1 UNDER ANY CIRCUMSTANCES.**
>
> - **G1 Status:** CLOSED & LOCKED
> - **Step 1:** CLOSED
> - **Step 2:** CLOSED
> - **Step 3 Implementation:** CLOSED
> - **Step 3 Adversarial Certification:** GO / PASSED
> - **C06 (Active DISPATCHING sweep isolation):** CLOSED (`test18_sweepIgnoresInFlightProductionDispatch`)
> - **C12 (Cancellation after claim preserves claim count):** CLOSED (`test19_cancellationAfterClaimPreservesClaimCount`)
> - **Production Changes:** NONE
> - **Current G1 Production Defects:** NONE
> - **Current G1 Requirements:** NONE
> - **Current G1 Evidence Gaps:** NONE
> - **Architecture:** FROZEN / NOT REOPENED

---

## STRICT AGENT DIRECTIVE FOR G1

1. **NO REOPENING**: Any future agent working on this codebase MUST NOT reopen Phase G1, Wave 1, Step 1, Step 2, or Step 3.
2. **NO PRODUCTION MODIFICATIONS**: Do not modify any production code related to G1 durable dispatch or synchronization.
3. **NO TEST DELETION / WEAKENING**: Do not alter, delete, skip, or weaken `test18_sweepIgnoresInFlightProductionDispatch`, `test19_cancellationAfterClaimPreservesClaimCount`, or any existing certification test.
4. **FROZEN BOUNDARY**: All G1 requirements are 100% satisfied with machine execution proof.

---

## Active Implementation Entry Point

1. Read `AGENTS.md` first.
2. Respect `docs/authority/Target Product Contract v0.6.md` as product/business authority.
3. Respect `docs/authority/G1-G8 Consolidated Architecture Summary.md` as engineering interpretation.
4. Respect `docs/authority/Final Independent Adjudication Memo.md` as final architectural judgment / implementation boundary.
5. Use executable tests as verification proof.

---

## Mandatory Non-Negotiable Invariants

### 1. No Deleting, Weakening, or Skipping Tests
- NEVER delete or skip tests.
- NEVER modify or weaken assertions in certification tests.

### 2. One State, One Authority
- Every synchronized state has one authoritative meaning, version domain, mutation policy, and synchronization path.

### 3. Remote Version Semantics
- Server version MUST be represented explicitly and compared only against the same semantic version domain.

### 4. Fail-Closed Security
- Fail closed immediately on credential, keystore, or security checks.
