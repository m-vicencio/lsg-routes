package cl.usach.diinf.lsg.routes.data.remote.dto

import com.google.gson.annotations.SerializedName

data class CreateRouteRequest(
    val name: String,
    val mode: String,
    val origin: String,
    val geometry: List<List<Double>>? = null,
    @SerializedName("start_latlon") val startLatlon: List<Double>? = null,
    @SerializedName("end_latlon") val endLatlon: List<Double>? = null,
)

data class RouteResponse(
    val id: String,
    @SerializedName("id_players") val idPlayers: Int,
    val name: String,
    val mode: String,
    val geometry: List<List<Double>>,
    @SerializedName("planned_distance_m") val plannedDistanceM: Double?,
    @SerializedName("planned_duration_s") val plannedDurationS: Int?,
    val origin: String,
    @SerializedName("is_public") val isPublic: Boolean = false,
    @SerializedName("shared_by_name") val sharedByName: String? = null,
    @SerializedName("hide_start_m") val hideStartM: Int = 0,
    @SerializedName("hide_end_m") val hideEndM: Int = 0,
    @SerializedName("created_at") val createdAt: String,
)

data class PublicRouteDto(
    val id: String,
    val name: String,
    val mode: String,
    val geometry: List<List<Double>>,
    @SerializedName("planned_distance_m") val plannedDistanceM: Double?,
    @SerializedName("planned_duration_s") val plannedDurationS: Int?,
    val origin: String,
    @SerializedName("shared_by_name") val sharedByName: String?,
    @SerializedName("is_mine") val isMine: Boolean = false,
    @SerializedName("created_at") val createdAt: String,
)

data class VisibilityRequest(
    @SerializedName("is_public") val isPublic: Boolean,
    @SerializedName("hide_start_m") val hideStartM: Int = 0,
    @SerializedName("hide_end_m") val hideEndM: Int = 0,
)

data class WeatherDto(
    val latitude: Double,
    val longitude: Double,
    @SerializedName("temperature_c") val temperatureC: Double?,
    @SerializedName("apparent_temperature_c") val apparentTemperatureC: Double?,
    @SerializedName("humidity_pct") val humidityPct: Int?,
    @SerializedName("precipitation_mm") val precipitationMm: Double?,
    @SerializedName("weather_code") val weatherCode: Int?,
    @SerializedName("wind_speed_kmh") val windSpeedKmh: Double?,
    @SerializedName("is_day") val isDay: Boolean,
)
