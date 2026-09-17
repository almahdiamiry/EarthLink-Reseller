from pathlib import Path
import subprocess

BASE_SHA = "1ad958832fbeb5093a796b2c8bdb70bb23f53ebb"
BRANCH = "fix/identity-aware-account-resolution"


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1))


vm = "app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt"
replace_once(
    vm,
    '''    fun getAccountByUsernameOrId(username: String): Flow<LocalAccount?> =
        localAccountRepository.getActiveAccountByUsernameOrId(username)

''',
    '''    fun getAccountByUsernameOrId(username: String): Flow<LocalAccount?> =
        localAccountRepository.getActiveAccountByUsernameOrId(username)

    fun getAccountByUsernameOrIdForUser(userIndex: Int?, username: String): Flow<LocalAccount?> {
        return localAccountRepository.getAllAccounts()
            .map { resolveAccountFromSnapshot(it, userIndex?.takeIf { index -> index > 0 }, username).account }
            .distinctUntilChanged()
    }

    private enum class AccountResolutionStatus { UNIQUE, NOT_FOUND, CONFLICT, AMBIGUOUS }

    private data class AccountResolution(
        val status: AccountResolutionStatus,
        val account: LocalAccount? = null
    )

    private fun resolveAccountFromSnapshot(
        accounts: List<LocalAccount>,
        userIndex: Int?,
        username: String?
    ): AccountResolution {
        val active = accounts.filter { !it.isHistoryOnlySubscriber }
        val cleanUsername = username?.trim()?.takeIf { it.isNotEmpty() }

        if (userIndex != null && userIndex > 0) {
            val byIndex = active.filter { it.ispUserIndex == userIndex }
            when {
                byIndex.size == 1 -> return AccountResolution(AccountResolutionStatus.UNIQUE, byIndex.single())
                byIndex.size > 1 -> return AccountResolution(AccountResolutionStatus.AMBIGUOUS)
            }

            if (cleanUsername != null) {
                val byUsername = active.filter {
                    it.earthlinkUsername?.trim()?.equals(cleanUsername, ignoreCase = true) == true
                }
                val safe = byUsername.filter { it.ispUserIndex == null || it.ispUserIndex == userIndex }
                when {
                    safe.size == 1 -> return AccountResolution(AccountResolutionStatus.UNIQUE, safe.single())
                    safe.size > 1 -> return AccountResolution(AccountResolutionStatus.AMBIGUOUS)
                    byUsername.isNotEmpty() -> return AccountResolution(AccountResolutionStatus.CONFLICT)
                }
            }
            return AccountResolution(AccountResolutionStatus.NOT_FOUND)
        }

        if (cleanUsername == null) return AccountResolution(AccountResolutionStatus.NOT_FOUND)
        val byUsername = active.filter {
            it.earthlinkUsername?.trim()?.equals(cleanUsername, ignoreCase = true) == true
        }
        return when (byUsername.size) {
            0 -> AccountResolution(AccountResolutionStatus.NOT_FOUND)
            1 -> AccountResolution(AccountResolutionStatus.UNIQUE, byUsername.single())
            else -> AccountResolution(AccountResolutionStatus.AMBIGUOUS)
        }
    }

    private suspend fun resolveMutationAccount(account: LocalAccount): LocalAccount? {
        val selectedIndex = _selectedUser.value?.userIndex?.takeIf { it > 0 }
        val existing = localAccountRepository.getAccountByIdOneShot(account.id)
            ?.takeIf { !it.isHistoryOnlySubscriber }
        if (existing != null) {
            if (selectedIndex != null && existing.ispUserIndex != null && existing.ispUserIndex != selectedIndex) {
                return null
            }
            return existing
        }

        val resolution = resolveAccountFromSnapshot(
            localAccountRepository.getAllAccountsOneShot(),
            selectedIndex,
            account.earthlinkUsername
        )
        return when (resolution.status) {
            AccountResolutionStatus.UNIQUE -> resolution.account
            AccountResolutionStatus.NOT_FOUND -> {
                localAccountRepository.saveAccount(
                    account.copy(ispUserIndex = selectedIndex ?: account.ispUserIndex)
                )
            }
            AccountResolutionStatus.CONFLICT, AccountResolutionStatus.AMBIGUOUS -> null
        }
    }

'''
)

