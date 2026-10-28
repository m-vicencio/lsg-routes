package cl.usach.diinf.lsg.routes.feature.update

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cl.usach.diinf.lsg.routes.R

/** Dialogo de actualizacion disponible. Abre el navegador con la URL de descarga. */
@Composable
fun AppUpdateDialog(updateInfo: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Text(stringResource(R.string.update_body, updateInfo.latestVersion))
        },
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                context.startActivity(intent)
                onDismiss()
            }) {
                Text(stringResource(R.string.update_action_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_action_later))
            }
        },
    )
}
