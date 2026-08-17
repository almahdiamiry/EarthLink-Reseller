Here is the **comprehensive Markdown specification** you requested. 

You can copy this entire Markdown block, save it as a `.md` file (e.g., `utower_import_spec.md`), and upload it to Google AI Studio along with your existing `utower_migration_converter.py` and the `utower_data.tgz` archive. 

The AI will have everything it needs to generate the exact Dart/Flutter importer function for your app.

---

# uTower Data Migration & Import Module Specification (For ISP Manager App)

## 1. Introduction
This document details the structure of the uTower local data cache, the role of the existing Python migration script, and the strict business logic required to import this data directly into the ISP Manager Flutter app's SQLite database (Drift). 

The goal is to provide Google AI Studio with the necessary technical context to generate a robust, production-ready Dart importer that reads the native `utower_data.tgz` (or the extracted SQLite `.db` file) and populates the app's `subscribers`, `ledger_transactions`, `import_batches`, `sync_outbox`, and `audit_log` tables without errors.

---

## 2. Data Source: The Firebase Realtime Database Cache
uTower stores its core data in an Android SQLite database located at:
`/data/data/com.mobx.utower/databases/utower-*.firebaseio.com_default`

**Extraction Method:** Root access is required via ADB (`su`). The data is packaged into a `.tgz` archive.

**Schema of the Source SQLite File:**
```sql
CREATE TABLE serverCache (
  path TEXT PRIMARY KEY,
  value BLOB
);
```
- `path`: The Firebase Realtime Database node path (e.g., `/IGNLwnsi.../live_users/e_12345`).
- `value`: A UTF-8 JSON-encoded string containing the data for that node.

---

## 3. Existing Python Converter (`utower_migration_converter.py`)

*Note: This Python script is already built and exists. It serves as a reference implementation for data normalization.* 

**How it works:**
1. Opens the SQLite file and reads all rows from the `serverCache` table.
2. Determines the record type by parsing the `path`:
   - `/live_users/` → **Subscribers**.
   - `/users/` → **Legacy/Archived Users**.
   - `/messagesOfHistory/` → **Transactions**.
   - `/deep_analysis/`, `/autoSends/`, `/setting/` → **Configuration**.
3. Parses the JSON BLOB and normalizes fields:
   - Converts **thousand-IQD units** (e.g., 40) to **real IQD** (e.g., 40000).
   - Converts **millisecond timestamps** to ISO 8601 UTC strings.
4. Generates an `utower_export_full.json` file.

**Important Findings from the Converter (Used for the App Importer):**
- **Currency:** Monetary values like `currentPrice`, `debts`, `amount`, and `totalDebitAfter` are stored as *thousands* of IQD. **Rule:** Multiply by 1000.
- **Timestamps:** Fields like `end`, `start`, `time`, and `serverTime` are milliseconds since epoch. **Rule:** Convert `DateTime.fromMillisecondsSinceEpoch(ms)` to UTC ISO string.
- **Data Duplication:** The `live_users` table is the source of truth. The `users` table contains older records (often `merged: true`) and should only be used as a fallback for missing device credentials.

---

## 4. Key Data Mappings (Subscribers & Transactions)

When parsing the JSON from the `serverCache`, map the fields as follows:

### 4.1 Subscriber (`/live_users/e_<id>`)
The JSON blob contains two main objects: `live` (Earthlink user details) and `utower` (local uTower settings).

| uTower JSON Path | Description | App Target Field (Drift) |
| :--- | :--- | :--- |
| `live.id` | Earthlink User Index (numeric ID) | `earthlink_user_index` (String) |
| `live.username` | Earthlink Username (e.g., `user@sacx`) | `earthlink_username` |
| `live.name` | Display Name | `display_name` |
| `live.profileName` | Package Name (e.g., "Economy") | `package_name` |
| `live.boardName` | Tower/Board name (e.g., "e") | `tower_name` |
| `live.end` | Subscription Expiry (ms) | `expiry_date` (ISO string) |
| `live.phone` / `utower.phoneNumber` | Primary Phone | `phone1` |
| `utower.phoneNumber2` | Secondary Phone | `phone2` |
| `utower.currentPrice` | Package Price (units) | `package_price_iqd` (units * 1000) |
| `utower.debts` | Current Debt (units) | `current_debt_iqd` (units * 1000) |
| `utower.nanoIp` | Device IP | `device_ip` |
| `utower.nanoUser` | Device Username | `router_username` |
| `utower.nanoPassword` | Device Password | `router_password_encrypted` (MUST encrypt) |
| `utower.note` | Custom note | `note` |
| `live.restricted` | Earthlink restriction flag | `status_earthlink` (set to 'restricted' if true) |

