package cl.usach.diinf.lsg.routes.data.repository

import cl.usach.diinf.lsg.routes.data.local.dao.TrackingDao
import cl.usach.diinf.lsg.routes.data.remote.dto.AccelBucketDto
import cl.usach.diinf.lsg.routes.data.remote.dto.CreateSessionRequest
import cl.usach.diinf.lsg.routes.data.remote.dto.FixDto
import cl.usach.diinf.lsg.routes.data.remote.dto.SessionSummaryDto
import cl.usach.diinf.lsg.routes.data.remote.dto.StepCountDto
import cl.usach.diinf.lsg.routes.data.remote.dto.SyncBatchRequest
import cl.usach.diinf.lsg.routes.data.remote.service.SessionService
import cl.usach.diinf.lsg.routes.domain.model.SessionSummary
import cl.usach.diinf.lsg.routes.domain.model.SessionTrack
import cl.usach.diinf.lsg.routes.domain.repository.SessionRepository
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

class SessionRepositoryImpl @Inject constructor(
    private val sessionService: SessionService,
    private val trackingDao: TrackingDao,
) : SessionRepository {

    override suspend fun createSession(routeId: String?, clientSessionUuid: String): Result<String> =
        runCatching {
            val response = sessionService.createSession(
                CreateSessionRequest(
                    id = clientSessionUuid, routeId = routeId, clientSessionUuid = clientSessionUuid,
                )
            )
            response.id
        }.onFailure { Timber.e(it, "Error al crear sesion en backend") }

    override suspend fun syncBatch(sessionId: String, clientRef: String, fixSeqs: List<Int>): Result<Unit> =
        runCatching {
            val fixes = trackingDao.getUnuploadedFixes(sessionId)
                .filter { it.clientSeq in fixSeqs }
                .map { fix ->
                    FixDto(
                        clientSeq = fix.clientSeq,
                        lat = fix.lat,
                        lon = fix.lon,
                        accuracyM = fix.accuracyM,
                        altitudeM = fix.altitudeM,
                        speedMps = fix.speedMps,
                        bearingDeg = fix.bearingDeg,
                        isFromMockProvider = fix.isMock,
                        capturedAt = fix.capturedAt.toIso(),
                    )
                }
            val accel = trackingDao.getUnuploadedAccel(sessionId).map { a ->
                AccelBucketDto(
                    bucketStartedAt = a.bucketStartedAt.toIso(),
                    bucketSeconds = a.bucketSeconds,
                    magnitudeMean = a.magnitudeMean,
                    magnitudeVariance = a.magnitudeVariance,
                )
            }
            val steps = trackingDao.getUnuploadedSteps(sessionId).map { s ->
                StepCountDto(stepsDelta = s.stepsDelta, capturedAt = s.capturedAt.toIso())
            }
            sessionService.syncBatch(sessionId, SyncBatchRequest(clientRef, fixes, accel, steps))
            trackingDao.markAccelUploaded(sessionId)
            trackingDao.markStepsUploaded(sessionId)
        }.onFailure { Timber.e(it, "Error al sincronizar batch") }

    override suspend fun finishSession(sessionId: String): Result<SessionSummary> =
        runCatching { sessionService.finishSession(sessionId).toDomain() }
            .onFailure { Timber.e(it, "Error al finalizar sesion") }

    override suspend fun abortSession(sessionId: String): Result<SessionSummary> =
        runCatching { sessionService.abortSession(sessionId).toDomain() }
            .onFailure { Timber.e(it, "Error al cancelar sesion") }

    override suspend fun getSummary(sessionId: String): Result<SessionSummary> =
        runCatching { sessionService.getSummary(sessionId).toDomain() }
            .onFailure { Timber.e(it, "Error al obtener resumen") }

    override suspend fun getTrack(sessionId: String): Result<SessionTrack> =
        runCatching {
            val dto = sessionService.getTrack(sessionId)
            SessionTrack(plannedGeometry = dto.plannedGeometry, fixes = dto.fixes)
        }.onFailure { Timber.e(it, "Error al obtener track de sesion") }

    private fun SessionSummaryDto.toDomain() = SessionSummary(
        sessionId = sessionId, status = status, mode = mode,
        awardedPoints = awardedPoints, adherenceRatio = adherenceRatio,
        validators = validators, syncStatus = syncStatus,
    )

    private fun Long.toIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(this))
}
