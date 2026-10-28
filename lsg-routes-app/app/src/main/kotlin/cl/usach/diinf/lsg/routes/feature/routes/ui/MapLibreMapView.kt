package cl.usach.diinf.lsg.routes.feature.routes.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import cl.usach.diinf.lsg.routes.BuildConfig
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

// La clave sale de local.properties (MAPTILER_API_KEY) via BuildConfig: no se versiona.
private val STYLE_URL =
    "https://api.maptiler.com/maps/hybrid-v4/style.json?key=${BuildConfig.MAPTILER_API_KEY}"

// Guia de la ruta planificada (visible mientras se camina, para no perderse)
private const val PLANNED_SOURCE_ID = "planned-source"
private const val PLANNED_LAYER_ID = "planned-layer"

// Linea de la ruta (dibujada, a mano alzada u ORS)
private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LAYER_ID = "route-layer"

// Un punto por vertice (solo en modo dibujo o mano alzada)
private const val DOTS_SOURCE_ID = "dots-source"
private const val DOTS_LAYER_ID = "dots-layer"

// Marcadores del modo A-B
private const val START_SOURCE_ID = "start-source"
private const val START_LAYER_ID = "start-layer"
private const val END_SOURCE_ID = "end-source"
private const val END_LAYER_ID = "end-layer"

// Marcador movil del replay
private const val REPLAY_SOURCE_ID = "replay-source"
private const val REPLAY_LAYER_ID = "replay-layer"

private val DEFAULT_CENTER = LatLng(-33.45, -70.65)
private const val DEFAULT_ZOOM = 13.0

