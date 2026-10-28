package cl.usach.diinf.lsg.routes.feature.update

import cl.usach.diinf.lsg.routes.BuildConfig
import cl.usach.diinf.lsg.routes.core.config.FeatureFlags
import cl.usach.diinf.lsg.routes.data.remote.service.AppMetaService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(val latestVersion: String, val downloadUrl: String, val sha256: String)

/** Verifica si hay una nueva version del APK disponible en el backend. */
@Singleton
class AppUpdateChecker @Inject constructor(
    private val appMetaService: AppMetaService,
    private val featureFlags: FeatureFlags,
) {
    /**
     * Compara la version instalada con la disponible en el backend.
     *
     * De paso guarda las banderas de funcionalidad que viajan en la misma respuesta:
     * es la unica llamada que la app hace al arrancar, y pedirlas aparte seria una
     * segunda peticion por un booleano.
     *
     * @return UpdateInfo si hay una version mas nueva, null si esta actualizado o hubo error.
     */
    suspend fun checkForUpdate(): UpdateInfo? = runCatching {
        val info = appMetaService.getAppVersion()
        featureFlags.setBikeEnabled(info.bikeActivate)
        if (isNewer(info.latest, BuildConfig.VERSION_NAME)) {
            Timber.i("Nueva version disponible: ${info.latest} (actual: ${BuildConfig.VERSION_NAME})")
            UpdateInfo(info.latest, info.url, info.sha256)
        } else null
    }.onFailure { Timber.w(it, "No se pudo verificar actualizacion") }.getOrNull()

    /** Retorna true si candidate es mayor que current usando comparacion de versiones semanticas. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val v = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(c.size, v.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = v.getOrElse(i) { 0 }
            if (a > b) return true
            if (a < b) return false
        }
        return false
    }
}
