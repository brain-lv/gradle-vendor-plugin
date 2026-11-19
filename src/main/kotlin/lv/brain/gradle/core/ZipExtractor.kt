/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
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

    fun isCurrent(zipFile: File, extractDir: File): Boolean {
        val marker = File(extractDir, READY_MARKER)
        return marker.isFile && marker.readText() == fingerprint(zipFile)
    }

    fun extractIfChanged(zipFile: File, extractDir: File) {
        if (isCurrent(zipFile, extractDir)) return

        extractDir.parentFile.mkdirs()
        val lockFile = File(extractDir.parentFile, "${extractDir.name}.lock")

        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                if (isCurrent(zipFile, extractDir)) return

                val stagingDir = File(extractDir.parentFile, "${extractDir.name}.staging")
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                try {
                    ZipInputStream(zipFile.inputStream().buffered(128 * 1024)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(stagingDir, entry.name)
                            if (!outFile.canonicalPath.startsWith(stagingDir.canonicalPath + File.separator)) {
                                error("Illegal ZIP entry outside target directory: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile.mkdirs()
                                outFile.outputStream().buffered(64 * 1024).use { zis.copyTo(it) }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }

                    File(stagingDir, READY_MARKER).writeText(fingerprint(zipFile))
                    if (extractDir.exists()) extractDir.deleteRecursively()
                    Files.move(stagingDir.toPath(), extractDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                }
            }
        }
    }

    private fun fingerprint(zipFile: File): String =
        "source=${zipFile.canonicalPath}\nsize=${zipFile.length()}\nmodified=${zipFile.lastModified()}\n"
}