replace_once(
    vm,
    '''            var foundLocal: LocalAccount? = null
            // If still null, try finding in local DB via targeted lookup''',
    '''            var foundLocal: LocalAccount? = null
            var identityBlocked = false
            // If still null, try finding in local DB via targeted lookup'''
)

replace_once(
    vm,
    '''                    foundLocal = withContext(Dispatchers.IO) {
                        if (!knownUserId.isNullOrBlank()) {
                            localAccountRepository.findActiveAccountByUsernameOrIdOneShot(knownUserId)
                        } else {
                            // Target lookup via search instead of full table scan
                            localAccountRepository.searchAccounts("", limit = 200, offset = 0).find { 
                                (it.earthlinkUsername != null && it.earthlinkUsername.hashCode() == userIndex) || 
                                (it.id.hashCode() == userIndex)
                            }
                        }
                    }''',
    '''                    foundLocal = withContext(Dispatchers.IO) {
                        if (!knownUserId.isNullOrBlank()) {
                            if (userIndex > 0) {
                                val resolution = resolveAccountFromSnapshot(
                                    localAccountRepository.getAllAccountsOneShot(),
                                    userIndex,
                                    knownUserId
                                )
                                if (resolution.status == AccountResolutionStatus.CONFLICT ||
                                    resolution.status == AccountResolutionStatus.AMBIGUOUS) {
                                    identityBlocked = true
                                }
                                resolution.account
                            } else {
                                localAccountRepository.findActiveAccountByUsernameOrIdOneShot(knownUserId)
                            }
                        } else {
                            // Target lookup via search instead of full table scan
                            localAccountRepository.searchAccounts("", limit = 200, offset = 0).find { 
                                (it.earthlinkUsername != null && it.earthlinkUsername.hashCode() == userIndex) || 
                                (it.id.hashCode() == userIndex)
                            }
                        }
                    }
                    if (identityBlocked) {
                        _error.value = "Local account identity is conflicting or ambiguous; remote detail lookup was blocked."
                        _isLoading.value = false
                        return@launch
                    }'''
)

replace_once(
    vm,
    '''                val localAcc = account?.let { localAccountRepository.getAccountByIdOneShot(it.id)?.takeIf { !it.isHistoryOnlySubscriber } }
                    ?: (localAccountRepository.getAccountByIdOneShot(userId)?.takeIf { !it.isHistoryOnlySubscriber }
                        ?: localAccountRepository.findActiveAccountByUsernameOrIdOneShot(userId))
                val effectiveAcc = if (localAcc == null) {
                    val snapshotUser = _selectedUser.value?.takeIf { it.userID.equals(userId, ignoreCase = true) }
                    val snapshotListItem = _usersList.value.find { it.userID.equals(userId, ignoreCase = true) }
                    val displayName = account?.displayName?.ifBlank { null }
                        ?: snapshotUser?.customerFullName
                        ?: snapshotListItem?.customerName
                        ?: userId
                    val phone = account?.phone1 ?: snapshotUser?.mobileNumber ?: snapshotListItem?.mobileNumber
                    val pkgName = account?.packageName ?: snapshotUser?.packageName ?: snapshotListItem?.packageName ?: "Default"
                    val resolvedId = if (!account?.id.isNullOrBlank() && localAccountRepository.getAccountByIdOneShot(account.id) == null) {
                        account.id
                    } else if (localAccountRepository.getAccountByIdOneShot(userId) == null) {
                        userId
                    } else {
                        java.util.UUID.randomUUID().toString()
                    }
                    val newAcc = account?.copy(
                        id = resolvedId,
                        earthlinkUsername = userId,
                        currentPriceIqd = exactAmountIqd.toDouble()
                    ) ?: LocalAccount(
                        id = resolvedId,
                        earthlinkUsername = userId,
                        displayName = displayName.ifBlank { userId },
                        phone1 = phone,
                        packageName = pkgName,
                        currentPriceIqd = exactAmountIqd.toDouble(),
                        debtIqd = 0.0
                    )
                    localAccountRepository.saveAccount(newAcc)
                    newAcc
                } else {
                    localAcc
                }''',
    '''                val effectiveAcc = if (account != null) {
                    resolveMutationAccount(account) ?: run {
                        _error.value = "Local account identity is ambiguous, conflicting, or unavailable for this operation."
                        return@launch
                    }
                } else {
                    val selectedIndex = _selectedUser.value?.userIndex?.takeIf { it > 0 }
                    when (val resolution = resolveAccountFromSnapshot(
                        localAccountRepository.getAllAccountsOneShot(),
                        selectedIndex,
                        userId
                    )) {
                        AccountResolutionStatus.UNIQUE -> resolution.account!!
                        AccountResolutionStatus.NOT_FOUND -> {
                            val newId = if (localAccountRepository.getAccountByIdOneShot(userId) == null) {
                                userId
                            } else {
                                java.util.UUID.randomUUID().toString()
                            }
                            localAccountRepository.saveAccount(
                                LocalAccount(
                                    id = newId,
                                    earthlinkUsername = userId,
                                    displayName = _selectedUser.value?.customerFullName?.ifBlank { userId } ?: userId,
                                    phone1 = _selectedUser.value?.mobileNumber,
                                    packageName = _selectedUser.value?.packageName ?: "Default",
                                    currentPriceIqd = exactAmountIqd.toDouble(),
                                    debtIqd = 0.0,
                                    ispUserIndex = selectedIndex
                                )
                            )
                        }
                        AccountResolutionStatus.CONFLICT, AccountResolutionStatus.AMBIGUOUS -> {
                            _error.value = "Local account identity is ambiguous or conflicting. Operation aborted."
                            return@launch
                        }
                    }
                }'''
)

