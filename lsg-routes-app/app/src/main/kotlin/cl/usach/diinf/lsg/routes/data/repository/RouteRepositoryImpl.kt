package cl.usach.diinf.lsg.routes.data.repository

import cl.usach.diinf.lsg.routes.data.local.dao.RouteDao
import cl.usach.diinf.lsg.routes.data.local.entity.PlannedRouteEntity
import cl.usach.diinf.lsg.routes.data.remote.dto.CreateRouteRequest
import cl.usach.diinf.lsg.routes.data.remote.dto.PublicRouteDto
import cl.usach.diinf.lsg.routes.data.remote.dto.RouteResponse
import cl.usach.diinf.lsg.routes.data.remote.dto.VisibilityRequest
import cl.usach.diinf.lsg.routes.data.remote.service.RouteService
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.domain.model.PublicRoute
import cl.usach.diinf.lsg.routes.domain.model.Route
import cl.usach.diinf.lsg.routes.domain.model.Weather
import cl.usach.diinf.lsg.routes.domain.repository.RouteRepository
import timber.log.Timber
import javax.inject.Inject

class RouteRepositoryImpl @Inject constructor(
    private val routeService: RouteService,
    private val routeDao: RouteDao,
) : RouteRepository {

    /** Mis rutas se guardan en Room al leerlas: sin conexion, se muestra la ultima
     *  copia vista. No es un cache de expiracion, es el respaldo offline. */
    override suspend fun getRoutes(): Result<List<Route>> =
        runCatching { routeService.getRoutes().map { it.toDomain() } }
            .onSuccess { routes -> routeDao.upsertAll(routes.map { it.toEntity() }) }
            .onFailure { Timber.e(it, "Error al obtener rutas") }
            .recoverCatching {
                routeDao.getAll().map { it.toDomain() }.ifEmpty { throw it }
            }

    override suspend fun getRoute(id: String): Result<Route> =
        runCatching { routeService.getRoute(id).toDomain() }
            .onSuccess { route -> routeDao.upsert(route.toEntity()) }
            .onFailure { Timber.e(it, "Error al obtener ruta $id") }
            .recoverCatching { cause -> routeDao.getById(id)?.toDomain() ?: throw cause }

    override suspend fun getCachedRouteNames(): Map<String, String> =
        routeDao.getAll().associate { it.id to it.name }

    override suspend fun getRouteWeather(id: String): Result<Weather> = runCatching {
        val dto = routeService.getRouteWeather(id)
        Weather(
            temperatureC = dto.temperatureC,
            apparentTemperatureC = dto.apparentTemperatureC,
            humidityPct = dto.humidityPct,
            precipitationMm = dto.precipitationMm,
            weatherCode = dto.weatherCode,
            windSpeedKmh = dto.windSpeedKmh,
            isDay = dto.isDay,
        )
    }.onFailure { Timber.e(it, "Error al obtener clima de ruta $id") }

    override suspend fun createDrawnRoute(
        name: String,
        mode: ActivityMode,
        geometry: List<List<Double>>,
        freehand: Boolean,
    ): Result<Route> = runCatching {
        routeService.createRoute(
            CreateRouteRequest(
                name = name,
                mode = mode.key,
                origin = if (freehand) "FREEHAND" else "DRAWN",
                geometry = geometry,
            )
        ).toDomain()
    }.onFailure { Timber.e(it, "Error al crear ruta ${if (freehand) "freehand" else "dibujada"}") }

    override suspend fun createRoutedRoute(
        name: String,
        mode: ActivityMode,
        startLatLon: List<Double>,
        endLatLon: List<Double>,
    ): Result<Route> = runCatching {
        routeService.createRoute(
            CreateRouteRequest(
                name = name, mode = mode.key, origin = "ROUTED",
                startLatlon = startLatLon, endLatlon = endLatLon,
            )
        ).toDomain()
    }.onFailure { Timber.e(it, "Error al calcular ruta ORS") }

    override suspend fun deleteRoute(id: String): Result<Unit> = runCatching {
        routeService.deleteRoute(id)
    }.onSuccess { routeDao.deleteById(id) }
        .onFailure { Timber.e(it, "Error al eliminar ruta $id") }

    override suspend fun getPublicRoutes(limit: Int, offset: Int): Result<List<PublicRoute>> = runCatching {
        routeService.getPublicRoutes(limit, offset).map { it.toDomain() }
    }.onFailure { Timber.e(it, "Error al obtener rutas publicas") }

    override suspend fun copyRoute(id: String): Result<Route> = runCatching {
        routeService.copyRoute(id).toDomain()
    }.onSuccess { route -> routeDao.upsert(route.toEntity()) }
        .onFailure { Timber.e(it, "Error al copiar ruta $id") }

    override suspend fun setRouteVisibility(
        id: String,
        isPublic: Boolean,
        hideStartM: Int,
        hideEndM: Int,
    ): Result<Route> = runCatching {
        routeService.setVisibility(id, VisibilityRequest(isPublic, hideStartM, hideEndM)).toDomain()
    }.onSuccess { route -> routeDao.upsert(route.toEntity()) }
        .onFailure { Timber.e(it, "Error al cambiar visibilidad de ruta $id") }

    private fun RouteResponse.toDomain() = Route(
        id = id, name = name,
        mode = runCatching { ActivityMode.fromKey(mode) }.getOrDefault(ActivityMode.WALKING),
        origin = origin, geometry = geometry,
        plannedDistanceM = plannedDistanceM, plannedDurationS = plannedDurationS,
        createdAt = createdAt, isPublic = isPublic, sharedByName = sharedByName,
        hideStartM = hideStartM, hideEndM = hideEndM,
    )

    private fun PublicRouteDto.toDomain() = PublicRoute(
        id = id, name = name,
        mode = runCatching { ActivityMode.fromKey(mode) }.getOrDefault(ActivityMode.WALKING),
        geometry = geometry,
        plannedDistanceM = plannedDistanceM, plannedDurationS = plannedDurationS,
        sharedByName = sharedByName, isMine = isMine, createdAt = createdAt,
    )

    // Room no guarda hideStartM/hideEndM (evita una migracion local). Sin red,
    // el detalle no muestra el aviso de recorte; la version compartida la arma el backend.
    private fun Route.toEntity() = PlannedRouteEntity(
        id = id, name = name, mode = mode.key, origin = origin, geometry = geometry,
        plannedDistanceM = plannedDistanceM, plannedDurationS = plannedDurationS,
        createdAt = createdAt, isPublic = isPublic, sharedByName = sharedByName,
        cachedAt = System.currentTimeMillis(),
    )

    private fun PlannedRouteEntity.toDomain() = Route(
        id = id, name = name,
        mode = runCatching { ActivityMode.fromKey(mode) }.getOrDefault(ActivityMode.WALKING),
        origin = origin, geometry = geometry,
        plannedDistanceM = plannedDistanceM, plannedDurationS = plannedDurationS,
        createdAt = createdAt, isPublic = isPublic, sharedByName = sharedByName,
    )
}
