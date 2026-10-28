package cl.usach.diinf.lsg.routes.domain.repository

import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.domain.model.PublicRoute
import cl.usach.diinf.lsg.routes.domain.model.Route
import cl.usach.diinf.lsg.routes.domain.model.Weather

interface RouteRepository {
    suspend fun getRoutes(): Result<List<Route>>
    suspend fun getRoute(id: String): Result<Route>

    /** Nombres de rutas ya vistas con conexion, solo desde el respaldo local (sin
     *  red). Sirve para mostrar el nombre de la ruta de una actividad pendiente de
     *  sincronizar sin depender de que haya conexion en ese momento. */
    suspend fun getCachedRouteNames(): Map<String, String>

    /** Clima actual en el punto de inicio de la ruta. */
    suspend fun getRouteWeather(id: String): Result<Weather>
    suspend fun createDrawnRoute(name: String, mode: ActivityMode, geometry: List<List<Double>>, freehand: Boolean = false): Result<Route>
    suspend fun createRoutedRoute(name: String, mode: ActivityMode, startLatLon: List<Double>, endLatLon: List<Double>): Result<Route>
    suspend fun deleteRoute(id: String): Result<Unit>

    // Compartir rutas
    suspend fun getPublicRoutes(limit: Int = 30, offset: Int = 0): Result<List<PublicRoute>>
    suspend fun copyRoute(id: String): Result<Route>
    /** Al publicar, hideStartM/hideEndM son los metros que se ocultan de cada extremo. */
    suspend fun setRouteVisibility(id: String, isPublic: Boolean, hideStartM: Int = 0, hideEndM: Int = 0): Result<Route>
}
