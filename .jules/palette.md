## 2025-08-29 - Localized Accessibility Descriptions for Dialog Icon Buttons
**Learning:** Icon-only buttons inside dialogs or popups may have hardcoded English `contentDescription` text even when the main screen supports bilingual layout (`currentLang == "ar"`).
**Action:** When inspecting UI screens for screen reader accessibility, ensure icon-only buttons inside inner dialogs and top app bars check `currentLang` to supply localized ARIA/content descriptions.
