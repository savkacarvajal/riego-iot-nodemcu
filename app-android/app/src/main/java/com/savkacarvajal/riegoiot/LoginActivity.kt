package com.savkacarvajal.riegoiot

import android.content.Context
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.savkacarvajal.riegoiot.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val prefs by lazy { getSharedPreferences("riego_iot", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
            binding.blobUno.setRenderEffect(blur)
            binding.blobDos.setRenderEffect(blur)
        }

        binding.inputIp.setText(prefs.getString("ip", ""))
        binding.inputUsuario.setText(prefs.getString("usuario", "admin"))
        val claveCifrada = prefs.getString("clave_cifrada", null)
        val ivClave = prefs.getString("clave_iv", null)
        if (claveCifrada != null && ivClave != null) {
            binding.inputClave.setText(SeguridadLocal.descifrar(claveCifrada, ivClave) ?: "")
        }

        binding.btnConectar.setOnClickListener { intentarConectar() }

        // Si ya hay una conexion guardada de una sesion anterior, se prueba
        // sola al abrir la app; si falla, el usuario ve el formulario y el error.
        if (binding.inputIp.text.toString().isNotBlank()) intentarConectar()
    }

    private fun intentarConectar() {
        val ip = binding.inputIp.text.toString().trim()
        val usuario = binding.inputUsuario.text.toString().trim()
        val clave = binding.inputClave.text.toString()
        if (ip.isEmpty()) {
            mostrarError(getString(R.string.error_conexion))
            return
        }

        binding.tvError.visibility = View.GONE
        binding.btnConectar.isEnabled = false
        binding.btnConectar.text = getString(R.string.btn_conectando)

        val cliente = RiegoApiClient(ip, usuario, clave)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { cliente.obtenerEstado() }
                val (claveCifrada, ivClave) = SeguridadLocal.cifrar(clave)
                prefs.edit()
                    .putString("ip", ip)
                    .putString("usuario", usuario)
                    .putString("clave_cifrada", claveCifrada)
                    .putString("clave_iv", ivClave)
                    .apply()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } catch (e: ApiException) {
                val mensaje = if (e.message == "credenciales")
                    getString(R.string.error_credenciales) else getString(R.string.error_conexion)
                mostrarError(mensaje)
            } catch (e: Exception) {
                mostrarError(getString(R.string.error_conexion))
            } finally {
                binding.btnConectar.isEnabled = true
                binding.btnConectar.text = getString(R.string.btn_conectar)
            }
        }
    }

    private fun mostrarError(mensaje: String) {
        binding.tvError.text = mensaje
        binding.tvError.visibility = View.VISIBLE
    }
}
