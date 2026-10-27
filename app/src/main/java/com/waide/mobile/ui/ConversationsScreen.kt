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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waide.mobile.AgentState
import com.waide.mobile.ConvHit
import com.waide.mobile.ConvMsg
import com.waide.mobile.ConvState
import com.waide.mobile.ConvSummary

@Composable
fun ConversationsScreen(
    state: ConvState,
    onUnlock: (String) -> Unit,
    onRefreshList: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
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
    onClearImage: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    // conversation files folder + compaction
    onOpenFiles: () -> Unit,
    onCloseFiles: () -> Unit,
    onRefreshFiles: () -> Unit,
    onReattachFile: (String) -> Unit,
    onDownloadFile: (String) -> Unit,
    onDismissFilesMsg: () -> Unit,
    onDismissCompacting: () -> Unit,
    // agents (same PIN session)
    agentState: AgentState,
    onAgentRefresh: () -> Unit,
    onAgentOpen: (String) -> Unit,
    onAgentNew: (String, String, Boolean) -> Unit,
    onAgentClose: () -> Unit,
    onAgentSend: (String) -> Unit,
    onAgentStop: () -> Unit,
    onAgentEdit: (String, String, String, Boolean) -> Unit,
    onAgentDelete: (String) -> Unit,
    onAgentDeleteMsg: (String) -> Unit,
    onAgentHide: (String) -> Unit,
    onAgentRequestUnhide: (String) -> Unit,
    onAgentSubmitUnhide: (String) -> Unit,
    onAgentCancelUnhide: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.locked) {
        PinGate(state, onUnlock, modifier)
        return
    }
    // Unlocked: a Chats | Agents toggle sits on top, hidden while a thread is open.
    var section by remember { mutableStateOf(0) }   // 0 = Chats, 1 = Agents
    val showToggle = state.openId == null && agentState.openId == null
    Box(modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        if (showToggle) {
            Row(
                Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTab("Chats", section == 0, Modifier.weight(1f)) { section = 0 }
                Spacer(Modifier.width(8.dp))
                SectionTab("Agents", section == 1, Modifier.weight(1f)) { section = 1 }
            }
            HorizontalDivider(color = W.Line)
        }
        if (section == 0) {
            if (state.openId == null)
                ConvList(state, onRefreshList, onOpen, onNew, onRename, onDeleteConv,
                    onHide, onRequestUnhide, onSubmitUnhide, onCancelUnhide, onSearch, onClearSearch,
                    Modifier.weight(1f))
            else
                ConvChat(state, onClose, onSend, onStop, onDeleteMsg, onRename,
                    onDeleteConv, onImagePicked, onClearImage,
                    onOpenFiles, onCloseFiles, onRefreshFiles, onReattachFile,
                    onDownloadFile, onDismissFilesMsg, onDismissCompacting, Modifier.weight(1f))
        } else {
            AgentsPane(
                state = agentState,
                onRefresh = onAgentRefresh,
                onOpen = onAgentOpen,
                onNew = onAgentNew,
                onClose = onAgentClose,
                onSend = onAgentSend,
                onStop = onAgentStop,
                onEdit = onAgentEdit,
                onDeleteAgent = onAgentDelete,
                onDeleteMsg = onAgentDeleteMsg,
                onHide = onAgentHide,
                onRequestUnhide = onAgentRequestUnhide,
                onSubmitUnhide = onAgentSubmitUnhide,
                onCancelUnhide = onAgentCancelUnhide,
                modifier = Modifier.weight(1f)
            )
        }
      }

      // A short "Welcome Wade" greeting right after unlocking, then it fades out.
      var showWelcome by remember { mutableStateOf(false) }
      LaunchedEffect(state.locked) {
          if (!state.locked) {
              showWelcome = true
              kotlinx.coroutines.delay(4000)
              showWelcome = false
          }
      }
      if (showWelcome) {
          Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
              Text("Welcome Wade", color = W.Amber, style = MaterialTheme.typography.headlineMedium)
          }
      }
    }
}

