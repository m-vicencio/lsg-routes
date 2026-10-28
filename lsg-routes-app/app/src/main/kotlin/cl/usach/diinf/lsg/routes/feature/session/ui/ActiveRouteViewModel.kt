package cl.usach.diinf.lsg.routes.feature.session.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cl.usach.diinf.lsg.routes.core.config.FeatureFlags
import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import cl.usach.diinf.lsg.routes.data.local.entity.SessionSyncStatus
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.domain.model.GpsTrack
import cl.usach.diinf.lsg.routes.domain.model.SpeedBands
import cl.usach.diinf.lsg.routes.domain.repository.AuthRepository
import cl.usach.diinf.lsg.routes.domain.repository.LocalTrackingRepository
import cl.usach.diinf.lsg.routes.domain.repository.RouteRepository
import cl.usach.diinf.lsg.routes.feature.session.service.TrackingService
import cl.usach.diinf.lsg.routes.navigation.Screen
import cl.usach.diinf.lsg.routes.feature.session.worker.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ActiveRouteUiState(
    val sessionId: String = "",
    val isTracking: Boolean = false,
    val plannedRoute: List<List<Double>> = emptyList(),  // [lat, lon] de la ruta planificada
    val fixes: List<LocationFixEntity> = emptyList(),
    val elapsedSeconds: Long = 0L,
    val distanceM: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val isFinishing: Boolean = false,
    val finished: Boolean = false,
    val failedNoMovement: Boolean = false,  // cancelada por no moverse en el tiempo de gracia
    val error: String? = null,
    // Recorrido libre (sin ruta planificada)
    val isFreeRoam: Boolean = false,
    val currentActivity: ActivityMode? = null,   // null = detenido o en exceso
    val estimatedPoints: Int = 0,
    val overspeedWarning: Boolean = false,
    val cutForSpeeding: Boolean = false,         // el servicio corto por exceso sostenido
)

