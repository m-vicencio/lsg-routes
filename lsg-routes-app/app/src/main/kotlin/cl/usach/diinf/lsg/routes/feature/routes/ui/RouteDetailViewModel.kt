package cl.usach.diinf.lsg.routes.feature.routes.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.usach.diinf.lsg.routes.domain.model.Route
import cl.usach.diinf.lsg.routes.domain.model.Weather
import cl.usach.diinf.lsg.routes.domain.repository.RouteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RouteDetailUiState {
    data object Loading : RouteDetailUiState
    data class Success(val route: Route) : RouteDetailUiState
    data class Error(val message: String) : RouteDetailUiState
}

@HiltViewModel
class RouteDetailViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val routeId: String = checkNotNull(savedStateHandle["routeId"])

    private val _uiState = MutableStateFlow<RouteDetailUiState>(RouteDetailUiState.Loading)
    val uiState: StateFlow<RouteDetailUiState> = _uiState.asStateFlow()

    // El clima es opcional: se carga aparte para no bloquear el detalle de la ruta
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()

    init {
        viewModelScope.launch {
            routeRepository.getRoute(routeId)
                .onSuccess { _uiState.value = RouteDetailUiState.Success(it) }
                .onFailure { _uiState.value = RouteDetailUiState.Error(it.message ?: "") }
        }
        viewModelScope.launch {
            routeRepository.getRouteWeather(routeId).onSuccess { _weather.value = it }
        }
    }

    /**
     * Elimina la ruta (borrado logico en el backend). Emite true cuando termina para
     * que la pantalla vuelva atras. Si falla se queda en false y la ruta sigue ahi.
     */
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    fun delete() {
        viewModelScope.launch {
            routeRepository.deleteRoute(routeId).onSuccess { _deleted.value = true }
        }
    }

    /** true si el ultimo intento de publicar fallo (p. ej. TRIM_TOO_LONG o sin red). */
    private val _publishFailed = MutableStateFlow(false)
    val publishFailed: StateFlow<Boolean> = _publishFailed.asStateFlow()

    fun dismissPublishError() { _publishFailed.value = false }

    /** Publica o vuelve privada la ruta. Al publicar, oculta hideStartM/hideEndM metros de cada extremo. */
    fun setPublic(isPublic: Boolean, hideStartM: Int = 0, hideEndM: Int = 0) {
        val current = (_uiState.value as? RouteDetailUiState.Success)?.route ?: return
        viewModelScope.launch {
            routeRepository.setRouteVisibility(current.id, isPublic, hideStartM, hideEndM)
                .onSuccess { _uiState.value = RouteDetailUiState.Success(it) }
                .onFailure { if (isPublic) _publishFailed.value = true }
        }
    }
}
