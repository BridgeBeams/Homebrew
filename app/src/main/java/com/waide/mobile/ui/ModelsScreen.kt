package com.waide.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waide.mobile.Fit
import com.waide.mobile.GgufFile
import com.waide.mobile.HfFileOption
import com.waide.mobile.LocalModelBrowse

@Composable
fun ModelsScreen(
    models: List<GgufFile>,
    loadedName: String,
    loading: Boolean,
    hasStorageAccess: Boolean,
    onGrantStorage: () -> Unit,
    onRefresh: () -> Unit,
    onLoad: (GgufFile) -> Unit,
    onUnload: () -> Unit,
    onDelete: (GgufFile) -> Unit = {},
    onBack: (() -> Unit)? = null,
    // find & download from Hugging Face
    browse: LocalModelBrowse = LocalModelBrowse(),
    onSearchHf: (String) -> Unit = {},
    onOpenQuants: (String) -> Unit = {},
    onCloseQuants: () -> Unit = {},
    onCloseSearch: () -> Unit = {},
    onDownload: (String, HfFileOption) -> Unit = { _, _ -> },
    onCancelDownload: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var confirmDelete by remember { mutableStateOf<GgufFile?>(null) }
    var query by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize().padding(16.dp)) {

        onBack?.let { back ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, contentPadding = PaddingValues(0.dp)) { Text("< Back", color = W.Amber) }
            }
        }

        if (!hasStorageAccess) {
            Column(
                Modifier.fillMaxWidth().border(1.dp, W.AmberDim, RoundedCornerShape(4.dp)).padding(14.dp)
            ) {
                Text("w-Ai-de needs file access to read model files from your Downloads folder.",
                    color = W.Text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onGrantStorage, colors = ButtonDefaults.buttonColors(containerColor = W.Amber)) {
                    Text("Grant file access")
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ---- find a model on Hugging Face ----
        Text("FIND A MODEL", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(6.dp))
        Text("Search Hugging Face and download straight to this phone. The tag next to each " +
            "file says whether it should run smoothly here.",
            color = W.Muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f), singleLine = true, enabled = !browse.searching && !browse.downloading,
                placeholder = { Text("e.g. qwen2.5 3b gguf", color = W.Muted) },
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                    focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                    focusedTextColor = W.Text, unfocusedTextColor = W.Text
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSearchHf(query) },
                enabled = !browse.searching && !browse.downloading && query.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = W.Amber, disabledContainerColor = W.SurfaceHi)
            ) { Text("Search") }
        }

        // download progress
        if (browse.downloading) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = W.Amber, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(browse.downloadStatus ?: "Downloading...", color = W.Muted,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancelDownload) { Text("Cancel", color = W.Danger) }
            }
        }
        browse.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = W.Danger, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = W.Line)
        Spacer(Modifier.height(14.dp))

        // ---- installed on this phone ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ON THIS PHONE", style = MaterialTheme.typography.labelSmall, color = W.Amber,
                modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("Rescan") }
            if (loadedName.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onUnload, enabled = !loading) { Text("Unload") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Tap a model to load it. Any .gguf in your Downloads folder shows up here too.",
            color = W.Muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(10.dp))

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = W.Amber, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Loading into memory. A big model takes a minute.", color = W.Muted)
            }
            Spacer(Modifier.height(14.dp))
        }

        if (models.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No models yet.\nSearch above to download one, or drop a .gguf in Downloads and Rescan.",
                    color = W.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models) { m ->
                    val active = m.name == loadedName
                    Row(
                        Modifier.fillMaxWidth()
                            .border(1.dp, if (active) W.Amber else W.Line, RoundedCornerShape(4.dp))
                            .background(if (active) W.SurfaceHi else W.Surface, RoundedCornerShape(4.dp))
                            .clickable(enabled = !loading && !active) { onLoad(m) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = W.Text, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(3.dp))
                            Text(if (active) "LOADED  ${m.sizeLabel}" else m.sizeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) W.Amber else W.Muted)
                        }
                        TextButton(onClick = { confirmDelete = m }, enabled = !loading) {
                            Text("Delete", color = W.Danger)
                        }
                    }
                }
            }
        }
    }

    // ---- search-results dialog (hidden while a version is being picked or a file downloads) ----
    if (browse.quantRepo == null && !browse.downloading && (browse.results.isNotEmpty() || browse.searching)) {
        AlertDialog(
            onDismissRequest = onCloseSearch,
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCloseSearch) { Text("Close", color = W.Muted) } },
            title = { Text("Search results", color = W.Text) },
            text = {
                if (browse.searching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = W.Amber, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Searching Hugging Face...", color = W.Muted)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(browse.results) { r ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .border(1.dp, W.Line, RoundedCornerShape(4.dp))
                                    .clickable(enabled = !browse.downloading) { onOpenQuants(r.id) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.id, color = W.Text, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(2.dp))
                                    Text("updated ${r.updated}  ·  ${r.downloads} downloads",
                                        color = W.Muted, style = MaterialTheme.typography.labelSmall)
                                }
                                Text("Pick >", color = W.Amber, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            },
            containerColor = W.Surface
        )
    }

    // ---- quant picker with recommendations ----
    browse.quantRepo?.let { repo ->
        AlertDialog(
            onDismissRequest = onCloseQuants,
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCloseQuants) { Text("Cancel", color = W.Muted) } },
            title = { Text("Pick a version", color = W.Text) },
            text = {
                if (browse.loadingQuants) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = W.Amber, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Reading versions...", color = W.Muted)
                    }
                } else if (browse.quants.isEmpty()) {
                    Text("No .gguf versions found in that repo.", color = W.Muted)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text("Smaller files run faster and use less memory. The tag is this phone's guide.",
                                color = W.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        items(browse.quants) { q ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .border(1.dp, W.Line, RoundedCornerShape(4.dp))
                                    .clickable(enabled = !browse.downloading) { onDownload(repo, q) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(q.label, color = W.Text, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(3.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(q.sizeLabel, color = W.Muted, style = MaterialTheme.typography.labelSmall)
                                        val (label, color) = fitBadge(q.fit)
                                        if (label.isNotBlank()) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                Text("Download", color = W.Amber, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            },
            containerColor = W.Surface
        )
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = {
                TextButton(onClick = { onDelete(m); confirmDelete = null }) { Text("Delete", color = W.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete this model?", color = W.Text) },
            text = {
                Text("This permanently deletes ${m.name} (${m.sizeLabel}) from your phone's storage. " +
                    "You can download it again later.", color = W.Muted)
            },
            containerColor = W.Surface
        )
    }
}

// Colored one-word verdict for how a file should run on this phone.
private fun fitBadge(fit: Fit): Pair<String, Color> = when (fit) {
    Fit.SMOOTH -> "Runs smoothly" to Color(0xFF5FB06B)
    Fit.TIGHT  -> "Might be slow" to W.Amber
    Fit.TOOBIG -> "Too big for this phone" to W.Danger
    Fit.UNKNOWN -> "" to W.Muted
}
