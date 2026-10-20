package com.waide.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val streamingText = state.streaming

    val itemCount = state.messages.size + if (streamingText != null) 1 else 0
    LaunchedEffect(itemCount, streamingText?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier.fillMaxSize()) {

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
