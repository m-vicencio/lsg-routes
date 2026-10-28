package cl.usach.diinf.lsg.routes.feature.explore.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.usach.diinf.lsg.routes.domain.model.PublicRoute
import cl.usach.diinf.lsg.routes.domain.repository.RouteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ExploreUiState {
    data object Loading : ExploreUiState
    data class Success(val routes: List<PublicRoute>) : ExploreUiState
    data class Error(val message: String) : ExploreUiState
}

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ExploreUiState>(ExploreUiState.Loading)
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Sin carga en init: ExploreScreen ya llama a load() en ON_RESUME, que se dispara
    // tambien la primera vez; cargar tambien aqui duplicaria la peticion.

    fun load() {
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
        if (_uiState.value !is ExploreUiState.Success) _uiState.value = ExploreUiState.Loading
        routeRepository.getPublicRoutes()
            .onSuccess { _uiState.value = ExploreUiState.Success(it) }
            .onFailure { _uiState.value = ExploreUiState.Error(it.message ?: "") }
    }
}
