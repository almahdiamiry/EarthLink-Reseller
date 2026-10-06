package com.example.core.network.samm

import com.example.core.network.SasAuthException
import com.example.core.network.SasBusinessException
import com.example.core.network.SasInconclusiveException
import com.example.core.network.SasNotFoundException
import com.example.core.network.SasTransportException
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * SammGatewayErrorMappingTest
 *
 * Claim: SammNetworkClient.parseSammError maps HTTP status codes and error payloads
 *        to the typed fail-closed SasGatewayException hierarchy per SAMM contract specification:
 *        - 401 -> SasAuthException
 *        - 403 -> SasAuthException (insufficient scope)
 *        - 404 -> SasNotFoundException
 *        - 409 -> SasBusinessException (conflict)
 *        - 422 -> SasBusinessException (validation error with structured detail)
 *        - 429 -> SasTransportException (rate limit with Retry-After header)
 *        - 5xx -> SasInconclusiveException (server/transport failure)
 * Seam: JVM Unit Test (direct invocation of parseSammError against status codes, bodies, and Retrofit Response objects).
 * Independent Oracle: Explicit expected exception types and extracted message strings derived from SAMM API specification.
 */
class SammGatewayErrorMappingTest {

    @Test
    fun http401_mapsToSasAuthException() {
        val errorBody = """{"detail": "Not authenticated"}"""
        val exception = SammNetworkClient.parseSammError(401, errorBody)

        assertTrue("Expected SasAuthException for 401", exception is SasAuthException)
        assertEquals("Not authenticated", exception.message)
    }

    @Test
    fun http403_mapsToSasAuthException_insufficientScope() {
        val errorBody = """{"detail": "Forbidden: missing scope customers:write"}"""
        val exception = SammNetworkClient.parseSammError(403, errorBody)

        assertTrue("Expected SasAuthException for 403", exception is SasAuthException)
        assertEquals("Forbidden: missing scope customers:write", exception.message)
    }

    @Test
    fun http404_mapsToSasNotFoundException() {
        val errorBody = """{"detail": "Customer not found"}"""
        val exception = SammNetworkClient.parseSammError(404, errorBody)

        assertTrue("Expected SasNotFoundException for 404", exception is SasNotFoundException)
        val notFound = exception as SasNotFoundException
        assertEquals(404, notFound.statusCode)
        assertEquals("Customer not found", notFound.message)
    }

    @Test
    fun http409_mapsToSasBusinessException_conflict() {
        val errorBody = """{"detail": "Username already exists"}"""
        val exception = SammNetworkClient.parseSammError(409, errorBody)

        assertTrue("Expected SasBusinessException for 409", exception is SasBusinessException)
        val business = exception as SasBusinessException
        assertEquals(409, business.statusCode)
        assertEquals("Username already exists", business.errorMessage)
    }

    @Test
    fun http422_withValidationList_mapsToSasBusinessException() {
        val errorBody = """
            {
              "detail": [
                {
                  "loc": ["body", "plan_id"],
                  "msg": "field required",
                  "type": "value_error.missing"
                }
              ]
            }
        """.trimIndent()
        val exception = SammNetworkClient.parseSammError(422, errorBody)

        assertTrue("Expected SasBusinessException for 422", exception is SasBusinessException)
        val business = exception as SasBusinessException
        assertEquals(422, business.statusCode)
        assertTrue(
            "Expected message to mention body.plan_id and field required",
            business.errorMessage.contains("body.plan_id: field required")
        )
    }

    @Test
    fun http422_withStringDetail_mapsToSasBusinessException() {
        val errorBody = """{"detail": "Invalid parameter provided"}"""
        val exception = SammNetworkClient.parseSammError(422, errorBody)

        assertTrue("Expected SasBusinessException for 422", exception is SasBusinessException)
        val business = exception as SasBusinessException
        assertEquals(422, business.statusCode)
        assertEquals("Invalid parameter provided", business.errorMessage)
    }

