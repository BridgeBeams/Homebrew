package com.waide.mobile.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waide.mobile.ConvFile
import com.waide.mobile.PremiumAttach
import com.waide.mobile.PremiumModel
import com.waide.mobile.PremiumMsg
import com.waide.mobile.PremiumState
import com.waide.mobile.PremiumSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PremiumScreen(
    state: PremiumState,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRename: (String, String) -> Unit,
    onDeleteConv: (String) -> Unit,
    onDeleteMsg: (String) -> Unit,
    onHide: (String) -> Unit,
    onRequestUnhide: (String) -> Unit,
    onSubmitUnhide: (String) -> Unit,
    onCancelUnhide: () -> Unit,
    onImagePicked: (Uri) -> Unit,
    onFilePicked: (Uri) -> Unit,
    onClearAttachment: (Int) -> Unit,
    onSetConvModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenAskAll: () -> Unit,
    onStartAskAll: (List<String>) -> Unit,
    onDismissAskAll: () -> Unit,
    onOpenFiles: () -> Unit,
    onCloseFiles: () -> Unit,
    onRefreshFiles: () -> Unit,
    onReattachFile: (String) -> Unit,
    onDownloadFile: (String) -> Unit,
    onDismissFilesMsg: () -> Unit,
    onDismissCompacting: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.openId == null) {
        PremiumList(state, onRefresh, onNew, onOpen, onRename, onDeleteConv,
            onHide, onRequestUnhide, onSubmitUnhide, onCancelUnhide,
            onOpenAskAll, onStartAskAll, onDismissAskAll, onToggleFavorite, onRefreshModels, modifier)
    } else {
        PremiumChat(state, onClose, onSend, onStop, onRename, onDeleteConv, onDeleteMsg,
            onImagePicked, onFilePicked, onClearAttachment, onSetConvModel, onRefreshModels,
            onToggleFavorite, onOpenFiles, onCloseFiles, onRefreshFiles, onReattachFile,
            onDownloadFile, onDismissFilesMsg, onDismissCompacting, modifier)
    }
}

