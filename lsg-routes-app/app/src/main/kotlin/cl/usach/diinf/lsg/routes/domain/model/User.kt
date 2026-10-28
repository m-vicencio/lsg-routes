package cl.usach.diinf.lsg.routes.domain.model

/** Modelo de dominio del jugador autenticado. */
data class User(
    val id: Int,
    val idPlayers: Int,
    val name: String,
    val email: String,
)
