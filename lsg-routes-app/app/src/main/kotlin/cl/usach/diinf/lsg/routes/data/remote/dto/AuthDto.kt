package cl.usach.diinf.lsg.routes.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val email: String,
    val password: String,
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_at") val expiresAt: String,
)

data class UserResponse(
    val id: Int,
    @SerializedName("id_players") val idPlayers: Int,
    val name: String,
    val email: String,
    @SerializedName("last_sync_at") val lastSyncAt: String?,
)
