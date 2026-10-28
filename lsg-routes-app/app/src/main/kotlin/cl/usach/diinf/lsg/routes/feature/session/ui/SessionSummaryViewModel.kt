package cl.usach.diinf.lsg.routes.feature.session.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.usach.diinf.lsg.routes.data.local.entity.SessionSyncStatus
import cl.usach.diinf.lsg.routes.domain.model.SessionSummary
import cl.usach.diinf.lsg.routes.domain.model.SessionTrack
import cl.usach.diinf.lsg.routes.domain.repository.LocalTrackingRepository
import cl.usach.diinf.lsg.routes.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SummaryUiState {
    data object Loading : SummaryUiState

    /** La sesion todavia no llego a SYNCED: se guardo en el dispositivo y el backend
     *  no calculo los puntos aun (no hubo conexion, o la subida sigue en curso). */
    data object Pending : SummaryUiState
    data class Success(
        val summary: SessionSummary,
        val distanceMInt: Int,
        val elapsedSecs: Int,
    ) : SummaryUiState
    data class Error(val message: String) : SummaryUiState
}

private val TERMINAL_STATUSES = setOf("SYNCED_TO_LSG", "REJECTED", "VALIDATED", "SYNCED", "UPLOAD_FAILED", "LSG_SYNC_FAILED")

@HiltViewModel
class SessionSummaryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val localTrackingRepository: LocalTrackingRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>("sessionId").orEmpty()
    private val distanceMInt: Int = savedStateHandle.get<Int>("distanceMInt") ?: 0
    private val elapsedSecs: Int = savedStateHandle.get<Int>("elapsedSecs") ?: 0

    private val _uiState = MutableStateFlow<SummaryUiState>(SummaryUiState.Loading)
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()

    // El track es opcional: se carga aparte para no bloquear el resumen
    private val _track = MutableStateFlow<SessionTrack?>(null)
    val track: StateFlow<SessionTrack?> = _track.asStateFlow()

    // Room reemite la sesion local ante cualquier cambio en la tabla, no solo la propia:
    // evita relanzar el polling del resumen mas de una vez.
    private var summaryRequested = false

    init { observeLocalStatus(); loadTrack() }

    private fun loadTrack() {
        if (sessionId.isEmpty()) return
        viewModelScope.launch {
            sessionRepository.getTrack(sessionId).onSuccess { _track.value = it }
        }
    }

    /** Antes de pedir el resumen al backend, mira el estado local de la sesion. Si
     *  todavia no esta SYNCED, no tiene sentido pedir /summary: la sesion puede ni
     *  siquiera existir en el backend todavia (arranco offline). Se muestra "pendiente" y se
     *  espera a que UploadWorker (WorkManager, corre solo con conexion) la sincronice. */
    private fun observeLocalStatus() {
        if (sessionId.isEmpty()) {
            _uiState.value = SummaryUiState.Error("ID de sesión no disponible")
            return
        }
        viewModelScope.launch {
            localTrackingRepository.observeSession(sessionId).collect { local ->
                if (local?.status == SessionSyncStatus.SYNCED) {
                    if (!summaryRequested) {
                        summaryRequested = true
                        fetchSummary()
                    }
                } else if (_uiState.value !is SummaryUiState.Success) {
                    _uiState.value = SummaryUiState.Pending
                }
            }
        }
    }

    private fun fetchSummary() {
        viewModelScope.launch {
            repeat(POLL_ATTEMPTS) {
                sessionRepository.getSummary(sessionId)
                    .onSuccess { summary ->
                        _uiState.value = SummaryUiState.Success(summary, distanceMInt, elapsedSecs)
                        if (summary.status in TERMINAL_STATUSES) return@launch
                    }
                    .onFailure { _uiState.value = SummaryUiState.Error(it.message ?: "") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val POLL_ATTEMPTS = 30
        const val POLL_INTERVAL_MS = 2_000L
    }
}
