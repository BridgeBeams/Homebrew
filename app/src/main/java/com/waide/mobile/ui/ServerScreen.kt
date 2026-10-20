package com.waide.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waide.mobile.ServerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    state: ServerState,
    hasServerUrl: Boolean,
    onRefreshModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onDeleteServerModel: (String) -> Unit = {},
    onPullServerModel: (String) -> Unit = {},
    onSearchHf: (String) -> Unit = {},
    onOpenQuants: (String) -> Unit = {},
    onCloseQuants: () -> Unit = {},
    onPullHf: (String, String) -> Unit = { _, _ -> },
    onSelectServer: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var managing by remember { mutableStateOf(false) }
    if (managing) {
        ServerModelsManager(
            state = state,
            onBack = { managing = false },
            onRefresh = onRefreshModels,
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

    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var machineMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val streamingText = state.streaming
    val activeServer = state.servers.firstOrNull { it.id == state.activeServerId }

    val itemCount = state.messages.size + if (streamingText != null) 1 else 0
    LaunchedEffect(itemCount, streamingText?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier.fillMaxSize()) {

        // Machine picker
        Row(
            Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.servers.isEmpty()) {
                Text("No machine set. Add one in Settings.", color = W.Muted,
                    style = MaterialTheme.typography.labelMedium)
            } else {
                ExposedDropdownMenuBox(
                    expanded = machineMenu,
                    onExpandedChange = { machineMenu = !machineMenu },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = activeServer?.name ?: "Pick a machine",
                        onValueChange = {}, readOnly = true,
                        label = { Text("Machine", color = W.Muted) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = machineMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                            focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                            focusedTextColor = W.Text, unfocusedTextColor = W.Text
                        )
                    )
                    ExposedDropdownMenu(expanded = machineMenu, onDismissRequest = { machineMenu = false }) {
                        state.servers.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name, color = if (s.id == state.activeServerId) W.Amber else W.Text) },
                                onClick = { onSelectServer(s.id); machineMenu = false }
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = W.Line)

        // Model picker + refresh
        Row(
            Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExposedDropdownMenuBox(
                expanded = menuOpen,
                onExpandedChange = { menuOpen = !menuOpen },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = state.selectedModel.ifBlank { "No model" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Server model", color = W.Muted) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    )
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (state.models.isEmpty()) {
                        DropdownMenuItem(text = { Text("No models. Tap Refresh.") }, onClick = { menuOpen = false })
                    }
                    state.models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m, color = if (m == state.selectedModel) W.Amber else W.Text) },
                            onClick = { onSelectModel(m); menuOpen = false }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRefreshModels) {
                if (state.loadingModels)
                    CircularProgressIndicator(Modifier.size(20.dp), color = W.Amber, strokeWidth = 2.dp)
                else
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh models", tint = W.Amber)
            }
            TextButton(onClick = { managing = true }) { Text("Manage", color = W.Muted) }
        }
        // status line
        Row(
            Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    !hasServerUrl -> "Set your server address in Settings"
                    state.connected -> "Connected · private · nothing saved"
                    else -> "Not connected · tap Refresh"
                },
                color = if (state.connected) W.Muted else W.Danger,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            if (state.messages.isNotEmpty()) {
                TextButton(onClick = onClear, enabled = !state.busy) { Text("Clear", color = W.Muted) }
            }
        }
        HorizontalDivider(color = W.Line)

        if (state.messages.isEmpty() && streamingText == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Server", style = MaterialTheme.typography.titleMedium, color = W.Amber)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (!hasServerUrl) "Add your PC's address in Settings, then tap Refresh to load its models."
                        else "Fast answers from your own machine. Anonymous, and nothing is saved here.",
                        color = W.Muted, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(state.messages) { m -> SrvBubble(m.role, m.text) }
                if (streamingText != null) item { SrvBubble("assistant", streamingText.ifBlank { "..." }) }
            }
        }

        state.error?.let {
            Text(
                it, color = W.Danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1512)).padding(14.dp)
            )
        }

        HorizontalDivider(color = W.Line)
        Row(
            Modifier.background(W.Surface).imePadding().padding(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask the server", color = W.Muted) },
                maxLines = 6,
                shape = RoundedCornerShape(6.dp),
                enabled = state.connected,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                    focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    if (state.busy) onStop() else { val t = draft; draft = ""; onSend(t) }
                },
                enabled = state.connected && (state.busy || draft.isNotBlank()),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = W.Amber, contentColor = Color(0xFF1A1204),
                    disabledContainerColor = W.SurfaceHi
                ),
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    if (state.busy) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                    contentDescription = if (state.busy) "Stop" else "Send"
                )
            }
        }
    }
}

