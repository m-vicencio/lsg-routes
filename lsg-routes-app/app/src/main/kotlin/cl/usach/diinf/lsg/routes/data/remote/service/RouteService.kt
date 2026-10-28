package cl.usach.diinf.lsg.routes.data.remote.service

import cl.usach.diinf.lsg.routes.data.remote.dto.CreateRouteRequest
import cl.usach.diinf.lsg.routes.data.remote.dto.PublicRouteDto
import cl.usach.diinf.lsg.routes.data.remote.dto.RouteResponse
import cl.usach.diinf.lsg.routes.data.remote.dto.VisibilityRequest
import cl.usach.diinf.lsg.routes.data.remote.dto.WeatherDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface RouteService {
    @GET("lsg-routes/routes")
    suspend fun getRoutes(): List<RouteResponse>

    @GET("lsg-routes/routes/{id}")
    suspend fun getRoute(@Path("id") id: String): RouteResponse

    @GET("lsg-routes/routes/{id}/weather")
    suspend fun getRouteWeather(@Path("id") id: String): WeatherDto

    @POST("lsg-routes/routes")
    suspend fun createRoute(@Body request: CreateRouteRequest): RouteResponse

    @DELETE("lsg-routes/routes/{id}")
    suspend fun deleteRoute(@Path("id") id: String)

    // Compartir rutas (feed comunitario)
    @GET("lsg-routes/routes/public")
    suspend fun getPublicRoutes(
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): List<PublicRouteDto>

    @POST("lsg-routes/routes/{id}/copy")
    suspend fun copyRoute(@Path("id") id: String): RouteResponse

    @PATCH("lsg-routes/routes/{id}/visibility")
    suspend fun setVisibility(@Path("id") id: String, @Body body: VisibilityRequest): RouteResponse
}
