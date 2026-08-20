import re

interface_methods = [
    "fun getLedgerForAccount(accountId: String): kotlinx.coroutines.flow.Flow<List<com.example.core.model.LocalLedgerEntry>>",
    "suspend fun addPayment(accountId: String, amount: Double, note: String?, idempotencyKey: String? = null): com.example.core.model.LocalLedgerEntry",
    "suspend fun addDebt(accountId: String, amount: Double, note: String?, idempotencyKey: String? = null): com.example.core.model.LocalLedgerEntry",
    "suspend fun addNoteTransaction(accountId: String, note: String): com.example.core.model.LocalLedgerEntry",
    "suspend fun correctTransaction(originalEntryId: String, intendedAmount: Double, note: String? = null, idempotencyKey: String? = null): com.example.core.model.LocalLedgerEntry",
    "suspend fun deleteTransaction(id: String)",
    "suspend fun deleteAllLedgerEntries()",
    "suspend fun recordAccountRenewal(account: com.example.core.model.LocalAccount, newPriceIqd: Double, chargeNote: String, payNote: String? = null, idempotencyKey: String? = null): com.example.core.model.LocalLedgerEntry",
    "suspend fun recordAccountPayment(account: com.example.core.model.LocalAccount, amount: Double, note: String?, idempotencyKey: String? = null): com.example.core.model.LocalLedgerEntry",
    "suspend fun recordAccountDebt(account: com.example.core.model.LocalAccount, amount: Double, note: String?, idempotencyKey: String? = null): com.example.core.model.LocalLedgerEntry",
    "suspend fun getPendingOperationByIntentId(operationIntentId: String): com.example.core.model.PendingExternalOperation?",
    "suspend fun getPendingOperationByAccountId(accountId: String): com.example.core.model.PendingExternalOperation?",
    "suspend fun getAllPendingOperations(): List<com.example.core.model.PendingExternalOperation>",
    "suspend fun markPendingOperationFailed(businessTransactionId: String, error: String)",
    "suspend fun completePendingOperation(businessTransactionId: String, accountId: String, ledgerEntryId: String? = null)",
    "suspend fun deletePendingOperation(businessTransactionId: String)"
]

with open("app/src/test/java/com/example/StatementViewModelTest.kt", "r") as f:
    content = f.read()

mock_methods = "\n".join(["        override " + m.replace(" = null", "") + " = throw NotImplementedError()" for m in interface_methods])
content = content.replace("    }", mock_methods + "\n    }", 1)

with open("app/src/test/java/com/example/StatementViewModelTest.kt", "w") as f:
    f.write(content)
