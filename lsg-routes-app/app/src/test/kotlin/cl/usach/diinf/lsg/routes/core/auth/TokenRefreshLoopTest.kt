package cl.usach.diinf.lsg.routes.core.auth

import cl.usach.diinf.lsg.routes.core.network.AuthAuthenticator
import cl.usach.diinf.lsg.routes.data.remote.dto.TokenResponse
import cl.usach.diinf.lsg.routes.data.remote.service.AuthService
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.io.IOException
import java.util.Base64

/**
 * Un 401 del refresh no puede provocar otro refresh.
 *
 * Si lo provoca, cada nivel bloquea un hilo del dispatcher de OkHttp con runBlocking y
 * al quinto anidamiento se agota maxRequestsPerHost (5 por defecto): el cliente queda en
 * deadlock y la app no vuelve a emitir NINGUNA peticion, ni siquiera el login. En el
 * backend se ve como varios POST /auth/refresh por arranque y ningun POST /auth/login.
 */
class TokenRefreshLoopTest {

    private lateinit var authService: AuthService
    private lateinit var jwtStorage: JwtStorage

    /** JWT de mentira ya caducado: la firma nunca se valida aqui. */
    private val expiredToken: String = Base64.getUrlEncoder().withoutPadding().let { encoder ->
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"42","exp":1}""".toByteArray())
        "$header.$payload.firma-irrelevante"
    }

    @Before
    fun setup() {
        authService = mockk()
        jwtStorage = mockk(relaxed = true)
    }

    private fun unauthorized(url: String) = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Unauthorized")
        .build()

    private fun http401() = HttpException(
        retrofit2.Response.error<Any>(401, "".toResponseBody("application/json".toMediaType()))
    )

    // AuthAuthenticator

    @Test
    fun `un 401 del refresh no dispara otro refresh`() {
        val authenticator = AuthAuthenticator(jwtStorage, Lazy { authService })

        val retry = authenticator.authenticate(
            null,
            unauthorized("http://host/lsg-routes/auth/refresh"),
        )

        assertNull("no debe reintentar el refresh", retry)
        coVerify(exactly = 0) { authService.refresh() }
        verify { jwtStorage.clearToken() }
    }

    @Test
    fun `un 401 de una peticion normal si refresca y reintenta una vez`() {
        coEvery { authService.refresh() } returns TokenResponse(
            accessToken = "token_nuevo",
            tokenType = "bearer",
            expiresAt = "2099-12-31T23:59:59+00:00",
        )
        val authenticator = AuthAuthenticator(jwtStorage, Lazy { authService })

        val retry = authenticator.authenticate(
            null,
            unauthorized("http://host/lsg-routes/routes"),
        )

        assertNotNull(retry)
        assertEquals("Bearer token_nuevo", retry!!.header("Authorization"))
        coVerify(exactly = 1) { authService.refresh() }
    }

    // TokenRefresher

    @Test
    fun `el refresco proactivo cierra la sesion ante un 401`() = runTest {
        every { jwtStorage.getToken() } returns expiredToken
        coEvery { authService.refresh() } throws http401()

        val result = TokenRefresher(jwtStorage, Lazy { authService }).refreshIfExpiringSoon()

        assertNull("un token que LSG-Auth rechazo no sirve de nada", result)
        verify { jwtStorage.clearToken() }
    }

    @Test
    fun `una caida de red conserva el token`() = runTest {
        every { jwtStorage.getToken() } returns expiredToken
        coEvery { authService.refresh() } throws IOException("sin conexion")

        val result = TokenRefresher(jwtStorage, Lazy { authService }).refreshIfExpiringSoon()

        assertEquals(expiredToken, result)
        verify(exactly = 0) { jwtStorage.clearToken() }
    }
}
