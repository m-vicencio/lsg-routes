package cl.usach.diinf.lsg.routes.domain.model

/** Una actividad finalizada del historial del jugador. */
data class ActivityItem(
    val sessionId: String,
    val mode: ActivityMode,
    val status: String,
    val distanceM: Double,
    val durationS: Int,
    val avgSpeedKmh: Double,
    val awardedPoints: Int,
    val createdAt: String,
) {
    val isRejected: Boolean get() = status == "REJECTED"
}

/** Totales del jugador + ultimas actividades para el perfil. */
data class ActivityStats(
    val totalActivities: Int,
    val totalDistanceM: Double,
    val totalPoints: Int,
    val recent: List<ActivityItem>,
)

/** Saldo de puntos del perfil del jugador en LSG-Core, por dimension. */
data class DimensionBalance(
    val idPointDimension: Int,
    val balance: Int,
    /** Nombre que da LSG-Core; null si la instancia es anterior a 1.2.3. */
    val name: String?,
)

/** Saldo global del jugador en LSG-Core (todas las dimensiones). */
data class PointsBalance(
    val total: Int,
    val dimensions: List<DimensionBalance>,
) {
    /**
     * Nombres de dimension por id, para etiquetar tambien las transacciones:
     * el ledger de LSG-Core solo trae el id, y este saldo es la unica fuente
     * de nombres del sistema.
     */
    val dimensionNames: Map<Int, String>
        get() = dimensions.mapNotNull { d -> d.name?.let { d.idPointDimension to it } }.toMap()
}

/** Una transaccion de puntos otorgados en LSG-Core, por dimension. */
data class PointTransaction(
    val pointDimensionId: Int,
    val amount: Int,
    val balanceBefore: Int?,
    val balanceAfter: Int?,
    val sourceType: String?,
    val reason: String?,
    val createdAt: String?,
)
