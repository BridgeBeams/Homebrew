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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
                items(state.messages) { m -> Bubble(m.role, m.text) }
                if (streamingText != null) {
                    item { Bubble("assistant", streamingText.ifBlank { "..." }) }
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

@Composable
private fun Bubble(role: String, text: String) {
    val isUser = role == "user"
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (isUser) "YOU" else "W-AI-DE",
            style = MaterialTheme.typography.labelSmall,
            color = if (isUser) W.Muted else W.Amber
        )
        Spacer(Modifier.height(5.dp))
        SelectionContainer {
            Text(
                text = text,
                color = W.Text,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (isUser) FontFamily.Default else FontFamily.Default,
                fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isUser) Modifier
                            .border(1.dp, W.Line, RoundedCornerShape(4.dp))
                            .background(W.Surface, RoundedCornerShape(4.dp))
                            .padding(12.dp)
                        else Modifier.padding(start = 2.dp)
                    )
            )
        }
    }
}
