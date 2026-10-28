package cl.usach.diinf.lsg.routes.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cl.usach.diinf.lsg.routes.feature.auth.ui.LoginScreen
import cl.usach.diinf.lsg.routes.feature.explore.ui.ExploreScreen
import cl.usach.diinf.lsg.routes.feature.onboarding.ui.PermissionsOnboardingScreen
import cl.usach.diinf.lsg.routes.feature.explore.ui.RoutePreviewScreen
import cl.usach.diinf.lsg.routes.feature.profile.ui.ActivityDetailScreen
import cl.usach.diinf.lsg.routes.feature.profile.ui.ProfileScreen
import cl.usach.diinf.lsg.routes.feature.routes.ui.NewRouteScreen
import cl.usach.diinf.lsg.routes.feature.routes.ui.RouteDetailScreen
import cl.usach.diinf.lsg.routes.feature.routes.ui.RoutesListScreen
import cl.usach.diinf.lsg.routes.feature.session.ui.ActiveRouteScreen
import cl.usach.diinf.lsg.routes.feature.session.ui.PendingActivitiesScreen
import cl.usach.diinf.lsg.routes.feature.session.ui.SessionSummaryScreen

@Composable
fun AppNavigation(
    deepLinkRoute: String? = null,
    onDeepLinkHandled: () -> Unit = {},
    viewModel: AppNavigationViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Vuelta al recorrido en curso desde la notificacion de seguimiento. launchSingleTop
    // evita apilar la pantalla si ya se estaba en ella.
    LaunchedEffect(deepLinkRoute) {
        deepLinkRoute?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
            onDeepLinkHandled()
        }
    }

    val noBottomBarRoutes = setOf(
        Screen.PermissionsOnboarding.route,
        Screen.Login.route, Screen.NewRoute.route, Screen.PendingActivities.route,
        Screen.RouteDetail.route, Screen.ActiveRoute.route, Screen.SessionSummary.route,
        Screen.RoutePreview.route, Screen.ActivityDetail.route, Screen.FreeRoam.route,
        // Profile y Explore mantienen la barra inferior visible
    )
    val showBottomBar = noBottomBarRoutes.none { currentRoute?.startsWith(it.substringBefore("{")) == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    items = BottomNavItem.items,
                    currentDestination = navBackStackEntry?.destination,
                    onNavigate = { item ->
                        navController.navigate(item.screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = viewModel.startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.PermissionsOnboarding.route) {
                PermissionsOnboardingScreen(
                    onFinished = {
                        navController.navigate(viewModel.mainDestination) {
                            popUpTo(Screen.PermissionsOnboarding.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Login.route) {
                LoginScreen(onLoginSuccess = {
                    navController.navigate(Screen.RoutesList.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                })
            }
            composable(Screen.Profile.route) {
                ProfileScreen(
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onActivityClick = { sessionId ->
                        navController.navigate(Screen.ActivityDetail.createRoute(sessionId))
                    },
                )
            }
            composable(Screen.RoutesList.route) {
                RoutesListScreen(
                    onNewRoute = { navController.navigate(Screen.NewRoute.route) },
                    onRouteClick = { id -> navController.navigate(Screen.RouteDetail.createRoute(id)) },
                    // idPlayers=0 es un marcador, igual que en ActiveRoute: el backend usa el JWT
                    onFreeRoam = { navController.navigate(Screen.FreeRoam.createRoute(0)) },
                    onPendingActivities = { navController.navigate(Screen.PendingActivities.route) },
                )
            }
            composable(Screen.PendingActivities.route) {
                PendingActivitiesScreen(
                    onBack = { navController.popBackStack() },
                    // Reanudar una grabacion que quedo abierta: la misma pantalla de
                    // recorrido, pero adoptando la sesion en vez de empezar otra.
                    onResumeSession = { routeId, idPlayers, sessionId ->
                        navController.navigate(
                            if (routeId.isBlank()) Screen.FreeRoam.createRoute(idPlayers, sessionId)
                            else Screen.ActiveRoute.createRoute(routeId, idPlayers, sessionId)
                        )
                    },
                )
            }
            composable(Screen.Explore.route) {
                ExploreScreen(
                    onRouteClick = { id, isMine ->
                        navController.navigate(Screen.RoutePreview.createRoute(id, isMine))
                    },
                )
            }
            composable(
                route = Screen.RoutePreview.route,
                arguments = listOf(
                    navArgument("routeId") { type = NavType.StringType },
                    navArgument("isMine") { type = NavType.BoolType },
                ),
            ) {
                RoutePreviewScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        // Tras guardar la copia, ir a Mis Rutas (se recarga en ON_RESUME)
                        navController.navigate(Screen.RoutesList.route) {
                            popUpTo(Screen.Explore.route)
                        }
                    },
                )
            }
            composable(Screen.NewRoute.route) {
                NewRouteScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
            }
            composable(
                route = Screen.RouteDetail.route,
                arguments = listOf(navArgument("routeId") { type = NavType.StringType }),
            ) {
                RouteDetailScreen(
                    onBack = { navController.popBackStack() },
                    onStartRoute = { routeId ->
                        // idPlayers=0 es un marcador: el backend identifica al jugador por el JWT
                        navController.navigate(Screen.ActiveRoute.createRoute(routeId, 0))
                    },
                )
            }
            composable(
                route = Screen.ActiveRoute.route,
                arguments = listOf(
                    navArgument("routeId") { type = NavType.StringType },
                    navArgument("idPlayers") { type = NavType.IntType },
                    // Solo viene al reanudar una grabacion que quedo abierta
                    navArgument("sessionId") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                ),
            ) { backStack ->
                val idPlayers = backStack.arguments?.getInt("idPlayers") ?: 0
                ActiveRouteScreen(
                    idPlayers = idPlayers,
                    onFinished = { sessionId, distanceMInt, elapsedSecs ->
                        // "routes_list" no tiene parametros: coincide por texto exacto y siempre se
                        // encuentra. No usar aqui una plantilla como "route_detail/{routeId}":
                        // Navigation 2.8 puede no resolverla y vaciar en silencio toda la pila.
                        navController.navigate(
                            Screen.SessionSummary.createRoute(sessionId, distanceMInt, elapsedSecs)
                        ) {
                            popUpTo(Screen.RoutesList.route)
                        }
                    },
                )
            }
            composable(
                route = Screen.FreeRoam.route,
                arguments = listOf(
                    navArgument("idPlayers") { type = NavType.IntType },
                    navArgument("sessionId") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                ),
            ) { backStack ->
                // Sin argumento routeId: el ViewModel lo lee nulo y arranca en modo libre
                ActiveRouteScreen(
                    idPlayers = backStack.arguments?.getInt("idPlayers") ?: 0,
                    onFinished = { sessionId, distanceMInt, elapsedSecs ->
                        navController.navigate(
                            Screen.SessionSummary.createRoute(sessionId, distanceMInt, elapsedSecs)
                        ) {
                            popUpTo(Screen.RoutesList.route)
                        }
                    },
                )
            }
            composable(
                route = Screen.SessionSummary.route,
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("distanceMInt") { type = NavType.IntType },
                    navArgument("elapsedSecs") { type = NavType.IntType },
                ),
            ) {
                SessionSummaryScreen(
                    onBack = {
                        // Vacia todo y navega a RoutesList: la pila queda siempre en [RoutesList],
                        // sin importar como se comporto popUpTo al entrar.
                        navController.navigate(Screen.RoutesList.route) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = Screen.ActivityDetail.route,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) {
                ActivityDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
