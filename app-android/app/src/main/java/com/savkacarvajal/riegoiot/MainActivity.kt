package com.savkacarvajal.riegoiot

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.savkacarvajal.riegoiot.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pollingJob: Job? = null
    private var api: RiegoApiClient? = null

    private val prefs by lazy { getSharedPreferences("riego_iot", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cargarPreferencias()

        binding.btnConectar.setOnClickListener { conectar() }
        binding.btnCambiarModo.setOnClickListener { ejecutar { it.cambiarModo() } }
        binding.btnEncender.setOnClickListener { ejecutar { it.encender() } }
        binding.btnApagar.setOnClickListener { ejecutar { it.apagar() } }

        if (binding.inputIp.text.toString().isNotBlank()) conectar()
    }

    private fun cargarPreferencias() {
        binding.inputIp.setText(prefs.getString("ip", ""))
        binding.inputUsuario.setText(prefs.getString("usuario", "admin"))
        binding.inputClave.setText(prefs.getString("clave", ""))
    }

    private fun conectar() {
        val ip = binding.inputIp.text.toString().trim()
        val usuario = binding.inputUsuario.text.toString().trim()
        val clave = binding.inputClave.text.toString()
        if (ip.isEmpty()) {
            mostrarError(getString(R.string.error_conexion))
            return
        }

        prefs.edit()
            .putString("ip", ip)
            .putString("usuario", usuario)
            .putString("clave", clave)
            .apply()

        api = RiegoApiClient(ip, usuario, clave)
        binding.tvError.visibility = View.GONE

        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                actualizarEstado()
                delay(2000)
            }
        }
    }

    private suspend fun actualizarEstado() {
        val cliente = api ?: return
        try {
            val estado = withContext(Dispatchers.IO) { cliente.obtenerEstado() }
            pintarEstado(estado)
        } catch (e: ApiException) {
            val mensaje = if (e.message == "credenciales")
                getString(R.string.error_credenciales) else getString(R.string.error_conexion)
            mostrarError(mensaje)
        } catch (e: Exception) {
            mostrarError(getString(R.string.error_conexion))
        }
    }

    private fun ejecutar(accion: suspend (RiegoApiClient) -> EstadoRiego) {
        val cliente = api ?: return
        lifecycleScope.launch {
            try {
                val estado = withContext(Dispatchers.IO) { accion(cliente) }
                pintarEstado(estado)
            } catch (e: Exception) {
                mostrarError(getString(R.string.error_conexion))
            }
        }
    }

    private fun pintarEstado(estado: EstadoRiego) {
        binding.tvError.visibility = View.GONE
        binding.tvSuelo.text = "${estado.humSuelo} % (raw ${estado.rawSuelo})"

        val temp = estado.tempC?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
        val aire = estado.humAire?.let { it.toInt().toString() } ?: "--"
        binding.tvAire.text = "$temp °C · $aire %"

        val modoTexto = if (estado.modoAuto) getString(R.string.modo_auto) else getString(R.string.modo_manual)
        val bombaTexto = if (estado.bombaOn) getString(R.string.bomba_on) else getString(R.string.bomba_off)
        binding.tvModoBomba.text = "${getString(R.string.label_modo)}: $modoTexto · ${getString(R.string.label_bomba)}: $bombaTexto"

        val siguienteModo = if (estado.modoAuto) getString(R.string.modo_manual) else getString(R.string.modo_auto)
        binding.btnCambiarModo.text = "${getString(R.string.btn_cambiar_modo)} → $siguienteModo"

        binding.layoutManual.visibility = if (estado.modoAuto) View.GONE else View.VISIBLE
    }

    private fun mostrarError(mensaje: String) {
        binding.tvError.text = mensaje
        binding.tvError.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }
}
