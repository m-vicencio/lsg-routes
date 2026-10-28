package cl.usach.diinf.lsg.routes.feature.routes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.usach.diinf.lsg.routes.core.config.FeatureFlags
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.domain.model.Route
import cl.usach.diinf.lsg.routes.domain.repository.RouteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject

data class NewRouteUiState(
    val name: String = "",
    val mode: ActivityMode = ActivityMode.WALKING,
    val isDrawnTab: Boolean = true,
    val waypoints: List<LatLng> = emptyList(),
    val startPoint: LatLng? = null,
    val endPoint: LatLng? = null,
    val routeGeometry: List<LatLng> = emptyList(),
    val isFreehandActive: Boolean = false,  // lapiz activo: se capturan los puntos del arrastre
    val isFreehandRoute: Boolean = false,   // la ruta se guardara con origen FREEHAND
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedRoute: Route? = null,
)

@HiltViewModel
class NewRouteViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
    featureFlags: FeatureFlags,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewRouteUiState())
    val uiState: StateFlow<NewRouteUiState> = _uiState.asStateFlow()

    /** Decide si el selector ofrece la bicicleta; lo manda el backend. */
    val bikeEnabled: StateFlow<Boolean> = featureFlags.bikeEnabled

    fun onNameChange(name: String) = _uiState.update { it.copy(name = name) }
    fun onModeChange(mode: ActivityMode) = _uiState.update { it.copy(mode = mode) }
    fun onTabChange(isDrawn: Boolean) = _uiState.update {
        it.copy(
            isDrawnTab = isDrawn,
            waypoints = emptyList(),
            startPoint = null,
            endPoint = null,
            isFreehandActive = false,
            isFreehandRoute = false,
        )
    }

    fun addWaypoint(latLng: LatLng) = _uiState.update { it.copy(waypoints = it.waypoints + latLng) }
    fun undoLastWaypoint() = _uiState.update { it.copy(waypoints = it.waypoints.dropLast(1)) }

    /** Borra todos los puntos dibujados y apaga el modo dibujo libre. */
    fun clearWaypoints() = _uiState.update {
        it.copy(waypoints = emptyList(), isFreehandActive = false)
    }

    fun toggleFreehand() = _uiState.update {
        it.copy(isFreehandActive = !it.isFreehandActive, isFreehandRoute = true)
    }

    fun addFreehandPoint(latLng: LatLng) = _uiState.update { state ->
        val last = state.waypoints.lastOrNull()
        if (last != null && last.distanceTo(latLng) < MIN_FREEHAND_DISTANCE_M) return@update state
        state.copy(waypoints = state.waypoints + latLng)
    }

    fun setPoint(latLng: LatLng) {
        _uiState.update { state ->
            if (state.startPoint == null) state.copy(startPoint = latLng)
            else state.copy(endPoint = latLng)
        }
    }

    fun saveDrawnRoute() {
        val state = _uiState.value
        if (state.name.isBlank() || state.waypoints.size < 2) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val geometry = state.waypoints.map { listOf(it.latitude, it.longitude) }
            routeRepository.createDrawnRoute(state.name, state.mode, geometry, state.isFreehandRoute)
                .onSuccess { route -> _uiState.update { it.copy(isLoading = false, savedRoute = route) } }
                .onFailure { err -> _uiState.update { it.copy(isLoading = false, error = err.message) } }
        }
    }

    fun calculateAndSaveRoute() {
        val state = _uiState.value
        val start = state.startPoint ?: return
        val end = state.endPoint ?: return
        if (state.name.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            routeRepository.createRoutedRoute(
                name = state.name,
                mode = state.mode,
                startLatLon = listOf(start.latitude, start.longitude),
                endLatLon = listOf(end.latitude, end.longitude),
            ).onSuccess { route ->
                val geometry = route.geometry.map { LatLng(it[0], it[1]) }
                _uiState.update { it.copy(isLoading = false, savedRoute = route, routeGeometry = geometry) }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, error = err.message) }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    companion object {
        private const val MIN_FREEHAND_DISTANCE_M = 8.0
    }
}
