# AI Development Guide

This guide defines the principles, architecture patterns, and frozen-target implementation workflow for AI coding assistants working on the Earthlink Reseller application.

---

## Frozen-Target Implementation Workflow

Development must strictly follow the frozen authority chain:
```text
authority → current artifact → implementation plan → executable evidence
```

1. **Authority**: Derive requirements and constraints strictly from `docs/authority/` (Target Product Contract v0.6, G1-G8 Consolidated Architecture Summary, Final Independent Adjudication Memo).
2. **Current Artifact**: Inspect current production Kotlin source and resources to understand real implementation state.
3. **Implementation Plan**: Execute the minimal required scope derived from a dedicated candidate scope assessment routed through `PROJECT_ROADMAP.md`, without improvising unsolicited features or reopening historical roadmaps.
4. **Executable Evidence**: Validate all changes using the Testing Playbook in `AGENTS.md` (proportional Gradle test tasks and targeted verifications).

---

## Architectural Principles (Settled V1 Baseline)

- **Language & Framework**: Kotlin exclusively, Jetpack Compose for UI, Material 3.
- **Local Persistence**: Room SQLite database is the direct authoritative operational store.
- **Synchronization**: Offline-first outbox synchronization with Firebase Firestore. Retryable mutations; no terminal blackhole / dead-letter discarding of user mutations.
- **Security**: Fail-closed encryption, SQLCipher/Room database passphrase protection, secure Android Keystore management.
- **Error Handling**: Graceful error handling and transparent user status; no unhandled crashes.

---

## Development Policy

- Implement exactly one task at a time.
- Never modify tests to force a pass; fix production code defects instead.
- Maintain single source of truth across local database, outbox, and cloud sync.
