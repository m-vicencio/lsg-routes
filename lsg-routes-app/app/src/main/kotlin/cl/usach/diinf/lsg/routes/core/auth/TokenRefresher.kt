package cl.usach.diinf.lsg.routes.core.auth

import cl.usach.diinf.lsg.routes.data.remote.service.AuthService
import dagger.Lazy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import timber.log.Timber
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renueva el token mientras todavia vale, en vez de esperar a que muera.
 *
 * LSG-Auth no emite refresh token durable: POST /token/refresh exige el Bearer vigente.
 * Por eso reaccionar al 401 llega tarde -- para entonces ya no hay nada que renovar y el
 * jugador termina desconectado con la ruta sin subir. El AuthAuthenticator sigue en su
 * sitio como red de seguridad para los 401 que se escapen.
 *
 * Usa Lazy<AuthService> para no cerrar el ciclo con el OkHttpClient, igual que el
 * AuthAuthenticator.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val jwtStorage: JwtStorage,
    private val authService: Lazy<AuthService>,
) {
    private val mutex = Mutex()

    /**
     * Renueva el token si le quedan menos de [marginSeconds] de vida.
     *
     * Devuelve el token vigente: el nuevo si se renovo, el anterior si el intento fallo
     * por red, o null si la sesion murio. Una caida de red no es motivo para cerrar la
     * sesion del jugador y el token anterior puede seguir sirviendo, pero un 401 sí lo
     * es: LSG-Auth ya rechazo ese token, no hay nada que recuperar. Conservarlo hacia
     * que cada arranque repitiera el intento contra un token muerto.
     */
    suspend fun refreshIfExpiringSoon(marginSeconds: Long = DEFAULT_MARGIN_S): String? {
        val token = jwtStorage.getToken() ?: return null
        if (!JwtExpiry.expiresWithin(token, marginSeconds)) return token

        return mutex.withLock {
            // Otra llamada pudo renovarlo mientras se esperaba el lock
            val current = jwtStorage.getToken() ?: return@withLock null
            if (!JwtExpiry.expiresWithin(current, marginSeconds)) return@withLock current

            try {
                val renewed = authService.get().refresh().accessToken
                jwtStorage.saveToken(renewed)
                Timber.d("Token renovado antes de caducar")
                renewed
            } catch (e: HttpException) {
                if (e.code() == HTTP_UNAUTHORIZED) {
                    Timber.w("Refresh rechazado con 401: el token ya no sirve, se cierra la sesion")
                    jwtStorage.clearToken()
                    null
                } else {
                    Timber.w(e, "Refresco proactivo fallido; se sigue con el token actual")
                    current
                }
            } catch (e: Exception) {
                Timber.w(e, "Refresco proactivo fallido; se sigue con el token actual")
                current
            }
        }
    }

    companion object {
        /** Margen de renovacion sobre un token de 120 min. */
        const val DEFAULT_MARGIN_S = 15 * 60L
    }
}
