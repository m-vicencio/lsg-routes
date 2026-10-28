package cl.usach.diinf.lsg.routes.feature.session.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cl.usach.diinf.lsg.routes.core.auth.TokenRefresher
import cl.usach.diinf.lsg.routes.data.local.dao.TrackingDao
import cl.usach.diinf.lsg.routes.data.local.entity.SessionSyncStatus
import cl.usach.diinf.lsg.routes.data.local.dao.RouteSessionDao
import cl.usach.diinf.lsg.routes.domain.repository.LocalTrackingRepository
import cl.usach.diinf.lsg.routes.domain.repository.SessionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.UUID

/** Worker que sube los datos de telemetria al backend y llama al endpoint /finish. */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessionRepository: SessionRepository,
    private val localTrackingRepository: LocalTrackingRepository,
    private val trackingDao: TrackingDao,
    private val tokenRefresher: TokenRefresher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)
            ?: return Result.failure()
        val isAbort = inputData.getBoolean(KEY_ABORT, false)

        Timber.d("UploadWorker iniciado para sesion $sessionId (abort=$isAbort)")
        // El worker puede arrancar horas despues de la ruta (reintento de WorkManager):
        // se renueva el token antes de subir, mientras todavia se puede.
        tokenRefresher.refreshIfExpiringSoon()

        // La sesion pudo arrancar sin conexion (tracking offline-first): recien aqui se
        // crea en el backend, con el mismo id generado en el cliente. Idempotente por
        // client_session_uuid, asi que repetir esta llamada en cada reintento es seguro.
        val routeId = localTrackingRepository.getLocalSession(sessionId)?.routeId?.takeIf { it.isNotBlank() }
        sessionRepository.createSession(routeId, sessionId).onFailure {
            Timber.e(it, "Fallo al crear sesion en backend")
            localTrackingRepository.updateSessionStatus(sessionId, SessionSyncStatus.UPLOAD_FAILED)
            return Result.retry()
        }

        if (isAbort) {
            return sessionRepository.abortSession(sessionId).fold(
                onSuccess = {
                    localTrackingRepository.updateSessionStatus(sessionId, SessionSyncStatus.SYNCED)
                    Result.success()
                },
                onFailure = {
                    Timber.e(it, "Fallo al cancelar sesion")
                    localTrackingRepository.updateSessionStatus(sessionId, SessionSyncStatus.UPLOAD_FAILED)
                    Result.retry()
                },
            )
        }

        localTrackingRepository.updateSessionStatus(sessionId, SessionSyncStatus.UPLOADING)

        // Subir fixes pendientes en lotes de 100
        val unuploaded = localTrackingRepository.getUnuploadedFixes(sessionId)
        unuploaded.chunked(BATCH_SIZE).forEachIndexed { index, batch ->
            val seqs = batch.map { it.clientSeq }
            // Determinista por sesion y rango de fixes: si el backend guardo el lote pero la
            // respuesta se perdio, el reintento manda el mismo client_ref y recibe DUPLICATE.
            // Los fixes tambien se deduplican por client_seq, pero acelerometro y pasos no
            // tienen clave propia en el backend: solo esto evita insertarlos dos veces.
            val clientRef = UUID.nameUUIDFromBytes(
                "$sessionId:${seqs.first()}-${seqs.last()}".toByteArray()
            ).toString()
            sessionRepository.syncBatch(sessionId, clientRef, seqs)
                .onSuccess { localTrackingRepository.markFixesUploaded(sessionId, seqs.max()) }
                .onFailure {
                    Timber.e(it, "Fallo sincronizacion del lote $index")
                    localTrackingRepository.updateSessionStatus(sessionId, SessionSyncStatus.UPLOAD_FAILED)
                    return Result.retry()
                }
        }

        sessionRepository.finishSession(sessionId)
            .onSuccess {
                localTrackingRepository.updateSessionStatus(sessionId, SessionSyncStatus.SYNCED)
                Timber.d("Sesion $sessionId sincronizada exitosamente")
            }
            .onFailure {
                Timber.e(it, "Fallo POST /finish para sesion $sessionId")
                localTrackingRepository.updateSessionStatus(sessionId, SessionSyncStatus.UPLOAD_FAILED)
                return Result.retry()
            }

        return Result.success()
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_ABORT = "abort"
        private const val BATCH_SIZE = 100
    }
}
