package com.waide.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waide.mobile.WaideSettings

@Composable
fun SettingsScreen(
    settings: WaideSettings,
    onChange: (WaideSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var system by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("ENGINE", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(12.dp))

        Slider2(
            label = "Context size",
            value = settings.contextSize.toFloat(),
            range = 1024f..16384f,
            steps = 14,
            display = "${settings.contextSize} tokens",
            help = "How much conversation the model can hold. Bigger uses more RAM. Takes effect when you next load a model."
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
        Text(
            "Everything runs on this phone. No account, no network, no server.",
            color = W.Muted,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(40.dp))
    }
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
