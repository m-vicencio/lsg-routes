package cl.usach.diinf.lsg.routes.feature.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.usach.diinf.lsg.routes.core.ui.theme.ThemeMode
import cl.usach.diinf.lsg.routes.core.ui.theme.ThemePreferences
import cl.usach.diinf.lsg.routes.domain.model.ActivityStats
import cl.usach.diinf.lsg.routes.domain.model.PointTransaction
import cl.usach.diinf.lsg.routes.domain.model.PointsBalance
import cl.usach.diinf.lsg.routes.domain.model.User
import cl.usach.diinf.lsg.routes.domain.repository.ActivityRepository
import cl.usach.diinf.lsg.routes.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Success(
        val user: User,
        val stats: ActivityStats?,
        val pointsBalance: PointsBalance?,
        val transactions: List<PointTransaction>,
    ) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val activityRepository: ActivityRepository,
    private val themePreferences: ThemePreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = themePreferences.themeMode

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch { fetch() }
    }

    /** Recarga pedida con el gesto de tirar hacia abajo.
     *
     *  Es la unica forma de ver una actividad recien sincronizada sin reiniciar la app:
     *  este ViewModel sobrevive a los cambios de pestaña (saveState/restoreState), asi
     *  que la carga de init no se repite. */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                fetch()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetch() {
        authRepository.getMe()
            .onSuccess { user ->
                // La actividad y el saldo son opcionales: si fallan, igual mostramos el perfil
                val stats = activityRepository.getMyActivity().getOrNull()
                val pointsBalance = activityRepository.getPointsBalance().getOrNull()
                val transactions = activityRepository.getTransactions(limit = 20).getOrDefault(emptyList())
                _uiState.value = ProfileUiState.Success(user, stats, pointsBalance, transactions)
            }
            .onFailure { _uiState.value = ProfileUiState.Error(it.message ?: "") }
    }

    fun setThemeMode(mode: ThemeMode) = themePreferences.setThemeMode(mode)

    fun logout() = authRepository.logout()
}
