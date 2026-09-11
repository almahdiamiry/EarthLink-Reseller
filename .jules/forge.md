## 2026-09-11 - Gradle Wrapper Prioritization in Production Gate
**Problem:** `scripts/production_gate.sh` checked `command -v gradle` before `./gradlew`, causing build failures in environments with older system Gradle installed (e.g. Gradle 8.8 vs AGP 9.1.1 requiring 9.3.1+).
**Learning:** Production gate scripts must check and prioritize `[ -f "./gradlew" ]` with `bash ./gradlew` over system `gradle` to ensure execution uses the project-mandated Gradle wrapper version.
