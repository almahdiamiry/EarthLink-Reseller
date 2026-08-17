# Earthlink Reseller Operations - Android Client (MVP)

An offline-first, secure native Android application designed for Earthlink reseller operators. This system enables local billing, subscriber management, uTower database JSON importing, and background Cloud sync using Firebase Firestore.

---

## Authority & Governance

Project requirements and architecture are governed by the Implementation Reference Bundle in `docs/authority/`:
1. `docs/authority/Target Product Contract v0.6.md`
2. `docs/authority/G1-G8 Consolidated Architecture Summary.md`
3. `docs/authority/Final Independent Adjudication Memo.md`

See `AGENTS.md` for AI contributor guidelines and `CONTRIBUTING.md` for developer standards.

---

## Technical Stack

- **Platform:** Native Android (Min SDK 24, Target SDK 36, Compile SDK 36)
- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Local Persistence:** Room Database (SQLite)
- **Networking:** Retrofit & OkHttp
- **Cloud Synchronization:** Firebase Authentication & Cloud Firestore
- **Background Scheduler:** WorkManager
