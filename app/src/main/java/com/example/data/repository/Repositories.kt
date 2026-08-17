package com.example.data.repository

import com.example.core.database.*
import com.example.core.model.*
import com.example.core.sync.OutboxManager
import com.example.core.network.ChangePasswordReq
import com.example.core.network.EarthlinkApiService
import com.example.core.network.PasswordReq
import kotlinx.coroutines.flow.distinctUntilChanged
import com.example.domain.repository.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import androidx.room.withTransaction
import androidx.annotation.VisibleForTesting

class EarthlinkGatewayImpl(private val apiService: EarthlinkApiService, private val prefs: com.example.core.security.PreferenceManager) : EarthlinkGateway {

    companion object {
        val customStatements = java.util.concurrent.CopyOnWriteArrayList<com.example.core.model.AccountStatementItem>()
        private val cachedCosts = java.util.concurrent.ConcurrentHashMap<Int, Double>()
        private val costMutexes = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.sync.Mutex>()
        @Volatile private var cachedBalance: Double? = null
        @Volatile private var lastBalanceFetchTime: Long = 0L
        private val balanceMutex = kotlinx.coroutines.sync.Mutex()
        val demoUsersCache = java.util.concurrent.ConcurrentHashMap<Int, com.example.core.model.UserDetail>()
        val demoSearchCache = java.util.concurrent.ConcurrentHashMap<Int, com.example.core.model.UserListItem>()
        private val isDemoUsersGenerated = java.util.concurrent.atomic.AtomicBoolean(false)

        suspend fun clearBalanceCache() {
            balanceMutex.withLock {
                cachedBalance = null
                lastBalanceFetchTime = 0L
                demoUsersCache.clear()
                demoSearchCache.clear()
                isDemoUsersGenerated.set(false)
            }
        }
        fun ensureDemoUsersGenerated() {
            if (isDemoUsersGenerated.get()) return
            if (isDemoUsersGenerated.compareAndSet(false, true)) {
                val names = listOf("Mohammed Ali", "Ahmed Hassan", "Zainab Hussein", "Fatima Abbas", "Ali Reza", "Huda Kamil", "Mustafa Karim", "Noor Saad", "Saif Al-Din", "Dhuha Nabil")
            val packages = listOf("eco", "plus", "standard", "turbo", "more", "business")
            val statuses = listOf("Active", "Expired", "Expiring Soon")
            for (i in 0 until 50) {
                val id = 1000 + i
                val name = names[i % names.size] + " $i"
                val pkg = packages[i % packages.size]
                val status = statuses[i % statuses.size]
                val expDate = when (status) {
                    "Active" -> "2026-10-15T00:00:00"
                    "Expired" -> "2023-01-01T00:00:00"
                    "Expiring Soon" -> "2024-10-01T00:00:00"
                    else -> "2025-01-01T00:00:00"
                }
                val activeDays = when (status) {
                    "Active" -> 20.0
                    "Expired" -> 0.0
                    "Expiring Soon" -> 2.0
                    else -> 0.0

                }
                val isDemoOnline = status == "Active" && (i % 3 == 0)
                val onlineStatusVal = if (isDemoOnline) "Online" else "Offline"

                demoSearchCache[id] = com.example.core.model.UserListItem(
                    userIndexLower = id,
                    userIDLower = "demo_user_$id",
                    customerNameLower = name,
                    mobileNumberLower = "0770${String.format("%07d", i)}",
                    accountStatusLower = status,
                    expirationDateLower = expDate,
                    accountNameLower = pkg,
                    activeDaysLeftLower = activeDays,
                    onlineStatusLower = onlineStatusVal
                )
                val demoSession = if (isDemoOnline) {
                    com.example.core.model.OnlineSession(
                        userIPLower = "10.100.${i % 255}.${i % 255}",
                        onlineTimeLower = "${(i % 12) + 1}h ${(i % 59) + 1}m",
                        onlineStatusLower = "Online",
                        onlineSinceLower = "2026-07-01T00:15:00"
                    )
                } else {
                    null

                }
                demoUsersCache[id] = com.example.core.model.UserDetail(
                    userIndexLower = id,
                    userIDLower = "demo_user_$id",
                    customerFullNameLower = name,
                    customerNameLower = name,
                    mobileNumberLower = "0770${String.format("%07d", i)}",
                    accountStatusLower = status,
                    expirationDateLower = expDate,
                    packageNameLower = pkg,
                    activeDaysLeftLower = activeDays,
                    onlineSessionLower = demoSession,
                    currentIPLower = if (isDemoOnline) "10.100.${i % 255}.${i % 255}" else null,
                    onlineSessionTimeLower = if (isDemoOnline) "${(i % 12) + 1}h ${(i % 59) + 1}m" else null
                )
            }
            }
        }
    }

