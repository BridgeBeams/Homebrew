package com.waide.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A project as the UI needs it (id + name). Both Saved and Premium map their own
 *  project lists to this so they can share the chips row and dialogs below. */
data class ProjItem(val id: String, val name: String)

/** Horizontally scrollable filter chips: All | Unfiled | <projects...> | + Project.
 *  Long-press a project chip to rename or delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectChipsRow(
    projects: List<ProjItem>,
    filter: String,
    onFilter: (String) -> Unit,
    onNewProject: () -> Unit,
    onEditProject: (ProjItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item { Chip("All", filter == "") { onFilter("") } }
        item { Chip("Unfiled", filter == com.waide.mobile.UNFILED_FILTER) { onFilter(com.waide.mobile.UNFILED_FILTER) } }
        items(projects) { p ->
            Chip(p.name, filter == p.id, onClick = { onFilter(p.id) }, onLongClick = { onEditProject(p) })
        }
        item { NewChip(onNewProject) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) W.SurfaceHi else W.Bg)
            .border(1.dp, if (selected) W.Amber else W.Line, RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick ?: {})
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) W.Amber else W.Muted,
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NewChip(onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .border(1.dp, W.Line, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = "New project", tint = W.Amber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(3.dp))
            Text("Project", color = W.Amber, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Simple name entry used for both "create project" and "rename project". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectNameDialog(
    titleText: String, initial: String, confirmLabel: String,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirmLabel, color = W.Amber) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Muted) } },
        title = { Text(titleText, color = W.Text) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("Project name", color = W.Muted) },
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

/** Pick a target project, create a new one inline, or (optionally) remove from project. */
@Composable
fun ProjectPickerDialog(
    projects: List<ProjItem>,
    heading: String,
    showRemove: Boolean,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = W.Muted) } },
        title = { Text(heading, color = W.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (creating) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        placeholder = { Text("New project name", color = W.Muted) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                            focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                            focusedTextColor = W.Text, unfocusedTextColor = W.Text
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = { if (newName.isNotBlank()) onCreate(newName.trim()) }) {
                            Text("Create & add", color = W.Amber)
                        }
                        TextButton(onClick = { creating = false }) { Text("Back", color = W.Muted) }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = W.Amber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New project...", color = W.Amber, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(color = W.Line)
                    if (projects.isEmpty()) {
                        Text("No projects yet.", color = W.Muted, modifier = Modifier.padding(vertical = 12.dp))
                    } else {
                        projects.forEach { p ->
                            Text(
                                p.name, color = W.Text, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 12.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            HorizontalDivider(color = W.Line)
                        }
                    }
                    if (showRemove) {
                        Text(
                            "Remove from project", color = W.Danger, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onRemove() }.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        containerColor = W.Surface
    )
}

/** Shown on a project chip long-press: rename or delete that project. */
@Composable
fun ProjectEditDialog(
    project: ProjItem,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = W.Muted) } },
        title = { Text(project.name, color = W.Text) },
        text = {
            Column {
                Text(
                    "Rename project", color = W.Amber, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onRename() }.padding(vertical = 12.dp)
                )
                HorizontalDivider(color = W.Line)
                Text(
                    "Delete project", color = W.Danger, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onDelete() }.padding(vertical = 12.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text("Deleting a project keeps its chats; they move back to Unfiled.",
                    color = W.Muted, style = MaterialTheme.typography.labelSmall)
            }
        },
        containerColor = W.Surface
    )
}
