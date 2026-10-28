package cl.usach.diinf.lsg.routes.data.repository

import cl.usach.diinf.lsg.routes.data.local.dao.RouteSessionDao
import cl.usach.diinf.lsg.routes.data.local.dao.TrackingDao
import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import cl.usach.diinf.lsg.routes.data.local.entity.RouteSessionEntity
import cl.usach.diinf.lsg.routes.data.local.entity.PendingAction
import cl.usach.diinf.lsg.routes.data.local.entity.SessionSyncStatus
import cl.usach.diinf.lsg.routes.domain.repository.LocalTrackingRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class LocalTrackingRepositoryImpl @Inject constructor(
    private val sessionDao: RouteSessionDao,
    private val trackingDao: TrackingDao,
) : LocalTrackingRepository {

    override suspend fun createLocalSession(sessionId: String, routeId: String, idPlayers: Int, mode: String) {
        sessionDao.insert(
            RouteSessionEntity(
                id = sessionId,
                routeId = routeId,
                idPlayers = idPlayers,
                mode = mode,
                status = SessionSyncStatus.RECORDING,
                startedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun getLocalSession(sessionId: String): RouteSessionEntity? =
        sessionDao.getById(sessionId)

    override fun observeSession(sessionId: String): Flow<RouteSessionEntity?> =
        sessionDao.observeById(sessionId)

    override suspend fun updateSessionStatus(sessionId: String, status: String) =
        sessionDao.updateStatus(sessionId, status)

    override suspend fun endLocalSession(sessionId: String, isAbort: Boolean) =
        sessionDao.updateEndedAt(
            sessionId, System.currentTimeMillis(), SessionSyncStatus.COMPLETED_PENDING_UPLOAD,
            if (isAbort) PendingAction.ABORT else PendingAction.FINISH,
        )

    override fun observeFixes(sessionId: String): Flow<List<LocationFixEntity>> =
        trackingDao.observeFixes(sessionId)

    override suspend fun getUnuploadedFixes(sessionId: String): List<LocationFixEntity> =
        trackingDao.getUnuploadedFixes(sessionId)

    override suspend fun markFixesUploaded(sessionId: String, upToSeq: Int) =
        trackingDao.markFixesUploaded(sessionId, upToSeq)

    override suspend fun getPendingSessions(): List<RouteSessionEntity> =
        sessionDao.getPendingUpload()

    override fun observeAllSessions(): Flow<List<RouteSessionEntity>> =
        sessionDao.observeAll()

    override fun observePendingSessions(): Flow<List<RouteSessionEntity>> =
        sessionDao.observePending()
}
