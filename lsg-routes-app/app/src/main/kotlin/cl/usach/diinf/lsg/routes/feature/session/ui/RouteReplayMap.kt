package cl.usach.diinf.lsg.routes.feature.session.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.domain.model.SessionTrack
import cl.usach.diinf.lsg.routes.feature.routes.ui.MapLibreMapView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.geometry.LatLng
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Velocidad de reproduccion del replay (m/s). ~500 m en ~30 s; 1 km en ~60 s.
private const val PLAYBACK_SPEED_MPS = 500.0 / 30.0
private const val REPLAY_LOOP_PAUSE_MS = 800L

/** True si hay algo que dibujar (ruta planificada o track real). */
fun SessionTrack?.hasGeometry(): Boolean =
    this != null && (plannedGeometry.isNotEmpty() || fixes.isNotEmpty())

/**
 * Mapa read-only con la ruta planificada (guia punteada) y el track GPS real
 * (linea solida) de una sesion, con un marcador que recorre el trayecto en bucle
 * (replay acelerado). Reutilizado por la pantalla de resumen y el detalle del historial.
 */
@Composable
fun RouteReplayCard(track: SessionTrack, modifier: Modifier = Modifier) {
    val plannedPts = remember(track) { track.plannedGeometry.toLatLngs() }
    // El suavizado va aqui y no en la llamada al mapa: framePts hereda de actualPts,
    // asi que el marcador del replay recorre exactamente la linea que se dibuja en
    // vez de despegarse en cada curva. Los extremos no se mueven, asi que los
    // marcadores de inicio y fin caen donde caian.
    val actualPts = remember(track) {
        smoothForDisplay(track.fixes.toGeoPoints()).map { LatLng(it.lat, it.lon) }
    }
    // El track real define inicio/fin, encuadre y trayecto del replay; si no hay, la planificada
    val framePts = remember(track) { actualPts.ifEmpty { plannedPts } }

    // Posicion animada del marcador de replay
    var replayPoint by remember(framePts) { mutableStateOf(framePts.firstOrNull()) }
    LaunchedEffect(framePts) {
        if (framePts.size < 2) return@LaunchedEffect
        val cum = cumulativeDistances(framePts)
        val total = cum.last()
        if (total <= 0.0) return@LaunchedEffect
        while (isActive) {
            var baseNs = 0L
            var finished = false
            while (isActive && !finished) {
                withFrameNanos { now ->
                    if (baseNs == 0L) baseNs = now
                    val travelled = ((now - baseNs) / 1_000_000_000.0) * PLAYBACK_SPEED_MPS
                    if (travelled >= total) {
                        replayPoint = framePts.last()
                        finished = true
                    } else {
                        replayPoint = pointAlong(framePts, cum, travelled)
                    }
                }
            }
            delay(REPLAY_LOOP_PAUSE_MS)  // pausa al final antes de repetir
        }
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.summary_route_map),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            MapLibreMapView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp)),
                waypoints = actualPts,               // track real (linea solida azul)
                plannedRoute = plannedPts,           // ruta guia (linea punteada ambar)
                startPoint = framePts.firstOrNull(),
                endPoint = framePts.lastOrNull(),
                replayPoint = replayPoint,           // marcador movil (naranja)
                cameraBounds = framePts,
            )
        }
    }
}

private fun List<List<Double>>.toLatLngs(): List<LatLng> =
    mapNotNull { c -> if (c.size >= 2) LatLng(c[0], c[1]) else null }

private fun List<List<Double>>.toGeoPoints(): List<GeoPoint> =
    mapNotNull { c -> if (c.size >= 2) GeoPoint(c[0], c[1]) else null }

/** Distancia acumulada (m) en cada punto del trayecto. */
private fun cumulativeDistances(pts: List<LatLng>): DoubleArray {
    val cum = DoubleArray(pts.size)
    for (i in 1 until pts.size) cum[i] = cum[i - 1] + haversineM(pts[i - 1], pts[i])
    return cum
}

/** Interpola la posicion a `dist` metros a lo largo del trayecto. */
private fun pointAlong(pts: List<LatLng>, cum: DoubleArray, dist: Double): LatLng {
    var i = 1
    while (i < cum.size && cum[i] < dist) i++
    if (i >= cum.size) return pts.last()
    val segLen = cum[i] - cum[i - 1]
    val t = if (segLen > 0) (dist - cum[i - 1]) / segLen else 0.0
    val a = pts[i - 1]
    val b = pts[i]
    return LatLng(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t,
    )
}

private fun haversineM(a: LatLng, b: LatLng): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
