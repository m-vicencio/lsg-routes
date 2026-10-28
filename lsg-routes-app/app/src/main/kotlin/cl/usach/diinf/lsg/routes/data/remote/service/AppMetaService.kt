package cl.usach.diinf.lsg.routes.data.remote.service

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET

data class AppVersionResponse(
    val latest: String,
    val url: String,
    val sha256: String,
    @SerializedName("minimum_required") val minimumRequired: String,
    // Un backend viejo no manda el campo: Gson deja el default y la bicicleta sigue oculta.
    @SerializedName("bike_activate") val bikeActivate: Boolean = false,
)

interface AppMetaService {
    @GET("lsg-routes/app/version")
    suspend fun getAppVersion(): AppVersionResponse
}
