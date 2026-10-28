package cl.usach.diinf.lsg.routes.feature.onboarding.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.core.permissions.PermissionCatalog
import cl.usach.diinf.lsg.routes.core.permissions.PermissionGroup
import kotlinx.coroutines.launch

/** Paginas del landing: bienvenida, una por permiso que aplique al dispositivo,
 *  y el cierre. La lista se arma en tiempo de ejecucion segun el SDK. */
private sealed interface OnboardingPage {
    data object Welcome : OnboardingPage
    data class Permission(
        val group: PermissionGroup,
        val icon: ImageVector,
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int,
    ) : OnboardingPage

    data object Finish : OnboardingPage
}

private fun buildPages(): List<OnboardingPage> = buildList {
    add(OnboardingPage.Welcome)
    PermissionCatalog.requiredGroups().forEach { group ->
        add(
            when (group) {
                PermissionGroup.LOCATION -> OnboardingPage.Permission(
                    group, Icons.Default.LocationOn,
                    R.string.onboarding_permission_location_title,
                    R.string.onboarding_permission_location_body,
                )
                PermissionGroup.ACTIVITY_RECOGNITION -> OnboardingPage.Permission(
                    group, Icons.Default.DirectionsWalk,
                    R.string.onboarding_permission_activity_title,
                    R.string.onboarding_permission_activity_body,
                )
                PermissionGroup.NOTIFICATIONS -> OnboardingPage.Permission(
                    group, Icons.Default.Notifications,
                    R.string.onboarding_permission_notifications_title,
                    R.string.onboarding_permission_notifications_body,
                )
            }
        )
    }
    add(OnboardingPage.Finish)
}

/** Landing que se muestra al arrancar cuando falta algun permiso: explica la app,
 *  pide cada permiso en su propia pagina y solo deja avanzar al otorgarlo. */
@Composable
fun PermissionsOnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val pages = remember { buildPages() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    // Estado real de cada grupo. Se refresca en ON_RESUME para cubrir el regreso
    // desde los ajustes de Android (donde el usuario pudo otorgar a mano).
    val granted = remember {
        mutableStateMapOf<PermissionGroup, Boolean>().apply {
            PermissionCatalog.requiredGroups().forEach {
                put(it, PermissionCatalog.isGroupGranted(context, it))
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted.keys.forEach { granted[it] = PermissionCatalog.isGroupGranted(context, it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val advance: () -> Unit = {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
        ) {
            // El avance es solo por boton: el swipe permitiria saltarse un permiso
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier.weight(1f),
            ) { index ->
                when (val page = pages[index]) {
                    is OnboardingPage.Welcome -> WelcomePage(onContinue = advance)
                    is OnboardingPage.Permission -> PermissionPage(
                        page = page,
                        isGranted = granted[page.group] == true,
                        onGranted = { granted[page.group] = true },
                        onContinue = advance,
                    )
                    is OnboardingPage.Finish -> FinishPage(onFinished = onFinished)
                }
            }
            Spacer(Modifier.height(16.dp))
            PageDots(total = pages.size, current = pagerState.currentPage)
        }
    }
}

@Composable
private fun PageDots(total: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (index == current) 10.dp else 8.dp)
                    .background(
                        color = if (index == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun WelcomePage(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FeatureRow(Icons.Default.DirectionsWalk, R.string.onboarding_feature_gps)
        FeatureRow(Icons.Default.Stars, R.string.onboarding_feature_points)
        FeatureRow(Icons.Default.VerifiedUser, R.string.onboarding_feature_fair)
        FeatureRow(Icons.Default.Explore, R.string.onboarding_feature_community)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, @StringRes textRes: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PermissionPage(
    page: OnboardingPage.Permission,
    isGranted: Boolean,
    onGranted: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var deniedOnce by remember { mutableStateOf(false) }

    // Denegacion permanente: Android deja de mostrar el popup cuando el usuario
    // denego dos veces. rationale=false tambien ocurre ANTES del primer pedido,
    // por eso se exige haber recibido al menos un denied.
    val permanentlyDenied = deniedOnce && !isGranted &&
        activity?.shouldShowRequestPermissionRationale(page.group.gatePermission) == false

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[page.group.gatePermission] == true) onGranted() else deniedOnce = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = page.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (isGranted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_permission_granted),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_continue))
            }
        } else if (permanentlyDenied) {
            Text(
                text = stringResource(R.string.onboarding_permission_denied_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_open_settings))
            }
        } else {
            Button(
                onClick = { launcher.launch(page.group.permissions.toTypedArray()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_grant_permission))
            }
        }
    }
}

@Composable
private fun FinishPage(onFinished: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_finish_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_finish_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_start_moving))
        }
    }
}

/** LocalActivity recien existe en activity-compose 1.10; con 1.9 se desenrolla
 *  el ContextWrapper a mano. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
