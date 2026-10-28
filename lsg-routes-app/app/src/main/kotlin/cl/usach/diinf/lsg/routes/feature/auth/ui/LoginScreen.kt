package cl.usach.diinf.lsg.routes.feature.auth.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cl.usach.diinf.lsg.routes.R
import cl.usach.diinf.lsg.routes.core.ui.theme.LsgRoutesTheme

/** Pantalla de inicio de sesion con email y contrasena. */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val errorMessage = when ((uiState as? LoginUiState.Error)?.messageRes) {
        LoginError.INVALID_CREDENTIALS -> stringResource(R.string.error_invalid_credentials)
        LoginError.NETWORK -> stringResource(R.string.error_network)
        LoginError.UNKNOWN -> stringResource(R.string.error_unknown)
        null -> null
    }

    LaunchedEffect(uiState) {
        when (uiState) {
            is LoginUiState.Success -> onLoginSuccess()
            is LoginUiState.Error -> {
                snackbarHostState.showSnackbar(errorMessage ?: "")
                viewModel.resetState()
            }
            else -> Unit
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.screen_login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = formState.email,
                onValueChange = { viewModel.onEmailChange(it) },
                label = { Text(stringResource(R.string.label_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = formState.password,
                onValueChange = { viewModel.onPasswordChange(it) },
                label = { Text(stringResource(R.string.label_password)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.login() }),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.onRememberMeChange(!formState.rememberMe) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = formState.rememberMe,
                    onCheckedChange = { viewModel.onRememberMeChange(it) },
                )
                Text(
                    text = stringResource(R.string.action_remember_me),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.login() },
                enabled = uiState !is LoginUiState.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState is LoginUiState.Loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                } else {
                    Text(stringResource(R.string.action_login))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    LsgRoutesTheme { }
}
