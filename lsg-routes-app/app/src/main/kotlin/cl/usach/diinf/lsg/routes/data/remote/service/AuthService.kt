package cl.usach.diinf.lsg.routes.data.remote.service

import cl.usach.diinf.lsg.routes.data.remote.dto.LoginRequest
import cl.usach.diinf.lsg.routes.data.remote.dto.TokenResponse
import cl.usach.diinf.lsg.routes.data.remote.dto.UserResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthService {
    @POST("lsg-routes/auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @POST("lsg-routes/auth/refresh")
    suspend fun refresh(): TokenResponse

    @GET("lsg-routes/auth/me")
    suspend fun getMe(): UserResponse
}
