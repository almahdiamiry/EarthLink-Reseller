# Contributing Guidelines

This document outlines the workflow, standards, and authority hierarchy for contributors and AI agents working on the Earthlink Reseller App.

---

## Authority Hierarchy

1. **Frozen Product / Architecture Authority**: `docs/authority/` bundle is authoritative over historical documentation, roadmaps, and ADRs.
2. **Implementation State**: Current source code and configuration determine implementation state, not documentation claims.
3. **Verification State**: Executable automated tests and machine verification evidence determine verification state.

```text
Frozen product/architecture requirements outrank historical repository guidance.
Current source determines implementation state, not product authority.
Executable evidence determines verification state.
```

---

## Development Workflow

1. **Task Assignment**: Execute the active approved phase plan specified by the user.
2. **Authority Check**: Verify requirements against `docs/authority/`.
3. **Implementation**: Modify only the minimal necessary files within the change allowlist.
4. **Verification**: Run unit/integration tests (`gradle :app:testDebugUnitTest`) and invariant verification scripts.
5. **Documentation**: Record changes in `CHANGELOG.md`.
