package com.savkacarvajal.riegoiot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra la clave del NodeMCU en reposo con una llave que nunca sale del
 * Android Keystore (respaldada por hardware cuando el dispositivo lo
 * permite). IP y usuario no son secretos y se guardan en texto plano.
 *
 * EncryptedSharedPreferences (androidx.security-crypto) esta descontinuado
 * desde 2025 sin reemplazo estable simple, por eso el cifrado es manual
 * aqui en vez de depender de esa libreria.
 */
object SeguridadLocal {
    private const val ALIAS = "riego_iot_clave_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMACION = "AES/GCM/NoPadding"
    private const val TAG_LEN_BITS = 128

    private fun obtenerOCrearLlave(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generador.generateKey()
    }

    /** Devuelve (cifradoBase64, ivBase64). */
    fun cifrar(texto: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMACION)
        cipher.init(Cipher.ENCRYPT_MODE, obtenerOCrearLlave())
        val cifrado = cipher.doFinal(texto.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cifrado, Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    /** Devuelve null ante dato corrupto o llave invalidada, nunca lanza. */
    fun descifrar(cifradoB64: String, ivB64: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMACION)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, obtenerOCrearLlave(), GCMParameterSpec(TAG_LEN_BITS, iv))
            val texto = cipher.doFinal(Base64.decode(cifradoB64, Base64.NO_WRAP))
            String(texto, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
