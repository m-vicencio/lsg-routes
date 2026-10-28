package cl.usach.diinf.lsg.routes.feature.routes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.usach.diinf.lsg.routes.domain.model.Route
import cl.usach.diinf.lsg.routes.domain.repository.LocalTrackingRepository
import cl.usach.diinf.lsg.routes.domain.repository.RouteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RoutesListUiState {
    data object Loading : RoutesListUiState
    data class Success(val routes: List<Route>) : RoutesListUiState
    data class Error(val message: String) : RoutesListUiState
}

@HiltViewModel
class RoutesListViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
    localTrackingRepository: LocalTrackingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RoutesListUiState>(RoutesListUiState.Loading)
    val uiState: StateFlow<RoutesListUiState> = _uiState.asStateFlow()

    /** Cuantas actividades grabadas en el dispositivo todavia no llegan a SYNCED. */
    val pendingCount: StateFlow<Int> = localTrackingRepository.observePendingSessions()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Sin carga en init: RoutesListScreen ya llama a loadRoutes() en ON_RESUME, que se
    // dispara tambien la primera vez; cargar tambien aqui duplicaria la peticion.

    fun loadRoutes() {
        viewModelScope.launch { fetch() }
    }

    /** Recarga pedida con el gesto de tirar hacia abajo: el aviso de progreso lo da el
     *  indicador del gesto, asi que la lista no se reemplaza por el spinner central. */
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
        // El spinner solo en la primera carga. En las recargas se conserva la lista
        // en pantalla; si no, cada cambio de pestaña la hace parpadear.
        if (_uiState.value !is RoutesListUiState.Success) _uiState.value = RoutesListUiState.Loading
        routeRepository.getRoutes()
            .onSuccess { _uiState.value = RoutesListUiState.Success(it) }
            .onFailure { _uiState.value = RoutesListUiState.Error(it.message ?: "") }
    }
}
