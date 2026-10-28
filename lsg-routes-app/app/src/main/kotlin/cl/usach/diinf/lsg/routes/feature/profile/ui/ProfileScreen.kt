package cl.usach.diinf.lsg.routes.feature.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.core.ui.RefreshableMessage
import cl.usach.diinf.lsg.routes.core.ui.theme.ThemeMode
import cl.usach.diinf.lsg.routes.domain.model.ActivityItem
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.domain.model.ActivityStats
import cl.usach.diinf.lsg.routes.domain.model.DimensionBalance
import cl.usach.diinf.lsg.routes.domain.model.PointTransaction
import cl.usach.diinf.lsg.routes.domain.model.PointsBalance
import cl.usach.diinf.lsg.routes.domain.model.User
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLogout: () -> Unit = {},
    onActivityClick: (String) -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_profile_title)) }) },
    ) { innerPadding ->
        // Sin ON_RESUME a proposito: dispararia cuatro peticiones cada vez que se toca la
        // pestaña. El gesto de tirar hacia abajo deja la recarga en manos del jugador.
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when (val state = uiState) {
                is ProfileUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ProfileUiState.Error -> RefreshableMessage(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                )
                is ProfileUiState.Success -> ProfileContent(
                    user = state.user,
                    stats = state.stats,
                    pointsBalance = state.pointsBalance,
                    transactions = state.transactions,
                    themeMode = themeMode,
                    onThemeChange = viewModel::setThemeMode,
                    onActivityClick = onActivityClick,
                    onLogout = { viewModel.logout(); onLogout() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileContent(
    user: User,
    stats: ActivityStats?,
    pointsBalance: PointsBalance?,
    transactions: List<PointTransaction>,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onActivityClick: (String) -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileHeader(user)

        if (stats != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Straighten,
                    value = formatDistance(stats.totalDistanceM),
                    label = stringResource(R.string.profile_total_distance),
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.EmojiEvents,
                    value = "${stats.totalPoints}",
                    label = stringResource(R.string.profile_total_points),
                )
            }
        }

        if (pointsBalance != null) {
            LsgPointsCard(pointsBalance)
        }

        if (transactions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.profile_transactions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // El ledger solo trae el id de dimension; los nombres vienen del saldo.
            val dimensionNames = pointsBalance?.dimensionNames.orEmpty()
            transactions.forEach { txn -> TransactionRow(txn, dimensionNames) }
        }

        ThemeSelector(themeMode = themeMode, onThemeChange = onThemeChange)

        Text(
            text = stringResource(R.string.profile_recent_activity),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        val recent = stats?.recent.orEmpty()
        if (recent.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_no_activity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            recent.forEach { item -> ActivityRow(item, onClick = { onActivityClick(item.sessionId) }) }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_logout))
        }
    }
}

@Composable
private fun ProfileHeader(user: User) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = user.name.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.size(16.dp))
            Column {
                Text(
                    text = user.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.profile_player_id, user.idPlayers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LsgPointsCard(balance: PointsBalance) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Stars,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.profile_lsg_points_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${balance.total}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (balance.dimensions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                balance.dimensions.forEach { dim -> DimensionRow(dim) }
            }
        }
    }
}

@Composable
private fun DimensionRow(dim: DimensionBalance) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dim.name ?: stringResource(R.string.point_dimension_unknown, dim.idPointDimension),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${dim.balance}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun TransactionRow(txn: PointTransaction, dimensionNames: Map<Int, String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = dimensionNames[txn.pointDimensionId]
                        ?: stringResource(R.string.point_dimension_unknown, txn.pointDimensionId),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                // Saldo inicial -> final, cuando LSG-Core los provee
                if (txn.balanceBefore != null && txn.balanceAfter != null) {
                    Text(
                        text = stringResource(
                            R.string.profile_transaction_balance,
                            txn.balanceBefore,
                            txn.balanceAfter,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val subtitle = listOfNotNull(
                    txn.sourceType?.takeIf { it.isNotBlank() },
                    txn.createdAt?.take(10)?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "+${txn.amount}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.profile_points_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.profile_appearance),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val options = listOf(
            Triple(ThemeMode.SYSTEM, Icons.Default.BrightnessAuto, R.string.theme_system),
            Triple(ThemeMode.LIGHT, Icons.Default.LightMode, R.string.theme_light),
            Triple(ThemeMode.DARK, Icons.Default.DarkMode, R.string.theme_dark),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, icon, labelRes) ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                ) {
                    Text(stringResource(labelRes))
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = modeIcon(item.mode),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.mode.labelEs,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Straighten, null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        formatDistance(item.distanceM),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.activity_speed_short, item.avgSpeedKmh),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = item.createdAt.take(10),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (item.isRejected) {
                    Text(
                        text = stringResource(R.string.activity_rejected),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = "+${item.awardedPoints}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "pts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun modeIcon(mode: ActivityMode): ImageVector = when (mode) {
    ActivityMode.CYCLING -> Icons.Default.DirectionsBike
    ActivityMode.JOGGING -> Icons.Default.DirectionsRun
    ActivityMode.HIKING -> Icons.Default.Terrain
    ActivityMode.WALKING -> Icons.Default.DirectionsWalk
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) "${"%.1f".format(meters / 1000)} km" else "${meters.roundToInt()} m"
