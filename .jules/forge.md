## 2026-09-08 - Gradle Wrapper Precedence in Production Gate Scripts
**Learning:** `scripts/production_gate.sh` previously checked system `gradle` before checking local `./gradlew`. On host environments with older or mismatched system Gradle installations (e.g., Gradle 8.8 vs required Gradle 9.3.1 for AGP 9.1.1), executing `production_gate.sh` failed closed.
**Action:** Always prioritize the project wrapper `./gradlew` when checking for available build tools in repository scripts to guarantee build execution using the exact project-mandated Gradle wrapper version.
