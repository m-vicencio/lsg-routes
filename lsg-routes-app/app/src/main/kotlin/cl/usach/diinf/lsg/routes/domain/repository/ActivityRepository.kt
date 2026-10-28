package cl.usach.diinf.lsg.routes.domain.repository

import cl.usach.diinf.lsg.routes.domain.model.ActivityStats
import cl.usach.diinf.lsg.routes.domain.model.PointTransaction
import cl.usach.diinf.lsg.routes.domain.model.PointsBalance

interface ActivityRepository {
    /** Historial de actividad del jugador (mas reciente primero). Sin limit -> todas. */
    suspend fun getMyActivity(limit: Int? = null): Result<ActivityStats>

    /** Saldo de puntos del perfil del jugador en LSG-Core (todas las dimensiones). */
    suspend fun getPointsBalance(): Result<PointsBalance>

    /** Historial de transacciones de puntos otorgados en LSG-Core. */
    suspend fun getTransactions(limit: Int = 20): Result<List<PointTransaction>>
}
