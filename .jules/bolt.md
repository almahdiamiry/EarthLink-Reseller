## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2026-10-09 - Fast Linear Character Scan for Date Sanitization in SharedComponents

**Learning:** Unconditionally chaining 15 `.replace()` and `.replace(Regex, ...)` calls on date strings during Jetpack Compose list rendering (`ArabicSubscriberCard`, subscriber filter/sort loops) creates heavy garbage collection pressure, even when inputs are already standard clean ASCII strings.
**Action:** Perform a fast linear character scan (`needsDateSanitization`) to verify whether directional control marks, NBSP, ISO markers, or non-ASCII digits exist before executing string replacements, returning clean ASCII inputs immediately.