### 4.2 Transactions (`/messagesOfHistory/<key>`)

| uTower JSON Path | Description | App Target Field (Drift) |
| :--- | :--- | :--- |
| `toWho` | Subscriber Ref (e.g., `e_27055588`) | `subscriber_id` (resolved in app) |
| `type` | Raw Type (`add`, `gave`, `debt`) | `type` (Map: add→renewal, gave→payment, debt→debt_added) |
| `amount` | Transaction Amount (units) | `amount_iqd` (units * 1000) |
| `totalDebitAfter` | Debt after transaction (units) | `debt_after_iqd` (units * 1000) |
| `time` or `serverTime` | Transaction Timestamp (ms) | `created_at` (ISO string) |
| `cost` | Earthlink cost (IQD) | `cost_iqd` |
| `note` / `message` | Notes/messages | `note` (concatenate if both exist) |

---

## 5. Target App Database Schema (Drift)
The importer function must target the following Drift SQLite tables. Use `uuid.v4()` for new primary keys.

### 5.1 Table: `subscribers`
```dart
id (PK), display_name, earthlink_username, earthlink_user_index, phone1, phone2, tower_name, package_name, package_price_iqd, current_debt_iqd, advance_balance_iqd, status_local (default 'active'), status_earthlink, online_status, expiry_date, public_ip, private_ip, device_ip, router_username, router_password_encrypted, note, source (default 'utower_import'), source_external_id, created_at, updated_at, deleted_at
```

### 5.2 Table: `ledger_transactions`
```dart
id (PK), subscriber_id (FK), type, amount_iqd, cost_iqd, debt_before_iqd, debt_after_iqd, advance_before_iqd, advance_after_iqd, payment_method, is_paid (int 0/1), note, earthlink_transaction_id, source (default 'utower_import'), source_external_id, created_at, synced_at
```

### 5.3 Table: `import_batches`
```dart
id (PK), source ('utower'), file_name, file_hash (SHA256), status ('running'/'completed'/'failed'), subscribers_found, subscribers_imported, transactions_found, transactions_imported, devices_found, devices_imported, warnings_count, errors_count, started_at, finished_at
```
### 5.4 Table: `sync_outbox`
```dart
id (PK), entity_type, entity_id, operation ('insert'), payload_json (String), status ('pending'), attempt_count, last_error, created_at, last_attempt_at, synced_at
```
### 5.5 Table: `audit_log`
```dart
id (PK), actor ('importer'), action ('import_utower'), entity_type ('batch'), entity_id (batchId), summary (JSON string of stats), before_json, after_json, created_at
```

---

## 6. Strict Business Rules for the Flutter Importer

The AI must generate a Dart class (e.g., `UtowerImporter`) that implements the following logic:

### 6.1 Input Handling & Transaction Wrapping
- Accept a `File` object representing the `.tgz` archive OR the extracted `.db` SQLite file.
- If `.tgz`, use `dart:io` + `package:archive` to extract the SQLite file into a temporary directory.
- Use `sqflite` to open the extracted SQLite file.
- **CRITICAL:** Wrap the entire import operation in a single database transaction (`batch` in Drift). If *any* record fails to insert/update, roll back the entire transaction and mark the `import_batches` row as `failed`.

### 6.2 Duplicate Detection (Subscribers)
Before inserting a subscriber, check if an existing record exists in the `subscribers` table using this **priority order**:
1. Exact match on `earthlink_username` (case-insensitive).
2. Exact match on `phone1` (if not null).
3. Exact match on `source_external_id` (the `e_<id>` from the path).
4. Match on `display_name` AND `tower_name` (both not null).

