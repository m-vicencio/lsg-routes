package cl.usach.diinf.lsg.routes.feature.routes.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode

/** Pantalla de creacion de nueva ruta (modo dibujado, freehand, o A-B via ORS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewRouteScreen(
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    viewModel: NewRouteViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bikeEnabled by viewModel.bikeEnabled.collectAsStateWithLifecycle()
    val scaffoldState = rememberBottomSheetScaffoldState()
    val context = LocalContext.current

    // Si el permiso de ubicacion esta otorgado en este momento
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        locationGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    // Pide la ubicacion al entrar si todavia no esta otorgada
    LaunchedEffect(Unit) {
        if (!locationGranted) {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    LaunchedEffect(uiState.savedRoute) {
        if (uiState.savedRoute != null) onSaved()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            scaffoldState.snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 280.dp,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_new_route_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (uiState.isDrawnTab && uiState.waypoints.isNotEmpty()) {
                        IconButton(onClick = { viewModel.undoLastWaypoint() }) {
                            Icon(Icons.Default.Undo, contentDescription = stringResource(R.string.action_undo))
                        }
                        IconButton(onClick = { viewModel.clearWaypoints() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
            )
        },
        sheetContent = {
            Column(Modifier.padding(horizontal = 16.dp)) {
                TabRow(selectedTabIndex = if (uiState.isDrawnTab) 0 else 1) {
                    Tab(selected = uiState.isDrawnTab, onClick = { viewModel.onTabChange(true) },
                        text = { Text(stringResource(R.string.tab_draw)) })
                    Tab(selected = !uiState.isDrawnTab, onClick = { viewModel.onTabChange(false) },
                        text = { Text(stringResource(R.string.tab_point_a_to_b)) })
                }

                Spacer(Modifier.height(12.dp))

                Row {
                    ActivityMode.entries.filter { bikeEnabled || it != ActivityMode.CYCLING }.forEach { mode ->
                        FilterChip(
                            selected = uiState.mode == mode,
                            onClick = { viewModel.onModeChange(mode) },
                            label = { Text(mode.labelEs) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.onNameChange(it) },
                    label = { Text(stringResource(R.string.label_route_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                if (!uiState.isDrawnTab) {
                    Text(
                        text = when {
                            uiState.startPoint == null -> stringResource(R.string.hint_long_press_start)
                            uiState.endPoint == null -> stringResource(R.string.hint_long_press_end)
                            else -> stringResource(R.string.hint_route_ready)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Button(
                    onClick = { if (uiState.isDrawnTab) viewModel.saveDrawnRoute() else viewModel.calculateAndSaveRoute() },
                    enabled = !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                    } else {
                        Text(
                            if (uiState.isDrawnTab) stringResource(R.string.action_save_route)
                            else stringResource(R.string.action_calculate_route)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            MapLibreMapView(
                modifier = Modifier.fillMaxSize(),
                waypoints = if (uiState.isDrawnTab) uiState.waypoints else uiState.routeGeometry,
                showWaypointDots = uiState.isDrawnTab,  // un punto por cada toque o arrastre; apagado para la geometria de ORS
                startPoint = if (!uiState.isDrawnTab) uiState.startPoint else null,
                endPoint = if (!uiState.isDrawnTab) uiState.endPoint else null,
                isFreehandMode = uiState.isFreehandActive,
                locationPermissionGranted = locationGranted,
                onMapClick = { latLng ->
                    if (uiState.isDrawnTab && !uiState.isFreehandActive) viewModel.addWaypoint(latLng)
                },
                onMapLongClick = { latLng ->
                    if (!uiState.isDrawnTab) viewModel.setPoint(latLng)
                },
                onFreehandPoint = { latLng ->
                    if (uiState.isFreehandActive) viewModel.addFreehandPoint(latLng)
                },
            )

            // FAB del lapiz: solo en la pestaña de dibujo
            if (uiState.isDrawnTab) {
                FloatingActionButton(
                    onClick = { viewModel.toggleFreehand() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    containerColor = if (uiState.isFreehandActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = if (uiState.isFreehandActive) Icons.Default.EditOff else Icons.Default.Edit,
                        contentDescription = if (uiState.isFreehandActive) "Detener dibujo libre" else "Activar dibujo libre",
                        tint = if (uiState.isFreehandActive)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}
