/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the 32-byte extension-storage master key the SpotiFLAC runtime
 * requires before `InitExtensionSystem`. Upstream stores this key in the
 * platform keystore (FlutterSecureStorage); Hush mirrors that contract with an
 * Android Keystore AES-GCM wrapped key, so provider credentials written by the
 * extension runtime are encrypted at rest and survive app restarts.
 */
object SpotiFLACExtensionKeyStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "hush_spotiflac_extension_master_v1"
    private const val WRAPPED_FILE = "spotiflac_extension_master_key_v1"
    private const val KEY_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val lock = Any()

    /**
     * Returns the base64-encoded master key, creating and persisting a fresh
     * random key on first use. A key that can no longer be unwrapped (for
     * example after a keystore reset) is transparently replaced so playback can
     * proceed; the extension runtime then re-provisions its own credentials.
     */
    fun masterKeyBase64(context: Context): String = synchronized(lock) {
        val file = File(context.noBackupFilesDir, WRAPPED_FILE)
        unwrap(file)?.let { return it }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        wrap(file, fresh)
        Base64.encodeToString(fresh, Base64.NO_WRAP)
    }

    private fun unwrap(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val payload = Base64.decode(file.readText().trim(), Base64.NO_WRAP)
            if (payload.size <= GCM_IV_BYTES) return@runCatching null
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val cipherText = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val key = cipher.doFinal(cipherText)
            if (key.size != KEY_BYTES) return@runCatching null
            Base64.encodeToString(key, Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun wrap(file: File, key: ByteArray) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val cipherText = cipher.doFinal(key)
            val payload = cipher.iv + cipherText
            file.parentFile?.mkdirs()
            file.writeText(Base64.encodeToString(payload, Base64.NO_WRAP))
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
