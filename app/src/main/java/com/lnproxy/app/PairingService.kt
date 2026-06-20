package com.lnproxy.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.*
import java.io.File

class PairingService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val PROXY_ADDRESS = "2.25.201.158:6000"

    private val keyPair: AdbKeyPair by lazy {
        val priv = File(filesDir, "adbkey")
        val pub  = File(filesDir, "adbkey.pub")
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 0)?.takeIf { it != 0 } ?: return START_NOT_STICKY

        scope.launch {
            try {
                PairingNotificationManager.updateStatus(applicationContext, "Conectando...")
                val dadb = Dadb.create("127.0.0.1", port, keyPair)
                dadb.shell("settings put global http_proxy $PROXY_ADDRESS")
                dadb.close()
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
