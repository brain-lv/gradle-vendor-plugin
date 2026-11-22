/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import org.gradle.api.GradleException
import java.io.File
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal object VendorSignature {
    const val SUFFIX = ".sig"

    private val KEY_ALGORITHMS = listOf("Ed25519", "RSA", "EC")

    fun der(text: String): ByteArray {
        val body = text.lines().filterNot { it.startsWith("-----") }.joinToString("").filterNot { it.isWhitespace() }
        return runCatching { Base64.getDecoder().decode(body) }.getOrElse {
            throw GradleException("The signing key is neither PEM nor base64: ${it.message}")
        }
    }

    fun privateKey(text: String): PrivateKey {
        val spec = PKCS8EncodedKeySpec(der(text))
        return KEY_ALGORITHMS.firstNotNullOfOrNull { runCatching { KeyFactory.getInstance(it).generatePrivate(spec) }.getOrNull() }
            ?: throw GradleException(
                "The vendor signing key cannot be read. Use an unencrypted PKCS#8 private key (PEM 'BEGIN PRIVATE KEY') " +
                    "of type Ed25519, RSA or EC, for example from the task generateVendorSigningKey."
            )
    }

    fun publicKey(text: String): PublicKey {
        val spec = X509EncodedKeySpec(der(text))
        return KEY_ALGORITHMS.firstNotNullOfOrNull { runCatching { KeyFactory.getInstance(it).generatePublic(spec) }.getOrNull() }
            ?: throw GradleException(
                "vendor.signing.publicKey cannot be read. Use the public key (PEM 'BEGIN PUBLIC KEY', or its base64 on one line) " +
                    "that belongs to the distributor's signing key."
            )
    }

    private fun algorithm(key: Key): String = when (key.algorithm) {
        "Ed25519", "EdDSA" -> "Ed25519"
        "RSA" -> "SHA256withRSA"
        "EC" -> "SHA256withECDSA"
        else -> throw GradleException("Unsupported signing key type ${key.algorithm}; use Ed25519, RSA or EC.")
    }

    private fun feed(signature: Signature, file: File) {
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                signature.update(buffer, 0, read)
            }
        }
    }

    fun sign(file: File, key: PrivateKey): String {
        val signature = Signature.getInstance(algorithm(key))
        signature.initSign(key)
        feed(signature, file)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun verify(file: File, signatureText: String, key: PublicKey): Boolean {
        val bytes = runCatching { Base64.getDecoder().decode(signatureText.trim()) }.getOrNull() ?: return false
        val signature = Signature.getInstance(algorithm(key))
        signature.initVerify(key)
        feed(signature, file)
        return runCatching { signature.verify(bytes) }.getOrDefault(false)
    }

    fun requireValid(zip: File, signatureFile: File?, publicKeyText: String) {
        if (signatureFile == null || !signatureFile.isFile) {
            throw GradleException(
                "vendor.signing.publicKey is set, but the vendor distribution ${zip.name} has no signature " +
                    "(${zip.name}$SUFFIX next to it). The distributor signs it when vendor.signing.key is set; it was not unpacked."
            )
        }
        if (!verify(zip, signatureFile.readText(), publicKey(publicKeyText))) {
            throw GradleException(
                "The signature of the vendor distribution ${zip.name} does not match vendor.signing.publicKey. The file was " +
                    "changed or signed with another key; it was not unpacked."
            )
        }
    }

    fun keyId(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(der(text)).joinToString("") { "%02x".format(it) }.take(16)

    fun pem(type: String, der: ByteArray): String =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    fun generate(): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        return pem("PRIVATE KEY", pair.private.encoded) to pem("PUBLIC KEY", pair.public.encoded)
    }

    fun readKeyText(value: String, resolve: (String) -> File): String {
        if ("-----BEGIN" in value) return value
        val file = runCatching { resolve(value) }.getOrNull()
        return if (file != null && file.isFile) file.readText() else value
    }
}
