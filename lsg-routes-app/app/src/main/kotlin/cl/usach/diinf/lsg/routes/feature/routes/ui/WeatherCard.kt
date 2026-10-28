package cl.usach.diinf.lsg.routes.feature.routes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.domain.model.Weather
import kotlin.math.roundToInt

/** Tarjeta con el clima actual en el punto de inicio de una ruta (via Open-Meteo). */
@Composable
fun WeatherCard(weather: Weather, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = weatherEmoji(weather.weatherCode, weather.isDay),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = weather.temperatureC?.let { "${it.roundToInt()}°C" } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(weatherLabelRes(weather.weatherCode)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                weather.apparentTemperatureC?.let {
                    Text(
                        text = stringResource(R.string.weather_feels_like, "${it.roundToInt()}°"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                weather.windSpeedKmh?.let {
                    Text(
                        text = stringResource(R.string.weather_wind, "%.0f".format(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                weather.humidityPct?.let {
                    Text(
                        text = stringResource(R.string.weather_humidity, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/** Emoji segun codigo WMO de Open-Meteo (https://open-meteo.com/en/docs). */
private fun weatherEmoji(code: Int?, isDay: Boolean): String = when (code) {
    0, 1 -> if (isDay) "☀️" else "🌙"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    in 51..57 -> "🌦️"
    in 61..67, in 80..82 -> "🌧️"
    in 71..77, 85, 86 -> "❄️"
    in 95..99 -> "⛈️"
    else -> "🌡️"
}

/** Etiqueta localizada agrupando codigos WMO en categorias. */
private fun weatherLabelRes(code: Int?): Int = when (code) {
    0, 1 -> R.string.weather_clear
    2 -> R.string.weather_partly_cloudy
    3 -> R.string.weather_overcast
    45, 48 -> R.string.weather_fog
    in 51..57 -> R.string.weather_drizzle
    in 61..67, in 80..82 -> R.string.weather_rain
    in 71..77, 85, 86 -> R.string.weather_snow
    in 95..99 -> R.string.weather_thunderstorm
    else -> R.string.weather_unknown
}
