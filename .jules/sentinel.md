## 2025-05-18 - Prevent Database Passphrase Exposure in Debug Logs During Restore

**Vulnerability:** Leftover `System.err.println` calls in `BackupManager.kt` printed database passphrase candidates (`candidates`) and verified passphrases to standard error output logs during database backup restore execution.
**Learning:** Temporary debug statements (`System.err.println`) used during backup and restore troubleshooting were accidentally committed to production source files, exposing sensitive SQLite database encryption keys to system logs.
**Prevention:** Strictly sanitize logs to remove candidate key/secret collections and perform automated checks or code reviews before committing diagnostic logging code.
