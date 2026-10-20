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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
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
import com.waide.mobile.ui.ModelsScreen
import com.waide.mobile.ui.ServerScreen
import com.waide.mobile.ui.SettingsScreen
import com.waide.mobile.ui.W
import com.waide.mobile.ui.WaideTheme

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()
    private val svm: ServerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeAskNotifications()
        setContent { WaideTheme { Shell(vm, svm, ::hasAllFiles, ::requestAllFiles) } }
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
    Chat("Chat", Icons.Filled.Chat),
    Models("Models", Icons.Filled.Memory),
    Server("Server", Icons.Filled.Cloud),
    Settings("Settings", Icons.Filled.Tune)
}

@Composable
private fun Shell(
    vm: ChatViewModel,
    svm: ServerViewModel,
    hasAllFiles: () -> Boolean,
    requestAllFiles: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Chat) }
    var storageOk by remember { mutableStateOf(hasAllFiles()) }

    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val models by vm.models.collectAsState()
    val serverState by svm.state.collectAsState()

    // Re-check permission and rescan whenever the app comes back to the front,
    // which is exactly when a download has just finished or access was granted.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageOk = hasAllFiles()
                vm.refreshModels()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
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
        Box(Modifier.padding(pad).background(W.Bg)) {
            when (tab) {
                Tab.Chat -> ChatScreen(
                    state = state,
                    onSend = { vm.send(it) },
                    onStop = { vm.stopGeneration() }
                )
                Tab.Models -> ModelsScreen(
                    models = models,
                    loadedName = state.modelName,
                    loading = state.modelLoading,
                    hasStorageAccess = storageOk,
                    onGrantStorage = requestAllFiles,
                    onRefresh = { vm.refreshModels() },
                    onLoad = { vm.loadModel(it); tab = Tab.Chat },
                    onUnload = { vm.unloadModel() }
                )
                Tab.Server -> ServerScreen(
                    state = serverState,
                    hasServerUrl = settings.serverUrl.isNotBlank(),
                    onRefreshModels = { svm.refreshModels() },
                    onSelectModel = { svm.selectModel(it) },
                    onSend = { svm.send(it) },
                    onStop = { svm.stopGeneration() },
                    onClear = { svm.clearChat() }
                )
                Tab.Settings -> SettingsScreen(
                    settings = settings,
                    onChange = { vm.updateSettings(it) }
                )
            }
        }
    }
}
