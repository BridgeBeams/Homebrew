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
import com.waide.mobile.GgufFile
import com.waide.mobile.HfFileOption
import com.waide.mobile.LocalModelBrowse
import com.waide.mobile.PremiumState
import com.waide.mobile.ServerEntry
import com.waide.mobile.ServerState
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
    onUnhideAllAgents: (String, (String?) -> Unit) -> Unit = { _, _ -> },
    onChangePassword: (String, String, (String?) -> Unit) -> Unit = { _, _, _ -> },
    onRequestReset: (String, (String?) -> Unit) -> Unit = { _, _ -> },
    onCompleteReset: (String, (String?) -> Unit) -> Unit = { _, _ -> },
    // server models (shared by the amnesia Server tab and the Convos tab)
    serverState: ServerState = ServerState(),
    onRefreshModels: () -> Unit = {},
    onSelectModel: (String) -> Unit = {},
    onSetVisionModel: (String) -> Unit = {},
    onDeleteServerModel: (String) -> Unit = {},
    onPullServerModel: (String) -> Unit = {},
    onSearchHf: (String) -> Unit = {},
    onOpenQuants: (String) -> Unit = {},
    onCloseQuants: () -> Unit = {},
    onPullHf: (String, String) -> Unit = { _, _ -> },
    // local models (the on-phone Chat tab)
    localModels: List<GgufFile> = emptyList(),
    loadedModelName: String = "",
    modelLoading: Boolean = false,
    hasStorageAccess: Boolean = true,
    onGrantStorage: () -> Unit = {},
    onRefreshLocal: () -> Unit = {},
    onLoadLocal: (GgufFile) -> Unit = {},
    onUnloadLocal: () -> Unit = {},
    onDeleteLocal: (GgufFile) -> Unit = {},
    // premium AI (OpenRouter)
    premiumState: PremiumState = PremiumState(),
    onSetOpenRouterKey: (String, String, (String?) -> Unit) -> Unit = { _, _, _ -> },
    onRefreshPremiumModels: () -> Unit = {},
    onSetDefaultPremiumModel: (String) -> Unit = {},
    onUnhideAllPremium: (String, (String?) -> Unit) -> Unit = { _, _ -> },
    // local model search/download (Hugging Face, straight to the phone)
    localBrowse: LocalModelBrowse = LocalModelBrowse(),
    onLocalSearchHf: (String) -> Unit = {},
    onLocalOpenQuants: (String) -> Unit = {},
    onLocalCloseQuants: () -> Unit = {},
    onLocalCloseSearch: () -> Unit = {},
    onLocalDownload: (String, HfFileOption) -> Unit = { _, _ -> },
    onLocalCancelDownload: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var system by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var unhideDialog by remember { mutableStateOf(false) }
    var unhideAgentsDialog by remember { mutableStateOf(false) }
    var unhideMsg by remember { mutableStateOf<String?>(null) }
    var changePwDialog by remember { mutableStateOf(false) }
    var pwMsg by remember { mutableStateOf<String?>(null) }
    var premiumKeyDialog by remember { mutableStateOf(false) }
    var premiumModelDialog by remember { mutableStateOf(false) }
    var unhidePremiumDialog by remember { mutableStateOf(false) }
    var premiumMsg by remember { mutableStateOf<String?>(null) }
    var managingModels by remember { mutableStateOf(false) }
    var managingLocal by remember { mutableStateOf(false) }

    // The full-screen server-models manager (search, pull, delete, pick answering model
    // and image reader). Swaps in over Settings, same pattern the Server tab used before.
    if (managingModels) {
        ServerModelsScreen(
            state = serverState,
            onBack = { managingModels = false },
            onRefresh = onRefreshModels,
            onSelectModel = onSelectModel,
            onSetVisionModel = onSetVisionModel,
            onDelete = onDeleteServerModel,
            onPull = onPullServerModel,
            onSearchHf = onSearchHf,
            onOpenQuants = onOpenQuants,
            onCloseQuants = onCloseQuants,
            onPullHf = onPullHf,
            modifier = modifier
        )
        return
    }

    // The on-phone model picker (scans Downloads for .gguf). Same swap-in pattern.
    if (managingLocal) {
        ModelsScreen(
            models = localModels,
            loadedName = loadedModelName,
            loading = modelLoading,
            hasStorageAccess = hasStorageAccess,
            onGrantStorage = onGrantStorage,
            onRefresh = onRefreshLocal,
            onLoad = onLoadLocal,
            onUnload = onUnloadLocal,
            onDelete = onDeleteLocal,
            onBack = { managingLocal = false },
            browse = localBrowse,
            onSearchHf = onLocalSearchHf,
            onOpenQuants = onLocalOpenQuants,
            onCloseQuants = onLocalCloseQuants,
            onCloseSearch = onLocalCloseSearch,
            onDownload = onLocalDownload,
            onCancelDownload = onLocalCancelDownload,
            modifier = modifier
        )
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("MACHINES", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "The servers the Server Chat tab can reach, each by its Tailscale address " +
                "(http://100.x.y.z:11434). Add more as you set up more machines, then " +
                "switch between them from the dropdown on the Server Chat tab.",
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
        Text("SERVER MODELS", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "One place for the whole server side. The Server Chat tab and the Saved Chats " +
                "tab both use the answering model and image reader you pick here. Search for, " +
                "download, and delete models too.",
            color = W.Muted, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        Text("Answering model: ${serverState.selectedModel.ifBlank { "none set" }}",
            color = W.Text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(2.dp))
        Text("Image reader: ${serverState.visionModel.ifBlank { "none set" }}",
            color = W.Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { managingModels = true },
            colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
        ) { Text("Manage server models") }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = W.Line)
        Spacer(Modifier.height(18.dp))
        Text("PREMIUM AI", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "Paid cloud models (Claude, GPT, Grok, and more) through OpenRouter. Your key " +
                "is stored on your PC server, never on the phone, and all processing happens " +
                "in the cloud. Get a key at openrouter.ai, then paste it here.",
            color = W.Muted, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        Text("OpenRouter key: ${if (premiumState.hasKey) "set" else "not set"}",
            color = if (premiumState.hasKey) W.Text else W.Danger, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { premiumMsg = null; premiumKeyDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
        ) { Text(if (premiumState.hasKey) "Change OpenRouter key" else "Set OpenRouter key") }
        Spacer(Modifier.height(10.dp))
        Text("Default model: ${premiumState.selectedModel.ifBlank { "none set" }.substringAfterLast('/')}",
            color = W.Text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = { premiumModelDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
            ) { Text("Choose default model") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { premiumMsg = null; unhidePremiumDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = W.SurfaceHi)
            ) { Text("Unhide all", color = W.Amber) }
        }
        premiumMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (it.startsWith("Revealed") || it.startsWith("Saved")) W.Muted else W.Danger,
                style = MaterialTheme.typography.labelMedium)
        }

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
        Text("SAVED CHATS (PRIVATE)", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "Reveal everything hidden at once, so you enter your PIN a single time instead " +
                "of unhiding one by one.",
            color = W.Muted, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { unhideMsg = null; unhideDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
        ) { Text("Unhide all conversations") }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { unhideMsg = null; unhideAgentsDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
        ) { Text("Unhide all agents") }
        unhideMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (it.startsWith("Revealed")) W.Muted else W.Danger,
                style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(14.dp))
        Text("The code that unlocks this tab. If you forget it, use Forgot password with " +
            "your master reset code to set a new one. The master code never unlocks chats " +
            "on its own and never changes.",
            color = W.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { pwMsg = null; changePwDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
        ) { Text("Change password") }
        pwMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (it.startsWith("Password")) W.Muted else W.Danger,
                style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = W.Line)
        Spacer(Modifier.height(18.dp))
        Text("LOCAL MODELS", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(6.dp))
        Text(
            "Models that run on the phone itself, for the Local Chat tab. Drop a .gguf file " +
                "into your Downloads folder, then pick it here.",
            color = W.Muted, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        Text("Loaded: ${loadedModelName.ifBlank { "none" }}",
            color = W.Text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { managingLocal = true },
            colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
        ) { Text("Manage local models") }

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
            help = "For the on-phone Local Chat tab. How much conversation the local model can hold. Bigger uses more of the phone's RAM. Takes effect when you next load a model."
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
        if (unhideAgentsDialog) {
            UnhideAllDialog(
                onSubmit = { pin ->
                    onUnhideAllAgents(pin) { err ->
                        unhideMsg = err ?: "Revealed all hidden agents."
                        unhideAgentsDialog = false
                    }
                },
                onCancel = { unhideAgentsDialog = false }
            )
        }
        if (changePwDialog) {
            ChangePasswordDialog(
                onSave = { current, newPin ->
                    onChangePassword(current, newPin) { err ->
                        pwMsg = err ?: "Password changed."
                        if (err == null) changePwDialog = false
                    }
                },
                onRequestReset = onRequestReset,
                onCompleteReset = { newPin, done ->
                    onCompleteReset(newPin) { err ->
                        done(err)
                        if (err == null) { pwMsg = "Password changed."; changePwDialog = false }
                    }
                },
                onCancel = { changePwDialog = false }
            )
        }
        if (premiumKeyDialog) {
            PremiumKeyDialog(
                save = onSetOpenRouterKey,
                onClose = { premiumKeyDialog = false },
                onSuccess = { premiumMsg = "Saved."; premiumKeyDialog = false }
            )
        }
        if (premiumModelDialog) {
            PremiumModelPicker(
                models = premiumState.models,
                loading = premiumState.loadingModels,
                current = premiumState.selectedModel,
                onRefresh = onRefreshPremiumModels,
                onPick = { onSetDefaultPremiumModel(it); premiumModelDialog = false },
                onDismiss = { premiumModelDialog = false }
            )
        }
        if (unhidePremiumDialog) {
            UnhideAllDialog(
                onSubmit = { pin ->
                    onUnhideAllPremium(pin) { err ->
                        premiumMsg = err ?: "Revealed all hidden premium chats."
                        unhidePremiumDialog = false
                    }
                },
                onCancel = { unhidePremiumDialog = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumKeyDialog(
    save: (String, String, (String?) -> Unit) -> Unit,
    onClose: () -> Unit,
    onSuccess: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        confirmButton = {
            if (saving) {
                CircularProgressIndicator(Modifier.size(22.dp), color = W.Amber, strokeWidth = 2.dp)
            } else {
                TextButton(
                    onClick = {
                        if (pin.length >= 4 && key.isNotBlank()) {
                            saving = true; err = null
                            save(pin, key.trim()) { e ->
                                saving = false
                                if (e == null) onSuccess() else err = e
                            }
                        }
                    },
                    enabled = pin.length >= 4 && key.isNotBlank()
                ) { Text("Save", color = W.Amber) }
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !saving) { Text("Cancel", color = W.Muted) } },
        title = { Text("OpenRouter key", color = W.Text) },
        text = {
            Column {
                Text("Your PIN confirms the change; the key is stored on your PC only.",
                    color = W.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pin, onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) },
                    singleLine = true, enabled = !saving, label = { Text("PIN", color = W.Muted) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    )
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, singleLine = true, enabled = !saving,
                    label = { Text("OpenRouter key (sk-or-...)", color = W.Muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    )
                )
                if (saving) {
                    Spacer(Modifier.height(10.dp))
                    Text("Saving to your server...", color = W.Muted, style = MaterialTheme.typography.labelSmall)
                }
                err?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = W.Danger, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        containerColor = W.Surface
    )
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
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) },
        singleLine = true,
        label = { Text(label, color = W.Muted) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
            focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
            focusedTextColor = W.Text, unfocusedTextColor = W.Text
        )
    )
}

@Composable
private fun ChangePasswordDialog(
    onSave: (String, String) -> Unit,
    onRequestReset: (String, (String?) -> Unit) -> Unit,
    onCompleteReset: (String, (String?) -> Unit) -> Unit,
    onCancel: () -> Unit
) {
    var cur by remember { mutableStateOf("") }
    var neu by remember { mutableStateOf("") }
    var forgotOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = { if (cur.length == 6 && neu.length == 6) onSave(cur, neu) },
                enabled = cur.length == 6 && neu.length == 6) { Text("Save", color = W.Amber) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = W.Muted) } },
        title = { Text("Change password", color = W.Text) },
        text = {
            Column {
                PinField("Current password", cur) { cur = it }
                Spacer(Modifier.height(8.dp))
                PinField("New password (6 digits)", neu) { neu = it }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { forgotOpen = true }, contentPadding = PaddingValues(0.dp)) {
                    Text("Forgot password", color = W.Amber, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        containerColor = W.Surface
    )
    if (forgotOpen) ForgotPasswordDialog(onRequestReset, onCompleteReset, onCancel = { forgotOpen = false })
}

/** Two steps: enter the master reset code, then set a new unlock password. No email. */
@Composable
private fun ForgotPasswordDialog(
    onRequestReset: (String, (String?) -> Unit) -> Unit,
    onCompleteReset: (String, (String?) -> Unit) -> Unit,
    onCancel: () -> Unit
) {
    var step by remember { mutableStateOf(0) }   // 0 = master code, 1 = new password
    var master by remember { mutableStateOf("") }
    var neu by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        confirmButton = {
            if (step == 0) {
                TextButton(
                    onClick = {
                        if (master.length == 6 && !busy) {
                            busy = true; err = null
                            onRequestReset(master) { e -> busy = false; err = e; if (e == null) { step = 1 } }
                        }
                    },
                    enabled = master.length == 6 && !busy
                ) { Text(if (busy) "Checking..." else "Continue", color = W.Amber) }
            } else {
                TextButton(
                    onClick = {
                        if (neu.length == 6 && !busy) {
                            busy = true; err = null
                            onCompleteReset(neu) { e -> busy = false; err = e }
                        }
                    },
                    enabled = neu.length == 6 && !busy
                ) { Text(if (busy) "Saving..." else "Set password", color = W.Amber) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", color = W.Muted) } },
        title = { Text("Forgot password", color = W.Text) },
        text = {
            Column {
                if (step == 0) {
                    Text("Enter your master reset code to set a new unlock password. The master code " +
                        "never unlocks Saved Chats on its own.",
                        color = W.Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    PinField("Master code", master) { master = it }
                } else {
                    Text("Master code accepted. Choose a new 6-digit unlock password.",
                        color = W.Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    PinField("New password", neu) { neu = it }
                }
                err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = W.Danger, style = MaterialTheme.typography.labelSmall) }
            }
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
