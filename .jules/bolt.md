## 2026-03-30 - Pre-compiled Regex Allocation Optimization in Hot Parsing Paths
**Learning:** Instantiating `Regex("[^0-9.-eE]")` on every `MoneyParser.parseAmount()` call allocates new `Regex` objects during bulk JSON field extractions. Pre-compiling `NON_NUMERIC_REGEX` as a private constant eliminates redundant heap allocations and regex compilation overhead without affecting parsing output or readability.
**Action:** Always pre-compile static regular expressions used inside loops or high-frequency parser methods into private constants.
