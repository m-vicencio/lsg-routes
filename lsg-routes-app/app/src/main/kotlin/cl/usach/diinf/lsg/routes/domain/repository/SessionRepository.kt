package cl.usach.diinf.lsg.routes.domain.repository

import cl.usach.diinf.lsg.routes.domain.model.SessionSummary
import cl.usach.diinf.lsg.routes.domain.model.SessionTrack

interface SessionRepository {
    /** Crea la sesion en el backend. routeId nulo inicia un recorrido libre. */
    suspend fun createSession(routeId: String?, clientSessionUuid: String): Result<String>
    suspend fun syncBatch(sessionId: String, clientRef: String, fixSeqs: List<Int>): Result<Unit>
    suspend fun finishSession(sessionId: String): Result<SessionSummary>

    /** Cancela la sesion marcandola REJECTED (ej: el jugador no empezo a moverse). */
    suspend fun abortSession(sessionId: String): Result<SessionSummary>

    suspend fun getSummary(sessionId: String): Result<SessionSummary>

    /** Geometria del recorrido (ruta planificada + track GPS real) para visualizar en mapa. */
    suspend fun getTrack(sessionId: String): Result<SessionTrack>
}
