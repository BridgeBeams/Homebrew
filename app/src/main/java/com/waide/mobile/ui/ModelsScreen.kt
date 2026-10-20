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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waide.mobile.GgufFile

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
    modifier: Modifier = Modifier
) {
    var confirmDelete by remember { mutableStateOf<GgufFile?>(null) }

    Column(modifier.fillMaxSize().padding(16.dp)) {

        Text("MODELS ON THIS PHONE", style = MaterialTheme.typography.labelSmall, color = W.Amber)
        Spacer(Modifier.height(4.dp))
        Text(
            "Download any .gguf file to your Downloads folder and it shows up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = W.Muted
        )
        Spacer(Modifier.height(14.dp))

        if (!hasStorageAccess) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, W.AmberDim, RoundedCornerShape(4.dp))
                    .padding(14.dp)
            ) {
                Text(
                    "w-Ai-de needs file access to read model files from your Downloads folder.",
                    color = W.Text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onGrantStorage,
                    colors = ButtonDefaults.buttonColors(containerColor = W.Amber)
                ) { Text("Grant file access") }
            }
            Spacer(Modifier.height(14.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("Rescan") }
            Spacer(Modifier.width(10.dp))
            if (loadedName.isNotBlank()) {
                OutlinedButton(onClick = onUnload, enabled = !loading) { Text("Unload") }
            }
        }

        Spacer(Modifier.height(14.dp))

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
                Text(
                    "No .gguf files found.\nPut one in Downloads and hit Rescan.",
                    color = W.Muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models) { m ->
                    val active = m.name == loadedName
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (active) W.Amber else W.Line, RoundedCornerShape(4.dp))
                            .background(if (active) W.SurfaceHi else W.Surface, RoundedCornerShape(4.dp))
                            .clickable(enabled = !loading && !active) { onLoad(m) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = W.Text, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (active) "LOADED  ${m.sizeLabel}" else m.sizeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) W.Amber else W.Muted
                            )
                        }
                        TextButton(onClick = { confirmDelete = m }, enabled = !loading) {
                            Text("Delete", color = W.Danger)
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = {
                TextButton(onClick = { onDelete(m); confirmDelete = null }) {
                    Text("Delete", color = W.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete this model?", color = W.Text) },
            text = {
                Text(
                    "This permanently deletes ${m.name} (${m.sizeLabel}) from your phone's storage. " +
                        "You can download it again later.",
                    color = W.Muted
                )
            },
            containerColor = W.Surface
        )
    }
}
