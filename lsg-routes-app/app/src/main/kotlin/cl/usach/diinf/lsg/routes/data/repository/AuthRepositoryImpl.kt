package cl.usach.diinf.lsg.routes.data.repository

import cl.usach.diinf.lsg.routes.core.auth.JwtStorage
import cl.usach.diinf.lsg.routes.data.remote.dto.LoginRequest
import cl.usach.diinf.lsg.routes.data.remote.service.AuthService
import cl.usach.diinf.lsg.routes.domain.model.User
import cl.usach.diinf.lsg.routes.domain.repository.AuthRepository
import timber.log.Timber
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val authService: AuthService,
    private val jwtStorage: JwtStorage,
) : AuthRepository {

    override suspend fun login(email: String, password: String): Result<String> = runCatching {
        val response = authService.login(LoginRequest(email, password))
        jwtStorage.saveToken(response.accessToken)
        response.accessToken
    }.onFailure { Timber.e(it, "Error en login") }

    override suspend fun getMe(): Result<User> = runCatching {
        val dto = authService.getMe()
        User(id = dto.id, idPlayers = dto.idPlayers, name = dto.name, email = dto.email)
    }.onFailure { Timber.e(it, "Error al obtener perfil") }

    override fun logout() = jwtStorage.clearToken()

    override fun isLoggedIn(): Boolean = jwtStorage.getToken() != null

    override fun saveCredentials(email: String, password: String) =
        jwtStorage.saveCredentials(email, password)

    override fun getSavedCredentials(): Pair<String, String>? =
        jwtStorage.getCredentials()

    override fun clearCredentials() = jwtStorage.clearCredentials()
}
