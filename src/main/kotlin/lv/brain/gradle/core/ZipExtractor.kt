/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

object ZipExtractor {
    private const val READY_MARKER = ".vendor-ready"

    fun isUnpacked(extractDir: File): Boolean = File(extractDir, READY_MARKER).isFile

    fun isCurrent(zipFile: File, extractDir: File, expectedSha256: String? = null, publicKey: String? = null): Boolean {
        val marker = File(extractDir, READY_MARKER)
        return marker.isFile && marker.readText() == fingerprint(zipFile, expectedSha256, publicKey)
    }

    fun unpackedFingerprint(extractDir: File): String =
        File(extractDir, READY_MARKER).takeIf { it.isFile }?.readText()?.lineSequence()?.firstOrNull { it.startsWith("content=") }.orEmpty()

    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun extractIfChanged(
        zipFile: File,
        extractDir: File,
        expectedSha256: String? = null,
        signatureFile: File? = null,
        publicKey: String? = null
    ) {
        if (isCurrent(zipFile, extractDir, expectedSha256, publicKey)) return
        if (publicKey != null) VendorSignature.requireValid(zipFile, signatureFile, publicKey)
        if (expectedSha256 != null) requireSha256(zipFile, expectedSha256)

        extractDir.parentFile.mkdirs()
        val lockFile = File(extractDir.parentFile, "${extractDir.name}.lock")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                if (!isCurrent(zipFile, extractDir, expectedSha256, publicKey)) {
                    replaceWithStaging(zipFile, extractDir, fingerprint(zipFile, expectedSha256, publicKey))
                }
            }
        }
    }

    private fun requireSha256(zipFile: File, expected: String) {
        val actual = sha256(zipFile)
        if (actual != expected) {
            throw org.gradle.api.GradleException(
                "The vendor distribution ${zipFile.name} has the SHA-256 $actual, but vendor.sha256 expects $expected. " +
                    "The file is damaged or not the expected release; it was not unpacked. Check vendor.version and " +
                    "vendor.url, or update vendor.sha256 from the .sha256 file published with the ZIP."
            )
        }
    }

    private fun replaceWithStaging(zipFile: File, extractDir: File, fingerprint: String) {
        val stagingDir = File(extractDir.parentFile, "${extractDir.name}.staging")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        try {
            unzip(zipFile, stagingDir)
            File(stagingDir, READY_MARKER).writeText(fingerprint)
            if (extractDir.exists()) extractDir.deleteRecursively()
            Files.move(stagingDir.toPath(), extractDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }

    internal fun unzip(zipFile: File, targetDir: File) {
        val root = targetDir.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream().buffered(128 * 1024)).use { zis ->
            generateSequence { zis.nextEntry }.forEach { entry ->
                val outFile = File(targetDir, entry.name)
                check(outFile.canonicalPath.startsWith(root)) { "Illegal ZIP entry outside target directory: ${entry.name}" }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile.mkdirs()
                    outFile.outputStream().buffered(64 * 1024).use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun contentDigest(zipFile: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.util.zip.ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) digest.update("${entry.name}\u0000${entry.crc}:${entry.size}\n".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fingerprint(zipFile: File, expectedSha256: String?, publicKey: String?): String =
        "source=${zipFile.canonicalPath}\nsize=${zipFile.length()}\ncontent=${contentDigest(zipFile)}\n" +
            (expectedSha256?.let { "sha256=$it\n" } ?: "") +
            (publicKey?.let { "signedBy=${VendorSignature.keyId(it)}\n" } ?: "")
}
