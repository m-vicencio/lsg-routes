package cl.usach.diinf.lsg.routes.feature.routes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.core.ui.RefreshableMessage
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.domain.model.Route
import kotlin.math.roundToInt

/** Pantalla con el listado de rutas planificadas del usuario. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesListScreen(
    onNewRoute: () -> Unit = {},
    onRouteClick: (String) -> Unit = {},
    onFreeRoam: () -> Unit = {},
    onPendingActivities: () -> Unit = {},
    viewModel: RoutesListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    // Recarga cada vez que la pantalla vuelve a primer plano (por ejemplo, tras guardar una ruta)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadRoutes()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_routes_list_title)) },
                actions = {
                    IconButton(onClick = onPendingActivities) {
                        BadgedBox(badge = {
                            if (pendingCount > 0) Badge { Text("$pendingCount") }
                        }) {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = stringResource(R.string.screen_pending_activities_title),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // El recorrido libre queda secundario respecto a crear/elegir una ruta,
            // que es lo que mas puntos rinde.
            Column(horizontalAlignment = Alignment.End) {
                ExtendedFloatingActionButton(
                    onClick = onFreeRoam,
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_free_roam)) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
                Spacer(Modifier.height(12.dp))
                FloatingActionButton(onClick = onNewRoute) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_new_route))
                }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when (val state = uiState) {
                is RoutesListUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is RoutesListUiState.Error -> RefreshableMessage(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                )
                is RoutesListUiState.Success -> {
                    if (state.routes.isEmpty()) {
                        RefreshableMessage(text = stringResource(R.string.routes_empty))
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.routes, key = { it.id }) { route ->
                                RouteListItem(route = route, onClick = { onRouteClick(route.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteListItem(route: Route, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        ListItem(
            headlineContent = { Text(route.name) },
            supportingContent = {
                val distanceText = route.plannedDistanceM?.let {
                    if (it >= 1000) "${"%.1f".format(it / 1000)} km" else "${it.roundToInt()} m"
                } ?: ""
                Text("${route.mode.labelEs}  $distanceText")
            },
            leadingContent = {
                Icon(
                    imageVector = when (route.mode) {
                        ActivityMode.CYCLING -> Icons.Default.DirectionsBike
                        ActivityMode.JOGGING -> Icons.Default.DirectionsRun
                        ActivityMode.HIKING -> Icons.Default.Terrain
                        ActivityMode.WALKING -> Icons.Default.DirectionsWalk
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}
