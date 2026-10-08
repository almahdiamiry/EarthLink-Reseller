package com.example.ui.viewmodels
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EarthlinkApp
import com.example.core.model.*
import com.example.domain.repository.SyncStatusState
import com.example.domain.repository.UtowerImportPreview
import java.security.MessageDigest
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AuthViewModel(
    val gateway: com.example.domain.repository.EarthlinkGateway,
    val prefs: com.example.core.security.PreferenceManager,
    private val audit: com.example.domain.repository.AuditRepository,
    val syncRepo: com.example.domain.repository.SyncRepository
) : ViewModel() {

    private val _username = MutableStateFlow("")
    val username = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password = _password.asStateFlow()

    private val _rememberMe = MutableStateFlow(prefs.getRememberMe())
    val rememberMe = _rememberMe.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val isLoggedIn = prefs.isLoggedInFlow

    private val _selectedProvider = MutableStateFlow(prefs.getLastSelectedLoginProvider())
    val selectedProvider = _selectedProvider.asStateFlow()

    private val _sammBaseUrl = MutableStateFlow(prefs.getSammBaseUrl() ?: "")
    val sammBaseUrl = _sammBaseUrl.asStateFlow()

    private val _sammUsername = MutableStateFlow(prefs.getSammAgentUsername() ?: "")
    val sammUsername = _sammUsername.asStateFlow()

    private val _sammPassword = MutableStateFlow("")
    val sammPassword = _sammPassword.asStateFlow()

    private val _sammApiToken = MutableStateFlow(prefs.getSammToken() ?: "")
    val sammApiToken = _sammApiToken.asStateFlow()

    val providerAccessState = prefs.providerAccessStateFlow

    init {
        if (prefs.getRememberMe()) {
            _username.value = prefs.getUsername() ?: ""
            _password.value = prefs.getPassword() ?: ""
        }
    }

    fun setSelectedProvider(provider: String) {
        if (SasProviders.isValid(provider)) {
            _selectedProvider.value = provider
            prefs.setLastSelectedLoginProvider(provider)
        }
    }

    fun setSammBaseUrl(value: String) { _sammBaseUrl.value = value }
    fun setSammUsername(value: String) { _sammUsername.value = value }
    fun setSammPassword(value: String) { _sammPassword.value = value }
    fun setSammApiToken(value: String) { _sammApiToken.value = value }

    fun setUsername(value: String) { _username.value = value }
    fun setPassword(value: String) { _password.value = value }
    fun setRememberMe(value: Boolean) { _rememberMe.value = value }

    fun clearError() { _error.value = null }

    fun loginSamm(
        username: String? = null,
        password: String? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): kotlinx.coroutines.Job {
        val rawUrl = _sammBaseUrl.value.trim()
        val user = (username ?: _sammUsername.value).trim()
        val pass = password ?: _sammPassword.value

        if (rawUrl.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            val msg = "Server URL, Username, and Password cannot be empty."
            _error.value = msg
            onError(msg)
            return kotlinx.coroutines.Job().apply { complete() }
        }

        val normalizedUrl = com.example.ui.screens.SammUrlNormalizer.normalize(rawUrl)

        return viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Zero Password Persistence: Passwords are used solely in this transient Retrofit call
                // and never written to SharedPreferences, Room, or disk.
                val apiService = com.example.core.network.samm.SammNetworkClient.createApiService(normalizedUrl, token = null)
                val response = apiService.agentLogin(
                    com.example.core.network.samm.SammAgentLoginRequest(
                        username = user,
                        password = pass
                    )
                )

                if (response.isSuccessful && response.body() != null) {
                    val loginResp = response.body()!!
                    val rawToken = loginResp.token
                    val agent = loginResp.agent

                    prefs.saveSammBaseUrl(normalizedUrl)
                    prefs.saveSammToken(rawToken)
                    prefs.saveSammAgentInfo(
                        agentId = agent.id,
                        username = agent.username,
                        resellerId = agent.resellerId
                    )
                    prefs.setLastSelectedLoginProvider(SasProviders.ALAMIRY)

                    audit.logAction(
                        action = "SAMM_LOGIN",
                        entityType = "PROVIDER",
                        entityId = normalizedUrl,
                        summary = "Agent ${agent.username} (ID: ${agent.id}) logged in"
                    )

                    // Immediately clear transient password from memory
                    _sammPassword.value = ""
                    onSuccess()
                } else {
                    val errBody = response.errorBody()?.string()
                    val errorDetail = com.example.core.network.samm.SammNetworkClient.parseErrorDetail(errBody)
                    val msg = when (response.code()) {
                        401 -> "Invalid username or password."
                        403 -> errorDetail ?: "Access denied: Account is not an authorized agent."
                        429 -> errorDetail ?: "Too many failed login attempts. Please try again later."
                        else -> errorDetail ?: "Login failed with code ${response.code()}."
                    }
                    _error.value = msg
                    _sammPassword.value = ""
                    onError(msg)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val msg = when (e) {
                    is java.net.UnknownHostException -> "Server not found. Check server URL."
                    is java.net.SocketTimeoutException -> "Connection timed out."
                    is java.io.IOException -> "Network connection failed: ${e.message}"
                    else -> e.message ?: "Failed to connect to SAMM server."
                }
                _error.value = msg
                _sammPassword.value = ""
                onError(msg)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logoutSamm(
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): kotlinx.coroutines.Job {
        val currentToken = prefs.getSammToken()
        val currentUrl = prefs.getSammBaseUrl()

        return viewModelScope.launch {
            _isLoading.value = true
            try {
                if (!currentToken.isNullOrBlank() && !currentUrl.isNullOrBlank()) {
                    try {
                        val api = com.example.core.network.samm.SammNetworkClient.createApiService(currentUrl, currentToken)
                        api.agentLogout()
                    } catch (_: Exception) {
                        // Fail-safe: even if server network call fails, local session is cleared
                    }
                }
                audit.logAction(
                    action = "SAMM_LOGOUT",
                    entityType = "PROVIDER",
                    entityId = currentUrl ?: "UNKNOWN",
                    summary = "Agent logged out from SAMM"
                )
            } finally {
                prefs.clearSammCredentials()
                _sammPassword.value = ""
                _isLoading.value = false
                onSuccess()
            }
        }
    }

    fun login() {
        if (_username.value.isEmpty() || _password.value.isEmpty()) {
            _error.value = "Username and password cannot be empty."
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = gateway.login(_username.value, _password.value)
                prefs.saveAuthToken(response.accessToken)
                
                // Automatically save ISP credentials
                prefs.saveIspAdminUsername(_username.value)
                prefs.saveIspAdminPassword(_password.value)
                syncRepo.triggerSettingsSync(reason = "auth_login")
                
                if (_rememberMe.value) {
                    prefs.saveUsername(_username.value)
                    prefs.savePassword(_password.value)
                    prefs.setRememberMe(true)
                } else {
                    prefs.clearUsername()
                    prefs.clearPassword()
                    prefs.setRememberMe(false)
                }
                audit.logAction(
                    action = "LOGIN",
                    entityType = "USER",
                    entityId = _username.value,
                    summary = "User logged in successfully"
                )
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message ?: "Authentication failed."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logout(
        force: Boolean = false,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                syncRepo.signOut(force = force)
                audit.logAction(
                    action = "LOGOUT",
                    entityType = "USER",
                    entityId = prefs.getUsername(),
                    summary = "User logged out"
                )
                prefs.clearAuthToken()
                _password.value = ""
                onSuccess()
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.e("AuthViewModel", "Failed to sign out from SyncRepository", e)
                onError(e.message ?: "Failed to sign out")
            }
        }
    }

    /**
     * Clears credentials for a single provider (EARTHLINK or ALAMIRY).
     * If the other provider remains configured, the app stays unlocked in single-provider mode.
     */
    fun clearProviderCredentials(provider: String) {
        when (provider) {
            SasProviders.EARTHLINK -> {
                prefs.clearEarthlinkCredentials()
                _password.value = ""
                viewModelScope.launch {
                    try {
                        audit.logAction(
                            action = "PROVIDER_REMOVE",
                            entityType = "PROVIDER",
                            entityId = SasProviders.EARTHLINK,
                            summary = "EarthLink credentials cleared by operator"
                        )
                    } catch (_: Throwable) {}
                }
            }
            SasProviders.ALAMIRY -> {
                prefs.clearSammCredentials()
                viewModelScope.launch {
                    try {
                        audit.logAction(
                            action = "PROVIDER_REMOVE",
                            entityType = "PROVIDER",
                            entityId = SasProviders.ALAMIRY,
                            summary = "SAMM credentials cleared by operator"
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * Clears credentials across all providers and resets session.
     */
    fun clearAllProviders() {
        prefs.clearCredentials()
        _password.value = ""
    }

    fun signInWithGoogle(idToken: String, email: String?, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val uid = syncRepo.googleSignIn(idToken)
                if (uid != null) {
                    val newToken = "google_oauth_session_$uid"
                    // A Google sign-in establishes a credential scope. Resident ISP admin
                    // credentials are only kept if they are already bound to THIS account; the
                    // 401 path clears the session token before the user re-signs, so comparing
                    // against the previous token would miss the switch entirely. TQ-18 item 9.
                    if (prefs.getIspCredentialOwner() != newToken) {
                        prefs.clearIspAdminCredentials()
                    }
                    prefs.saveAuthToken(newToken)
                    prefs.saveUsername(email ?: "google_user")
                    audit.logAction(
                        action = "GOOGLE_LOGIN",
                        entityType = "USER",
                        entityId = uid,
                        summary = "User logged in via Google Sign-In (${email ?: "unknown"})"
                    )
                    onSuccess()
                } else {
                    _error.value = "Failed to sign in with Google on Firebase."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message ?: "Google authentication failed."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveIspAdminCredentials(username: String, password: String) {
        prefs.saveIspAdminUsername(username.trim())
        prefs.saveIspAdminPassword(password.trim())
        syncRepo.triggerSettingsSync(reason = "save_isp_credentials")
        viewModelScope.launch {
            syncRepo.triggerSyncOneShot()
        }
    }
}
