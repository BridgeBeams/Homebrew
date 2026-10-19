package com.waide.mobile

/**
 * Thin Kotlin wrapper over the native engine. Every call here touches the model,
 * so nothing on this object should be called from the main thread.
 */
object LlamaBridge {

    init {
        System.loadLibrary("waide")
    }

    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Boolean
    external fun nativeUnload()
    external fun nativeModelDescription(): String
    external fun nativeLastError(): String
    external fun nativeSetSampling(temp: Float, topP: Float, minP: Float)
    external fun nativeResetChat(): Boolean
    external fun nativeSetSystemPrompt(sys: String)
    external fun nativePrimeMessage(role: String, content: String)
    external fun nativeBeginTurn(userMsg: String): Int
    external fun nativeNextToken(): String?
    external fun nativeStop()
    external fun nativeContextUsed(): Int
}
