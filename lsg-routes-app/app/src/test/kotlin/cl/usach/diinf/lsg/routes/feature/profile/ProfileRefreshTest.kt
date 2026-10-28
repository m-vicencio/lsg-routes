package cl.usach.diinf.lsg.routes.feature.profile

import app.cash.turbine.test
import cl.usach.diinf.lsg.routes.core.ui.theme.ThemePreferences
import cl.usach.diinf.lsg.routes.domain.model.ActivityStats
import cl.usach.diinf.lsg.routes.domain.model.User
import cl.usach.diinf.lsg.routes.domain.repository.ActivityRepository
import cl.usach.diinf.lsg.routes.domain.repository.AuthRepository
import cl.usach.diinf.lsg.routes.feature.profile.ui.ProfileUiState
import cl.usach.diinf.lsg.routes.feature.profile.ui.ProfileViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El perfil sobrevive a los cambios de pestaña (saveState/restoreState) y su init no se
 * repite, asi que refresh() es la unica forma de ver una actividad recien sincronizada
 * sin reiniciar la app. Si deja de pedir los datos de nuevo, el perfil queda
 * desactualizado sin que nada mas se rompa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var authRepository: AuthRepository
    private lateinit var activityRepository: ActivityRepository
    private lateinit var themePreferences: ThemePreferences

    private fun stats(total: Int) = ActivityStats(
        totalActivities = total,
        totalDistanceM = 0.0,
        totalPoints = 0,
        recent = emptyList(),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        authRepository = mockk()
        activityRepository = mockk()
        themePreferences = mockk(relaxed = true)
        coEvery { authRepository.getMe() } returns Result.success(mockk<User>())
        coEvery { activityRepository.getPointsBalance() } returns Result.failure(Exception())
        coEvery { activityRepository.getTransactions(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refrescar vuelve a pedir la actividad y muestra la nueva`() = runTest {
        coEvery { activityRepository.getMyActivity(any()) } returns Result.success(stats(1))
        val viewModel = ProfileViewModel(authRepository, activityRepository, themePreferences)

        viewModel.uiState.test {
            assertEquals(ProfileUiState.Loading, awaitItem())
            assertEquals(1, (awaitItem() as ProfileUiState.Success).stats?.totalActivities)

            // Llega una actividad nueva al backend mientras la pantalla sigue viva
            coEvery { activityRepository.getMyActivity(any()) } returns Result.success(stats(2))
            viewModel.refresh()
            assertEquals(2, (awaitItem() as ProfileUiState.Success).stats?.totalActivities)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `el indicador del gesto se enciende y se apaga`() = runTest {
        coEvery { activityRepository.getMyActivity(any()) } returns Result.success(stats(1))
        val viewModel = ProfileViewModel(authRepository, activityRepository, themePreferences)

        viewModel.isRefreshing.test {
            assertFalse(awaitItem())
            viewModel.refresh()
            assertTrue("el indicador debe encenderse al refrescar", awaitItem())
            // Sin el finally, un fallo de red dejaria el indicador girando para siempre
            assertFalse("el indicador debe apagarse al terminar", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `el indicador se apaga tambien cuando la carga falla`() = runTest {
        coEvery { activityRepository.getMyActivity(any()) } returns Result.success(stats(1))
        val viewModel = ProfileViewModel(authRepository, activityRepository, themePreferences)

        viewModel.isRefreshing.test {
            assertFalse(awaitItem())
            coEvery { authRepository.getMe() } returns Result.failure(Exception("sin red"))
            viewModel.refresh()
            assertTrue(awaitItem())
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
