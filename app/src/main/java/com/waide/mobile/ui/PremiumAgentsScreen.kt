package com.waide.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waide.mobile.PremiumAgentMsg
import com.waide.mobile.PremiumAgentState
import com.waide.mobile.PremiumAgentSummary
import com.waide.mobile.PremiumModel

@Composable
fun PremiumAgentsPane(
    state: PremiumAgentState,
    models: List<PremiumModel>, loadingModels: Boolean, favorites: Set<String>,
    defaultModel: String,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (String, String, String) -> Unit,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onEdit: (String, String, String, String) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDeleteMsg: (String) -> Unit,
    onHide: (String) -> Unit,
    onRequestUnhide: (String) -> Unit,
    onSubmitUnhide: (String) -> Unit,
    onCancelUnhide: () -> Unit,
    onSetModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.openId == null) {
        PremiumAgentList(state, models, loadingModels, favorites, defaultModel,
            onRefresh, onOpen, onNew, onEdit, onDeleteAgent, onHide,
            onRequestUnhide, onSubmitUnhide, onCancelUnhide, onRefreshModels, onToggleFavorite, modifier)
    } else {
        PremiumAgentChat(state, models, loadingModels, favorites,
            onClose, onSend, onStop, onEdit, onDeleteAgent, onDeleteMsg,
            onSetModel, onRefreshModels, onToggleFavorite, modifier)
    }
}

