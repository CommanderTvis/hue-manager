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
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.SERVER_PORT

@Composable
fun ServerConnectScreen(
    onConnect: (String) -> Unit
) {
    var url by remember { mutableStateOf("http://localhost:$SERVER_PORT") }
    var error by remember { mutableStateOf<String?>(null) }

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
            value = url,
            onValueChange = { 
                url = it 
                error = null
            },
            label = { Text("Server URL") },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    if (url.isNotBlank()) {
                        onConnect(url)
                    } else {
                        error = "URL cannot be empty"
                    }
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (url.isNotBlank()) {
                    onConnect(url)
                } else {
                    error = "URL cannot be empty"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Connect")
        }
    }
}
