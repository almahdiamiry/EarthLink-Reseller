package com.example.core.network

import com.example.core.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Gateway API Contract Test Suite
 *
 * Validates request payload encoding, URL path resolution, query parameters,
 * and response envelope serialization against the canonical Earthlink Gateway API contract.
 */
class EarthlinkGatewayApiContractTest {

    private lateinit var apiService: EarthlinkApiService
    private lateinit var testInterceptor: FakeHttpInterceptor
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    class FakeHttpInterceptor : Interceptor {
        var lastRequest: Request? = null
        var lastRequestBody: String? = null
        var nextResponseJson: String = "{}"
        var nextResponseCode: Int = 200
        val responseQueue = java.util.ArrayDeque<Pair<Int, String>>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            lastRequest = request

            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            lastRequestBody = buffer.readUtf8()

            val (code, json) = if (responseQueue.isNotEmpty()) {
                responseQueue.poll()!!
            } else {
                Pair(nextResponseCode, nextResponseJson)
            }

            val body = json.toResponseBody("application/json".toMediaTypeOrNull())
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(body)
                .build()
        }
    }

    @Before
    fun setup() = runBlocking {
        testInterceptor = FakeHttpInterceptor()
        testInterceptor.responseQueue.clear()
        com.example.data.repository.EarthlinkGatewayImpl.clearBalanceCache()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(testInterceptor)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://rapi.earthlink.iq/api/reseller/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        apiService = retrofit.create(EarthlinkApiService::class.java)
    }

    @Test
    fun testLoginEndpoint_encodesFormFieldsProperly() = runBlocking {
        testInterceptor.nextResponseJson = """
            {
                "access_token": "mock_test_token_12345",
                "token_type": "bearer",
                "expires_in": 3600
            }
        """.trimIndent()

        val response = apiService.login("testreseller", "secretPass", "1", "password")

        val req = testInterceptor.lastRequest
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("https://rapi.earthlink.iq/api/reseller/token", req.url.toString())
        val body = testInterceptor.lastRequestBody ?: ""
        assertTrue(body.contains("username=testreseller"))
        assertTrue(body.contains("password=secretPass"))
        assertTrue(body.contains("loginType=1"))
        assertTrue(body.contains("grant_type=password"))

        assertEquals("mock_test_token_12345", response.accessToken)
        assertEquals(3600, response.expiresIn)
    }

    @Test
    fun testGetBalance_deserializesDoubleEnvelope() = runBlocking {
        testInterceptor.nextResponseJson = """
            {
                "value": 1525000.0,
                "isSuccessful": true,
                "statusCode": 200,
                "responseMessage": null
            }
        """.trimIndent()

        val response = apiService.getBalance()

        val req = testInterceptor.lastRequest
        assertNotNull(req)
        assertEquals("GET", req!!.method)
        assertEquals("https://rapi.earthlink.iq/api/reseller/affiliate/deposit/balance", req.url.toString())

        assertTrue(response.isSuccessful ?: false)
        assertEquals(1525000.0, response.value ?: 0.0, 0.001)
    }

    @Test
    fun testSearchUsers_encodesAllPagingAndFilterParams() = runBlocking {
        testInterceptor.nextResponseJson = """
            {
                "value": {
                    "totalCount": 1,
                    "itemsList": [
                        {
                            "userIndex": 901,
                            "userID": "sub_test_01",
                            "displayName": "Ahmed Ali",
                            "mobileNumber": "07700000000",
                            "accountStatus": "Active",
                            "accountName": "Economy",
                            "expirationDate": "2026-09-20 12:00:00"
                        }
                    ]
                },
                "isSuccessful": true,
                "statusCode": 200
            }
        """.trimIndent()

        val response = apiService.searchUsers(
            startIndex = 0,
            rowCount = 50,
            orderDescending = true,
            orderBy = "ExpirationDate",
            accountStatusId = "1",
            timePeriodId = "0",
            query = "sub_test"
        )

        val req = testInterceptor.lastRequest
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("https://rapi.earthlink.iq/api/reseller/user/all", req.url.toString())
        val body = testInterceptor.lastRequestBody ?: ""
        assertTrue(body.contains("StartIndex=0"))
        assertTrue(body.contains("RowCount=50"))
        assertTrue(body.contains("OrderDescending=true"))
        assertTrue(body.contains("OrderBy=ExpirationDate"))
        assertTrue(body.contains("Query=sub_test"))

        assertEquals(1, response.value?.itemsList?.size)
        assertEquals("sub_test_01", response.value?.itemsList?.first()?.userID)
    }

    @Test
    fun testCreateUserUsingDeposit_encodesFinancialPayloadCorrectly() = runBlocking {
        testInterceptor.nextResponseJson = """
            {
                "value": 4501,
                "isSuccessful": true,
                "statusCode": 200,
                "responseMessage": "User created successfully"
            }
        """.trimIndent()

        val result = apiService.createUserUsingDeposit(
            mobile = "07712345678",
            accountIndex = 12,
            userId = "new_sub_4501",
            displayName = "Zaid Ali",
            affiliateIndex = 1001,
            userPass = "UserPass123",
            depositPass = "DepSecret456",
            customerId = 8821
        )

        val req = testInterceptor.lastRequest
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("https://rapi.earthlink.iq/api/reseller/user/newuserdeposit", req.url.toString())
        val body = testInterceptor.lastRequestBody ?: ""
        assertTrue(body.contains("UserID=new_sub_4501"))
        assertTrue(body.contains("DepositPassword=DepSecret456"))
        assertTrue(body.contains("customerId=8821"))

        assertEquals(4501, result.userIndex)
        assertTrue(result.isSuccessful ?: false)
    }

    @Test
    fun testRefillUserDeposit_formatsParameters() = runBlocking {
        testInterceptor.nextResponseJson = """
            {
                "value": true,
                "isSuccessful": true,
                "statusCode": 200,
                "responseMessage": "Refill successful"
            }
        """.trimIndent()

        val result = apiService.refillUserDeposit("sub_refill_99", "DepPass999")

        val req = testInterceptor.lastRequest
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("https://rapi.earthlink.iq/api/reseller/user/newrefilldeposit", req.url.toString())
        val body = testInterceptor.lastRequestBody ?: ""
        assertTrue(body.contains("UserID=sub_refill_99"))
        assertTrue(body.contains("DepositPassword=DepPass999"))

        assertTrue(result.value ?: false)
    }

    @Test
    fun testExtendUser_resolvesPathParameterCorrectly() = runBlocking {
        testInterceptor.nextResponseJson = """
            {
                "value": true,
                "isSuccessful": true,
                "statusCode": 200
            }
        """.trimIndent()

        val result = apiService.extendUser(7721)

        val req = testInterceptor.lastRequest
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("https://rapi.earthlink.iq/api/reseller/user/extend/7721", req.url.toString())
        assertTrue(result.value ?: false)
    }

    @Test
    fun testAccountStatement_encodesQueryParameters() = runBlocking {
        testInterceptor.nextResponseJson = """
            {
                "value": {
                    "totalCount": 1,
                    "itemsList": [
                        {
                            "occurredAt": "2026-08-27 10:30:00",
                            "operation": "Withdraw",
                            "withdrawalAmount": 35000.0,
                            "depositAmount": 0.0,
                            "note": "Refill sub_test_01",
                            "balanceAfter": 1490000.0,
                            "userID": "sub_test_01"
                        }
                    ]
                },
                "isSuccessful": true,
                "statusCode": 200
            }
        """.trimIndent()

        val result = apiService.getAccountStatement(
            startIndex = 0,
            rowCount = 20,
            query = "sub_test_01",
            opType = "Withdraw",
            fromDate = "2026-08-01",
            toDate = "2026-08-27"
        )

        val req = testInterceptor.lastRequest
        assertNotNull(req)
        assertEquals("GET", req!!.method)
        val url = req.url.toString()
        assertTrue(url.startsWith("https://rapi.earthlink.iq/api/reseller/affiliate/deposit/accountStatement?"))
        assertTrue(url.contains("Query=sub_test_01"))
        assertTrue(url.contains("OperationType=Withdraw"))

        assertEquals(1, result.value?.itemsList?.size)
        assertEquals(35000.0, result.value?.itemsList?.first()?.withdrawalAmount ?: 0.0, 0.001)
    }

    @Test
    fun testChangeAccountType_preservesBackendTypoPath() = runBlocking {
        testInterceptor.nextResponseJson = """
            {
                "value": true,
                "isSuccessful": true,
                "statusCode": 200
            }
        """.trimIndent()

        val payload = mapOf("userIndex" to 884, "accountIndex" to 5)
        val result = apiService.changeAccountType(payload)

        val req = testInterceptor.lastRequest
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("https://rapi.earthlink.iq/api/reseller/user/chnageaccounttype", req.url.toString())
        assertTrue(result.value ?: false)
    }

    @Test
    fun testNewUserDeposit_numericValueDecodesToUserIndex() {
        val json = """
            {
              "value": 36332059,
              "responseMessage": "User sajadzaki@sacx has been created successfully",
              "isSuccessful": true
            }
        """.trimIndent()

        val adapter = moshi.adapter(NewUserDepositResult::class.java)
        val result = adapter.fromJson(json)

        assertNotNull("NewUserDepositResult should not be null", result)
        assertEquals(true, result?.isSuccessful)
        assertEquals(36332059, result?.userIndex)
        assertEquals("User sajadzaki@sacx has been created successfully", result?.responseMessage)
    }

    @Test
    fun testNewTestUser_numericValueDecodesToUserIndex() {
        val json = """
            {
              "value": 36328246,
              "responseMessage": "User almahdi@sacx has been created successfully",
              "isSuccessful": true
            }
        """.trimIndent()

        val adapter = moshi.adapter(NewTestUserResult::class.java)
        val result = adapter.fromJson(json)

        assertNotNull("NewTestUserResult should not be null", result)
        assertEquals(true, result?.isSuccessful)
        assertEquals(36328246, result?.userIndex)
    }

    @Test
    fun testNewUserDeposit_businessFailureWithHttp200() {
        val json = """
            {
              "isSuccessful": false,
              "error": "Invalid deposit password",
              "responseMessage": null
            }
        """.trimIndent()

        val adapter = moshi.adapter(NewUserDepositResult::class.java)
        val result = adapter.fromJson(json)

        assertNotNull(result)
        assertEquals(false, result?.isSuccessful)
        assertEquals("Invalid deposit password", result?.errorMessage)
    }

    @Test
    fun testNewUserDeposit_missingPayloadRejected() {
        val json = """
            {
              "value": null,
              "responseMessage": "Success",
              "isSuccessful": true
            }
        """.trimIndent()

        val adapter = moshi.adapter(NewUserDepositResult::class.java)
        val result = adapter.fromJson(json)

        assertNotNull(result)
        assertEquals(true, result?.isSuccessful)
        assertNull("userIndex must be null when value is null, indicating malformed/missing payload", result?.userIndex)
    }

    @Test
    fun testRefillUserDeposit_invalidatesCachedBalance() = runBlocking {
        testInterceptor.responseQueue.clear()
        com.example.data.repository.EarthlinkGatewayImpl.clearBalanceCache()
        val mockPrefs = org.mockito.Mockito.mock(com.example.core.security.PreferenceManager::class.java)
        org.mockito.Mockito.`when`(mockPrefs.getDemoMode()).thenReturn(false)

        val gateway = com.example.data.repository.EarthlinkGatewayImpl(apiService, mockPrefs)

        // 1. Initial balance fetch -> 1,000,000 IQD
        testInterceptor.responseQueue.add(
            200 to """
                {
                    "value": 1000000.0,
                    "isSuccessful": true,
                    "statusCode": 200
                }
            """.trimIndent()
        )

        val initialBalance = gateway.getBalance()
        assertEquals(1000000.0, initialBalance, 0.001)

        // 2. Perform refill user deposit
        testInterceptor.responseQueue.add(
            200 to """
                {
                    "value": true,
                    "isSuccessful": true,
                    "statusCode": 200,
                    "responseMessage": "Refill successful"
                }
            """.trimIndent()
        )

        val refillSuccess = gateway.refillUserDeposit("sub_refill_101", "Secret123")
        assertTrue(refillSuccess)

        // 3. Second balance fetch -> API now returns 965,000 IQD
        testInterceptor.responseQueue.add(
            200 to """
                {
                    "value": 965000.0,
                    "isSuccessful": true,
                    "statusCode": 200
                }
            """.trimIndent()
        )

        val freshBalance = gateway.getBalance()
        // Must immediately reflect fresh value (965000.0) rather than returning stale cached value (1000000.0)
        assertEquals(965000.0, freshBalance, 0.001)
    }

    @Test
    fun testCreateUserUsingDeposit_invalidatesCachedBalance() = runBlocking {
        testInterceptor.responseQueue.clear()
        com.example.data.repository.EarthlinkGatewayImpl.clearBalanceCache()
        val mockPrefs = org.mockito.Mockito.mock(com.example.core.security.PreferenceManager::class.java)
        org.mockito.Mockito.`when`(mockPrefs.getDemoMode()).thenReturn(false)

        val gateway = com.example.data.repository.EarthlinkGatewayImpl(apiService, mockPrefs)

        // 1. Initial balance fetch -> 1,000,000 IQD
        testInterceptor.responseQueue.add(
            200 to """
                {
                    "value": 1000000.0,
                    "isSuccessful": true,
                    "statusCode": 200
                }
            """.trimIndent()
        )

        val initialBalance = gateway.getBalance()
        assertEquals(1000000.0, initialBalance, 0.001)

        // 2. Customer lookup by phone -> returns customer ID 1001
        testInterceptor.responseQueue.add(
            200 to """
                {
                    "value": {
                        "customerId": 1001
                    },
                    "isSuccessful": true,
                    "statusCode": 200
                }
            """.trimIndent()
        )

        // 3. Create user using deposit -> returns userIndex 5001
        testInterceptor.responseQueue.add(
            200 to """
                {
                    "value": 5001,
                    "isSuccessful": true,
                    "statusCode": 200,
                    "responseMessage": "User created successfully"
                }
            """.trimIndent()
        )

        val generatedPass = gateway.createUserUsingDeposit(
            username = "new_user_5001",
            phone = "07701234567",
            fullName = "Test User",
            accountIndex = 1,
            depositPassword = "DepositPass123"
        )
        assertNotNull(generatedPass)

        // 4. Second balance fetch -> API now returns 950,000 IQD
        testInterceptor.responseQueue.add(
            200 to """
                {
                    "value": 950000.0,
                    "isSuccessful": true,
                    "statusCode": 200
                }
            """.trimIndent()
        )

        val freshBalance = gateway.getBalance()
        // Must immediately reflect fresh value (950000.0) rather than returning stale cached value (1000000.0)
        assertEquals(950000.0, freshBalance, 0.001)
    }
}
