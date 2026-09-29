package com.savkacarvajal.riegoiot

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class EstadoRiego(
    val tempC: Double?,
    val humAire: Double?,
    val humSuelo: Int,
    val rawSuelo: Int,
    val modoAuto: Boolean,
    val bombaOn: Boolean
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

    fun obtenerEstado(): EstadoRiego = llamar("/api/estado", post = false)
    fun cambiarModo(): EstadoRiego = llamar("/api/modo", post = true)
    fun encender(): EstadoRiego = llamar("/api/on", post = true)
    fun apagar(): EstadoRiego = llamar("/api/off", post = true)

    private fun llamar(path: String, post: Boolean): EstadoRiego {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", credencial)
        if (post) builder.post(ByteArray(0).toRequestBody(null))

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 401) throw ApiException("credenciales")
            if (!resp.isSuccessful) throw ApiException("http_${resp.code}")
            val cuerpo = resp.body?.string() ?: throw ApiException("respuesta_vacia")
            return parsear(cuerpo)
        }
    }

    private fun parsear(cuerpo: String): EstadoRiego {
        val j = JSONObject(cuerpo)
        return EstadoRiego(
            tempC = if (j.isNull("tempC")) null else j.getDouble("tempC"),
            humAire = if (j.isNull("humAire")) null else j.getDouble("humAire"),
            humSuelo = j.getInt("humSuelo"),
            rawSuelo = j.getInt("rawSuelo"),
            modoAuto = j.getBoolean("modoAuto"),
            bombaOn = j.getBoolean("bombaOn")
        )
    }
}
