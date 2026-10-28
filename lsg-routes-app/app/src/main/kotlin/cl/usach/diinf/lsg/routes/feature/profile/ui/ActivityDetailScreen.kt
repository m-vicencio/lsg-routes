package cl.usach.diinf.lsg.routes.feature.profile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import cl.usach.diinf.lsg.routes.core.ui.RefreshableMessage
import cl.usach.diinf.lsg.routes.domain.model.ActivityItem
import cl.usach.diinf.lsg.routes.domain.model.SessionTrack
import cl.usach.diinf.lsg.routes.feature.session.ui.RouteReplayCard
import cl.usach.diinf.lsg.routes.feature.session.ui.hasGeometry
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    onBack: () -> Unit = {},
    viewModel: ActivityDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_activity_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when (val state = uiState) {
                is ActivityDetailUiState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ActivityDetailUiState.Error -> RefreshableMessage(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                )
                is ActivityDetailUiState.Success -> DetailContent(state.item, state.track)
            }
        }
    }
}

@Composable
private fun DetailContent(item: ActivityItem?, track: SessionTrack?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (item != null) {
            HeaderCard(item)
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    DetailStat(stringResource(R.string.label_stat_distance), formatDistance(item.distanceM))
                    DetailStat(stringResource(R.string.label_stat_time), item.durationS.toTimeString())
                    DetailStat(stringResource(R.string.label_stat_speed), "%.1f km/h".format(item.avgSpeedKmh))
                }
            }
            PointsCard(item)
        }

        if (track.hasGeometry()) {
            RouteReplayCard(track!!)
        } else if (item == null) {
            Text(
                text = stringResource(R.string.activity_detail_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeaderCard(item: ActivityItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isRejected) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = item.mode.labelEs,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (item.isRejected) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = item.createdAt.take(10),
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.isRejected) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (item.isRejected) stringResource(R.string.status_rejected)
                else stringResource(R.string.status_completed),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (item.isRejected) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PointsCard(item: ActivityItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (item.isRejected) "0" else "+${item.awardedPoints}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (item.isRejected) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.label_lsg_points), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) "${"%.2f".format(meters / 1000)} km" else "${meters.roundToInt()} m"

private fun Int.toTimeString(): String {
    val h = this / 3600
    val m = (this % 3600) / 60
    val s = this % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
