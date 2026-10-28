package cl.usach.diinf.lsg.routes.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ActivityLogDto(
    val id: Long,
    @SerializedName("session_id") val sessionId: String,
    // Nulo en recorrido libre: no hay ruta planificada que referenciar. Gson construye
    // el objeto con Unsafe y se salta la verificacion de nulidad: un tipo no anulable
    // no protegeria de nada y solo aplazaria el NullPointerException a quien lo leyera.
    @SerializedName("route_id") val routeId: String?,
    val mode: String,
    val status: String,
    @SerializedName("distance_m") val distanceM: Double,
    @SerializedName("duration_s") val durationS: Int,
    @SerializedName("avg_speed_kmh") val avgSpeedKmh: Double,
    @SerializedName("adherence_ratio") val adherenceRatio: Double?,
    @SerializedName("awarded_points") val awardedPoints: Int,
    @SerializedName("created_at") val createdAt: String,
)

data class ActivityStatsDto(
    @SerializedName("total_activities") val totalActivities: Int,
    @SerializedName("total_distance_m") val totalDistanceM: Double,
    @SerializedName("total_points") val totalPoints: Int,
    val recent: List<ActivityLogDto>,
)

data class PointDimensionBalanceDto(
    @SerializedName("id_point_dimension") val idPointDimension: Int,
    val balance: Int,
    val name: String?,
)

data class PointsBalanceDto(
    val total: Int,
    val dimensions: List<PointDimensionBalanceDto>,
)

data class PointTransactionDto(
    @SerializedName("point_dimension_id") val pointDimensionId: Int,
    val amount: Int,
    @SerializedName("balance_before") val balanceBefore: Int?,
    @SerializedName("balance_after") val balanceAfter: Int?,
    @SerializedName("source_type") val sourceType: String?,
    val reason: String?,
    @SerializedName("created_at") val createdAt: String?,
)