    private inline suspend fun <reified T> safeApiCall(defaultOnNull: T? = null, call: suspend () -> ApiEnvelope<T>): T {
        val response = try {
            call()
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            val msg = e.localizedMessage ?: "Unknown connection error"
            var detail = msg
            var prefix = "API Protocol Error (Parsing/Moshi or Server error)."
            if (e is java.io.IOException || e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException || e is javax.net.ssl.SSLHandshakeException) {
                prefix = "Network unavailable. Check connection and retry."
            } else if (e is retrofit2.HttpException) {
                val code = e.code()
                val errorBody = try { e.response()?.errorBody()?.string() } catch(ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex; null }
                if (code == 401) {
                    val isGoogle = prefs.getAuthToken()?.startsWith("google_oauth_session_") == true
                    if (isGoogle) {
                        prefs.saveEarthlinkApiToken(null)
                        throw Exception("Earthlink API unauthorized. Please check ISP Admin credentials in Settings.", e)
                    } else {
                        prefs.clearAuthToken()
                        throw Exception("Session expired. Please log in again.", e)
                    }
                }
                prefix = "Server HTTP Error (Status $code) - live rapi.earthlink.iq reseller endpoint error."
                detail = errorBody ?: msg
            } else if (e is com.squareup.moshi.JsonDataException || e is com.squareup.moshi.JsonEncodingException) {
                prefix = "Moshi JSON Deserialization Mismatch."
                detail = "The server response did not match local structures: ${e.message}"
            }
            throw Exception("$prefix Detail: $detail", e)
        }
        if (response.isSuccessful == true) {
            val value = response.value
            if (value != null) return value
            if (defaultOnNull != null) return defaultOnNull
            if (T::class == Boolean::class) {
                @Suppress("UNCHECKED_CAST")
                return true as T
            }
            if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                return Unit as T
            }
            throw Exception(response.responseMessage ?: response.error ?: "API returned null payload without explicit data.")
        } else {
            val msg = response.responseMessage ?: response.error ?: "Earthlink rejected this action."
            if (response.statusCode == 401 || msg.contains("Unauthorized", ignoreCase = true) || msg.contains("expired", ignoreCase = true)) {
                val isGoogle = prefs.getAuthToken()?.startsWith("google_oauth_session_") == true
                if (isGoogle) {
                    prefs.saveEarthlinkApiToken(null)
                    throw Exception("Earthlink API unauthorized. Please check ISP Admin credentials in Settings.")
                } else {
                    prefs.clearAuthToken()
                    throw Exception("Session expired. Please log in again.")
                }
            }
            throw Exception(msg)
        }
    }
    override suspend fun login(username: String, password: String): LoginResponse {
        if (prefs.getDemoMode()) {
            kotlinx.coroutines.delay(1000)
            return LoginResponse(
                accessToken = "demo_token_12345",
                tokenType = "bearer",
                expiresIn = 3600
            )
        }
        return try {
            apiService.login(username, password)
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                throw Exception("Session expired. Please log in again.")
            }
            val msg = e.localizedMessage ?: "Unknown login error"
            var detail = msg
            var prefix = "API Protocol Error during Authentication."
            if (e is java.io.IOException || e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException || e is javax.net.ssl.SSLHandshakeException) {
                prefix = "Network unavailable. Check connection and retry."
            } else if (e is retrofit2.HttpException) {
                val code = e.code()
                val errorBody = try { e.response()?.errorBody()?.string() } catch(ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex; null }
                prefix = "Server Authentication HTTP Error (Status $code)."
                detail = errorBody ?: msg
            } else if (e is com.squareup.moshi.JsonDataException || e is com.squareup.moshi.JsonEncodingException) {
                prefix = "Moshi JSON Authentication Deserialization Mismatch."
                detail = "Structure mismatch: ${e.message}"
            }
            throw Exception("$prefix Detail: $detail", e)


        }
    }
    override suspend fun getBalance(): Double {
        if (prefs.getDemoMode()) {
            return 282250.0
        }
        return balanceMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = cachedBalance
            if (cached != null && (now - lastBalanceFetchTime < 15000)) {
                return@withLock cached
            }
            val fetched = safeApiCall { apiService.getBalance() }
            cachedBalance = fetched
            lastBalanceFetchTime = now
            fetched
        }
    }
    override suspend fun getTestUsersCount(affiliateIndex: Int?): Int {
        return try {
            val responseBody = apiService.getTestUsersCount(affiliateIndex).string()
            if (com.alamiry.earthlinkreseller.BuildConfig.DEBUG) {
                android.util.Log.d("EarthlinkRepository", "DEBUG: getTestUsersCount RAW: $responseBody")
            }
            try {
                val json = org.json.JSONObject(responseBody)
                val value = json.optInt("value", json.optInt("Value", -1))
                if (value != -1) {
                    value 
                } else {
                    json.optInt("count", json.optInt("Count", 0))
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                // If it's not valid JSON, it might just be a raw number string
                responseBody.trim().toDoubleOrNull()?.toInt() ?: 0
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            if (com.alamiry.earthlinkreseller.BuildConfig.DEBUG) {
                android.util.Log.d("EarthlinkRepository", "DEBUG: getTestUsersCount EXCEPTION: ${e.message}")
            }
            0
        }
    }
    override suspend fun getActiveTestUsersCount(): Int {
        if (prefs.getDemoMode()) return 3
        return try {
            val response = apiService.getTestsUsed(0, 1)
            var total = response.totalRecords ?: response.totalRecordsAlt ?: 0
            if (total == 0) {
                val v = response.value
                if (v is Map<*, *>) {
                    val tc = v["totalCount"] ?: v["TotalCount"] ?: v["totalRecords"] ?: v["TotalRecords"]
                    if (tc != null) {
                        total = tc.toString().toDouble().toInt()
                    }
                 else {
                        val items = v["itemsList"] ?: v["ItemsList"] ?: v["items"] ?: v["Items"]
                        if (items is List<*>) {
                            total = items.size
                        }
                 }
                }
            }
            total
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            android.util.Log.w("EarthlinkRepository", "Failed to fetch active test users: ${e.message}", e)
            if (e is retrofit2.HttpException && e.code() == 401) {
                prefs.clearAuthToken()
                throw Exception("Session expired. Please log in again.", e)
            }
            0


        }
    }
    override suspend fun getPrepaidNeeded(): Double {
        val value = safeApiCall { apiService.getPrepaidNeeded() }
        return calculatePrepaidNeededFromPayload(value)


    }
    private fun calculatePrepaidNeededFromPayload(value: Any?): Double {
        if (value == null) return 0.0
        val rows = mutableListOf<Map<String, Any>>()

        if (value is List<*>) {
            for (item in value) {
                if (item is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    rows.add(item as Map<String, Any>)
                }
            }
        } else if (value is Map<*, *>) {
            val keys = listOf("itemsList", "ItemsList", "items", "Items", "rows", "Rows", "prepaidNeeded", "PrepaidNeeded")
            for (key in keys) {
                val list = value[key]
                if (list is List<*>) {
                    for (item in list) {
                        if (item is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            rows.add(item as Map<String, Any>)
                        }
                    }
                    break

                }
            }
        }
        var total = 0.0
        for (row in rows) {
            val neededNum = getFirstNumber(row, "neededCount", "NeededCount", "needed", "Needed")
            val costNum = getFirstNumber(row, "accountCost", "AccountCost", "cost", "Cost")
            var totalNum = getFirstNumber(row, "totalCost", "TotalCost", "total", "Total")

            if (totalNum <= 0.0 && neededNum > 0.0 && costNum > 0.0) {
                totalNum = neededNum * costNum
            }
            total += totalNum
        }
        return total

    }
    private fun getFirstNumber(row: Map<String, Any>, vararg keys: String): Double {
        for (key in keys) {
            val value = row[key] ?: continue
            val num = parseMoneyNumber(row, key)
            if (num != 0.0) return num
        }
        return 0.0

    }
    private fun parseMoneyNumber(row: Map<String, Any>, key: String): Double {
        val value = row[key] ?: return 0.0
        if (value is Number) return value.toDouble()
        if (value is Map<*, *>) {
            val nestedVal = value["value"] ?: value["Value"] ?: return 0.0
            if (nestedVal is Number) return nestedVal.toDouble()
            val textValue = nestedVal.toString().trim()
            return textValue.toDoubleOrNull() ?: 0.0
        }
        val textValue = value.toString().trim()
        if (textValue.isEmpty()) return 0.0
        val cleanValue = textValue
            .replace("IQD", "", ignoreCase = true)
            .replace(",", "")
            .replace(" ", "")
            .trim()
        return cleanValue.toDoubleOrNull() ?: 0.0

    }
    override suspend fun getPackages(): List<AccountPackage> {
        return try {
            safeApiCall { apiService.getPackages() }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            if (prefs.getDemoMode()) {
                listOf(
                    AccountPackage(1, "Eco", false, 15000.0),
                    AccountPackage(2, "Standard", false, 25000.0),
                    AccountPackage(3, "Plus", false, 35000.0),
                    AccountPackage(4, "Turbo", false, 45000.0),
                    AccountPackage(5, "Business", false, 60000.0)
                )
            } else {
                throw e

            }
        }
    }
    override suspend fun getAccountCost(accountIndex: Int): Double = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cached = cachedCosts[accountIndex]
        if (cached != null && cached > 0.0) {
            return@withContext cached
        }
        val mutex = costMutexes.getOrPut(accountIndex) { kotlinx.coroutines.sync.Mutex() }
        mutex.withLock {
            val retry = cachedCosts[accountIndex]
            if (retry != null && retry > 0.0) {
                return@withLock retry
            }
            val cost = try {
                val responseBody = apiService.getAccountCost(accountIndex)
                val jsonString = responseBody.use { it.string() }
                var c = 0.0
                try {
                    val jsonObject = org.json.JSONObject(jsonString)
                    if (jsonObject.has("value") && !jsonObject.isNull("value")) {
                        val valueObj = jsonObject.get("value")
                        if (valueObj is org.json.JSONObject && valueObj.has("value") && !valueObj.isNull("value")) {
                            c = valueObj.optDouble("value", 0.0)
                            if (c == 0.0) {
                                val str = valueObj.optString("value", "").replace(Regex("[^0-9.]"), "")
                                c = str.toDoubleOrNull() ?: 0.0
                            }
                        }
                     else {
                            c = jsonObject.optDouble("value", 0.0)
                            if (c == 0.0) {
                                val str = jsonObject.optString("value", "").replace(Regex("[^0-9.]"), "")
                                c = str.toDoubleOrNull() ?: 0.0
                            }
                     }
                    }
                    if (c == 0.0 && (jsonObject.has("responseMessage") || jsonObject.has("error"))) {
                        val msg = jsonObject.optString("responseMessage", "") + jsonObject.optString("error", "")
                        val parts = msg.split(Regex("(?i)Account cost is "))
                        if (parts.size > 1) {
                            val costPart = parts[1].split(Regex("(?i) IQD"))
                            if (costPart.isNotEmpty()) {
                                val numStr = costPart[0].replace(Regex("[^0-9.]"), "")
                                c = numStr.toDoubleOrNull() ?: 0.0
                            }
                        }
                     else {
                            val cParts = msg.split(Regex("(?i)cost[\\s:]+"))
                            if (cParts.size > 1) {
                                val cPart = cParts[1].split(Regex("[^0-9.]"))
                                for (p in cPart) {
                                    val d = p.toDoubleOrNull()
                                    if (d != null && d > 1000.0) {
                                        c = d
                                        break
                                    }
                                }
                            }
                     }
                    }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                    // Ignore parse errors, returning 0.0 instead
                }
                c
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                0.0
            }
            if (cost > 0.0) {
                cachedCosts[accountIndex] = cost
            }
            cost
        }
    }
    override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
        if (prefs.getDemoMode()) {
            ensureDemoUsersGenerated()
            val filtered = demoSearchCache.values.filter {
                query.isBlank() || it.userIDLower?.contains(query, true) == true ||
                it.customerNameLower?.contains(query, true) == true ||
                it.mobileNumberLower?.contains(query, true) == true
            }.sortedByDescending { it.userIndexLower }
            val paginated = filtered.drop(startIndex).take(rowCount)
            return UserListResponse(
                itemsList = paginated,
                totalCount = filtered.size
            )
        }
        val response = safeApiCall {
            apiService.searchUsers(
                startIndex = startIndex,
                rowCount = rowCount,
                orderDescending = true,
                orderBy = "",
                accountStatusId = "",
                timePeriodId = "",
                query = query
            )
        }
        return response


    }
    override suspend fun getUserDetail(userIndex: Int): UserDetail {
        if (prefs.getDemoMode()) {
            ensureDemoUsersGenerated()
            val cached = demoUsersCache[userIndex]
            if (cached != null) {
                return cached
            }
        }
        val userDetail = safeApiCall { apiService.getUserDetail(userIndex) }
        var finalUserDetail = userDetail
        val targetUserId = userDetail.userID.trim().lowercase()
        if (targetUserId.isNotEmpty()) {
            try {
                val activeSessionsEnv = apiService.getActiveSessions(0, 200)
                if (activeSessionsEnv.isSuccessful == true) {
                    val activeList = activeSessionsEnv.value?.itemsList
                    val matchingSession = activeList?.find {
                        val sessionIndex = it.userIndex
                        val sessionUserId = it.userID.trim().lowercase()
                        
                        (sessionIndex != 0 && (sessionIndex == userIndex || sessionIndex == userDetail.userIndex)) ||
                        (sessionUserId.isNotEmpty() && sessionUserId == targetUserId) ||
                        (sessionUserId.isNotEmpty() && targetUserId.isNotEmpty() && 
                         sessionUserId.substringBefore("@") == targetUserId.substringBefore("@"))
                    }
                    if (matchingSession != null) {
                        val session = OnlineSession(
                            userIPLower = matchingSession.userIP,
                            onlineTimeLower = matchingSession.onlineTime,
                            onlineStatusLower = matchingSession.onlineStatus ?: "Online",
                            onlineSinceLower = matchingSession.onlineSince
                        )
                        finalUserDetail = userDetail.copy(
                            onlineSessionLower = session,
                            currentIPLower = matchingSession.userIP,
                            onlineSessionTimeLower = matchingSession.onlineTime
                        )
                    }
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                // Best effort hydration
            }
        }
        return finalUserDetail


    }
    override suspend fun autocompleteUser(query: String): List<AutocompleteUser> {
        val value = safeApiCall { apiService.autocompleteUser(query) }
        return extractAutocompleteUsersFromPayload(value)


    }
    private fun extractAutocompleteUsersFromPayload(value: Any?): List<AutocompleteUser> {
        if (value == null) return emptyList()
        val rows = mutableListOf<Map<String, Any>>()
        if (value is List<*>) {
            for (item in value) {
                if (item is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    rows.add(item as Map<String, Any>)
                }
            }
        } else if (value is Map<*, *>) {
            val keys = listOf("itemsList", "ItemsList", "items", "Items", "users", "Users")
            for (key in keys) {
                val list = value[key]
                if (list is List<*>) {
                    for (item in list) {
                        if (item is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            rows.add(item as Map<String, Any>)
                        }
                    }
                    break
                }
            }
        }
        val resultList = mutableListOf<AutocompleteUser>()
        for (row in rows) {
            val userIndexNum = row["userIndex"] ?: row["UserIndex"] ?: row["userindex"] ?: row["index"] ?: 0
            val userIndex = when (userIndexNum) {
                is Number -> userIndexNum.toInt()
                else -> userIndexNum.toString().toDoubleOrNull()?.toInt() ?: userIndexNum.toString().toIntOrNull() ?: 0
            }
            val userIDVal = row["userID"] ?: row["UserID"] ?: row["userId"] ?: row["UserId"] ?: row["userid"] ?: ""
            val userID = userIDVal.toString()
            if (userID.isNotEmpty()) {
                resultList.add(AutocompleteUser(userIndex, userID))
            }
        }
        return resultList


    }
    override suspend fun checkUsernameAvailable(userId: String): Boolean {
        if (prefs.getDemoMode()) {
            return userId.trim().lowercase() != "ali_hassan" && userId.trim().lowercase() != "mustafa_kh"
        }
        return safeApiCall { apiService.checkUserAvailable(userId) }


    }
    override suspend fun checkCustomerByPhone(phone: String): String? {
        if (prefs.getDemoMode()) {
            return if (phone.contains("1234")) "1001" else null
        }
        val result = safeApiCall { apiService.customerLookupByPhone(phone) }
        return extractCustomerIdFromLookup(result)


    }
    private fun extractCustomerIdFromLookup(value: Any?): String? {
        if (value == null) return null
        val rows = mutableListOf<Map<String, Any>>()
        if (value is List<*>) {
            for (item in value) {
                if (item is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    rows.add(item as Map<String, Any>)
                }
            }
        } else if (value is Map<*, *>) {
            val keys = listOf("itemsList", "ItemsList", "items", "Items", "customers", "Customers")
            for (key in keys) {
                val list = value[key]
                if (list is List<*>) {
                    for (item in list) {
                        if (item is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            rows.add(item as Map<String, Any>)
                        }
                    }
                    break
                }
            }
            if (rows.isEmpty()) {
                val singleId = value["customerId"] ?: value["CustomerId"] ?: value["id"] ?: value["ID"]
                if (singleId != null) return singleId.toString()
            }
        }
        if (rows.isNotEmpty()) {
            val first = rows[0]
            val id = first["customerId"] ?: first["CustomerId"] ?: first["id"] ?: first["ID"]
            if (id != null) return id.toString()
        }
        return null


    }
    override suspend fun createCustomer(name: String, phone: String): Boolean {
        if (prefs.getDemoMode()) {
            return true
        }
        val result = safeApiCall { apiService.createCustomer(name, phone) }
        return result != null


    }
    override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? {
        if (prefs.getDemoMode()) {
            kotlinx.coroutines.delay(800)
            val packages = getPackages()
            val pkgName = packages.find { it.accountIndex == accountIndex }?.accountName ?: "Standard"
            val newId = (demoSearchCache.keys.maxOrNull() ?: 1000) + 1
            demoSearchCache[newId] = com.example.core.model.UserListItem(
                userIndexLower = newId,
                userIDLower = username,
                customerNameLower = fullName,
                mobileNumberLower = phone,
                accountStatusLower = "Active",
                expirationDateLower = "2026-10-15T00:00:00",
                accountNameLower = pkgName,
                activeDaysLeftLower = 20.0
            )
            demoUsersCache[newId] = com.example.core.model.UserDetail(
                userIndexLower = newId,
                userIDLower = username,
                customerFullNameLower = fullName,
                customerNameLower = fullName,
                mobileNumberLower = phone,
                accountStatusLower = "Active",
                expirationDateLower = "2026-10-15T00:00:00",
                packageNameLower = pkgName,
                activeDaysLeftLower = 20.0
            )
            return "DemoPass_123"
        }
        val userPass = "Pass_${(100000..999999).random()}"
        val affiliateIndex = 1

        val success = safeApiCall {
            apiService.createTestUser(
                mobile = phone,
                accountIndex = accountIndex,
                userId = username,
                displayName = fullName,
                affiliateIndex = affiliateIndex,
                userPass = userPass
            )
        }
        return if (success != null && success) userPass else null
    }
    override suspend fun createUserUsingDeposit(
        username: String,
        phone: String,
        fullName: String,
        accountIndex: Int,
        depositPassword: String
    ): String? {
        if (prefs.getDemoMode()) {
            kotlinx.coroutines.delay(800)
            val packages = getPackages()
            val pkgName = packages.find { it.accountIndex == accountIndex }?.accountName ?: "Standard"
            val newId = (demoSearchCache.keys.maxOrNull() ?: 1000) + 1
            demoSearchCache[newId] = com.example.core.model.UserListItem(
                userIndexLower = newId,
                userIDLower = username,
                customerNameLower = fullName,
                mobileNumberLower = phone,
                accountStatusLower = "Active",
                expirationDateLower = "2026-10-15T00:00:00",
                accountNameLower = pkgName,
                activeDaysLeftLower = 20.0
            )
            demoUsersCache[newId] = com.example.core.model.UserDetail(
                userIndexLower = newId,
                userIDLower = username,
                customerFullNameLower = fullName,
                customerNameLower = fullName,
                mobileNumberLower = phone,
                accountStatusLower = "Active",
                expirationDateLower = "2026-10-15T00:00:00",
                packageNameLower = pkgName,
                activeDaysLeftLower = 20.0
            )
            return "DemoPass_123"

        }
        var customerIdStr = checkCustomerByPhone(phone)
        if (customerIdStr == null) {
            val custResult = safeApiCall { apiService.createCustomer(fullName, phone) }
            customerIdStr = extractCustomerIdFromLookup(custResult)
        }
        val customerId = customerIdStr?.toDoubleOrNull()?.toInt() ?: customerIdStr?.toIntOrNull()
            ?: throw IllegalStateException("CUSTOMER_ID_REQUIRED: Unable to resolve customer identity for phone $phone")

        val userPass = "Pass_${(100000..999999).random()}"
        val affiliateIndex = 1

        val success = safeApiCall {
            apiService.createUserUsingDeposit(
                mobile = phone,
                accountIndex = accountIndex,
                userId = username,
                displayName = fullName,
                affiliateIndex = affiliateIndex,
                userPass = userPass,
                depositPass = depositPassword,
                customerId = customerId
            )
        }
        return if (success != null && success) userPass else null
    }
    override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
        if (prefs.getDemoMode()) {
            kotlinx.coroutines.delay(800)
            val matchedIndex = demoUsersCache.entries.find { it.value.userIDLower?.trim()?.lowercase() == userId.trim().lowercase() }?.key
            if (matchedIndex != null) {
                val current = demoUsersCache[matchedIndex]
                if (current != null) {
                    demoUsersCache[matchedIndex] = current.copy(
                        accountStatusLower = "Active",
                        expirationDateLower = "2026-11-15T00:00:00",
                        activeDaysLeftLower = 30.0
                    )
                    demoSearchCache[matchedIndex] = demoSearchCache[matchedIndex]?.copy(accountStatusLower = "Active", expirationDateLower = "2026-11-15T00:00:00", activeDaysLeftLower = 30.0)
                        ?: com.example.core.model.UserListItem(userIndexLower = matchedIndex, accountStatusLower = "Active", expirationDateLower = "2026-11-15T00:00:00", activeDaysLeftLower = 30.0)
                }
            }
            return true
        }
        return safeApiCall {
            apiService.refillUserDeposit(
                userId = userId,
                depositPass = depositPassword
            )


        }
    }
    override suspend fun extendUser(userIndex: Int): Boolean {
        if (prefs.getDemoMode()) {
            kotlinx.coroutines.delay(500)
            val current = demoUsersCache[userIndex] ?: safeApiCall { apiService.getUserDetail(userIndex) }
            demoUsersCache[userIndex] = current.copy(
                accountStatusLower = "Active",
                expirationDateLower = "2026-11-15T00:00:00",
                activeDaysLeftLower = 30.0
            )
            demoSearchCache[userIndex] = demoSearchCache[userIndex]?.copy(accountStatusLower = "Active", expirationDateLower = "2026-11-15T00:00:00", activeDaysLeftLower = 30.0) ?: com.example.core.model.UserListItem(userIndexLower = userIndex, accountStatusLower = "Active", expirationDateLower = "2026-11-15T00:00:00", activeDaysLeftLower = 30.0)
            return true
        }
        return safeApiCall { apiService.extendUser(userIndex) }


    }
    override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> {
        val response = safeApiCall {
            apiService.getAccountStatement(
                startIndex = startIndex,
                rowCount = rowCount,
                query = query,
                opType = "",
                fromDate = "",
                toDate = ""
            )
        }
        val apiItems = response.itemsList ?: emptyList()
        if (customStatements.isEmpty()) return apiItems
        val filteredCustom = if (query.isBlank()) customStatements else customStatements.filter {
            it.note?.contains(query, true) == true || it.operation?.contains(query, true) == true
        }
        return (filteredCustom + apiItems)
    }
    override suspend fun showUserPassword(userIndex: Int, userId: String): String {
        if (prefs.getDemoMode()) {
            return "DemoPass7788"
        }
        val payload = PasswordReq(userIndex, userId)
        val responseBody = try { apiService.showUserPassword(payload) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
        val responseStr = responseBody?.use { it.string() } ?: ""
        
        try {
            val root = JSONObject(responseStr)
            if (root.has("item")) {
                val item = root.get("item")
                return extractPasswordText(item)
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            // ignore
        }
        return extractPasswordText(responseStr)
    }

    override suspend fun showAccountPassword(userIndex: Int, userId: String): String {
        if (prefs.getDemoMode()) {
            return "DemoAccPass4321"
        }
        val payload = PasswordReq(userIndex, userId)
        val responseBody = try { apiService.showAccountPassword(payload) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
        val responseStr = responseBody?.use { it.string() } ?: ""
        
        try {
            val root = JSONObject(responseStr)
            if (root.has("item")) {
                val item = root.get("item")
                return extractPasswordText(item)
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            // ignore
        }
        return extractPasswordText(responseStr)
    }

    private fun extractPasswordText(value: Any?): String {
        if (value == null || value == "") return ""
        if (value is String) return value
        if (value is Map<*, *>) {
            for (key in listOf("value", "Value", "password", "Password", "userPassword", "UserPassword", "accountPassword", "AccountPassword")) {
                val v = value[key]
                if (v != null && v != "") return v.toString()
            }
        }
        return value.toString()


    }
    override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean {
        if (prefs.getDemoMode()) {
            return true
        }
        val payload = ChangePasswordReq(userIndex, userId, newPass)
        return safeApiCall(defaultOnNull = true) { apiService.changeUserPassword(payload) }


    }
    override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean {
        if (prefs.getDemoMode()) {
            return true
        }
        val payload = ChangePasswordReq(userIndex, userId, newPass)
        return safeApiCall(defaultOnNull = true) { apiService.changeAccountPassword(payload) }


    }
    override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean {
        if (prefs.getDemoMode()) {
            val status = if (active) "Active" else "Suspended"
            val current = demoUsersCache[userIndex] ?: safeApiCall { apiService.getUserDetail(userIndex) }
            demoUsersCache[userIndex] = current.copy(accountStatusLower = status)
            demoSearchCache[userIndex] = demoSearchCache[userIndex]?.copy(accountStatusLower = status) ?: com.example.core.model.UserListItem(userIndexLower = userIndex, accountStatusLower = status)
            return true
        }
        val user = getUserDetail(userIndex)
        val copyPayload = user.copy(
            userActiveLower = true,
            userActiveUpper = true,
            userActiveManageLower = active,
            userActiveManageUpper = active,
            isBlockedLower = false,
            isBlockedUpper = false
        )
        return safeApiCall(defaultOnNull = true) { apiService.updateUser(userIndex, copyPayload) }


    }
    override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean {
        if (prefs.getDemoMode()) {
            val packages = getPackages()
            val pkgName = packages.find { it.accountIndex == accountIndex }?.accountName ?: "Standard"
            val current = demoUsersCache[userIndex] ?: safeApiCall { apiService.getUserDetail(userIndex) }
            demoUsersCache[userIndex] = current.copy(packageNameLower = pkgName)
            demoSearchCache[userIndex] = demoSearchCache[userIndex]?.copy(accountNameLower = pkgName) ?: com.example.core.model.UserListItem(userIndexLower = userIndex, accountNameLower = pkgName)
            return true
        }
        val payload = mapOf(
            "userindex" to userIndex,
            "userIndex" to userIndex,
            "userid" to userId,
            "UserID" to userId,
            "accountIndex" to accountIndex,
            "AccountIndex" to accountIndex,
            "accountId" to accountIndex,
            "AccountID" to accountIndex
        )
        return safeApiCall(defaultOnNull = true) { apiService.changeAccountType(payload) }


    }
    override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean {
        if (prefs.getDemoMode()) {
            val current = demoUsersCache[userIndex] ?: safeApiCall { apiService.getUserDetail(userIndex) }
            val updated = current.copy(
                displayNameLower = newName,
                displayNameUpper = newName,
                customerFullNameLower = newName,
                customerFullNameUpper = newName,
                customerNameLower = newName,
                customerNameUpper = newName,
                nameLower = newName,
                nameUpper = newName
            )
            demoUsersCache[userIndex] = updated
            demoSearchCache[userIndex] = demoSearchCache[userIndex]?.copy(
                displayNameLower = newName,
                displayNameUpper = newName,
                customerNameLower = newName,
                customerNameUpper = newName
            ) ?: com.example.core.model.UserListItem(userIndexLower = userIndex, displayNameLower = newName)
            return true
        }
        val user = getUserDetail(userIndex)
        val copyPayload = user.copy(
            displayNameLower = newName,
            displayNameUpper = newName,
            customerFullNameLower = newName,
            customerFullNameUpper = newName,
            customerNameLower = newName,
            customerNameUpper = newName,
            nameLower = newName,
            nameUpper = newName
        )
        return safeApiCall(defaultOnNull = true) { apiService.updateUser(userIndex, copyPayload) }


    }
    override fun addCustomStatement(statement: AccountStatementItem) {



        customStatements.add(0, statement)



    }
}


class LocalAccountRepositoryImpl(
    private val database: AppDatabase,
    private val accountDao: LocalAccountDao,
    private val outboxDao: SyncOutboxDao
) : LocalAccountRepository {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(LocalAccount::class.java)
    private val ledgerAdapter = moshi.adapter(LocalLedgerEntry::class.java)

    init {
        // Initialization without destructive background deduplication
    }

    override fun getAllAccounts(): Flow<List<LocalAccount>> {
        return accountDao.getAll().distinctUntilChanged()


    }
    override suspend fun getAllAccountsOneShot(): List<LocalAccount> {
        return accountDao.getAllOneShot()


    }
    override suspend fun searchAccounts(query: String, limit: Int, offset: Int): List<LocalAccount> {
        return accountDao.searchAccounts(query, limit, offset)
    }

    override fun searchAccountsFilteredFlow(
        query: String,
        filterDebt: Boolean,
        filterAdvance: Boolean,
        filterNoUsername: Boolean,
        filterCoordinates: Boolean,
        sortOption: String,
        limit: Int,
        offset: Int
    ): Flow<List<LocalAccount>> {
        var sql = "SELECT * FROM local_accounts WHERE 1=1"
        val args = mutableListOf<Any>()
        if (query.isNotEmpty()) {
            sql += " AND (displayName LIKE '%' || ? || '%' OR earthlinkUsername LIKE '%' || ? || '%' OR phone1 LIKE '%' || ? || '%' OR phone2 LIKE '%' || ? || '%' OR packageName LIKE '%' || ? || '%' OR towerName LIKE '%' || ? || '%' OR address LIKE '%' || ? || '%')"
            for (i in 0 until 7) args.add(query)
        }
        if (filterDebt) sql += " AND debtIqd > 0.0"
        if (filterAdvance) sql += " AND advanceIqd > 0.0"
        if (filterNoUsername) sql += " AND (earthlinkUsername IS NULL OR earthlinkUsername = '')"
        if (filterCoordinates) sql += " AND (latitude IS NOT NULL AND longitude IS NOT NULL)"
        
        sql += when (sortOption) {
            "name" -> " ORDER BY displayName ASC"
            "debt" -> " ORDER BY debtIqd DESC"
            "price" -> " ORDER BY currentPriceIqd DESC"
            else -> ""
        }
        
        sql += " LIMIT ? OFFSET ?"
        args.add(limit)
        args.add(offset)
        
        return accountDao.searchAccountsRawFlow(androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()))
    }

    override fun countAccountsFilteredFlow(
        query: String,
        filterDebt: Boolean,
        filterAdvance: Boolean,
        filterNoUsername: Boolean,
        filterCoordinates: Boolean
    ): Flow<Int> {
        var sql = "SELECT COUNT(*) FROM local_accounts WHERE 1=1"
        val args = mutableListOf<Any>()
        if (query.isNotEmpty()) {
            sql += " AND (displayName LIKE '%' || ? || '%' OR earthlinkUsername LIKE '%' || ? || '%' OR phone1 LIKE '%' || ? || '%' OR phone2 LIKE '%' || ? || '%' OR packageName LIKE '%' || ? || '%' OR towerName LIKE '%' || ? || '%' OR address LIKE '%' || ? || '%')"
            for (i in 0 until 7) args.add(query)
        }
        if (filterDebt) sql += " AND debtIqd > 0.0"
        if (filterAdvance) sql += " AND advanceIqd > 0.0"
        if (filterNoUsername) sql += " AND (earthlinkUsername IS NULL OR earthlinkUsername = '')"
        if (filterCoordinates) sql += " AND (latitude IS NOT NULL AND longitude IS NOT NULL)"
        
        return accountDao.getSearchCountRawFlow(androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()))
    }
    override fun getAccountById(id: String): Flow<LocalAccount?> {
        return accountDao.getById(id).distinctUntilChanged()


    }
    override suspend fun getAccountByIdOneShot(id: String): LocalAccount? {
        return accountDao.getByIdOneShot(id)
    }
    override fun getAccountByUsernameOrId(username: String): Flow<LocalAccount?> {
        return accountDao.getAccountByUsernameOrId(username).distinctUntilChanged()
    }
    override suspend fun findAccountByUsernameOrIdOneShot(username: String): LocalAccount? {
        return accountDao.findAccountByUsernameOrIdOneShot(username)
    }
    override suspend fun saveAccount(account: LocalAccount): LocalAccount {
        return com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction {
                val existing = accountDao.getByIdOneShot(account.id)
                    ?: if (!account.earthlinkUsername.isNullOrBlank()) {
                        accountDao.findAccountByUsernameOrIdOneShot(account.earthlinkUsername)
                    } else null

                val updated = if (existing != null) {
                    existing.copy(
                        displayName = if (account.displayName.isNotBlank()) account.displayName else existing.displayName,
                        phone1 = account.phone1 ?: existing.phone1,
                        phone2 = account.phone2 ?: existing.phone2,
                        packageName = account.packageName ?: existing.packageName,
                        currentPriceIqd = if (account.currentPriceIqd > 0.0) account.currentPriceIqd else existing.currentPriceIqd,
                        nanoIp = account.nanoIp ?: existing.nanoIp,
                        note = account.note ?: existing.note,
                        latitude = account.latitude ?: existing.latitude,
                        longitude = account.longitude ?: existing.longitude,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    account.copy(updatedAt = System.currentTimeMillis())
                }

                if (existing != null) {
                    accountDao.update(updated)
                } else {
                    accountDao.insert(updated)
                }

                // Queue Sync Outbox
                OutboxManager.upsertWithOutbox(outboxDao, "local_accounts", updated.id, adapter.toJson(updated))
                
                updated
            }
        }
    }
    override suspend fun deleteAccount(id: String) {
        com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction {
                val childEntries = database.localLedgerEntryDao().getByAccountIdOneShot(id, limit = Int.MAX_VALUE)
                for (entry in childEntries) {
                    OutboxManager.deleteWithTombstone(outboxDao, "local_ledger_entries", entry.id, "{}")
                }
                database.localLedgerEntryDao().deleteByAccountId(id)

                accountDao.deleteById(id)
                OutboxManager.deleteWithTombstone(outboxDao, "local_accounts", id, "{}")
            }
        }
    }

    override suspend fun deleteAllAccounts() {
        com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction {
                val allAccounts = accountDao.getAllOneShot(limit = Int.MAX_VALUE)
                val allLedgers = database.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)

                OutboxManager.deleteWithTombstoneBatch(outboxDao, "local_accounts", allAccounts.map { it.id }, "{}")
                OutboxManager.deleteWithTombstoneBatch(outboxDao, "local_ledger_entries", allLedgers.map { it.id }, "{}")

                database.localLedgerEntryDao().deleteAll()
                accountDao.deleteAll()
            }
        }
    }
}
class LocalLedgerRepositoryImpl(
    private val database: AppDatabase,
    private val ledgerDao: LocalLedgerEntryDao,
    private val accountDao: LocalAccountDao,
    private val outboxDao: SyncOutboxDao
) : LocalLedgerRepository {

    private val moshi = Moshi.Builder().build()
    private val ledgerAdapter = moshi.adapter(LocalLedgerEntry::class.java)
    private val accountAdapter = moshi.adapter(LocalAccount::class.java)

    override fun getLedgerForAccount(accountId: String): Flow<List<LocalLedgerEntry>> =
        ledgerDao.getByAccountId(accountId).distinctUntilChanged()

    private suspend fun saveAccountInternal(account: LocalAccount): LocalAccount {
        val existing = accountDao.getByIdOneShot(account.id)
            ?: if (!account.earthlinkUsername.isNullOrBlank()) {
                accountDao.findAccountByUsernameOrIdOneShot(account.earthlinkUsername)
            } else null

        val updated = if (existing != null) {
            existing.copy(
                displayName = if (account.displayName.isNotBlank()) account.displayName else existing.displayName,
                phone1 = account.phone1 ?: existing.phone1,
                packageName = account.packageName ?: existing.packageName,
                currentPriceIqd = if (account.currentPriceIqd > 0.0) account.currentPriceIqd else existing.currentPriceIqd,
                nanoIp = account.nanoIp ?: existing.nanoIp,
                note = account.note ?: existing.note,
                latitude = account.latitude ?: existing.latitude,
                longitude = account.longitude ?: existing.longitude,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            account.copy(updatedAt = System.currentTimeMillis())
        }

        if (existing != null) {
            accountDao.update(updated)
        } else {
            accountDao.insert(updated)
        }

        OutboxManager.upsertWithOutbox(outboxDao, "local_accounts", updated.id, accountAdapter.toJson(updated))

        return updated
    }

    private suspend fun addPaymentInternal(accountId: String, amount: Double, note: String?, idempotencyKey: String? = null): LocalLedgerEntry {
        require(amount > 0.0) { "Payment amount must be greater than zero." }
        
        val entryId = idempotencyKey ?: java.util.UUID.randomUUID().toString()
        if (idempotencyKey != null) {
            val existing = ledgerDao.getByIdOneShot(idempotencyKey)
            if (existing != null) return existing
        }

        val account = accountDao.getByIdOneShot(accountId)
            ?: throw Exception("Account not found.")

        val balances = com.example.core.ledger.BalanceCalculator.applyTransaction(account.debtIqd, account.advanceIqd, account.loanIqd, "gave", amount)

        val updatedAccount = account.copy(
            debtIqd = balances.debtIqd,
            loanIqd = balances.loanIqd,
            advanceIqd = balances.advanceIqd,
            lastPaymentAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        accountDao.update(updatedAccount)

        OutboxManager.upsertWithOutbox(outboxDao, "local_accounts", updatedAccount.id, accountAdapter.toJson(updatedAccount))

        val ledgerEntry = LocalLedgerEntry(
            id = entryId,
            accountId = accountId,
            typeRaw = "gave",
            amountIqd = amount,
            debtAfterIqd = balances.debtIqd,
            note = note
        )
        ledgerDao.insert(ledgerEntry)

        OutboxManager.upsertWithOutbox(outboxDao, "local_ledger_entries", ledgerEntry.id, ledgerAdapter.toJson(ledgerEntry))

        return ledgerEntry
    }

    private suspend fun addDebtInternal(accountId: String, amount: Double, note: String?, idempotencyKey: String? = null): LocalLedgerEntry {
        require(amount > 0.0) { "Debt amount must be greater than zero." }
        
        val entryId = idempotencyKey ?: java.util.UUID.randomUUID().toString()
        if (idempotencyKey != null) {
            val existing = ledgerDao.getByIdOneShot(idempotencyKey)
            if (existing != null) return existing
        }

        val account = accountDao.getByIdOneShot(accountId)
            ?: throw Exception("Account not found.")

        val balances = com.example.core.ledger.BalanceCalculator.applyTransaction(account.debtIqd, account.advanceIqd, account.loanIqd, "took", amount)

        val updatedAccount = account.copy(
            debtIqd = balances.debtIqd,
            loanIqd = balances.loanIqd,
            advanceIqd = balances.advanceIqd,
            updatedAt = System.currentTimeMillis()
        )
        accountDao.update(updatedAccount)

        OutboxManager.upsertWithOutbox(outboxDao, "local_accounts", updatedAccount.id, accountAdapter.toJson(updatedAccount))

        val ledgerEntry = LocalLedgerEntry(
            id = entryId,
            accountId = accountId,
            typeRaw = "took",
            amountIqd = amount,
            debtAfterIqd = balances.debtIqd,
            note = note
        )
        ledgerDao.insert(ledgerEntry)

        OutboxManager.upsertWithOutbox(outboxDao, "local_ledger_entries", ledgerEntry.id, ledgerAdapter.toJson(ledgerEntry))

        return ledgerEntry
    }

    override suspend fun addPayment(accountId: String, amount: Double, note: String?, idempotencyKey: String?): LocalLedgerEntry {
        return com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction { addPaymentInternal(accountId, amount, note, idempotencyKey) }
        }
    }

    override suspend fun addDebt(accountId: String, amount: Double, note: String?, idempotencyKey: String?): LocalLedgerEntry {
        return com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction { addDebtInternal(accountId, amount, note, idempotencyKey) }
        }
    }

    override suspend fun recordAccountRenewal(
        account: LocalAccount,
        newPriceIqd: Double,
        chargeNote: String,
        payNote: String?,
        idempotencyKey: String?
    ): LocalLedgerEntry {
        return com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction {
                val accountWithPrice = account.copy(currentPriceIqd = if (newPriceIqd > 0.0) newPriceIqd else account.currentPriceIqd)
                val savedAcc = saveAccountInternal(accountWithPrice)
                val renewalPrice = accountWithPrice.currentPriceIqd
                require(renewalPrice > 0.0) { "Renewal price must be greater than zero." }
                val chargeId = if (idempotencyKey != null) "charge_$idempotencyKey" else null
                val payId = if (idempotencyKey != null) "pay_$idempotencyKey" else null
                val chargeEntry = addDebtInternal(savedAcc.id, renewalPrice, chargeNote, chargeId)
                if (payNote != null) {
                    addPaymentInternal(savedAcc.id, renewalPrice, payNote, payId)
                }
                chargeEntry
            }
        }
    }

    override suspend fun recordAccountPayment(
        account: LocalAccount,
        amount: Double,
        note: String?,
        idempotencyKey: String?
    ): LocalLedgerEntry {
        require(amount > 0.0) { "Payment amount must be greater than zero." }
        return com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction {
                val savedAcc = saveAccountInternal(account)
                addPaymentInternal(savedAcc.id, amount, note, idempotencyKey)
            }
        }
    }

    override suspend fun recordAccountDebt(
        account: LocalAccount,
        amount: Double,
        note: String?,
        idempotencyKey: String?
    ): LocalLedgerEntry {
        require(amount > 0.0) { "Debt amount must be greater than zero." }
        return com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction {
                val savedAcc = saveAccountInternal(account)
                addDebtInternal(savedAcc.id, amount, note, idempotencyKey)
            }
        }
    }
    override suspend fun addNoteTransaction(accountId: String, note: String): LocalLedgerEntry {
        return com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction {
                val account = accountDao.getByIdOneShot(accountId)
                    ?: throw Exception("Account not found.")

                val ledgerEntry = LocalLedgerEntry(
                    accountId = accountId,
                    typeRaw = "note",
                    amountIqd = 0.0,
                    debtAfterIqd = account.debtIqd,
                    note = note
                )
                ledgerDao.insert(ledgerEntry)

                // Queue ledger sync
                OutboxManager.upsertWithOutbox(outboxDao, "local_ledger_entries", ledgerEntry.id, ledgerAdapter.toJson(ledgerEntry))

                ledgerEntry
            }
        }
    }
    override suspend fun deleteTransaction(id: String) {
        com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction {
                val tx = ledgerDao.getByIdOneShot(id)
                if (tx != null) {
                    val account = accountDao.getByIdOneShot(tx.accountId)
                    if (account != null) {
                        val balances = com.example.core.ledger.BalanceCalculator.revertTransaction(account.debtIqd, account.advanceIqd, account.loanIqd, tx.typeRaw, tx.amountIqd)
                        val updatedAccount = if (balances.debtIqd != account.debtIqd || balances.advanceIqd != account.advanceIqd) {
                            account.copy(
                                debtIqd = balances.debtIqd,
                                loanIqd = balances.loanIqd,
                                advanceIqd = balances.advanceIqd,
                                updatedAt = System.currentTimeMillis()
                            )
                        } else null

                        if (updatedAccount != null) {
                            accountDao.update(updatedAccount)
                            OutboxManager.upsertWithOutbox(outboxDao, "local_accounts", updatedAccount.id, accountAdapter.toJson(updatedAccount))
                        }
                    }

                    ledgerDao.deleteById(id)

                    // Queue delete sync
                    OutboxManager.deleteWithTombstone(outboxDao, "local_ledger_entries", id, "{}")

                    recalculateAccountHistoryInternal(tx.accountId, accountDao, ledgerDao, outboxDao, ledgerAdapter, origin = RecalcOrigin.LOCAL_MUTATION)
                }
            }
        }
    }

    override suspend fun deleteAllLedgerEntries() {
        com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            database.withTransaction {
                val allLedgers = ledgerDao.getAllOneShot(limit = Int.MAX_VALUE)
                val allAccounts = accountDao.getAllOneShot(limit = Int.MAX_VALUE)
                OutboxManager.deleteWithTombstoneBatch(outboxDao, "local_ledger_entries", allLedgers.map { it.id }, "{}")
                ledgerDao.deleteAll()
                for (account in allAccounts) {
                    if (account.debtIqd > 0.0 || account.advanceIqd > 0.0 || account.loanIqd > 0.0) {
                        val updatedAccount = account.copy(
                            debtIqd = 0.0,
                            loanIqd = 0.0,
                            advanceIqd = 0.0,
                            updatedAt = System.currentTimeMillis()
                        )
                        accountDao.update(updatedAccount)
                        OutboxManager.upsertWithOutbox(outboxDao, "local_accounts", updatedAccount.id, accountAdapter.toJson(updatedAccount))
                    }
                }
            }
        }
    }
}
class UtowerImportRepositoryImpl(
    private val context: android.content.Context,
    private val database: AppDatabase,
    private val batchDao: ImportBatchDao,
    private val accountDao: LocalAccountDao,
    private val ledgerDao: LocalLedgerEntryDao,
    private val outboxDao: SyncOutboxDao,
    private val auditRepo: AuditRepository? = null
) : UtowerImportRepository {

    private val moshi = Moshi.Builder().build()
    private val batchAdapter = moshi.adapter(ImportBatch::class.java)
    private val accountAdapter = moshi.adapter(LocalAccount::class.java)
    private val ledgerAdapter = moshi.adapter(LocalLedgerEntry::class.java)

    private val bghSdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Baghdad") }
    @Synchronized private fun formatBghFull(ms: Long): String = bghSdf.format(java.util.Date(ms))

    private fun parseDateString(strVal: String, pattern: String): java.util.Date? {
        val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Baghdad")
        }
        return try { sdf.parse(strVal) } catch (_: Exception) { null }
    }

    private fun parseBghDate(strVal: String?): Long? {
        if (strVal.isNullOrBlank() || strVal == "null") return null
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (fmt in formats) {
            val d = parseDateString(strVal, fmt)
            if (d != null) return d.time
        }
        return strVal.toLongOrNull()
    }

    override fun getImportBatches(): Flow<List<ImportBatch>> = batchDao.getAll().distinctUntilChanged()

    override suspend fun processImportPreview(jsonString: String): UtowerImportPreview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val parsedAccounts = mutableListOf<LocalAccount>()
        val parsedTransactions = mutableListOf<LocalLedgerEntry>()

        try {
            val root = JSONObject(jsonString)

            val isLegacy = !root.has("subscribers") && (root.has("users") || root.has("accounts") || root.has("customers") || root.has("clients"))

            // Dynamic keys check
            val subscribersKey = when {
                root.has("subscribers") -> "subscribers"
                root.has("users") -> "users"
                root.has("accounts") -> "accounts"
                root.has("customers") -> "customers"
                root.has("clients") -> "clients"
                else -> null
            }
            val transactionsKey = when {
                root.has("transactions") -> "transactions"
                root.has("payments") -> "payments"
                root.has("ledger") -> "ledger"
                root.has("history") -> "history"
                else -> null
            }
            val batchId = java.util.UUID.randomUUID().toString()

            val subscriberExtToInternalMap = mutableMapOf<String, String>()

            if (subscribersKey != null) {
                val subArray: JSONArray = root.getJSONArray(subscribersKey)
                for (i in 0 until subArray.length()) {
                    val subObj = subArray.getJSONObject(i)
                    val liveObj = subObj.optJSONObject("live") ?: JSONObject()
                    val utowerObj = subObj.optJSONObject("utower") ?: JSONObject()

                    val extId = (if (isLegacy) subObj.optString("userName") else subObj.optString("source_key", subObj.optString("id", "")))
                        .takeIf { it.isNotEmpty() && it != "null" } ?: subObj.optString("source_key", subObj.optString("id", ""))
                    
                    var earthlinkUsername = (if (isLegacy) subObj.optString("userName") else liveObj.optString("username"))?.trim()
                    if (earthlinkUsername.isNullOrEmpty() || earthlinkUsername == "null") {
                        earthlinkUsername = subObj.optString("earthlink_username", subObj.optString("username", "")).trim()
                    }
                    val finalEarthlinkUser = if (earthlinkUsername == "null" || earthlinkUsername.isNullOrEmpty()) null else earthlinkUsername

                    val phone1 = (if (isLegacy) subObj.optString("phoneNumber") else liveObj.optString("phoneNumber"))
                        ?.takeIf { it.isNotEmpty() && it != "null" } ?: subObj.optString("phone1", subObj.optString("phone", "")).takeIf { it.isNotEmpty() && it != "null" }
                    val phone2 = utowerObj.optString("phoneNumber2").takeIf { it.isNotEmpty() && it != "null" }
                        ?: subObj.optString("phone2", subObj.optString("mobile", "")).takeIf { it.isNotEmpty() && it != "null" }

                    val dispName = (if (isLegacy) subObj.optString("fullName") else liveObj.optString("fullName"))
                        ?.takeIf { !it.isBlank() && it != "null" } ?: subObj.optString("display_name", subObj.optString("name", "Unnamed Subscriber"))

                    val pkg = (if (isLegacy) subObj.optString("packageName") else liveObj.optString("profileName"))
                        ?.takeIf { !it.isBlank() && it != "null" } ?: subObj.optString("package_name", subObj.optString("package", "")).takeIf { it.isNotEmpty() && it != "null" }

                    val directDebtIqd = com.example.core.ledger.MoneyParser.parseAmount(if (isLegacy) subObj else utowerObj, keys = listOf("debt_iqd"))
                    val directPriceIqd = com.example.core.ledger.MoneyParser.parseAmount(if (isLegacy) subObj else utowerObj, keys = listOf("price_iqd", "current_price_iqd"))
                    val directLoanIqd = com.example.core.ledger.MoneyParser.parseAmount(if (isLegacy) subObj else utowerObj, keys = listOf("loan_iqd", "loanIqd"))
                    val directAdvanceIqd = com.example.core.ledger.MoneyParser.parseAmount(if (isLegacy) subObj else utowerObj, keys = listOf("advance_iqd"))

                    val debtUnit = if (isLegacy) com.example.core.ledger.MoneyParser.parseAmount(subObj, keys = listOf("totalDebit", "debts", "debt", "remindPrice", "totalPrice", "debt_unit")) else com.example.core.ledger.MoneyParser.parseAmount(utowerObj, subObj, keys = listOf("totalDebit", "debts", "debt", "remindPrice", "totalPrice", "debt_unit"))
                    val priceUnit = if (isLegacy) com.example.core.ledger.MoneyParser.parseAmount(subObj, keys = listOf("currentPrice", "price", "current_price_unit")) else com.example.core.ledger.MoneyParser.parseAmount(utowerObj, subObj, keys = listOf("currentPrice", "price", "current_price_unit"))
                    val loanUnit = if (isLegacy) com.example.core.ledger.MoneyParser.parseAmount(subObj, keys = listOf("loan")) else com.example.core.ledger.MoneyParser.parseAmount(utowerObj, subObj, keys = listOf("loan"))
                    val advanceUnit = if (isLegacy) com.example.core.ledger.MoneyParser.parseAmount(subObj, keys = listOf("advance")) else com.example.core.ledger.MoneyParser.parseAmount(utowerObj, subObj, keys = listOf("advance"))

                    val debtIqd = if (directDebtIqd != null) com.example.core.ledger.MoneyParser.parseRawIqd(directDebtIqd) else com.example.core.ledger.MoneyParser.parseUtowerAmount(debtUnit)
                    val priceIqd = if (directPriceIqd != null) com.example.core.ledger.MoneyParser.parseRawIqd(directPriceIqd) else com.example.core.ledger.MoneyParser.parseUtowerAmount(priceUnit)
                    val loanIqdVal = if (directLoanIqd != null) com.example.core.ledger.MoneyParser.parseRawIqd(directLoanIqd) else if (loanUnit != null) com.example.core.ledger.MoneyParser.parseUtowerAmount(loanUnit) else debtIqd
                    val advanceIqd = if (directAdvanceIqd != null) com.example.core.ledger.MoneyParser.parseRawIqd(directAdvanceIqd) else com.example.core.ledger.MoneyParser.parseUtowerAmount(advanceUnit)

                    val tower = utowerObj.optString("boardName").takeIf { it.isNotEmpty() && it != "null" } ?: subObj.optString("tower_name", subObj.optString("tower", "")).takeIf { it.isNotEmpty() && it != "null" }
                    val zone = subObj.optString("zone_name", subObj.optString("zone", "")).takeIf { it.isNotEmpty() && it != "null" }
                    val addr = subObj.optString("address", "").takeIf { it.isNotEmpty() && it != "null" }
                    val nanoIp = if (isLegacy) (subObj.optString("nanoIp", subObj.optString("nano_ip"))) else (utowerObj.optString("nanoIp") ?: subObj.optString("nano_ip"))
                    val note = if (isLegacy) subObj.optString("note") else utowerObj.optString("note")

                    val endMs = if (isLegacy) (subObj.optLong("end", 0L).takeIf { it > 0L } ?: subObj.optLong("subscription_end_ms", 0L)) else (liveObj.optLong("end", 0L).takeIf { it > 0L } ?: subObj.optLong("subscription_end_ms", 0L))
                    val expiresAt = if (endMs > 0) formatBghFull(endMs) else subObj.optString("expires_at", "").takeIf { it.isNotEmpty() && it != "null" }

                    val lat = if (subObj.has("lat")) subObj.optDouble("lat") else null
                    val lon = if (subObj.has("lon")) subObj.optDouble("lon") else if (subObj.has("lng")) subObj.optDouble("lng") else if (subObj.has("longitude")) subObj.optDouble("longitude") else null

                    val sourceKeyToUse = if (extId.isNotEmpty()) extId else java.util.UUID.randomUUID().toString()

                    val internalId = java.util.UUID.randomUUID().toString()
                    subscriberExtToInternalMap[sourceKeyToUse] = internalId
                    if (!finalEarthlinkUser.isNullOrEmpty()) subscriberExtToInternalMap[finalEarthlinkUser] = internalId
                    if (!phone1.isNullOrEmpty()) subscriberExtToInternalMap[phone1] = internalId

                    val account = LocalAccount(
                        id = internalId,
                        sourceExternalId = sourceKeyToUse,
                        sourceBatchId = batchId,
                        displayName = dispName,
                        earthlinkUsername = finalEarthlinkUser,
                        phone1 = phone1,
                        phone2 = phone2,
                        packageName = pkg,
                        currentPriceIqd = priceIqd,
                        debtIqd = debtIqd,
                        loanIqd = loanIqdVal,
                        advanceIqd = advanceIqd,
                        towerName = tower,
                        zoneName = zone,
                        address = addr,
                        nanoIp = nanoIp.takeIf { !it.isNullOrBlank() && it != "null" },
                        latitude = if (lat == null || lat.isNaN()) null else lat,
                        longitude = if (lon == null || lon.isNaN()) null else lon,
                        note = note.takeIf { !it.isNullOrBlank() && it != "null" },
                        expiresAt = expiresAt,
                        rawJson = subObj.toString()
                    )
                    parsedAccounts.add(account)
                }
            } else {
                warnings.add("No subscriber list key found under typical shapes.")
            }

            if (transactionsKey != null) {
                val txArray = root.getJSONArray(transactionsKey)
                for (i in 0 until txArray.length()) {
                    val txObj = txArray.getJSONObject(i)
                    val rawTxId = txObj.optString("source_key", txObj.optString("id", txObj.optString("tx_id", txObj.optString("transaction_id", txObj.optString("key", txObj.optString("ref", ""))))))
                    val subRef = txObj.optString("subscriber_ref", txObj.optString("accountId", txObj.optString("user_ref", txObj.optString("toWho", ""))))

                    val rawType = if (txObj.has("type")) txObj.optString("type") else txObj.optString("type_raw", "gave")
                    val typeNormalized = com.example.core.ledger.TransactionTypeNormalizer.normalizeTransactionType(rawType)

                    val directAmountIqd = com.example.core.ledger.MoneyParser.parseAmount(txObj, keys = listOf("amount_iqd"))
                    val amountUnit = com.example.core.ledger.MoneyParser.parseAmount(txObj, keys = listOf("amount", "amount_unit"))
                    val amt = if (directAmountIqd != null) {
                        com.example.core.ledger.MoneyParser.parseRawIqd(directAmountIqd)
                    } else {
                        com.example.core.ledger.MoneyParser.parseUtowerAmount(amountUnit)
                    }

                    val directDebtAfterIqd = com.example.core.ledger.MoneyParser.parseAmount(txObj, keys = listOf("debt_after_iqd"))
                    val debtAfterUnit = com.example.core.ledger.MoneyParser.parseAmount(txObj, keys = listOf("totalDebitAfter", "debt_after_unit", "debt_after"))
                    val debtAfter = if (directDebtAfterIqd != null) {
                        com.example.core.ledger.MoneyParser.parseRawIqd(directDebtAfterIqd)
                    } else {
                        com.example.core.ledger.MoneyParser.parseUtowerAmount(debtAfterUnit)
                    }

                    val rawDateStr = txObj.optString("date").takeIf { it.isNotEmpty() }
                        ?: txObj.optString("timeOfAction").takeIf { it.isNotEmpty() }
                        ?: txObj.optString("time").takeIf { it.isNotEmpty() }
                        ?: txObj.optString("createdAt").takeIf { it.isNotEmpty() }
                        ?: txObj.optString("timestamp").takeIf { it.isNotEmpty() }
                    val timeMs = txObj.optLong("time_ms", txObj.optLong("actualTimeMs", txObj.optLong("timestamp_ms", txObj.optLong("serverTime", 0L))))
                    val actualTimeMs = if (timeMs > 0) timeMs else parseBghDate(rawDateStr)

                    if (actualTimeMs == null || actualTimeMs <= 0L) {
                        warnings.add("Transaction row $i ($rawTxId): Invalid or unparseable date. Quarantined/Skipped.")
                        continue
                    }
                    val noteStr = txObj.optString("note", "")

                    if (subRef.isEmpty()) {
                        warnings.add("Transaction row $i: Missing subscriber_ref. Skipping.")
                        continue
                    }

                    val targetAccountId = subscriberExtToInternalMap[subRef]
                        ?: parsedAccounts.firstOrNull { it.sourceExternalId == subRef || it.earthlinkUsername == subRef || it.phone1 == subRef || it.displayName == subRef }?.id
                        ?: subRef

                    val txKey = if (rawTxId.isNotEmpty()) rawTxId else "tx_${subRef}_${actualTimeMs}_${amt}_${typeNormalized}"

                    val tx = LocalLedgerEntry(
                        id = java.util.UUID.nameUUIDFromBytes("tx_${targetAccountId}_${txKey}".toByteArray()).toString(),
                        accountId = targetAccountId,
                        sourceExternalId = txKey,
                        sourceBatchId = batchId,
                        typeRaw = typeNormalized,
                        amountIqd = amt,
                        debtAfterIqd = debtAfter,
                        note = if (noteStr.isEmpty()) null else noteStr,
                        occurredAt = actualTimeMs,
                        rawJson = txObj.toString(),
                        isSnapshotHistory = true
                    )
                    parsedTransactions.add(tx)
                }
            }

            // Post-reset debt reconciliation for preview (aligned with UtowerImporter)
            for (i in 0 until parsedAccounts.size) {
                val acc = parsedAccounts[i]
                val rawObj = try { JSONObject(acc.rawJson ?: "{}") } catch (_: Exception) { JSONObject() }
                val utowerObj = rawObj.optJSONObject("utower") ?: JSONObject()
                val liveObj = rawObj.optJSONObject("live") ?: JSONObject()
                val resetStr = utowerObj.optString("lastDebtResetDate", rawObj.optString("lastDebtResetDate", liveObj.optString("lastDebtResetDate"))).takeIf { !it.isNullOrBlank() && it != "null" }
                val resetMs = parseBghDate(resetStr)
                if (resetMs != null && resetMs > 0L) {
                    val userTxs = parsedTransactions.filter { it.accountId == acc.id && it.occurredAt > resetMs }
                        .sortedWith(compareBy<LocalLedgerEntry> { it.occurredAt }.thenBy { it.sourceExternalId ?: "" }.thenBy { it.id })
                    if (userTxs.isNotEmpty()) {
                        var resolvedDebt = 0.0
                        var resolvedAdvance = 0.0
                        var resolvedLoan = 0.0
                        for (tx in userTxs) {
                            val canonicalType = com.example.core.ledger.TransactionTypeNormalizer.normalizeTransactionType(tx.typeRaw)
                            val balances = com.example.core.ledger.BalanceCalculator.applyTransaction(resolvedDebt, resolvedAdvance, resolvedLoan, canonicalType, tx.amountIqd)
                            resolvedDebt = balances.debtIqd
                            resolvedAdvance = balances.advanceIqd
                            resolvedLoan = balances.loanIqd
                        }

                        val latestTxWithDebtAfter = userTxs.reversed().firstOrNull { tx ->
                            if (!tx.rawJson.isNullOrEmpty()) {
                                try {
                                    val json = JSONObject(tx.rawJson)
                                    json.has("totalDebitAfter") || json.has("debt_after") || json.has("debt_after_iqd") || json.has("debtAfter") || json.has("debt_after_unit")
                                } catch (_: Exception) {
                                    false
                                }
                            } else {
                                false
                            }
                        }

                        if (latestTxWithDebtAfter != null) {
                            resolvedDebt = latestTxWithDebtAfter.debtAfterIqd
                        } else {
                            val latestTx = userTxs.last()
                            if (latestTx.debtAfterIqd > 0.0) {
                                resolvedDebt = latestTx.debtAfterIqd
                            }
                        }

                        parsedAccounts[i] = acc.copy(
                            debtIqd = resolvedDebt,
                            advanceIqd = acc.openingAdvanceIqd,
                            loanIqd = acc.openingLoanIqd,
                            openingDebtIqd = resolvedDebt,
                            openingAdvanceIqd = acc.openingAdvanceIqd,
                            openingLoanIqd = acc.openingLoanIqd,
                            stateSource = "UTOWER_SNAPSHOT_RESOLVED",
                            stateConfidence = "AUTHORITATIVE"
                        )
                    }
                }
            }

            var accountsWithUser = 0
            var accountsNoUser = 0
            var debtSum = 0.0
            var advanceSum = 0.0
            var withCoord = 0

            for (acc in parsedAccounts) {
                if (!acc.earthlinkUsername.isNullOrEmpty()) accountsWithUser++ else accountsNoUser++
                debtSum += acc.debtIqd
                advanceSum += acc.advanceIqd
                if (acc.latitude != null && acc.longitude != null) withCoord++

            }
            UtowerImportPreview(
                totalAccountsFound = parsedAccounts.size,
                totalTransactionsFound = parsedTransactions.size,
                accountsWithEarthlinkUsername = accountsWithUser,
                accountsMissingUsername = accountsNoUser,
                totalCurrentDebtIqd = debtSum,
                totalAdvanceIqd = advanceSum,
                accountsWithCoordinates = withCoord,
                warnings = warnings,
                parsedSubscribers = parsedAccounts,
                parsedTransactions = parsedTransactions
            )

        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            UtowerImportPreview(
                warnings = listOf("Failed to parse JSON: ${e.message}")
            )
        }
    }

    override suspend fun commitImport(preview: UtowerImportPreview, fileName: String, fileHash: String): ImportBatch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val importer = com.example.core.sync.UtowerImporter(context, database)
        val batch = importer.importFromPreview(preview, fileName, fileHash)
        auditRepo?.log(
            severity = AuditSeverity.INFO,
            action = "IMPORT_COMMIT_SUCCESS",
            message = "Successfully committed import batch ${batch.id} (${batch.fileName}): accountsImported=${batch.accountsImported}, transactionsImported=${batch.transactionsImported}"
        )
        batch
    }
    override suspend fun rollbackImportBatch(batchId: String): Boolean {
        return com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.ROLLBACK) {
            database.withTransaction {
                val batch = batchDao.getById(batchId) ?: return@withTransaction false

                // Purge pending outbox upserts and queue tombstones for ledger entries of this batch
                val txs = ledgerDao.getByBatchId(batchId)
                for (tx in txs) {
                    val pendingTxOutbox = OutboxManager.getByEntity(outboxDao, tx.id, "local_ledger_entries")
                    val isTxUnsyncedLocal = pendingTxOutbox.any { it.operation == "upsert" && it.status != "synced" }
                    if (!isTxUnsyncedLocal) {
                        OutboxManager.deleteWithTombstone(outboxDao, "local_ledger_entries", tx.id, "{}")
                    } else {
                        OutboxManager.clearByEntity(outboxDao, tx.id, "local_ledger_entries")
                    }
                }
                ledgerDao.deleteByBatchId(batchId)

                // Purge pending outbox upserts and queue tombstones for accounts created in this batch
                val accounts = accountDao.getByBatchId(batchId)
                val deletedAccountIds = accounts.map { it.id }.toSet()
                val affectedAccountIds = txs.map { it.accountId }.toSet()
                for (accId in affectedAccountIds) {
                    if (accId !in deletedAccountIds) {
                        recalculateAccountHistoryInternal(accId, accountDao, ledgerDao, outboxDao, ledgerAdapter, origin = RecalcOrigin.LOCAL_MUTATION)
                    }
                }

                for (acc in accounts) {
                    val remainingLedgers = ledgerDao.getByAccountIdOneShot(acc.id, limit = Int.MAX_VALUE)
                    if (remainingLedgers.isNotEmpty()) {
                        // Account has manual transactions or other batch transactions. Do NOT delete it.
                        // Just recalculate its history based on remaining transactions.
                        recalculateAccountHistoryInternal(acc.id, accountDao, ledgerDao, outboxDao, ledgerAdapter, origin = RecalcOrigin.LOCAL_MUTATION)
                        continue
                    }

                    val pendingAccOutbox = OutboxManager.getByEntity(outboxDao, acc.id, "local_accounts")
                    val isAccUnsyncedLocal = pendingAccOutbox.any { it.operation == "upsert" && it.status != "synced" }
                    if (!isAccUnsyncedLocal) {
                        OutboxManager.deleteWithTombstone(outboxDao, "local_accounts", acc.id, "{}")
                    } else {
                        OutboxManager.clearByEntity(outboxDao, acc.id, "local_accounts")
                    }
                    accountDao.deleteById(acc.id)
                }

                // Purge pending outbox upsert and queue tombstone for import batch record
                OutboxManager.deleteWithTombstone(outboxDao, "import_batches", batchId, "{}")

                // Delete batch record locally
                batchDao.delete(batch)

                auditRepo?.log(
                    severity = AuditSeverity.WARNING,
                    action = "IMPORT_BATCH_ROLLED_BACK",
                    message = "Successfully rolled back import batch $batchId: deleted ${accounts.size} accounts and ${txs.size} transactions"
                )

                true
            }
        }
    }
}
// Add variable to enable simple duplicate merging marker compile options
val LocalAccount.version: Int get() = 1


