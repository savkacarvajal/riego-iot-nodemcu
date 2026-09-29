package com.savkacarvajal.riegoiot

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.tasks.await

data class EstadoRiego(
    val tempC: Double?,
    val humAire: Double?,
    val humSuelo: Int,
    val rawSuelo: Int,
    val luxLuz: Double?,
    val modoAuto: Boolean,
    val bombaOn: Boolean
)

data class EventoRiego(
    val haceMin: Int,
    val duracionS: Int
)

data class Umbrales(
    val umbralRiego: Int,
    val umbralApaga: Int
)

/**
 * Lee y escribe en Firebase Realtime Database: es la misma base que usan la
 * pagina web y el bridge que le habla al NodeMCU (ver /bridge en el repo).
 * Esta app ya no le habla directo al NodeMCU por IP: el bridge es el unico
 * que hace eso, porque el NodeMCU no tiene HTTPS.
 */
class RiegoRepository {

    private val raiz = FirebaseDatabase.getInstance().reference

    fun escucharEstado(alCambiar: (EstadoRiego) -> Unit, alError: (Exception) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                parsearEstado(snapshot)?.let(alCambiar)
            }
            override fun onCancelled(error: DatabaseError) = alError(error.toException())
        }
        raiz.child("estado").addValueEventListener(listener)
        return listener
    }

    fun dejarDeEscuchar(nodo: String, listener: ValueEventListener) {
        raiz.child(nodo).removeEventListener(listener)
    }

    fun escucharHistorial(alCambiar: (List<EventoRiego>) -> Unit): ValueEventListener {
        val consulta = raiz.child("historial").orderByChild("inicio").limitToLast(30)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val ahora = System.currentTimeMillis()
                val eventos = snapshot.children.mapNotNull { hijo ->
                    val inicio = hijo.child("inicio").getValue(Long::class.java) ?: return@mapNotNull null
                    val duracionS = hijo.child("duracionS").getValue(Long::class.java)?.toInt() ?: return@mapNotNull null
                    EventoRiego(haceMin = ((ahora - inicio) / 60_000L).toInt().coerceAtLeast(0), duracionS = duracionS)
                }
                alCambiar(eventos.reversed()) // mas reciente primero
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        consulta.addValueEventListener(listener)
        return listener
    }

    fun dejarDeEscucharHistorial(listener: ValueEventListener) {
        raiz.child("historial").removeEventListener(listener)
    }

    // Los umbrales cambian poco: se piden una vez al entrar (igual que antes),
    // no con un listener en vivo, para no pisar lo que el usuario este editando.
    suspend fun obtenerUmbrales(): Umbrales? {
        val snapshot = raiz.child("umbrales").get().await()
        val riego = snapshot.child("umbralRiego").getValue(Long::class.java)?.toInt() ?: return null
        val apaga = snapshot.child("umbralApaga").getValue(Long::class.java)?.toInt() ?: return null
        return Umbrales(riego, apaga)
    }

    fun cambiarModo(modoAutoActual: Boolean, alError: (Exception) -> Unit) =
        enviarComando(mapOf("modoAuto" to !modoAutoActual), alError)

    fun encender(alError: (Exception) -> Unit) =
        enviarComando(mapOf("accionBomba" to "on"), alError)

    fun apagar(alError: (Exception) -> Unit) =
        enviarComando(mapOf("accionBomba" to "off"), alError)

    fun actualizarUmbrales(umbralRiego: Int, umbralApaga: Int, alExito: () -> Unit, alError: (Exception) -> Unit) {
        enviarComando(mapOf("umbralRiego" to umbralRiego, "umbralApaga" to umbralApaga), alError, alExito)
    }

    private fun enviarComando(campos: Map<String, Any>, alError: (Exception) -> Unit, alExito: (() -> Unit)? = null) {
        val comando = campos + ("ts" to ServerValue.TIMESTAMP)
        raiz.child("comandos").setValue(comando)
            .addOnSuccessListener { alExito?.invoke() }
            .addOnFailureListener { alError(it) }
    }

    private fun parsearEstado(snapshot: DataSnapshot): EstadoRiego? {
        val humSuelo = snapshot.child("humSuelo").getValue(Long::class.java)?.toInt() ?: return null
        val rawSuelo = snapshot.child("rawSuelo").getValue(Long::class.java)?.toInt() ?: return null
        val modoAuto = snapshot.child("modoAuto").getValue(Boolean::class.java) ?: return null
        val bombaOn = snapshot.child("bombaOn").getValue(Boolean::class.java) ?: return null
        return EstadoRiego(
            tempC = snapshot.child("tempC").getValue(Double::class.java),
            humAire = snapshot.child("humAire").getValue(Double::class.java),
            humSuelo = humSuelo,
            rawSuelo = rawSuelo,
            luxLuz = snapshot.child("luxLuz").getValue(Double::class.java),
            modoAuto = modoAuto,
            bombaOn = bombaOn
        )
    }
}
