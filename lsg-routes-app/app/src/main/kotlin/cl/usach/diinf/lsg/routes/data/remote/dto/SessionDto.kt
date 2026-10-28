package cl.usach.diinf.lsg.routes.data.remote.dto

import com.google.gson.annotations.SerializedName

/** routeId nulo = recorrido libre (sin ruta planificada). id es el mismo que ya
 *  se uso como client_session_uuid: el backend lo adopta como su propia clave
 *  primaria para que la telemetria guardada localmente no necesite remapeo. */
data class CreateSessionRequest(
    val id: String,
    @SerializedName("route_id") val routeId: String?,
    @SerializedName("client_session_uuid") val clientSessionUuid: String,
)

data class SessionResponse(
    val id: String,
    @SerializedName("route_id") val routeId: String?,
    @SerializedName("id_players") val idPlayers: Int,
    val status: String,
    val mode: String,
    @SerializedName("client_session_uuid") val clientSessionUuid: String,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("ended_at") val endedAt: String?,
)

data class FixDto(
    @SerializedName("client_seq") val clientSeq: Int,
    val lat: Double,
    val lon: Double,
    @SerializedName("accuracy_m") val accuracyM: Float?,
    @SerializedName("altitude_m") val altitudeM: Double?,
    @SerializedName("speed_mps") val speedMps: Float?,
    @SerializedName("bearing_deg") val bearingDeg: Float?,
    @SerializedName("is_from_mock_provider") val isFromMockProvider: Boolean,
    @SerializedName("captured_at") val capturedAt: String,
)

data class AccelBucketDto(
    @SerializedName("bucket_started_at") val bucketStartedAt: String,
    @SerializedName("bucket_seconds") val bucketSeconds: Int,
    @SerializedName("magnitude_mean") val magnitudeMean: Float,
    @SerializedName("magnitude_variance") val magnitudeVariance: Float,
)

data class StepCountDto(
    @SerializedName("steps_delta") val stepsDelta: Int,
    @SerializedName("captured_at") val capturedAt: String,
)

data class SyncBatchRequest(
    @SerializedName("client_ref") val clientRef: String,
    val fixes: List<FixDto> = emptyList(),
    @SerializedName("accel_buckets") val accelBuckets: List<AccelBucketDto> = emptyList(),
    @SerializedName("step_counts") val stepCounts: List<StepCountDto> = emptyList(),
)

data class SyncBatchResponse(
    @SerializedName("client_ref") val clientRef: String,
    val status: String,
    @SerializedName("fixes_stored") val fixesStored: Int = 0,
)

data class SessionSummaryDto(
    @SerializedName("session_id") val sessionId: String,
    val status: String,
    val mode: String,
    @SerializedName("awarded_points") val awardedPoints: Int?,
    @SerializedName("adherence_ratio") val adherenceRatio: Float?,
    val validators: Map<String, String>,
    @SerializedName("sync_status") val syncStatus: String?,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("ended_at") val endedAt: String?,
)

data class SessionTrackDto(
    @SerializedName("session_id") val sessionId: String,
    val mode: String,
    val status: String,
    @SerializedName("planned_geometry") val plannedGeometry: List<List<Double>>,
    val fixes: List<List<Double>>,
)