@Composable
fun SectionTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) W.SurfaceHi else W.Bg)
            .border(1.dp, if (selected) W.Amber else W.Line, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) W.Amber else W.Muted,
            style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinGate(state: ConvState, onUnlock: (String) -> Unit, modifier: Modifier) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(state.authError) { if (state.authError != null) pin = "" }

    Column(
        modifier.fillMaxSize().imePadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = W.Amber, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text("Conversations", style = MaterialTheme.typography.titleLarge, color = W.Text)
        Spacer(Modifier.height(8.dp))
        Text("Enter your PIN", color = W.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { v ->
                val d = v.filter { it.isDigit() }.take(6)
                pin = d
                if (d.length == 6 && !state.authBusy) onUnlock(d)
            },
            singleLine = true,
            enabled = !state.authBusy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, letterSpacing = 10.sp, fontSize = 24.sp),
            modifier = Modifier.width(220.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                focusedTextColor = W.Text, unfocusedTextColor = W.Text
            )
        )
        Spacer(Modifier.height(18.dp))
        if (state.authBusy) {
            CircularProgressIndicator(color = W.Amber, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Button(
                onClick = { if (pin.isNotEmpty()) onUnlock(pin) },
                enabled = pin.length >= 4,
                colors = ButtonDefaults.buttonColors(containerColor = W.Amber, disabledContainerColor = W.SurfaceHi)
            ) { Text("Unlock") }
        }
        state.authError?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = W.Danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ConvList(
    state: ConvState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDeleteConv: (String) -> Unit,
    onHide: (String) -> Unit,
    onRequestUnhide: (String) -> Unit,
    onSubmitUnhide: (String) -> Unit,
    onCancelUnhide: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    modifier: Modifier
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<ConvSummary?>(null) }
    var deleteFor by remember { mutableStateOf<ConvSummary?>(null) }
    val searchMode = state.query.isNotBlank()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Conversations", style = MaterialTheme.typography.titleMedium, color = W.Amber,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                if (state.loadingList)
                    CircularProgressIndicator(Modifier.size(18.dp), color = W.Amber, strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = W.Amber)
            }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onNew, colors = ButtonDefaults.buttonColors(containerColor = W.Amber),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color(0xFF1A1204),
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New", color = Color(0xFF1A1204))
            }
        }

        // keyword search box
        OutlinedTextField(
            value = state.query,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Search conversations", color = W.Muted) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = W.Muted) },
            trailingIcon = {
                if (searchMode) {
                    if (state.searching)
                        CircularProgressIndicator(Modifier.size(18.dp), color = W.Amber, strokeWidth = 2.dp)
                    else IconButton(onClick = onClearSearch) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = W.Muted)
                    }
                }
            },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                focusedTextColor = W.Text, unfocusedTextColor = W.Text
            )
        )
        HorizontalDivider(color = W.Line)

        state.error?.let {
            Text(it, color = W.Danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1512)).padding(12.dp))
        }

        if (searchMode) {
            // search results (hidden conversations are excluded by the server)
            if (state.results.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.searching) "Searching..." else "No matches for \"${state.query}\".",
                        color = W.Muted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.results) { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpen(h.id) }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Text(h.title, color = W.Text, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (h.snippet.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(h.snippet, color = W.Muted, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text("${fmtTime(h.updated)}  ·  ${h.count} messages",
                                color = W.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider(color = W.Line)
                    }
                }
            }
        } else if (state.conversations.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.loadingList) "Loading..." else "No conversations yet. Tap New to start one.",
                    color = W.Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp)
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
                            Text(c.title, color = if (c.hidden) W.Muted else W.Text,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text("${fmtTime(c.updated)}  ·  ${c.count} messages",
                                color = W.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        // Eye = visible (tap to hide). Eye-slash = hidden (tap to reveal with PIN).
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
                                if (!c.hidden) {
                                    DropdownMenuItem(text = { Text("Rename", color = W.Text) },
                                        onClick = { renameFor = c; menuFor = null })
                                }
                                DropdownMenuItem(text = { Text("Delete", color = W.Danger) },
                                    onClick = { deleteFor = c; menuFor = null })
                            }
                        }
                    }
                    HorizontalDivider(color = W.Line)
                }
            }
        }

        // The quiet audit line.
        state.loginInfo?.let { info ->
            val failed = if (info.failedCount > 0)
                "  ·  ${info.failedCount} failed (last ${fmtTime(info.failedAttempts.lastOrNull())})" else ""
            Text(
                "last login ${fmtTime(info.lastLogin)}$failed",
                color = if (info.failedCount > 0) W.Danger else W.Muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
    }

    renameFor?.let { c -> RenameDialog(c.title, { t -> onRename(c.id, t); renameFor = null }, { renameFor = null }) }
    deleteFor?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            confirmButton = { TextButton(onClick = { onDeleteConv(c.id); deleteFor = null }) { Text("Delete", color = W.Danger) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete conversation?", color = W.Text) },
            text = { Text("This permanently removes \"${c.title}\" and everything in it. It cannot be undone, and nothing remembers it afterward.", color = W.Muted) },
            containerColor = W.Surface
        )
    }
    state.unhideTarget?.let {
        UnhidePinDialog(state.unhideError, onSubmitUnhide, onCancelUnhide)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnhidePinDialog(error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = { if (pin.length >= 4) onSubmit(pin) }, enabled = pin.isNotEmpty()) {
                Text("Reveal", color = W.Amber)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = W.Muted) } },
        title = { Text("Enter PIN to reveal", color = W.Text) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = LocalTextStyle.current.copy(letterSpacing = 6.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg,
                        focusedTextColor = W.Text, unfocusedTextColor = W.Text
                    )
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = W.Danger, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        containerColor = W.Surface
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ConvChat(
    state: ConvState,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onDeleteMsg: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDeleteConv: (String) -> Unit,
    onImagePicked: (Uri) -> Unit,
    onClearImage: () -> Unit,
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
    val listState = rememberLazyListState()
    val streamingText = state.streaming

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onImagePicked(uri) }

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
            Text(state.openTitle.ifBlank { "Conversation" }, color = W.Text,
                style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenFiles) {
                Icon(Icons.Filled.Folder, contentDescription = "Conversation files", tint = W.Amber)
            }
            Box {
                IconButton(onClick = { topMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = W.Muted) }
                DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                    DropdownMenuItem(text = { Text("Rename", color = W.Text) }, onClick = { renameOpen = true; topMenu = false })
                    DropdownMenuItem(text = { Text("Delete conversation", color = W.Danger) }, onClick = { deleteOpen = true; topMenu = false })
                }
            }
        }
        HorizontalDivider(color = W.Line)

        if (state.compactingNotice) {
            LaunchedEffect(state.compactedAt) { kotlinx.coroutines.delay(4000); onDismissCompacting() }
            Text("Compacting this conversation so we can keep chatting...",
                color = Color(0xFF1A1204), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().background(W.Amber).padding(horizontal = 14.dp, vertical = 8.dp))
        } else if (state.compactedCount > 0) {
            Text("Earlier turns are summarized to keep this chat fast. Your full history is still shown below, and uploaded images are in the folder.",
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

        if (state.messages.isEmpty() && streamingText == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.busy) "Loading..." else "Say something to begin. This conversation is saved.",
                    color = W.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(40.dp))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(state.messages) { m -> ConvBubble(m, onImageTap = { zoomB64 = it }) { deleteMsgId = m.id } }
                if (streamingText != null) item { ConvBubble(ConvMsg("streaming", "assistant", streamingText.ifBlank { "..." })) {} }
            }
        }

        state.error?.let {
            Text(it, color = W.Danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A1512)).padding(12.dp))
        }

        HorizontalDivider(color = W.Line)
        Column(Modifier.background(W.Surface).imePadding()) {
            // Model is read-only here; it's chosen in Settings > Server models.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Model", color = W.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(10.dp))
                Text(state.selectedModel.ifBlank { "No model (set one in Settings)" }, color = W.Text,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }

            // pending image preview (a photo chosen but not yet sent)
            state.pendingImageB64?.let { b64 ->
                val bmp = remember(b64) { decodeB64(b64) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp, contentDescription = "Attached image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp)
                                .clickable { zoomB64 = b64 }
                                .border(1.dp, W.Line, RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text("Image attached", color = W.Muted, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearImage) { Text("Remove", color = W.Danger) }
                }
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                FilledIconButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = W.SurfaceHi, contentColor = W.Amber
                    ),
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach image")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message", color = W.Muted) },
                    maxLines = 6,
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = W.Amber, unfocusedBorderColor = W.Line,
                        focusedContainerColor = W.Bg, unfocusedContainerColor = W.Bg
                    )
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (state.busy) onStop() else { val t = draft; draft = ""; onSend(t) } },
                    enabled = state.busy || draft.isNotBlank() || state.pendingImageB64 != null,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = W.Amber, contentColor = Color(0xFF1A1204),
                        disabledContainerColor = W.SurfaceHi
                    ),
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(if (state.busy) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                        contentDescription = if (state.busy) "Stop" else "Send")
                }
            }
        }
    }

    if (renameOpen) {
        state.openId?.let { id ->
            RenameDialog(state.openTitle, { t -> onRename(id, t); renameOpen = false }, { renameOpen = false })
        }
    }
    if (deleteOpen) {
        state.openId?.let { id ->
            AlertDialog(
                onDismissRequest = { deleteOpen = false },
                confirmButton = { TextButton(onClick = { onDeleteConv(id); deleteOpen = false; onClose() }) { Text("Delete", color = W.Danger) } },
                dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel", color = W.Muted) } },
                title = { Text("Delete conversation?", color = W.Text) },
                text = { Text("This permanently removes everything in it. Nothing remembers it afterward.", color = W.Muted) },
                containerColor = W.Surface
            )
        }
    }
    deleteMsgId?.let { mid ->
        AlertDialog(
            onDismissRequest = { deleteMsgId = null },
            confirmButton = { TextButton(onClick = { onDeleteMsg(mid); deleteMsgId = null }) { Text("Delete", color = W.Danger) } },
            dismissButton = { TextButton(onClick = { deleteMsgId = null }) { Text("Cancel", color = W.Muted) } },
            title = { Text("Delete this message?", color = W.Text) },
            text = { Text("Removes just this message, permanently.", color = W.Muted) },
            containerColor = W.Surface
        )
    }
    zoomB64?.let { b64 -> ImageZoomDialog(b64) { zoomB64 = null } }
    if (state.filesOpen) {
        ConvFilesDialog(
            files = state.files, loading = state.loadingFiles, thumbs = state.fileThumbs,
            onReattach = onReattachFile, onDownload = onDownloadFile,
            onRefresh = onRefreshFiles, onClose = onCloseFiles
        )
    }
}

