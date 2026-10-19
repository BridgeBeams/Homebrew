package com.waide.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waide.mobile.ChatState
import com.waide.mobile.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onToggleMemory: () -> Unit = {},
    onSetRemembered: (String, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val streamingText: String? = state.streaming

    val itemCount = state.messages.size + if (streamingText != null) 1 else 0
    LaunchedEffect(itemCount, streamingText?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier.fillMaxSize()) {

        // The gauge. Only lights up while the engine is actually turning.
        Row(
            Modifier
                .fillMaxWidth()
                .background(W.Surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.modelName.ifBlank { "NO MODEL LOADED" }.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.modelName.isBlank()) W.Muted else W.Amber,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (state.busy && state.tokensPerSecond > 0f) {
                Text(
                    text = String.format("%.1f tok/s", state.tokensPerSecond),
                    style = MaterialTheme.typography.labelSmall,
                    color = W.Amber
                )
            } else if (state.contextUsed > 0) {
                Text(
                    text = "${state.contextUsed} ctx",
                    style = MaterialTheme.typography.labelSmall,
                    color = W.Muted
                )
            }
        }
        HorizontalDivider(color = W.Line)

        if (state.messages.isEmpty() && streamingText == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "w-Ai-de",
                        style = MaterialTheme.typography.titleMedium,
                        color = W.Amber
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (state.modelName.isBlank())
                            "Open Models and load a .gguf file to start."
                        else "Running on this phone. Nothing leaves the device.",
                        color = W.Muted,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 48.dp)
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
                items(state.messages, key = { it.id }) { m ->
                    Bubble(
                        role = m.role,
                        text = m.text,
                        remembered = m.remembered,
                        onToggleRemembered = { onSetRemembered(m.id, !m.remembered) }
                    )
                }
                if (streamingText != null) {
                    item { Bubble("assistant", streamingText.ifBlank { "..." }, remembered = state.memoryOn, showBadge = false) }
                }
            }
        }

        state.error?.let {
            Text(
                it,
                color = W.Danger,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A1512))
                    .padding(14.dp)
            )
        }

        HorizontalDivider(color = W.Line)
        // Brain memory toggle row
        Row(
            Modifier
                .fillMaxWidth()
                .background(W.Surface)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val rememberedCount = state.messages.count { it.remembered }
            Surface(
                onClick = onToggleMemory,
                shape = RoundedCornerShape(20.dp),
                color = if (state.memoryOn) Color(0x22FF9F1C) else W.Bg,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, if (state.memoryOn) W.Amber else W.Line
                )
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (state.memoryOn) "🧠" else "🧠❌", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (state.memoryOn) "Memory on" else "Memory off",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.memoryOn) W.Amber else W.Muted
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    rememberedCount > 0 -> "$rememberedCount in memory"
                    state.memoryOn -> "remembering from here"
                    else -> "long-press a message to remember it"
                },
                style = MaterialTheme.typography.labelSmall,
                color = W.Muted
            )
        }
        Row(
            Modifier
                .background(W.Surface)
                .imePadding()
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask w-Ai-de", color = W.Muted) },
                maxLines = 6,
                shape = RoundedCornerShape(6.dp),
                enabled = state.modelName.isNotBlank(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = W.Amber,
                    unfocusedBorderColor = W.Line,
                    focusedContainerColor = W.Bg,
                    unfocusedContainerColor = W.Bg
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    if (state.busy) {
                        onStop()
                    } else {
                        val t = draft
                        draft = ""
                        onSend(t)
                    }
                },
                enabled = state.modelName.isNotBlank() && (state.busy || draft.isNotBlank()),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = W.Amber,
                    contentColor = Color(0xFF1A1204),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    role: String,
    text: String,
    remembered: Boolean = false,
    showBadge: Boolean = true,
    onToggleRemembered: () -> Unit = {}
) {
    val isUser = role == "user"
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isUser) "YOU" else "W-AI-DE",
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) W.Muted else W.Amber,
                modifier = Modifier.weight(1f)
            )
            if (showBadge) {
                // 🧠 remembered, 🧠❌ not remembered. Tap or long-press to flip.
                Box {
                    Text(
                        if (remembered) "🧠" else "🧠❌",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { menuOpen = true },
                                onLongClick = { menuOpen = true }
                            )
                            .padding(start = 6.dp, end = 2.dp)
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (remembered) "🧠❌  Delete from memory" else "🧠  Add to memory") },
                            onClick = { menuOpen = false; onToggleRemembered() }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        val bubbleMod = Modifier
            .fillMaxWidth()
            .then(
                if (isUser) Modifier
                    .border(
                        1.dp,
                        if (remembered && showBadge) W.Amber else W.Line,
                        RoundedCornerShape(4.dp)
                    )
                    .background(W.Surface, RoundedCornerShape(4.dp))
                    .padding(12.dp)
                else Modifier.padding(start = 2.dp)
            )
        // Selection stays available for copy; the memory badge handles remember/forget.
        SelectionContainer {
            Text(
                text = text,
                color = W.Text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal,
                modifier = bubbleMod
            )
        }
    }
}