@Composable
private fun PremiumList(
    state: PremiumState,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDeleteConv: (String) -> Unit,
    onHide: (String) -> Unit,
    onRequestUnhide: (String) -> Unit,
    onSubmitUnhide: (String) -> Unit,
    onCancelUnhide: () -> Unit,
    onOpenAskAll: () -> Unit,
    onStartAskAll: (List<String>) -> Unit,
    onDismissAskAll: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefreshModels: () -> Unit,
    modifier: Modifier
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<PremiumSummary?>(null) }
    var deleteFor by remember { mutableStateOf<PremiumSummary?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Premium AI", style = MaterialTheme.typography.titleMedium, color = W.Amber,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                if (state.loadingList)
                    CircularProgressIndicator(Modifier.size(18.dp), color = W.Amber, strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = W.Amber)
            }
            Spacer(Modifier.width(2.dp))
            OutlinedButton(onClick = onOpenAskAll, enabled = state.hasKey,
                border = androidx.compose.foundation.BorderStroke(1.dp, W.Amber),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Icon(Icons.Filled.ViewColumn, contentDescription = null, tint = W.Amber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ask all", color = W.Amber, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onNew, colors = ButtonDefaults.buttonColors(containerColor = W.Amber),
                enabled = state.hasKey,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color(0xFF1A1204), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New", color = Color(0xFF1A1204))
            }
        }
        HorizontalDivider(color = W.Line)

        if (state.checkedKey && !state.hasKey) {
            Text("Add your OpenRouter key in Settings > Premium AI to start.",
                color = W.Amber, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(W.SurfaceHi).padding(14.dp))
        }
        state.error?.let {
            Text(it, color = W.Danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1512)).padding(12.dp))
        }

        if (state.conversations.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.loadingList) "Loading..."
                    else "No chats yet. Tap New to start one with a cloud model.",
                    color = W.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.conversations) { c ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (c.hidden) onRequestUnhide(c.id) else onOpen(c.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!c.hidden && c.mode == "multi") {
                                    Icon(Icons.Filled.ViewColumn, contentDescription = "Ask all",
                                        tint = W.Amber, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                }
                                Text(c.title, color = if (c.hidden) W.Muted else W.Text,
                                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!c.hidden) {
                                val sub = if (c.mode == "multi")
                                    c.models.joinToString(" · ") { it.substringAfterLast('/') }
                                else c.model.substringAfterLast('/')
                                if (sub.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(sub, color = W.Muted,
                                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        IconButton(onClick = { if (c.hidden) onRequestUnhide(c.id) else onHide(c.id) }) {
                            Icon(
                                if (c.hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (c.hidden) "Reveal" else "Hide",
                                tint = if (c.hidden) W.Amber else W.Muted
                            )
                        }
                        Box {
                            IconButton(onClick = { menuFor = c.id }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = W.Muted)
                            }
                            DropdownMenu(expanded = menuFor == c.id, onDismissRequest = { menuFor = null }) {
                                if (!c.hidden) DropdownMenuItem(text = { Text("Rename", color = W.Text) },
                                    onClick = { renameFor = c; menuFor = null })
                                DropdownMenuItem(text = { Text("Delete", color = W.Danger) },
                                    onClick = { deleteFor = c; menuFor = null })
                            }
                        }
                    }
                    HorizontalDivider(color = W.Line)
                }
            }
        }
    }

    renameFor?.let { c -> PremiumRenameDialog(c.title, { t -> onRename(c.id, t); renameFor = null }, { renameFor = null }) }
    deleteFor?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            confirmButton = { TextButton(onClick = { onDeleteConv(c.id); deleteFor = null }) { Text("Delete", color = W.Danger) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete this chat?", color = W.Text) },
            text = { Text("Permanently removes \"${c.title}\" from your server.", color = W.Muted) },
            containerColor = W.Surface
        )
    }
    state.unhideTarget?.let {
        PremiumUnhidePinDialog(state.unhideError, onSubmitUnhide, onCancelUnhide)
    }
    if (state.askAllPicker) {
        PremiumAskAllDialog(
            models = state.models, loading = state.loadingModels, favorites = state.favorites,
            onRefresh = onRefreshModels, onToggleFavorite = onToggleFavorite,
            onStart = onStartAskAll, onDismiss = onDismissAskAll
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PremiumChat(
    state: PremiumState,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRename: (String, String) -> Unit,
    onDeleteConv: (String) -> Unit,
    onDeleteMsg: (String) -> Unit,
    onImagePicked: (Uri) -> Unit,
    onFilePicked: (Uri) -> Unit,
    onClearAttachment: (Int) -> Unit,
    onSetConvModel: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onCloseFiles: () -> Unit,
    onRefreshFiles: () -> Unit,
    onReattachFile: (String) -> Unit,
    onDownloadFile: (String) -> Unit,
    onDismissFilesMsg: () -> Unit,
    onDismissCompacting: () -> Unit,
    modifier: Modifier
) {
    var draft by remember { mutableStateOf("") }
    var topMenu by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var deleteMsgId by remember { mutableStateOf<String?>(null) }
    var zoomB64 by remember { mutableStateOf<String?>(null) }
    var modelPicker by remember { mutableStateOf(false) }
    val isMulti = state.openMode == "multi"
    val listState = rememberLazyListState()
    val streamingText = state.streaming

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImagePicked(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    // group messages for the labeled-column view when this is an "ask all" chat
    val segments = remember(state.messages, isMulti) {
        if (!isMulti) emptyList()
        else buildMultiSegments(state.messages)
    }
    val multiStreamLen = state.multiStreaming.values.sumOf { it.length }
    val itemCount = if (isMulti) segments.size + (if (state.busy) 1 else 0)
        else state.messages.size + if (streamingText != null) 1 else 0
    LaunchedEffect(itemCount, streamingText?.length, multiStreamLen) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = W.Amber) }
            Text(state.openTitle.ifBlank { "Chat" }, color = W.Text,
                style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenFiles) {
                Icon(Icons.Filled.Folder, contentDescription = "Conversation files", tint = W.Amber)
            }
            Box {
                IconButton(onClick = { topMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = W.Muted) }
                DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                    DropdownMenuItem(text = { Text("Rename", color = W.Text) }, onClick = { renameOpen = true; topMenu = false })
                    DropdownMenuItem(text = { Text("Delete chat", color = W.Danger) }, onClick = { deleteOpen = true; topMenu = false })
                }
            }
        }
        HorizontalDivider(color = W.Line)

        // transient "Compacting..." notice when old turns were just summarized
        if (state.compactingNotice) {
            LaunchedEffect(state.compactedAt) {
                kotlinx.coroutines.delay(4000); onDismissCompacting()
            }
            Text("Compacting this conversation so we can keep chatting...",
                color = Color(0xFF1A1204), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().background(W.Amber).padding(horizontal = 14.dp, vertical = 8.dp))
        } else if (state.compactedCount > 0) {
            Text("Earlier turns are summarized to keep this chat fast. Your full history is still shown below, and uploaded files are in the folder.",
                color = W.Muted, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().background(W.SurfaceHi).padding(horizontal = 14.dp, vertical = 6.dp))
        }
        state.filesMsg?.let { msg ->
            Row(Modifier.fillMaxWidth().background(W.SurfaceHi).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(msg, color = W.Text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissFilesMsg, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("OK", color = W.Amber)
                }
            }
        }

        val emptyNow = state.messages.isEmpty() && (if (isMulti) !state.busy else streamingText == null)
        if (emptyNow) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val hint = if (isMulti)
                    "Ask all ${state.openModels.size} models at once. Each answers in its own column."
                else "Ask ${state.openModel.substringAfterLast('/')} anything."
                Text(if (state.busy) "Thinking..." else hint,
                    color = W.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(40.dp))
            }
        } else if (isMulti) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(segments) { seg ->
                    if (seg.user != null) PremiumBubble(seg.user, onImageTap = { zoomB64 = it }) { deleteMsgId = seg.user.id }
                    else PremiumMultiColumns(
                        models = state.openModels,
                        replies = seg.replies,
                        streaming = emptyMap(),
                        done = emptySet(),
                        onImageTap = { zoomB64 = it }
                    )
                }
                if (state.busy) item {
                    PremiumMultiColumns(
                        models = state.openModels,
                        replies = emptyList(),
                        streaming = state.multiStreaming,
                        done = state.multiDone,
                        onImageTap = { zoomB64 = it }
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
                items(state.messages) { m -> PremiumBubble(m, onImageTap = { zoomB64 = it }) { deleteMsgId = m.id } }
                if (streamingText != null) item {
                    PremiumBubble(PremiumMsg("streaming", "assistant", streamingText.ifBlank { "..." }, state.openModel)) {}
                }
            }
        }

        state.error?.let {
            Text(it, color = W.Danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1512)).padding(12.dp))
        }

        HorizontalDivider(color = W.Line)
        Column(Modifier.background(W.Surface).imePadding()) {
            // model row — single: tap to change; multi: fixed list of the models being asked
            if (isMulti) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ViewColumn, contentDescription = null, tint = W.Amber, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(state.openModels.joinToString(" · ") { it.substringAfterLast('/') },
                        color = W.Muted, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            } else {
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
            }

            // pending attachments
            if (state.pendingAttachments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    state.pendingAttachments.forEachIndexed { i, a ->
                        Row(
                            Modifier.border(1.dp, W.Line, RoundedCornerShape(4.dp)).padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (a.kind == "image" && a.data != null) {
                                val bmp = remember(a.data) { decodeB64(a.data) }
                                if (bmp != null) Image(bmp, "image", Modifier.size(34.dp), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Filled.AttachFile, null, tint = W.Muted, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(a.name, color = W.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { onClearAttachment(i) }, contentPadding = PaddingValues(0.dp)) {
                                Text("x", color = W.Danger)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }

            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                FilledIconButton(
                    onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = W.SurfaceHi, contentColor = W.Amber),
                    modifier = Modifier.size(46.dp)
                ) { Icon(Icons.Filled.Image, contentDescription = "Attach image") }
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = { filePicker.launch("*/*") },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = W.SurfaceHi, contentColor = W.Amber),
                    modifier = Modifier.size(46.dp)
                ) { Icon(Icons.Filled.AttachFile, contentDescription = "Attach file") }
                Spacer(Modifier.width(6.dp))
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
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = { if (state.busy) onStop() else { val t = draft; draft = ""; onSend(t) } },
                    enabled = state.busy || draft.isNotBlank() || state.pendingAttachments.isNotEmpty(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = W.Amber, contentColor = Color(0xFF1A1204), disabledContainerColor = W.SurfaceHi),
                    modifier = Modifier.size(52.dp)
                ) { Icon(if (state.busy) Icons.Filled.Stop else Icons.Filled.ArrowUpward, contentDescription = if (state.busy) "Stop" else "Send") }
            }
        }
    }

    if (modelPicker) {
        PremiumModelPicker(state.models, state.loadingModels, state.openModel, onRefreshModels,
            onPick = { onSetConvModel(it); modelPicker = false }, onDismiss = { modelPicker = false },
            favorites = state.favorites, onToggleFavorite = onToggleFavorite)
    }
    if (renameOpen) state.openId?.let { id ->
        PremiumRenameDialog(state.openTitle, { t -> onRename(id, t); renameOpen = false }, { renameOpen = false })
    }
    if (deleteOpen) state.openId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            confirmButton = { TextButton(onClick = { onDeleteConv(id); deleteOpen = false; onClose() }) { Text("Delete", color = W.Danger) } },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete this chat?", color = W.Text) },
            text = { Text("Permanently removes this chat from your server.", color = W.Muted) },
            containerColor = W.Surface
        )
    }
    deleteMsgId?.let { mid ->
        AlertDialog(
            onDismissRequest = { deleteMsgId = null },
            confirmButton = { TextButton(onClick = { onDeleteMsg(mid); deleteMsgId = null }) { Text("Delete", color = W.Danger) } },
            dismissButton = { TextButton(onClick = { deleteMsgId = null }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete this message?", color = W.Text) },
            text = { Text("Removes just this message.", color = W.Muted) },
            containerColor = W.Surface
        )
    }
    zoomB64?.let { b64 -> PremiumZoomDialog(b64) { zoomB64 = null } }
    if (state.filesOpen) {
        ConvFilesDialog(
            files = state.files, loading = state.loadingFiles,
            onReattach = onReattachFile, onDownload = onDownloadFile,
            onRefresh = onRefreshFiles, onClose = onCloseFiles
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PremiumBubble(m: PremiumMsg, onImageTap: (String) -> Unit = {}, onLongPress: () -> Unit) {
    val isUser = m.role == "user"
    val who = if (isUser) "YOU" else m.model.substringAfterLast('/').ifBlank { "AI" }.uppercase()
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onLongPress)) {
        Text(who, style = MaterialTheme.typography.labelSmall, color = if (isUser) W.Muted else W.Amber)
        Spacer(Modifier.height(5.dp))
        m.attachments.forEach { a ->
            if (a.kind == "image" && a.data != null) {
                val bmp = remember(a.data) { decodeB64(a.data) }
                if (bmp != null) {
                    Image(bmp, "attached image", contentScale = ContentScale.Fit,
                        modifier = Modifier.sizeIn(maxHeight = 220.dp).fillMaxWidth(0.7f)
                            .clickable { onImageTap(a.data) }.border(1.dp, W.Line, RoundedCornerShape(6.dp)))
                    Spacer(Modifier.height(6.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Filled.AttachFile, null, tint = W.Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(a.name, color = W.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (m.text.isNotBlank()) {
            Text(text = m.text, color = W.Text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().then(
                    if (isUser) Modifier.border(1.dp, W.Line, RoundedCornerShape(4.dp))
                        .background(W.Surface, RoundedCornerShape(4.dp)).padding(12.dp)
                    else Modifier.padding(start = 2.dp)
                ))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumModelPicker(
    models: List<PremiumModel>, loading: Boolean, current: String,
    onRefresh: () -> Unit, onPick: (String) -> Unit, onDismiss: () -> Unit,
    favorites: Set<String> = emptySet(), onToggleFavorite: (String) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (models.isEmpty()) onRefresh() }
    val filtered = remember(query, models, favorites) {
        val base = if (query.isBlank()) models
            else models.filter { it.name.contains(query, true) || it.id.contains(query, true) }
        // favorites float to the top, keeping their original order within each group
        base.sortedByDescending { favorites.contains(it.id) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = W.Muted) } },
        title = { Text("Pick a model", color = W.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("search, e.g. claude, gpt, grok", color = W.Muted) },
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    )
                )
                Spacer(Modifier.height(8.dp))
                if (loading && models.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = W.Amber, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Loading models...", color = W.Muted)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filtered) { mdl ->
                            val fav = favorites.contains(mdl.id)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onToggleFavorite(mdl.id) }, modifier = Modifier.size(38.dp)) {
                                    Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = if (fav) "Unfavorite" else "Favorite",
                                        tint = if (fav) W.Amber else W.Muted, modifier = Modifier.size(20.dp))
                                }
                                Column(
                                    Modifier.weight(1f).clickable { onPick(mdl.id) }.padding(vertical = 8.dp)
                                ) {
                                    Text(mdl.name, color = if (mdl.id == current) W.Amber else W.Text,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(mdl.id, color = W.Muted, style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            HorizontalDivider(color = W.Line)
                        }
                    }
                }
            }
        },
        containerColor = W.Surface
    )
}

@Composable
private fun PremiumRenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Save", color = W.Amber) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Muted) } },
        title = { Text("Rename chat", color = W.Text) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                    focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                    focusedTextColor = W.Text, unfocusedTextColor = W.Text
                ))
        },
        containerColor = W.Surface
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumUnhidePinDialog(error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
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

@Composable
private fun PremiumZoomDialog(b64: String, onDismiss: () -> Unit) {
    val bmp = remember(b64) { decodeB64(b64) } ?: return
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { z, p, _ -> scale = (scale * z).coerceIn(1f, 6f); offset += p }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
            Image(bmp, "full image", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .transformable(transform))
        }
    }
}

