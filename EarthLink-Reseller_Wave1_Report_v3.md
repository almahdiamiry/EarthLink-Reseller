# EarthLink-Reseller — التقرير الهندسي الشامل للتبسيط (Wave 1 — نسخة نهائية للتنفيذ، v3)

**تاريخ:** 2026-08-21 (نسخة v3 — إعادة صياغة جزء 5A كقرار تشغيلي مؤقت موثق، إضافة جزء 5B لمسار Audit Log المستقبلي، إصلاح تنسيق وترتيب)
**الحالة:** جاهز للتنفيذ **مع بوابة موافقة بشرية إلزامية بعد Step 1**. مسار التحقق الاستدلالي الحالي لـ`INCONCLUSIVE` معتمد كحل تشغيلي مؤقت وموثق، ولا يُعامل كإثبات device attribution قاطع. أي حل أقوى لاحقاً يُدرس كـPOC مستقل.
**قاعدة التوثيق:** كل ادعاء هنا إما (أ) **مؤكد** بدليل مباشر (ملف/سطر)، أو (ب) مصنّف صراحة **"غير مؤكد"**. لا يوجد تقييم بدون دليل.

---

# جزء 0A — Pre-Wave-1 Backup Architecture Correction

هذا التغيير تم تنفيذه **قبل إعداد خطة Wave 1 وقبل بدء تنفيذها**، لذلك لا يُعتبر جزءاً من خطوات Wave 1 ولا يُعاد تنفيذه ضمن Wave 1.

## القرار

تم استبدال تصميم backup المرتبط بهوية Firebase / الجهاز بتصميم portable اختياري يعتمد على:

1. `No password` — backup portable بدون external identity dependency.
2. `Password protected` — AES-256-GCM مع اشتقاق مفتاح من كلمة مرور المستخدم.

ويجب ألا يعتمد restore الجديد على:
- Firebase UID
- installation/device ID
- network availability

## الحالة

**IMPLEMENTED BEFORE WAVE 1 — BASELINE**

هذا التغيير يُعامل كـbaseline قائم عند بداية Wave 1.

لا يجوز لـWave 1:
- إعادة تنفيذ هذا التغيير؛
- إضافة fallback identity-based encryption جديد؛
- توسيع قائمة legacy/developer/device keys؛
- إنشاء test-only/mock database recovery لإجبار اختبارات backup على النجاح.

## 5-Whys Root Cause

**Problem:** backup encryption was tightly coupled to Firebase User IDs, device-specific installation IDs, and local system environments, making offline disaster recovery or cross-device restoration impossible.

1. **Why 1:** Backups were non-portable because the encryption key depended on current Firebase UID and device/installation identity.
2. **Why 2:** Those identities were used because the previous design had no user-supplied backup password and aimed for automatic, zero-friction backup encryption.
3. **Why 3:** Automatic encryption was chosen to support background daily rolling backups without user interaction.
4. **Why 4:** The design assumed restore would occur under the same account/device identity, overlooking offline recovery, cross-device migration, and Firebase-unavailable environments.
5. **Why 5:** Threat/recovery modeling did not adequately separate authentication identity from portable content-protection key management, producing a cloud-first identity binding where a portable-first backup contract was required.

## Technical baseline recorded from the completed pre-Wave-1 change

- Optional password-protected backup/restore implemented.
- No-password backup mode implemented.
- Restore detects protected backups and requests the password.
- Portable AES-256-GCM design uses a user-supplied password and portable cryptographic parameters rather than Firebase/device identity for the new format.
- Pre-restore safety checkpoint is implemented.
- Database file-path / SQLite journal-mode issues encountered under unit-test conditions were addressed.
- Reported verification run: **362+ tests, 100% green**.

## Evidence boundary — legacy backups

The statement "backward-compatible" must be interpreted narrowly:
- **New portable backup design:** `CLOSED / BASELINE`.
- **Legacy identity-coupled backup decryptability:** `OPEN / DEFERRED` until a real historical backup can be decrypted and restored with actual evidence.
- Synthetic/mock database reconstruction is **not** valid proof of historical backup compatibility.
- The historical `earthlink_backup.zip` remains a read-only forensic artifact and must not force the new design to retain device/Firebase-dependent key recovery.

## Wave 1 / Wave 2 relationship

The new portable encryption architecture is **not a Wave 1 implementation task**.

Any remaining backup work is limited to:
- simplifying / validating the broader restore lifecycle;
- removing production/test-environment coupling that still remains;
- independently verifying legacy backup compatibility where required.

## Explicit regression checklist (must run before AND after Step 3/Step 4)

`BACKUP` و`RESTORE` هم أنماط ضمن نفس `DataOperationMode` enum اللي يديره `DataOperationCoordinator` (نفس الملف المتأثر بـStep 3/Step 4). أي تعديل على آلية الـclaim أو على `inflightAccountLocks` removal يحتاج يثبت إنه ما أثر سلباً على backup/restore، حتى لو التغيير المقصود يمس فقط المسار المالي. لا يكفي "362+ tests سابقاً 100% green" كدليل بعد أي تغيير لاحق بنفس الملف المشترك.

قائمة smoke tests صريحة مطلوبة بعد كل Step من Step 3 وStep 4:

1. تنفيذ `BACKUP` كامل (no-password + password-protected) والتأكد إن `DataOperationCoordinator.isMaintenanceActive` ما زال يمنع أي `SYNC`/`REMOTE_APPLY` متزامن أثناء BACKUP.
2. تنفيذ `RESTORE` كامل ثم التحقق من pre-restore safety checkpoint ما زال يعمل.
3. محاولة تشغيل `SYNC` أثناء `BACKUP`/`RESTORE` فعلي (مو mock) والتأكد من الرفض الصحيح.
4. التأكد إن أي تعديل بمنطق re-entrancy (`Job.hashCode()`) بـStep 3 ما يكسر السيناريو الحالي المستخدم من BACKUP/RESTORE لو كانوا يعتمدون على نفس آلية الـre-entrancy.

---

# جزء 1 — الخلاصة التنفيذية

**Root cause:** مو أن الكود معقد بحد ذاته — المشكلة أن الـarchitecture سمح لأكثر من طبقة تكون مسؤولة عن نفس القرار (مين يقرر نجاح عملية مالية؟ مين يملك التزامن؟ مين يملك مفتاح التشفير؟). هذا التعدد ينتج نمط متكرر بكل أنحاء المشروع:

```text
مشكلة → state جديد → fallback → helper → test → edge case → exception path → تعقيد أكثر
```

**القرار الاستراتيجي الثابت طول المراجعة:** نبني البدائل الـdurable ونثبتها بالاختبار **قبل** حذف أي حماية حالية (حتى لو هشة). لا حذف قبل إثبات البديل.

**مبدأ إضافي تأكد بالمراجعة الخامسة:** لا نضيف state أو abstraction جديدة إلا بعد إثبات أن الموجود فعلاً لا يكفي. المشروع عنده سابقة داخلية جيدة لهذا المبدأ (`UnknownOutcomeResolutionResult` — انظر جزء 4) ولازم نبني عليها، مو نخترع بديل موازي.

---

# جزء 2 — Simplification Impact Matrix الكامل (18 بند)

