package cl.usach.diinf.lsg.routes.data.repository

import cl.usach.diinf.lsg.routes.core.auth.JwtStorage
import cl.usach.diinf.lsg.routes.data.remote.dto.LoginRequest
import cl.usach.diinf.lsg.routes.data.remote.dto.TokenResponse
import cl.usach.diinf.lsg.routes.data.remote.dto.UserResponse
import cl.usach.diinf.lsg.routes.data.remote.service.AuthService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRepositoryTest {

    private lateinit var authService: AuthService
    private lateinit var jwtStorage: JwtStorage
    private lateinit var repository: AuthRepositoryImpl

    private val mockTokenResponse = TokenResponse(
        accessToken = "test_token_abc",
        tokenType = "bearer",
        expiresAt = "2099-12-31T23:59:59+00:00",
    )

    @Before
    fun setup() {
        authService = mockk()
        jwtStorage = mockk(relaxed = true)
        repository = AuthRepositoryImpl(authService, jwtStorage)
    }

    @Test
    fun `login success stores token and returns success`() = runTest {
        coEvery { authService.login(any()) } returns mockTokenResponse

        val result = repository.login("test@usach.cl", "secret")

        assertTrue(result.isSuccess)
        assertEquals("test_token_abc", result.getOrNull())
        verify { jwtStorage.saveToken("test_token_abc") }
    }

    @Test
    fun `login failure returns failure result`() = runTest {
        coEvery { authService.login(any()) } throws RuntimeException("Network error")

        val result = repository.login("bad@usach.cl", "wrong")

        assertTrue(result.isFailure)
    }

    @Test
    fun `getMe maps dto to domain model correctly`() = runTest {
        val mockUserDto = UserResponse(id = 1, idPlayers = 46, name = "Test Player", email = "t@usach.cl", lastSyncAt = null)
        coEvery { authService.getMe() } returns mockUserDto

        val result = repository.getMe()

        assertTrue(result.isSuccess)
        result.getOrNull()!!.let { user ->
            assertEquals(46, user.idPlayers)
            assertEquals("Test Player", user.name)
            assertEquals("t@usach.cl", user.email)
        }
    }

    @Test
    fun `logout clears stored token`() {
        repository.logout()
        verify { jwtStorage.clearToken() }
    }
}