    @Test
    fun http429_withRetryAfterHeader_mapsToSasTransportException() {
        val errorBody = """{"detail": "Rate limit exceeded"}"""
        val headers = Headers.Builder()
            .add("Retry-After", "15")
            .build()
        val exception = SammNetworkClient.parseSammError(429, errorBody, headers)

        assertTrue("Expected SasTransportException for 429", exception is SasTransportException)
        assertTrue(
            "Expected message to include retry after 15",
            exception.message?.contains("Retry after 15 seconds") == true
        )
    }

    @Test
    fun http429_withoutRetryAfterHeader_mapsToSasTransportException() {
        val errorBody = """{"detail": "Rate limit exceeded"}"""
        val exception = SammNetworkClient.parseSammError(429, errorBody, null)

        assertTrue("Expected SasTransportException for 429", exception is SasTransportException)
        assertEquals("Rate limit exceeded", exception.message)
    }

    @Test
    fun http500_mapsToSasInconclusiveException() {
        val errorBody = """{"message": "Internal database query error"}"""
        val exception = SammNetworkClient.parseSammError(500, errorBody)

        assertTrue("Expected SasInconclusiveException for 500", exception is SasInconclusiveException)
        val inconclusive = exception as SasInconclusiveException
        assertEquals(500, inconclusive.statusCode)
        assertEquals("Internal database query error", inconclusive.message)
    }

    @Test
    fun http502_badGateway_mapsToSasInconclusiveException() {
        val errorBody = "502 Bad Gateway"
        val exception = SammNetworkClient.parseSammError(502, errorBody)

        assertTrue("Expected SasInconclusiveException for 502", exception is SasInconclusiveException)
        val inconclusive = exception as SasInconclusiveException
        assertEquals(502, inconclusive.statusCode)
        assertEquals("502 Bad Gateway", inconclusive.message)
    }

    @Test
    fun http503_serviceUnavailable_mapsToSasInconclusiveException() {
        val errorBody = """{"detail": "Service Temporarily Unavailable"}"""
        val exception = SammNetworkClient.parseSammError(503, errorBody)

        assertTrue("Expected SasInconclusiveException for 503", exception is SasInconclusiveException)
        val inconclusive = exception as SasInconclusiveException
        assertEquals(503, inconclusive.statusCode)
        assertEquals("Service Temporarily Unavailable", inconclusive.message)
    }

    @Test
    fun retrofitResponseOverload_mapsAccurately() {
        val errorBody = """{"detail": "Customer not found"}""".toResponseBody("application/json".toMediaTypeOrNull())
        val response = Response.error<CustomerResponse>(404, errorBody)

        val exception = SammNetworkClient.parseSammError(response)
        assertTrue("Expected SasNotFoundException", exception is SasNotFoundException)
        assertEquals("Customer not found", exception.message)
    }

    @Test
    fun urlNormalization_handlesVariedTrailingSlashesAndApiPrefixes() {
        assertEquals("https://samm.example.com/", SammNetworkClient.normalizeBaseUrl("https://samm.example.com"))
        assertEquals("https://samm.example.com/", SammNetworkClient.normalizeBaseUrl("https://samm.example.com/"))
        assertEquals("https://samm.example.com/", SammNetworkClient.normalizeBaseUrl("https://samm.example.com/api/v1"))
        assertEquals("https://samm.example.com/", SammNetworkClient.normalizeBaseUrl("https://samm.example.com/api/v1/"))
        assertEquals("http://192.168.1.50:8000/", SammNetworkClient.normalizeBaseUrl("http://192.168.1.50:8000/api/v1"))
        assertEquals("http://192.168.1.50:8000/", SammNetworkClient.normalizeBaseUrl("http://192.168.1.50:8000/api/v1/"))
        assertEquals("http://192.168.1.50:8000/", SammNetworkClient.normalizeBaseUrl("http://192.168.1.50:8000"))
        assertEquals("https://samm.iq/", SammNetworkClient.normalizeBaseUrl("  samm.iq  "))
    }
}
