# Lesson Learned: Pre-Restore Safety Checkpoints

**Identifier:** `LL-PRE-RESTORE-SAFETY-CHECKPOINT`
**Status:** Permanent engineering lesson; non-authoritative.

## What Happened?
Early database restore mechanisms performed in-place overwrites of active SQLite database files without prior transactional snapshots. If the restore failed partway through (due to an I/O error, corrupt archive payload, or process interruption), the original database was left in an unrecoverable, corrupted state.

## Why It Mattered
Destructive operations without guaranteed rollback can destroy local business history and subscriber debt ledgers with zero possibility of recovery.

## What to Do Differently
1. **Automated Safety Checkpoint:** Before executing any destructive restore or dataset replacement (Restore Replace), automatically generate a verified snapshot backup of the current database (`pre_restore_backup_*.zip`) in persistent storage before opening the replacement transaction.
2. **Atomic Replacement:** Ensure the new database files are validated and swapped atomically.
3. **Fail-Safe Recovery:** If verification or initialization of the restored database fails, immediately rollback or make the safety checkpoint readily accessible to the operator.
