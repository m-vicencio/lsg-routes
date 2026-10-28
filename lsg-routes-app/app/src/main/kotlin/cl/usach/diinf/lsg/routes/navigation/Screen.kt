package cl.usach.diinf.lsg.routes.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import cl.usach.diinf.lsg.routes.R

sealed class Screen(val route: String) {
    data object PermissionsOnboarding : Screen("permissions_onboarding")
    data object Login : Screen("login")
    data object RoutesList : Screen("routes_list")
    data object NewRoute : Screen("new_route")
    data object PendingActivities : Screen("pending_activities")
    data object Profile : Screen("profile")
    data object Explore : Screen("explore")
    data object RoutePreview : Screen("route_preview/{routeId}/{isMine}") {
        fun createRoute(id: String, isMine: Boolean) = "route_preview/$id/$isMine"
    }
    data object RouteDetail : Screen("route_detail/{routeId}") {
        fun createRoute(id: String) = "route_detail/$id"
    }
    /** sessionId opcional: cuando viene, la pantalla adopta esa sesion local en vez de
     *  empezar una nueva. Lo usan la notificacion de seguimiento y el boton Reanudar de
     *  actividades pendientes, para volver a una grabacion que quedo abierta. */
    data object ActiveRoute : Screen("active_route/{routeId}/{idPlayers}?sessionId={sessionId}") {
        fun createRoute(routeId: String, idPlayers: Int, sessionId: String? = null) =
            "active_route/$routeId/$idPlayers".withSessionId(sessionId)
    }
    /** Recorrido libre: misma pantalla que ActiveRoute pero sin routeId. */
    data object FreeRoam : Screen("free_roam/{idPlayers}?sessionId={sessionId}") {
        fun createRoute(idPlayers: Int, sessionId: String? = null) =
            "free_roam/$idPlayers".withSessionId(sessionId)
    }
    data object SessionSummary : Screen("session_summary/{sessionId}/{distanceMInt}/{elapsedSecs}") {
        fun createRoute(sessionId: String, distanceMInt: Int, elapsedSecs: Int) =
            "session_summary/$sessionId/$distanceMInt/$elapsedSecs"
    }
    data object ActivityDetail : Screen("activity_detail/{sessionId}") {
        fun createRoute(sessionId: String) = "activity_detail/$sessionId"
    }
}

/** Agrega el argumento opcional solo si hay sesion que reanudar: pasarlo vacio haria
 *  que Navigation lo tomara como un id valido y la pantalla buscaria una sesion "". */
private fun String.withSessionId(sessionId: String?): String =
    if (sessionId.isNullOrBlank()) this else "$this?sessionId=$sessionId"

sealed class BottomNavItem(val screen: Screen, val labelRes: Int, val icon: ImageVector) {
    data object RoutesList : BottomNavItem(Screen.RoutesList, R.string.nav_routes, Icons.Default.Home)
    data object Explore : BottomNavItem(Screen.Explore, R.string.nav_explore, Icons.Default.Explore)
    data object Profile : BottomNavItem(Screen.Profile, R.string.nav_profile, Icons.Default.Person)

    companion object {
        val items: List<BottomNavItem> = listOf(RoutesList, Explore, Profile)
    }
}
