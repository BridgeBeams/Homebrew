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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.waide.mobile.smartTextFile
import com.waide.mobile.suggestFileName
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.waide.mobile.Message
import com.waide.mobile.ServerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    state: ServerState,
    hasServerUrl: Boolean,
    onRefreshModels: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onSelectServer: (String) -> Unit = {},
    onConfirmBump: () -> Unit = {},
    onDeclineBump: () -> Unit = {},
    onImagePicked: (Uri) -> Unit = {},
    onClearImage: () -> Unit = {},
    onSaveMessage: (String, String) -> Unit = { _, _ -> },
    onSaveConversation: (String) -> Unit = {},
    onDismissFilesMsg: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    var machineMenu by remember { mutableStateOf(false) }
    var zoomB64 by remember { mutableStateOf<String?>(null) }   // image tapped for full screen
    var downloadFor by remember { mutableStateOf<String?>(null) }        // message text to export
    var saveCustomFor by remember { mutableStateOf<String?>(null) }      // message text, custom name
    var convDownload by remember { mutableStateOf(false) }               // whole-chat format picker
    var convCustom by remember { mutableStateOf(false) }                 // whole-chat custom name
    val listState = rememberLazyListState()
    val streamingText = state.streaming
    val activeServer = state.servers.firstOrNull { it.id == state.activeServerId }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onImagePicked(uri) }

    val itemCount = state.messages.size + if (streamingText != null) 1 else 0
    LaunchedEffect(itemCount, streamingText?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier.fillMaxSize()) {

        // Compact header: machine + model pickers and status, kept tight to leave
        // the most room for the chat itself.
        Column(Modifier.fillMaxWidth().background(W.Surface).padding(horizontal = 12.dp, vertical = 2.dp)) {
            if (state.servers.isEmpty()) {
                Text("No machine set. Add one in Settings.", color = W.Muted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 6.dp))
            } else {
                Box {
                    Row(
                        Modifier.fillMaxWidth().clickable { machineMenu = true }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Machine", color = W.Muted, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(10.dp))
                        Text(activeServer?.name ?: "Pick a machine", color = W.Text,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick machine", tint = W.Muted)
                    }
                    DropdownMenu(expanded = machineMenu, onDismissRequest = { machineMenu = false }) {
                        state.servers.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name, color = if (s.id == state.activeServerId) W.Amber else W.Text) },
                                onClick = { onSelectServer(s.id); machineMenu = false }
                            )
                        }
                    }
                }
            }

            // Model is read-only here; it's chosen in Settings > Server models.
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Model", color = W.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(10.dp))
                Text(state.selectedModel.ifBlank { "No model (set one in Settings)" }, color = W.Text,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefreshModels, modifier = Modifier.size(34.dp)) {
                    if (state.loadingModels)
                        CircularProgressIndicator(Modifier.size(18.dp), color = W.Amber, strokeWidth = 2.dp)
                    else
                        Icon(Icons.Filled.Refresh, contentDescription = "Reconnect", tint = W.Amber)
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        !hasServerUrl -> "Set your server address in Settings"
                        state.connected -> "Connected · private · nothing saved"
                        else -> "Not connected · tap refresh"
                    },
                    color = if (state.connected) W.Muted else W.Danger,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                if (state.messages.isNotEmpty()) {
                    TextButton(onClick = { convDownload = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Save", color = W.Amber)
                    }
                    TextButton(onClick = onClear, enabled = !state.busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Clear", color = W.Muted)
                    }
                }
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
                items(state.messages) { m -> SrvBubble(m, onImageTap = { zoomB64 = it }, onDownload = { downloadFor = m.text }) }
                if (streamingText != null) item {
                    val t = if (state.visionBusy) "reading image..." else streamingText.ifBlank { "..." }
                    SrvBubble(Message("assistant", t))
                }
            }
        }

        state.error?.let { ConnErrorBar(it) }
        state.filesMsg?.let {
            Text(it, color = W.Amber, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().background(W.SurfaceHi)
                    .clickable { onDismissFilesMsg() }.padding(12.dp))
        }

        HorizontalDivider(color = W.Line)
        Column(Modifier.background(W.Surface).imePadding()) {

            // Preview of the attached photo, with a way to drop it.
            state.pendingImageB64?.let { b64 ->
                val bmp = remember(b64) { decodeB64(b64) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp, contentDescription = "attached image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).border(1.dp, W.Line, RoundedCornerShape(6.dp))
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Image attached", color = W.Muted,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearImage) { Text("Remove", color = W.Danger) }
                }
            }

            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Attach a photo. The image reader is set in Settings; if none is set,
                // sending shows a short note pointing there.
                FilledIconButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = state.connected && !state.busy,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = W.SurfaceHi, contentColor = W.Amber,
                        disabledContainerColor = W.SurfaceHi
                    ),
                    modifier = Modifier.size(52.dp)
                ) { Icon(Icons.Filled.Add, contentDescription = "Attach image") }
                Spacer(Modifier.width(8.dp))
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
                    enabled = state.connected &&
                        (state.busy || draft.isNotBlank() || state.pendingImageB64 != null),
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

    // Asked when a prompt would overflow the current window: raise it or keep it light.
    state.bumpTo?.let { target ->
        AlertDialog(
            onDismissRequest = onDeclineBump,
            confirmButton = {
                TextButton(onClick = onConfirmBump) { Text("Yes, bump it", color = W.Amber) }
            },
            dismissButton = {
                TextButton(onClick = onDeclineBump) { Text("No, keep it light", color = W.Muted) }
            },
            title = { Text("Lean harder on your GPU?", color = W.Text) },
            text = {
                Text(
                    "This one's big. To answer it without dropping earlier parts of the " +
                        "conversation, I need to raise this chat's window to $target tokens, " +
                        "which uses more of the PC's memory. Bump it up just for this conversation?",
                    color = W.Muted
                )
            },
            containerColor = W.Surface
        )
    }

    // Tap an attached image to inspect it full screen (pinch to zoom, drag to pan).
    zoomB64?.let { b64 ->
        val bmp = remember(b64) { decodeB64(b64) }
        Dialog(
            onDismissRequest = { zoomB64 = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val tstate = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                offset += pan
            }
            Box(Modifier.fillMaxSize().background(Color(0xE6000000))) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp, contentDescription = "full image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                            .graphicsLayer(
                                scaleX = scale, scaleY = scale,
                                translationX = offset.x, translationY = offset.y
                            )
                            .transformable(tstate)
                    )
                }
                TextButton(
                    onClick = { zoomB64 = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) { Text("Close", color = W.Amber) }
            }
        }
    }

    // ---- download dialogs ----
    downloadFor?.let { text ->
        FormatChooserDialog(
            onPdf = { onSaveMessage(text, suggestFileName(text) + ".pdf"); downloadFor = null },
            onDocx = { onSaveMessage(text, suggestFileName(text) + ".docx"); downloadFor = null },
            onXlsx = { onSaveMessage(text, suggestFileName(text) + ".xlsx"); downloadFor = null },
            onPptx = { onSaveMessage(text, suggestFileName(text) + ".pptx"); downloadFor = null },
            onHtml = { onSaveMessage(text, suggestFileName(text) + ".html"); downloadFor = null },
            onEpub = { onSaveMessage(text, suggestFileName(text) + ".epub"); downloadFor = null },
            onSmart = { val (ext, content) = smartTextFile(text); onSaveMessage(content, suggestFileName(text) + "." + ext); downloadFor = null },
            onCustom = { saveCustomFor = text; downloadFor = null },
            onDismiss = { downloadFor = null }
        )
    }
    saveCustomFor?.let { text ->
        FileNameDialog(initial = suggestFileName(text) + ".pdf",
            onSave = { name -> onSaveMessage(text, name); saveCustomFor = null },
            onDismiss = { saveCustomFor = null })
    }
    if (convDownload) {
        FormatChooserDialog(
            onPdf = { onSaveConversation("waide-conversation.pdf"); convDownload = false },
            onDocx = { onSaveConversation("waide-conversation.docx"); convDownload = false },
            onXlsx = { onSaveConversation("waide-conversation.xlsx"); convDownload = false },
            onPptx = { onSaveConversation("waide-conversation.pptx"); convDownload = false },
            onHtml = { onSaveConversation("waide-conversation.html"); convDownload = false },
            onEpub = { onSaveConversation("waide-conversation.epub"); convDownload = false },
            onSmart = { onSaveConversation("waide-conversation.md"); convDownload = false },
            onCustom = { convCustom = true; convDownload = false },
            onDismiss = { convDownload = false }
        )
    }
    if (convCustom) {
        FileNameDialog(initial = "waide-conversation.pdf",
            onSave = { name -> onSaveConversation(name); convCustom = false },
            onDismiss = { convCustom = false })
    }
}

