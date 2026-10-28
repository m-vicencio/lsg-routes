package cl.usach.diinf.lsg.routes.domain.model

import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Bandas de velocidad del recorrido libre: deben coincidir con app/services/free_roam.py. */
class SpeedBandsTest {

    // Metros por grado de latitud con el mismo radio que usa haversineM: moviendose
    // solo en latitud, la distancia del tramo es exactamente R * dphi.
    private val metersPerDegree = 6_371_000.0 * Math.PI / 180.0

    /** Agrega un tramo a velocidad constante ([mps] m/s durante [seconds]) al final del track. */
    private fun leg(
        fixes: MutableList<LocationFixEntity>,
        mps: Double,
        seconds: Int,
        stepS: Int = 10,
    ): MutableList<LocationFixEntity> {
        var lat = fixes.lastOrNull()?.lat ?: -33.45
        var t = fixes.lastOrNull()?.capturedAt ?: 0L
        repeat(seconds / stepS) {
            lat += (mps * stepS) / metersPerDegree
            t += stepS * 1000L
            fixes += fix(lat, t)
        }
        return fixes
    }

    private fun fix(lat: Double, capturedAt: Long) = LocationFixEntity(
        sessionId = "s", lat = lat, lon = -70.65, accuracyM = null, altitudeM = null,
        speedMps = null, bearingDeg = null, isMock = false,
        capturedAt = capturedAt, clientSeq = 0,
    )

    private fun start() = mutableListOf(fix(-33.45, 0L))

    @Test
    fun `classify respeta las fronteras de cada banda`() {
        assertNull(SpeedBands.classify(1.4, BIKE_ON))                            // detenido
        assertEquals(ActivityMode.WALKING, SpeedBands.classify(1.6, BIKE_ON))
        assertEquals(ActivityMode.WALKING, SpeedBands.classify(6.9, BIKE_ON))
        assertEquals(ActivityMode.JOGGING, SpeedBands.classify(7.1, BIKE_ON))
        assertEquals(ActivityMode.JOGGING, SpeedBands.classify(14.9, BIKE_ON))
        assertEquals(ActivityMode.CYCLING, SpeedBands.classify(15.1, BIKE_ON))
        assertEquals(ActivityMode.CYCLING, SpeedBands.classify(34.9, BIKE_ON))
        assertNull(SpeedBands.classify(35.1, BIKE_ON))                           // exceso
    }

    @Test
    fun `sin la bandera de bicicleta esa banda pasa a ser exceso`() {
        assertEquals(ActivityMode.JOGGING, SpeedBands.classify(14.9, BIKE_OFF))
        assertNull(SpeedBands.classify(15.1, BIKE_OFF))
        assertNull(SpeedBands.classify(34.9, BIKE_OFF))
        assertEquals(15.0, SpeedBands.excessKmh(BIKE_OFF), 0.001)
        assertEquals(35.0, SpeedBands.excessKmh(BIKE_ON), 0.001)
    }

    @Test
    fun `sin la bandera de bicicleta esos tramos no suman puntos`() {
        // La misma sesion mixta de mas abajo: solo quedan los 900 m caminando
        val fixes = leg(leg(start(), mps = 1.5, seconds = 600), mps = 6.0, seconds = 600)
        assertEquals(3, SpeedBands.estimatePoints(fixes, BIKE_OFF))
    }

    @Test
    fun `caminar rinde la mitad del factor de caminata`() {
        // 1.5 m/s durante 600 s = 900 m -> 0.9 km x 8 pts x 0.5 = 3.6 -> 3
        assertEquals(3, SpeedBands.estimatePoints(leg(start(), mps = 1.5, seconds = 600), BIKE_ON))
    }

    @Test
    fun `sesion mixta reparte la distancia por actividad`() {
        // 900 m caminando (3.6) + 3600 m en bici (7.2) = 10.8 -> 10
        val fixes = leg(leg(start(), mps = 1.5, seconds = 600), mps = 6.0, seconds = 600)
        assertEquals(10, SpeedBands.estimatePoints(fixes, BIKE_ON))
    }

    @Test
    fun `los tramos en exceso no suman puntos`() {
        // Mismo tramo caminando, pero seguido de 900 m a 54 km/h
        val fixes = leg(leg(start(), mps = 1.5, seconds = 600), mps = 15.0, seconds = 60)
        assertEquals(3, SpeedBands.estimatePoints(fixes, BIKE_ON))
    }

    @Test
    fun `estar detenido no suma puntos`() {
        val quieto = (0..9).map { fix(-33.45, it * 10_000L) }
        assertEquals(0, SpeedBands.estimatePoints(quieto, BIKE_ON))
        assertEquals(0.0, SpeedBands.rollingSpeedKmh(quieto), 0.001)
    }

    @Test
    fun `rollingSpeedKmh solo mira la ventana reciente`() {
        // 10 min caminando lento y luego 30 s a 6 m/s: la ventana de 15 s ve la bici
        val fixes = leg(leg(start(), mps = 1.5, seconds = 600), mps = 6.0, seconds = 30, stepS = 5)
        assertEquals(21.6, SpeedBands.rollingSpeedKmh(fixes), 0.5)
        assertEquals(
            ActivityMode.CYCLING,
            SpeedBands.classify(SpeedBands.rollingSpeedKmh(fixes), BIKE_ON),
        )
    }

    private companion object {
        const val BIKE_ON = true
        const val BIKE_OFF = false
    }
}
