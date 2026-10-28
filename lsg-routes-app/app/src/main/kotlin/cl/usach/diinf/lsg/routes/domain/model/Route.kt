package cl.usach.diinf.lsg.routes.domain.model

/** Modelo de dominio de una ruta planificada. */
data class Route(
    val id: String,
    val name: String,
    val mode: ActivityMode,
    val origin: String,
    val geometry: List<List<Double>>,
    val plannedDistanceM: Double?,
    val plannedDurationS: Int?,
    val createdAt: String,
    val isPublic: Boolean = false,
    val sharedByName: String? = null,
    /** Metros ocultos al inicio y al final en la version compartida (0 = nada). */
    val hideStartM: Int = 0,
    val hideEndM: Int = 0,
)

/** Clima actual en el punto de inicio de una ruta. weatherCode es codigo WMO. */
data class Weather(
    val temperatureC: Double?,
    val apparentTemperatureC: Double?,
    val humidityPct: Int?,
    val precipitationMm: Double?,
    val weatherCode: Int?,
    val windSpeedKmh: Double?,
    val isDay: Boolean,
)

/** Ruta publica del feed comunitario (Explorar). */
data class PublicRoute(
    val id: String,
    val name: String,
    val mode: ActivityMode,
    val geometry: List<List<Double>>,
    val plannedDistanceM: Double?,
    val plannedDurationS: Int?,
    val sharedByName: String?,
    val isMine: Boolean = false,
    val createdAt: String,
)
