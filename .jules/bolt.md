## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## Fast-Path Zero-Allocation String Normalization in UI Date Formatting

**Learning:** Sequential `String.replace` calls inside string normalization functions (e.g., `normalizeArabicPersianDigits`) scan the string multiple times and allocate intermediate `String` objects on every call, even when no digits need conversion. When executed per item during Jetpack Compose list scrolling (such as rendering subscriber cards), this creates avoidable garbage collection allocations.
**Action:** Perform a fast linear check for characters requiring normalization before allocating a `StringBuilder`. Return the input string directly if no conversion is needed, and perform single-pass replacement when conversion is necessary.
