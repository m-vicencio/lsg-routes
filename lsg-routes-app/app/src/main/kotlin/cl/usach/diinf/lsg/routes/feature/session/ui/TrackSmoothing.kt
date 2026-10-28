package cl.usach.diinf.lsg.routes.feature.session.ui

// Dos pasadas dan curva suficiente; con mas, la linea empieza a despegarse del
// trazo real en las esquinas cerradas.
private const val CHAIKIN_ITERATIONS = 2
private const val CHAIKIN_CUT = 0.25

/** Punto del track a suavizar. Existe para que el suavizado no dependa de MapLibre
 *  y se pueda probar en la JVM. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Suaviza el track para dibujarlo: media movil centrada de 3 puntos y despues dos
 * pasadas de Chaikin.
 *
 * Es solo cosmetico y no puede alimentar ninguna medicion. Chaikin corta las
 * esquinas, asi que la linea suavizada es mas corta que la real: medir sobre ella
 * daria menos metros que los que acredita el backend. La distancia sale de GpsTrack
 * sobre los fixes sin tocar.
 *
 * Los extremos se conservan exactos porque ahi se anclan los marcadores de inicio,
 * de fin y el del replay.
 */
fun smoothForDisplay(points: List<GeoPoint>): List<GeoPoint> {
    var smoothed = movingAverage3(points)
    repeat(CHAIKIN_ITERATIONS) { smoothed = chaikinStep(smoothed) }
    return smoothed
}

/**
 * Media movil centrada de 3 puntos; los extremos quedan como estan.
 *
 * Promediar grados directamente basta: a escala de una ruta urbana la deformacion
 * entre grados de latitud y longitud no se ve, y no hay polos ni antimeridiano.
 */
private fun movingAverage3(points: List<GeoPoint>): List<GeoPoint> {
    if (points.size < 3) return points
    return List(points.size) { i ->
        if (i == 0 || i == points.lastIndex) {
            points[i]
        } else {
            GeoPoint(
                (points[i - 1].lat + points[i].lat + points[i + 1].lat) / 3.0,
                (points[i - 1].lon + points[i].lon + points[i + 1].lon) / 3.0,
            )
        }
    }
}

/**
 * Una pasada de Chaikin: cada tramo aporta sus puntos a 1/4 y 3/4, y los extremos
 * del track se reinsertan intactos.
 *
 * Con menos de 3 puntos no hay esquina que cortar: subdividir solo agregaria
 * vertices colineales, y con un solo punto duplicaria ese punto.
 */
private fun chaikinStep(points: List<GeoPoint>): List<GeoPoint> {
    if (points.size < 3) return points
    val out = ArrayList<GeoPoint>(points.size * 2)
    out += points.first()
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        out += between(a, b, CHAIKIN_CUT)
        out += between(a, b, 1.0 - CHAIKIN_CUT)
    }
    out += points.last()
    return out
}

private fun between(a: GeoPoint, b: GeoPoint, t: Double) = GeoPoint(
    a.lat + (b.lat - a.lat) * t,
    a.lon + (b.lon - a.lon) * t,
)
