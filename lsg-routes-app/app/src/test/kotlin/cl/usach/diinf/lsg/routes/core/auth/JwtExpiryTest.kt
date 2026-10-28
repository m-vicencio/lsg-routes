package cl.usach.diinf.lsg.routes.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class JwtExpiryTest {

    private val now = 1_800_000_000L
    private val margin = TokenRefresher.DEFAULT_MARGIN_S  // 15 min

    /** JWT de mentira: solo importa el payload, la firma nunca se valida aqui. */
    private fun tokenExpiringAt(exp: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"42","exp":$exp}""".toByteArray())
        return "$header.$payload.firma-irrelevante"
    }

    @Test
    fun `lee el claim exp`() {
        assertEquals(now + 7200, JwtExpiry.epochSeconds(tokenExpiringAt(now + 7200)))
    }

    @Test
    fun `token recien emitido no necesita renovacion`() {
        val token = tokenExpiringAt(now + 7200)  // 120 min por delante

        assertFalse(JwtExpiry.expiresWithin(token, margin, now))
    }

    @Test
    fun `token por caducar necesita renovacion`() {
        val token = tokenExpiringAt(now + 5 * 60)  // 5 min por delante

        assertTrue(JwtExpiry.expiresWithin(token, margin, now))
    }

    @Test
    fun `token ya caducado necesita renovacion`() {
        val token = tokenExpiringAt(now - 60)

        assertTrue(JwtExpiry.expiresWithin(token, margin, now))
    }

    @Test
    fun `justo en el limite del margen se renueva`() {
        val token = tokenExpiringAt(now + margin)

        assertTrue(JwtExpiry.expiresWithin(token, margin, now))
    }

    @Test
    fun `token ilegible no dispara renovacion`() {
        assertNull(JwtExpiry.epochSeconds("esto-no-es-un-jwt"))
        assertFalse(JwtExpiry.expiresWithin("esto-no-es-un-jwt", margin, now))
    }

    @Test
    fun `payload sin claim exp no dispara renovacion`() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val token = "h." + encoder.encodeToString("""{"sub":"42"}""".toByteArray()) + ".f"

        assertNull(JwtExpiry.epochSeconds(token))
        assertFalse(JwtExpiry.expiresWithin(token, margin, now))
    }
}
