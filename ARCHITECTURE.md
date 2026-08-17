# Earthlink Reseller App - Architecture Documentation

> **Status**: Technical architecture reference, subordinate to the Frozen Implementation Authority Bundle in `docs/authority/`.

---

## 1. Authoritative Architecture Reference

For all normative product contracts, architecture boundaries, and synchronization semantics, refer directly to:
1. `docs/authority/Target Product Contract v0.6.md` (Product & Business Authority)
2. `docs/authority/G1-G8 Consolidated Architecture Summary.md` (Engineering Interpretation)
3. `docs/authority/Final Independent Adjudication Memo.md` (Final Architectural Judgment)
4. `docs/authority/EARTHLINK_V1_HANDOVER.md` & `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md` (Transition Guidance)

---

## 2. Superseded Architectural Mechanisms (Historical Notice)

The following architectural mechanisms from earlier iterations are explicitly **SUPERSEDED** by the frozen authority bundle:
- **Terminal `DEAD_LETTER` state**: Superseded. Outbox mutations are retryable and maintain durability; mutations must never be discarded into an unrecoverable blackhole.
- **Staging Database as Default Operational Architecture**: Superseded. The local Room database is the direct authoritative operational store. Staging is not an intermediate mandatory requirement.
- **Global CRUD Ownership Claims**: Superseded. Scoped to the frozen minimum required for local billing and reseller sync.
- **Broad Coordinator / State-Machine Over-Engineering**: Superseded. The system uses a clean, predictable synchronization flow with explicit server-side timestamp semantics.

---

## 3. Active Technical Stack

- **Platform**: Android SDK 24+ (Target SDK 36, Compile SDK 36)
- **UI Framework**: Jetpack Compose (Material 3)
- **Architecture**: MVVM + Repository Pattern
- **Local Persistence**: Room SQLite Database
- **Networking**: Retrofit / OkHttp + Coroutines & Flow
- **Cloud Backend**: Firebase Authentication & Cloud Firestore
- **Background Tasks**: WorkManager
