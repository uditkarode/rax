package de.uditkaro.rax.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

/**
 * What Rax keeps for SSH hosts: the pasted private key, its passphrase or the password of each
 * host, encrypted with a key in Android's keystore, and the host key each computer showed the
 * first time. The file is in the directory that backups leave out, because the keystore key cannot
 * leave this phone.
 */
class SshStore(context: Context) {
    private val data: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(context.applicationContext.noBackupFilesDir, "ssh.preferences_pb") },
    )

    /** What is saved to sign in to one host. A value that could not be decrypted is null. */
    data class Secrets(val privateKey: String?, val passphrase: String?, val password: String?)

    suspend fun secrets(hostId: String): Secrets {
        val p = data.data.first()
        return Secrets(
            privateKey = p[key(PRIVATE_KEY, hostId)]?.let(::decrypt),
            passphrase = p[key(PASSPHRASE, hostId)]?.let(::decrypt),
            password = p[key(PASSWORD, hostId)]?.let(::decrypt),
        )
    }

    /** What is saved for [hostId], read without decrypting it. */
    data class Saved(val privateKey: Boolean, val password: Boolean)

    suspend fun saved(hostId: String): Saved {
        val p = data.data.first()
        return Saved(privateKey = key(PRIVATE_KEY, hostId) in p, password = key(PASSWORD, hostId) in p)
    }

    /** The host key that [hostId] showed the first time, in authorized_keys form. */
    suspend fun hostKey(hostId: String): String? = data.data.first()[key(HOST_KEY, hostId)]

    /** Saves the private key and its passphrase that [hostId] signs in with, and drops its password. */
    suspend fun saveOwnKey(hostId: String, privateKey: String, passphrase: String) = data.edit { p ->
        p[key(PRIVATE_KEY, hostId)] = encrypt(privateKey)
        if (passphrase.isEmpty()) p.remove(key(PASSPHRASE, hostId)) else p[key(PASSPHRASE, hostId)] = encrypt(passphrase)
        p.remove(key(PASSWORD, hostId))
    }

    /** Saves the password that [hostId] signs in with, and drops its private key. */
    suspend fun savePassword(hostId: String, password: String) = data.edit { p ->
        p[key(PASSWORD, hostId)] = encrypt(password)
        p.remove(key(PRIVATE_KEY, hostId))
        p.remove(key(PASSPHRASE, hostId))
    }

    /** Drops the private key and the password of [hostId], for a host that signs in with the phone's key. */
    suspend fun forgetSecrets(hostId: String) = data.edit { p ->
        SECRETS.forEach { p.remove(key(it, hostId)) }
    }

    suspend fun saveHostKey(hostId: String, hostKey: String) = data.edit { p -> p[key(HOST_KEY, hostId)] = hostKey }

    /** Forgets everything about [hostId], for a host that was deleted. */
    suspend fun forget(hostId: String) = data.edit { p ->
        (SECRETS + HOST_KEY).forEach { p.remove(key(it, hostId)) }
    }

    private fun key(kind: String, hostId: String) = stringPreferencesKey(kind + hostId)

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    } catch (e: Exception) {
        Log.w(TAG, "a saved SSH secret could not be decrypted", e)
        null
    }

    private val secretKey: SecretKey by lazy {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        store.getKey(KEY_ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rax_ssh_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val PRIVATE_KEY = "key/"
        private const val PASSPHRASE = "passphrase/"
        private const val PASSWORD = "password/"
        private const val HOST_KEY = "hostkey/"
        private val SECRETS = listOf(PRIVATE_KEY, PASSPHRASE, PASSWORD)
        private const val TAG = "RaxSsh"
    }
}
