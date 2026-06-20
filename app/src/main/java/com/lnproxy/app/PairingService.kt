package com.lnproxy.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.*

class PairingService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val keyPair = AdbKeyPair.generate()
    private val PROXY_ADDRESS = "2.25.201.158:6000"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getStringExtra("code") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 0).takeIf { it != 0 } ?: return START_NOT_STICKY

        scope.launch {
            try {
                PairingNotificationManager.updateStatus(applicationContext, "Pareando...")

                Dadb.pair("127.0.0.1", port, code)

                val dadb = withTimeoutOrNull(12_000L) {
                    var conn: Dadb? = null
                    while (conn == null) {
                        conn = runCatching { Dadb.discover("127.0.0.1", keyPair) }.getOrNull()
                        if (conn == null) delay(1000)
                    }
                    conn
                } ?: throw Exception("Servidor ADB não encontrado")

                dadb.shell("settings put global http_proxy $PROXY_ADDRESS")
                dadb.close()

                PairingReceiver.pendingCode = null
                PairingReceiver.pendingPort = null
                PairingNotificationManager.cancel(applicationContext)
                sendBroadcast(Intent("com.lnproxy.app.PROXY_INJECTED"))
            } catch (e: Exception) {
                PairingNotificationManager.updateStatus(
                    applicationContext, "Erro: ${e.message?.take(60)}"
                )
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
