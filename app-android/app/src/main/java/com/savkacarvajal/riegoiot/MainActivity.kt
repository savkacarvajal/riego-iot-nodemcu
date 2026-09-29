package com.savkacarvajal.riegoiot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ValueEventListener
import com.google.firebase.ktx.Firebase
import com.savkacarvajal.riegoiot.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repo = RiegoRepository()
    private var ultimoEstado: EstadoRiego? = null
    private var listenerEstado: ValueEventListener? = null
    private var listenerHistorial: ValueEventListener? = null

    private val solicitarPermisoNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) activarNotificaciones() else mostrarEstadoNotificaciones(getString(R.string.notificaciones_denegadas), esError = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        aplicarBlurDecorativo()

        if (Firebase.auth.currentUser == null) {
            irALogin()
            return
        }

        configurarSeccionesPlegables()
        binding.btnCambiarConexion.setOnClickListener {
            Firebase.auth.signOut()
            irALogin()
        }
        binding.btnCambiarModo.setOnClickListener {
            ultimoEstado?.let { estado -> repo.cambiarModo(estado.modoAuto) { mostrarError(getString(R.string.error_conexion)) } }
        }
        binding.btnEncender.setOnClickListener { repo.encender { mostrarError(getString(R.string.error_conexion)) } }
        binding.btnApagar.setOnClickListener { repo.apagar { mostrarError(getString(R.string.error_conexion)) } }
        binding.btnGuardarUmbrales.setOnClickListener { guardarUmbrales() }
        binding.btnNotificaciones.setOnClickListener { pedirPermisoYActivarNotificaciones() }

        cargarUmbrales()
        listenerEstado = repo.escucharEstado(
            alCambiar = { estado -> ultimoEstado = estado; pintarEstado(estado) },
            alError = { mostrarError(getString(R.string.error_conexion)) }
        )
        listenerHistorial = repo.escucharHistorial { eventos -> pintarHistorial(eventos) }
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
        quitarListeners()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun quitarListeners() {
        listenerEstado?.let { repo.dejarDeEscuchar("estado", it) }
        listenerHistorial?.let { repo.dejarDeEscucharHistorial(it) }
        listenerEstado = null
        listenerHistorial = null
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

    // Los umbrales cambian poco: se cargan una vez al entrar (lectura unica a
    // Firebase), no con un listener en vivo, asi no se pisa lo que el usuario
    // este editando.
    private fun cargarUmbrales() {
        lifecycleScope.launch {
            try {
                val umbrales = withContext(Dispatchers.IO) { repo.obtenerUmbrales() }
                umbrales?.let { pintarUmbrales(it) }
            } catch (e: Exception) {
                // Si falla, los campos quedan vacios; guardar reintenta la conexion.
            }
        }
    }

    private fun pintarUmbrales(umbrales: Umbrales) {
        binding.inputUmbralRiego.setText(umbrales.umbralRiego.toString())
        binding.inputUmbralApaga.setText(umbrales.umbralApaga.toString())
    }

    private fun guardarUmbrales() {
        val riego = binding.inputUmbralRiego.text.toString().toIntOrNull()
        val apaga = binding.inputUmbralApaga.text.toString().toIntOrNull()
        if (riego == null || apaga == null || riego !in 0..100 || apaga !in 0..100 || apaga <= riego) {
            mostrarEstadoUmbrales(getString(R.string.error_umbrales), esError = true)
            return
        }
        repo.actualizarUmbrales(
            riego, apaga,
            alExito = {
                pintarUmbrales(Umbrales(riego, apaga))
                mostrarEstadoUmbrales(getString(R.string.umbrales_guardados), esError = false)
            },
            alError = { mostrarEstadoUmbrales(getString(R.string.error_conexion), esError = true) }
        )
    }

    private fun mostrarEstadoUmbrales(mensaje: String, esError: Boolean) {
        binding.tvUmbralesEstado.text = mensaje
        binding.tvUmbralesEstado.setTextColor(getColor(if (esError) R.color.error else R.color.primario_oscuro))
        binding.tvUmbralesEstado.visibility = View.VISIBLE
    }

    // En Android 13+ (API 33) mostrar notificaciones requiere permiso en tiempo
    // de ejecucion; en versiones anteriores el permiso se concede solo.
    private fun pedirPermisoYActivarNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val concedido = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (concedido) activarNotificaciones() else solicitarPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            activarNotificaciones()
        }
    }

    private fun activarNotificaciones() {
        RegistroNotificaciones.activar(
            alExito = { mostrarEstadoNotificaciones(getString(R.string.notificaciones_activadas), esError = false) },
            alError = { mostrarEstadoNotificaciones(getString(R.string.notificaciones_error), esError = true) }
        )
    }

    private fun mostrarEstadoNotificaciones(mensaje: String, esError: Boolean) {
        binding.tvNotificacionesEstado.text = mensaje
        binding.tvNotificacionesEstado.setTextColor(getColor(if (esError) R.color.error else R.color.primario_oscuro))
        binding.tvNotificacionesEstado.visibility = View.VISIBLE
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
        quitarListeners()
    }
}
