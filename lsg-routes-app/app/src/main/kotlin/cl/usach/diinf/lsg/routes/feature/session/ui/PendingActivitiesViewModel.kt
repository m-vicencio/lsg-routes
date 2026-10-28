package cl.usach.diinf.lsg.routes.feature.session.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cl.usach.diinf.lsg.routes.data.local.entity.PendingAction
import cl.usach.diinf.lsg.routes.data.local.entity.RouteSessionEntity
import cl.usach.diinf.lsg.routes.data.local.entity.SessionSyncStatus
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.domain.repository.LocalTrackingRepository
import cl.usach.diinf.lsg.routes.domain.repository.RouteRepository
import cl.usach.diinf.lsg.routes.feature.session.service.TrackingService
import cl.usach.diinf.lsg.routes.feature.session.worker.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Actividad grabada en el dispositivo que todavia no llego a SYNCED: puede estar
 *  registrandose, esperando conexion, subiendo, o con un intento fallido. */
data class PendingActivityItem(
    val sessionId: String,
    val routeName: String?,   // null = recorrido libre
    val routeId: String,      // vacio = recorrido libre
    val idPlayers: Int,
    val mode: ActivityMode,
    val status: String,
    val pendingAction: String,
    val startedAt: Long,
) {
    /** Una sesion que quedo grabando porque el proceso murio sin cerrarla. Es la unica
     *  que se puede reanudar: el resto ya esta cerrada y solo espera la subida. */
    val isStuckRecording: Boolean get() = status == SessionSyncStatus.RECORDING
}

@HiltViewModel
class PendingActivitiesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localTrackingRepository: LocalTrackingRepository,
    private val routeRepository: RouteRepository,
) : ViewModel() {

    val items: StateFlow<List<PendingActivityItem>> =
        localTrackingRepository.observePendingSessions()
            .map { sessions ->
                // Los nombres de ruta solo salen del respaldo local: esta pantalla tiene
                // que funcionar sin conexion, que es justo el caso que la trae a pantalla.
                val routeNames = routeRepository.getCachedRouteNames()
                sessions.map { it.toItem(routeNames) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Vuelve a encolar la sincronizacion de una actividad. WorkManager la corre
     *  apenas haya conexion; esto solo sirve para no esperar el backoff tras un
     *  intento fallido. */
    fun retry(item: PendingActivityItem) {
        val work = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    UploadWorker.KEY_SESSION_ID to item.sessionId,
                    UploadWorker.KEY_ABORT to (item.pendingAction == PendingAction.ABORT),
                )
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("pending_sync_${item.sessionId}", ExistingWorkPolicy.KEEP, work)
    }

    /**
     * Cierra una sesion que quedo grabando y la encola para sincronizar.
     *
     * El ACTION_STOP va primero y es imprescindible: si el servicio sigue vivo seguiria
     * insertando fixes en una sesion ya cerrada, esos tramos quedarian sin subir y al
     * llegar al destino encolaria trabajo duplicado con el wake lock aun tomado.
     */
    fun finishStuck(item: PendingActivityItem) {
        context.startService(Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
            putExtra(TrackingService.EXTRA_SESSION_ID, item.sessionId)
        })
        viewModelScope.launch {
            localTrackingRepository.endLocalSession(item.sessionId)
            retry(item.copy(pendingAction = PendingAction.FINISH))
        }
    }

    private fun RouteSessionEntity.toItem(routeNames: Map<String, String>) = PendingActivityItem(
        sessionId = id,
        routeName = routeId.takeIf { it.isNotBlank() }?.let { routeNames[it] },
        routeId = routeId,
        idPlayers = idPlayers,
        mode = runCatching { ActivityMode.fromKey(mode) }.getOrDefault(ActivityMode.WALKING),
        status = status,
        pendingAction = pendingAction,
        startedAt = startedAt,
    )
}