replace_once(
    vm,
    '''                        payloadJson = "{\\"userId\\":\\"$userId\\",\\"price\\":$authoritativePrice,\\"note\\":\\"$finalNote\\",\\"isWasil\\":$isWasil}",''',
    '''                        payloadJson = "{\\"userId\\":\\"$userId\\",\\"localAccountId\\":\\"${effectiveAcc.id}\\",\\"price\\":$authoritativePrice,\\"note\\":\\"$finalNote\\",\\"isWasil\\":$isWasil}",'''
)

replace_once(
    vm,
    '''        try {
            val noteVal = note?.trim() ?: ""
            val baseNote = if (account.debtIqd > 0) "[PAYMENT]" else "[DEPOSIT]"''',
    '''        try {
            val safeAccount = resolveMutationAccount(account) ?: run {
                withContext(Dispatchers.Main) { onError?.invoke("Local account identity is ambiguous or conflicting.") }
                return@launch
            }
            val noteVal = note?.trim() ?: ""
            val baseNote = if (safeAccount.debtIqd > 0) "[PAYMENT]" else "[DEPOSIT]"'''
)
replace_once(
    vm,
    '''            localLedgerRepository.recordAccountPayment(
                account = account,
                amount = amount,
                note = payNote''',
    '''            localLedgerRepository.recordAccountPayment(
                account = safeAccount,
                amount = amount,
                note = payNote'''
)
replace_once(
    vm,
    '''        try {
            val noteVal = note?.trim() ?: ""
            val debtNote = if (noteVal.isNotBlank()) "[DEBT] $noteVal" else null''',
    '''        try {
            val safeAccount = resolveMutationAccount(account) ?: run {
                withContext(Dispatchers.Main) { onError?.invoke("Local account identity is ambiguous or conflicting.") }
                return@launch
            }
            val noteVal = note?.trim() ?: ""
            val debtNote = if (noteVal.isNotBlank()) "[DEBT] $noteVal" else null'''
)
replace_once(
    vm,
    '''            localLedgerRepository.recordAccountDebt(
                account = account,
                amount = amount,
                note = debtNote''',
    '''            localLedgerRepository.recordAccountDebt(
                account = safeAccount,
                amount = amount,
                note = debtNote'''
)

ui = "app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt"
replace_once(
    ui,
    '''    val matchingAccountFlow = remember(targetUsername) {
        if (targetUsername.isNotEmpty()) {
            viewModel.getAccountByUsernameOrId(targetUsername)''',
    '''    val matchingAccountFlow = remember(userIndex, targetUsername) {
        if (targetUsername.isNotEmpty()) {
            viewModel.getAccountByUsernameOrIdForUser(userIndex, targetUsername)'''
)

