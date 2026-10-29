package com.waide.mobile

import kotlinx.coroutines.delay
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Helpers for recognizing "can't reach the server" failures (almost always Tailscale being
 * disconnected, or another VPN having taken over) and for riding out brief network blips.
 */
object Net {

    /** True if this throwable (or any cause) looks like the server was unreachable. */
    fun isConnError(t: Throwable?): Boolean {
        var e = t
        while (e != null) {
            if (e is ConnectException || e is SocketTimeoutException || e is UnknownHostException ||
                e is NoRouteToHostException || e is InterruptedIOException) return true
            e = e.cause
        }
        return isConnErrorMsg(t?.message)
    }

    /** True if an error message string looks like a connection/reachability failure. */
    fun isConnErrorMsg(msg: String?): Boolean {
        if (msg == null) return false
        val m = msg.lowercase()
        return "failed to connect" in m || "unable to resolve host" in m ||
            "timed out" in m || "timeout" in m || "unreachable" in m ||
            "connection refused" in m || ("connect to" in m && "after" in m)
    }

    /**
     * Run [block], retrying up to [times] extra attempts if it fails with a connection error,
     * waiting [delayMs] between tries. Non-connection errors are thrown immediately, and the
     * final connection error is rethrown so the UI still surfaces it.
     */
    suspend fun <T> retryOnConn(times: Int = 2, delayMs: Long = 700, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (t: Throwable) {
                if (!isConnError(t) || attempt >= times) throw t
                attempt++
                delay(delayMs)
            }
        }
    }
}
