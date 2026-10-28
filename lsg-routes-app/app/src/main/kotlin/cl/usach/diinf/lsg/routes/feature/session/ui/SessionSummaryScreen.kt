package cl.usach.diinf.lsg.routes.feature.session.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.domain.model.SessionSummary
import cl.usach.diinf.lsg.routes.domain.model.SessionTrack

/** Pantalla de resultados de la sesion finalizada. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSummaryScreen(
    onBack: () -> Unit = {},
    viewModel: SessionSummaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()

    // El boton atras del sistema usa la misma navegacion segura que "Volver"
    BackHandler { onBack() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_session_summary_title)) }) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is SummaryUiState.Loading -> LoadingContent(Modifier.align(Alignment.Center))
                is SummaryUiState.Pending -> PendingContent(onBack = onBack)
                is SummaryUiState.Error -> Text(
                    state.message,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                is SummaryUiState.Success -> ResultadosContent(
                    summary = state.summary,
                    distanceMInt = state.distanceMInt,
                    elapsedSecs = state.elapsedSecs,
                    track = track,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.summary_processing), style = MaterialTheme.typography.bodyMedium)
    }
}

/** La ruta ya se guardo en el dispositivo pero el backend todavia no la recibio
 *  (sin conexion, o la subida sigue en curso): ahi se calculan los puntos, asi que
 *  todavia no hay nada que mostrar mas alla de lo que se registro localmente. */
@Composable
private fun PendingContent(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.summary_points_pending),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.summary_points_pending_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back_to_routes))
        }
    }
}

@Composable
private fun ResultadosContent(
    summary: SessionSummary,
    distanceMInt: Int,
    elapsedSecs: Int,
    track: SessionTrack?,
    onBack: () -> Unit,
) {
    val isRejected = summary.status == "REJECTED"
    val isTerminal = summary.status in setOf("SYNCED_TO_LSG", "REJECTED", "VALIDATED", "LSG_SYNC_FAILED")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Estado
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRejected) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when {
                        isRejected -> stringResource(R.string.status_rejected)
                        isTerminal -> stringResource(R.string.status_completed)
                        else -> stringResource(R.string.status_processing)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isRejected) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Estadisticas del recorrido
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                val distanceKm = distanceMInt / 1000.0
                val speedKmh = if (elapsedSecs > 0) distanceKm / (elapsedSecs / 3600.0) else 0.0
                ResultStat(
                    label = stringResource(R.string.label_stat_distance),
                    value = if (distanceMInt >= 1000) "${"%.2f".format(distanceKm)} km"
                    else "$distanceMInt m",
                )
                ResultStat(
                    label = stringResource(R.string.label_stat_time),
                    value = elapsedSecs.toTimeString(),
                )
                ResultStat(
                    label = stringResource(R.string.label_stat_speed),
                    value = "${"%.1f".format(speedKmh)} km/h",
                )
            }
        }

        // Mapa del recorrido (guia planificada + track GPS real)
        if (track.hasGeometry()) {
            Spacer(Modifier.height(16.dp))
            RouteReplayCard(track!!)
        }

        Spacer(Modifier.height(16.dp))

        // Puntos y adherencia (del backend)
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val points = summary.awardedPoints ?: 0
                Text(
                    text = if (isRejected) "0" else "+$points",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isRejected) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.label_lsg_points),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Un recorrido libre no tiene ruta planificada contra la cual medir
                // adherencia: en su lugar se etiqueta como tal.
                val isFreeRoam = track?.plannedGeometry?.isEmpty() == true
                val adherence = summary.adherenceRatio
                Spacer(Modifier.height(4.dp))
                when {
                    adherence != null -> Text(
                        text = stringResource(
                            R.string.label_adherence, "%.0f%%".format(adherence * 100)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    isFreeRoam -> Text(
                        text = stringResource(R.string.label_free_roam),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Validadores
        if (summary.validators.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.label_validators),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    summary.validators.entries.forEachIndexed { index, (name, result) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = name.replace("_", " ").replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = result,
                                fontWeight = FontWeight.Bold,
                                color = if (result == "OK") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back_to_routes))
        }
    }
}

@Composable
private fun ResultStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Int.toTimeString(): String {
    val h = this / 3600
    val m = (this % 3600) / 60
    val s = this % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
