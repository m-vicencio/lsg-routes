package cl.usach.diinf.lsg.routes.feature.session.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cl.usach.diinf.lsg.routes.LsgRoutesApplication
import cl.usach.diinf.lsg.routes.MainActivity
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.core.auth.TokenRefresher
import cl.usach.diinf.lsg.routes.core.config.FeatureFlags
import cl.usach.diinf.lsg.routes.data.local.dao.TrackingDao
import cl.usach.diinf.lsg.routes.data.local.entity.AccelSampleEntity
import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import cl.usach.diinf.lsg.routes.data.local.entity.StepCountEntity
import cl.usach.diinf.lsg.routes.domain.model.GpsTrack
import cl.usach.diinf.lsg.routes.domain.model.SpeedBands
import cl.usach.diinf.lsg.routes.domain.repository.LocalTrackingRepository
import cl.usach.diinf.lsg.routes.feature.session.worker.UploadWorker
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.sqrt

/** Servicio en primer plano que captura GPS, acelerometro y pasos durante una ruta activa. */
@AndroidEntryPoint
class TrackingService : Service() {

    @Inject lateinit var trackingDao: TrackingDao
    @Inject lateinit var localTrackingRepository: LocalTrackingRepository
    @Inject lateinit var tokenRefresher: TokenRefresher
    @Inject lateinit var featureFlags: FeatureFlags

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tokenRefreshJob: Job? = null
    private var sessionId: String? = null
    private val fixSeq = AtomicInteger(0)
    /** Ruta de navegacion del recorrido en curso, para volver desde la notificacion. */
    private var navRoute: String? = null

    // Auto-completado al llegar al destino (ultimo punto de la ruta)
    private var destLat: Double? = null
    private var destLon: Double? = null
    private var armedForCompletion = false  // true tras alejarse del destino (soporta rutas en bucle)
    private var completing = false

    // Recorrido libre: corte por exceso de velocidad sostenido
    private var freeRoam = false
    private val recentFixes = ArrayDeque<LocationFixEntity>()
    private var overspeedStartMs = 0L
    private var overspeedWarned = false

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val powerManager by lazy {
        getSystemService(PowerManager::class.java)
    }
    // Mantiene la CPU despierta para que el acelerometro siga muestreando con la pantalla apagada.
    // Solo se sostiene durante una sesion activa; ver acquireWakeLock/releaseWakeLock.
    private var wakeLock: PowerManager.WakeLock? = null
    private val sensorManager by lazy {
        getSystemService(SensorManager::class.java)
    }

    // Buffer de acelerometro para agregar en buckets de 1 segundo
    private val accelBuffer = mutableListOf<Float>()
    private var accelBucketStart = 0L
    private var lastStepCount = -1