@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    waypoints: List<LatLng> = emptyList(),
    plannedRoute: List<LatLng> = emptyList(),  // guia punteada de la ruta planificada, visible mientras se camina
    showWaypointDots: Boolean = false,   // true en modo dibujo o mano alzada: un punto por vertice
    startPoint: LatLng? = null,          // marcador de inicio A-B (verde)
    endPoint: LatLng? = null,            // marcador de destino A-B (rojo)
    replayPoint: LatLng? = null,         // marcador movil del replay (avatar recorriendo la ruta)
    cameraBounds: List<LatLng> = emptyList(),  // fija la camara para enmarcar estos puntos (mapa estatico)
    isFreehandMode: Boolean = false,
    locationPermissionGranted: Boolean = false,
    followGps: Boolean = false,
    onMapReady: (MapLibreMap) -> Unit = {},
    onMapClick: (LatLng) -> Unit = {},
    onMapLongClick: (LatLng) -> Unit = {},
    onFreehandPoint: (LatLng) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember { MapView(context) }
    // El estilo carga async: hasta que no este listo no se puede activar el
    // componente de ubicacion. Sirve de disparador para el efecto de abajo.
    val styleReady = remember { mutableStateOf(false) }

    // Referencias estables para el listener de toques
    val isFreehandRef = remember { mutableStateOf(isFreehandMode) }
    LaunchedEffect(isFreehandMode) { isFreehandRef.value = isFreehandMode }
    val onFreehandPointRef = rememberUpdatedState(onFreehandPoint)
    val showDotsRef = rememberUpdatedState(showWaypointDots)

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = mapView.onCreate(Bundle())
            override fun onStart(owner: LifecycleOwner) = mapView.onStart()
            override fun onResume(owner: LifecycleOwner) = mapView.onResume()
            override fun onPause(owner: LifecycleOwner) = mapView.onPause()
            override fun onStop(owner: LifecycleOwner) = mapView.onStop()
            override fun onDestroy(owner: LifecycleOwner) = mapView.onDestroy()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Redibuja la guia de la ruta planificada cuando cambia
    LaunchedEffect(plannedRoute) {
        mapRef.value?.style?.let { style -> updatePlannedRoute(style, plannedRoute) }
    }

    // Redibuja la linea y los puntos cuando cambian los vertices o el modo de puntos
    LaunchedEffect(waypoints, showWaypointDots) {
        mapRef.value?.style?.let { style ->
            updateRoute(style, waypoints)
            updateWaypointDots(style, if (showDotsRef.value) waypoints else emptyList())
        }
    }

    // Redibuja los marcadores A-B cuando cambian el inicio o el destino
    LaunchedEffect(startPoint, endPoint) {
        mapRef.value?.style?.let { style ->
            updateMarker(style, START_SOURCE_ID, START_LAYER_ID, startPoint, Color.parseColor("#4CAF50"))
            updateMarker(style, END_SOURCE_ID, END_LAYER_ID, endPoint, Color.parseColor("#F44336"))
        }
    }

    // Enmarca la camara en los puntos dados (mapas estaticos de replay)
    LaunchedEffect(cameraBounds) {
        if (cameraBounds.isNotEmpty()) mapRef.value?.let { fitCameraToBounds(it, cameraBounds) }
    }

    // Mueve el marcador del replay (reproduccion animada)
    LaunchedEffect(replayPoint) {
        mapRef.value?.style?.let { style ->
            updateMarker(style, REPLAY_SOURCE_ID, REPLAY_LAYER_ID, replayPoint, Color.parseColor("#FF5722"))
        }
    }

    // Punto del usuario + centrado. Unico lugar donde se activa el componente de
    // ubicacion: se dispara cuando el estilo termina de cargar Y cuando el permiso
    // cambia, asi cubre las dos carreras (permiso antes o despues de cargar el mapa).
    LaunchedEffect(locationPermissionGranted, styleReady.value) {
        if (!locationPermissionGranted || !styleReady.value) return@LaunchedEffect
        val map = mapRef.value ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        enableLocationComponent(context, map, style, followGps)
        // No recentrar en el usuario si hay un encuadre explicito (preview/replay)
        if (cameraBounds.isEmpty()) jumpToUserLocation(context, map)
    }

    AndroidView(
        modifier = modifier,
        factory = {
            // Listener de toques: con la mano alzada activa intercepta el arrastre y
            // devuelve true (consume el evento) para que el mapa no se desplace al dibujar.
            mapView.setOnTouchListener { _, event ->
                if (!isFreehandRef.value) return@setOnTouchListener false
                val map = mapRef.value ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        val latLng = map.projection.fromScreenLocation(PointF(event.x, event.y))
                        onFreehandPointRef.value(latLng)
                        true
                    }
                    else -> false
                }
            }

            mapView.getMapAsync { map ->
                mapRef.value = map
                map.setStyle(STYLE_URL) { style ->
                    map.cameraPosition = CameraPosition.Builder()
                        .target(DEFAULT_CENTER)
                        .zoom(DEFAULT_ZOOM)
                        .build()
                    // Dibuja lo que ya hay en el estado al terminar de cargar el estilo.
                    // La ruta planificada va primero para quedar DEBAJO del track y los puntos.
                    updatePlannedRoute(style, plannedRoute)
                    updateRoute(style, waypoints)
                    updateWaypointDots(style, if (showDotsRef.value) waypoints else emptyList())
                    updateMarker(style, START_SOURCE_ID, START_LAYER_ID, startPoint, Color.parseColor("#4CAF50"))
                    updateMarker(style, END_SOURCE_ID, END_LAYER_ID, endPoint, Color.parseColor("#F44336"))
                    updateMarker(style, REPLAY_SOURCE_ID, REPLAY_LAYER_ID, replayPoint, Color.parseColor("#FF5722"))
                    if (cameraBounds.isNotEmpty()) fitCameraToBounds(map, cameraBounds)
                    // Recien aqui el componente de ubicacion puede activarse (ver efecto arriba)
                    styleReady.value = true
                    onMapReady(map)
                }
                map.addOnMapClickListener { latLng -> onMapClick(latLng); true }
                map.addOnMapLongClickListener { latLng -> onMapLongClick(latLng); true }
            }
            mapView
        },
    )
}

// Ubicacion

@SuppressLint("MissingPermission")
private fun enableLocationComponent(
    context: android.content.Context,
    map: MapLibreMap,
    style: Style,
    followGps: Boolean,
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) return

    map.locationComponent.apply {
        activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(true)
                .build()
        )
        isLocationComponentEnabled = true
        cameraMode = if (followGps) CameraMode.TRACKING else CameraMode.NONE
        renderMode = RenderMode.COMPASS
    }
}

/**
 * Centra la camara en la posicion del usuario una vez. No hace nada si no hay
 * ubicacion disponible.
 *
 * Usa getCurrentLocation en lugar de lastLocation: este ultimo devuelve null en
 * arranque frio (si ninguna app pidio GPS hace poco), y entonces no habria
 * centrado. getCurrentLocation reusa la cache si tiene menos de un minuto y, si no,
 * pide un fix nuevo.
 */
