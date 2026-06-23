package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * MCP OAuth authorization screen - allows users to authorize MCP clients
 * by entering their Hue Manager password or confirming with stored credentials.
 */
@Composable
fun McpOAuthScreen(
    redirectUri: String,
    state: String?,
    responseType: String,
    clientId: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    onAuthorize: (password: String) -> Unit,
    onAuthorizeWithStoredPassword: () -> Unit = {},
    isLoading: Boolean = false,
    error: String? = null,
    hasStoredPassword: Boolean = false,
    modifier: Modifier = Modifier
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var useNewPassword by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Authorize MCP Access",
                    style = MaterialTheme.typography.headlineMedium
                )

                // Show client info if available
                clientId?.let { client ->
                    Text(
                        text = "Client: $client",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (hasStoredPassword && !useNewPassword) {
                    // User has stored credentials - show confirmation UI
                    Text(
                        text = "An MCP client is requesting access to control your Hue lamps.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    error?.let { errorMessage ->
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = { onAuthorizeWithStoredPassword() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isLoading) "Authorizing..." else "Authorize")
                    }

                    TextButton(
                        onClick = { useNewPassword = true },
                        enabled = !isLoading
                    ) {
                        Text("Use different password")
                    }
                } else {
                    // No stored credentials or user wants to enter new password
                    Text(
                        text = "Enter your Hue Manager password to authorize this MCP client.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { onAuthorize(password) }
                        ),
                        trailingIcon = {
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(if (passwordVisible) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )

                    error?.let { errorMessage ->
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = { onAuthorize(password) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && password.isNotBlank()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isLoading) "Authorizing..." else "Authorize")
                    }

                    if (hasStoredPassword) {
                        TextButton(
                            onClick = { useNewPassword = false },
                            enabled = !isLoading
                        ) {
                            Text("Use saved password")
                        }
                    }
                }
            }
        }
    }
}