/** Full-screen pinch/drag zoom for an attached image. Tap outside or back to close. */
@Composable
private fun ImageZoomDialog(b64: String, onDismiss: () -> Unit) {
    val bmp = remember(b64) { decodeB64(b64) } ?: return
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset += panChange
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bmp, contentDescription = "Full image", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp)
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
                    .transformable(transform)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConvBubble(m: ConvMsg, onImageTap: (String) -> Unit = {}, onLongPress: () -> Unit) {
    val isUser = m.role == "user"
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    // Long-press a message for Copy / Delete.
    Box {
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (m.text.isNotBlank()) DropdownMenuItem(text = { Text("Copy", color = W.Text) },
            onClick = { clipboard.setText(AnnotatedString(m.text)); menuOpen = false })
        DropdownMenuItem(text = { Text("Delete", color = W.Danger) },
            onClick = { menuOpen = false; onLongPress() })
    }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menuOpen = true })) {
        Text(if (isUser) "YOU" else "WAIDE", style = MaterialTheme.typography.labelSmall,
            color = if (isUser) W.Muted else W.Amber)
        Spacer(Modifier.height(5.dp))
        m.imageB64?.let { b64 ->
            val bmp = remember(b64) { decodeB64(b64) }
            if (bmp != null) {
                Image(
                    bitmap = bmp, contentDescription = "Attached image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.sizeIn(maxHeight = 220.dp).fillMaxWidth(0.7f)
                        .clickable { onImageTap(b64) }
                        .border(1.dp, W.Line, RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        if (m.hasVision) {
            Text("image read", color = W.Muted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
        }
        if (m.text.isNotBlank()) {
            Text(
                text = m.text, color = W.Text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().then(
                    if (isUser) Modifier.border(1.dp, W.Line, RoundedCornerShape(4.dp))
                        .background(W.Surface, RoundedCornerShape(4.dp)).padding(12.dp)
                    else Modifier.padding(start = 2.dp)
                )
            )
        }
    }
    }
}

/** Decode a base64 JPEG/PNG into an ImageBitmap for Compose. Returns null on failure. */
private fun decodeB64(b64: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Save", color = W.Amber) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Muted) } },
        title = { Text("Rename conversation", color = W.Text) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
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

private fun fmtTime(epochSec: Double?): String {
    if (epochSec == null || epochSec <= 0.0) return "never"
    val d = java.util.Date((epochSec * 1000).toLong())
    return java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(d)
}
