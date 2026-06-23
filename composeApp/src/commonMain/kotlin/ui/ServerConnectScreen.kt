package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.SERVER_PORT
import io.github.commandertvis.huemanager.storage.PlatformStorage
import io.github.commandertvis.huemanager.viewmodel.ServerConnectViewModel

@Composable
fun ServerConnectScreen(
    storage: PlatformStorage,
    initialUrl: String?,
    onConnect: (String) -> Unit
) {
    val viewModel = remember(storage, initialUrl) { 
        ServerConnectViewModel(storage, initialUrl) 
    }
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Connect to Hue Manager Server",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = uiState.url,
            onValueChange = { viewModel.updateUrl(it) },
            label = { Text("Server URL") },
            isError = uiState.error != null,
            supportingText = uiState.error?.let { error -> 
                { SelectionContainer { Text(error) } }
            },
            singleLine = true,
            enabled = !uiState.isConnecting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = { viewModel.connect(onConnect) }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.connect(onConnect) },
            enabled = !uiState.isConnecting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connecting...")
            } else {
                Text("Connect")
            }
        }
    }
}