@HiltViewModel
class ActiveRouteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeRepository: RouteRepository,
    private val localTrackingRepository: LocalTrackingRepository,
    private val authRepository: AuthRepository,
    private val featureFlags: FeatureFlags,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Nulo cuando se entro por "Recorrido libre": no hay ruta planificada que seguir. */
    private val routeId: String? = savedStateHandle["routeId"]
    private val isFreeRoam: Boolean = routeId == null

    /** Sesion local que quedo grabando y se viene a retomar, si se entro por la
     *  notificacion o por el boton Reanudar de actividades pendientes. */
    private val resumeSessionId: String? = savedStateHandle["sessionId"]

    private val _uiState = MutableStateFlow(ActiveRouteUiState())
    val uiState: StateFlow<ActiveRouteUiState> = _uiState.asStateFlow()

    private var sessionId: String = ""
    private var startTimeMs = 0L
    private var hasMoved = false

    private companion object {
        const val START_GRACE_MS = 120_000L    // ventana para empezar a moverse (2 min)
        const val MOVE_THRESHOLD_M = 20.0       // desplazamiento minimo desde el inicio
    }

    fun startSession(idPlayers: Int) {
        if (_uiState.value.isTracking) return
        viewModelScope.launch {
            // Reanudar: se adopta la sesion local existente en vez de crear otra.
            val resumed = resumeSessionId?.let { localTrackingRepository.getLocalSession(it) }
            if (resumeSessionId != null && resumed?.status != SessionSyncStatus.RECORDING) {
                // Se cerro mientras se abria la pantalla (por ejemplo desde Pendientes):
                // arrancar una grabacion nueva a espaldas del jugador seria peor.
                _uiState.update { it.copy(error = "session_not_resumable") }
                return@launch
            }

            // El recorrido libre no tiene ruta que cargar ni destino al que llegar. Si no
            // es libre, getRoute cae al respaldo local (Room) cuando no hay red: solo falla
            // si la ruta nunca se vio con conexion antes.
            val route = if (isFreeRoam) {
                null
            } else {
                routeRepository.getRoute(routeId!!).getOrNull() ?: run {
                    _uiState.update { it.copy(error = "route_unavailable_offline") }
                    return@launch
                }
            }
            // Mostrar la ruta planificada como guia en el mapa apenas se carga
            _uiState.update { it.copy(plannedRoute = route?.geometry.orEmpty(), isFreeRoam = isFreeRoam) }

            if (resumed != null) {
                // El cronometro tiene que seguir contando desde el inicio real y no desde
                // que se reabrio la pantalla.
                sessionId = resumed.id
                startTimeMs = resumed.startedAt
            } else {
                // El id lo genera el cliente y es el mismo que adoptara el backend: el
                // seguimiento arranca local de inmediato, sin esperar a que haya conexion.
                // La sesion se crea en el backend recien al sincronizar (ver UploadWorker).
                sessionId = UUID.randomUUID().toString()
                startTimeMs = System.currentTimeMillis()

                // Persistir sesion local. En libre no hay ruta: el modo real lo
                // determina el backend al finalizar, segun la velocidad observada.
                // No se repite al reanudar: createLocalSession es un @Insert sin
                // OnConflictStrategy y reinsertar el mismo id lanzaria SQLiteConstraintException.
                localTrackingRepository.createLocalSession(
                    sessionId = sessionId,
                    routeId = routeId.orEmpty(),
                    idPlayers = idPlayers,
                    mode = route?.mode?.key ?: ActivityMode.WALKING.key,
                )
            }

            // Iniciar servicio de seguimiento (le pasamos el destino para auto-completar).
            // Al reanudar, destino y modo libre se reconstruyen solos a partir de routeId,
            // que viaja en la ruta de navegacion.
            val dest = route?.geometry?.lastOrNull()
            val navRoute = if (isFreeRoam) Screen.FreeRoam.createRoute(idPlayers, sessionId)
            else Screen.ActiveRoute.createRoute(routeId!!, idPlayers, sessionId)
            val intent = Intent(context, TrackingService::class.java).apply {
                action = TrackingService.ACTION_START
                putExtra(TrackingService.EXTRA_SESSION_ID, sessionId)
                putExtra(TrackingService.EXTRA_FREE_ROAM, isFreeRoam)
                putExtra(TrackingService.EXTRA_NAV_ROUTE, navRoute)
                if (dest != null && dest.size >= 2) {
                    putExtra(TrackingService.EXTRA_DEST_LAT, dest[0])
                    putExtra(TrackingService.EXTRA_DEST_LON, dest[1])
                }
            }
            context.startForegroundService(intent)

            _uiState.update { it.copy(sessionId = sessionId, isTracking = true) }

            // Observar fixes en tiempo real
            observeFixes()
            trackElapsedTime()
            // En libre el jugador decide cuando termina: quedarse quieto no lo cancela.
            // Al reanudar tampoco corre: hasMoved arranca en false y cancelaria a los dos
            // minutos una ruta que ya venia caminandose.
            if (!isFreeRoam && resumed == null) startNoMovementWatchdog()
            observeAutoCompletion()
        }
    }

    private fun observeFixes() {
        viewModelScope.launch {
            localTrackingRepository.observeFixes(sessionId).collect { raw ->
                // Mismo criterio que el backend al acreditar (validators/common.py): lo que
                // se muestra durante la ruta tiene que coincidir con lo que se otorga al
                // finalizar. `clean` es el track sin jitter, para dibujar; `usable` conserva
                // la cadencia de 1 Hz que necesita la velocidad rodante.
                val usable = GpsTrack.filterOutliers(GpsTrack.dropImprecise(raw))
                val clean = GpsTrack.anchors(usable)
                val dist = GpsTrack.totalDistanceM(usable)
                val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000.0
                val speed = if (elapsed > 0) (dist / elapsed) * 3.6 else 0.0
                // Movimiento real = desplazamiento desde el inicio (evita falsos por jitter GPS)
                if (!hasMoved && clean.isNotEmpty()) {
                    val start = clean.first()
                    if (clean.any {
                            GpsTrack.haversineM(start.lat, start.lon, it.lat, it.lon) > MOVE_THRESHOLD_M
                        }
                    ) {
                        hasMoved = true
                    }
                }
                if (isFreeRoam) {
                    val rolling = SpeedBands.rollingSpeedKmh(usable)
                    val bike = featureFlags.isBikeEnabled
                    _uiState.update {
                        it.copy(
                            fixes = clean, distanceM = dist, avgSpeedKmh = speed,
                            currentActivity = SpeedBands.classify(rolling, bike),
                            estimatedPoints = SpeedBands.estimatePoints(usable, bike),
                            overspeedWarning = rolling > SpeedBands.excessKmh(bike),
                        )
                    }
                } else {
                    _uiState.update { it.copy(fixes = clean, distanceM = dist, avgSpeedKmh = speed) }
                }
            }
        }
    }

    private fun trackElapsedTime() {
        viewModelScope.launch {
            while (_uiState.value.isTracking) {
                kotlinx.coroutines.delay(1_000L)
                _uiState.update { it.copy(elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000L) }
            }
        }
    }

    /** Observa cuando el servicio auto-completa la ruta al llegar al destino (incluso
     *  con la pantalla bloqueada) y refleja el fin en la UI. */
    private fun observeAutoCompletion() {
        viewModelScope.launch {
            localTrackingRepository.observeSession(sessionId).collect { session ->
                val s = _uiState.value
                if (session?.status == SessionSyncStatus.COMPLETED_PENDING_UPLOAD &&
                    !s.finished && !s.isFinishing
                ) {
                    // El servicio cerro la sesion y encolo la subida: solo reflejamos el fin.
                    // Si venia en exceso de velocidad, el cierre fue el corte del servicio.
                    _uiState.update {
                        it.copy(isTracking = false, finished = true, cutForSpeeding = it.overspeedWarning)
                    }
                }
            }
        }
    }

    /** Da 2 min para empezar a moverse; si no, cancela la ruta con estado fallido. */
    private fun startNoMovementWatchdog() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(START_GRACE_MS)
            val s = _uiState.value
            if (s.isTracking && !s.isFinishing && !hasMoved) {
                abortForNoMovement()
            }
        }
    }

    private fun abortForNoMovement() {
        _uiState.update { it.copy(isTracking = false, isFinishing = true) }

        context.startService(Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
            // Identificar la sesion evita detener otra grabacion que siga en curso
            putExtra(TrackingService.EXTRA_SESSION_ID, sessionId)
        })

        viewModelScope.launch {
            // Marcar REJECTED queda pendiente de conexion igual que cualquier otro cierre,
            // asi cancelar sin red no se pierde (ver UploadWorker isAbort).
            localTrackingRepository.endLocalSession(sessionId, isAbort = true)
            enqueueSync(isAbort = true)
            _uiState.update { it.copy(isFinishing = false, finished = true, failedNoMovement = true) }
        }
    }

    fun finishSession() {
        if (!_uiState.value.isTracking || _uiState.value.isFinishing) return
        _uiState.update { it.copy(isTracking = false, isFinishing = true) }

        context.startService(Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
            // Identificar la sesion evita detener otra grabacion que siga en curso
            putExtra(TrackingService.EXTRA_SESSION_ID, sessionId)
        })

        viewModelScope.launch {
            localTrackingRepository.endLocalSession(sessionId)
            enqueueSync(isAbort = false)
            _uiState.update { it.copy(isFinishing = false, finished = true) }
        }
    }

    /** Encola la creacion de la sesion en el backend (si hiciera falta) y su cierre
     *  (finalizar con calculo de puntos, o cancelar sin puntos). Corre cuando haya
     *  conexion, aunque eso sea recien despues de cerrar la app. */
    private fun enqueueSync(isAbort: Boolean) {
        val workRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(UploadWorker.KEY_SESSION_ID to sessionId, UploadWorker.KEY_ABORT to isAbort)
            )
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }
}