@SuppressLint("MissingPermission")
private fun jumpToUserLocation(context: android.content.Context, map: MapLibreMap) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) return

    val request = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        .setMaxUpdateAgeMillis(60_000L)
        .build()
    LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(request, null)
        .addOnSuccessListener { location ->
            location ?: return@addOnSuccessListener
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .zoom(DEFAULT_ZOOM)
                        .build()
                )
            )
        }
}

/**
 * Mueve la camara para enmarcar los puntos dados. Con un solo punto (o todos
 * iguales) centra con un zoom fijo; con varios usa LatLngBounds con padding.
 * Tolerante a fallos: nunca propaga excepciones de MapLibre.
 */
private fun fitCameraToBounds(map: MapLibreMap, points: List<LatLng>) {
    val distinct = points.distinct()
    if (distinct.isEmpty()) return
    try {
        if (distinct.size == 1) {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(distinct.first()).zoom(15.0).build()
                )
            )
            return
        }
        val bounds = LatLngBounds.Builder().apply { distinct.forEach { include(it) } }.build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
    } catch (e: Exception) {
        // Bounds degenerados u otro problema: centrar en el primer punto sin romper
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(distinct.first()).zoom(14.0).build()
            )
        )
    }
}

// Capas del mapa

/** Actualiza (o crea) la guia de la ruta planificada: una linea punteada semitransparente. */
private fun updatePlannedRoute(style: Style, planned: List<LatLng>) {
    if (planned.size < 2) {
        style.getSourceAs<GeoJsonSource>(PLANNED_SOURCE_ID)?.setGeoJson(emptyFeatureCollection())
        return
    }
    val coords = planned.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    val geojson = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}]}"""
    val existing = style.getSourceAs<GeoJsonSource>(PLANNED_SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(geojson)
    } else {
        style.addSource(GeoJsonSource(PLANNED_SOURCE_ID, geojson))
        style.addLayer(
            LineLayer(PLANNED_LAYER_ID, PLANNED_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#FB8C00")),  // guia ambar
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.7f),
                PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),       // punteada
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
    }
}

/** Actualiza (o crea) la capa LineString de la ruta. Con menos de 2 puntos la vacia. */
private fun updateRoute(style: Style, waypoints: List<LatLng>) {
    if (waypoints.size < 2) {
        // Vacia la fuente si existe, para que no quede dibujada una linea vieja
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            ?.setGeoJson(emptyFeatureCollection())
        return
    }
    val coords = waypoints.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    val geojson = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}]}"""
    val existing = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(geojson)
    } else {
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, geojson))
        style.addLayer(
            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#4285F4")),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
    }
}

/** Actualiza (o crea) un punto azul por cada vertice. */
private fun updateWaypointDots(style: Style, waypoints: List<LatLng>) {
    val geojson = if (waypoints.isEmpty()) {
        emptyFeatureCollection()
    } else {
        val features = waypoints.joinToString(",") { pt ->
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${pt.longitude},${pt.latitude}]}}"""
        }
        """{"type":"FeatureCollection","features":[$features]}"""
    }
    val existing = style.getSourceAs<GeoJsonSource>(DOTS_SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(geojson)
    } else if (waypoints.isNotEmpty()) {
        style.addSource(GeoJsonSource(DOTS_SOURCE_ID, geojson))
        style.addLayer(
            CircleLayer(DOTS_LAYER_ID, DOTS_SOURCE_ID).withProperties(
                PropertyFactory.circleColor(Color.parseColor("#4285F4")),
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            )
        )
    }
}

/** Actualiza (o crea) un marcador circular de color (inicio o destino A-B, replay). */
private fun updateMarker(
    style: Style,
    sourceId: String,
    layerId: String,
    point: LatLng?,
    color: Int,
) {
    val geojson = if (point != null) {
        """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[${point.longitude},${point.latitude}]}}]}"""
    } else {
        emptyFeatureCollection()
    }
    val existing = style.getSourceAs<GeoJsonSource>(sourceId)
    if (existing != null) {
        existing.setGeoJson(geojson)
    } else if (point != null) {
        style.addSource(GeoJsonSource(sourceId, geojson))
        style.addLayer(
            CircleLayer(layerId, sourceId).withProperties(
                PropertyFactory.circleColor(color),
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            )
        )
    }
}

private fun emptyFeatureCollection() =
    """{"type":"FeatureCollection","features":[]}"""
