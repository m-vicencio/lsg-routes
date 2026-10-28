package cl.usach.diinf.lsg.routes.feature.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.usach.diinf.lsg.routes.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState
    data object Success : LoginUiState
    data class Error(val messageRes: LoginError) : LoginUiState
}

enum class LoginError { INVALID_CREDENTIALS, NETWORK, UNKNOWN }

data class LoginFormState(
    val email: String = "",
    val password: String = "",
    val rememberMe: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(LoginFormState())
    val formState: StateFlow<LoginFormState> = _formState.asStateFlow()

    init {
        // Rellena los campos si el usuario marco antes "Recordar contraseña"
        authRepository.getSavedCredentials()?.let { (savedEmail, savedPassword) ->
            _formState.value = LoginFormState(
                email = savedEmail,
                password = savedPassword,
                rememberMe = true,
            )
        }
    }

    fun onEmailChange(email: String) = _formState.update { it.copy(email = email) }
    fun onPasswordChange(password: String) = _formState.update { it.copy(password = password) }
    fun onRememberMeChange(remember: Boolean) = _formState.update { it.copy(rememberMe = remember) }

    fun login() {
        val form = _formState.value
        if (form.email.isBlank() || form.password.isBlank()) return
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading
            authRepository.login(form.email, form.password)
                .onSuccess {
                    if (form.rememberMe) authRepository.saveCredentials(form.email, form.password)
                    else authRepository.clearCredentials()
                    _uiState.value = LoginUiState.Success
                }
                .onFailure { error ->
                    _uiState.value = LoginUiState.Error(
                        when (error) {
                            is HttpException -> if (error.code() == 401) LoginError.INVALID_CREDENTIALS else LoginError.UNKNOWN
                            is IOException -> LoginError.NETWORK
                            else -> LoginError.UNKNOWN
                        }
                    )
                }
        }
    }

    fun resetState() {
        _uiState.value = LoginUiState.Idle
    }
}
