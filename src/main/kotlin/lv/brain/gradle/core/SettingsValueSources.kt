/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

internal abstract class GitExcludeSource : ValueSource<String, GitExcludeSource.Params> {
    interface Params : ValueSourceParameters {
        val projectDir: Property<String>
    }

    override fun obtain(): String {
        GitExcludeManager.ensureExcluded(File(parameters.projectDir.get()))
        return DONE
    }

    companion object {
        private const val DONE = "done"

        fun ensure(settings: Settings) {
            settings.providers.of(GitExcludeSource::class.java) {
                parameters.projectDir.set(settings.rootDir.absolutePath)
            }.get()
        }
    }
}

internal abstract class VendorUnpackSource : ValueSource<String, VendorUnpackSource.Params> {
    interface Params : ValueSourceParameters {
        val vendorRoot: Property<String>
        val zip: Property<String>
        val sha256: Property<String>
        val signature: Property<String>
        val publicKey: Property<String>
    }

    override fun obtain(): String {
        val root = File(parameters.vendorRoot.get())
        val zipPath = parameters.zip.orNull
        if (zipPath != null) {
            val zip = File(zipPath)
            if (!zip.isFile) return "missing $zipPath"
            ZipExtractor.extractIfChanged(
                zip, root, parameters.sha256.orNull, parameters.signature.orNull?.let(::File), parameters.publicKey.orNull
            )
            requireLayout(root)
            return "$READY ${ZipExtractor.unpackedFingerprint(root)}"
        } else if (!ZipExtractor.isUnpacked(root)) {
            return ABSENT
        }
        requireLayout(root)
        return READY
    }

    private fun requireLayout(root: File) {
        val ivyDir = File(root, VendorRepositoryLayout.IVY_DIR)
        require(ivyDir.isDirectory) { "Expected Ivy repository directory not found at: ${ivyDir.absolutePath}" }
        VendorLayout.requireCurrent(root)
    }

    companion object {
        private const val READY = "ready"
        private const val ABSENT = "absent"

        fun isReady(settings: Settings, vendorRoot: File): Boolean = obtain(settings, vendorRoot, null, null, null, null) == READY

        fun ensure(settings: Settings, vendorRoot: File, zip: File, sha256: String? = null, signature: File? = null, publicKey: String? = null) {
            obtain(settings, vendorRoot, zip, sha256, signature, publicKey)
        }

        private fun obtain(settings: Settings, vendorRoot: File, zip: File?, sha256: String?, signature: File?, publicKey: String?): String =
            settings.providers.of(VendorUnpackSource::class.java) {
                parameters.vendorRoot.set(vendorRoot.absolutePath)
                if (zip != null) parameters.zip.set(zip.absolutePath)
                if (sha256 != null) parameters.sha256.set(sha256)
                if (signature != null) parameters.signature.set(signature.absolutePath)
                if (publicKey != null) parameters.publicKey.set(publicKey)
            }.get()
    }
}