| # | المجال | الحالة (دليل من الكود) | Verdict | الحجم |
|---|---|---|---|---|
| 1 | DataOperationCoordinator | `Mutex()` عالمي واحد يغطي 7 modes (SYNC×19, IMPORT, RESTORE, BACKUP, ROLLBACK, CLEAR_DATA, REMOTE_APPLY)؛ re-entrancy تعتمد على `Job.hashCode()` غير المضمون التفرّد | 🔴 P0 | متوسط-كبير |
| 2 | G1 terminal-state writers | 9 مواقع مستقلة تكتب `"COMPLETED"` مباشرة بـ`Repositories.kt` (سطور 1206, 1250, 1266, 1272, 1780, 1799, 1818 + دالتين إضافيتين) | 🔴 P0 | متوسط |
| 3 | ViewModel financial orchestration | `EarthlinkSearchViewModel` يعرف كامل G1 lifecycle + قفل ثالث مستقل `inflightAccountLocks: ConcurrentHashMap<String, Mutex>` منفصل عن Coordinator وRoom | 🔴 P0 (رُفع من P1) | متوسط-كبير |
| 4 | Financial fallback/error swallowing | 87 catch block بـsync/repository، 16 منها `catch (_)` بلع كامل؛ مثال مؤكد: `gateway.getBalance()` بمسار renewal يرجع `0.0` صامتاً عند الفشل | 🔴 P0 (بالنطاق المالي فقط) | صغير-محصور |
| 5 | Backup encryption/recovery | تشفير مرتبط بـFirebase UID/device ID + Keystore + 5 candidates بالاستعادة + 5 مستويات salvage fallback | 🔴 P0 | صغير (ملفين) |
| 6 | Backup restore lifecycle (أوسع من التشفير) | `RestoreTransportSnapshot`, `captureRestoreTransportSnapshot()`, `unresolvedObligations`, `executeRestoreReplaceInternal` | 🔴 P0/P1 | متوسط |
| 7 | Test-environment leakage | مسار backup الحقيقي يفحص `Class.forName("org.robolectric.Robolectric")` وقت التشغيل (5 مواقع بملف واحد)؛ ملفات إنتاجية إضافية فيها فحوصات test-environment مشابهة (`BuildConfig.DEBUG` وأنماط قريبة) | 🔴 P0/P1 (مرتبط بـ#5/#6) | صغير-متوسط |
| 8 | API result/error abstraction | 30 موقع فعلي يستخدم `JSONObject`/`ResponseBody`/`Any`/`Map<String, Any>` بدل typed DTO | 🟠 P1 (endpoints المالية أولاً) | كبير — تدريجي |
| 9 | Money representation | `PendingExternalOperation.amountIqd: Long` مقابل `LocalLedgerEntry.amountIqd: Double` و`LocalAccount.debtIqd/loanIqd/advanceIqd: Double` | 🟠 P1 **(انظر جزء 11.4 — أولوية معاد تقييمها، اتجاه واضح Long)** | يحتاج migration منضبط، مو قرار غامض |
| 10 | Identity/ID domain audit | `businessTransactionId`, `operationIntentId`, `sourceExternalId`, `sourceBatchId`, `earthlinkUsername` | 🟠 P1 | audit مو حذف |
| 11 | Outbox state machine | `status: pending/syncing/failed` + `attemptCount`؛ تعليق بالكود: *"Terminal dead_letter semantics are strictly prohibited"* | 🟠 P1 | فحص منفصل |
| 12 | SyncMetadata/generation/lineage | `getGeneration()`, `lineageSnapshotToken` — عدة state domains متوازية | 🟠 P1 | جولة عد شاملة |
| 13 | isLegacy/snapshot/history semantics | `isLegacy`, `isHistoryOnlySubscriber`, `openingDebtIqd/AdvanceIqd/LoanIqd`, `stateSource`, `stateConfidence`, `snapshotCapturedAt`, `isSnapshotHistory` (Models.kt سطور 344-390) | 🟠 P1 | inventory أولاً |
| 14 | Demo Mode | `demoMode` checks موزعة على 8 ملفات (مؤكد بالفحص المستقل) | 🔴 P1 — **REMOVE** (Wave 2) | متوسط |
| 15 | Any/JSONObject/ResponseBody cleanup | مكرر مع #8 | 🟡 P2 | — |
| 16 | Room migration baseline squash | 16 migration متسلسلة (`MIGRATION_1_2` → `MIGRATION_15_16`)، بدون destructive fallback (جيد) | 🟡 P2 — بعد الاستقرار فقط | متوسط |
| 17 | ملفات ضخمة متعددة المسؤوليات | `Repositories.kt`(2922)، `UserDetailScreenV2.kt`(2666)، `UtowerImporter.kt`(1605)، `SettingsScreen.kt`(1636)، `SyncRepositoryImpl.kt`(1503) | 🟡 P2 | كبير |
| 18 | حمل حوكمة/توثيق زايد | 14 ملف `contract/` + 30 سكربت `scripts/` مقابل 25,553 سطر Kotlin فعلي | 🟡 P2 (أرشفة) | صغير |

**بنود من تقارير سابقة ما قدرت أتحقق منها بنفسي** (تحتاج مصدر غير متوفر لي): sweep بدون baseline+fallback success، operation attribution لـexpiration proof، manual evidence semantic validity، git object corruption، production call-graph proof.

---

# جزء 3 — الفجوة المحورية: dispatch gap

## السلسلة الفعلية (مؤكدة بالكود)

```text
recordPendingOperation()
   └─ داخل database.withTransaction واحدة        ← محمي فعلياً (check+insert atomic + unique index)
   └─ + DataOperationCoordinator(SYNC)             ← طبقة حماية إضافية

[الـ transaction تنتهي، تُغلق]

   ↓
gateway.refillUserDeposit(...)   ← خارج أي transaction أو قفل durable
   ↓
response / timeout / crash
```

### ليش `recordPendingOperation()` نفسها ليست المشكلة
الـcheck-then-insert محمي فعلاً: transaction واحدة + `Index(operationIntentId, unique=true)` + `Index(businessTransactionId, unique=true)` + `@Insert(onConflict = IGNORE)`. المشكلة **مو** "ممكن ينكتب pending مكرر" — المشكلة "ممكن ينبعث نفس الطلب لسيرفر Earthlink مرتين".

### الفجوة الحقيقية
الفترة بين "PENDING محفوظ durably" و"استلام نتيجة الـAPI" **غير محمية بأي آلية durable**. الحماية الوحيدة الحالية (`inflightAccountLocks`) بالذاكرة فقط — تضيع بإعادة إنشاء الـViewModel، ما تحمي من process ثاني، ما تحمي بعد إعادة تشغيل التطبيق.

### حالة الـstatus الحالية (مؤكدة — Models.kt سطر 490-497)
```text
"PENDING"    → مسجلة محلياً قبل الإرسال
"RESOLVING"  → أثناء المعالجة بعد الاستجابة
"COMPLETED"  → نجاح مؤكد + materialized
"FAILED"     → فشل مؤكد
```
ما فيه حالة durable تمثل "جاري الإرسال الآن" — هذا بالضبط الفراغ اللي `DISPATCHING` المقترحة تسده.

---

# جزء 4 — سابقة معمارية مهمة: الفصل بين Lifecycle State وResolution Outcome موجود مسبقاً

هذا اكتشاف حاسم من المراجعة الخامسة — تحقق منه بالكود مباشرة:

```kotlin
// Models.kt سطر 525-529
enum class UnknownOutcomeResolutionResult {
    VERIFIED_SUCCESS,
    VERIFIED_FAILURE,
    INCONCLUSIVE
}
```

مستخدمة فعلياً بـ**18 موقع** داخل `verifyAndResolvePendingOperation` وما يتفرع منها. والأهم:

```kotlin
// Repositories.kt سطر 1295-1309
override suspend fun resolvePendingOperationInconclusive(
    businessTransactionId: String, diagnostic: String
): Boolean {
    ...
    pendingDao.updateStatus(businessTransactionId, "PENDING", ...)  // ← يرجع لـPENDING، مو state جديد
    ...
}
```

يعني: لما النتيجة تكون `INCONCLUSIVE`، الـ**status الدائم بقاعدة البيانات يرجع لـ"PENDING"** — والـ`INCONCLUSIVE` نفسها تبقى **نتيجة transient** تُرجَّع بـ`PendingOperationResolution`، **مو** تُكتب كحالة دائمة بالجدول.

**النتيجة:** المشروع عنده مسبقاً النمط الصحيح المطلوب (فصل lifecycle عن resolution outcome). Wave 1 **لا يخترع نمط جديد** — يمدّ نفس النمط ليغطي لحظة الـdispatch، بدل إضافة حقل/state موازي.

---

# جزء 5 — نموذج الحل النهائي (durable dispatch ownership)

**مو** قفل رابع. **هو** تمديد لنفس الآلية الموجودة (status + resolution result pattern):

```text
PENDING
  = العملية موجودة durably، لسا ما تم إثبات إرسالها

### قاعدة أهلية الـPENDING للـredispatch

`PENDING` بحد ذاته لا يُعتبر تصريحاً بإعادة إرسال العملية.

إذا كانت العملية قد وصلت سابقاً إلى `DISPATCHING` ثم فقدت نتيجة الـexternal dispatch وأصبحت نتيجتها `UNKNOWN/INCONCLUSIVE`، فهي تُعامل كـ`recovery-blocked PENDING`.

هذه العملية يجوز التحقق منها أو حل نتيجتها، لكنها **لا يجوز إعادة dispatch لها تلقائياً تحت نفس `operationIntentId`** إلا إذا وُجدت قاعدة recovery صريحة ومصرّح بها بشكل مستقل.

بالتالي:

```text
fresh PENDING
  → eligible for first dispatch claim

recovered PENDING after DISPATCHING/unknown outcome
  → NOT eligible for blind redispatch
  → verification/resolution required first

DISPATCHING  ← الإضافة الوحيدة الجديدة للـstatus الدائم
  = actor واحد امتلك حق محاولة الإرسال، بـatomic claim حقيقي (مو مجرد قراءة-ثم-كتابة)

RESOLVING (موجودة، تُستخدم كما هي)
  = بعد الاستجابة، جاري الفحص/materialization

COMPLETED / FAILED (موجودة، تُستخدم كما هي)
  = terminal states مؤكدة

INCONCLUSIVE
  = **ليست state دائمة جديدة** — نفس نمط UnknownOutcomeResolutionResult الموجود:
    الـstatus الدائم يرجع لـPENDING، والـINCONCLUSIVE تبقى transient resolution result
```

## آلية الـclaim (يجب أن تكون atomic حقيقياً — تصحيح المراجعة الخامسة)

**خطأ يجب تجنبه:**
```kotlin
// غلط — race ممكن بين القراءة والكتابة
val op = pendingDao.getByBusinessTransactionId(id)
if (op.status == "PENDING") { pendingDao.updateStatus(id, "DISPATCHING", ...) }
```

**الصح — conditional update بشرط WHERE، وفحص rowsAffected:**
```kotlin
@Query("UPDATE pending_external_operations SET status = 'DISPATCHING', updatedAt = :now, ownerToken = :token WHERE businessTransactionId = :id AND status = 'PENDING'")
suspend fun claimDispatch(id: String, token: String, now: Long): Int  // ترجع عدد الصفوف المتأثرة
```
```kotlin
val claimed = pendingDao.claimDispatch(id, myToken, now())
if (claimed != 1) {
    // actor آخر امتلك الـclaim، أو الحالة تغيرت — لا نرسل
}
```
هذا يعتمد على SQLite نفسها كحاجز ذري (single-writer semantics)، **مو** على قفل تطبيقي إضافي.

## القاعدة الجوهرية (اتفق عليها كل المراجعين، وصياغتها دقيقة الآن)

> لا توجد distributed transaction بين Room وسيرفر Earthlink. الـclaim يضمن **at-most-one active local dispatch owner في وقت واحد** — **وليس** exactly-once execution على السيرفر الخارجي. أي عملية بحالة `DISPATCHING` بعد crash/restart تنتقل لتصنيف `INCONCLUSIVE` (transient، status يرجع PENDING)، **ولا يُعاد إرسالها تلقائياً** (blind redispatch ممنوع صراحة) — تحتاج قرار recovery واعي.

## معيار القبول النهائي (الصيغة المعتمدة)

> لعملية مالية واحدة (نفس `operationIntentId`)، أكثر من actor واحد ما يقدر يمتلك **active local dispatch claim** بنفس الوقت — يُثبت بـ`rowsAffected == 1` على التحويل الذري `PENDING → DISPATCHING`. النظام لا يعيد الإرسال تلقائياً لأي عملية `DISPATCHING` بعد استئناف/إعادة تشغيل.

---

# جزء 5A — قرار التحقق التشغيلي المؤقت لحالة INCONCLUSIVE

هذا الجزء يثبت **قرار التصميم الحالي** الذي سيُستخدم إلى أن يظهر مصدر تحقق أقوى.

## 5A.1 القرار

حالياً، أفضل مصدر متاح من الـmobile/reseller API للتحقق من نتيجة عملية مالية غير مؤكدة هو:

`GET /affiliate/deposit/accountStatement`

القرار التشغيلي هو استخدامه كـ**compound correlation heuristic** في recovery/verification.

هذا القرار:
- **معتمد للتنفيذ الحالي**؛
- لا يحتاج انتظار POC آخر حتى تبدأ Wave 1؛
- لا يُعتبر إثباتاً تشفيرياً أو server-side لجهاز بعينه؛
- يبقى **قابلاً للاستبدال** إذا تم اكتشاف مصدر أقوى وأكثر مباشرة.

سبب اعتماد هذا الحل الآن هو أنه أفضل بكثير من الحالة الحالية التي لا تملك آلية تحقق عملية واضحة بعد crash/unknown outcome، مع الحفاظ على `INCONCLUSIVE` عندما تكون الأدلة غير كافية.

## 5A.2 المصدر الحالي

الـAPI موثق كـ`Confirmed` ويعيد حقولاً مفيدة للمطابقة، منها:
- `operation` (`Withdraw` / `Deposit`)
- `withdrawal` / `deposit`
- `userID`
- `description`
- `transactionID`
- `fromDate`
- `toDate`
- `Query`
- `BatchNo`

ولا يوجد في هذا الـendpoint حقل موثق يربط السجل مباشرةً بـ:
- `businessTransactionId`
- `operationIntentId`
- device ID
- local installation ID

لذلك لا يجوز وصف المطابقة بأنها "device attribution".

## 5A.3 المطابقة المركبة الحالية

بعد أن تصبح العملية `INCONCLUSIVE` بسبب فقدان نتيجة dispatch، يجري الاستعلام عن `accountStatement` بنافذة زمنية ضيقة حول **وقت claim الـ`DISPATCHING` المحلي**، ثم تُقارن العملية المحلية مع السجل البعيد.

المعايير الأساسية:
1. `userID` — مطابقة تامة.
2. `operation` / `description` — مطابقة تامة مع نوع العملية.
3. المبلغ — مطابقة تامة باستخدام `withdrawal` أو `deposit` المناسب، وليس `balance`.
4. الزمن — فرق ضيق بين وقت claim المحلي ووقت statement.

**النافذة الابتدائية:** تبدأ محافظة، مثلاً `±90 seconds`، ولا تُعتبر هذه القيمة business invariant. يمكن تعديلها فقط بناءً على evidence تجريبي لاحق.

الـAPI documentation يذكر أن بعض قراءات ما بعد العمليات المالية قد تتأخر لثوانٍ، لذلك لا يجوز استخدام غياب السجل فوراً كدليل فشل قطعي.

## 5A.4 مستويات النتيجة

### تطابق مركب قوي
إذا تطابقت المعايير الأربعة دون غموض، فالنتيجة:

```text
HIGH-CONFIDENCE EXTERNAL EVIDENCE
```

ويجوز إدخالها في مسار `verifyAndResolvePendingOperation` كمرشح قوي لـ`VERIFIED_SUCCESS` **إذا كان تصنيف evidence هذا مقبولاً ضمن G1 authority**. لا يوصف هذا على أنه proof لقيمة "هذا الجهاز بالتحديد"، بل على أنه **strong local-to-remote operation correlation**.

### تطابق جزئي أو متعدد السجلات
إذا تطابق بعض الحقول فقط، أو ظهر أكثر من سجل مرشح ضمن النافذة، فالنتيجة `INCONCLUSIVE` ولا يجوز تحويلها تلقائياً إلى نجاح مالي.

### عدم وجود تطابق
عدم وجود سجل ضمن النافذة الحالية لا يُعتبر وحده `VERIFIED_FAILURE` بل `INCONCLUSIVE` إلى أن يوجد دليل موثوق على عدم التنفيذ.

## 5A.5 قاعدة عدم الاعتماد على anti-repeat كدليل ملكية

تم **تأكيد السلوك تجريبياً** أن محاولة تجديد نفس المشترك خلال أقل من دقيقة تُرفض. لكن هذا السلوك ليس جزءاً موثقاً في `v0.7.0` كقاعدة business رسمية، ولذلك يصنّف:

```text
CONFIRMED EXPERIMENTALLY
DOCUMENTATION STATUS: NOT EXPLICITLY DOCUMENTED
```

وهو **supporting signal فقط**، وليس دليلاً على أن جهازنا هو الذي نفذ العملية. لا يجوز بناء `rate-limit passed → this device executed the operation`. إذا ظهر سجل statement قريب جداً من claim المحلي، تبقى المطابقة الزمنية + user + operation + amount هي الأساس، والـanti-repeat مجرد إشارة مساندة.

**استخدام إضافي مفيد:** لو `accountStatement` أظهر سجلين قريبين لنفس المستخدم بفارق أقل من دقيقة، هذا بحد ذاته anomaly يخالف السلوك المؤكد تجريبياً، ويستحق تسجيله/تنبيه منفصل (ممكن يدل على استدعاء API خارج مسارنا، أو خلل بالـrate-limit نفسه من طرف Earthlink) — مو مجرد إشارة نتجاهلها.

## 5A.6 افتراض المنتج حول تعدد الأجهزة

هناك **افتراض تشغيلي من نطاق المنتج** أن الغالبية الساحقة من المستخدمين يستخدمون نفس الجهاز/الشخص للحساب نفسه. هذا الافتراض مفيد في تقييم المخاطر التشغيلية ويبرر قبول heuristic الحالية كحل عملي مؤقت في V1، لكنه **ليس دليلاً تقنياً** ولا يُستخدم لتغيير قواعد الإثبات.

لذلك يبقى:

```text
same reseller account
+
strong compound correlation
→ high-confidence operational evidence
```

مع الحفاظ على `INCONCLUSIVE` في الحالات الغامضة.

## 5A.7 لماذا نعتمد هذا الحل الآن؟

الاختيار الحالي أفضل من بقاء recovery في حالة `UNKNOWN → no practical verification path → unresolved indefinitely`، وفي الوقت نفسه لا يفرض علينا بناء بنية reconciliation جديدة أو ادعاء إثبات لا يقدمه الـAPI. هذا هو **الحل التشغيلي المعتمد حالياً، وليس الحل النهائي الأبدي**. أي مصدر أقوى لاحقاً يمكن أن يحل محله دون تغيير المبدأ الأعلى: `stronger evidence → stronger verification → no blind redispatch`.

## 5A.8 فجوة تشغيلية متبقية: قائمة/واجهة مراقبة لعمليات INCONCLUSIVE

مطابقة `accountStatement` (5A.1-5A.7) تحل جزء "هل نتحقق آلياً؟" لكنها ما تحل جزء "شنو يصير بالعمليات اللي تبقى غامضة بعد المطابقة؟". بدون آلية مراقبة، عمليات `INCONCLUSIVE` الغامضة ممكن تتراكم بصمت — status يرجع `PENDING` ولا فيه أي إشارة توجّه المستخدم لمراجعتها يدوياً.

**متطلب أساسي (لا يُعتبر Step 2/3 مكتملة بدونه):** استعلام أو شاشة بسيطة تعرض كل عملية `status=PENDING` عمرها أطول من threshold معين (مثلاً >5 دقايق) مع نتيجة آخر محاولة مطابقة `accountStatement` (لا تطابق / تطابق جزئي / لا يوجد سجل بعد). هذا هو الـ"queue/UI" المُشار له بجزء 8A (Deferred G1 Closure — Manual verification).

---

# جزء 5B — مسار تحقق مستقبلي: اكتشاف Audit Log API

يوجد نظام Audit Log فعلي في لوحة إدارة الشركة يظهر على الأقل: action, timestamp, user/account, source IP, login/action context. وبين السجلات أمثلة مثل `User_Refill_Deposit` و`User_Extended` مع عنوان IP ووقت العملية.

هذا المصدر **ليس جزءاً من Wave 1** لأنه لم يثبت بعد أنه متاح عبر الـmobile/reseller API الحالي أو أنه يملك عقد API مستقراً وقابلاً للاستخدام من التطبيق.

## الهدف

تنفيذ POC مستقل لاحقاً للتحقق مما إذا كان يمكن الوصول إلى audit events عبر API رسمي أو endpoint قابل للاعتماد، وهل يمكنه توفير action + timestamp + user/subscriber + transaction/reference + source IP/session. إذا أمكن ربط هذه المعلومات بالعملية المحلية، قد ينتقل التحقق مستقبلاً من `strong correlation` إلى `stronger server-side attribution`. لكن **لا يوجد اعتماد على هذا الـPOC حالياً**، ولا يجوز تعطيل Wave 1 بانتظاره.

## نتيجة هذا القرار

```text
Current V1:
accountStatement compound correlation
→ operational verification

Future:
Audit Log discovery POC
→ potentially stronger attribution
```

---

# جزء 6 — خطة تنفيذ Wave 1 (5 خطوات، كل وحدة قابلة للتراجع لحالها)

### Step 1 — Canonical G1 Financial Operation Lifecycle Owner
*(الاسم الأدق بدل "Canonical G1 operation owner" — يشمل الدورة كاملة، مو بس كتابة COMPLETED)*

تحديد owner واحد يملك: `initiation → dispatch claim → outcome classification → resolution → materialization → terminal transition`، **لكن Step 1 ليس توثيقاً سردياً فقط**.

يجب أن ينتج Step 1 **boundary/call-site inventory قابل للفحص** يربط كل وظيفة lifecycle بالمصدر الفعلي لها:

```text
Who creates PendingExternalOperation?
Who owns dispatch claim?
Who is allowed to call the external financial mutation?
Who classifies external outcome?
Who invokes verification/resolution?
Who owns verified-success materialization?
Who owns the canonical failure transition?
Who owns manual verification entry?
Who can write COMPLETED?
Who can write FAILED?
```

يتم فحص كل production caller وتسجيل أي caller خارج الـcanonical owner.

**مهم:** `COMPLETED` (canonical verified-success materializer) و`FAILED` (canonical outcome transition) يبقوا **مفهومياً منفصلين** — لا نجبر كل فشل يمر بنفس مسار الـmaterializer الخاص بالنجاح، لأن `FAILED` ينتج من مصادر مختلفة (pre-dispatch validation، business rejection، authoritative non-execution proof).

**معيار القبول:** لا يكفي `grep("COMPLETED") == 1`. يجب أن يثبت الـinventory أن كل production path يمر عبر owner/lifecycle boundary موحّد، وأن أي remaining direct writer أو dispatch caller موثق ومقرر له corrective action قبل Step 2.

### ⛔ بوابة موافقة إلزامية بعد Step 1 (انظر جزء 11.2)

**الإيجنت يتوقف بعد إنتاج الـinventory ويعرضه للمراجعة البشرية قبل الشروع بـStep 2/3.** قرارات Step 1 (مين canonical owner، مين caller خارج النطاق) تؤثر مباشرة على نطاق Step 3، وما ينبغي تُترك لتقدير الإيجنت وحده بدون موافقة صريحة.

### Step 2 — Financial Outcome Semantics
تصنيف صريح: `not-dispatched / business-failure / success / unknown-after-dispatch` (باستخدام `UnknownOutcomeResolutionResult` الموجودة، مو enum جديد). إزالة أنماط `catch → FAILED` / `catch → 0` / `catch → true` من مسار الأموال تحديداً.

**ملحق:** مسار `unknown-after-dispatch` يستخدم القرار التشغيلي المؤقت الموثق في جزء 5A (`accountStatement` + compound correlation) كأول خطوة آلية قبل أي تدخل يدوي. هذا حل V1 مؤقت قابل للاستبدال، وليس device attribution قاطعاً. يشمل أيضاً متطلب المراقبة بجزء 5A.8.

### قاعدة عدم الـredispatch بعد crash/restart
عودة عملية `DISPATCHING` إلى `PENDING` بعد crash/restart لا تعني أن نفس `operationIntentId` اكتسب صلاحية إرسال جديدة. أي recovery/retry يجب أن يمر أولاً عبر verification/resolution (الآن يشمل مطابقة `accountStatement` الاستدلالية)، أو عبر إنشاء intent جديد صريح لعملية جديدة. لا يجوز أن تنتج بنية الـclaim loop من نوع `crash → reset → retry → ...`.

### Step 3 — Durable Dispatch Claim (الأهم — هنا فقط نلمس الـrace gap)
إضافة `DISPATCHING` كـstatus دائم واحد جديد + آلية claim ذرية (`UPDATE ... WHERE status='PENDING'` + فحص `rowsAffected`). **لا** نضيف `UNKNOWN`/`INCONCLUSIVE` كـstatus دائم — نستخدم نمط `UnknownOutcomeResolutionResult` الموجود.

### تمييز fresh PENDING عن recovery-blocked PENDING

يجب أن يثبت التنفيذ كيف يميّز بين:

```text
PENDING because it has never been dispatched

**قاعدة عدم الـredispatch:** عودة عملية `DISPATCHING` إلى `PENDING` بعد crash/restart لا تعني أن نفس `operationIntentId` اكتسب صلاحية إرسال جديدة. الـrecovery يجب أن يمر أولاً عبر verification/resolution، أو عبر إنشاء intent جديد صريح لعملية جديدة. لا يجوز أن تنتج بنية الـclaim loop من نوع `crash → reset → retry → ...`.

**اختبار الحالة الحرجة (قلب Wave 1 بالكامل):**
```text
Caller A + Caller B، نفس operationIntentId، نفس account، متزامنين
  → واحد بس ينجح بالـclaim (rowsAffected==1)
  → الثاني يُرفض كـduplicate
  → external gateway mutation يُستدعى مرة وحدة بالضبط

Restart أثناء DISPATCHING
  → لا إعادة إرسال تلقائية
  → تصنيف INCONCLUSIVE (transient) + status يرجع PENDING
  → compound correlation من accountStatement (جزء 5A)، ثم تحقق يدوي إذا كانت النتيجة غامضة — مو redispatch أعمى
```

**متطلب اختبار:** سيناريو الـ"Restart" أعلاه يجب اختباره بإعادة فتح فعلية لملف قاعدة البيانات بـinstance Room جديد تماماً — **مو** مجرد رمي exception بنفس العملية/الـprocess. محاكاة الـcrash بنفس الـsession لا تثبت durability فعلية (انظر جزء 11.3).

### Step 4 — حذف inflightAccountLocks
**فقط بعد** ما Step 3 يثبت نفسه بالاختبار أعلاه ويغطي نفس الضمان أو أفضل، **و** بعد تشغيل قائمة regression checks الخاصة بـbackup/restore (جزء 0A) كون كلاهما يشترك بنفس `DataOperationCoordinator`.

### Step 5 — Thin ViewModel
نقل orchestration: `ViewModel → FinancialOperationService/UseCase → Gateway + Repository`. الـViewModel يعرف بس "ابدأ العملية" / "راقب النتيجة" — صفر معرفة بـG1 lifecycle.

بعد كل خطوة: **focused tests → full G1 regression → قياس Complexity Budget** (جزء 7) قبل الانتقال للي بعدها.

---

# جزء 7 — Complexity Budget (مقاييس دلالية، مو عدّ نصوص)

| Metric | الحين | الهدف بعد Wave 1 |
|---|---|---|
| `COMPLETED` state authority | 9 مواقع كتابة مباشرة | 1 (canonical verified-success materializer) |
| `FAILED` transition authority | متعدد ضمن نفس الـ9 | 1 canonical failure-transition authority، منفصل مفهومياً عن COMPLETED |
| active dispatch claim authority | 0 (غير موجود — فجوة كاملة) | 1 (atomic DB claim) |
| external dispatch initiation authority | متعدد (كل caller يقدر يرسل مباشرة) | 1 (بس صاحب الـclaim) |
| ViewModel financial lifecycle knowledge | كامل (PENDING/COMPLETED/idempotency/statement/audit) | 0 |
| blind redispatch paths | غير مضمون حالياً | 0 (مُثبت بالاختبار) |
| financial silent-catch paths | ≥1 مؤكد (`getBalance()` → `0.0`) | 0 |
| backup key dependencies | Firebase UID + Device ID + 5 candidates | password واحد فقط |
| durable status جديد أُضيف | — | 1 بس (`DISPATCHING`) — لا `UNKNOWN` كـstatus منفصل |
| INCONCLUSIVE ops بدون مسار تحقق موثّق | غير موجود قبل هذا التقرير | مسار استدلالي موثّق (جزء 5A) + قائمة مراقبة (5A.8) |

**ملاحظة مهمة (تصحيح مُعتمد):** الهدف مو "عدد آليات التزامن أقل" لحد ذاته — الهدف "كل concern له owner واحد واضح". النتيجة النهائية ممكن تكون 3 آليات (Room transaction + durable dispatch claim + maintenance exclusion منفصلة) وتكون أصح من قفل واحد يحاول يحل كل شي.

---

# جزء 8 — ما تم تأجيله عمداً (لا يُلمس بـWave 1)

- Money representation (Long/Double) — اتجاه واضح (Long)، يحتاج migration منضبط بجدول زمني محدد. **انظر جزء 11.4.**
- Identity/ID domains — audit فقط، مو حذف IDs
- Outbox state machine — بعد ما يثبت financial lifecycle
- SyncMetadata/generation/lineage — جولة عد منفصلة
- isLegacy/snapshot/history — inventory فقط حالياً
- API typed abstraction — تدريجي، endpoints مالية أولاً
- **Demo Mode removal — feature is explicitly not required now or in the intended product. Remove the feature entirely in Wave 2; do not replace it with a new Demo/Real abstraction. Tests that only exist for Demo Mode should be removed; tests that use Demo Mode merely as a test double should be migrated to explicit test doubles. Verify zero production references remain.**
- Migration baseline squash — بعد الاستقرار الكامل فقط
- **الـAudit Log الخاص بلوحة الويب الإدارية (ASP.NET WebForms)** — نظام منفصل تماماً عن الـmobile API الموثّق، بمصادقة مختلفة (على الأغلب session/cookie مو Bearer)، وغير مغطى بأي توثيق API حالي. **مؤجل كـPOC مستقبلي منفصل، خارج نطاق Wave 1** (انظر جزء 5B وجزء 11.5).

---

# جزء 8A — G1 Closure Backlog حي مرتبط بـ Wave 1

Wave 1 لا تلغي متطلبات G1، ولا تنشئ خطة G1 ثانية مستقلة.
المتطلبات المفتوحة من G1 تُدار كـ **Live Closure Backlog** وتُغلق عبر خطوات
التبسيط عندما تعالج نفس الـroot cause.

هذا القسم هو سجل العمل الحي الذي يمنع ضياع فجوات G1 أثناء تنفيذ
التبسيط. لا يجوز نقل بند G1 إلى "مبسّط" أو "مؤجل" ثم نسيانه؛ كل بند
يبقى مرتبطاً بخطوة Wave 1 أو بخطة إغلاق لاحقة حتى يُثبت إغلاقه.

## Frozen / Closed — لا يعاد فتحه إلا عند contradiction

| Pre-Wave-1 baseline / area | الحالة | القرار |
|---|---|---|
| New portable backup architecture | CLOSED / BASELINE | لا يعاد تنفيذه ضمن Wave 1 |

| G1 Finding / Area | الحالة | القرار |
|---|---|---|
| G1-B | CLOSED/FROZEN | لا تغيير إلا عند contradiction مثبت |
| G1-G | CLOSED/FROZEN | لا تغيير إلا عند contradiction مثبت |
| G1-H | CLOSED/FROZEN | لا تغيير إلا عند contradiction مثبت |
| G1-K | CLOSED/FROZEN | لا تغيير إلا عند contradiction مثبت |
| G1-L | CLOSED/FROZEN | لا تغيير إلا عند contradiction مثبت |
| G1-M | CLOSED/FROZEN | لا تغيير إلا عند contradiction مثبت |
| Statement separation | CLOSED/FROZEN | لا تغيير إلا عند contradiction مثبت |
| Governance | CLOSED/FROZEN | لا تغيير إلا عند contradiction مثبت |

## Active G1 Closure — يغلق عبر Wave 1

| G1 Finding / Area | الحالة | Wave 1 Owner |
|---|---|---|
| Pending intent persistence | OPEN/PARTIAL | Step 1 + Step 3 |
| UNKNOWN safety | OPEN | Step 2 + Step 3 |
| State-only inference removal | OPEN | Step 2 |
| Canonical G1 path | OPEN | Step 1 |
| Financial API error semantics | OPEN | Step 2 |
| Restart durability | OPEN/PARTIAL | Step 3 + Step 4 |
| Financial concurrency | OPEN/PARTIAL | Step 3 + Step 4 |

## Wave 2 Simplification — Feature Removal

### Demo Mode — REMOVE

**قرار المنتج:** التطبيق لن يحتاج Demo Mode، لا الآن ولا في المنتج المقصود.
لذلك لا توجد قيمة في عزله خلف `DemoGateway` أو إعادة تصميمه؛ التبسيط الصحيح هو
إزالة الـfeature بالكامل بعد استقرار Wave 1.

#### نطاق الإزالة

1. إزالة إعداد/تفضيل `demoMode` وكل مسارات القراءة والكتابة الخاصة به.
2. إزالة `demoUsersCache` و`demoSearchCache` وأي fake/demo data paths المرتبطة به.
3. إزالة فروع `if (demoMode)` من الـViewModels والـScreens والـRepositories.
4. إزالة أي UI أو Settings controls مخصصة لتفعيل Demo Mode.
5. حذف الاختبارات التي تختبر Demo Mode كـfeature لم تعد مطلوبة.
6. إذا كان اختبار إنتاجي يستخدم Demo Mode فقط كـtest double، استبداله بـfake/mock
   صريح في طبقة الاختبار بدلاً من إبقاء Demo Mode داخل production code.
7. تشغيل full regression بعد الإزالة.
8. إثبات عدم بقاء production references للـDemo Mode.

#### معيار القبول

- لا يوجد `demoMode` production behavior.
- لا توجد demo caches/fake production data paths.
- لا توجد UI controls للـDemo Mode.
- الاختبارات المشتركة تبقى خضراء بدون الاعتماد على Demo Mode.
- لا يُستبدل الحذف بطبقة abstraction جديدة غير مطلوبة.

## Deferred G1 Closure — لا تُنسى ولا تدخل Wave 1

| G1 Area | الحالة | متى؟ |
|---|---|---|
| Manual verification | DEFERRED → **جزئياً مؤتمت الآن عبر جزء 5A** (accountStatement heuristic)؛ الجزء المتبقي (queue/UI للحالات الغامضة — تفصيله بجزء 5A.8) لسا DEFERRED | بعد تثبيت canonical lifecycle وoutcome semantics |
| Migration compatibility | DEFERRED | بعد استقرار schema/backup architecture |
| Final certification | BLOCKED | بعد إغلاق جميع G1 blocking findings وإعادة تشغيل certification |

## قاعدة الإغلاق

أي بند G1 لا ينتقل إلى `CLOSED` إلا عندما:

1. يكون شرط الـauthority محققاً؛
2. يكون production path مثبتاً؛
3. يمر الاختبار السلوكي المرتبط به؛
4. لا يوجد production bypass بديل يكسر الـinvariant؛
5. يكون test/evidence المحدد مسجلاً مقابل الـcommit الحالي.

Wave 1 يجب أن تحدّث هذا الـbacklog بعد كل Step.
لا يجوز أن يختفي أي G1 finding مفتوح لمجرد أن تنفيذَه نُقل إلى طبقة
تبسيط أو خدمة جديدة.

## قاعدة redispatch المرتبطة بالـG1 backlog

عودة عملية كانت `DISPATCHING` إلى `PENDING` بعد crash/استئناف **لا تمنح تلقائياً**
صلاحية إرسال خارجية جديدة لنفس `operationIntentId`.

أي recovery/retry يجب أن يمر أولاً عبر بروتوكول resolution/verification
المعتمد (يشمل الآن المطابقة الاستدلالية بجزء 5A)، أو عبر إنشاء **intent جديد صريح** إذا كان المطلوب فعلاً عملية جديدة.

الاختبار يجب أن يثبت:

```text
DISPATCHING
  ↓ crash / restart (فعلي، بـinstance Room جديد)
PENDING + INCONCLUSIVE resolution
  ↓
مطابقة accountStatement استدلالية (جزء 5A)
  ↓ (لو غامضة)
تحقق يدوي فقط
  ↓
NO blind redispatch

same operationIntentId
  ≠
automatic permission for a second external dispatch
```

تمييز recovery عن user intent الجديد إلزامي، حتى لا تتحول آلية
الـdurable claim نفسها إلى مصدر loop من نوع:

```text
crash → reset → retry → crash → reset → retry
```

---

# جزء 9 — نقاط مفتوحة تحتاج فحص إضافي

1. بقية الشاشات المالية غير `EarthlinkSearchViewModel` (لو موجودة) — فحصها الكامل مطلوب عند Step 1 ضمن الـboundary/call-site inventory قبل تثبيت الـowner النهائي.
2. Identity domain audit — "مين يملك مين" بدقة بين الـIDs الخمسة.
3. Outbox وSyncMetadata — موجودين ومؤكدين، التعقيد الداخلي غير مُفصّل بعد.
4. بنود التقرير الخارجي غير المؤكدة (git corruption، إلخ) — تحتاج مصدر إضافي.
5. **سلوك anti-repeat للتجديد** — **مؤكد تجريبياً لأقل من دقيقة**، لكنه غير موثق صراحةً في API v0.7.0؛ يبقى supporting signal فقط، ولا يعتمد عليه لإثبات device attribution.
6. **Audit Log API discovery** — POC مستقبلي منفصل لمعرفة ما إذا كان مصدر audit في لوحة الويب يمكن الوصول إليه عبر API رسمي وقابل للاعتماد. لا يوقف Wave 1.

---

# جزء 10 — سجل المراجعات (audit trail)

| الجولة | أهم تصحيح أضافته |
|---|---|
| 1 | تشخيص أولي: 12 فرصة تبسيط عامة |
| 2 | فحص فعلي للريبو + تأكيد backup identity-binding بدليل كود |
| 3 | Simplification Impact Matrix الأول (10 مجالات) |
| 4 | 6 إضافات (money/identity/outbox/syncMeta/backup-lifecycle/isLegacy) + ViewModel audit كشف قفل ثالث مستقل |
| 5 | تصحيح: 3 آليات تزامن مختلفة الغرض، مو تكرار — الفجوة الحقيقية بين PENDING والـdispatch |
| 6 | نموذج durable dispatch ownership + state machine مقترح |
| 7 | **4 تصحيحات نهائية:** فصل COMPLETED/FAILED، عدم إضافة UNKNOWN كـstate دائم (تأكد بالكود عبر `UnknownOutcomeResolutionResult`)، claim ذري حقيقي بـrowsAffected، صياغة "one active owner" بدل "exactly-once" |
| 8 | إضافة **G1 Closure Backlog حي**، منع redispatch لنفس `operationIntentId` بعد crash/restart، تحويل Step 1 إلى **boundary/call-site inventory**، وتصحيح metric `FAILED` إلى **canonical failure-transition authority** بدل عدّ writers |
| 9 | توثيق **Pre-Wave-1 Backup Architecture Correction** كـbaseline سابق للخطة، مع فصل new portable backup عن legacy decryptability والتحقق التاريخي |
| 10 | تأكيد أن **Demo Mode غير مطلوب لا الآن ولا في المنتج المقصود**؛ تحويله من Simplify إلى **REMOVE في Wave 2**، مع تعريف نطاق الإزالة ومعايير قبول تمنع استبداله بتعقيد جديد |
| 11 | **تحقق مستقل كامل من الريبو الفعلي** (git clone + grep مباشر) لكل الأرقام الرئيسية بالتقرير — مؤكدة بدرجة عالية جداً؛ إضافة نسخة أولى من مصدر تحقق استدلالي عبر `accountStatement` لحالة INCONCLUSIVE؛ إضافة **بوابة موافقة بشرية إلزامية بعد Step 1**؛ إضافة **متطلب اختبار restart حقيقي** (instance جديد، مو محاكاة بنفس الـprocess)؛ إضافة **checklist regression صريح لـbackup/restore** بجزء 0A؛ توثيق الـaudit log بلوحة الويب كـPOC مستقبلي منفصل خارج نطاق Wave 1 |
| 12 | **تأكيد جزئي لفرضية rate-limit** من اختبار فعلي (مؤكد <دقيقة، غير مختبر 1-3 دقايق)، استخدامها كإشارة مساندة/anomaly-detector لا أساسية؛ **تبسيط قرار Money representation** بمعلومة نطاق مؤكدة (أصغر عملة 250 دينار، كل المعاملات أعداد صحيحة) — نقلها من "غموض تصميمي مؤجل" إلى "اتجاه واضح (Long) يحتاج migration منضبط بجدول زمني محدد"؛ **إعادة صياغة جزء 5A بالكامل** (5A.1-5A.7) كقرار تشغيلي مؤقت موثق بلغة أدق (compound correlation، مو device attribution)؛ إضافة **جزء 5B** (Audit Log API كمسار مستقبلي منفصل)؛ إضافة **5A.8** لاستعادة متطلب قائمة/واجهة مراقبة عمليات INCONCLUSIVE الغامضة (كان أُسقط سهواً بإعادة الصياغة، وأعيد ربطه بمرجعه بجزء 8A) |

---

# جزء 11 — نتائج التحقق المستقل ومراجعة الخبير

## 11.1 التحقق من دقة التقرير مقابل الريبو الفعلي

تم عمل `git clone` مباشر للريبو وفحص كل رقم رئيسي بالتقرير بـ`grep`/`wc -l` مباشرة على الكود، وليس اعتماداً على النص وحده. النتيجة: **دقة عالية جداً وغير معتادة لتقارير من هذا النوع.**

| الادعاء | التحقق | النتيجة |
|---|---|---|
| 25,553 سطر Kotlin بالمصدر الرئيسي | `wc -l` على `app/src/main` | ✅ مطابق تماماً |
| 14 ملف بمجلد `contract/` | `find` | ✅ مطابق تماماً |
| Repositories.kt = 2922 سطر | `wc -l` | ✅ مطابق |
| كتابة `"COMPLETED"` بالسطور المذكورة بالضبط | `grep -n` | ✅ مطابق |
| `inflightAccountLocks` بـEarthlinkSearchViewModel سطر 318 | فحص مباشر | ✅ مؤكد |
| `demoMode` بـ8 ملفات | `grep -rli` | ✅ مطابق تماماً |
| `amountIqd: Long` مقابل `amountIqd: Double` | Models.kt سطر 514/384 | ✅ مؤكد |
| re-entrancy تعتمد على `Job.hashCode()` | سطر 74-76 بـDataOperationCoordinator.kt | ✅ مؤكد حرفياً |
| نمط `UnknownOutcomeResolutionResult` (جزء 4) | Repositories.kt سطر 1305/1488 | ✅ مؤكد بالضبط |

نقطة دقة أقل وجدتها: ادعاء "12 ملف إنتاجي فيه فحوصات مشابهة لـRobolectric" — الفحص المباشر لقى 3 ملفات فيها `Class.forName("org.robolectric...")` صراحة؛ الرقم 12 يبدو يشمل أنماط test-detection أوسع (`BuildConfig.DEBUG` وما شابه) وليس نفس النمط بالضبط. تفصيل صغير، ما يغيّر التقييم العام.

## 11.2 بوابة الموافقة بعد Step 1 (تفصيل)

Step 1 ينتج قرارات (مين canonical owner، مين caller يحتاج تصحيح) لها أثر مباشر على نطاق Step 3. تنفيذ هذي القرارات تلقائياً بدون مراجعة بشرية يخالف مبدأ "لا نضيف/نغيّر إلا بعد إثبات الحاجة" المعتمد بجزء 1. **التوصية:** الإيجنت يتوقف صراحة بعد إنتاج الـinventory ويعرضه، ولا يكمل لـStep 2/3 إلا بموافقة صريحة.

## 11.3 منهجية اختبار الـrestart

اختبار "Restart أثناء DISPATCHING" (جزء 6 وجزء 8A) لازم يحاكي إعادة تشغيل حقيقية — Room instance جديد يفتح نفس ملف قاعدة البيانات — **مو** رمي exception بنفس الـprocess/الجلسة. محاكاة الكسل (نفس object بالذاكرة) ما تثبت durability فعلية لأنها ما تختبر فعلياً هل الـclaim باقي مكتوب بالـdisk بشكل صحيح بعد فقدان كل الحالة بالذاكرة.

## 11.4 مخاطرة Money representation — إعادة تقييم الأولوية (محدّثة بمعلومة نطاق المستخدم)

الفرق بين `PendingExternalOperation.amountIqd: Long` و`LocalLedgerEntry.amountIqd: Double` (وحقول `LocalAccount` كـDouble) مصنّف بالتقرير الأصلي كـ"P1 يحتاج قرار تصميمي، مؤجل".

**معلومة نطاق مؤكدة من المستخدم:** أصغر عملة فعلية بالدينار العراقي هي 250 دينار، وكل معاملات النظام أرقام صحيحة (أمثلة فعلية: 40,000، 22,000، 30,500). **يعني ماكو أي حاجة تجارية حقيقية لكسور دينار بأي مسار بالنظام.** هذا يبسّط "القرار التصميمي" لأنه ما هو فعلياً قرار غامض — الاتجاه واضح: توحيد على `Long` (دينار عراقي صحيح) بكل مكان، والاعتماد الحالي على `Double` أرجح كان default كسول (Kotlin/Java يفترضان Double لأي رقم عشري) مو حاجة فعلية.

**هذا يبقيها P1 لكن ينقلها من "غموض تصميمي" إلى "تنفيذ واضح الاتجاه يحتاج migration منضبط"**، مو مؤجلة بلا أفق. تفاصيل الـmigration المطلوبة:

1. أعمدة قاعدة البيانات REAL/Double الحالية تحتاج migration صريح يحوّل القيم إلى Long، مع assertion إن كل قيمة موجودة `value % 1.0 == 0.0` قبل التحويل — أي قيمة كسرية موجودة فعلاً بالداتا الحالية هي دليل خطأ سابق يحتاج فحص منفصل قبل الحذف، مو تجاهل صامت.
2. أي حساب وسيط (نسب، خصومات، تقسيم مبالغ) لازم يُتحقق إنه ينتج عدد صحيح دايماً ضمن نطاق العمل الفعلي (مضاعفات الـ250)، أو تُعرَّف قاعدة تقريب صريحة (floor/round) بدل الاعتماد الضمني على سلوك Double الحالي.
3. أي مقارنة `==` مباشرة على قيم Double بالكود الحالي غير آمنة أصلاً (floating-point equality) بغض النظر عن هذا التغيير — تحتاج فحص وتصحيح بنفس الخطوة لو موجودة.

**التوصية المحدّثة:** تنفَّذ كخطوة مستقلة (Step 2.5 تقريباً) مباشرة بعد استقرار Wave 1، بجدول زمني محدد — مو "مؤجل" بلا نهاية.

## 11.5 الـAudit Log بلوحة الويب — تقييم واضح لماذا مؤجل

جدول الـAudit Log اللي شفناه (Admin_Login, User_Refill_Deposit, User_Extended، مع IP) يصدر من **لوحة إدارة ويب مبنية بـASP.NET WebForms قديمة** (control IDs من نوع `ctl00_ctl00_MainContentPlaceHolder...`)، نظام منفصل تماماً عن الـmobile API الموثّق (`rapi.earthlink.iq/api/reseller/`). لا فيه أي ذكر لهذا النظام بتوثيق الـAPI الحالي. أي عمل عليه يحتاج:
- HAR capture جديد لجلسة ويب فعلية لمعرفة آلية المصادقة (على الأغلب session/cookie، مو Bearer JWT).
- عدم وضوح إذا هذا "API" رسمي أصلاً أو مجرد HTML يُستهلك بالمتصفح (scraping هش، ينكسر بأي تحديث واجهة).

هذا شغل استكشاف منفصل تماماً بمخاطر مختلفة، ولازم يبقى POC مستقل خارج Wave 1 (انظر جزء 5B)، **مو** جزء من مسار التحقق الحالي (`accountStatement` بجزء 5A يبقى المصدر العملي المتاح الآن ضمن الـAPI الموثّق والمستقر).

---

**الحالة النهائية: جاهز للتنفيذ، مع بوابة موافقة بشرية إلزامية بعد Step 1.** قرار جزء 5A موثق كحل تشغيلي V1 معتمد: `accountStatement` compound correlation هو المسار الحالي للتحقق من `INCONCLUSIVE` (مع متطلب المراقبة بـ5A.8)، مع إبقاء الحالات الجزئية/الغامضة `INCONCLUSIVE` وعدم استخدام anti-repeat كدليل device attribution. اكتشاف Audit Log API (جزء 5B) مسار مستقبلي منفصل ولا يوقف Wave 1. Money representation صار اتجاهه واضح (Long) بفضل معلومة نطاق العملة، وينفَّذ كخطوة مستقلة بعد استقرار Wave 1.

الخطوة التالية: **Step 1** — تحديد Canonical G1 Financial Operation Lifecycle Owner عبر **boundary/call-site inventory**، بدون تغيير سلوك بعد، مع تحديث **G1 Closure Backlog** مباشرة من نتائج الـinventory، ثم **التوقف للمراجعة البشرية** قبل أي خطوة أبعد.

لا تحتاج Wave 1 إلى Implementation Plan منفصل جديد: هذا المستند نفسه هو خطة التنفيذ المرحلية. أي قرار تصميمي غير محسوم يجب أن يُكتشف ويُوثق ضمن Step 1 قبل كتابة production code، ولا يجوز للإيجنت اختراع abstraction أو state جديد فقط لإكمال المهمة.

**ملاحظة تنفيذية:** إزالة Demo Mode ليست جزءاً من Wave 1 ولا تعتمد عليها صحة الـG1 lifecycle؛ تُنفذ كـWave 2 مستقلة بعد استقرار Wave 1، ثم تُعاد regression/certification المناسبة.
