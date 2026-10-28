package cl.usach.diinf.lsg.routes.domain.repository

import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import cl.usach.diinf.lsg.routes.data.local.entity.RouteSessionEntity
import kotlinx.coroutines.flow.Flow

interface LocalTrackingRepository {
    suspend fun createLocalSession(sessionId: String, routeId: String, idPlayers: Int, mode: String)
    suspend fun getLocalSession(sessionId: String): RouteSessionEntity?
    fun observeSession(sessionId: String): Flow<RouteSessionEntity?>
    suspend fun updateSessionStatus(sessionId: String, status: String)

    /** Cierra la sesion local y deja anotado si al sincronizar hay que finalizarla
     *  (calcula puntos) o cancelarla (no_movement, sin puntos). */
    suspend fun endLocalSession(sessionId: String, isAbort: Boolean = false)
    fun observeFixes(sessionId: String): Flow<List<LocationFixEntity>>
    suspend fun getUnuploadedFixes(sessionId: String): List<LocationFixEntity>
    suspend fun markFixesUploaded(sessionId: String, upToSeq: Int)
    suspend fun getPendingSessions(): List<RouteSessionEntity>
    fun observeAllSessions(): Flow<List<RouteSessionEntity>>

    /** Sesiones que todavia no llegaron a SYNCED: grabadas offline o con la subida fallida. */
    fun observePendingSessions(): Flow<List<RouteSessionEntity>>
}
