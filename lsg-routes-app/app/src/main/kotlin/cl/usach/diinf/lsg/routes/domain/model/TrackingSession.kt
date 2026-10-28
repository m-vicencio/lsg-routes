package cl.usach.diinf.lsg.routes.domain.model

/** Resumen de sesion retornado por el backend al finalizar. */
data class SessionSummary(
    val sessionId: String,
    val status: String,
    val mode: String,
    val awardedPoints: Int?,
    val adherenceRatio: Float?,
    val validators: Map<String, String>,
    val syncStatus: String?,
)

/** Punto GPS local. */
data class LocalFix(
    val lat: Double,
    val lon: Double,
    val capturedAt: Long,
)

/** Geometria del recorrido de una sesion: ruta planificada + track GPS real.
 *  Cada punto es un par [lat, lon]. */
data class SessionTrack(
    val plannedGeometry: List<List<Double>>,
    val fixes: List<List<Double>>,
)