repo = "app/src/main/java/com/example/data/repository/Repositories.kt"
replace_once(
    repo,
    '''                    val localAcc = accountDao.getByIdOneShot(op.accountId)?.takeIf { !it.isHistoryOnlySubscriber }
                        ?: accountDao.findActiveAccountByUsernameOrIdOneShot(op.accountId)
                        ?: if (op.operationType.equals("ACTIVATION", ignoreCase = true) && payloadObj.has("username") && payloadObj.optString("username").isNotBlank()) {''',
    '''                    val explicitLocalAccountId = payloadObj.optString("localAccountId").trim().takeIf { it.isNotBlank() }
                    val localAcc = if (explicitLocalAccountId != null) {
                        accountDao.getByIdOneShot(explicitLocalAccountId)
                            ?.takeIf { !it.isHistoryOnlySubscriber }
                            ?: throw IllegalStateException("EXPLICIT_LOCAL_FINANCIAL_TARGET_INVALID: Account $explicitLocalAccountId not found or history-only")
                    } else {
                        accountDao.getByIdOneShot(op.accountId)?.takeIf { !it.isHistoryOnlySubscriber }
                            ?: accountDao.findActiveAccountByUsernameOrIdOneShot(op.accountId)
                            ?: if (op.operationType.equals("ACTIVATION", ignoreCase = true) && payloadObj.has("username") && payloadObj.optString("username").isNotBlank()) {'''
)
replace_once(
    repo,
    '''                            saveAccountInternal(shellAcc)
                        } else {
                            throw IllegalStateException("MISSING_LOCAL_FINANCIAL_TARGET: Cannot materialize financial position for missing local account ${op.accountId}")
                        }

                    val operationPrice = op.amountIqd.toDouble()''',
    '''                            saveAccountInternal(shellAcc)
                        } else {
                            throw IllegalStateException("MISSING_LOCAL_FINANCIAL_TARGET: Cannot materialize financial position for missing local account ${op.accountId}")
                        }
                    }

                    val operationPrice = op.amountIqd.toDouble()'''
)

