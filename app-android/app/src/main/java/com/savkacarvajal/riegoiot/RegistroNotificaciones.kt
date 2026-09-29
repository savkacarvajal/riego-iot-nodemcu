package com.savkacarvajal.riegoiot

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Registra el token FCM de este dispositivo en /dispositivos, para que el
 * bridge (ver /bridge/index.js -> notificar()) le pueda mandar un push
 * cuando la bomba cambia de estado. Es un objeto aparte (no parte de
 * RiegoRepository) porque FirebaseMessagingService.onNewToken() puede
 * dispararse sin que MainActivity este abierta.
 */
object RegistroNotificaciones {

    fun activar(alExito: () -> Unit, alError: (Exception) -> Unit) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> registrarToken(token, alExito, alError) }
            .addOnFailureListener { alError(it) }
    }

    fun registrarToken(token: String, alExito: () -> Unit = {}, alError: (Exception) -> Unit = {}) {
        val datos = mapOf("token" to token, "plataforma" to "android")
        FirebaseDatabase.getInstance().reference.child("dispositivos").child(token).setValue(datos)
            .addOnSuccessListener { alExito() }
            .addOnFailureListener { alError(it) }
    }
}
