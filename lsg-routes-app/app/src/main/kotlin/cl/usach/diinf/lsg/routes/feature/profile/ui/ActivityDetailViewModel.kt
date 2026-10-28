package cl.usach.diinf.lsg.routes.feature.profile.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.usach.diinf.lsg.routes.domain.model.ActivityItem
import cl.usach.diinf.lsg.routes.domain.model.SessionTrack
import cl.usach.diinf.lsg.routes.domain.repository.ActivityRepository
import cl.usach.diinf.lsg.routes.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ActivityDetailUiState {
    data object Loading : ActivityDetailUiState
    data class Success(val item: ActivityItem?, val track: SessionTrack?) : ActivityDetailUiState
    data class Error(val message: String) : ActivityDetailUiState
}

@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val sessionRepository: SessionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()

    private val _uiState = MutableStateFlow<ActivityDetailUiState>(ActivityDetailUiState.Loading)
    val uiState: StateFlow<ActivityDetailUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch { fetch() }
    }

    /** Recarga pedida con el gesto de tirar hacia abajo: el aviso de progreso lo da el
     *  indicador del gesto, asi que no se vuelve al spinner central. */
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
        if (sessionId.isEmpty()) {
            _uiState.value = ActivityDetailUiState.Error("ID de sesión no disponible")
            return
        }
        // La actividad sale del historial local (ActivityLog); el track del backend.
        // Ambos best-effort: si falta uno, igual mostramos lo disponible.
        coroutineScope {
            val itemDeferred = async {
                activityRepository.getMyActivity().getOrNull()
                    ?.recent?.firstOrNull { it.sessionId == sessionId }
            }
            val trackDeferred = async { sessionRepository.getTrack(sessionId).getOrNull() }
            _uiState.value = ActivityDetailUiState.Success(itemDeferred.await(), trackDeferred.await())
        }
    }
}
