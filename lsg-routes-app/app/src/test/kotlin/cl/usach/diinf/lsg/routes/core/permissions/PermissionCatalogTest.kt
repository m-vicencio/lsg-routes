package cl.usach.diinf.lsg.routes.core.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

/** Los cortes de SDK del catalogo deciden cuantas paginas tiene el landing de
 *  permisos: un corte mal puesto pediria un permiso inexistente en el dispositivo
 *  (crash) u omitiria uno necesario. */
class PermissionCatalogTest {

    @Test
    fun `en API 26-28 solo aplica ubicacion`() {
        assertEquals(listOf(PermissionGroup.LOCATION), PermissionCatalog.requiredGroups(26))
        assertEquals(listOf(PermissionGroup.LOCATION), PermissionCatalog.requiredGroups(28))
    }

    @Test
    fun `en API 29 se agrega actividad fisica`() {
        assertEquals(
            listOf(PermissionGroup.LOCATION, PermissionGroup.ACTIVITY_RECOGNITION),
            PermissionCatalog.requiredGroups(29),
        )
    }

    @Test
    fun `en API 33 se agregan las notificaciones`() {
        assertEquals(
            listOf(
                PermissionGroup.LOCATION,
                PermissionGroup.ACTIVITY_RECOGNITION,
                PermissionGroup.NOTIFICATIONS,
            ),
            PermissionCatalog.requiredGroups(33),
        )
    }
}
