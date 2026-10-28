package cl.usach.diinf.lsg.routes.feature.routes

import app.cash.turbine.test
import cl.usach.diinf.lsg.routes.domain.model.PublicRoute
import cl.usach.diinf.lsg.routes.domain.model.Route
import cl.usach.diinf.lsg.routes.domain.repository.LocalTrackingRepository
import cl.usach.diinf.lsg.routes.domain.repository.RouteRepository
import cl.usach.diinf.lsg.routes.feature.explore.ui.ExploreUiState
import cl.usach.diinf.lsg.routes.feature.explore.ui.ExploreViewModel
import cl.usach.diinf.lsg.routes.feature.routes.ui.RoutesListUiState
import cl.usach.diinf.lsg.routes.feature.routes.ui.RoutesListViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Las dos listas con barra inferior se recargan en ON_RESUME, o sea en cada cambio de
 * pestaña. Si la recarga pasa por el estado Loading, la pantalla parpadea (contenido,
 * spinner, contenido). Estos tests fallan si la recarga emite Loading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListRefreshTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var routeRepository: RouteRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        routeRepository = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Explorar no vuelve a Loading al recargar con contenido en pantalla`() = runTest {
        val first = mockk<PublicRoute>()
        val second = mockk<PublicRoute>()
        coEvery { routeRepository.getPublicRoutes() } returns Result.success(listOf(first))
        val viewModel = ExploreViewModel(routeRepository)

        viewModel.uiState.test {
            assertEquals(ExploreUiState.Loading, awaitItem())  // estado inicial, sin carga en init
            viewModel.load()
            assertEquals(ExploreUiState.Success(listOf(first)), awaitItem())

            // Recarga: la siguiente emision debe ser el contenido nuevo, nunca Loading
            coEvery { routeRepository.getPublicRoutes() } returns Result.success(listOf(first, second))
            viewModel.load()
            assertEquals(ExploreUiState.Success(listOf(first, second)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Rutas no vuelve a Loading al recargar con contenido en pantalla`() = runTest {
        val first = mockk<Route>()
        val second = mockk<Route>()
        coEvery { routeRepository.getRoutes() } returns Result.success(listOf(first))
        val localTrackingRepository = mockk<LocalTrackingRepository>()
        every { localTrackingRepository.observePendingSessions() } returns flowOf(emptyList())
        val viewModel = RoutesListViewModel(routeRepository, localTrackingRepository)

        viewModel.uiState.test {
            assertEquals(RoutesListUiState.Loading, awaitItem())
            viewModel.loadRoutes()
            assertEquals(RoutesListUiState.Success(listOf(first)), awaitItem())

            coEvery { routeRepository.getRoutes() } returns Result.success(listOf(first, second))
            viewModel.loadRoutes()
            assertEquals(RoutesListUiState.Success(listOf(first, second)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
