## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## Fast Character-Scan Bypass for Date String Sanitization

**Learning:** String helper functions called frequently during UI list item rendering or timestamp parsing (such as `sanitizePresentationDateString`) often perform multiple sequential `.replace()` and regex calls. When standard input strings are already clean ASCII, these operations allocate multiple temporary `String` objects per item without performing any actual transformation.
**Action:** Perform a fast linear character scan (`0 until len`) to check if characters requiring stripping/conversion exist before executing string replacements, returning the input string immediately to eliminate intermediate allocations on standard inputs.
