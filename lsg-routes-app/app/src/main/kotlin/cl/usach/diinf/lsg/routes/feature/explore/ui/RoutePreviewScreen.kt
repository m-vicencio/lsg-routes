package cl.usach.diinf.lsg.routes.feature.explore.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.domain.model.Route
import cl.usach.diinf.lsg.routes.domain.model.Weather
import cl.usach.diinf.lsg.routes.feature.routes.ui.MapLibreMapView
import cl.usach.diinf.lsg.routes.feature.routes.ui.WeatherCard
import kotlin.math.roundToInt

/** Previsualización de una ruta pública con opción de guardar una copia. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePreviewScreen(
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    viewModel: RoutePreviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = stringResource(R.string.error_unknown)

    LaunchedEffect(saved) { if (saved) onSaved() }
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(errorText)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_route_preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val state = uiState) {
                is PreviewUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is PreviewUiState.Error -> Text(
                    state.message,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                is PreviewUiState.Success -> PreviewContent(
                    route = state.route,
                    weather = weather,
                    isMine = viewModel.isMine,
                    saving = saving,
                    onSaveCopy = { viewModel.saveCopy() },
                )
            }
        }
    }
}

@Composable
private fun PreviewContent(
    route: Route,
    weather: Weather?,
    isMine: Boolean,
    saving: Boolean,
    onSaveCopy: () -> Unit,
) {
    val routePoints = route.geometry.mapNotNull { c ->
        if (c.size >= 2) org.maplibre.android.geometry.LatLng(c[0], c[1]) else null
    }
    Box(Modifier.fillMaxSize()) {
        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
            waypoints = routePoints,
            cameraBounds = routePoints,  // centra el mapa en la ruta, no en el usuario
        )
        // Panel inferior: colapsado muestra nombre/modo; al tocarlo se expande con detalles
        PreviewDetailsSheet(
            route = route,
            weather = weather,
            isMine = isMine,
            saving = saving,
            onSaveCopy = onSaveCopy,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PreviewDetailsSheet(
    route: Route,
    weather: Weather?,
    isMine: Boolean,
    saving: Boolean,
    onSaveCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // Cabecera: siempre visible, toca para expandir/colapsar
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(route.name, style = MaterialTheme.typography.headlineSmall)
                    Text(route.mode.labelEs, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = stringResource(
                        if (expanded) R.string.action_collapse else R.string.action_expand
                    ),
                )
            }

            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(12.dp))

                    route.plannedDistanceM?.let { dist ->
                        val t = if (dist >= 1000) "${"%.2f".format(dist / 1000)} km" else "${dist.roundToInt()} m"
                        Text(stringResource(R.string.label_distance, t))
                    }
                    route.plannedDurationS?.let { Text(stringResource(R.string.label_duration, it / 60)) }
                    route.sharedByName?.let {
                        Text(
                            text = stringResource(R.string.route_shared_by, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    weather?.let {
                        Spacer(Modifier.height(12.dp))
                        WeatherCard(it)
                    }

                    Spacer(Modifier.height(16.dp))

                    if (isMine) {
                        Text(
                            text = stringResource(R.string.preview_already_yours),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(onClick = onSaveCopy, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                            if (saving) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.action_save_copy))
                            }
                        }
                    }
                }
            }
        }
    }
}
