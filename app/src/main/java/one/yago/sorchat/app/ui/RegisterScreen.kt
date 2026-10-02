package one.yago.sorchat.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import one.yago.sorchat.app.UiState

@Composable
fun RegisterScreen(state: UiState, onRegister: (String) -> Unit, onSignIn: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text("sorchat", style = MaterialTheme.typography.displaySmall)
            Text("Pick a display name. Your contacts see it next to your ID.")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                label = { Text("Display name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRegister(name) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onRegister(name) },
                enabled = name.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "Registering…" else "Get started") }
            Text("Already have an account?", modifier = Modifier.padding(top = 16.dp))
            OutlinedButton(onClick = onSignIn, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with passkey")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
