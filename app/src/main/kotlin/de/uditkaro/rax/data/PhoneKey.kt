package de.uditkaro.rax.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import de.uditkaro.rax.tailnet.SshKeys
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The SSH key Rax makes on this phone, an ECDSA P-256 key in Android's keystore. Its private half
 * never leaves the keystore, so it cannot be copied off the phone, not even by Rax. It is made the
 * first time it is needed. [comment] ends its authorized_keys line.
 */
class PhoneKey(private val comment: String) {

    private val encodedPublic: ByteArray by lazy {
        keyStore().getCertificate(ALIAS)?.publicKey?.encoded
            ?: KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair().public.encoded
    }

    private val signingKey: PrivateKey by lazy {
        encodedPublic
        keyStore().getKey(ALIAS, null) as PrivateKey
    }

    private val keyLine: String by lazy { SshKeys.authorizedKey(encodedPublic, comment) }

    /** The public half in PKIX DER form. Blocks while the key is made. */
    fun publicKey(): ByteArray = encodedPublic

    /** An ASN.1 DER ECDSA signature of [data] hashed with SHA-256, the form SSH needs from this key. */
    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(signingKey)
        update(data)
        sign()
    }

    /** The line to add to ~/.ssh/authorized_keys on a computer. Blocks while the key is made. */
    fun authorizedKey(): String = keyLine

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "rax_ssh_phone_key"
    }
}
