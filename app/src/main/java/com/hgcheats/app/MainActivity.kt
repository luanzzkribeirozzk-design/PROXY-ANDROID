package com.hgcheats.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.*
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private val PROXY_ADDRESS = "2.25.201.158:6000"

    // Gerada uma vez e reutilizada para que o par de chaves seja sempre o mesmo
    private val adbKeyPair: AdbKeyPair by lazy { AdbKeyPair.generate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        val btnMenu = findViewById<ImageButton>(R.id.btn_menu)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val btnInject = findViewById<Button>(R.id.btn_inject)
        val btnRemove = findViewById<Button>(R.id.btn_remove)

        btnMenu.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        navView.findViewById<Button>(R.id.btn_nav_connect)?.setOnClickListener {
            showPairingDialog()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        updateStatusUI()

        btnInject.setOnClickListener { applyProxy(PROXY_ADDRESS) }
        btnRemove.setOnClickListener { applyProxy(":0") }
    }

    // Tenta injetar/remover proxy por todas as vias disponíveis
    private fun applyProxy(address: String) {
        val removing = address == ":0"
        val actionLabel = if (removing) "Removido" else "Injetado"

        // Via WRITE_SECURE_SETTINGS (concedida via adb pm grant)
        if (hasWriteSecureSettings()) {
            runCatching {
                Settings.Global.putString(contentResolver, Settings.Global.HTTP_PROXY, address)
            }.onSuccess {
                updateStatusUI()
                toast("Proxy $actionLabel!")
                return
            }
        }

        // Via root
        if (tryRootCommand("settings put global http_proxy $address")) {
            updateStatusUI()
            toast("Proxy $actionLabel via Root!")
            return
        }

        // Precisa parear via Depuração Wi-Fi
        toast("Conecte via Depuração Wi-Fi para injetar o proxy.")
        showPairingDialog()
    }

    private fun showPairingDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_adb_pairing, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val etCode = dialogView.findViewById<EditText>(R.id.et_pairing_code)
        val etPort = dialogView.findViewById<EditText>(R.id.et_port)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_adb_status)
        val btnPair = dialogView.findViewById<Button>(R.id.btn_pair_submit)
        val btnClose = dialogView.findViewById<Button>(R.id.btn_pair_notification)

        tvStatus.text = "Aguardando..."
        btnClose.setOnClickListener { dialog.dismiss() }

        btnPair.setOnClickListener {
            val code = etCode.text.toString().trim()
            val pairingPort = etPort.text.toString().trim().toIntOrNull()

            if (code.length != 6 || pairingPort == null) {
                toast("Código deve ter 6 dígitos e porta válida")
                return@setOnClickListener
            }

            tvStatus.text = "Pareando..."
            btnPair.isEnabled = false
            btnClose.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 1. Parear via protocolo ADB wireless (SPAKE2+)
                    Dadb.pair("127.0.0.1", pairingPort, code)

                    // 2. Descobrir automaticamente a porta ADB via mDNS
                    val dadb = withTimeoutOrNull(12_000L) {
                        var connection: Dadb? = null
                        while (connection == null) {
                            connection = runCatching {
                                Dadb.discover("127.0.0.1", adbKeyPair)
                            }.getOrNull()
                            if (connection == null) delay(1000)
                        }
                        connection
                    } ?: throw Exception("Servidor ADB não encontrado.\nVerifique se a Depuração Wi-Fi está ativa e tente novamente.")

                    // 3. Injetar proxy via shell ADB (sem permissão extra necessária)
                    dadb.shell("settings put global http_proxy $PROXY_ADDRESS")
                    dadb.close()

                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Conectado — proxy injetado ✓"
                        updateStatusUI()
                        toast("Proxy Injetado via Depuração Wi-Fi!")
                        dialog.dismiss()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Erro: ${e.message}"
                        btnPair.isEnabled = true
                        btnClose.isEnabled = true
                    }
                }
            }
        }

        dialog.show()
    }

    private fun hasWriteSecureSettings(): Boolean =
        checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun tryRootCommand(command: String): Boolean = runCatching {
        val process = Runtime.getRuntime().exec("su")
        val os = DataOutputStream(process.outputStream)
        os.writeBytes("$command\n")
        os.writeBytes("exit\n")
        os.flush()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun isProxyActive(): Boolean {
        val proxy = Settings.Global.getString(contentResolver, Settings.Global.HTTP_PROXY)
        return !proxy.isNullOrEmpty() && proxy != ":0"
    }

    private fun updateStatusUI() {
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        tvStatus.text = if (isProxyActive()) "Online" else "Offline"
        tvStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_dot_offline, 0, 0, 0)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
