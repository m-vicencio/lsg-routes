package cl.usach.diinf.lsg.routes.domain.model

import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity

/**
 * Clasificacion de actividad por velocidad para el recorrido libre.
 *
 * Espejo de `app/services/free_roam.py` en el backend, que es la fuente autoritativa
 * de los puntos finalmente acreditados; esto solo alimenta el estimado en pantalla y
 * el corte por exceso de velocidad, igual que [ActivityMode.pointsPerKm] ya duplica
 * los factores por km.
 */
object SpeedBands {

    const val STOPPED_KMH = 1.5
    const val CYCLING_MAX_KMH = 35.0
    const val FREE_ROAM_MULTIPLIER = 0.5

    /** Techo de cada banda en km/h; la actividad aplica desde el techo anterior hasta el propio. */
    private val BANDS = listOf(
        7.0 to ActivityMode.WALKING,
        15.0 to ActivityMode.JOGGING,
        CYCLING_MAX_KMH to ActivityMode.CYCLING,
    )

    /** Bandas vigentes. Sin la bandera de bicicleta, esa actividad no existe. */
    private fun bands(bikeEnabled: Boolean) =
        if (bikeEnabled) BANDS else BANDS.filter { it.second != ActivityMode.CYCLING }

    /** Velocidad desde la cual un tramo se descarta por exceso (techo de la ultima banda). */
    fun excessKmh(bikeEnabled: Boolean): Double = bands(bikeEnabled).last().first

    /** Actividad implicada por una velocidad. null si esta detenido o en exceso. */
    fun classify(speedKmh: Double, bikeEnabled: Boolean): ActivityMode? {
        if (speedKmh < STOPPED_KMH) return null
        return bands(bikeEnabled).firstOrNull { speedKmh < it.first }?.second
    }

    /** Velocidad media de los ultimos [windowMs] milisegundos de track, en km/h. */
    fun rollingSpeedKmh(fixes: List<LocationFixEntity>, windowMs: Long = 15_000L): Double {
        if (fixes.size < 2) return 0.0
        val cutoff = fixes.last().capturedAt - windowMs
        val window = fixes.filter { it.capturedAt >= cutoff }
        if (window.size < 2) return 0.0
        val elapsedS = (window.last().capturedAt - window.first().capturedAt) / 1000.0
        if (elapsedS <= 0) return 0.0
        return (GpsTrack.totalDistanceM(window) / elapsedS) * 3.6
    }

    /**
     * Puntos estimados del recorrido libre: cada tramo se clasifica por su propia
     * velocidad y suma km x factor del modo x multiplicador. Los tramos detenidos o
     * en exceso no suman.
     *
     * Recalcula sobre la lista completa en cada emision, una vez por segundo; en
     * sesiones muy largas convendria acumular de forma incremental.
     */
    fun estimatePoints(fixes: List<LocationFixEntity>, bikeEnabled: Boolean): Int {
        val metersByMode = mutableMapOf<ActivityMode, Double>()
        // Solo los tramos que se distinguen del ruido del GPS: un jitter de pocos metros
        // a 1 Hz implica ~20 km/h y se cobraria como bicicleta estando el jugador quieto.
        for (leg in GpsTrack.significantLegs(fixes)) {
            val dtS = (leg.to.capturedAt - leg.from.capturedAt) / 1000.0
            if (dtS <= 0) continue
            val mode = classify((leg.distanceM / dtS) * 3.6, bikeEnabled) ?: continue
            metersByMode[mode] = (metersByMode[mode] ?: 0.0) + leg.distanceM
        }
        return metersByMode.entries
            .sumOf { (mode, meters) -> (meters / 1000.0) * mode.pointsPerKm * FREE_ROAM_MULTIPLIER }
            .toInt()
    }
}