**If found:** Update the existing row (e.g., update debt, package, expiry, device_ip) and increment `subscribers_merged` counter. **Do not** insert a `sync_outbox` row for this subscriber (it already exists in the app).

**If not found:** Insert a new subscriber row. Insert a `sync_outbox` row with `operation='insert'` and `payload_json` containing the subscriber object.

### 6.3 Transaction Processing
- Process subscribers **first**, and create an in-memory map (`Map<String, String>`) from `source_external_id` (the `e_<id>`) to the new/updated Drift `id`.
- Iterate through transactions. For each:
  - Resolve `subscriber_id` using the map or a query if it already existed.
  - If `subscriber_id` is `null`, log a warning and **skip** the transaction.
  - Query the subscriber's current `current_debt_iqd` and `advance_balance_iqd` just before inserting the transaction to populate `debt_before_iqd` and `advance_before_iqd`.
  - Insert the transaction.
  - **Update the subscriber** `current_debt_iqd` to the `debt_after_iqd` provided by uTower (this is the most accurate state).
  - Insert a `sync_outbox` row for the transaction.

### 6.4 Audit Logging
After the batch is successfully processed, insert a single row into `audit_log` with a summary JSON containing the final counts (found, imported, merged, warnings, errors).

### 6.5 Security & Masking
- **Never** log or print `nanoPassword` or `router_password_encrypted`.
- When storing `router_password_encrypted`, it is expected that the AI will pass it to the app's `SecretStore` (Flutter Secure Storage) or an encryption service.
- For the temporary `.db` file copied to the device cache, ensure it is deleted after import (e.g., `tempFile.delete()`).

---

## 7. What the AI Must Generate

Based on this specification, the Python script, and the `.tgz` archive provided, please generate:

1. **A Dart class `UtowerImporter`**: 
   - Containing a `Future<ImportResult> importFromFile(File sourceFile)` method.
   - Uses `sqflite` to read the SQLite cache file and parse the BLOBs.
   - Uses the app's Drift `Database` instance to commit the transaction.
2. **A `ImportResult` model**: Containing `batchId`, `success`, `subscribersFound`, `subscribersImported`, `subscribersMerged`, `transactionsImported`, `warnings`, `errors`.
3. **Helper methods**:
   - `Future<Map<String, String>> _resolveSubscriberReferences(List<dynamic> subscribers)` to build the `e_id` -> `drift_id` map.
   - `_normalizeMoney(dynamic unitValue)` to convert and handle nulls.
   - `_normalizeTimestamp(dynamic msValue)` to convert to ISO string.
4. **The Drift DAO integration**: The generated code must call the `drift` generated DAOs (e.g., `subscribersDao.insertSubscriber`, `ledgerDao.insertTransaction`, `importBatchDao.startBatch`, etc.) as defined in the blueprint. Assume these DAOs exist. 

---

## 8. Sample Flutter Implementation Pattern

The generated code should follow this architectural flow:

```
UtowerImporter.importFromFile(sourceFile)
    -> Extract .tgz if needed (use archive package)
    -> Open SQLite file using sqflite
    -> Query serverCache table
    -> Start Drift Transaction
    -> Create import_batches row
    -> Iterate rows, route to _processSubscriber, _processTransaction, _processLegacy
    -> Resolve subscriber IDs
    -> Update batch status
    -> Insert audit log
    -> Commit transaction
    -> Delete temp file
    -> Return ImportResult
```

---

## 9. Things to Note for the AI
- The `sqflite` package returns BLOB data as `Uint8List`. You must decode it using `utf8.decode(blob)`.
- The `path` string is what distinguishes data types. Use `path.startsWith('/live_users/')`, etc.
- Legacy users (`/users/`) should **not** be imported as active subscribers. Only use them to patch missing `device_ip` or `router_username` on existing subscribers if found.
- The `advance_balance_iqd` field is entirely absent in uTower. It should default to `0` upon import; the app’s ledger logic will handle advance payments later.