@Composable
private fun ServerModelsManager(
    state: ServerState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onPull: (String) -> Unit,
    onSearchHf: (String) -> Unit,
    onOpenQuants: (String) -> Unit,
    onCloseQuants: () -> Unit,
    onPullHf: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< Back", color = W.Amber) }
            Text("Server models", color = W.Text, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                if (state.loadingModels)
                    CircularProgressIndicator(Modifier.size(20.dp), color = W.Amber, strokeWidth = 2.dp)
                else
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = W.Amber)
            }
        }
        HorizontalDivider(color = W.Line)

        // Search Hugging Face
        Column(Modifier.padding(14.dp)) {
            Text("FIND A MODEL", style = MaterialTheme.typography.labelSmall, color = W.Amber)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.weight(1f), singleLine = true, enabled = !state.searching,
                    placeholder = { Text("search, e.g. mytho abliterated", color = W.Muted) },
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSearchHf(query) },
                    enabled = !state.searching && query.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = W.Amber, disabledContainerColor = W.SurfaceHi)
                ) { Text("Search") }
            }
            Text("Searches Hugging Face, newest first.", color = W.Muted,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))

            if (state.managingBusy && state.pullStatus != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = W.Amber, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Downloading: ${state.pullStatus}", color = W.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }

            // optional: paste an exact name
            TextButton(onClick = { showPaste = !showPaste }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showPaste) "hide exact-name box" else "or paste an exact name", color = W.Muted,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (showPaste) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = addName, onValueChange = { addName = it },
                        modifier = Modifier.weight(1f), singleLine = true, enabled = !state.managingBusy,
                        placeholder = { Text("qwen2.5:7b  or  hf.co/user/repo:Q4_K_M", color = W.Muted) },
                        shape = RoundedCornerShape(6.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                            focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onPull(addName); addName = "" },
                        enabled = !state.managingBusy && addName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = W.Amber, disabledContainerColor = W.SurfaceHi)
                    ) { Text("Add") }
                }
            }
        }
        HorizontalDivider(color = W.Line)

        state.error?.let {
            Text(it, color = W.Danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1512)).padding(14.dp))
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            if (state.searching) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = W.Amber, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Searching Hugging Face...", color = W.Muted)
                    }
                }
            }

            if (state.searchResults.isNotEmpty()) {
                item { Text("SEARCH RESULTS", style = MaterialTheme.typography.labelSmall, color = W.Amber) }
                items(state.searchResults) { r ->
                    Row(
                        Modifier.fillMaxWidth()
                            .border(1.dp, W.Line, RoundedCornerShape(4.dp))
                            .background(W.Surface, RoundedCornerShape(4.dp))
                            .clickable(enabled = !state.managingBusy) { onOpenQuants(r.id) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.id, color = W.Text, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(2.dp))
                            Text("updated ${r.updated}  ·  ${r.downloads} downloads",
                                color = W.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text("Get >", color = W.Amber, style = MaterialTheme.typography.labelMedium)
                    }
                }
                item { HorizontalDivider(color = W.Line, modifier = Modifier.padding(vertical = 6.dp)) }
            }

            item { Text("ON THE SERVER", style = MaterialTheme.typography.labelSmall, color = W.Amber) }
            if (state.models.isEmpty()) {
                item { Text("Nothing installed yet.", color = W.Muted, style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(state.models) { name ->
                    Row(
                        Modifier.fillMaxWidth()
                            .border(1.dp, W.Line, RoundedCornerShape(4.dp))
                            .background(W.Surface, RoundedCornerShape(4.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, color = W.Text, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { confirm = name }, enabled = !state.managingBusy) {
                            Text("Delete", color = W.Danger)
                        }
                    }
                }
            }
        }
    }

    // Quant picker for a tapped search result
    if (state.quantRepo != null) {
        AlertDialog(
            onDismissRequest = onCloseQuants,
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCloseQuants) { Text("Cancel", color = W.Muted) } },
            title = { Text("Pick a version", color = W.Text) },
            text = {
                if (state.loadingQuants) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = W.Amber, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Reading versions...", color = W.Muted)
                    }
                } else if (state.quants.isEmpty()) {
                    Text("No .gguf versions found in that repo.", color = W.Muted)
                } else {
                    Column {
                        Text("Smaller = faster and less memory. Q4_K_M is a good default.",
                            color = W.Muted, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        state.quants.forEach { qz ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .border(1.dp, W.Line, RoundedCornerShape(4.dp))
                                    .clickable { onPullHf(state.quantRepo!!, qz.tag) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(qz.tag, color = W.Amber, modifier = Modifier.weight(1f))
                                Text("Download", color = W.Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            },
            containerColor = W.Surface
        )
    }

    confirm?.let { name ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = {
                TextButton(onClick = { onDelete(name); confirm = null }) { Text("Delete", color = W.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Remove from server?", color = W.Text) },
            text = { Text("This deletes $name from your PC. You can add it again later.", color = W.Muted) },
            containerColor = W.Surface
        )
    }
}

@Composable
private fun SrvBubble(role: String, text: String) {
    val isUser = role == "user"
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (isUser) "YOU" else "SERVER",
            style = MaterialTheme.typography.labelSmall,
            color = if (isUser) W.Muted else W.Amber
        )
        Spacer(Modifier.height(5.dp))
        SelectionContainer {
            Text(
                text = text, color = W.Text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().then(
                    if (isUser) Modifier
                        .border(1.dp, W.Line, RoundedCornerShape(4.dp))
                        .background(W.Surface, RoundedCornerShape(4.dp)).padding(12.dp)
                    else Modifier.padding(start = 2.dp)
                )
            )
        }
    }
}
