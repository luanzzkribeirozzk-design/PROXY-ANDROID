package com.lnproxy.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

class PairingReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SET_CODE = "com.lnproxy.app.ACTION_SET_CODE"
        const val ACTION_SET_PORT = "com.lnproxy.app.ACTION_SET_PORT"
        const val ACTION_CONNECT  = "com.lnproxy.app.ACTION_CONNECT"
        const val KEY_CODE = "key_code"
        const val KEY_PORT = "key_port"

        var pendingCode: String? = null
        var pendingPort: Int?   = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString()?.trim()
                if (!code.isNullOrEmpty()) {
                    pendingCode = code
                    PairingNotificationManager.update(context)
                }
            }
            ACTION_SET_PORT -> {
                val port = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_PORT)?.toString()?.trim()?.toIntOrNull()
                if (port != null) {
                    pendingPort = port
                    PairingNotificationManager.update(context)
                    tryConnect(context)
                }
            }
            ACTION_CONNECT -> tryConnect(context)
        }
    }

    private fun tryConnect(context: Context) {
        val port = pendingPort ?: return
        context.startService(
            Intent(context, PairingService::class.java)
                .putExtra("port", port)
        )
    }
}
