package com.savkacarvajal.riegoiot

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

class ApiException(message: String) : Exception(message)

/**
 * Cliente HTTP del NodeMCU. Pensado solo para la red local: el NodeMCU
 * no tiene HTTPS, por eso el cleartext esta habilitado en
 * network_security_config.xml.
 */
class RiegoApiClient(ip: String, usuario: String, clave: String) {

    private val baseUrl = "http://$ip"
    private val credencial = Credentials.basic(usuario, clave)

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun obtenerEstado(): EstadoRiego = parsearEstado(llamar("/api/estado", post = false))
    fun cambiarModo(): EstadoRiego = parsearEstado(llamar("/api/modo", post = true))
    fun encender(): EstadoRiego = parsearEstado(llamar("/api/on", post = true))
    fun apagar(): EstadoRiego = parsearEstado(llamar("/api/off", post = true))
    fun obtenerHistorial(): List<EventoRiego> = parsearHistorial(llamar("/api/historial", post = false))
    fun obtenerUmbrales(): Umbrales = parsearUmbrales(llamar("/api/umbrales", post = false))

    fun actualizarUmbrales(umbralRiego: Int, umbralApaga: Int): Umbrales = parsearUmbrales(
        llamar(
            "/api/umbrales", post = true,
            formParams = mapOf("umbralRiego" to umbralRiego.toString(), "umbralApaga" to umbralApaga.toString())
        )
    )

    private fun llamar(path: String, post: Boolean, formParams: Map<String, String>? = null): String {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", credencial)
        if (post) {
            val cuerpo = if (formParams != null) {
                val fb = FormBody.Builder()
                formParams.forEach { (k, v) -> fb.add(k, v) }
                fb.build()
            } else {
                ByteArray(0).toRequestBody(null)
            }
            builder.post(cuerpo)
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 401) throw ApiException("credenciales")
            if (!resp.isSuccessful) throw ApiException("http_${resp.code}")
            return resp.body?.string() ?: throw ApiException("respuesta_vacia")
        }
    }

    private fun parsearEstado(cuerpo: String): EstadoRiego {
        val j = JSONObject(cuerpo)
        return EstadoRiego(
            tempC = if (j.isNull("tempC")) null else j.getDouble("tempC"),
            humAire = if (j.isNull("humAire")) null else j.getDouble("humAire"),
            humSuelo = j.getInt("humSuelo"),
            rawSuelo = j.getInt("rawSuelo"),
            luxLuz = if (j.isNull("luxLuz")) null else j.getDouble("luxLuz"),
            modoAuto = j.getBoolean("modoAuto"),
            bombaOn = j.getBoolean("bombaOn")
        )
    }

    private fun parsearHistorial(cuerpo: String): List<EventoRiego> {
        val arr = JSONArray(cuerpo)
        return (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            EventoRiego(haceMin = e.getInt("haceMin"), duracionS = e.getInt("duracionS"))
        }
    }

    private fun parsearUmbrales(cuerpo: String): Umbrales {
        val j = JSONObject(cuerpo)
        return Umbrales(umbralRiego = j.getInt("umbralRiego"), umbralApaga = j.getInt("umbralApaga"))
    }
}
