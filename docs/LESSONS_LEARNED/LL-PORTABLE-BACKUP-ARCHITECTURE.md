# Lesson Learned: Portable Backup Architecture

**Identifier:** `LL-PORTABLE-BACKUP-ARCHITECTURE`
**Status:** Permanent engineering lesson; non-authoritative.

## What Happened?
Early backup designs tied database encryption directly to device-bound hardware keystores and Firebase user authentication IDs (`UID`). Over time, this created deep fragility, requiring multi-level fallback salvage algorithms and complex decryption candidate chains to handle device switches or authentication token refreshes.

## Why It Mattered
A backup mechanism coupled to device hardware or external cloud auth tokens cannot be safely restored when a device is lost, replaced, or migrated offline. Resellers lost access to their business records when attempting to restore legitimate backups across devices.

## What to Do Differently
1. **Decouple Backup Encryption from Hardware and Identity:** Use standard, portable cryptographic schemes (e.g., AES-256-GCM with PBKDF2/Argon2 key derivation) using an optional user passphrase rather than hardware-bound Keystores or Firebase UIDs.
2. **Avoid Multi-Tier Salvage Complexity:** Portable, standard backups eliminate the need for heuristic decryption fallbacks and maintain clear, verifiable restore semantics.
3. **Format Portability:** Ensure backup archive formats (e.g., standard ZIP containing SQLite and metadata JSON) can be inspected, verified, and restored reliably across environments.
