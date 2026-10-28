package cl.usach.diinf.lsg.routes.domain.repository

import cl.usach.diinf.lsg.routes.domain.model.User

interface AuthRepository {
    suspend fun login(email: String, password: String): Result<String>
    suspend fun getMe(): Result<User>
    fun logout()
    fun isLoggedIn(): Boolean
    fun saveCredentials(email: String, password: String)
    fun getSavedCredentials(): Pair<String, String>?
    fun clearCredentials()
}
