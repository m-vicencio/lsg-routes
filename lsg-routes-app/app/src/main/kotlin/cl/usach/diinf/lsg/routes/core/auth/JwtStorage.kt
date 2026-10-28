package cl.usach.diinf.lsg.routes.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/** Almacenamiento seguro del JWT usando EncryptedSharedPreferences. */
@Singleton
class JwtStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Inicializacion diferida: si el Keystore falla, la excepcion cae dentro del try de
    // prefs y no en la inyeccion de Hilt, donde nada la puede atrapar.
    private val masterKeyAlias by lazy { MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC) }

    private val prefs: SharedPreferences by lazy {
        try {
            createPrefs()
        } catch (e: GeneralSecurityException) {
            recoverFromCorruptPrefs(e)
        } catch (e: IOException) {
            recoverFromCorruptPrefs(e)
        } catch (e: RuntimeException) {
            // Tink lanza InvalidProtocolBufferException (RuntimeException) cuando el
            // keyset no se puede parsear, que es el caso mas comun aqui.
            recoverFromCorruptPrefs(e)
        }
    }

    private fun createPrefs(): SharedPreferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * El archivo de preferencias sobrevivio pero la clave maestra del Keystore que lo
     * descifra ya no corresponde (restauracion de backup, clon de la app, reinstalacion
     * sobre datos viejos). Se descarta y se regenera vacio.
     *
     * Consecuencia: el usuario vuelve a iniciar sesion. Preferible a que la app no abra,
     * y el contenido perdido es solo un JWT de 120 min mas las credenciales de autorrelleno.
     */
    private fun recoverFromCorruptPrefs(cause: Throwable): SharedPreferences {
        Timber.w(cause, "Preferencias cifradas ilegibles: se regeneran vacias")
        context.deleteSharedPreferences(PREFS_NAME)  // borra datos y keyset: viven en el mismo archivo
        return createPrefs()
    }

    /** Persiste el token JWT de forma segura. */
    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    /** Retorna el token almacenado o null si no existe. */
    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    /** Elimina el token (logout). */
    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    /** Guarda email y contraseña cifrados para auto-relleno en el login. */
    fun saveCredentials(email: String, password: String) {
        prefs.edit()
            .putString(KEY_SAVED_EMAIL, email)
            .putString(KEY_SAVED_PASSWORD, password)
            .apply()
    }

    /** Retorna (email, password) guardados, o null si no hay ninguno. */
    fun getCredentials(): Pair<String, String>? {
        val email = prefs.getString(KEY_SAVED_EMAIL, null) ?: return null
        val password = prefs.getString(KEY_SAVED_PASSWORD, null) ?: return null
        return email to password
    }

    /** Elimina las credenciales guardadas. */
    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_SAVED_EMAIL)
            .remove(KEY_SAVED_PASSWORD)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "lsg_auth_prefs"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_SAVED_EMAIL = "saved_email"
        private const val KEY_SAVED_PASSWORD = "saved_password"
    }
}
