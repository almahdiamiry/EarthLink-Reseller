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

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            lastRequest = request

            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            lastRequestBody = buffer.readUtf8()

            val body = nextResponseJson.toResponseBody("application/json".toMediaTypeOrNull())
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(nextResponseCode)
                .message("OK")
                .body(body)
                .build()
        }
    }

    @Before
    fun setup() {
        testInterceptor = FakeHttpInterceptor()

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
}
