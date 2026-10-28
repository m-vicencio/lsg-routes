package cl.usach.diinf.lsg.routes.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Mensaje centrado dentro de un contenedor desplazable, para las ramas de lista vacia
 * y de error.
 *
 * Existe por el gesto de tirar hacia abajo: PullToRefreshBox solo lo detecta si su
 * contenido se puede desplazar, y un Text suelto dentro de un Box no lo es. Sin esto,
 * refrescar seria imposible justo cuando mas hace falta, que es cuando la lista quedo
 * vacia o la carga fallo.
 */
@Composable
fun RefreshableMessage(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            color = color,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(32.dp),
        )
    }
}
