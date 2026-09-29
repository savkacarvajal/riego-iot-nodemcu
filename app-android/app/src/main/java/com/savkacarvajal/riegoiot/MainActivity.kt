package com.savkacarvajal.riegoiot

import android.content.Context
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var api: RiegoApiClient

    private val prefs by lazy { getSharedPreferences("riego_iot", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        aplicarBlurDecorativo()

        val ip = prefs.getString("ip", "") ?: ""
        if (ip.isBlank()) {
            // No hay conexion guardada (ej. se borraron los datos de la app):
            // vuelve al login en vez de mostrar un dashboard sin datos.
            irALogin()
            return
        }
        api = RiegoApiClient(ip, prefs.getString("usuario", "") ?: "", prefs.getString("clave", "") ?: "")

        configurarSeccionesPlegables()
        binding.btnCambiarConexion.setOnClickListener { irALogin() }
        binding.btnCambiarModo.setOnClickListener { ejecutar { it.cambiarModo() } }
        binding.btnEncender.setOnClickListener { ejecutar { it.encender() } }
        binding.btnApagar.setOnClickListener { ejecutar { it.apagar() } }

        pollingJob = lifecycleScope.launch {
            while (isActive) {
                actualizarEstado()
                actualizarHistorial()
                delay(2000)
            }
        }
    }

    // Manchas de color decorativas detras del contenido, difuminadas de
    // verdad con RenderEffect (API 31+). En versiones anteriores se ven
    // igual pero sin desenfoque: el degradado radial del drawable ya las
    // hace lucir como un resplandor suave por si solo.
    private fun aplicarBlurDecorativo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
            binding.blobUno.setRenderEffect(blur)
            binding.blobDos.setRenderEffect(blur)
        }
    }

    private fun irALogin() {
        pollingJob?.cancel()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    // Paneles por capa (Percepcion / Procesamiento / Aplicacion), cada uno se
    // expande o colapsa al tocar su encabezado. Percepcion arranca abierto
    // (son los datos principales); los otros dos, cerrados.
    private fun configurarSeccionesPlegables() {
        toggleAlTocar(binding.headerPercepcion, binding.contentPercepcion, binding.chevronPercepcion)
        toggleAlTocar(binding.headerProcesamiento, binding.contentProcesamiento, binding.chevronProcesamiento)
        toggleAlTocar(binding.headerAplicacion, binding.contentAplicacion, binding.chevronAplicacion)
    }

    private fun toggleAlTocar(header: View, content: View, chevron: TextView) {
        header.setOnClickListener {
            val abierto = content.visibility == View.VISIBLE
            content.visibility = if (abierto) View.GONE else View.VISIBLE
            chevron.text = getString(if (abierto) R.string.chevron_cerrado else R.string.chevron_abierto)
        }
    }

    private suspend fun actualizarEstado() {
        val cliente = api
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

    private suspend fun actualizarHistorial() {
        val cliente = api
        try {
            val eventos = withContext(Dispatchers.IO) { cliente.obtenerHistorial() }
            pintarHistorial(eventos)
        } catch (e: Exception) {
            // El historial es informacion secundaria: si falla, se deja el ultimo
            // valor pintado en vez de tapar la pantalla con un error.
        }
    }

    private fun ejecutar(accion: suspend (RiegoApiClient) -> EstadoRiego) {
        val cliente = api
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

        if (estado.luxLuz != null) {
            binding.layoutLuz.visibility = View.VISIBLE
            binding.tvLuz.text = getString(R.string.formato_lux, estado.luxLuz.toInt())
        } else {
            binding.layoutLuz.visibility = View.GONE
        }

        val modoTexto = if (estado.modoAuto) getString(R.string.modo_auto) else getString(R.string.modo_manual)
        val bombaTexto = if (estado.bombaOn) getString(R.string.bomba_on) else getString(R.string.bomba_off)
        binding.tvModoBomba.text = "${getString(R.string.label_modo)}: $modoTexto · ${getString(R.string.label_bomba)}: $bombaTexto"

        val siguienteModo = if (estado.modoAuto) getString(R.string.modo_manual) else getString(R.string.modo_auto)
        binding.btnCambiarModo.text = "${getString(R.string.btn_cambiar_modo)} → $siguienteModo"

        binding.layoutManual.visibility = if (estado.modoAuto) View.GONE else View.VISIBLE
    }

    private fun pintarHistorial(eventos: List<EventoRiego>) {
        binding.layoutHistorial.removeAllViews()
        binding.tvHistorialVacio.visibility = if (eventos.isEmpty()) View.VISIBLE else View.GONE

        val dp = resources.displayMetrics.density
        eventos.forEachIndexed { index, evento ->
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
            val tvTiempo = TextView(this).apply {
                text = formatearHaceMin(evento.haceMin)
                setTextColor(getColor(R.color.texto_secundario))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvDuracion = TextView(this).apply {
                text = getString(R.string.formato_duracion, evento.duracionS)
                setTextColor(getColor(R.color.texto))
                gravity = Gravity.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            fila.addView(tvTiempo)
            fila.addView(tvDuracion)
            binding.layoutHistorial.addView(fila)

            if (index < eventos.size - 1) {
                val divisor = View(this).apply {
                    setBackgroundColor(getColor(R.color.divisor))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    )
                }
                binding.layoutHistorial.addView(divisor)
            }
        }
    }

    private fun formatearHaceMin(min: Int): String {
        if (min < 60) return getString(R.string.formato_hace_min, min)
        return getString(R.string.formato_hace_horas, min / 60, min % 60)
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
