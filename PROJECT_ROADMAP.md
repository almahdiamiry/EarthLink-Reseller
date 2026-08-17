# Project Roadmap

## 1. Implementation Authority & Alignment

This roadmap tracks the single canonical implementation sequence defined in `docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md` and the G1-G8 Consolidated Architecture.

---

## 2. Canonical Phase Sequence

- [x] **Phase 0 — Repository / Documentation / Governance Alignment** (COMPLETED)
  - Discard corrupted Git baseline and establish clean forensic baseline.
  - Vendor the 5 frozen Implementation Reference Bundle artifacts under `docs/authority/`.
  - Rebase human governance and decision documentation.
  - Reconcile invariant definitions and machine verification contracts.
  - Establish bounded verification scripts and remove local YAML shim regression.
- [ ] **Phase 1 — G2 / Transport Hardening & G1 Durability Lane**
  - G2 Transport resilient network execution and interceptor security.
  - G1 Durability verification lane.
- [ ] **Phase 2 — G3 / Restore & Import Reconciliation**
  - G3 deterministic database restore & uTower import pipeline.
- [ ] **Phase 3 — G4 / Concurrency & Lineage Protection**
  - G4 mutation locking, token lifecycle, and outbox sequencing.
- [ ] **Phase 4 — G5 / Identity & Credential Management**
  - G5 secure authentication, Keystore fail-closed security, and token caching.
- [ ] **Phase 5 — G6/G7 / Semantics & State Migration**
  - G6/G7 version semantics, delta calculation, and data integrity.
- [ ] **Phase 6 — G8 / Production Certification & Release**
  - Final test matrix certification, release signing, and verification bundle generation.

---

## 3. G1 Architectural Status Tracking

- **G1 Architecture**: CLOSED (Architectural foundation established in `docs/authority/`)
- **G1 Implementation**: OPEN (Tracked in Phase 1 G2/G1 durability lane)
- **G1 Verification**: REQUIRED (Automated test coverage required)
- **G1 Limitation**: ACCEPTED (Documented operational bounds)

---

## 4. Historical Phase Records (Archived Context)

*(Previous developmental iterations and hot-fix cycles are recorded in historical evidence bundles and `CHANGELOG.md`)*
