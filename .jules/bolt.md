## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2.2 Exact-Match Fast-Path String Normalization

**Learning:** String normalization functions called in high-frequency list processing or ledger calculations (such as `TransactionTypeNormalizer.normalizeTransactionType`) allocate temporary uppercase strings via `.trim().uppercase()` even when passed standard canonical string literals or common upper-case constants.
**Action:** Provide an exact-match `when` fast-path for expected canonical string literals and common upper-case string inputs to return constant strings directly, bypassing temporary string allocations and set lookups in hot execution paths.
