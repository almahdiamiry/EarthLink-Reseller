## 2026-05-18 - Localized Content Descriptions for Icon-Only TopAppBar Buttons
**Learning:** Icon-only buttons in top app bars often default to hardcoded English content descriptions ("Back", "Refresh", "Share"), which degrades accessibility for screen readers in localized RTL/Arabic contexts.
**Action:** Always check `currentLang` or string resources when defining `contentDescription` on icon-only interactive buttons in Jetpack Compose screens.
