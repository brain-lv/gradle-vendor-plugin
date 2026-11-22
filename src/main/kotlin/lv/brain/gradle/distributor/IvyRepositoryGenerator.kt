/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import lv.brain.gradle.core.Templates

import lv.brain.gradle.core.VendorRepositoryLayout
import lv.brain.gradle.distributor.VendorIvyDependencyMapper.ResolvedModule
import java.io.File

object IvyRepositoryGenerator {
    fun generate(
        modules: List<ResolvedModule>,
        vendorRoot: File,
        ivyOutputDir: File,
        version: String,
        bundleGroup: String,
        slimJars: Boolean
    ) {
        val bundleCategories = modules.flatMap { it.categories }.distinct().sorted()
        requireDistinctFolders(modules, bundleCategories, version, bundleGroup)
        modules.forEach { requireSafeJarPath(it, vendorRoot) }

        if (ivyOutputDir.exists()) ivyOutputDir.deleteRecursively()
        ivyOutputDir.mkdirs()

        val compileIds = modules.filter { it.compile }.mapTo(HashSet()) { it.moduleId }

        modules.parallelStream().forEach { module ->
            writeMetadataAndSlimArtifact(module, File(vendorRoot, module.jarRelativePath), ivyOutputDir, slimJars, compileIds)
        }

        modules
            .filter { it.pluginIds.isEmpty() }
            .flatMap { module -> module.categories.map { category -> category to module } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .forEach { (category, items) ->
                writeCategoryBundleMetadata(category, items.sortedBy { it.moduleId }, version, bundleGroup, ivyOutputDir, compileIds)
            }

        val fileNameIdentity = modules.filter { it.identity == VendorJarScanner.IDENTITY_FILE_NAME }
            .map { "${it.groupId}:${it.artifactId}:${it.version}" }.sorted()
        writeLayoutMarker(ivyOutputDir, compileSplit = compileIds.size < modules.size, fileNameIdentity)
        writePluginIndex(ivyOutputDir, modules)
    }

    private fun writePluginIndex(ivyOutputDir: File, modules: List<ResolvedModule>) {
        val index = sortedMapOf<String, String>()
        for (module in modules) {
            for (id in module.pluginIds) {
                val coordinates = "${module.groupId}:${module.artifactId}:${module.version}"
                val previous = index.putIfAbsent(id, coordinates)
                require(previous == null || previous == coordinates) {
                    "The Gradle plugin id '$id' is defined by two vendor jars ($previous and $coordinates); keep only one of them."
                }
            }
        }
        File(ivyOutputDir, VendorRepositoryLayout.PLUGINS_FILE).writeText(
            groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(index)) + "\n"
        )
    }

    private fun writeLayoutMarker(ivyOutputDir: File, compileSplit: Boolean, fileNameIdentity: List<String>) {
        File(ivyOutputDir, VendorRepositoryLayout.LAYOUT_FILE).writeText(
            "# Written by the vendor distributor plugin. compileSplit=true: some jars are only on the runtime classpath.\n" +
                "layoutVersion=${VendorRepositoryLayout.LAYOUT_VERSION}\n" +
                "compileSplit=$compileSplit\n" +
                if (fileNameIdentity.isEmpty()) "" else "fileNameIdentity=${fileNameIdentity.joinToString(",")}\n"
        )
    }

    private fun moduleDir(ivyOutputDir: File, group: String, module: String, version: String): File =
        File(ivyOutputDir, "$group/$module-$version")

    private fun requireSafeCoordinate(kind: String, value: String, owner: String) {
        val bad = listOf("/", "\\", "..", ":").firstOrNull { it in value }
        require(value.isNotBlank() && bad == null) {
            "Vendor module '$owner' has the $kind '$value', which cannot be used in the repository " +
                "(it is empty or contains '${bad ?: ""}'). Give the jar another identity with a vendor.rule.<n>."
        }
    }

    private fun requireDistinctFolders(modules: List<ResolvedModule>, categories: List<String>, version: String, bundleGroup: String) {
        val owners = LinkedHashMap<String, MutableList<String>>()
        for (module in modules) {
            requireSafeCoordinate("group", module.groupId, module.moduleId)
            requireSafeCoordinate("name", module.artifactId, module.moduleId)
            requireSafeCoordinate("version", module.version, module.moduleId)
            owners.getOrPut("${module.groupId}/${module.artifactId}-${module.version}") { ArrayList() }
                .add("${module.groupId}:${module.artifactId}:${module.version}")
        }
        for (category in categories) {
            requireSafeCoordinate("category", category, category)
            owners.getOrPut("$bundleGroup/$category-$version") { ArrayList() }.add("bundle $bundleGroup:$category:$version")
        }
        val clashes = owners.filterValues { it.size > 1 }.mapValues { (_, ids) -> ids.toList() }
        require(clashes.isEmpty()) {
            "Vendor modules share a repository folder (ivy/<group>/<name>-<version>), which would mix their metadata: " +
                clashes.entries.joinToString("; ") { (dir, ids) -> "ivy/$dir: ${ids.joinToString()}" } +
                ". Give one of them another identity with a vendor.rule.<n>."
        }
    }

