/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonOutput
import lv.brain.gradle.core.LicensePolicy
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The `generateVendorSbom` task: writes a CycloneDX software bill of materials of the distribution.
 *
 * It lists every vendor jar with its coordinates, SHA-256, licenses and dependencies, and is packaged into the
 * distribution as `vendor-sbom.cdx.json`.
 */
@CacheableTask
abstract class GenerateVendorSbomTask : DefaultTask() {
    /** The module graph written by `resolveVendorModules`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyGraphFile: RegularFileProperty

    /** The version of the distribution. */
    @get:Input
    abstract val versionString: Property<String>

    /** The base group of the distribution. */
    @get:Input
    abstract val defaultGroup: Property<String>

    /** The bill of materials, by default `build/vendor/vendor-sbom.cdx.json`. */
    @get:OutputFile
    abstract val sbomFile: RegularFileProperty

    /** Writes [sbomFile]. */
    @TaskAction
    fun generate() {
        val modules = VendorIvyDependencyMapper.loadGraph(dependencyGraphFile.get().asFile)
        val out = sbomFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(VendorSbom.render(modules, defaultGroup.get(), versionString.get()))) + "\n")
    }
}

internal object VendorSbom {
    const val FILE_NAME = "vendor-sbom.cdx.json"

    private val MAVEN_IDENTITIES = setOf(
        VendorJarScanner.IDENTITY_POM_PROPERTIES, VendorJarScanner.IDENTITY_POM_XML,
        VendorJarScanner.IDENTITY_MANIFEST, VendorJarScanner.IDENTITY_RULE
    )

    fun ref(module: VendorIvyDependencyMapper.ResolvedModule) = "${module.groupId}:${module.artifactId}:${module.version}"

    fun render(modules: List<VendorIvyDependencyMapper.ResolvedModule>, defaultGroup: String, version: String): Map<String, Any> {
        val sorted = modules.sortedBy { ref(it) }
        val components = sorted.map { module ->
            val component = linkedMapOf<String, Any>(
                "type" to "library",
                "bom-ref" to ref(module),
                "group" to module.groupId,
                "name" to module.artifactId,
                "version" to module.version
            )
            module.sha256?.let { component["hashes"] = listOf(mapOf("alg" to "SHA-256", "content" to it)) }
            if (module.licenses.isNotEmpty()) {
                component["licenses"] = module.licenses.map { name ->
                    val id = LicensePolicy.normalize(name)
                    mapOf("license" to if (id in LicensePolicy.SPDX_IDS) mapOf("id" to id) else mapOf("name" to name))
                }
            }
            if (module.identity in MAVEN_IDENTITIES) component["purl"] = "pkg:maven/${module.groupId}/${module.artifactId}@${module.version}"
            component["properties"] = listOfNotNull(
                mapOf("name" to "lv.brain.vendor:jar", "value" to module.jarRelativePath),
                mapOf("name" to "lv.brain.vendor:identity", "value" to module.identity),
                mapOf("name" to "lv.brain.vendor:origin", "value" to module.origin),
                mapOf("name" to "lv.brain.vendor:categories", "value" to module.categories.joinToString(",")),
                module.javaVersion?.let { mapOf("name" to "lv.brain.vendor:javaVersion", "value" to it.toString()) }
            )
            component
        }
        val byId = modules.associateBy { it.moduleId }
        val dependencies = sorted.map { module ->
            mapOf("ref" to ref(module), "dependsOn" to module.dependsOn.mapNotNull { byId[it.moduleId]?.let(::ref) }.sorted())
        }
        return linkedMapOf(
            "bomFormat" to "CycloneDX",
            "specVersion" to "1.5",
            "version" to 1,
            "metadata" to mapOf(
                "tools" to mapOf("components" to listOf(mapOf("type" to "application", "name" to "lv.brain.vendor-distributor"))),
                "component" to mapOf(
                    "type" to "library", "bom-ref" to "$defaultGroup.dist:vendor-sdk:$version",
                    "group" to "$defaultGroup.dist", "name" to "vendor-sdk", "version" to version
                )
            ),
            "components" to components,
            "dependencies" to dependencies
        )
    }
}
