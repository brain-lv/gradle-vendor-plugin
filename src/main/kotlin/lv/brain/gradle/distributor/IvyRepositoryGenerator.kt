/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

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
        if (ivyOutputDir.exists()) ivyOutputDir.deleteRecursively()
        ivyOutputDir.mkdirs()

        val compileIds = modules.filter { it.compile }.mapTo(HashSet()) { it.moduleId }

        modules.parallelStream().forEach { module ->
            writeMetadataAndSlimArtifact(module, File(vendorRoot, module.jarRelativePath), ivyOutputDir, slimJars, compileIds)
        }

        modules
            .flatMap { module -> module.categories.map { category -> category to module } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .forEach { (category, items) ->
                writeCategoryBundleMetadata(category, items.sortedBy { it.moduleId }, version, bundleGroup, ivyOutputDir, compileIds)
            }

        writeLayoutMarker(ivyOutputDir, compileSplit = compileIds.size < modules.size)
    }

    private fun writeLayoutMarker(ivyOutputDir: File, compileSplit: Boolean) {
        File(ivyOutputDir, VendorRepositoryLayout.LAYOUT_FILE).writeText(
            "# Written by the vendor distributor plugin. compileSplit=true: some jars are only on the runtime classpath.\n" +
                "compileSplit=$compileSplit\n"
        )
    }

    private fun writeMetadataAndSlimArtifact(
        module: ResolvedModule,
        sourceJar: File,
        ivyOutputDir: File,
        slimJarsEnabled: Boolean,
        compileIds: Set<String>
    ) {
        val moduleDir = File(ivyOutputDir, "${module.groupId}/${module.artifactId}/${module.version}")
        moduleDir.mkdirs()

        val relativeRuntimeUrl = moduleDir.toPath()
            .relativize(sourceJar.toPath())
            .toString()
            .replace('\\', '/')

        val runtimeLogicalName = "${module.artifactId}-${module.version}.jar"
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
                    "org.gradle.usage": "java-api"
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
                    "org.gradle.usage": "java-runtime"
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

        val depsBlock = VendorIvyDependencyMapper.renderIvyDependenciesBlock(module)
        File(moduleDir, "ivy-${module.version}.xml").writeText(
            buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("<!-- do_not_remove: published-with-gradle-metadata -->")
                appendLine("""<ivy-module version="2.0">""")
                appendLine(
                    """    <info organisation="${xml(module.groupId)}" module="${xml(module.artifactId)}" """ +
                        """revision="${xml(module.version)}" status="release"/>"""
                )
                appendLine("    <configurations>")
                appendLine("""        <conf name="default" visibility="public" extends="runtime"/>""")
                appendLine("""        <conf name="runtime" visibility="public"/>""")
                appendLine("""        <conf name="compile" visibility="public"/>""")
                appendLine("    </configurations>")
                appendLine("    <publications/>")
                if (depsBlock.isNotEmpty()) appendLine(depsBlock)
                appendLine("</ivy-module>")
            }
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
        val moduleDir = File(ivyOutputDir, "$bundleGroup/$category/$version")
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
            buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("<!-- do_not_remove: published-with-gradle-metadata -->")
                appendLine("""<ivy-module version="2.0">""")
                appendLine(
                    """    <info organisation="${xml(bundleGroup)}" module="${xml(category)}" """ +
                        """revision="${xml(version)}" status="release"/>"""
                )
                appendLine("    <configurations>")
                appendLine("""        <conf name="default" visibility="public" extends="runtime"/>""")
                appendLine("""        <conf name="runtime" visibility="public"/>""")
                appendLine("""        <conf name="compile" visibility="public"/>""")
                appendLine("    </configurations>")
                appendLine("    <publications/>")
                appendLine("    <dependencies>")
                for (item in items) {
                    appendLine(
                        """        <dependency org="${xml(item.groupId)}" name="${xml(item.artifactId)}" """ +
                            """rev="${xml(item.version)}" conf="compile->compile;runtime->runtime"/>"""
                    )
                }
                appendLine("    </dependencies>")
                appendLine("</ivy-module>")
            }
        )
    }

    private fun renderDependenciesJson(deps: List<Triple<String, String, String>>): String {
        if (deps.isEmpty()) return "[]"
        val entries = deps.joinToString(",\n") { (group, module, version) ->
            """                        { "group": "${json(group)}", "module": "${json(module)}", "version": { "requires": "${json(version)}" } }"""
        }
        return "[\n$entries\n                  ]"
    }

    private fun xml(value: String): String = VendorIvyDependencyMapper.escapeXml(value)

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
