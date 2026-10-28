package cl.usach.diinf.lsg.routes.domain.model

import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Limpieza del track: debe coincidir con app/validators/common.py del backend. */
class GpsTrackTest {

    // Metros por grado de latitud con el mismo radio que usa haversineM: moviendose
    // solo en latitud, la distancia del tramo es exactamente R * dphi.
    private val metersPerDegree = 6_371_000.0 * Math.PI / 180.0

    private fun fix(lat: Double, capturedAt: Long, accuracyM: Float? = 10f) = LocationFixEntity(
        sessionId = "s", lat = lat, lon = -70.65, accuracyM = accuracyM, altitudeM = null,
        speedMps = null, bearingDeg = null, isMock = false,
        capturedAt = capturedAt, clientSeq = 0,
    )

    /** Track de [seconds] fixes a 1 Hz avanzando [mps], con un jitter alternado de [jitterM]. */
    private fun track(mps: Double, seconds: Int, jitterM: Double = 0.0, accuracyM: Float? = 10f) =
        (0 until seconds).map { i ->
            val jitter = if (i % 2 == 0) jitterM else -jitterM
            fix(-33.45 + (i * mps + jitter) / metersPerDegree, i * 1_000L, accuracyM)
        }

    @Test
    fun `quieto con jitter no acumula distancia`() {
        // 60 fixes rebotando +-5 m: el piso de ruido (2 x 10 m) se los come enteros
        assertEquals(0.0, GpsTrack.totalDistanceM(track(mps = 0.0, seconds = 60, jitterM = 5.0)), 0.001)
    }

    @Test
    fun `caminar a 1 Hz no se borra pese a avanzar menos que la precision por fix`() {
        // 1.4 m/s durante 120 s = 168 m reales. El ancla acumula hasta superar el piso,
        // asi que solo se pierde el ultimo tramo incompleto (< 20 m).
        val distancia = GpsTrack.totalDistanceM(track(mps = 1.4, seconds = 120))
        assertTrue("distancia = $distancia", distancia > 168.0 * 0.85 && distancia <= 168.0)
    }

    @Test
    fun `un fix que salta y vuelve se descarta como outlier`() {
        val fixes = listOf(
            fix(-33.4500, 0L),
            fix(-33.4470, 1_000L),   // ~333 m en 1 s = 1200 km/h, y vuelve
            fix(-33.4500, 2_000L),
        )
        val limpio = GpsTrack.filterOutliers(fixes)
        assertEquals(2, limpio.size)
        assertEquals(fixes.first(), limpio.first())
        assertEquals(fixes.last(), limpio.last())
    }

    @Test
    fun `una teleportacion sostenida no se filtra, la sigue viendo el anti-cheat`() {
        val fixes = listOf(
            fix(-33.4500, 0L),
            fix(-33.4470, 1_000L),
            fix(-33.4469, 2_000L),   // se queda alli: no es el patron de ida y vuelta
        )
        assertEquals(3, GpsTrack.filterOutliers(fixes).size)
    }

    @Test
    fun `la precision insuficiente descarta el fix, la ausente no`() {
        assertFalse(GpsTrack.isUsable(60f))
        assertTrue(GpsTrack.isUsable(20f))
        assertTrue(GpsTrack.isUsable(null))
        val fixes = listOf(fix(-33.45, 0L, 10f), fix(-33.45, 1_000L, 200f), fix(-33.45, 2_000L, null))
        assertEquals(2, GpsTrack.dropImprecise(fixes).size)
    }

    @Test
    fun `las anclas dejan el track sin jitter para dibujar`() {
        // Quieto: no hay tramo significativo, queda un solo punto en vez de 60
        assertEquals(1, GpsTrack.anchors(track(mps = 0.0, seconds = 60, jitterM = 5.0)).size)
    }

    @Test
    fun `estar quieto con jitter no rinde puntos`() {
        // A 1 Hz un jitter de 5 m implica 18 km/h: sin el piso de ruido se cobraria
        // como bicicleta con el jugador detenido.
        val quieto = track(mps = 0.0, seconds = 60, jitterM = 5.0)
        assertEquals(0, SpeedBands.estimatePoints(quieto, bikeEnabled = true))
        assertEquals(0.0, SpeedBands.rollingSpeedKmh(quieto), 0.001)
    }
}
