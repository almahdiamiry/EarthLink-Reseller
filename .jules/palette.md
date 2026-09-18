## 2026-05-02 - Icon-Action Semantic Alignment in Dialog Top Bars
**Learning:** In Compose UI, action buttons in dialogs or cards may sometimes copy code or icon assets from other toolbars (e.g., using `Icons.Default.Settings` instead of `Icons.Default.ContentCopy`), leading to screen-reader or visual mismatches where `contentDescription` says "Copy message text" but the icon displays a gear/settings image.
**Action:** Always visually align icon vector graphics with their corresponding `contentDescription` and click handlers in Compose UI screens.

## 2026-09-18 - Localized Accessibility Content Descriptions in Reusable UI Cards
**Learning:** In Jetpack Compose reusable UI card composables (e.g. `ArabicSubscriberCard`), hardcoding English strings in `contentDescription` (such as `"Verified subscriber"`) causes TalkBack to read English descriptions when the app or device is set to Arabic.
**Action:** Always condition accessibility `contentDescription` strings on the UI language parameter (e.g., `if (lang == "ar") "مشترك موثق" else "Verified subscriber"`).
