# Lesson Learned: Verification Governance

**Identifier:** `LL-VERIFICATION-GOVERNANCE`
**Status:** Permanent engineering lesson; non-authoritative.

## Durable rules

1. **Narrative is not closure.** A plan, report, screenshot, or agent-declared PASS is historical context only. Closure requires executable evidence mapped to the governing requirement set.
2. **Verify the real production path.** A test that exercises an isolated helper, signature, or structural pattern is not sufficient when the requirement concerns an integrated production pathway.
3. **Keep verification contracts synchronized with the filesystem.** Renames, moves, and removals of test assets must update their machine-readable verification contracts atomically.
4. **Fail closed on invalid execution.** Verification commands require bounded execution, non-zero failure handling, and explicit rejection of `NO-SOURCE` or equivalent empty/invalid evidence.
5. **Never weaken certification tests to fit implementation behavior.** A failing certification or regression test is evidence of a defect until production behavior is proven correct.
6. **One active implementation path.** Historical plans must never be used to select new work. The active agent path is `AGENTS.md` → explicitly named current-phase plan → frozen authority → current source → executable evidence.

## Why this exists

Earlier development cycles accumulated obsolete verification references and accepted tests that passed while bypassing the production code paths they claimed to certify. These failures are retained here as prevention rules, not as instructions for future implementation.

## Authority boundary

This lesson does not override or duplicate product/architecture authority. Normative authority remains the frozen authority bundle referenced by `AGENTS.md`.
