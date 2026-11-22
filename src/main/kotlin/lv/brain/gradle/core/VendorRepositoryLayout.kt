/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import org.gradle.api.GradleException
import org.gradle.api.artifacts.dsl.RepositoryHandler
import java.io.File
import java.net.URI
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object VendorRepositoryLayout {
    const val IVY_DIR = "ivy"

    const val LAYOUT_FILE = "vendor-layout.properties"

    const val PLUGINS_FILE = "vendor-plugins.json"

    const val LAYOUT_VERSION = 2

    const val IVY_PATTERN = "$IVY_DIR/[organisation]/[module]-[revision]/ivy-[revision].xml"
    const val ARTIFACT_PATTERN = "$IVY_DIR/[organisation]/[module]-[revision]/[artifact]-[revision](-[classifier]).[ext]"

    const val UP_TO_VENDOR_ROOT = "../../../"

    fun moduleDirPath(group: String, module: String, version: String): String = "$IVY_DIR/$group/$module-$version"

    fun rootUri(vendorRoot: File): URI = URI(vendorRoot.toURI().toString().trimEnd('/') + "/")

    fun RepositoryHandler.addVendorIvyRepository(repositoryName: String, vendorRoot: File, onlyModules: List<String>? = null) {
        ivy {
            name = repositoryName
            url = rootUri(vendorRoot)
            if (onlyModules != null) {
                content {
                    onlyModules.forEach { coordinate -> includeModule(coordinate.substringBefore(':'), coordinate.substringAfter(':')) }
                }
            }
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

    @Suppress("UNCHECKED_CAST")
    fun plugins(vendorRoot: File): Map<String, String> {
        val file = File(File(vendorRoot, IVY_DIR), PLUGINS_FILE)
        if (!file.isFile) return emptyMap()
        return (groovy.json.JsonSlurper().parse(file) as? Map<String, Any?>).orEmpty()
            .mapNotNull { (id, coordinates) -> (coordinates as? String)?.let { id to it } }
            .toMap(sortedMapOf())
    }

    fun moduleCoordinates(vendorRoot: File): Set<String> =
        modules(vendorRoot).mapTo(LinkedHashSet()) { "${it.group}:${it.artifact}" }

    fun modules(vendorRoot: File): List<VendorModuleRef> {
        fun dirs(parent: File): List<File> = parent.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        val builder = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        val result = ArrayList<VendorModuleRef>()
        for (group in dirs(File(vendorRoot, IVY_DIR))) {
            for (moduleDir in dirs(group)) {
                val descriptor = moduleDir.listFiles { f -> f.isFile && f.name.startsWith("ivy-") && f.name.endsWith(".xml") }
                    ?.minByOrNull { it.name } ?: continue
                val info = runCatching {
                    builder.parse(descriptor).documentElement.getElementsByTagName("info").item(0) as? org.w3c.dom.Element
                }.getOrNull() ?: continue
                val org = info.getAttribute("organisation")
                val name = info.getAttribute("module")
                val rev = info.getAttribute("revision")
                if (org.isNotEmpty() && name.isNotEmpty() && rev.isNotEmpty()) result.add(VendorModuleRef(org, name, rev))
            }
        }
        return result.sortedWith(compareBy({ it.group }, { it.artifact }, { it.version }))
    }
}

internal data class VendorLayout(
    val compileSplit: Boolean,
    val layoutVersion: Int? = null,
    val fileNameIdentity: List<String> = emptyList()
) {
    companion object {
        fun file(vendorRoot: File): File = File(File(vendorRoot, VendorRepositoryLayout.IVY_DIR), VendorRepositoryLayout.LAYOUT_FILE)

        fun read(vendorRoot: File): VendorLayout {
            val file = file(vendorRoot)
            if (!file.isFile) return VendorLayout(compileSplit = false)
            val props = Properties().apply { file.inputStream().use { load(it) } }
            return VendorLayout(
                compileSplit = props.getProperty("compileSplit")?.trim().equals("true", ignoreCase = true),
                layoutVersion = props.getProperty("layoutVersion")?.trim()?.toIntOrNull(),
                fileNameIdentity = props.getProperty("fileNameIdentity").orEmpty().split(',').map { it.trim() }.filter { it.count { c -> c == ':' } == 2 }
            )
        }

        fun requireCurrent(vendorRoot: File) {
            val found = read(vendorRoot).layoutVersion
            if (found == VendorRepositoryLayout.LAYOUT_VERSION) return
            val what = if (found == null) "an older distributor" else "a distributor with repository layout $found"
            throw GradleException(
                "The vendor distribution in $vendorRoot was built by $what; this plugin needs repository layout " +
                    "${VendorRepositoryLayout.LAYOUT_VERSION}. Rebuild the distribution with the new distributor " +
                    "(the same plugin version as the consumer, task packageVendorDistribution) and publish it again."
            )
        }
    }
}