# Focused regression suite. Duplicate username fixtures use the DAO directly so saveAccount's merge policy cannot mask the defect.
test = Path("app/src/test/java/com/example/IdentityAwareAccountResolutionRegressionTest.kt")
test.write_text(r'''package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.core.model.UserListItem
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import com.example.ui.viewmodels.EarthlinkSearchViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IdentityAwareAccountResolutionRegressionTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountRepository: LocalAccountRepository
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var auditRepository: AuditRepository
    private lateinit var prefs: PreferenceManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? com.example.EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val accountDao = db.localAccountDao()
        val ledgerDao = db.localLedgerEntryDao()
        val outboxDao = db.syncOutboxDao()
        val pendingDao = db.pendingExternalOperationDao()
        accountRepository = LocalAccountRepositoryImpl(db, accountDao, outboxDao)
        ledgerRepository = LocalLedgerRepositoryImpl(db, ledgerDao, accountDao, outboxDao, pendingDao)
        auditRepository = AuditRepositoryImpl(db, db.auditLogDao())
        prefs = PreferenceManager(context)
    }

    @After
    fun tearDown() = db.close()

    private fun vm(): EarthlinkSearchViewModel = EarthlinkSearchViewModel(
        gateway = Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway(),
        audit = auditRepository,
        prefs = prefs,
        localAccountRepository = accountRepository,
        localLedgerRepository = ledgerRepository
    )

    @Test
    fun authoritativeIndex_selectsCorrectContainer_whenUsernameIsRecycled() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        db.localAccountDao().insert(LocalAccount(id = "UUID_B", earthlinkUsername = "alice", ispUserIndex = 2002))
        assertEquals("UUID_B", vm().getAccountByUsernameOrIdForUser(2002, "alice").first()?.id)
    }

    @Test
    fun authoritativeIndex_failsClosed_whenSameIdentityHasMultipleContainers() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_B1", earthlinkUsername = "alice", ispUserIndex = 2002))
        db.localAccountDao().insert(LocalAccount(id = "UUID_B2", earthlinkUsername = "alice", ispUserIndex = 2002))
        assertEquals(null, vm().getAccountByUsernameOrIdForUser(2002, "alice").first())
    }

    @Test
    fun authoritativeIndex_failsClosed_whenUsernameBelongsToDifferentIdentity() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        assertEquals(null, vm().getAccountByUsernameOrIdForUser(2002, "alice").first())
    }

    @Test
    fun authoritativeIndex_canResolveSingleUnboundUsernameContainer() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_UNBOUND", earthlinkUsername = "alice", ispUserIndex = null))
        assertEquals("UUID_UNBOUND", vm().getAccountByUsernameOrIdForUser(2002, "alice").first()?.id)
    }

    @Test
    fun identityAwareResolver_remainsRoomReactive() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_B", displayName = "Alice", earthlinkUsername = "alice", ispUserIndex = 2002))
        val flow = vm().getAccountByUsernameOrIdForUser(2002, "alice")
        assertEquals("Alice", flow.first()?.displayName)
        val next = async { withTimeout(5000) { flow.drop(1).first() } }
        delay(100)
        val current = db.localAccountDao().getByIdOneShot("UUID_B")!!
        db.localAccountDao().update(current.copy(displayName = "Alice Updated"))
        assertEquals("Alice Updated", next.await()?.displayName)
    }

    @Test
    fun refill_withConflictingSyntheticAccount_failsClosedBeforeGatewayDispatch() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        val gateway = Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway()
        val viewModel = EarthlinkSearchViewModel(gateway, auditRepository, prefs, accountRepository, ledgerRepository)
        viewModel.prepareUserDetail(2002, UserListItem(userIndexLower = 2002, userIDLower = "alice"))
        val beforeAccounts = db.localAccountDao().getTotalCount()
        viewModel.refillUser(
            userId = "alice",
            depositPass = "pass",
            price = 35000.0,
            account = LocalAccount(id = "SYNTHETIC", earthlinkUsername = "alice")
        ).join()
        assertEquals(beforeAccounts, db.localAccountDao().getTotalCount())
        assertEquals(0, gateway.refillCalls.get())
        assertTrue(viewModel.error.value?.contains("ambiguous") == true || viewModel.error.value?.contains("conflicting") == true)
    }

    @Test
    fun verifiedRefillMaterialization_usesExplicitPhysicalAccountId() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        db.localAccountDao().insert(LocalAccount(id = "UUID_B", earthlinkUsername = "alice", ispUserIndex = 2002))
        val txId = "charge_explicit_target"
        db.pendingExternalOperationDao().insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_explicit_target",
                accountId = "alice",
                operationType = "REFILL",
                amountIqd = 35000L,
                payloadJson = "{\"userId\":\"alice\",\"localAccountId\":\"UUID_B\",\"isWasil\":false}",
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )
        ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "")
        assertEquals(0, db.localLedgerEntryDao().getByAccountIdOneShot("UUID_A").size)
        assertEquals(1, db.localLedgerEntryDao().getByAccountIdOneShot("UUID_B").size)
    }
}
''')

# Remove temporary runner files from the final tree, then create a clean commit whose parent is the certified PR base.
for path in [
    Path(".github/workflows/pr63-fix-runner.yml"),
    Path("scripts/pr63_apply_fix.py"),
    Path(".github/workflows/pr63-pr-runner.yml"),
]:
    if path.exists():
        path.unlink()

subprocess.run(["git", "config", "user.name", "github-actions[bot]"], check=True)
subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], check=True)
subprocess.run(["git", "add", "app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt",
                "app/src/main/java/com/example/data/repository/Repositories.kt",
                "app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt",
                "app/src/test/java/com/example/IdentityAwareAccountResolutionRegressionTest.kt"], check=True)

tree = subprocess.check_output(["git", "write-tree"], text=True).strip()
commit = subprocess.check_output([
    "bash", "-lc",
    f"printf '%s\\n\\n' 'fix(identity): anchor local financial mutations to authoritative subscriber identity' | git commit-tree {tree} -p {BASE_SHA}"
], text=True).strip()
subprocess.run(["git", "push", "--force", "origin", f"{commit}:refs/heads/{BRANCH}"], check=True)
