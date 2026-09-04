## 2026-05-02 - Icon-Action Semantic Alignment in Dialog Top Bars
**Learning:** In Compose UI, action buttons in dialogs or cards may sometimes copy code or icon assets from other toolbars (e.g., using `Icons.Default.Settings` instead of `Icons.Default.ContentCopy`), leading to screen-reader or visual mismatches where `contentDescription` says "Copy message text" but the icon displays a gear/settings image.
**Action:** Always visually align icon vector graphics with their corresponding `contentDescription` and click handlers in Compose UI screens.

## 2026-09-04 - Dynamic ContentDescription Localization in Bilingual Screens
**Learning:** In bilingual Compose screens parameterized by `currentLang` (e.g. Arabic `"ar"` vs English `"en"`), hardcoding English `contentDescription` values for `IconButton` actions causes TalkBack and screen readers to announce English button descriptions to Arabic operators.
**Action:** Ensure all `IconButton` and icon action `contentDescription` parameters mirror the screen's language state (`if (currentLang == "ar") ... else ...`).
