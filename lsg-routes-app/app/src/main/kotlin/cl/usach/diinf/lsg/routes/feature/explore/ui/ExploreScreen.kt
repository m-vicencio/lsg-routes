package cl.usach.diinf.lsg.routes.feature.explore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.core.ui.RefreshableMessage
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.domain.model.PublicRoute
import kotlin.math.roundToInt

/** Feed comunitario: rutas públicas que otros usuarios han compartido. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onRouteClick: (String, Boolean) -> Unit = { _, _ -> },
    viewModel: ExploreViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    // Recargar al volver a primer plano (aparecen rutas recién publicadas)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_explore_title)) }) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when (val state = uiState) {
                is ExploreUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ExploreUiState.Error -> RefreshableMessage(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                )
                is ExploreUiState.Success -> {
                    if (state.routes.isEmpty()) {
                        RefreshableMessage(text = stringResource(R.string.explore_empty))
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.routes, key = { it.id }) { route ->
                                PublicRouteItem(
                                    route = route,
                                    onClick = { onRouteClick(route.id, route.isMine) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicRouteItem(route: PublicRoute, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(route.name)
                    if (route.isMine) {
                        Spacer(Modifier.size(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.route_mine_chip)) },
                        )
                    }
                }
            },
            supportingContent = {
                Column {
                    val distanceText = route.plannedDistanceM?.let {
                        if (it >= 1000) "${"%.1f".format(it / 1000)} km" else "${it.roundToInt()} m"
                    } ?: ""
                    Text("${route.mode.labelEs}  $distanceText")
                    route.sharedByName?.let { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Person, null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.route_shared_by, name),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            leadingContent = {
                Icon(
                    imageVector = modeIcon(route.mode),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}

private fun modeIcon(mode: ActivityMode): ImageVector = when (mode) {
    ActivityMode.CYCLING -> Icons.Default.DirectionsBike
    ActivityMode.JOGGING -> Icons.Default.DirectionsRun
    ActivityMode.HIKING -> Icons.Default.Terrain
    ActivityMode.WALKING -> Icons.Default.DirectionsWalk
}
