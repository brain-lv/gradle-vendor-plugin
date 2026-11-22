/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import lv.brain.gradle.core.VendorSignature
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Files

/**
 * The `generateVendorSigningKey` task: creates a new Ed25519 key pair for signing the distribution.
 *
 * Writes `vendor-signing.key` (keep it secret: point `vendor.signing.key` at it, or put its content into the
 * environment variable `VENDOR_SIGNING_KEY`) and `vendor-signing.pub` (give it to consumers as
 * `vendor.signing.publicKey`). It never overwrites an existing key.
 */
@DisableCachingByDefault(because = "Creates a new secret every time")
abstract class GenerateVendorSigningKeyTask : DefaultTask() {
    /** The folder for the two key files, by default `build/vendor-signing`. */
    @get:Internal
    abstract val outputDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    /** Writes the key pair. */
    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.apply { mkdirs() }
        val privateFile = File(dir, "vendor-signing.key")
        val publicFile = File(dir, "vendor-signing.pub")
        if (privateFile.exists()) {
            throw GradleException("$privateFile already exists; move it away first, a new key would make every earlier signature unverifiable.")
        }
        val (privatePem, publicPem) = VendorSignature.generate()
        writeOwnerOnly(privateFile, privatePem)
        publicFile.writeText(publicPem)
        val oneLine = VendorSignature.der(publicPem).let { java.util.Base64.getEncoder().encodeToString(it) }
        logger.lifecycle(
            "Vendor signing key written to {} (keep it secret) and {}.\nDistributor: vendor.signing.key={}\nConsumers: vendor.signing.publicKey={}",
            privateFile, publicFile, privateFile, oneLine
        )
    }

    private fun writeOwnerOnly(file: File, text: String) {
        val path = file.toPath()
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(path)
            val restricted = file.setReadable(false, false) && file.setReadable(true, true)
            if (!restricted) logger.warn("Could not make {} readable only by you; restrict its access by hand.", file)
        }
        Files.writeString(path, text)
    }
}
