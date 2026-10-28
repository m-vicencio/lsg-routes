package cl.usach.diinf.lsg.routes.core.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Persiste la preferencia de tema (System/Light/Dark) y la expone como StateFlow. */
@Singleton
class ThemePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("lsg_ui_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(load())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    private fun load(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!)
    }.getOrDefault(ThemeMode.SYSTEM)

    companion object {
        private const val KEY_THEME = "theme_mode"
    }
}
