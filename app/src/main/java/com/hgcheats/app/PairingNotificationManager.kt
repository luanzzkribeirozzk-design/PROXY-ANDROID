package com.hgcheats.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

object PairingNotificationManager {

    private const val CHANNEL_ID = "pairing_channel"
    const val NOTIF_ID = 1001

    fun send(context: Context) {
        createChannel(context)
        notifyManager(context).notify(NOTIF_ID, build(context))
    }

    fun update(context: Context) = send(context)

    fun updateStatus(context: Context, status: String) {
        createChannel(context)
        notifyManager(context).notify(NOTIF_ID, build(context, status))
    }

    fun cancel(context: Context) = notifyManager(context).cancel(NOTIF_ID)

    private fun build(context: Context, statusOverride: String? = null): android.app.Notification {
        val code = PairingReceiver.pendingCode
        val port = PairingReceiver.pendingPort

        val contentText = statusOverride
            ?: "${if (code != null) "Código ✓" else "Código: —"}  |  ${if (port != null) "Porta ✓" else "Porta: —"}"

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        val immutableFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        // Action: Inserir Código
        val codeInput = RemoteInput.Builder(PairingReceiver.KEY_CODE)
            .setLabel("Código de 6 dígitos")
            .build()
        val codePi = PendingIntent.getBroadcast(
            context, 10,
            Intent(PairingReceiver.ACTION_SET_CODE, null, context, PairingReceiver::class.java),
            flags
        )
        val codeAction = NotificationCompat.Action.Builder(
            0, if (code != null) "Código ✓" else "📝 Inserir Código", codePi
        ).addRemoteInput(codeInput).build()

        // Action: Inserir Porta
        val portInput = RemoteInput.Builder(PairingReceiver.KEY_PORT)
            .setLabel("Porta (ex: 37681)")
            .build()
        val portPi = PendingIntent.getBroadcast(
            context, 11,
            Intent(PairingReceiver.ACTION_SET_PORT, null, context, PairingReceiver::class.java),
            flags
        )
        val portAction = NotificationCompat.Action.Builder(
            0, if (port != null) "Porta ✓" else "📝 Inserir Porta", portPi
        ).addRemoteInput(portInput).build()

        // Action: Conectar
        val connectPi = PendingIntent.getBroadcast(
            context, 12,
            Intent(PairingReceiver.ACTION_CONNECT, null, context, PairingReceiver::class.java),
            immutableFlags
        )
        val connectAction = NotificationCompat.Action(0, "⚡ CONECTAR", connectPi)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Depuração Wi-Fi — Aguardando dados")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(codeAction)
            .addAction(portAction)
            .addAction(connectAction)
            .build()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Pareamento ADB", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notificação para parear via Depuração Wi-Fi" }
            notifyManager(context).createNotificationChannel(channel)
        }
    }

    private fun notifyManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