class AuditRepositoryImpl(
    private val database: AppDatabase? = null,
    private val auditDao: AuditLogDao,
    private val outboxDao: SyncOutboxDao? = null,
    private val syncRepo: SyncRepository? = null,
    private val preferenceManager: com.example.core.security.PreferenceManager? = null
) : AuditRepository {
    private val moshi = Moshi.Builder().build()
    private val auditAdapter = moshi.adapter(AuditLog::class.java)

    private fun calculateSignature(
        timestamp: Long,
        severity: String,
        action: String,
        message: String,
        actor: String,
        salt: String
    ): String {
        val rawString = "$timestamp|$severity|$action|$message|$actor|$salt"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(rawString.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    override fun getAuditLogs(): Flow<List<AuditLog>> = auditDao.getAll().distinctUntilChanged()

    override suspend fun logAction(
        action: String,
        entityType: String?,
        entityId: String?,
        summary: String,
        metadataJsonMasked: String?,
        origin: com.example.core.model.AuditOrigin
    ) {
        val salt = preferenceManager?.getDatabasePassphrase() ?: "default_test_salt"
        val now = System.currentTimeMillis()
        val severity = if (action.startsWith("SEC_") || action.contains("SECURITY") || action.contains("FALLBACK") || action.contains("KEYSTORE") || action.contains("PASSWORD")) {
            AuditSeverity.SECURITY
        } else if (action.contains("FAIL") || action.contains("ERROR") || action.contains("CORRUPT")) {
            AuditSeverity.CRITICAL
        } else {
            AuditSeverity.INFO
        }

        val isEligibleForSync = (origin == com.example.core.model.AuditOrigin.USER_ACTION)

        val signature = calculateSignature(
            timestamp = now,
            severity = severity.name,
            action = action,
            message = summary,
            actor = "system",
            salt = salt
        )

        val executeInsert: suspend () -> Unit = {
            val audit = AuditLog(
                action = action,
                entityType = entityType,
                entityId = entityId,
                summary = summary,
                createdAt = now,
                metadataJsonMasked = metadataJsonMasked,
                severity = severity.name,
                actor = "system",
                signature = signature,
                origin = origin.name
            )
            auditDao.insert(audit)
            if (outboxDao != null && isEligibleForSync) {
                OutboxManager.upsertWithOutbox(outboxDao, "audit_logs", audit.id, auditAdapter.toJson(audit))
            }
        }

        if (database != null) {
            database.withTransaction { executeInsert() }
        } else {
            executeInsert()
        }
        if (isEligibleForSync) {
            syncRepo?.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
        }
    }

    override suspend fun log(
        severity: AuditSeverity,
        action: String,
        message: String,
        actor: String,
        origin: com.example.core.model.AuditOrigin
    ) {
        val salt = preferenceManager?.getDatabasePassphrase() ?: "default_test_salt"
        val now = System.currentTimeMillis()
        val isEligibleForSync = (origin == com.example.core.model.AuditOrigin.USER_ACTION)

        val signature = calculateSignature(
            timestamp = now,
            severity = severity.name,
            action = action,
            message = message,
            actor = actor,
            salt = salt
        )

        val executeInsert: suspend () -> Unit = {
            val audit = AuditLog(
                action = action,
                entityType = null,
                entityId = null,
                summary = message,
                createdAt = now,
                metadataJsonMasked = null,
                severity = severity.name,
                actor = actor,
                signature = signature,
                origin = origin.name
            )
            auditDao.insert(audit)
            if (outboxDao != null && isEligibleForSync) {
                outboxDao.insert(
                    SyncOutbox(
                        entityType = "audit_logs",
                        entityId = audit.id,
                        operation = "upsert",
                        payloadJson = auditAdapter.toJson(audit)
                    )
                )
            }
        }

        if (database != null) {
            database.withTransaction { executeInsert() }
        } else {
            executeInsert()
        }
        if (isEligibleForSync) {
            syncRepo?.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
        }
    }

    override suspend fun verifyLogsIntegrity(): List<AuditLogIntegrityIssue> {
        val logs = auditDao.getAllSync()
        val salt = preferenceManager?.getDatabasePassphrase() ?: "default_test_salt"
        val issues = mutableListOf<AuditLogIntegrityIssue>()
        for (log in logs) {
            val expectedSig = calculateSignature(
                timestamp = log.createdAt,
                severity = log.severity,
                action = log.action,
                message = log.summary,
                actor = log.actor,
                salt = salt
            )
            if (log.signature != expectedSig) {
                issues.add(
                    AuditLogIntegrityIssue(
                        logId = log.id,
                        expectedSignature = expectedSig,
                        actualSignature = log.signature ?: "null",
                        detail = "Tamper detected on audit log ${log.id}: signature mismatch. Log contents: action=${log.action}, summary=${log.summary}"
                    )
                )
            }
        }
        return issues
    }

    override suspend fun getRecentLogs(limit: Int): List<AuditLog> {
        return auditDao.getRecent(limit)
    }

    override suspend fun clearAuditTrail() {
        auditDao.clearAll()
    }
}

@VisibleForTesting
fun getMockAccountsForDemo(): List<LocalAccount> {
    if (!com.alamiry.earthlinkreseller.BuildConfig.DEBUG) return emptyList()
    return listOf(
        LocalAccount(
            id = "ali_hassan",
            displayName = "علي حسن كريم",
            earthlinkUsername = "ali_hassan",
            phone1 = "07701234567",
            packageName = "Standard",
            currentPriceIqd = 40000.0,
            debtIqd = 0.0,
            note = "متصل ومستقر",
            expiresAt = "2026-06-30 12:00:00"
        ),
        LocalAccount(
            id = "mustafa_kh",
            displayName = "مصطفى خالد",
            earthlinkUsername = "mustafa_kh",
            phone1 = "07801112223",
            packageName = "Business",
            currentPriceIqd = 100000.0,
            debtIqd = 100000.0,
            note = "منتهي من 3 ايام ومطالب بالدفع",
            expiresAt = "2026-06-25 12:00:00"
        ),
        LocalAccount(
            id = "zahra_sh",
            displayName = "زهراء شعلان",
            earthlinkUsername = "zahra_sh",
            phone1 = "07503334445",
            packageName = "More",
            currentPriceIqd = 80000.0,
            debtIqd = -20000.0,
            note = "باقي ساعات وينتهي الاشتراك",
            expiresAt = "2026-06-29 03:00:00"
        ),
        LocalAccount(
            id = "hussein_ali",
            displayName = "حسين علي محمود",
            earthlinkUsername = "hussein_ali",
            phone1 = "07712334455",
            packageName = "Lite",
            currentPriceIqd = 30000.0,
            debtIqd = 0.0,
            note = "باقي يوم واحد وينتهي",
            expiresAt = "2026-06-30 01:00:00"
        ),
        LocalAccount(
            id = "k_samir",
            displayName = "كرار سمير عباس",
            earthlinkUsername = "k_samir",
            phone1 = "07711223344",
            packageName = "Economy",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0,
            note = "مشترك نشط ومدفوع مقدماً",
            expiresAt = "2026-07-15 12:00:00"
        ),
        LocalAccount(
            id = "abu_hosam",
            displayName = "ابو حسام",
            earthlinkUsername = "abu_hosam",
            phone1 = "07811223344",
            packageName = "Plus",
            currentPriceIqd = 45000.0,
            debtIqd = 15000.0,
            note = "متبقي بذمته 15 الف",
            expiresAt = "2026-07-10 12:00:00"
        ),
        LocalAccount(
            id = "haider_faleh",
            displayName = "حيدر فالح جابر",
            earthlinkUsername = "haider_faleh",
            phone1 = "07511223344",
            packageName = "Turbo",
            currentPriceIqd = 65000.0,
            debtIqd = -10000.0,
            note = "دفع بالزيادة للاشتراك القادم",
            expiresAt = "2026-07-18 12:00:00"
        ),
        LocalAccount(
            id = "abu_mojtaba",
            displayName = "ابو مجتبى الموسوي",
            earthlinkUsername = "abu_mojtaba",
            phone1 = "07733445566",
            packageName = "Business",
            currentPriceIqd = 120000.0,
            debtIqd = 0.0,
            note = "اشتراك تجاري مستقر",
            expiresAt = "2026-07-25 12:00:00"
        ),
        LocalAccount(
            id = "muhaimin",
            displayName = "مهيمن جاسم",
            earthlinkUsername = "muhaimin",
            phone1 = "07833445566",
            packageName = "Plus",
            currentPriceIqd = 45000.0,
            debtIqd = 45000.0,
            note = "مطلوب كشف حساب واصل",
            expiresAt = "2026-07-12 12:00:00"
        ),
        LocalAccount(
            id = "h_yousef",
            displayName = "حسين ابو يوسف",
            earthlinkUsername = "h_yousef",
            phone1 = "07533445566",
            packageName = "Economy",
            currentPriceIqd = 35000.0,
            debtIqd = 70000.0,
            note = "مغلق موقتاً لم يدفع منذ شهرين",
            expiresAt = "2026-05-25 12:00:00"
        ),
        LocalAccount(
            id = "hashimja@sacx",
            displayName = "هاشم ابو عبد الله",
            earthlinkUsername = "hashimja@sacx",
            phone1 = "07729517321",
            packageName = "Economy",
            currentPriceIqd = 35000.0,
            debtIqd = -5000.0,
            note = "مشترك دائم متفاعل جداً",
            expiresAt = "2026-07-13 12:00:00"
        ),
        LocalAccount(
            id = "alamiry",
            displayName = "عبد الامير",
            earthlinkUsername = "alamiry",
            phone1 = "07729517321",
            packageName = "More",
            currentPriceIqd = 56000.0,
            debtIqd = 0.0,
            note = "صاحب الحساب الرئيسي للتيست",
            expiresAt = "2026-07-07 12:00:00"
        ),
        LocalAccount(
            id = "murtadha_s",
            displayName = "مرتضى صلاح",
            earthlinkUsername = "murtadha_s",
            phone1 = "07702223344",
            packageName = "Standard",
            currentPriceIqd = 40000.0,
            debtIqd = 40000.0,
            note = "يحتاج تفعيل بالدين",
            expiresAt = "2026-06-29 02:30:00"
        ),
        LocalAccount(
            id = "fatima_r",
            displayName = "فاطمة رضا",
            earthlinkUsername = "fatima_r",
            phone1 = "07804445566",
            packageName = "Turbo",
            currentPriceIqd = 65000.0,
            debtIqd = -15000.0,
            note = "سيتم التجديد التلقائي لوجود رصيد",
            expiresAt = "2026-06-30 15:00:00"
        ),
        LocalAccount(
            id = "karim_w",
            displayName = "عبد الكريم وادي",
            earthlinkUsername = "karim_w",
            phone1 = "07505556677",
            packageName = "More",
            currentPriceIqd = 80000.0,
            debtIqd = 80000.0,
            note = "متوقف حالياً، يرجى الاتصال",
            expiresAt = "2026-06-20 12:00:00"
        )
    )

}
@VisibleForTesting
suspend fun getMockUserListForDemo(repo: LocalAccountRepository? = null): List<UserListItem> {
    if (!com.alamiry.earthlinkreseller.BuildConfig.DEBUG) return emptyList()
    val localAccounts = try {
        repo?.getAllAccounts()?.first() ?: emptyList()
    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
        emptyList()
    }
    if (localAccounts.isNotEmpty()) {
        return localAccounts.map { acc ->
            val idx = kotlin.math.abs(acc.id.hashCode())
            UserListItem(
                userIndexLower = idx,
                userIDLower = acc.earthlinkUsername ?: "user_$idx",
                customerNameLower = acc.displayName,
                mobileNumberLower = acc.phone1,
                accountStatusLower = if (acc.debtIqd > 0) "Expired" else "Active",
                expirationDateLower = acc.expiresAt ?: "2026-12-31 12:00:00",
                accountNameLower = acc.packageName ?: "Standard"
            )

        }
    }
    return listOf(
        UserListItem(userIndexLower = 1001, userIDLower = "ali_hassan", customerNameLower = "علي حسن كريم", mobileNumberLower = "07701234567", accountStatusLower = "Active", expirationDateLower = "2026-06-30 12:00:00"),
        UserListItem(userIndexLower = 1002, userIDLower = "mustafa_kh", customerNameLower = "مصطفى خالد", mobileNumberLower = "07801112223", accountStatusLower = "Expired", expirationDateLower = "2026-06-25 12:00:00"),
        UserListItem(userIndexLower = 1003, userIDLower = "zahra_sh", customerNameLower = "زهراء شعلان", mobileNumberLower = "07503334445", accountStatusLower = "Active", expirationDateLower = "2026-06-29 03:00:00"),
        UserListItem(userIndexLower = 1004, userIDLower = "hussein_ali", customerNameLower = "حسين علي محمود", mobileNumberLower = "07712334455", accountStatusLower = "Active", expirationDateLower = "2026-06-30 01:00:00"),
        UserListItem(userIndexLower = 1005, userIDLower = "k_samir", customerNameLower = "كرار سمير عباس", mobileNumberLower = "07711223344", accountStatusLower = "Active", expirationDateLower = "2026-07-15 12:00:00"),
        UserListItem(userIndexLower = 1006, userIDLower = "abu_hosam", customerNameLower = "ابو حسام", mobileNumberLower = "07811223344", accountStatusLower = "Active", expirationDateLower = "2026-07-10 12:00:00"),
        UserListItem(userIndexLower = 1007, userIDLower = "haider_faleh", customerNameLower = "حيدر فالح جابر", mobileNumberLower = "07511223344", accountStatusLower = "Active", expirationDateLower = "2026-07-18 12:00:00"),
        UserListItem(userIndexLower = 1008, userIDLower = "abu_mojtaba", customerNameLower = "ابو مجتبى الموسوي", mobileNumberLower = "07733445566", accountStatusLower = "Active", expirationDateLower = "2026-07-25 12:00:00"),
        UserListItem(userIndexLower = 1009, userIDLower = "muhaimin", customerNameLower = "مهيمن جاسم", mobileNumberLower = "07833445566", accountStatusLower = "Active", expirationDateLower = "2026-07-12 12:00:00"),
        UserListItem(userIndexLower = 1010, userIDLower = "h_yousef", customerNameLower = "حسين ابو يوسف", mobileNumberLower = "07533445566", accountStatusLower = "Expired", expirationDateLower = "2026-05-25 12:00:00"),
        UserListItem(userIndexLower = 1011, userIDLower = "hashimja@sacx", customerNameLower = "هاشم ابو عبد الله", mobileNumberLower = "07729517321", accountStatusLower = "Active", expirationDateLower = "2026-07-13 12:00:00"),
        UserListItem(userIndexLower = 1012, userIDLower = "alamiry", customerNameLower = "عبد الامير", mobileNumberLower = "07729517321", accountStatusLower = "Active", expirationDateLower = "2026-07-07 12:00:00"),
        UserListItem(userIndexLower = 1013, userIDLower = "murtadha_s", customerNameLower = "مرتضى صلاح", mobileNumberLower = "07702223344", accountStatusLower = "Active", expirationDateLower = "2026-06-29 02:30:00"),
        UserListItem(userIndexLower = 1014, userIDLower = "fatima_r", customerNameLower = "فاطمة رضا", mobileNumberLower = "07804445566", accountStatusLower = "Active", expirationDateLower = "2026-06-30 15:00:00"),
        UserListItem(userIndexLower = 1015, userIDLower = "karim_w", customerNameLower = "عبد الكريم وادي", mobileNumberLower = "07505556677", accountStatusLower = "Expired", expirationDateLower = "2026-06-20 12:00:00")
    )

}
@VisibleForTesting
suspend fun getMockUserDetailForDemo(userIndex: Int, repo: LocalAccountRepository? = null): UserDetail {
    if (!com.alamiry.earthlinkreseller.BuildConfig.DEBUG) return UserDetail(userIndexLower = userIndex, customerFullNameLower = "N/A", userIDLower = "N/A")
    val localAccounts = try {
        repo?.getAllAccounts()?.first() ?: emptyList()
    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
        emptyList()
    }
    val match = localAccounts.find { kotlin.math.abs(it.id.hashCode()) == userIndex }
    if (match != null) {
        return UserDetail(
            userIndexLower = userIndex,
            userIDLower = match.earthlinkUsername ?: "user_$userIndex",
            customerFullNameLower = match.displayName,
            mobileNumberLower = match.phone1,
            packageNameLower = match.packageName ?: "Standard",
            accountStatusLower = if (match.debtIqd > 0) "Expired" else "Active",
            expirationDateLower = match.expiresAt ?: "2026-12-31 12:00:00",
            currentIPLower = "100.103.1.20",
            currentMACLower = "AA:BB:CC:DD:EE:FF",
            accountMACLower = "AA:BB:CC:DD:EE:FF",
            onlineSessionTimeLower = "2 hours 15 min"
        )

    }
    return when (userIndex) {
        1001 -> UserDetail(userIndexLower = 1001, userIDLower = "ali_hassan", customerFullNameLower = "علي حسن كريم", mobileNumberLower = "07701234567", packageNameLower = "Standard", accountStatusLower = "Active", expirationDateLower = "2026-06-30 12:00:00", currentIPLower = "172.16.4.25", currentMACLower = "A1:B2:C3:D4:E5:F6", accountMACLower = "A1:B2:C3:D4:E5:F6", onlineSessionTimeLower = "2 hours 15 min")
        1002 -> UserDetail(userIndexLower = 1002, userIDLower = "mustafa_kh", customerFullNameLower = "مصطفى خالد", mobileNumberLower = "07801112223", packageNameLower = "Business", accountStatusLower = "Expired", expirationDateLower = "2026-06-25 12:00:00", currentIPLower = "Offline", currentMACLower = "AA:BB:CC:DD:EE:01", accountMACLower = "AA:BB:CC:DD:EE:01", onlineSessionTimeLower = "Offline")
        1003 -> UserDetail(userIndexLower = 1003, userIDLower = "zahra_sh", customerFullNameLower = "زهراء شعلان", mobileNumberLower = "07503334445", packageNameLower = "More", accountStatusLower = "Active", expirationDateLower = "2026-06-29 03:00:00", currentIPLower = "172.16.8.12", currentMACLower = "AA:BB:CC:DD:EE:02", accountMACLower = "AA:BB:CC:DD:EE:02", onlineSessionTimeLower = "5 hours 12 min")
        1004 -> UserDetail(userIndexLower = 1004, userIDLower = "hussein_ali", customerFullNameLower = "حسين علي محمود", mobileNumberLower = "07712334455", packageNameLower = "Lite", accountStatusLower = "Active", expirationDateLower = "2026-06-30 01:00:00", currentIPLower = "172.16.8.20", currentMACLower = "AA:BB:CC:DD:EE:03", accountMACLower = "AA:BB:CC:DD:EE:03", onlineSessionTimeLower = "45 min")
        1005 -> UserDetail(userIndexLower = 1005, userIDLower = "k_samir", customerFullNameLower = "كرار سمير عباس", mobileNumberLower = "07711223344", packageNameLower = "Economy", accountStatusLower = "Active", expirationDateLower = "2026-07-15 12:00:00", currentIPLower = "100.103.2.14", currentMACLower = "AA:BB:CC:DD:EE:05", accountMACLower = "AA:BB:CC:DD:EE:05", onlineSessionTimeLower = "13 days")
        1006 -> UserDetail(userIndexLower = 1006, userIDLower = "abu_hosam", customerFullNameLower = "ابو حسام", mobileNumberLower = "07811223344", packageNameLower = "Plus", accountStatusLower = "Active", expirationDateLower = "2026-07-10 12:00:00", currentIPLower = "100.103.3.45", currentMACLower = "AA:BB:CC:DD:EE:06", accountMACLower = "AA:BB:CC:DD:EE:06", onlineSessionTimeLower = "8 days")
        1007 -> UserDetail(userIndexLower = 1007, userIDLower = "haider_faleh", customerFullNameLower = "حيدر فالح جابر", mobileNumberLower = "07511223344", packageNameLower = "Turbo", accountStatusLower = "Active", expirationDateLower = "2026-07-18 12:00:00", currentIPLower = "100.103.4.78", currentMACLower = "AA:BB:CC:DD:EE:07", accountMACLower = "AA:BB:CC:DD:EE:07", onlineSessionTimeLower = "16 days")
        1008 -> UserDetail(userIndexLower = 1008, userIDLower = "abu_mojtaba", customerFullNameLower = "ابو مجتبى الموسوي", mobileNumberLower = "07733445566", packageNameLower = "Business", accountStatusLower = "Active", expirationDateLower = "2026-07-25 12:00:00", currentIPLower = "100.103.5.99", currentMACLower = "AA:BB:CC:DD:EE:08", accountMACLower = "AA:BB:CC:DD:EE:08", onlineSessionTimeLower = "23 days")
        1009 -> UserDetail(userIndexLower = 1009, userIDLower = "muhaimin", customerFullNameLower = "مهيمن جاسم", mobileNumberLower = "07833445566", packageNameLower = "Plus", accountStatusLower = "Active", expirationDateLower = "2026-07-12 12:00:00", currentIPLower = "100.103.6.110", currentMACLower = "AA:BB:CC:DD:EE:09", accountMACLower = "AA:BB:CC:DD:EE:09", onlineSessionTimeLower = "10 days")
        1010 -> UserDetail(userIndexLower = 1010, userIDLower = "h_yousef", customerFullNameLower = "حسين ابو يوسف", mobileNumberLower = "07533445566", packageNameLower = "Economy", accountStatusLower = "Expired", expirationDateLower = "2026-05-25 12:00:00", currentIPLower = "Offline", currentMACLower = "AA:BB:CC:DD:EE:10", accountMACLower = "AA:BB:CC:DD:EE:10", onlineSessionTimeLower = "Offline")
        1011 -> UserDetail(userIndexLower = 1011, userIDLower = "hashimja@sacx", customerFullNameLower = "هاشم ابو عبد الله", mobileNumberLower = "07729517321", packageNameLower = "Economy", accountStatusLower = "Active", expirationDateLower = "2026-07-13 12:00:00", currentIPLower = "100.103.1.33", currentMACLower = "00:15:6D:A0:A1:B2", accountMACLower = "00:15:6D:A0:A1:B2", onlineSessionTimeLower = "11 days 16 hours")
        1012 -> UserDetail(userIndexLower = 1012, userIDLower = "alamiry", customerFullNameLower = "عبد الامير", mobileNumberLower = "07729517321", packageNameLower = "More", accountStatusLower = "Active", expirationDateLower = "2026-07-07 12:00:00", currentIPLower = "100.103.7.124", currentMACLower = "00:15:6D:F0:45:67", accountMACLower = "00:15:6D:F0:45:67", onlineSessionTimeLower = "5 days 11 hours")
        1013 -> UserDetail(userIndexLower = 1013, userIDLower = "murtadha_s", customerFullNameLower = "مرتضى صلاح", mobileNumberLower = "07702223344", packageNameLower = "Standard", accountStatusLower = "Active", expirationDateLower = "2026-06-29 02:30:00", currentIPLower = "100.103.8.5", currentMACLower = "AA:BB:CC:DD:EE:13", accountMACLower = "AA:BB:CC:DD:EE:13", onlineSessionTimeLower = "2 hours")
        1014 -> UserDetail(userIndexLower = 1014, userIDLower = "fatima_r", customerFullNameLower = "فاطمة رضا", mobileNumberLower = "07804445566", packageNameLower = "Turbo", accountStatusLower = "Active", expirationDateLower = "2026-06-30 15:00:00", currentIPLower = "100.103.9.12", currentMACLower = "AA:BB:CC:DD:EE:14", accountMACLower = "AA:BB:CC:DD:EE:14", onlineSessionTimeLower = "1 day 3 hours")
        1015 -> UserDetail(userIndexLower = 1015, userIDLower = "karim_w", customerFullNameLower = "عبد الكريم وادي", mobileNumberLower = "07505556677", packageNameLower = "More", accountStatusLower = "Expired", expirationDateLower = "2026-06-20 12:00:00", currentIPLower = "Offline", currentMACLower = "AA:BB:CC:DD:EE:15", accountMACLower = "AA:BB:CC:DD:EE:15", onlineSessionTimeLower = "Offline")
        else -> UserDetail(userIndexLower = 1012, userIDLower = "alamiry", customerFullNameLower = "عبد الامير", mobileNumberLower = "07729517321", packageNameLower = "More", accountStatusLower = "Active", expirationDateLower = "2026-07-07 12:00:00", currentIPLower = "100.103.7.124", currentMACLower = "00:15:6D:F0:45:67", accountMACLower = "00:15:6D:F0:45:67", onlineSessionTimeLower = "5 days 11 hours")


    }
}
enum class RecalcOrigin { LOCAL_MUTATION, REMOTE_APPLY, RESTORE, IMPORT, FULL_REBUILD }

suspend fun recalculateAccountHistoryInternal(
    accountId: String,
    accountDao: com.example.core.database.LocalAccountDao,
    ledgerDao: com.example.core.database.LocalLedgerEntryDao,
    outboxDao: com.example.core.database.SyncOutboxDao,
    ledgerAdapter: com.squareup.moshi.JsonAdapter<com.example.core.model.LocalLedgerEntry>,
    origin: RecalcOrigin
) {
    val account = accountDao.getByIdOneShot(accountId) ?: return
    val allTxs = ledgerDao.getByAccountIdOneShot(accountId, limit = Int.MAX_VALUE)
    if (allTxs.isEmpty()) return

    // Sort deterministically: occurredAt ASC, sourceExternalId ASC, id ASC
    val txsAsc = allTxs.sortedWith(
        compareBy<com.example.core.model.LocalLedgerEntry> { it.occurredAt }
            .thenBy { it.sourceExternalId ?: "" }
            .thenBy { it.id }
    )

    var runningDebt = 0.0
    var runningAdvance = 0.0
    var runningLoan = 0.0

    if (origin == RecalcOrigin.RESTORE || origin == RecalcOrigin.IMPORT) {
        // RESTORE and IMPORT are authoritative snapshot operations.
        // History recalculation must never mutate recorded snapshot balances.
        return
    }

    val isFullRebuildOrigin = origin == RecalcOrigin.FULL_REBUILD

    val targetTxs = if (account.stateSource != null) {
        txsAsc.filter { !it.isSnapshotHistory }
    } else {
        txsAsc
    }

    if (isFullRebuildOrigin) {
        runningDebt = account.openingDebtIqd
        runningAdvance = account.openingAdvanceIqd
        runningLoan = account.openingLoanIqd
    } else {
        var backDebt = account.debtIqd
        var backAdvance = account.advanceIqd
        var backLoan = account.loanIqd
        for (tx in targetTxs.reversed()) {
            val canonicalType = com.example.core.ledger.TransactionTypeNormalizer.normalizeTransactionType(tx.typeRaw)
            val rBalances = com.example.core.ledger.BalanceCalculator.revertTransaction(backDebt, backAdvance, backLoan, canonicalType, tx.amountIqd)
            backDebt = rBalances.debtIqd
            backAdvance = rBalances.advanceIqd
            backLoan = rBalances.loanIqd
        }
        runningDebt = backDebt
        runningAdvance = backAdvance
        runningLoan = backLoan
    }

    val updatedLedgers = mutableListOf<com.example.core.model.LocalLedgerEntry>()

    for (tx in targetTxs) {
        val canonicalType = com.example.core.ledger.TransactionTypeNormalizer.normalizeTransactionType(tx.typeRaw)
        val nBalances = com.example.core.ledger.BalanceCalculator.applyTransaction(runningDebt, runningAdvance, runningLoan, canonicalType, tx.amountIqd)
        runningDebt = nBalances.debtIqd
        runningAdvance = nBalances.advanceIqd
        runningLoan = nBalances.loanIqd

        if (kotlin.math.abs(tx.debtAfterIqd - runningDebt) > 0.01) {
            val updatedTx = tx.copy(debtAfterIqd = runningDebt)
            updatedLedgers.add(updatedTx)
            if (origin == RecalcOrigin.LOCAL_MUTATION || origin == RecalcOrigin.IMPORT) {
                OutboxManager.upsertWithOutbox(outboxDao, "local_ledger_entries", updatedTx.id, ledgerAdapter.toJson(updatedTx))
            }
        }
    }

    if (kotlin.math.abs(account.debtIqd - runningDebt) > 0.01 || kotlin.math.abs(account.advanceIqd - runningAdvance) > 0.01) {
        val newUpdatedAt = if (origin == RecalcOrigin.LOCAL_MUTATION || origin == RecalcOrigin.IMPORT) {
            System.currentTimeMillis()
        } else {
            account.updatedAt
        }
        val updatedAccount = account.copy(debtIqd = runningDebt, loanIqd = runningLoan, advanceIqd = runningAdvance, updatedAt = newUpdatedAt)
        accountDao.upsert(updatedAccount)
        if (origin == RecalcOrigin.LOCAL_MUTATION || origin == RecalcOrigin.IMPORT) {
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val accAdapter = moshi.adapter(com.example.core.model.LocalAccount::class.java)
            OutboxManager.upsertWithOutbox(outboxDao, "local_accounts", account.id, accAdapter.toJson(updatedAccount))
        }
    }

    if (updatedLedgers.isNotEmpty()) {
        ledgerDao.insertAll(updatedLedgers)
    }
}
