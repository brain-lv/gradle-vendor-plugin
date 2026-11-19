/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.core

import org.gradle.api.artifacts.dsl.RepositoryHandler
import java.io.File
import java.net.URI

internal object VendorRepositoryLayout {
    const val IVY_DIR = "ivy"

    const val LAYOUT_FILE = "vendor-layout.properties"

    const val IVY_PATTERN = "$IVY_DIR/[organisation]/[module]/[revision]/ivy-[revision].xml"
    const val ARTIFACT_PATTERN = "$IVY_DIR/[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]"

    fun rootUri(vendorRoot: File): URI = URI(vendorRoot.toURI().toString().trimEnd('/') + "/")

    fun RepositoryHandler.addVendorIvyRepository(repositoryName: String, vendorRoot: File) {
        ivy {
            name = repositoryName
            url = rootUri(vendorRoot)
            patternLayout {
                ivy(IVY_PATTERN)
                artifact(ARTIFACT_PATTERN)
            }
            metadataSources {
                gradleMetadata()
                ivyDescriptor()
            }
        }
    }

    fun moduleCoordinates(vendorRoot: File): Set<String> {
        val result = LinkedHashSet<String>()
        File(vendorRoot, IVY_DIR).listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { group ->
            group.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { artifact ->
                result.add("${group.name}:${artifact.name}")
            }
        }
        return result
    }
}

internal data class VendorLayout(
    val compileSplit: Boolean
) {
    companion object {
        fun read(vendorRoot: File): VendorLayout {
            val file = File(File(vendorRoot, VendorRepositoryLayout.IVY_DIR), VendorRepositoryLayout.LAYOUT_FILE)
            if (!file.isFile) return VendorLayout(compileSplit = false)
            val props = java.util.Properties().apply { file.inputStream().use { load(it) } }
            return VendorLayout(compileSplit = props.getProperty("compileSplit")?.trim().equals("true", ignoreCase = true))
        }
    }
}