@Composable
private fun PremiumAgentList(
    state: PremiumAgentState,
    models: List<PremiumModel>, loadingModels: Boolean, favorites: Set<String>, defaultModel: String,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (String, String, String) -> Unit,
    onEdit: (String, String, String, String) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onHide: (String) -> Unit,
    onRequestUnhide: (String) -> Unit,
    onSubmitUnhide: (String) -> Unit,
    onCancelUnhide: () -> Unit,
    onRefreshModels: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var editFor by remember { mutableStateOf<PremiumAgentSummary?>(null) }
    var deleteFor by remember { mutableStateOf<PremiumAgentSummary?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Premium agents", style = MaterialTheme.typography.titleMedium, color = W.Amber,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                if (state.loadingList)
                    CircularProgressIndicator(Modifier.size(18.dp), color = W.Amber, strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = W.Amber)
            }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { createOpen = true }, colors = ButtonDefaults.buttonColors(containerColor = W.Amber),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color(0xFF1A1204), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New", color = Color(0xFF1A1204))
            }
        }
        HorizontalDivider(color = W.Line)

        Text("Premium agents read only your premium conversations, never your private Saved Chats.",
            color = W.Muted, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().background(W.SurfaceHi).padding(horizontal = 14.dp, vertical = 6.dp))

        state.error?.let {
            Text(it, color = W.Danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1512)).padding(12.dp))
        }

        if (state.agents.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.loadingList) "Loading..."
                    else "No agents yet. Tap New to create one.\nA premium agent answers using your premium chats, on a cloud model you pick.",
                    color = W.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.agents) { ag ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (ag.hidden) onRequestUnhide(ag.id) else onOpen(ag.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ag.name, color = if (ag.hidden) W.Muted else W.Text,
                                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!ag.hidden) {
                                val sub = listOf(ag.job, ag.model.substringAfterLast('/'))
                                    .filter { it.isNotBlank() }.joinToString(" · ")
                                if (sub.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(sub, color = W.Muted, style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        IconButton(onClick = { if (ag.hidden) onRequestUnhide(ag.id) else onHide(ag.id) }) {
                            Icon(
                                if (ag.hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (ag.hidden) "Reveal" else "Hide",
                                tint = if (ag.hidden) W.Amber else W.Muted
                            )
                        }
                        Box {
                            IconButton(onClick = { menuFor = ag.id }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = W.Muted)
                            }
                            DropdownMenu(expanded = menuFor == ag.id, onDismissRequest = { menuFor = null }) {
                                if (!ag.hidden) DropdownMenuItem(text = { Text("Edit", color = W.Text) },
                                    onClick = { editFor = ag; menuFor = null })
                                DropdownMenuItem(text = { Text("Delete", color = W.Danger) },
                                    onClick = { deleteFor = ag; menuFor = null })
                            }
                        }
                    }
                    HorizontalDivider(color = W.Line)
                }
            }
        }
    }

    if (createOpen) {
        PremiumAgentEditDialog("New agent", "", "", defaultModel, models, loadingModels, favorites,
            onRefreshModels, onToggleFavorite,
            onConfirm = { n, j, m -> onNew(n, j, m); createOpen = false }, onDismiss = { createOpen = false })
    }
    editFor?.let { ag ->
        PremiumAgentEditDialog("Edit agent", ag.name, ag.job, ag.model, models, loadingModels, favorites,
            onRefreshModels, onToggleFavorite,
            onConfirm = { n, j, m -> onEdit(ag.id, n, j, m); editFor = null }, onDismiss = { editFor = null })
    }
    deleteFor?.let { ag ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            confirmButton = { TextButton(onClick = { onDeleteAgent(ag.id); deleteFor = null }) { Text("Delete", color = W.Danger) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete this agent?", color = W.Text) },
            text = { Text("Removes the agent and its memory. Your premium conversations are untouched.", color = W.Muted) },
            containerColor = W.Surface
        )
    }
    state.unhideTarget?.let {
        PremiumAgentUnhidePinDialog(state.unhideError, onSubmitUnhide, onCancelUnhide)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PremiumAgentChat(
    state: PremiumAgentState,
    models: List<PremiumModel>, loadingModels: Boolean, favorites: Set<String>,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onEdit: (String, String, String, String) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDeleteMsg: (String) -> Unit,
    onSetModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier
) {
    var draft by remember { mutableStateOf("") }
    var topMenu by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var deleteMsgId by remember { mutableStateOf<String?>(null) }
    var modelPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val streamingText = state.streaming

    val itemCount = state.messages.size + if (streamingText != null) 1 else 0
    LaunchedEffect(itemCount, streamingText?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = W.Amber) }
            Column(Modifier.weight(1f)) {
                Text(state.openName.ifBlank { "Agent" }, color = W.Text,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.openJob.isNotBlank()) {
                    Text(state.openJob, color = W.Muted, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                IconButton(onClick = { topMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = W.Muted) }
                DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                    DropdownMenuItem(text = { Text("Edit", color = W.Text) }, onClick = { editOpen = true; topMenu = false })
                    DropdownMenuItem(text = { Text("Delete agent", color = W.Danger) }, onClick = { deleteOpen = true; topMenu = false })
                }
            }
        }
        HorizontalDivider(color = W.Line)

        if (state.messages.isEmpty() && streamingText == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.busy) "Thinking..." else "Ask this agent anything. It draws on your premium chats only.",
                    color = W.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(40.dp))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(state.messages) { m -> PremiumAgentBubble(m, state.openName) { deleteMsgId = m.id } }
                if (streamingText != null) item {
                    PremiumAgentBubble(PremiumAgentMsg("streaming", "assistant", streamingText.ifBlank { "..." }), state.openName) {}
                }
            }
        }

        state.error?.let {
            Text(it, color = W.Danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1512)).padding(12.dp))
        }

        HorizontalDivider(color = W.Line)
        Column(Modifier.background(W.Surface).imePadding()) {
            Row(
                Modifier.fillMaxWidth().clickable { modelPicker = true }.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Model", color = W.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(10.dp))
                Text(state.openModel.ifBlank { "pick a model" }, color = W.Text,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change model", tint = W.Muted)
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Message", color = W.Muted) }, maxLines = 6,
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    )
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (state.busy) onStop() else { val t = draft; draft = ""; onSend(t) } },
                    enabled = state.busy || draft.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = W.Amber, contentColor = Color(0xFF1A1204), disabledContainerColor = W.SurfaceHi),
                    modifier = Modifier.size(52.dp)
                ) { Icon(if (state.busy) Icons.Filled.Stop else Icons.Filled.ArrowUpward, contentDescription = if (state.busy) "Stop" else "Send") }
            }
        }
    }

    if (modelPicker) {
        PremiumModelPicker(models, loadingModels, state.openModel, onRefreshModels,
            onPick = { onSetModel(it); modelPicker = false }, onDismiss = { modelPicker = false },
            favorites = favorites, onToggleFavorite = onToggleFavorite)
    }
    if (editOpen) state.openId?.let { id ->
        PremiumAgentEditDialog("Edit agent", state.openName, state.openJob, state.openModel,
            models, loadingModels, favorites, onRefreshModels, onToggleFavorite,
            onConfirm = { n, j, m -> onEdit(id, n, j, m); editOpen = false }, onDismiss = { editOpen = false })
    }
    if (deleteOpen) state.openId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            confirmButton = { TextButton(onClick = { onDeleteAgent(id); deleteOpen = false; onClose() }) { Text("Delete", color = W.Danger) } },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete this agent?", color = W.Text) },
            text = { Text("Removes the agent and its memory. Your premium conversations are untouched.", color = W.Muted) },
            containerColor = W.Surface
        )
    }
    deleteMsgId?.let { mid ->
        AlertDialog(
            onDismissRequest = { deleteMsgId = null },
            confirmButton = { TextButton(onClick = { onDeleteMsg(mid); deleteMsgId = null }) { Text("Delete", color = W.Danger) } },
            dismissButton = { TextButton(onClick = { deleteMsgId = null }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete this message?", color = W.Text) },
            text = { Text("Removes just this message from the agent's memory.", color = W.Muted) },
            containerColor = W.Surface
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PremiumAgentBubble(m: PremiumAgentMsg, agentName: String, onLongPress: () -> Unit) {
    val isUser = m.role == "user"
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (m.text.isNotBlank()) DropdownMenuItem(text = { Text("Copy", color = W.Text) },
            onClick = { clipboard.setText(AnnotatedString(m.text)); menuOpen = false })
        DropdownMenuItem(text = { Text("Delete", color = W.Danger) },
            onClick = { menuOpen = false; onLongPress() })
    }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menuOpen = true })) {
        Text(if (isUser) "YOU" else agentName.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = if (isUser) W.Muted else W.Amber)
        Spacer(Modifier.height(5.dp))
        Text(text = m.text, color = W.Text, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().then(
                if (isUser) Modifier.border(1.dp, W.Line, RoundedCornerShape(4.dp))
                    .background(W.Surface, RoundedCornerShape(4.dp)).padding(12.dp)
                else Modifier.padding(start = 2.dp)
            ))
    }
    }
}

