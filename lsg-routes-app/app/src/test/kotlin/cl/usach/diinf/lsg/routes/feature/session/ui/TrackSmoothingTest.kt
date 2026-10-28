package cl.usach.diinf.lsg.routes.feature.session.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** El suavizado es solo para dibujar: no puede mover los extremos, donde se anclan
 *  los marcadores de inicio, fin y el del replay, ni inventar curvas donde el
 *  jugador camino derecho. */
class TrackSmoothingTest {

    // lat/lon tratados como plano cartesiano: a esta escala el resultado es el mismo
    // y las cuentas quedan verificables a mano.
    private fun p(lat: Double, lon: Double) = GeoPoint(lat, lon)

    /** Cinco puntos alineados sobre lat = 0, avanzando en longitud. */
    private val recta = listOf(p(0.0, 0.0), p(0.0, 1.0), p(0.0, 2.0), p(0.0, 3.0), p(0.0, 4.0))

    /** La misma recta con el punto del medio desviado: el jitter tipico del GPS. */
    private val conPico = recta.toMutableList().apply { this[2] = p(PICO, 2.0) }

    @Test
    fun `los extremos se conservan exactamente`() {
        val suave = smoothForDisplay(conPico)
        assertEquals(conPico.first(), suave.first())
        assertEquals(conPico.last(), suave.last())
    }

    @Test
    fun `una recta no se curva al suavizarla`() {
        val suave = smoothForDisplay(recta)
        suave.forEach { assertEquals("punto = $it", 0.0, it.lat, 1e-12) }
        // Cada pasada de Chaikin duplica los vertices y ninguna descarta puntos
        assertEquals(recta.size * 4, suave.size)
    }

    @Test
    fun `un pico aislado se acerca a sus vecinos`() {
        // La media movil reparte el pico entre tres puntos (queda en PICO/3) y Chaikin
        // solo interpola entre vertices existentes, asi que no puede volver a subirlo.
        val desvio = smoothForDisplay(conPico).maxOf { abs(it.lat) }
        assertTrue("desvio = $desvio", desvio < PICO / 2)
        // Suaviza, no aplasta: la traza no debe quedar pegada a la cuerda
        assertTrue("desvio = $desvio", desvio > 0.0)
    }

    @Test
    fun `los tracks degenerados salen sin cambios`() {
        assertEquals(emptyList<GeoPoint>(), smoothForDisplay(emptyList()))

        val uno = listOf(p(0.0, 0.0))
        assertEquals(uno, smoothForDisplay(uno))

        // Dos puntos son un tramo recto: no hay esquina que cortar y Chaikin no
        // puede acortarlo por los extremos.
        val dos = listOf(p(0.0, 0.0), p(0.0, 1.0))
        assertEquals(dos, smoothForDisplay(dos))
    }

    private companion object {
        const val PICO = 0.001
    }
}
