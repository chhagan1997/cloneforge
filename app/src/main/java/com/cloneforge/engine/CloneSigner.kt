package com.cloneforge.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Signs APKs with a key that lives in the Android Keystore (generated on first use, never leaves the device).
 * Every clone made on this device is signed with the same key, so clones can be updated in place.
 */
object CloneSigner {
    private const val ALIAS = "cloneforge_signing_key"

    private fun ensureKey(ks: KeyStore) {
        if (ks.containsAlias(ALIAS)) return
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=CloneForge Clone Signer"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 30L * 365 * 86_400_000L))
                .build()
        )
        kpg.generateKeyPair()
    }

    fun sign(input: File, output: File) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ensureKey(ks)
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        val signer = ApkSigner.SignerConfig.Builder("cloneforge", key, listOf(cert)).build()
        output.delete()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .setCreatedBy("CloneForge")
            .build()
            .sign()
    }
}
