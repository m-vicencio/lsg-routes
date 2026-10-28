package cl.usach.diinf.lsg.routes.feature.session.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.core.permissions.PermissionCatalog
import cl.usach.diinf.lsg.routes.core.permissions.PermissionGroup
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.feature.routes.ui.MapLibreMapView
import org.maplibre.android.geometry.LatLng

/** Pantalla de seguimiento de ruta activa con GPS en tiempo real. */
@Composable
fun ActiveRouteScreen(
    idPlayers: Int = 0,
    onFinished: (sessionId: String, distanceMInt: Int, elapsedSecs: Int) -> Unit = { _, _, _ -> },
    viewModel: ActiveRouteViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // El mapa necesita saber cuando el permiso queda concedido: si solo se mira al
    // entrar, el punto del usuario nunca aparece cuando se concede en el dialogo.
    var locationGranted by remember {
        mutableStateOf(PermissionCatalog.isGroupGranted(context, PermissionGroup.LOCATION))
    }

    // Una sola solicitud con todos los permisos del catalogo. Lanzar varias seguidas
    // no funciona: Android atiende una sola a la vez y descarta en silencio las que
    // llegan mientras hay un dialogo abierto (el de ubicacion se perderia).
    val requiredPermissions = remember {
        PermissionCatalog.requiredGroups().flatMap { it.permissions }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // La sesion solo depende de la ubicacion precisa; pasos y notificaciones son
        // deseables pero no bloquean el seguimiento.
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            locationGranted = true
            viewModel.startSession(idPlayers)
        }
    }

    LaunchedEffect(Unit) {
        if (PermissionCatalog.allGranted(context)) viewModel.startSession(idPlayers)
        else permissionLauncher.launch(requiredPermissions)
    }

    // Solo para dibujar: la distancia y los puntos salen de las anclas sin suavizar.
    // La clave del remember compara por contenido, asi que las recomposiciones de
    // 1 Hz del cronometro no rehacen el trazo; solo lo hace un ancla nueva.
    val trackLine = remember(uiState.fixes) {
        smoothForDisplay(uiState.fixes.map { GeoPoint(it.lat, it.lon) })
            .map { LatLng(it.lat, it.lon) }
    }
    val plannedLine = remember(uiState.plannedRoute) {
        uiState.plannedRoute.map { LatLng(it[0], it[1]) }
    }

    Column(Modifier.fillMaxSize()) {
        MapLibreMapView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            waypoints = trackLine,
            plannedRoute = plannedLine,   // ORS ya viene pegada a la calle: no se suaviza
            locationPermissionGranted = locationGranted,
            followGps = true,
        )

        if (uiState.error != null) {
            ErrorPanel(errorKey = uiState.error!!)
        } else if (uiState.finished) {
            // Sesion terminada: un boton explicito hace que la navegacion la dispare siempre
            // una accion directa del usuario en el hilo principal, y no una corrutina de
            // LaunchedEffect. Evita un caso borde de Navigation 2.8 en que la navegacion
            // automatica compite con el desmontaje del composable y vacia la pila.
            FinishedPanel(
                distanceM = uiState.distanceM,
                elapsedSeconds = uiState.elapsedSeconds,
                failedNoMovement = uiState.failedNoMovement,
                cutForSpeeding = uiState.cutForSpeeding,
                onViewResults = {
                    onFinished(
                        uiState.sessionId,
                        uiState.distanceM.toInt(),
                        uiState.elapsedSeconds.toInt(),
                    )
                },
            )
        } else {
            ActivePanel(
                uiState = uiState,
                onFinish = { viewModel.finishSession() },
            )
        }
    }
}

@Composable
private fun FinishedPanel(
    distanceM: Double,
    elapsedSeconds: Long,
    failedNoMovement: Boolean,
    cutForSpeeding: Boolean,
    onViewResults: () -> Unit,
) {
    // El corte por exceso sí guarda el recorrido y sus puntos: se avisa sin usar el
    // color de error, reservado para lo que no otorga nada.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (failedNoMovement) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    when {
                        failedNoMovement -> R.string.label_route_no_movement
                        cutForSpeeding -> R.string.label_route_cut_speeding
                        else -> R.string.label_route_finished
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (failedNoMovement) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    failedNoMovement -> stringResource(R.string.label_route_no_movement_detail)
                    cutForSpeeding -> stringResource(R.string.label_route_cut_speeding_detail)
                    else -> buildString {
                        append("%.0f m".format(distanceM))
                        append("  ·  ")
                        append(elapsedSeconds.toTimeString())
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (failedNoMovement) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onViewResults, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_view_results))
            }
        }
    }
}

/** Se muestra cuando la sesion no pudo arrancar (ej: ruta nunca vista con conexion). */
@Composable
private fun ErrorPanel(errorKey: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = when (errorKey) {
                "route_unavailable_offline" -> stringResource(R.string.error_route_not_cached)
                "session_not_resumable" -> stringResource(R.string.error_session_not_resumable)
                else -> stringResource(R.string.error_unknown)
            },
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ActivePanel(
    uiState: ActiveRouteUiState,
    onFinish: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (uiState.isFreeRoam) {
                if (uiState.overspeedWarning) {
                    OverspeedBanner()
                    Spacer(Modifier.height(12.dp))
                }
                CurrentActivityRow(uiState.currentActivity)
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                StatItem(label = stringResource(R.string.stat_time), value = uiState.elapsedSeconds.toTimeString())
                StatItem(label = stringResource(R.string.stat_distance), value = "%.0f m".format(uiState.distanceM))
                StatItem(label = stringResource(R.string.stat_speed), value = "%.1f km/h".format(uiState.avgSpeedKmh))
                if (uiState.isFreeRoam) {
                    StatItem(
                        label = stringResource(R.string.stat_estimated_points),
                        value = "${uiState.estimatedPoints}",
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onFinish,
                enabled = uiState.isTracking && !uiState.isFinishing,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isFinishing) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                } else {
                    Text(stringResource(R.string.action_finish_route))
                }
            }
        }
    }
}

/** Actividad que la velocidad reciente sugiere; "Detenido" mientras no hay movimiento. */
@Composable
private fun CurrentActivityRow(activity: ActivityMode?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (activity) {
                ActivityMode.CYCLING -> Icons.Default.DirectionsBike
                ActivityMode.JOGGING -> Icons.Default.DirectionsRun
                else -> Icons.Default.DirectionsWalk
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = activity?.labelEs ?: stringResource(R.string.label_activity_stopped),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun OverspeedBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = stringResource(R.string.label_overspeed_warning),
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge)
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun Long.toTimeString(): String {
    val h = this / 3600
    val m = (this % 3600) / 60
    val s = this % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
