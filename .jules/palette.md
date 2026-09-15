## 2026-05-02 - Icon-Action Semantic Alignment in Dialog Top Bars
**Learning:** In Compose UI, action buttons in dialogs or cards may sometimes copy code or icon assets from other toolbars (e.g., using `Icons.Default.Settings` instead of `Icons.Default.ContentCopy`), leading to screen-reader or visual mismatches where `contentDescription` says "Copy message text" but the icon displays a gear/settings image.
**Action:** Always visually align icon vector graphics with their corresponding `contentDescription` and click handlers in Compose UI screens.

## 2026-05-03 - Localized Accessibility Descriptions and Decorative Icons
**Learning:** Interactive icon buttons with hardcoded English or Arabic `contentDescription` strings break screen-reader localization for TalkBack users in multi-language environments. Furthermore, icons placed immediately adjacent to text labels inside rows or containers cause duplicate announcements if given non-null `contentDescription`s.
**Action:** Ensure interactive icon controls dynamically derive `contentDescription` from the current language context (e.g. `if (lang == "ar") ... else ...`) and set `contentDescription = null` for purely decorative icons adjacent to text labels.
