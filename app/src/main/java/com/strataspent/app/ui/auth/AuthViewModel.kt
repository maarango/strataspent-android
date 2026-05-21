package com.strataspent.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val mode: Mode = Mode.SignIn,
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    enum class Mode { SignIn, SignUp }

    val canSubmit: Boolean
        get() = !loading && email.isNotBlank() && password.length >= 6 &&
                (mode == Mode.SignIn || displayName.isNotBlank())
}

class AuthViewModel(private val repo: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun toggleMode() = _state.update {
        it.copy(
            mode = if (it.mode == AuthUiState.Mode.SignIn) AuthUiState.Mode.SignUp
            else AuthUiState.Mode.SignIn,
            error = null,
        )
    }

    fun onDisplayName(v: String) = _state.update { it.copy(displayName = v) }
    fun onEmail(v: String) = _state.update { it.copy(email = v) }
    fun onPassword(v: String) = _state.update { it.copy(password = v) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                if (s.mode == AuthUiState.Mode.SignIn) {
                    repo.signIn(s.email, s.password)
                } else {
                    repo.signUp(s.displayName.trim(), s.email, s.password)
                }
            }.onFailure { t ->
                _state.update { it.copy(loading = false, error = t.message ?: "Auth failed") }
            }.onSuccess {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun signInWithGoogleToken(idToken: String) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.signInWithGoogleIdToken(idToken) }
                .onFailure { t ->
                    _state.update { it.copy(loading = false, error = t.message ?: "Google sign-in failed") }
                }
                .onSuccess {
                    _state.update { it.copy(loading = false) }
                }
        }
    }

    fun reportGoogleSignInError(message: String) {
        _state.update { it.copy(loading = false, error = message) }
    }

    fun setGoogleSignInLoading(loading: Boolean) {
        _state.update { it.copy(loading = loading, error = if (loading) null else it.error) }
    }
}
