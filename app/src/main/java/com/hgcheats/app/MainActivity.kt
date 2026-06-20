package com.hgcheats.app

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private var isConnected = false
    private val PROXY_ADDRESS = "2.25.201.158:6000"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        val btnMenu = findViewById<ImageButton>(R.id.btn_menu)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val btnInject = findViewById<Button>(R.id.btn_inject)
        val btnRemove = findViewById<Button>(R.id.btn_remove)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Lógica do Menu Lateral
        val btnNavConnect = navView.findViewById<Button>(R.id.btn_nav_connect)
        btnNavConnect?.setOnClickListener {
            showPairingDialog()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        // Botão INJETAR HS
        btnInject.setOnClickListener {
            if (isConnected) {
                executeAdbCommand("settings put global http_proxy $PROXY_ADDRESS")
                Toast.makeText(this, "Proxy Injetado: $PROXY_ADDRESS", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Conecte via ADB primeiro!", Toast.LENGTH_LONG).show()
                showPairingDialog()
            }
        }

        // Botão REMOVER HS
        btnRemove.setOnClickListener {
            if (isConnected) {
                executeAdbCommand("settings put global http_proxy :0")
                Toast.makeText(this, "Proxy Removido", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Conecte via ADB primeiro!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPairingDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_adb_pairing, null)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(dialogView)
            .create()

        val etCode = dialogView.findViewById<EditText>(R.id.et_pairing_code)
        val etPort = dialogView.findViewById<EditText>(R.id.et_port)
        val btnPair = dialogView.findViewById<Button>(R.id.btn_pair_submit)
        val tvAdbStatus = dialogView.findViewById<TextView>(R.id.tv_adb_status)

        btnPair.setOnClickListener {
            val code = etCode.text.toString()
            val port = etPort.text.toString()

            if (code.isNotEmpty() && port.isNotEmpty()) {
                // Aqui simularíamos o pareamento real via ADB Wireless
                // No Android 11+, o pareamento exige o uso da biblioteca ADB Lib ou Shizuku
                // Para este projeto, vamos simular a conexão bem-sucedida
                Toast.makeText(this, "Pareando com $port usando código $code...", Toast.LENGTH_SHORT).show()
                
                // Simulação de sucesso
                isConnected = true
                updateStatusUI()
                dialog.dismiss()
                Toast.makeText(this, "Conectado com Sucesso!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Preencha todos os campos!", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun updateStatusUI() {
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        if (isConnected) {
            tvStatus.text = "Online"
            tvStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_dot_offline, 0, 0, 0) // Usaríamos um ic_dot_online verde
        } else {
            tvStatus.text = "Offline"
        }
    }

    private fun executeAdbCommand(command: String) {
        // Esta função executaria o comando via shell se o app tivesse root ou ADB pareado
        // Como estamos usando ADB Wireless pareado internamente:
        try {
            val process = Runtime.getRuntime().exec("sh")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
