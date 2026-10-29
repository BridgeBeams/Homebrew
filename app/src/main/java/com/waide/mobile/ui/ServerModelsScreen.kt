package com.waide.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.waide.mobile.ServerState

/**
 * One place to manage the server's models, reached from Settings. It handles the
 * whole server side: the amnesia Server tab and the private Conversations tab both
 * use the answering model and image reader chosen here (they share one Ollama box).
 *
 * - Tap a model to make it the answering model.
 * - "Reader" sets it as the image reader (used when you attach a photo).
 * - "Delete" removes it from the PC.
 * - Search Hugging Face up top to find and pull new ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerModelsScreen(
    state: ServerState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSetVisionModel: (String) -> Unit,
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

        // What's active right now.
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("Answering model: ${state.selectedModel.ifBlank { "none" }}",
                color = W.Text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text("Image reader: ${state.visionModel.ifBlank { "none" }}",
                color = W.Muted, style = MaterialTheme.typography.labelMedium)
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

        state.error?.let { ConnErrorBar(it) }

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

            item {
                Text("ON THE SERVER (tap to use as answering model)",
                    style = MaterialTheme.typography.labelSmall, color = W.Amber)
            }
            if (state.models.isEmpty()) {
                item { Text("Nothing installed yet.", color = W.Muted, style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(state.models) { name ->
                    val isAnswer = name == state.selectedModel
                    val isReader = name == state.visionModel
                    Row(
                        Modifier.fillMaxWidth()
                            .border(1.dp, if (isAnswer) W.Amber else W.Line, RoundedCornerShape(4.dp))
                            .background(W.Surface, RoundedCornerShape(4.dp))
                            .clickable(enabled = !state.managingBusy) { onSelectModel(name) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, color = if (isAnswer) W.Amber else W.Text,
                                style = MaterialTheme.typography.bodyMedium)
                            if (isAnswer || isReader) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    listOfNotNull(
                                        if (isAnswer) "answering model" else null,
                                        if (isReader) "image reader" else null
                                    ).joinToString("  ·  "),
                                    color = W.Amber, style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        TextButton(onClick = { onSetVisionModel(name) }, enabled = !state.managingBusy) {
                            Text(if (isReader) "Reader ✓" else "Reader",
                                color = if (isReader) W.Amber else W.Muted)
                        }
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
