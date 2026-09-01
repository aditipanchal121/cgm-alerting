package com.aadiinfo.nightwatch.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.AuthRepository
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null
)

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    var uiState by mutableStateOf(AuthUiState())
        private set

    fun onEmailChange(value: String) {
        uiState = uiState.copy(email = value, error = null)
    }

    fun onPasswordChange(value: String) {
        uiState = uiState.copy(password = value, error = null)
    }

    fun signIn(onSuccess: () -> Unit) = submit(onSuccess) {
        authRepository.signIn(uiState.email, uiState.password)
    }

    fun signUp(onSuccess: () -> Unit) = submit(onSuccess) {
        authRepository.signUp(uiState.email, uiState.password)
    }

    private fun submit(onSuccess: () -> Unit, action: suspend () -> Result<*>) {
        uiState = uiState.copy(loading = true, error = null)
        viewModelScope.launch {
            action().fold(
                onSuccess = {
                    uiState = uiState.copy(loading = false)
                    onSuccess()
                },
                onFailure = { err ->
                    uiState = uiState.copy(loading = false, error = err.message ?: "Something went wrong")
                }
            )
        }
    }
}
