package cl.usach.diinf.lsg.routes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import cl.usach.diinf.lsg.routes.core.auth.TokenRefresher
import cl.usach.diinf.lsg.routes.core.ui.theme.LsgRoutesTheme
import cl.usach.diinf.lsg.routes.core.ui.theme.ThemePreferences
import cl.usach.diinf.lsg.routes.feature.update.AppUpdateChecker
import cl.usach.diinf.lsg.routes.feature.update.AppUpdateDialog
import cl.usach.diinf.lsg.routes.feature.session.service.TrackingService
import cl.usach.diinf.lsg.routes.feature.update.UpdateInfo
import cl.usach.diinf.lsg.routes.navigation.AppNavigation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Actividad principal de LSG Routes. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appUpdateChecker: AppUpdateChecker
    @Inject lateinit var themePreferences: ThemePreferences
    @Inject lateinit var tokenRefresher: TokenRefresher

    /** Destino al que saltar cuando se entra tocando la notificacion de seguimiento. */
    private var deepLinkRoute by mutableStateOf<String?>(null)

    /** launchMode=singleTop: con la app ya abierta la notificacion entra por aqui y no
     *  por onCreate, asi que sin esto el extra se perderia justo en el caso mas comun
     *  (el servicio sigue grabando y solo se perdio la ventana). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkRoute = intent.getStringExtra(TrackingService.EXTRA_NAV_ROUTE)
    }

    /** Renueva el token al volver a primer plano: si la app estuvo horas cerrada, la
     *  primera peticion moriria con un 401 que ya no se puede refrescar. */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { tokenRefresher.refreshIfExpiringSoon() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkRoute = intent.getStringExtra(TrackingService.EXTRA_NAV_ROUTE)
        setContent {
            val themeMode by themePreferences.themeMode.collectAsStateWithLifecycle()
            LsgRoutesTheme(themeMode = themeMode) {
                var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

                LaunchedEffect(Unit) {
                    updateInfo = appUpdateChecker.checkForUpdate()
                }

                if (updateInfo != null) {
                    AppUpdateDialog(
                        updateInfo = updateInfo!!,
                        onDismiss = { updateInfo = null },
                    )
                }

                AppNavigation(
                    deepLinkRoute = deepLinkRoute,
                    onDeepLinkHandled = { deepLinkRoute = null },
                )
            }
        }
    }
}
