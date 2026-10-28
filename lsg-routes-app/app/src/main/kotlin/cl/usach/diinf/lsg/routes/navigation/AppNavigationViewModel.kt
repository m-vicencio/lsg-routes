package cl.usach.diinf.lsg.routes.navigation

import android.content.Context
import androidx.lifecycle.ViewModel
import cl.usach.diinf.lsg.routes.core.permissions.PermissionCatalog
import cl.usach.diinf.lsg.routes.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Decide donde arranca la navegacion.
 *
 *  Si falta algun permiso runtime se entra al landing de permisos; si no, con un
 *  token guardado se entra directo a Mis Rutas (asi un usuario sin conexion no queda
 *  encerrado en el login, que si o si necesita red) y sin token, al login. */
@HiltViewModel
class AppNavigationViewModel @Inject constructor(
    @ApplicationContext context: Context,
    authRepository: AuthRepository,
) : ViewModel() {

    /** Destino tras el landing (o directo, si los permisos ya estaban). */
    val mainDestination: String =
        if (authRepository.isLoggedIn()) Screen.RoutesList.route else Screen.Login.route

    val startDestination: String =
        if (PermissionCatalog.allGranted(context)) mainDestination
        else Screen.PermissionsOnboarding.route
}
