package com.example.ui.screens

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.AccountPackage
import com.example.core.model.AccountStatementItem
import com.example.core.model.AutocompleteUser
import com.example.core.model.LocalAccount
import com.example.core.model.LoginResponse
import com.example.core.model.PendingExternalOperation
import com.example.core.model.SasProviders
import com.example.core.model.UserDetail
import com.example.core.model.UserListResponse
import com.example.core.network.EarthlinkSasGatewayAdapter
import com.example.core.network.SasGatewayRouter
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import com.example.ui.viewmodels.EarthlinkSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * SammSettingsAndProviderSelectionTest
 *
 * Authoritative verification suite for Task 11 of Alamiry/SAMM Integration:
 * SAMM Settings, URL Normalization, Connection Status State Machine, and Provider Selection Guardrails.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - SAMM Base URL normalization safely guarantees valid HTTP/HTTPS schemes and removes trailing
 *      `/api/v1` and slash variants before persistence.
 *    - Connection status evaluator maps input validity, HTTP status codes, and network exceptions
 *      deterministically into typed [SammConnectionStatus] states without leaking bearer tokens.
 *    - Changing account provider succeeds when zero in-flight pending operations exist for the account.
 *    - Changing account provider is fail-closed and strictly BLOCKED with an explicit invariant error
 *      ("Cannot change provider: account has an active in-flight operation.") whenever an in-flight
 *      pending operation exists, preserving provider execution lineage.
 *
 * 2. Seam / Environment:
 *    - ROBOLECTRIC tier ([RobolectricTestRunner]) for Android Context, EncryptedSharedPreferences fallback,
 *      and real SQLite Room database transactions.
 *    - JVM unit execution for pure URL normalization and connection evaluation state machine.
 *
 * 3. Independent Oracle:
 *    - Independent literal string outputs, exact enum values, and literal error messages defined
 *      independently of ViewModel implementation internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SammSettingsAndProviderSelectionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl
    private lateinit var viewModel: EarthlinkSearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        prefs = PreferenceManager(context).apply {
            setDemoMode(false)
        }

        accountRepo = LocalAccountRepositoryImpl(
            database = db,
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())

        val mockGateway = TestEarthlinkGateway()

        val router = SasGatewayRouter(
            earthlinkAdapter = EarthlinkSasGatewayAdapter(mockGateway),
            preferenceManager = prefs
        )

        viewModel = EarthlinkSearchViewModel(
            gateway = mockGateway,
            sasGatewayRouter = router,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = null
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // =========================================================================
    // 1. URL NORMALIZATION TESTS
    // =========================================================================

    @Test
    fun sammUrlNormalizer_emptyOrBlank_returnsEmpty() {
        assertEquals("", SammUrlNormalizer.normalize(""))
        assertEquals("", SammUrlNormalizer.normalize("   "))
    }

    @Test
    fun sammUrlNormalizer_addsHttpsScheme_whenMissing() {
        val result = SammUrlNormalizer.normalize("samm.al-amiry.net")
        assertEquals("https://samm.al-amiry.net", result)
    }

    @Test
    fun sammUrlNormalizer_preservesHttpScheme() {
        val result = SammUrlNormalizer.normalize("http://192.168.1.50:8080")
        assertEquals("http://192.168.1.50:8080", result)
    }

    @Test
    fun sammUrlNormalizer_stripsTrailingSlashes() {
        val result = SammUrlNormalizer.normalize("https://samm.al-amiry.net///")
        assertEquals("https://samm.al-amiry.net", result)
    }

    @Test
    fun sammUrlNormalizer_stripsTrailingApiV1() {
        val result = SammUrlNormalizer.normalize("https://samm.al-amiry.net/api/v1")
        assertEquals("https://samm.al-amiry.net", result)
    }

    @Test
    fun sammUrlNormalizer_stripsTrailingApiV1WithTrailingSlashes() {
        val result = SammUrlNormalizer.normalize("   https://samm.al-amiry.net/api/v1///   ")
        assertEquals("https://samm.al-amiry.net", result)
    }

    @Test
    fun sammUrlNormalizer_normalizesCombinedRawInput() {
        val result = SammUrlNormalizer.normalize("   samm.domain.com/api/v1/  ")
        assertEquals("https://samm.domain.com", result)
    }

    // =========================================================================
    // 2. CONNECTION EVALUATOR STATE MACHINE TESTS
    // =========================================================================

    @Test
    fun connectionEvaluator_insufficientConfiguration_onMissingInputs() = runTest {
        assertEquals(
            SammConnectionStatus.INSUFFICIENT_CONFIGURATION,
            SammConnectionEvaluator.testConnection(null, "some_token")
        )
        assertEquals(
            SammConnectionStatus.INSUFFICIENT_CONFIGURATION,
            SammConnectionEvaluator.testConnection("", "some_token")
        )
        assertEquals(
            SammConnectionStatus.INSUFFICIENT_CONFIGURATION,
            SammConnectionEvaluator.testConnection("https://samm.domain.com", null)
        )
        assertEquals(
            SammConnectionStatus.INSUFFICIENT_CONFIGURATION,
            SammConnectionEvaluator.testConnection("https://samm.domain.com", "   ")
        )
    }

    @Test
    fun connectionEvaluator_connected_on200Success() = runTest {
        val status = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "valid_token",
            apiCall = { _, _ -> Response.success("OK") }
        )
        assertEquals(SammConnectionStatus.CONNECTED, status)
    }

    @Test
    fun connectionEvaluator_invalidToken_on401And403() = runTest {
        val errorBody = "{\"detail\":\"Unauthorized\"}".toResponseBody("application/json".toMediaTypeOrNull())

        val status401 = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "bad_token",
            apiCall = { _, _ -> Response.error<String>(401, errorBody) }
        )
        assertEquals(SammConnectionStatus.INVALID_TOKEN, status401)

        val status403 = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "insufficient_scope_token",
            apiCall = { _, _ -> Response.error<String>(403, errorBody) }
        )
        assertEquals(SammConnectionStatus.INVALID_TOKEN, status403)
    }

    @Test
    fun connectionEvaluator_serverUnavailable_on500And503() = runTest {
        val errorBody = "Internal Server Error".toResponseBody("text/plain".toMediaTypeOrNull())

        val status500 = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "token",
            apiCall = { _, _ -> Response.error<String>(500, errorBody) }
        )
        assertEquals(SammConnectionStatus.SERVER_UNAVAILABLE, status500)

        val status503 = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "token",
            apiCall = { _, _ -> Response.error<String>(503, errorBody) }
        )
        assertEquals(SammConnectionStatus.SERVER_UNAVAILABLE, status503)
    }

    @Test
    fun connectionEvaluator_tlsNetworkFailure_onSSLExceptionAndIOException() = runTest {
        val statusSsl = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "token",
            apiCall = { _, _ -> throw SSLException("Certificate untrusted") }
        )
        assertEquals(SammConnectionStatus.TLS_NETWORK_FAILURE, statusSsl)

        val statusIo = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "token",
            apiCall = { _, _ -> throw UnknownHostException("Host not found") }
        )
        assertEquals(SammConnectionStatus.TLS_NETWORK_FAILURE, statusIo)

        val statusNet = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "token",
            apiCall = { _, _ -> throw IOException("Connection reset by peer") }
        )
        assertEquals(SammConnectionStatus.TLS_NETWORK_FAILURE, statusNet)
    }

    @Test
    fun connectionEvaluator_unexpectedResponse_on404AndUnknownErrors() = runTest {
        val errorBody = "Not Found".toResponseBody("text/plain".toMediaTypeOrNull())

        val status404 = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "token",
            apiCall = { _, _ -> Response.error<String>(404, errorBody) }
        )
        assertEquals(SammConnectionStatus.UNEXPECTED_RESPONSE, status404)

        val statusRuntime = SammConnectionEvaluator.testConnection(
            baseUrl = "https://samm.domain.com",
            token = "token",
            apiCall = { _, _ -> throw IllegalStateException("Custom failure") }
        )
        assertEquals(SammConnectionStatus.UNEXPECTED_RESPONSE, statusRuntime)
    }

    // =========================================================================
    // 3. PROVIDER SELECTION & UPDATE GUARDRAIL TESTS
    // =========================================================================

    @Test
    fun updateAccountProvider_succeeds_whenNoInFlightOperation() = runTest {
        // Setup: Account exists in Room with default provider EARTHLINK
        val account = LocalAccount(
            id = "acc_safe_100",
            earthlinkUsername = "safe_user",
            displayName = "Safe User",
            operationProvider = SasProviders.EARTHLINK
        )
        accountRepo.saveAccount(account)

        // Verify precondition: no in-flight operation exists
        val hasInFlightBefore = viewModel.hasActivePendingOperation(account.id)
        assertFalse("Account should not have in-flight operation", hasInFlightBefore)

        // Action: Update provider to ALAMIRY
        var successCalled = false
        var errorCalled: String? = null

        val job = viewModel.updateAccountProvider(
            account = account,
            newProvider = SasProviders.ALAMIRY,
            onSuccess = { successCalled = true },
            onError = { errorCalled = it }
        )
        job.join()

        // Assert: Success callback invoked, error is null
        assertTrue("onSuccess must be invoked", successCalled)
        assertNull("onError must not be invoked", errorCalled)

        // Independent Oracle: Room must now contain ALAMIRY
        val persisted = accountRepo.getAccountByIdOneShot(account.id)
        assertNotNull("Account must be found in Room", persisted)
        assertEquals(SasProviders.ALAMIRY, persisted?.operationProvider)
    }

    @Test
    fun updateAccountProvider_blocked_whenActivePendingOperationExists() = runTest {
        // Setup: Account exists in Room with default provider EARTHLINK
        val account = LocalAccount(
            id = "acc_inflight_200",
            earthlinkUsername = "inflight_user",
            displayName = "In Flight User",
            operationProvider = SasProviders.EARTHLINK
        )
        accountRepo.saveAccount(account)

        // Insert active pending operation in Room
        val pendingOp = PendingExternalOperation(
            businessTransactionId = "tx_inflight_test",
            operationIntentId = "intent_inflight_test",
            accountId = account.id,
            operationType = "RENEWAL",
            amountIqd = 40000L,
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(pendingOp)

        // Verify precondition: in-flight operation exists
        val hasInFlightBefore = viewModel.hasActivePendingOperation(account.id)
        assertTrue("Account must have active in-flight operation", hasInFlightBefore)

        // Action: Attempt provider change to ALAMIRY
        var successCalled = false
        var errorCalled: String? = null

        val job = viewModel.updateAccountProvider(
            account = account,
            newProvider = SasProviders.ALAMIRY,
            onSuccess = { successCalled = true },
            onError = { errorCalled = it }
        )
        job.join()

        // Assert: Blocked fail-closed
        assertFalse("onSuccess must NEVER be called when operation is in-flight", successCalled)
        assertEquals(
            "Cannot change provider: account has an active in-flight operation.",
            errorCalled
        )
        assertEquals(
            "Cannot change provider: account has an active in-flight operation.",
            viewModel.error.value
        )

        // Independent Oracle: Room account provider remains strictly EARTHLINK (unchanged)
        val persisted = accountRepo.getAccountByIdOneShot(account.id)
        assertNotNull(persisted)
        assertEquals(
            "Provider must NOT be updated when in-flight operation exists",
            SasProviders.EARTHLINK,
            persisted?.operationProvider
        )
    }

    @Test
    fun preferenceManager_saveAndClearSammCredentials_interoperability() {
        val rawInput = "   https://samm.al-amiry.net/api/v1/   "
        val normalized = SammUrlNormalizer.normalize(rawInput)

        prefs.saveSammBaseUrl(normalized)
        prefs.saveSammToken("my_secret_token_123")

        assertEquals("https://samm.al-amiry.net", prefs.getSammBaseUrl())
        assertEquals("my_secret_token_123", prefs.getSammToken())
        assertTrue(prefs.isSammConfigured())

        // Clear credentials
        prefs.clearSammCredentials()
        assertNull(prefs.getSammBaseUrl())
        assertNull(prefs.getSammToken())
        assertFalse(prefs.isSammConfigured())
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    class TestEarthlinkGateway : EarthlinkGateway {
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = "test_pass_123"
        override suspend fun getBalance(): Double = 500000.0
        override suspend fun getPrepaidNeeded(): Double = 10000.0
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = "deposit_pass_456"
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse =
            UserListResponse(itemsList = emptyList(), totalCount = 0)
        override suspend fun getUserDetail(userIndex: Int): UserDetail = UserDetail()
        override suspend fun login(username: String, password: String): LoginResponse =
            LoginResponse(accessToken = "token", tokenType = "Bearer", expiresIn = 3600)
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "acc_pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }
}