private fun decodeB64(b64: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(b64, Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
}.getOrNull()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SrvBubble(m: Message, onImageTap: (String) -> Unit = {}, onDownload: () -> Unit = {}) {
    // A hidden image-read note: show only a tiny marker, never the readout text.
    if (m.role == "system") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = W.Muted,
                modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text("image read", color = W.Muted, style = MaterialTheme.typography.labelSmall)
        }
        return
    }

    val isUser = m.role == "user"
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (m.text.isNotBlank()) DropdownMenuItem(text = { Text("Copy", color = W.Text) },
            onClick = { clipboard.setText(AnnotatedString(m.text)); menuOpen = false })
        if (m.text.isNotBlank()) DropdownMenuItem(text = { Text("Download...", color = W.Text) },
            onClick = { menuOpen = false; onDownload() })
    }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menuOpen = true })) {
        Text(
            if (isUser) "YOU" else "SERVER",
            style = MaterialTheme.typography.labelSmall,
            color = if (isUser) W.Muted else W.Amber
        )
        Spacer(Modifier.height(5.dp))
        m.imageB64?.let { b64 ->
            val bmp = remember(b64) { decodeB64(b64) }
            if (bmp != null) {
                Image(
                    bitmap = bmp, contentDescription = "attached image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 160.dp)
                        .border(1.dp, W.Line, RoundedCornerShape(4.dp))
                        .clickable { onImageTap(b64) }
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        if (m.visionNote != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = W.Muted,
                    modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
                Text("image read", color = W.Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        SelectionContainer {
            Text(
                text = m.text, color = W.Text, style = MaterialTheme.typography.bodyLarge,
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
}