    // Logger de rendimiento (ver Logger-Interno.md): contadores acumulados que le pasa este servicio.
    private val perfLogger by lazy { PerfLogger(applicationContext) }
    private val gpsUpdateCount = AtomicInteger(0)
    private val stepTotal = AtomicInteger(0)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val sid = sessionId ?: return
            // Un fix con precision peor que el tope no sirve ni para estimar: en canon
            // urbano el multipath los devuelve a decenas de metros del punto real.
            val (usable, imprecise) = result.locations.partition { GpsTrack.isUsable(it.accuracy) }
            if (imprecise.isNotEmpty()) {
                Timber.d("Sesion $sid: %d fix(es) descartados por precision insuficiente", imprecise.size)
            }
            if (usable.isEmpty()) return
            gpsUpdateCount.addAndGet(usable.size)
            val fixes = usable.map { loc ->
                LocationFixEntity(
                    sessionId = sid,
                    lat = loc.latitude,
                    lon = loc.longitude,
                    accuracyM = loc.accuracy,
                    altitudeM = loc.altitude,
                    speedMps = loc.speed,
                    bearingDeg = loc.bearing,
                    isMock = loc.isFromMockProvider,
                    capturedAt = loc.time,
                    clientSeq = fixSeq.getAndIncrement(),
                )
            }
            serviceScope.launch { fixes.forEach { trackingDao.insertFix(it) } }
            // Auto-completar al llegar al destino (corre en el servicio, asi funciona con la pantalla bloqueada)
            fixes.forEach { checkArrival(it.lat, it.lon) }
            checkOverspeed(fixes)
        }
    }

    /** Marca la ruta completa al llegar al destino. Se "arma" tras alejarse del destino,
     *  asi una ruta en bucle (inicio y fin coinciden) no se completa al partir. */
    private fun checkArrival(lat: Double, lon: Double) {
        val dLat = destLat ?: return
        val dLon = destLon ?: return
        if (completing) return
        val dist = GpsTrack.haversineM(lat, lon, dLat, dLon)
        if (dist > COMPLETION_RADIUS_M) {
            armedForCompletion = true
        } else if (armedForCompletion) {
            autoComplete()
        }
    }

    /** Avisa y luego corta el recorrido libre si la velocidad se mantiene sobre el techo de bicicleta.
     *
     *  Vive en el servicio (y no en el ViewModel) porque el caso real es ir en un vehiculo
     *  con la pantalla apagada: el aviso tiene que llegar como notificacion. */
    private fun checkOverspeed(fixes: List<LocationFixEntity>) {
        if (!freeRoam || completing || fixes.isEmpty()) return
        recentFixes.addAll(fixes)
        while (recentFixes.size > RECENT_FIXES_CAPACITY) recentFixes.removeFirst()

        val speedKmh = SpeedBands.rollingSpeedKmh(recentFixes.toList())
        if (speedKmh <= SpeedBands.excessKmh(featureFlags.isBikeEnabled)) {
            // Volvio a un rango plausible: se perdona lo acumulado
            if (overspeedWarned) updateNotification(getString(R.string.notification_tracking_body))
            overspeedStartMs = 0L
            overspeedWarned = false
            return
        }

        val now = System.currentTimeMillis()
        if (overspeedStartMs == 0L) {
            overspeedStartMs = now
            return
        }
        val sustainedMs = now - overspeedStartMs
        if (sustainedMs >= OVERSPEED_CUT_MS) {
            Timber.w("Recorrido libre $sessionId: corte por exceso de velocidad (%.0f km/h)", speedKmh)
            autoComplete()
        } else if (sustainedMs >= OVERSPEED_WARN_MS && !overspeedWarned) {
            overspeedWarned = true
            updateNotification(getString(R.string.notification_overspeed_warning))
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Finaliza la sesion automaticamente: marca local completada y encola la subida + /finish. */
    private fun autoComplete() {
        if (completing) return
        completing = true
        val sid = sessionId ?: return
        Timber.d("Ruta $sid: destino alcanzado, completando automaticamente")
        serviceScope.launch {
            localTrackingRepository.endLocalSession(sid)  // COMPLETED_PENDING_UPLOAD (la UI lo observa)
            val work = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(UploadWorker.KEY_SESSION_ID to sid))
                .build()
            WorkManager.getInstance(applicationContext).enqueue(work)

            stopLocationUpdates()
            unregisterSensors()
            releaseWakeLock()
            stopSelf()
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> aggregateAccel(event)
                Sensor.TYPE_STEP_COUNTER -> handleSteps(event)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun aggregateAccel(event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        val now = android.os.SystemClock.elapsedRealtime()
        if (accelBucketStart == 0L) accelBucketStart = now
        accelBuffer.add(mag)

        if (now - accelBucketStart >= 1000L && accelBuffer.isNotEmpty()) {
            val mean = accelBuffer.average().toFloat()
            val variance = accelBuffer.map { (it - mean) * (it - mean) }.average().toFloat()
            val bucketTs = System.currentTimeMillis() - (now - accelBucketStart)
            serviceScope.launch {
                sessionId?.let { sid ->
                    trackingDao.insertAccelSample(
                        AccelSampleEntity(sessionId = sid, magnitudeMean = mean,
                            magnitudeVariance = variance, bucketStartedAt = bucketTs, bucketSeconds = 1)
                    )
                }
            }
            accelBuffer.clear()
            accelBucketStart = now
        }
    }

    private fun handleSteps(event: SensorEvent) {
        val current = event.values[0].toInt()
        if (lastStepCount < 0) { lastStepCount = current; return }
        val delta = current - lastStepCount
        if (delta > 0) {
            lastStepCount = current
            stepTotal.addAndGet(delta)
            serviceScope.launch {
                sessionId?.let { sid ->
                    trackingDao.insertStepCount(
                        StepCountEntity(sessionId = sid, stepsDelta = delta, capturedAt = System.currentTimeMillis())
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sid = intent.getStringExtra(EXTRA_SESSION_ID)
                sessionId = sid
                destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN).takeIf { !it.isNaN() }
                destLon = intent.getDoubleExtra(EXTRA_DEST_LON, Double.NaN).takeIf { !it.isNaN() }
                freeRoam = intent.getBooleanExtra(EXTRA_FREE_ROAM, false)
                navRoute = intent.getStringExtra(EXTRA_NAV_ROUTE)
                recentFixes.clear()
                overspeedStartMs = 0L
                overspeedWarned = false
                armedForCompletion = false
                completing = false
                gpsUpdateCount.set(0)
                stepTotal.set(0)
                perfLogger.start(
                    sessionId = sid ?: "unknown",
                    mode = if (freeRoam) "libre" else "planificada",
                    gpsUpdates = { gpsUpdateCount.get() },
                    steps = { stepTotal.get() },
                )
                // La numeracion sigue desde el ultimo fix guardado y no desde cero: al
                // reanudar, repetir un clientSeq haria que markFixesUploaded diera por
                // subidos fixes que nunca salieron del telefono. En una sesion nueva la
                // consulta devuelve null y la numeracion empieza en 0.
                serviceScope.launch {
                    fixSeq.set((sid?.let { trackingDao.maxClientSeq(it) } ?: -1) + 1)
                    startLocationUpdates()
                    registerSensors()
                }
                acquireWakeLock()
                startTokenRefreshLoop()
                // Con la ruta a mano, tocar la notificacion devuelve al recorrido en curso
                updateNotification(getString(R.string.notification_tracking_body))
                Timber.d("TrackingService iniciado para sesion $sessionId")
            }
            ACTION_STOP -> {
                // Puede haber otra sesion grabandose de verdad: solo se detiene si el id
                // coincide, o si quien detiene no especifica cual (parada incondicional).
                // sessionId nulo significa que este arranque creo el servicio solo para
                // atender el STOP (el anterior ya estaba muerto): hay que detenerlo igual,
                // o queda un servicio en primer plano sin nada que grabar.
                val target = intent.getStringExtra(EXTRA_SESSION_ID)
                if (target == null || sessionId == null || target == sessionId) {
                    stopLocationUpdates()
                    unregisterSensors()
                    releaseWakeLock()
                    stopSelf()
                } else {
                    Timber.d("ACTION_STOP para $target ignorado: la sesion activa es $sessionId")
                }
            }
            // START_NOT_STICKY evita el reinicio con Intent nulo, pero cualquier arranque
            // con una accion desconocida dejaria un servicio en primer plano sin nada que
            // grabar: notificacion y wake lock tomados para siempre.
            else -> stopSelf()
        }
        // NO START_STICKY: todo el estado (sesion, destino, modo libre) viene en los extras
        // y Android reentregaria un Intent nulo, con lo que el servicio reviviria sin saber
        // que grabar. La recuperacion es explicita: la notificacion o la lista de pendientes.
        return START_NOT_STICKY
    }

    /** Mantiene el token vivo durante la ruta.
     *
     *  Una sesion puede durar mas que el token (120 min), y mientras se camina no hay
     *  ninguna peticion que lo renueve: la telemetria se guarda en Room y solo se sube al
     *  final. Sin esto, una caminata larga termina con un 401 imposible de refrescar y la
     *  ruta se queda sin subir hasta que el jugador vuelva a entrar. */
    private fun startTokenRefreshLoop() {
        if (tokenRefreshJob?.isActive == true) return
        tokenRefreshJob = serviceScope.launch {
            while (isActive) {
                tokenRefresher.refreshIfExpiringSoon()
                delay(TOKEN_CHECK_INTERVAL_MS)
            }
        }
    }

    /** Adquiere un wake lock parcial acotado por timeout (red de seguridad anti-fuga). */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    /** Libera el wake lock de forma idempotente (seguro ante doble llamada). */
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() = fusedLocationClient.removeLocationUpdates(locationCallback)

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterSensors() = sensorManager.unregisterListener(sensorListener)

    /**
     * Intent que devuelve al recorrido en curso al tocar la notificacion.
     *
     * Es el camino de vuelta cuando la app se minimiza o su ventana se destruye mientras
     * el servicio sigue grabando; sin el, la unica salida seria la lista de actividades
     * pendientes. FLAG_IMMUTABLE es obligatorio desde API 31.
     */
    private fun contentIntent(): PendingIntent? {
        val route = navRoute ?: return null
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_NAV_ROUTE, route)
            // singleTop + SINGLE_TOP: con la app ya abierta el extra llega por onNewIntent
            // en vez de apilar otra MainActivity encima.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(
        text: String = getString(R.string.notification_tracking_body),
    ): Notification =
        NotificationCompat.Builder(this, LsgRoutesApplication.TRACKING_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent())
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        unregisterSensors()
        releaseWakeLock()
        perfLogger.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ACTION_START_TRACKING"
        const val ACTION_STOP = "ACTION_STOP_TRACKING"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_DEST_LAT = "extra_dest_lat"
        const val EXTRA_DEST_LON = "extra_dest_lon"
        const val EXTRA_FREE_ROAM = "extra_free_roam"
        /** Ruta de navegacion a la que vuelve la app al tocar la notificacion. */
        const val EXTRA_NAV_ROUTE = "extra_nav_route"
        // Radio (m) alrededor del destino que cuenta como "llegada"
        private const val COMPLETION_RADIUS_M = 30.0
        // Exceso de velocidad en recorrido libre: se avisa al minuto y se corta a los dos.
        private const val OVERSPEED_WARN_MS = 60_000L
        private const val OVERSPEED_CUT_MS = 120_000L
        // Buffer para la velocidad rodante: a 1 Hz cubre holgadamente la ventana de 15 s
        private const val RECENT_FIXES_CAPACITY = 30
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "LsgRoutes:TrackingWakeLock"
        // Red de seguridad: si por algun motivo no se libera, Android lo suelta a las 4h.
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        // Cada cuanto se comprueba si el token esta por caducar. Comodo frente al margen
        // de 15 min de TokenRefresher: siempre cae al menos una comprobacion dentro.
        private const val TOKEN_CHECK_INTERVAL_MS = 10 * 60 * 1000L
    }
}