// ---------------- ask-all (multi-model) helpers ----------------

/** A chunk of a multi chat: either one user turn, or the group of model replies to it. */
private class MultiSegment(val user: PremiumMsg?, val replies: List<PremiumMsg>)

private fun buildMultiSegments(messages: List<PremiumMsg>): List<MultiSegment> {
    val out = ArrayList<MultiSegment>()
    val group = ArrayList<PremiumMsg>()
    fun flush() { if (group.isNotEmpty()) { out.add(MultiSegment(null, group.toList())); group.clear() } }
    for (m in messages) {
        if (m.role == "user") { flush(); out.add(MultiSegment(m, emptyList())) } else group.add(m)
    }
    flush()
    return out
}

/** A horizontally-scrolling row of labeled columns, one per model. Swipe to see the rest. */
@Composable
private fun PremiumMultiColumns(
    models: List<String>,
    replies: List<PremiumMsg>,
    streaming: Map<Int, String>,
    done: Set<Int>,
    onImageTap: (String) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        models.forEachIndexed { idx, modelId ->
            // match a stored reply by model id first, then fall back to position
            val replyMsg = replies.firstOrNull { it.model == modelId }
                ?: replies.getOrNull(idx)
            val streamText = streaming[idx]
            val text = replyMsg?.text ?: streamText ?: ""
            val isStreaming = replyMsg == null && streamText != null && !done.contains(idx)
            Column(
                Modifier.width(272.dp)
                    .border(1.dp, W.Line, RoundedCornerShape(6.dp))
                    .background(W.Surface, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text(modelId.substringAfterLast('/').uppercase(),
                    style = MaterialTheme.typography.labelSmall, color = W.Amber,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                if (text.isBlank() && isStreaming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = W.Amber, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("thinking...", color = W.Muted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Text(text.ifBlank { "—" }, color = W.Text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumAskAllDialog(
    models: List<PremiumModel>, loading: Boolean, favorites: Set<String>,
    onRefresh: () -> Unit, onToggleFavorite: (String) -> Unit,
    onStart: (List<String>) -> Unit, onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { if (models.isEmpty()) onRefresh() }
    val filtered = remember(query, models, favorites) {
        val base = if (query.isBlank()) models
            else models.filter { it.name.contains(query, true) || it.id.contains(query, true) }
        base.sortedByDescending { favorites.contains(it.id) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (selected.isNotEmpty()) onStart(selected.toList()) },
                enabled = selected.isNotEmpty()) {
                Text(if (selected.isEmpty()) "Ask all" else "Ask ${selected.size}",
                    color = if (selected.isEmpty()) W.Muted else W.Amber)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Muted) } },
        title = { Text("Ask all — pick up to 3", color = W.Text) },
        text = {
            Column {
                Text("Your question goes to every model you pick; their answers show side by side.",
                    color = W.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("search, e.g. claude, gpt, grok", color = W.Muted) },
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    )
                )
                Spacer(Modifier.height(8.dp))
                if (loading && models.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = W.Amber, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("Loading models...", color = W.Muted)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filtered) { mdl ->
                            val fav = favorites.contains(mdl.id)
                            val checked = selected.contains(mdl.id)
                            val atCap = selected.size >= 3
                            fun toggle() {
                                if (checked) selected.remove(mdl.id)
                                else if (!atCap) selected.add(mdl.id)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onToggleFavorite(mdl.id) }, modifier = Modifier.size(38.dp)) {
                                    Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = if (fav) "Unfavorite" else "Favorite",
                                        tint = if (fav) W.Amber else W.Muted, modifier = Modifier.size(20.dp))
                                }
                                Column(Modifier.weight(1f).clickable { toggle() }.padding(vertical = 8.dp)) {
                                    Text(mdl.name, color = if (checked) W.Amber else W.Text,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(mdl.id, color = W.Muted, style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Checkbox(
                                    checked = checked, onCheckedChange = { toggle() },
                                    enabled = checked || !atCap,
                                    colors = CheckboxDefaults.colors(checkedColor = W.Amber,
                                        uncheckedColor = W.Muted, checkmarkColor = Color(0xFF1A1204))
                                )
                            }
                            HorizontalDivider(color = W.Line)
                        }
                    }
                }
            }
        },
        containerColor = W.Surface
    )
}

/** Shared conversation-files dialog, reused by Premium and Saved Chats. */
@Composable
fun ConvFilesDialog(
    files: List<ConvFile>, loading: Boolean,
    onReattach: (String) -> Unit, onDownload: (String) -> Unit,
    onRefresh: () -> Unit, onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Close", color = W.Muted) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = W.Amber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Conversation files", color = W.Text, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = W.Amber, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = W.Muted)
                }
            }
        },
        text = {
            if (files.isEmpty()) {
                Text(if (loading) "Loading..." else "No files yet. Images and files you attach in this chat are kept here so you can download or resend them later.",
                    color = W.Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(files) { f ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (f.kind == "image") Icons.Filled.Image else Icons.Filled.AttachFile,
                                    contentDescription = null, tint = W.Muted, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(f.name, color = W.Text, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            Text("${fmtWhen(f.added)} · ${fmtSize(f.size)}", color = W.Muted,
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 26.dp))
                            Row(Modifier.padding(start = 20.dp, top = 2.dp)) {
                                TextButton(onClick = { onReattach(f.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Filled.AttachFile, null, tint = W.Amber, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp)); Text("Reattach", color = W.Amber)
                                }
                                TextButton(onClick = { onDownload(f.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Icon(Icons.Filled.Download, null, tint = W.Amber, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp)); Text("Download", color = W.Amber)
                                }
                            }
                        }
                        HorizontalDivider(color = W.Line)
                    }
                }
            }
        },
        containerColor = W.Surface
    )
}

private fun fmtWhen(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    return runCatching {
        SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))
    }.getOrDefault("")
}

private fun fmtSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

private fun decodeB64(b64: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
