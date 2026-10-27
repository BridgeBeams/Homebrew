package com.waide.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.waide.mobile.ui.ChatScreen
import com.waide.mobile.ui.ConversationsScreen
import com.waide.mobile.ui.PremiumScreen
import com.waide.mobile.ui.ServerScreen
import com.waide.mobile.ui.SettingsScreen
import com.waide.mobile.ui.W
import com.waide.mobile.ui.WaideTheme

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()
    private val svm: ServerViewModel by viewModels()
    private val cvm: ConversationsViewModel by viewModels()
    private val pvm: PremiumViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeAskNotifications()
        setContent { WaideTheme { Shell(vm, svm, cvm, pvm, ::hasAllFiles, ::requestAllFiles) } }
    }

    // The "working" notification needs this on Android 13+. If denied, generation
    // still runs in the background; only the status notification is hidden.
    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            i.data = Uri.parse("package:$packageName")
            runCatching { startActivity(i) }.onFailure {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Premium("Premium AI", Icons.Filled.AutoAwesome),
    Chat("Local Chat", Icons.Filled.Chat),
    Server("Server Chat", Icons.Filled.Cloud),
    Convos("Saved Chats", Icons.Filled.Lock),
    Settings("Settings", Icons.Filled.Tune)
}

@Composable
private fun Shell(
    vm: ChatViewModel,
    svm: ServerViewModel,
    cvm: ConversationsViewModel,
    pvm: PremiumViewModel,
    hasAllFiles: () -> Boolean,
    requestAllFiles: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Chat) }
    var storageOk by remember { mutableStateOf(hasAllFiles()) }

    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val models by vm.models.collectAsState()
    val browse by vm.browse.collectAsState()
    val serverState by svm.state.collectAsState()
    val convState by cvm.state.collectAsState()
    val agentState by cvm.agentState.collectAsState()
    val premiumState by pvm.state.collectAsState()

    // Re-check permission and rescan whenever the app comes back to the front,
    // which is exactly when a download has just finished or access was granted.
    // Also re-lock the Conversations side whenever the app goes to the background.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageOk = hasAllFiles()
                vm.refreshModels()
            }
            if (event == Lifecycle.Event.ON_STOP) {
                cvm.lock()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Leaving the Conversations tab locks it again (nothing stays unlocked in the background).
    // Opening Local Chat loads the last-used model (once per run), so startup stays instant
    // when you only want the Server or Saved tabs.
    LaunchedEffect(tab) {
        if (tab != Tab.Convos) cvm.lock()
        if (tab == Tab.Chat) vm.ensureLocalModelLoaded()
        if (tab == Tab.Premium) pvm.refreshStatus()
    }

    Scaffold(
        containerColor = W.Bg,
        topBar = {
            Surface(color = W.Bg) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        "w-Ai-de",
                        style = MaterialTheme.typography.titleMedium,
                        color = W.Amber,
                        modifier = Modifier.weight(1f)
                    )
                    if (tab == Tab.Chat && state.messages.isNotEmpty()) {
                        TextButton(onClick = { vm.newChat() }, enabled = !state.busy) {
                            Text("New chat", color = W.Muted)
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = W.Surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = W.Amber,
                            selectedTextColor = W.Amber,
                            indicatorColor = W.SurfaceHi,
                            unselectedIconColor = W.Muted,
                            unselectedTextColor = W.Muted
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).background(W.Bg)) {
            when (tab) {
                Tab.Premium -> PremiumScreen(
                    state = premiumState,
                    onRefresh = { pvm.refreshStatus() },
                    onNew = { pvm.newConversation() },
                    onOpen = { pvm.openConversation(it) },
                    onClose = { pvm.closeConversation() },
                    onSend = { pvm.send(it) },
                    onStop = { pvm.stopGeneration() },
                    onRename = { id, t -> pvm.renameConversation(id, t) },
                    onDeleteConv = { pvm.deleteConversation(it) },
                    onDeleteMsg = { pvm.deleteMessage(it) },
                    onHide = { pvm.hideConversation(it) },
                    onRequestUnhide = { pvm.requestUnhide(it) },
                    onSubmitUnhide = { pvm.submitUnhide(it) },
                    onCancelUnhide = { pvm.cancelUnhide() },
                    onImagePicked = { pvm.attachImage(it) },
                    onFilePicked = { pvm.attachFile(it) },
                    onClearAttachment = { pvm.clearAttachment(it) },
                    onSetConvModel = { pvm.setOpenModel(it) },
                    onRefreshModels = { pvm.refreshModels() }
                )
                Tab.Chat -> ChatScreen(
                    state = state,
                    onSend = { vm.send(it) },
                    onStop = { vm.stopGeneration() }
                )
                Tab.Server -> ServerScreen(
                    state = serverState,
                    hasServerUrl = serverState.servers.any { it.id == serverState.activeServerId },
                    onRefreshModels = { svm.refreshModels() },
                    onSend = { svm.send(it) },
                    onStop = { svm.stopGeneration() },
                    onClear = { svm.clearChat() },
                    onSelectServer = { svm.selectServer(it) },
                    onConfirmBump = { svm.confirmBump() },
                    onDeclineBump = { svm.declineBump() },
                    onImagePicked = { svm.attachImage(it) },
                    onClearImage = { svm.clearImage() }
                )
                Tab.Convos -> ConversationsScreen(
                    state = convState,
                    onUnlock = { cvm.unlock(it) },
                    onRefreshList = { cvm.refreshList() },
                    onOpen = { cvm.openConversation(it) },
                    onNew = { cvm.newConversation() },
                    onClose = { cvm.closeConversation() },
                    onSend = { cvm.send(it) },
                    onStop = { cvm.stopGeneration() },
                    onRename = { id, t -> cvm.renameConversation(id, t) },
                    onDeleteConv = { cvm.deleteConversation(it) },
                    onDeleteMsg = { cvm.deleteMessage(it) },
                    onHide = { cvm.hideConversation(it) },
                    onRequestUnhide = { cvm.requestUnhide(it) },
                    onSubmitUnhide = { cvm.submitUnhide(it) },
                    onCancelUnhide = { cvm.cancelUnhide() },
                    onImagePicked = { cvm.attachImage(it) },
                    onClearImage = { cvm.clearImage() },
                    onSearch = { cvm.setQuery(it) },
                    onClearSearch = { cvm.clearSearch() },
                    agentState = agentState,
                    onAgentRefresh = { cvm.refreshAgents() },
                    onAgentOpen = { cvm.openAgent(it) },
                    onAgentNew = { n, j -> cvm.createAgent(n, j) },
                    onAgentClose = { cvm.closeAgent() },
                    onAgentSend = { cvm.sendToAgent(it) },
                    onAgentStop = { cvm.stopAgent() },
                    onAgentEdit = { id, n, j -> cvm.editAgent(id, n, j) },
                    onAgentDelete = { cvm.deleteAgent(it) },
                    onAgentDeleteMsg = { cvm.deleteAgentMsg(it) },
                    onAgentHide = { cvm.hideAgent(it) },
                    onAgentRequestUnhide = { cvm.requestUnhideAgent(it) },
                    onAgentSubmitUnhide = { cvm.submitUnhideAgent(it) },
                    onAgentCancelUnhide = { cvm.cancelUnhideAgent() }
                )
                Tab.Settings -> SettingsScreen(
                    settings = settings,
                    onChange = { vm.updateSettings(it) },
                    servers = serverState.servers,
                    onAddServer = { n, u -> svm.addServer(n, u) },
                    onRemoveServer = { svm.removeServer(it) },
                    serverCtx = serverState.ctx,
                    onServerCtxChange = { svm.setCtx(it) },
                    onUnhideAll = { pin, cb -> cvm.unhideAll(pin, cb) },
                    onUnhideAllAgents = { pin, cb -> cvm.unhideAllAgents(pin, cb) },
                    serverState = serverState,
                    onRefreshModels = { svm.refreshModels() },
                    onSelectModel = { svm.selectModel(it) },
                    onSetVisionModel = { svm.setVisionModel(it) },
                    onDeleteServerModel = { svm.deleteServerModel(it) },
                    onPullServerModel = { svm.pullServerModel(it) },
                    onSearchHf = { svm.searchHf(it) },
                    onOpenQuants = { svm.openQuants(it) },
                    onCloseQuants = { svm.closeQuants() },
                    onPullHf = { repo, tag -> svm.pullHf(repo, tag) },
                    premiumState = premiumState,
                    onSetOpenRouterKey = { pin, key, cb -> pvm.setKey(pin, key, cb) },
                    onRefreshPremiumModels = { pvm.refreshModels() },
                    onSetDefaultPremiumModel = { pvm.setDefaultModel(it) },
                    onUnhideAllPremium = { pin, cb -> pvm.unhideAll(pin, cb) },
                    localModels = models,
                    loadedModelName = state.modelName,
                    modelLoading = state.modelLoading,
                    hasStorageAccess = storageOk,
                    onGrantStorage = requestAllFiles,
                    onRefreshLocal = { vm.refreshModels() },
                    onLoadLocal = { vm.loadModel(it); tab = Tab.Chat },
                    onUnloadLocal = { vm.unloadModel() },
                    onDeleteLocal = { vm.deleteLocalModel(it) },
                    localBrowse = browse,
                    onLocalSearchHf = { vm.searchLocalHf(it) },
                    onLocalOpenQuants = { vm.openLocalQuants(it) },
                    onLocalCloseQuants = { vm.closeLocalQuants() },
                    onLocalCloseSearch = { vm.closeLocalSearch() },
                    onLocalDownload = { repo, f -> vm.downloadLocalModel(repo, f) },
                    onLocalCancelDownload = { vm.cancelLocalDownload() }
                )
            }
        }
    }
}
