package com.example

import com.example.core.network.NewUserDepositResult
import com.example.core.network.NewTestUserResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EarthlinkMutationResponseContractTest {

    private lateinit var moshi: Moshi

    @Before
    fun setup() {
        moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
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
}

