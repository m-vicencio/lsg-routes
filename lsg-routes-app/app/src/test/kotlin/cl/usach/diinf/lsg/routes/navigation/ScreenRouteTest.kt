package cl.usach.diinf.lsg.routes.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El argumento opcional sessionId es lo unico que conecta la notificacion de seguimiento
 * y la lista de pendientes con la pantalla de recorrido. Si la cadena generada no calza
 * con el patron declarado, Navigation no resuelve el destino y el boton no hace nada,
 * sin error visible.
 */
class ScreenRouteTest {

    @Test
    fun `una ruta nueva no arrastra el argumento opcional`() {
        assertEquals("active_route/r1/46", Screen.ActiveRoute.createRoute("r1", 46))
        assertEquals("free_roam/46", Screen.FreeRoam.createRoute(46))
    }

    @Test
    fun `reanudar agrega la sesion como query`() {
        assertEquals(
            "active_route/r1/46?sessionId=s-1",
            Screen.ActiveRoute.createRoute("r1", 46, "s-1"),
        )
        assertEquals("free_roam/46?sessionId=s-2", Screen.FreeRoam.createRoute(46, "s-2"))
    }

    @Test
    fun `una sesion vacia no se agrega`() {
        // Pasarla vacia haria que la pantalla buscara una sesion con id "" y fallara al
        // reanudar en vez de empezar una grabacion nueva.
        assertEquals("active_route/r1/46", Screen.ActiveRoute.createRoute("r1", 46, ""))
        assertEquals("free_roam/46", Screen.FreeRoam.createRoute(46, null))
    }

    @Test
    fun `las rutas generadas casan con el prefijo que oculta la barra inferior`() {
        // AppNavigation compara con startsWith sobre el patron cortado en la primera llave
        listOf(
            Screen.ActiveRoute.route.substringBefore("{") to Screen.ActiveRoute.createRoute("r1", 46, "s-1"),
            Screen.FreeRoam.route.substringBefore("{") to Screen.FreeRoam.createRoute(46, "s-1"),
        ).forEach { (prefix, generated) ->
            assertTrue("$generated deberia empezar con $prefix", generated.startsWith(prefix))
        }
    }
}
