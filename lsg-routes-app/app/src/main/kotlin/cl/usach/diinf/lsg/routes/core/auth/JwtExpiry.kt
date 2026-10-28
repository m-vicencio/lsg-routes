package cl.usach.diinf.lsg.routes.core.auth

import java.util.Base64

/**
 * Lectura del claim `exp` de un JWT, sin validar la firma.
 *
 * Solo sirve para decidir cuando renovar; quien valida de verdad es LSG-Auth en cada
 * peticion. Leer el claim no cuesta una llamada de red, que es justo lo que se quiere
 * evitar aqui.
 */
object JwtExpiry {

    // El payload es JSON plano y solo interesa un entero: basta una expresion regular.
    private val EXP_CLAIM = """"exp"\s*:\s*(\d+)""".toRegex()

    /** Epoch en segundos en que caduca el token, o null si el claim no se puede leer. */
    fun epochSeconds(token: String): Long? = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return null
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        EXP_CLAIM.find(json)?.groupValues?.get(1)?.toLong()
    }.getOrNull()

    /**
     * true si el token caduca dentro de [marginSeconds], o si ya caduco.
     *
     * Sin claim `exp` legible devuelve false: no se renueva a ciegas en cada peticion.
     * Para ese caso queda el AuthAuthenticator, que reacciona al 401.
     */
    fun expiresWithin(
        token: String,
        marginSeconds: Long,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        val exp = epochSeconds(token) ?: return false
        return exp - nowEpochSeconds <= marginSeconds
    }
}
