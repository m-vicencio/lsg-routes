package cl.usach.diinf.lsg.routes.feature.explore.ui

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PreviewUiState {
    data object Loading : PreviewUiState
    data class Success(val route: Route) : PreviewUiState
    data class Error(val message: String) : PreviewUiState
}

@HiltViewModel
class RoutePreviewViewModel @Inject constructor(
    private val routeRepository: RouteRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val routeId: String = checkNotNull(savedStateHandle["routeId"])
    val isMine: Boolean = savedStateHandle.get<Boolean>("isMine") ?: false

    private val _uiState = MutableStateFlow<PreviewUiState>(PreviewUiState.Loading)
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    // null = inactivo; true = guardada; false = mientras guarda
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // El clima es opcional: se carga aparte para no bloquear la previsualizacion
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()

    init {
        viewModelScope.launch {
            routeRepository.getRoute(routeId)
                .onSuccess { _uiState.value = PreviewUiState.Success(it) }
                .onFailure { _uiState.value = PreviewUiState.Error(it.message ?: "") }
        }
        viewModelScope.launch {
            routeRepository.getRouteWeather(routeId).onSuccess { _weather.value = it }
        }
    }

    fun saveCopy() {
        if (_saving.value || _saved.value) return
        viewModelScope.launch {
            _saving.value = true
            routeRepository.copyRoute(routeId)
                .onSuccess { _saved.value = true }
                .onFailure { _error.update { it ?: "error" } }
            _saving.value = false
        }
    }

    fun clearError() = _error.update { null }
}
