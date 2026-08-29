# EarthLink Reseller

An offline-first Android app built for EarthLink resellers to manage customers, subscriber accounts, and the reseller's financial records in one place.

The idea is simple: **the reseller's account history is valuable and should remain safe even when the internet, a device, or a remote sync operation fails.**

## What the app does

- **Manage subscriber accounts** — keep customer records and their current financial position locally on the device.
- **Record debts and payments** — record customer debt, payments/settlements, and advance/prepayment while keeping the underlying transaction history.
- **Keep a proper transaction history** — financial corrections are recorded as new activity rather than silently rewriting or deleting the original event.
- **Perform EarthLink/ISP operations** — use the EarthLink operational API for reseller workflows such as account lookup/details, ISP balance, activation, renewal/extension, refill, and related account operations.
- **Record reseller charges** — activation, renewal/extension, and refill can create a customer-side charge in the local ledger. The customer's charge remains separate from the ISP's own price or balance.
- **Work offline** — ledger activity can be recorded without an internet connection. Local data remains available and can be synchronized when connectivity returns.
- **Sync through Firebase** — customer/account data and ledger history can be persisted in Firebase for device replacement and app reinstallation. Independent transactions are preserved rather than treated as competing edits.
- **Import uTower data** — import an existing uTower database as a snapshot, preserving the supplied source data and establishing the appropriate opening/current financial baseline instead of trying to reconstruct years of accounting from incomplete history.
- **Restore the account book** — restore backups using replace or merge workflows, with transaction identity used to avoid duplicating the same transaction.
- **Keep ISP deletion separate from local history** — removing a subscriber from the ISP side does not mean the reseller's local customer record or financial history should disappear.
- **Recover required operational credentials** — information required to resume the EarthLink workflow on a new or reinstalled device is part of the recovery model.

## Data integrity first

This is not intended to be a generic synchronization framework, accounting ERP, or distributed-database platform.

The main production goal is straightforward:

> **A reseller should be able to use the app without silently losing, deleting, duplicating, or corrupting customer/account history.**

The local database is the immediate working store. Cloud synchronization is a separate persistence and recovery concern, not something that should roll back a confirmed local transaction.

## Technical stack

- **Platform:** Native Android
- **Language:** Kotlin
- **UI:** Jetpack Compose / Material 3
- **Local database:** Room / SQLite
- **Networking:** Retrofit / OkHttp
- **Cloud:** Firebase Authentication / Cloud Firestore
- **Background work:** WorkManager

## Project documentation

The repository contains the product contract, architecture decisions, invariants, verification model, and release evidence. The main starting points are:

- `docs/authority/` — product and architectural authority documents
- `contract/` — machine-readable requirements and invariants
- `AGENTS.md` — development and verification rules
- `evidence/` — certification and verification artifacts
