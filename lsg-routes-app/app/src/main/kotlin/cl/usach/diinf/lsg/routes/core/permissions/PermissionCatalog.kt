package cl.usach.diinf.lsg.routes.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Grupo de permisos runtime que la app necesita, con el permiso que decide si el
 *  grupo cuenta como otorgado. Ubicacion pide FINE y COARSE juntos pero el gate es
 *  FINE: en Android 12+ el usuario puede elegir "aproximada" y eso deja FINE
 *  denegado, que para esta app equivale a no tener el permiso. */
enum class PermissionGroup(
    val permissions: List<String>,
    val gatePermission: String,
) {
    LOCATION(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
        gatePermission = Manifest.permission.ACCESS_FINE_LOCATION,
    ),
    ACTIVITY_RECOGNITION(
        permissions = listOf(Manifest.permission.ACTIVITY_RECOGNITION),
        gatePermission = Manifest.permission.ACTIVITY_RECOGNITION,
    ),
    NOTIFICATIONS(
        permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
        gatePermission = Manifest.permission.POST_NOTIFICATIONS,
    ),
}

/** Catalogo unico de los permisos runtime de la app. Lo usan el landing de
 *  permisos, la decision del destino inicial de navegacion y el launcher de
 *  ActiveRouteScreen.
 *
 *  ACTIVITY_RECOGNITION (API 29+) es para el contador de pasos: sin el, el sensor
 *  step_count falla en silencio y se debilita el validador anti-trampa. */
object PermissionCatalog {

    /** Grupos que aplican al dispositivo. sdkInt es parametro para poder probar
     *  los cortes de version en JVM sin mockear Build. */
    fun requiredGroups(sdkInt: Int = Build.VERSION.SDK_INT): List<PermissionGroup> = buildList {
        add(PermissionGroup.LOCATION)
        if (sdkInt >= Build.VERSION_CODES.Q) add(PermissionGroup.ACTIVITY_RECOGNITION)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(PermissionGroup.NOTIFICATIONS)
    }

    fun isGroupGranted(context: Context, group: PermissionGroup): Boolean =
        ContextCompat.checkSelfPermission(context, group.gatePermission) ==
            PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context): Boolean =
        requiredGroups().all { isGroupGranted(context, it) }
}