    private fun requireSafeJarPath(module: ResolvedModule, vendorRoot: File) {
        val path = module.jarRelativePath
        val segments = path.split('/')
        val problem = when {
            path.isEmpty() -> "it is empty"
            '\\' in path -> "it contains a backslash"
            path.startsWith("/") || File(path).isAbsolute -> "it is absolute"
            Regex("^[A-Za-z]:").containsMatchIn(path) || ':' in segments.first() -> "it starts with a drive letter"
            segments.any { it == ".." || it == "." || it.isEmpty() } -> "it contains '..', '.' or an empty segment"
            !File(vendorRoot, path).toPath().normalize().startsWith(vendorRoot.toPath().normalize()) -> "it points outside the vendor folder"
            else -> null
        }
        require(problem == null) { "The jar path '$path' of vendor module '${module.moduleId}' cannot be used: $problem." }
    }

    private fun writeMetadataAndSlimArtifact(
        module: ResolvedModule,
        sourceJar: File,
        ivyOutputDir: File,
        slimJarsEnabled: Boolean,
        compileIds: Set<String>
    ) {
        val moduleDir = moduleDir(ivyOutputDir, module.groupId, module.artifactId, module.version)
        moduleDir.mkdirs()

        val relativeRuntimeUrl = VendorRepositoryLayout.UP_TO_VENDOR_ROOT + module.jarRelativePath

        val runtimeLogicalName = sourceJar.name
        val compileLogicalName: String
        val compileRelativeUrl: String

        if (slimJarsEnabled && module.compile) {
            compileLogicalName = "${module.artifactId}-${module.version}-slim.jar"
            compileRelativeUrl = compileLogicalName
            SlimJarGenerator.generateSlimJar(sourceJar, File(moduleDir, compileLogicalName))
        } else {
            compileLogicalName = runtimeLogicalName
            compileRelativeUrl = relativeRuntimeUrl
        }

        val depsJson = renderDependenciesJson(module.dependsOn.map { Triple(it.groupId, it.artifactId, it.version) })
        val apiDeps = module.apiDependsOn
            ?: if (module.compile) module.dependsOn.filter { it.moduleId in compileIds } else module.dependsOn
        val apiDepsJson = renderDependenciesJson(apiDeps.map { Triple(it.groupId, it.artifactId, it.version) })
        val jvm = jvmAttribute(module.javaVersion)

        File(moduleDir, "${module.artifactId}-${module.version}.module").writeText(
            """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "${json(module.groupId)}",
                "module": "${json(module.artifactId)}",
                "version": "${json(module.version)}"
              },
              "variants": [
                {
                  "name": "apiElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.libraryelements": "jar",
                    "org.gradle.usage": "java-api"$jvm
                  },
                  "files": [
                    { "name": "${json(compileLogicalName)}", "url": "${json(compileRelativeUrl)}" }
                  ],
                  "dependencies": $apiDepsJson
                },
                {
                  "name": "runtimeElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.libraryelements": "jar",
                    "org.gradle.usage": "java-runtime"$jvm
                  },
                  "files": [
                    { "name": "${json(runtimeLogicalName)}", "url": "${json(relativeRuntimeUrl)}" }
                  ],
                  "dependencies": $depsJson
                }
              ]
            }
            """.trimIndent()
        )

        File(moduleDir, "ivy-${module.version}.xml").writeText(
            ivyXml(module.groupId, module.artifactId, module.version, module.dependsOn.map { Triple(it.groupId, it.artifactId, it.version) })
        )
    }

    private fun writeCategoryBundleMetadata(
        category: String,
        items: List<ResolvedModule>,
        version: String,
        bundleGroup: String,
        ivyOutputDir: File,
        compileIds: Set<String>
    ) {
        val moduleDir = moduleDir(ivyOutputDir, bundleGroup, category, version)
        moduleDir.mkdirs()

        val depsJson = renderDependenciesJson(items.map { Triple(it.groupId, it.artifactId, it.version) })
        val apiDepsJson = renderDependenciesJson(
            items.filter { it.moduleId in compileIds }.map { Triple(it.groupId, it.artifactId, it.version) }
        )

        File(moduleDir, "$category-$version.module").writeText(
            """
            {
              "formatVersion": "1.1",
              "component": {
                "group": "${json(bundleGroup)}",
                "module": "${json(category)}",
                "version": "${json(version)}"
              },
              "variants": [
                {
                  "name": "apiElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.usage": "java-api"
                  },
                  "dependencies": $apiDepsJson
                },
                {
                  "name": "runtimeElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.usage": "java-runtime"
                  },
                  "dependencies": $depsJson
                }
              ]
            }
            """.trimIndent()
        )

        File(moduleDir, "ivy-$version.xml").writeText(
            ivyXml(bundleGroup, category, version, items.map { Triple(it.groupId, it.artifactId, it.version) })
        )
    }

    private fun ivyXml(organisation: String, module: String, revision: String, dependencies: List<Triple<String, String, String>>): String =
        Templates.render(
            "ivy.xml.vm",
            mapOf(
                "organisation" to organisation,
                "module" to module,
                "revision" to revision,
                "dependencies" to dependencies.map { (group, name, version) -> mapOf("group" to group, "module" to name, "version" to version) }
            )
        )

    private fun renderDependenciesJson(deps: List<Triple<String, String, String>>): String {
        if (deps.isEmpty()) return "[]"
        val entries = deps.joinToString(",\n") { (group, module, version) ->
            """                        { "group": "${json(group)}", "module": "${json(module)}", "version": { "requires": "${json(version)}" } }"""
        }
        return "[\n$entries\n                  ]"
    }

    internal fun jvmAttribute(javaVersion: Int?): String =
        if (javaVersion == null) "" else ",\n                    \"org.gradle.jvm.version\": $javaVersion"

    private fun json(value: String): String = buildString {
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}