@Composable
private fun PremiumAgentEditDialog(
    title: String, initialName: String, initialJob: String, initialModel: String,
    models: List<PremiumModel>, loadingModels: Boolean, favorites: Set<String>,
    onRefreshModels: () -> Unit, onToggleFavorite: (String) -> Unit,
    onConfirm: (String, String, String) -> Unit, onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var job by remember { mutableStateOf(initialJob) }
    var model by remember { mutableStateOf(initialModel) }
    var pickModel by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), job.trim(), model.trim()) },
                enabled = name.isNotBlank()) { Text("Save", color = W.Amber) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Muted) } },
        title = { Text(title, color = W.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name", color = W.Muted) },
                    placeholder = { Text("e.g. Research Bot", color = W.Muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    )
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = job, onValueChange = { job = it },
                    label = { Text("Job / profession", color = W.Muted) },
                    placeholder = { Text("e.g. reviews my premium chats", color = W.Muted) },
                    modifier = Modifier.heightIn(min = 70.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    )
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().border(1.dp, W.Line, RoundedCornerShape(6.dp))
                        .clickable { pickModel = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Model", color = W.Muted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(10.dp))
                    Text(model.ifBlank { "pick a model" }, color = W.Text,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick model", tint = W.Muted)
                }
            }
        },
        containerColor = W.Surface
    )
    if (pickModel) {
        PremiumModelPicker(models, loadingModels, model, onRefreshModels,
            onPick = { model = it; pickModel = false }, onDismiss = { pickModel = false },
            favorites = favorites, onToggleFavorite = onToggleFavorite)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumAgentUnhidePinDialog(error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = { if (pin.length >= 4) onSubmit(pin) }, enabled = pin.isNotEmpty()) { Text("Reveal", color = W.Amber) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = W.Muted) } },
        title = { Text("Enter PIN to reveal", color = W.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin, onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = LocalTextStyle.current.copy(letterSpacing = 6.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    ))
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = W.Danger, style = MaterialTheme.typography.labelSmall) }
            }
        },
        containerColor = W.Surface
    )
}
