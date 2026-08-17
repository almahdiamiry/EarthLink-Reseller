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

    init {
        if (prefs.getRememberMe()) {
            _username.value = prefs.getUsername() ?: ""
            _password.value = prefs.getPassword() ?: ""
        }
    }

    fun setUsername(value: String) { _username.value = value }
    fun setPassword(value: String) { _password.value = value }
    fun setRememberMe(value: Boolean) { _rememberMe.value = value }

    fun clearError() { _error.value = null }

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

    fun signInWithGoogle(idToken: String, email: String?, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val uid = syncRepo.googleSignIn(idToken)
                if (uid != null) {
                    prefs.saveAuthToken("google_oauth_session_$uid")
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
