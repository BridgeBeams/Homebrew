package com.waide.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.waide.mobile.ServerEntry
import com.waide.mobile.WaideSettings

@Composable
fun SettingsScreen(
    settings: WaideSettings,
    onChange: (WaideSettings) -> Unit,
    servers: List<ServerEntry> = emptyList(),
    onAddServer: (String, String) -> Unit = { _, _ -> },
    onRemoveServer: (String) -> Unit = {},
    serverCtx: Int = 8192,
    onServerCtxChange: (Int) -> Unit = {},
    onUnhideAll: (String, (String?) -> Unit) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var system by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var unhideDialog by remember { mutableStateOf(false) }
    var unhideMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("MACHINES", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "The servers the Server tab can reach, each by its Tailscale address " +
                "(http://100.x.y.z:11434). Add more as you set up more machines, then " +
                "switch between them from the dropdown on the Server tab.",
            color = W.Muted, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))

        servers.forEach { s ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, color = W.Text, style = MaterialTheme.typography.bodyLarge)
                    Text(s.url, color = W.Muted, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onRemoveServer(s.id) }) { Text("Remove", color = W.Danger) }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("ADD A MACHINE", style = MaterialTheme.typography.labelSmall, color = W.Muted)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = newName, onValueChange = { newName = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("name, e.g. Shop PC", color = W.Muted) },
            shape = RoundedCornerShape(4.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line)
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = newUrl, onValueChange = { newUrl = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("http://100.x.y.z:11434", color = W.Muted) },
            shape = RoundedCornerShape(4.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line)
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onAddServer(newName, newUrl); newName = ""; newUrl = "" },
            enabled = newUrl.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = W.Amber, disabledContainerColor = W.SurfaceHi)
        ) { Text("Add machine") }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = W.Line)
        Spacer(Modifier.height(18.dp))
        Text("SERVER", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(12.dp))

        Slider2(
            label = "Server context size",
            value = serverCtx.toFloat(),
            range = 2048f..65536f,
            steps = 30,
            display = "$serverCtx tokens",
            help = "The server model's window for this session: your prompt plus the running conversation. It starts at 8,192 each time you open the app fresh. If a message needs more, the app asks before leaning harder on your PC, so you don't have to set this. Raise it here only if you'd rather do it yourself. Bigger uses more of the PC's memory."
        ) { onServerCtxChange(it.toInt()) }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = W.Line)
        Spacer(Modifier.height(18.dp))
        Text("CONVERSATIONS", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "Reveal every hidden conversation at once, so you enter your PIN a single " +
                "time instead of unhiding them one by one.",
            color = W.Muted, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { unhideMsg = null; unhideDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
        ) { Text("Unhide all conversations") }
        unhideMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (it.startsWith("Revealed")) W.Muted else W.Danger,
                style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = W.Line)
        Spacer(Modifier.height(18.dp))
        Text("ENGINE", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(12.dp))

        Slider2(
            label = "Phone context size",
            value = settings.contextSize.toFloat(),
            range = 1024f..16384f,
            steps = 14,
            display = "${settings.contextSize} tokens",
            help = "For the on-phone Chat tab. How much conversation the local model can hold. Bigger uses more of the phone's RAM. Takes effect when you next load a model."
        ) { onChange(settings.copy(contextSize = (it / 1024).toInt() * 1024)) }

        Slider2(
            label = "CPU threads",
            value = settings.threads.toFloat(),
            range = 2f..8f,
            steps = 5,
            display = "${settings.threads}",
            help = "Four is the sweet spot on a Snapdragon. More is not always faster and drains the battery harder."
        ) { onChange(settings.copy(threads = it.toInt())) }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = W.Line)
        Spacer(Modifier.height(18.dp))
        Text("SAMPLING", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(12.dp))

        Slider2(
            label = "Temperature",
            value = settings.temperature,
            range = 0.1f..1.5f,
            steps = 27,
            display = String.format("%.2f", settings.temperature),
            help = "Low is literal and repetitive. High is loose and creative."
        ) { onChange(settings.copy(temperature = Math.round(it * 20) / 20f)) }

        Slider2(
            label = "Top P",
            value = settings.topP,
            range = 0.5f..1.0f,
            steps = 9,
            display = String.format("%.2f", settings.topP),
            help = null
        ) { onChange(settings.copy(topP = Math.round(it * 20) / 20f)) }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = W.Line)
        Spacer(Modifier.height(18.dp))
        Text("SYSTEM PROMPT", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "Standing instructions the model reads before every conversation. Applied when you start a new chat.",
            color = W.Muted,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = system,
            onValueChange = { system = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            placeholder = { Text("Leave blank to use the model's own default.", color = W.Muted) },
            shape = RoundedCornerShape(4.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = W.Amber,
                unfocusedBorderColor = W.Line
            )
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onChange(settings.copy(systemPrompt = system)) },
            colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
        ) { Text("Save system prompt") }

        Spacer(Modifier.height(40.dp))

        if (unhideDialog) {
            UnhideAllDialog(
                onSubmit = { pin ->
                    onUnhideAll(pin) { err ->
                        unhideMsg = err ?: "Revealed all hidden conversations."
                        unhideDialog = false
                    }
                },
                onCancel = { unhideDialog = false }
            )
        }
    }
}

@Composable
private fun UnhideAllDialog(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = { if (pin.length >= 4) onSubmit(pin) }, enabled = pin.isNotEmpty()) {
                Text("Unhide all", color = W.Amber)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = W.Muted) } },
        title = { Text("Enter PIN", color = W.Text) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                    focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                    focusedTextColor = W.Text, unfocusedTextColor = W.Text
                )
            )
        },
        containerColor = W.Surface
    )
}

@Composable
private fun Slider2(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    help: String?,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = W.Text, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.labelSmall, color = W.Amber)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = W.Amber,
                activeTrackColor = W.Amber,
                inactiveTrackColor = W.Line
            )
        )
        if (help != null) {
            Text(help, color = W.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
