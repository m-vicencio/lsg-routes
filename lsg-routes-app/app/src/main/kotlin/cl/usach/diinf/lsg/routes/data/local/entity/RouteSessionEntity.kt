package cl.usach.diinf.lsg.routes.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Estados locales de la sesion de ruta. */
object SessionSyncStatus {
    const val RECORDING = "RECORDING"
    const val COMPLETED_PENDING_UPLOAD = "COMPLETED_PENDING_UPLOAD"
    const val UPLOADING = "UPLOADING"
    const val SYNCED = "SYNCED"
    const val UPLOAD_FAILED = "UPLOAD_FAILED"
}

/** Que hacer en el backend cuando por fin haya conexion para subir la sesion. */
object PendingAction {
    const val FINISH = "FINISH"
    const val ABORT = "ABORT"
}

@Entity(tableName = "local_route_sessions")
data class RouteSessionEntity(
    @PrimaryKey val id: String,
    val routeId: String,
    val idPlayers: Int,
    val mode: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val pendingAction: String = PendingAction.FINISH,
)
