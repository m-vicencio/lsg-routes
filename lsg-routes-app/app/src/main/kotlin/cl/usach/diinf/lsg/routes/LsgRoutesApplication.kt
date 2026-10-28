package cl.usach.diinf.lsg.routes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre
import timber.log.Timber
import javax.inject.Inject

/** Clase de aplicacion principal con soporte Hilt e inicializacion de librerias. */
@HiltAndroidApp
class LsgRoutesApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    // Proporciona la HiltWorkerFactory a WorkManager. Requerido porque el
    // inicializador automatico (WorkManagerInitializer) esta deshabilitado en el manifest.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        Timber.plant(Timber.DebugTree())
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        // IMPORTANCE_DEFAULT y no LOW: el aviso de exceso de velocidad del recorrido
        // libre debe verse con la pantalla apagada, que es justo cuando ocurre.
        val channel = NotificationChannel(
            TRACKING_CHANNEL_ID,
            "Seguimiento de ruta",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "tracking_channel"
    }
}
