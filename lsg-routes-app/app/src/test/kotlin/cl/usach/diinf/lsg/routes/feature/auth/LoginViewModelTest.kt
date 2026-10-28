package cl.usach.diinf.lsg.routes.feature.auth

import app.cash.turbine.test
import cl.usach.diinf.lsg.routes.domain.repository.AuthRepository
import cl.usach.diinf.lsg.routes.feature.auth.ui.LoginError
import cl.usach.diinf.lsg.routes.feature.auth.ui.LoginUiState
import cl.usach.diinf.lsg.routes.feature.auth.ui.LoginViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // relaxed: el init del ViewModel lee getSavedCredentials() y el exito escribe
        // credenciales; nada de eso es lo que se esta probando aqui.
        authRepository = mockk(relaxed = true)
        // El relaxed devolveria un Pair<Object, Object> (el genérico se borra) y el init
        // revienta con ClassCastException al desestructurarlo como String.
        every { authRepository.getSavedCredentials() } returns null
        viewModel = LoginViewModel(authRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** login() no recibe argumentos: lee el formulario que la pantalla fue llenando. */
    private fun fillForm(email: String, password: String) {
        viewModel.onEmailChange(email)
        viewModel.onPasswordChange(password)
    }

    @Test
    fun `login success emits Loading then Success`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns Result.success("token_abc")
        fillForm("test@usach.cl", "secret")

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())
            viewModel.login()
            assertEquals(LoginUiState.Loading, awaitItem())
            assertEquals(LoginUiState.Success, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `login with network error emits Error with NETWORK type`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns
            Result.failure(java.io.IOException("timeout"))
        fillForm("test@usach.cl", "secret")

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.login()
            awaitItem() // Loading
            val errorState = awaitItem()
            assertTrue(errorState is LoginUiState.Error)
            assertEquals(LoginError.NETWORK, (errorState as LoginUiState.Error).messageRes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `login with blank email does not trigger network call`() = runTest {
        fillForm("", "secret")

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.login()
            // Sin emisiones adicionales
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
