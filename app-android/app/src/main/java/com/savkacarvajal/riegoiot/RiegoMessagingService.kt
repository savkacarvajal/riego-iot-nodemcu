package com.savkacarvajal.riegoiot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

private const val CANAL_ID = "riego_alertas"

/**
 * Recibe los push que manda el bridge cuando la bomba se enciende o se apaga
 * (ver /bridge/index.js -> notificar()). Se muestra manualmente con
 * NotificationCompat para que aparezca igual con la app abierta o cerrada
 * (el payload "notification" solo se autodespliega cuando la app esta en
 * segundo plano).
 */
class RiegoMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        crearCanalSiHaceFalta()
    }

    override fun onMessageReceived(mensaje: RemoteMessage) {
        val titulo = mensaje.notification?.title ?: getString(R.string.app_name)
        val cuerpo = mensaje.notification?.body ?: return

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificacion = NotificationCompat.Builder(this, CANAL_ID)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setColor(ContextCompat.getColor(this, R.color.primario))
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val gestor = ContextCompat.getSystemService(this, NotificationManager::class.java)
        gestor?.notify(System.currentTimeMillis().toInt(), notificacion)
    }

    override fun onNewToken(token: String) {
        RegistroNotificaciones.registrarToken(token)
    }

    private fun crearCanalSiHaceFalta() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canal = NotificationChannel(
            CANAL_ID,
            getString(R.string.canal_notificaciones_nombre),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = getString(R.string.canal_notificaciones_descripcion) }
        val gestor = ContextCompat.getSystemService(this, NotificationManager::class.java)
        gestor?.createNotificationChannel(canal)
    }
}
