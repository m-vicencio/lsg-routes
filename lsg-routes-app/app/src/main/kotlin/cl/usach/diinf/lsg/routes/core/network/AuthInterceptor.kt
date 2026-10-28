package cl.usach.diinf.lsg.routes.core.network

import cl.usach.diinf.lsg.routes.core.auth.JwtStorage
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/** Interceptor OkHttp que inyecta el JWT en el header Authorization. */
class AuthInterceptor @Inject constructor(
    private val jwtStorage: JwtStorage,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = jwtStorage.getToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
