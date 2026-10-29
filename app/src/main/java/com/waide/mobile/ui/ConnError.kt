package com.waide.mobile.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.waide.mobile.Net

/**
 * Red error banner. When the message looks like the server is unreachable (Tailscale dropped,
 * or another VPN took over), it adds a one-tap "Open Tailscale" action and, if given, "Retry".
 * Otherwise it renders exactly like the plain error text it replaced.
 */
@Composable
fun ConnErrorBar(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val conn = Net.isConnErrorMsg(message)
    Column(modifier.fillMaxWidth().background(Color(0xFF2A1512)).padding(12.dp)) {
        Text(message, color = W.Danger, style = MaterialTheme.typography.bodyMedium)
        if (conn) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Your phone can't reach the server. Tailscale may be off, or another VPN took over.",
                color = W.Muted, style = MaterialTheme.typography.labelSmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { openTailscaleApp(ctx) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) { Text("Open Tailscale", color = W.Amber) }
                if (onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) { Text("Retry", color = W.Amber) }
                }
            }
        }
    }
}

/**
 * Open the Tailscale app so the user can tap Connect. If it isn't installed, open its Play
 * Store listing instead.
 */
fun openTailscaleApp(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        return
    }
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.tailscale.ipn"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "Tailscale app not found", Toast.LENGTH_SHORT).show()
    }
}
