package cl.usach.diinf.lsg.routes.core.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Banderas de funcionalidad que decide el backend (`GET /lsg-routes/app/version`).
 *
 * Se persisten para que valgan desde el primer frame y con la app sin red: la
 * pantalla de ruta nueva y el servicio de seguimiento las consultan de inmediato,
 * mucho antes de que responda la comprobacion de version. El default es el modo
 * restringido, asi que una instalacion nueva sin red no muestra la bicicleta.
 */
@Singleton
class FeatureFlags @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("lsg_feature_flags", Context.MODE_PRIVATE)

    private val _bikeEnabled = MutableStateFlow(prefs.getBoolean(KEY_BIKE, false))
    val bikeEnabled: StateFlow<Boolean> = _bikeEnabled.asStateFlow()

    /** Lectura sincrona para quien no puede observar un flujo (TrackingService). */
    val isBikeEnabled: Boolean get() = _bikeEnabled.value

    fun setBikeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIKE, enabled).apply()
        _bikeEnabled.value = enabled
    }

    private companion object {
        const val KEY_BIKE = "bike_enabled"
    }
}
