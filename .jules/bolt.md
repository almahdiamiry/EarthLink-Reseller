## 2025-05-20 - Pre-compiled Regex for Currency Parsing
**Learning:** Instantiating `Regex` dynamically inside `parseAmount()` created unnecessary GC pressure during network payload processing and list iterations. Extracting regex patterns to `private val` pre-compiled constants in Kotlin singletons/companion objects eliminates runtime re-compilation without adding complexity.
**Action:** Always check helper singleton functions processing strings or JSON for inline `Regex(...)` or `toRegex()` calls and extract them to pre-compiled static/object properties.
