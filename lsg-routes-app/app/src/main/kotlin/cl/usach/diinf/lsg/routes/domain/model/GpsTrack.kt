package cl.usach.diinf.lsg.routes.domain.model

import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Limpieza del track GPS: precision insuficiente, jitter y outliers de multipath.
 *
 * Espejo de `app/validators/common.py` en el backend, que es la fuente autoritativa:
 * los puntos finalmente acreditados salen de alli. Lo de aqui solo alimenta lo que se
 * ve en pantalla durante la ruta, y existe para que ambos numeros coincidan. Si cambia
 * una constante o un criterio en el backend, tiene que cambiar aqui.
 */
object GpsTrack {

    /** Precision (radio, m) sobre la cual un fix no sirve ni para estimar. */
    const val MAX_ACCURACY_M = 50.0f

    private const val NOISE_FLOOR_FACTOR = 2.0
    private const val MAX_NOISE_FLOOR_M = 30.0
    private const val STOP_WINDOW_S = 60.0

    // Techo de la banda mas alta admitida (bicicleta en bajada). Aqui solo interesa matar
    // teleportaciones; el techo estricto por modo lo aplica el backend al validar.
    private const val MAX_SPIKE_MPS = 70.0 / 3.6

    /** Tramo del track cuyo desplazamiento se distingue del ruido del GPS. */
    data class Leg(val from: LocationFixEntity, val to: LocationFixEntity, val distanceM: Double)

    /**
     * Un fix sin precision declarada pasa: son fixes anteriores a que se propagara el
     * campo, y sin dato no se filtra nada.
     */
    fun isUsable(accuracyM: Float?): Boolean = accuracyM == null || accuracyM <= MAX_ACCURACY_M

    /**
     * Descarta los fixes demasiado imprecisos para medir nada.
     *
     * No lo cubre el piso de ruido: su tope de [MAX_NOISE_FLOOR_M] hace que un fix con
     * 200 m de precision se filtre como si estuviera errado por 15 m, y entra entero.
     */
    fun dropImprecise(fixes: List<LocationFixEntity>): List<LocationFixEntity> =
        fixes.filter { isUsable(it.accuracyM) }

    /**
     * Elimina outliers de un solo punto (spikes que saltan y vuelven), el patron tipico
     * del multipath en canon urbano. Los extremos se conservan siempre.
     *
     * Una teleportacion real es sostenida (salta y se queda), asi que no presenta el
     * patron de ida y vuelta y no se filtra.
     */
    fun filterOutliers(fixes: List<LocationFixEntity>): List<LocationFixEntity> {
        if (fixes.size < 3) return fixes
        val kept = mutableListOf(fixes.first())
        for (i in 1 until fixes.size - 1) {
            val inSpeed = segmentSpeedMps(kept.last(), fixes[i])
            val outSpeed = segmentSpeedMps(fixes[i], fixes[i + 1])
            if (inSpeed != null && outSpeed != null &&
                inSpeed > MAX_SPIKE_MPS && outSpeed > MAX_SPIKE_MPS
            ) continue
            kept += fixes[i]
        }
        kept += fixes.last()
        return kept
    }

    /**
     * Trocea el track en tramos cuyo desplazamiento se distingue del ruido del GPS.
     *
     * Acumula desde un ancla y cierra un tramo cuando la separacion respecto de ella
     * supera el piso de ruido; entonces el destino pasa a ser la nueva ancla. Estar
     * detenido deja de generar distancia fantasma, que es de donde viene el grueso de
     * la inflacion.
     *
     * El umbral se mide contra el ancla y no entre fixes consecutivos porque llegan a
     * 1 Hz: un caminante avanza ~1,4 m por fix, muy por debajo de la precision tipica
     * (5-15 m), y descartar fix a fix borraria la caminata entera.
     *
     * Si la quietud pasa de [STOP_WINDOW_S] el ancla salta al fix actual sin acumular
     * distancia: si no, la pausa se sumaria al tiempo del tramo siguiente y lo haria
     * parecer mucho mas lento de lo que fue.
     */
    fun significantLegs(fixes: List<LocationFixEntity>): List<Leg> {
        val legs = mutableListOf<Leg>()
        var anchor = fixes.firstOrNull() ?: return legs
        for (fix in fixes.drop(1)) {
            val distance = haversineM(anchor.lat, anchor.lon, fix.lat, fix.lon)
            if (distance >= noiseFloorM(anchor, fix)) {
                legs += Leg(anchor, fix, distance)
                anchor = fix
            } else if ((fix.capturedAt - anchor.capturedAt) / 1000.0 > STOP_WINDOW_S) {
                anchor = fix
            }
        }
        return legs
    }

    /** Track sin jitter para dibujar: el origen mas el destino de cada tramo significativo. */
    fun anchors(fixes: List<LocationFixEntity>): List<LocationFixEntity> {
        val first = fixes.firstOrNull() ?: return emptyList()
        return listOf(first) + significantLegs(fixes).map { it.to }
    }

    /** Distancia recorrida en metros, ignorando el jitter del GPS. */
    fun totalDistanceM(fixes: List<LocationFixEntity>): Double =
        significantLegs(fixes).sumOf { it.distanceM }

    /**
     * Desplazamiento bajo el cual el movimiento no se distingue del ruido.
     *
     * Es el doble de la peor precision declarada de los dos fixes: `accuracyM` es un
     * radio (~68% de confianza), asi que dos lecturas del mismo punto quieto pueden
     * separarse hasta el diametro. El techo evita que un fix con precision mala se
     * trague un tramo real.
     */
    private fun noiseFloorM(a: LocationFixEntity, b: LocationFixEntity): Double {
        val worst = maxOf(a.accuracyM?.toDouble() ?: 0.0, b.accuracyM?.toDouble() ?: 0.0)
        return min(worst * NOISE_FLOOR_FACTOR, MAX_NOISE_FLOOR_M)
    }

    /** Velocidad implicada entre dos fixes (m/s). null si dt <= 0. */
    private fun segmentSpeedMps(a: LocationFixEntity, b: LocationFixEntity): Double? {
        val dtS = (b.capturedAt - a.capturedAt) / 1000.0
        if (dtS <= 0) return null
        return haversineM(a.lat, a.lon, b.lat, b.lon) / dtS
    }

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
