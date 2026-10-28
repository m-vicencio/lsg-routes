package cl.usach.diinf.lsg.routes.feature.routes.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.domain.model.Route
import cl.usach.diinf.lsg.routes.domain.model.Weather
import kotlin.math.roundToInt

/** Pantalla de detalle de una ruta planificada con vista del mapa y boton de inicio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(
    onBack: () -> Unit = {},
    onStartRoute: (String) -> Unit = {},
    viewModel: RouteDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val publishFailed by viewModel.publishFailed.collectAsStateWithLifecycle()
    var showPublishDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val route = (uiState as? RouteDetailUiState.Success)?.route

    // La lista recarga en ON_RESUME, asi que basta con volver atras.
    LaunchedEffect(deleted) { if (deleted) onBack() }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text(stringResource(R.string.route_delete_title)) },
            text = { Text(stringResource(R.string.route_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete()
                    showDeleteDialog = false
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (publishFailed) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPublishError,
            text = { Text(stringResource(R.string.share_publish_failed)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPublishError) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    if (showPublishDialog) {
        // Por defecto no se oculta nada; el recorte lo aplica el backend a quien no es dueno
        var hideStartM by remember { mutableIntStateOf(0) }
        var hideEndM by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { showPublishDialog = false },
            icon = { Icon(Icons.Default.Public, null) },
            title = { Text(stringResource(R.string.share_publish_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.share_privacy_warning))
                    Spacer(Modifier.height(12.dp))
                    HideDistanceSelector(stringResource(R.string.share_hide_start), hideStartM) { hideStartM = it }
                    HideDistanceSelector(stringResource(R.string.share_hide_end), hideEndM) { hideEndM = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setPublic(true, hideStartM, hideEndM)
                    showPublishDialog = false
                }) { Text(stringResource(R.string.action_make_public)) }
            },
            dismissButton = {
                TextButton(onClick = { showPublishDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_route_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (route != null) {
                        if (route.isPublic) {
                            IconButton(onClick = { viewModel.setPublic(false) }) {
                                Icon(
                                    Icons.Default.PublicOff,
                                    contentDescription = stringResource(R.string.action_make_private),
                                )
                            }
                        } else {
                            IconButton(onClick = { showPublishDialog = true }) {
                                Icon(
                                    Icons.Default.Public,
                                    contentDescription = stringResource(R.string.action_make_public),
                                )
                            }
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is RouteDetailUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is RouteDetailUiState.Error -> Text(
                    state.message,
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error,
                )
                is RouteDetailUiState.Success -> RouteDetailContent(
                    route = state.route,
                    weather = weather,
                    onStartRoute = { onStartRoute(state.route.id) },
                )
            }
        }
    }
}

/** Metros que se pueden ocultar de cada extremo al publicar (el backend acepta hasta 2000). */
private val HIDE_OPTIONS_M = listOf(0, 200, 500, 1000)

@Composable
private fun HideDistanceSelector(label: String, selectedM: Int, onSelect: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HIDE_OPTIONS_M.forEach { meters ->
            FilterChip(
                selected = selectedM == meters,
                onClick = { onSelect(meters) },
                label = {
                    Text(
                        when {
                            meters == 0 -> stringResource(R.string.share_hide_none)
                            meters >= 1000 -> stringResource(R.string.share_hide_km, meters / 1000)
                            else -> stringResource(R.string.share_hide_meters, meters)
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun RouteDetailContent(route: Route, weather: Weather?, onStartRoute: () -> Unit) {
    val context = LocalContext.current
    // Esta pantalla no pide el permiso; solo informa al mapa si ya esta concedido,
    // que es lo que habilita el punto del usuario y el centrado.
    val locationGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxSize()) {
        // Vista previa del mapa con geometria de la ruta
        MapLibreMapView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            waypoints = route.geometry.mapNotNull { coords ->
                if (coords.size >= 2) org.maplibre.android.geometry.LatLng(coords[0], coords[1]) else null
            },
            locationPermissionGranted = locationGranted,
        )

        // Informacion de la ruta
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(route.name, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.size(8.dp))
                if (route.isPublic) {
                    val chipLabel = when {
                        route.hideStartM > 0 && route.hideEndM > 0 -> R.string.route_public_hidden_both
                        route.hideStartM > 0 -> R.string.route_public_hidden_start
                        route.hideEndM > 0 -> R.string.route_public_hidden_end
                        else -> R.string.route_public_chip
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(chipLabel)) },
                        leadingIcon = { Icon(Icons.Default.Public, null, Modifier.size(16.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = route.mode.labelEs,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            route.plannedDistanceM?.let { dist ->
                val distText = if (dist >= 1000) "${"%.2f".format(dist / 1000)} km" else "${dist.roundToInt()} m"
                Text(stringResource(R.string.label_distance, distText))
            }
            route.plannedDurationS?.let { dur ->
                val minutes = dur / 60
                Text(stringResource(R.string.label_duration, minutes))
            }

            weather?.let {
                Spacer(Modifier.height(16.dp))
                WeatherCard(it)
            }

            Spacer(Modifier.height(16.dp))

            Button(onClick = onStartRoute, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_start_route))
            }
        }
    }
}

