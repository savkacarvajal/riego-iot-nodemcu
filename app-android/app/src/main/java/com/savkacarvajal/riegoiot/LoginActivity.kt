package com.savkacarvajal.riegoiot

import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.savkacarvajal.riegoiot.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
            binding.blobUno.setRenderEffect(blur)
            binding.blobDos.setRenderEffect(blur)
        }

        // Firebase Auth guarda su propia sesion; si ya hay una activa, no hace
        // falta volver a pedir correo y clave.
        if (Firebase.auth.currentUser != null) {
            irADashboard()
            return
        }

        binding.btnConectar.setOnClickListener { intentarIngresar() }
    }

    private fun intentarIngresar() {
        val correo = binding.inputUsuario.text.toString().trim()
        val clave = binding.inputClave.text.toString()
        if (correo.isEmpty() || clave.isEmpty()) {
            mostrarError(getString(R.string.error_credenciales))
            return
        }

        binding.tvError.visibility = View.GONE
        binding.btnConectar.isEnabled = false
        binding.btnConectar.text = getString(R.string.btn_conectando)

        lifecycleScope.launch {
            try {
                Firebase.auth.signInWithEmailAndPassword(correo, clave).await()
                irADashboard()
            } catch (e: Exception) {
                mostrarError(getString(R.string.error_credenciales))
                binding.btnConectar.isEnabled = true
                binding.btnConectar.text = getString(R.string.btn_conectar)
            }
        }
    }

    private fun irADashboard() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun mostrarError(mensaje: String) {
        binding.tvError.text = mensaje
        binding.tvError.visibility = View.VISIBLE
    }
}
