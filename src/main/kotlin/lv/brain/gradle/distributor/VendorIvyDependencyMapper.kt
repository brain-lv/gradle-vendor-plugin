/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File

object VendorIvyDependencyMapper {
    data class ResolvedDep(
        val moduleId: String,
        val groupId: String,
        val artifactId: String,
        val version: String
    )

    data class ResolvedModule(
        val moduleId: String,
        val groupId: String,
        val artifactId: String,
        val version: String,
        val jarRelativePath: String,
        val resolutionSource: String,
        val dependsOn: List<ResolvedDep>,
        val apiDependsOn: List<ResolvedDep>? = null,
        val categories: List<String> = emptyList(),
        val compile: Boolean = true,
        val compileReason: String? = null
    )

    @Suppress("UNCHECKED_CAST")
    fun loadGraph(graphJsonFile: File): List<ResolvedModule> {
        if (!graphJsonFile.exists()) return emptyList()
        val parsed = JsonSlurper().parse(graphJsonFile) as? Map<String, Any?> ?: return emptyList()
        val rawModules = parsed["modules"] as? List<Map<String, Any?>> ?: return emptyList()

        return rawModules.map { mod ->
            val rawDeps = mod["dependsOn"] as? List<Map<String, Any?>> ?: emptyList()
            val rawApiDeps = mod["apiDependsOn"] as? List<Map<String, Any?>>
            fun dep(d: Map<String, Any?>) = ResolvedDep(
                moduleId = d["moduleId"] as String,
                groupId = d["groupId"] as String,
                artifactId = d["artifactId"] as String,
                version = d["version"] as String
            )
            ResolvedModule(
                moduleId = mod["moduleId"] as String,
                groupId = mod["groupId"] as String,
                artifactId = mod["artifactId"] as String,
                version = mod["version"] as String,
                jarRelativePath = mod["jar"] as String,
                resolutionSource = (mod["resolutionSource"] as? String) ?: "unknown",
                compile = (mod["compile"] as? Boolean) ?: true,
                compileReason = mod["compileReason"] as? String,
                categories = (mod["categories"] as? List<String>)?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(mod["category"] as? String),
                dependsOn = rawDeps.map(::dep),
                apiDependsOn = rawApiDeps?.map(::dep)
            )
        }
    }

    fun renderIvyDependenciesBlock(module: ResolvedModule): String {
        if (module.dependsOn.isEmpty()) return ""
        val lines = module.dependsOn.joinToString("\n") { dep ->
            """        <dependency org="${escapeXml(dep.groupId)}" name="${escapeXml(dep.artifactId)}" rev="${escapeXml(dep.version)}" conf="compile->compile;runtime->runtime"/>"""
        }
        return """    <dependencies>
$lines
    </dependencies>"""
    }

    fun renderGmmDependenciesList(module: ResolvedModule): List<Map<String, Any>> {
        return module.dependsOn.map { dep ->
            mapOf(
                "group" to dep.groupId,
                "module" to dep.artifactId,
                "version" to mapOf("requires" to dep.version)
            )
        }
    }

    fun renderGmmDependenciesJson(module: ResolvedModule): String {
        return JsonOutput.prettyPrint(JsonOutput.toJson(renderGmmDependenciesList(module)))
    }

    internal fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
