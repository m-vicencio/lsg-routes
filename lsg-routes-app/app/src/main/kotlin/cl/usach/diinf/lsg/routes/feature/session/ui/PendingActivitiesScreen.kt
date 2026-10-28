package cl.usach.diinf.lsg.routes.feature.session.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cl.usach.diinf.lsg.routes.data.local.entity.SessionSyncStatus
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Actividades grabadas en el dispositivo que todavia no se sincronizaron con el
 *  backend: sirve para verlas sin conexion y, si quedaron con un intento fallido,
 *  reintentar sin esperar el backoff automatico. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingActivitiesScreen(
    onBack: () -> Unit = {},
    onResumeSession: (routeId: String, idPlayers: Int, sessionId: String) -> Unit = { _, _, _ -> },
    viewModel: PendingActivitiesViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_pending_activities_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.pending_activities_empty),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                LazyColumn {
                    items(items, key = { it.sessionId }) { item ->
                        PendingActivityRow(
                            item = item,
                            onRetry = { viewModel.retry(item) },
                            onResume = { onResumeSession(item.routeId, item.idPlayers, item.sessionId) },
                            onFinish = { viewModel.finishStuck(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingActivityRow(
    item: PendingActivityItem,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (item.mode) {
                        ActivityMode.CYCLING -> Icons.Default.DirectionsBike
                        ActivityMode.JOGGING -> Icons.Default.DirectionsRun
                        ActivityMode.HIKING -> Icons.Default.Terrain
                        ActivityMode.WALKING -> Icons.Default.DirectionsWalk
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.routeName ?: stringResource(R.string.label_free_roam),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = statusLabel(item.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = dateFormatter.format(Date(item.startedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.status == SessionSyncStatus.UPLOAD_FAILED) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.action_retry_sync))
                    }
                }
            }
            // Una grabacion que quedo abierta es la unica fila accionable: desde aqui se
            // vuelve al recorrido o se cierra; si no, quedaria en la lista para siempre.
            if (item.isStuckRecording) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.action_finish_stuck))
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.action_resume_route))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: String): String = stringResource(
    when (status) {
        SessionSyncStatus.RECORDING -> R.string.pending_status_recording
        SessionSyncStatus.UPLOADING -> R.string.pending_status_uploading
        SessionSyncStatus.UPLOAD_FAILED -> R.string.pending_status_upload_failed
        else -> R.string.pending_status_completed_pending_upload
    }
)

private val dateFormatter = SimpleDateFormat("dd/MM HH:mm", Locale("es", "CL"))
