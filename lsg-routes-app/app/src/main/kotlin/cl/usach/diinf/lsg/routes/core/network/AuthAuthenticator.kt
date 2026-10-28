package cl.usach.diinf.lsg.routes.core.network

import cl.usach.diinf.lsg.routes.core.auth.JwtStorage
import cl.usach.diinf.lsg.routes.data.remote.service.AuthService
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import timber.log.Timber
import javax.inject.Inject

/**
 * Authenticator OkHttp que refresca el JWT ante respuestas 401.
 *
 * Usa Lazy<AuthService> para evitar una dependencia circular con el OkHttpClient.
 * Si el refresh falla, limpia el token y retorna null (forzando nuevo login).
 */
class AuthAuthenticator @Inject constructor(
    private val jwtStorage: JwtStorage,
    private val authService: Lazy<AuthService>,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.code != 401) return null

        // Un 401 del propio refresh no se arregla refrescando otra vez. Si se intenta,
        // cada nivel abre una llamada nueva (sin la cabecera de abajo, que solo protege
        // de reintentar la MISMA peticion) y bloquea un hilo del dispatcher con el
        // runBlocking de mas abajo. Al quinto anidamiento se agota maxRequestsPerHost
        // (5 por defecto en OkHttp) y el cliente entero queda en deadlock: ninguna
        // peticion posterior sale del telefono, ni siquiera el login.
        if (response.request.url.encodedPath.endsWith(REFRESH_PATH)) {
            Timber.w("Refresh rechazado con 401: se cierra la sesion")
            jwtStorage.clearToken()
            return null
        }

        // No reintentar si ya fallamos en el refresh
        if (response.request.header("X-Refresh-Attempt") != null) return null

        val newToken = runBlocking {
            try {
                val result = authService.get().refresh()
                jwtStorage.saveToken(result.accessToken)
                result.accessToken
            } catch (e: Exception) {
                Timber.w(e, "Refresh de token fallo, se requiere nuevo login")
                jwtStorage.clearToken()
                null
            }
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .header("X-Refresh-Attempt", "true")
            .build()
    }

    companion object {
        /** Cola de la ruta de AuthService.refresh(). */
        const val REFRESH_PATH = "auth/refresh"
    }
}
