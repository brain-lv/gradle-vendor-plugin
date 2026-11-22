/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonOutput
import java.io.File
import lv.brain.gradle.core.CategoryRule
import lv.brain.gradle.core.ClassExcludes
import lv.brain.gradle.core.JarExcludes
import lv.brain.gradle.core.LicensePolicy
import org.gradle.api.logging.Logger

internal class ModuleGraphReport(private val logger: Logger) {
    fun selection(rules: List<CategoryRule>, selection: JarSelection, fallbackCategory: String) {
        val perCategory = selection.jars.groupingBy { it.category }.eachCount()
        val summary = perCategory.entries.joinToString(", ") { "${it.key}=${it.value}" }
        logger.lifecycle("Vendor jars: {} selected ({}), {} skipped", selection.jars.size, summary, selection.skipped.size)

        rules.filter { it.category !in perCategory }.forEach { rule ->
            logger.warn(
                "Vendor category '{}' matched no jars. Patterns are relative to the vendor directory: {}",
                rule.category,
                rule.patterns.joinToString()
            )
        }
        if (rules.isNotEmpty() && selection.defaulted.isNotEmpty()) {
            logger.warn(
                "Vendor: {} jar(s) match no category include and went to the default category '{}' because " +
                    "vendor.defaultCategory is set. Remove vendor.defaultCategory to leave them out, or exclude folders " +
                    "with vendor.scanExcludes. Run with --info to list them.",
                selection.defaulted.size, fallbackCategory
            )
            selection.defaulted.forEach { logger.info("  default category: {}", it) }
        }
        if (selection.skipped.isNotEmpty()) {
            logger.lifecycle(
                "{} jar(s) match no category include and are left out (add an include for them, " +
                    "or set vendor.defaultCategory to keep them). Run with --info to list them.",
                selection.skipped.size
            )
            selection.skipped.forEach { logger.info("  skipped: {}", it) }
        }
    }

    fun excludes(classExcludes: ClassExcludes, jarExcludes: JarExcludes, ignoredJars: Set<String>) {
        if (ignoredJars.isNotEmpty()) {
            logger.lifecycle(
                "Vendor dependency check: {} jar(s) are left out by vendor.dependencyExcludeJars ({})",
                ignoredJars.size, ignoredJars.joinToString()
            )
        }
        classExcludes.unmatched().takeIf { it.isNotEmpty() }?.let {
            logger.warn("vendor.dependencyExcludeClasses: no class in the selected jars matches {}", it.joinToString())
        }
        jarExcludes.unmatched().takeIf { it.isNotEmpty() }?.let {
            logger.warn(
                "vendor.dependencyExcludeJars: no selected jar matches {} (the path is relative to the vendor directory)",
                it.joinToString()
            )
        }
    }

    fun redundantEdges(reduction: ReducedGraph, edges: Map<String, Set<String>>) {
        if (reduction.redundant.isEmpty()) return
        logger.lifecycle(
            "Vendor dependency tree: {} of {} direct dependencies are left out because another dependency of the same jar " +
                "already brings them in (--info lists them)",
            reduction.redundant.size, edges.values.sumOf { it.size }
        )
        reduction.redundant.forEach { logger.info("  {} -> {} is covered by {}", it.from, it.to, it.via) }
    }

    fun brokenCycles(cycles: List<BrokenCycle>, evidence: EdgeEvidence) {
        val shown = if (logger.isInfoEnabled) EdgeEvidence.MAX_EXAMPLES else 3
        for (cycle in cycles) {
            val message = StringBuilder()
            message.append("Breaking circular vendor JAR dependency: ${cycle.removedFrom} -> ${cycle.removedTo}\n")
            message.append("  cycle: ${cycle.path.joinToString(" -> ")}\n")
            for ((from, to) in cycle.edges) {
                val removed = from == cycle.removedFrom && to == cycle.removedTo
                message.append("  $from -> $to${if (removed) "   (removed from the module metadata)" else ""}\n")
                val examples = evidence.of(from, to)
                if (examples.isEmpty()) message.append("      (no recorded reason)\n")
                examples.take(shown).forEach { message.append("      $it\n") }
                if (examples.size > shown) message.append("      ... more examples with --info\n")
            }
            message.append("  All jars are still packaged; only this dependency declaration is dropped.")
            logger.warn(message.toString())
        }
    }
}

internal object ModuleGraphJson {
    fun write(file: File, payload: Map<String, Any?>) {
        file.parentFile.mkdirs()
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(payload)))
    }

    fun empty(skippedJarCount: Int, compilePackages: List<String>): Map<String, Any?> = mapOf(
        "schemaVersion" to 1,
        "moduleCount" to 0,
        "skippedJarCount" to skippedJarCount,
        "topologicalOrder" to emptyList<String>(),
        "compileSplit" to false,
        "compilePackages" to compilePackages,
        "modules" to emptyList<Any>(),
        "redundantEdges" to emptyList<Any>(),
        "brokenCycles" to emptyList<Any>()
    )

    class Graph(
        val order: List<String>,
        val metaById: Map<String, JarMetadata>,
        val dependsOn: Map<String, Set<String>>,
        val apiDependsOn: Map<String, Set<String>>?,
        val compileReasons: Map<String, String>?
    )

    fun modules(
        result: Graph,
        builder: ModuleGraphBuilder,
        rootDir: File,
        referencesOf: (JarMetadata) -> RawReferences
    ): List<Map<String, Any?>> = result.order.mapNotNull { id ->
        val metaById = result.metaById
        val compileReasons = result.compileReasons
        val evidence = builder.evidence
        val meta = metaById[id] ?: return@mapNotNull null
        fun ref(depId: String): Map<String, String>? = metaById[depId]?.let { dep ->
            mapOf("moduleId" to dep.moduleId, "groupId" to dep.groupId, "artifactId" to dep.artifactId, "version" to dep.version)
        }
        val raw = referencesOf(meta)
        mapOf(
            "moduleId" to meta.moduleId,
            "groupId" to meta.groupId,
            "artifactId" to meta.artifactId,
            "version" to meta.version,
            "jar" to meta.file.relativeTo(rootDir).invariantSeparatorsPath,
            "category" to meta.categories.first(),
            "categories" to meta.categories,
            "resolutionSource" to (builder.resolutionSources[id] ?: "none"),
            "compile" to (compileReasons == null || id in compileReasons),
            "compileReason" to compileReasons?.get(id),
            "mavenCoord" to meta.mavenCoord,
            "identity" to meta.identity,
            "origin" to meta.origin,
            "sha256" to raw.sha256,
            "javaVersion" to raw.javaVersion,
            "licenses" to meta.licenses,
            "licenseIds" to meta.licenses.map { LicensePolicy.normalize(it) }.distinct(),
            "moduleName" to meta.moduleName,
            "moduleKind" to meta.moduleKind,
            "pluginIds" to meta.pluginIds,
            "dependsOn" to result.dependsOn[id]?.toList().orEmpty().mapNotNull { dep -> ref(dep)?.plus("evidence" to evidence.of(id, dep)) },
            "apiDependsOn" to result.apiDependsOn?.get(id)?.toList()?.mapNotNull(::ref)
        )
    }

    fun sharedClasses(groups: List<SharedClassGroup>): List<Map<String, Any>> = groups.map { group ->
        mapOf(
            "jars" to group.jars,
            "classCount" to group.classes.size,
            "examples" to group.classes.take(EdgeEvidence.MAX_EXAMPLES).map { it.replace('/', '.') }
        )
    }

    fun brokenCycles(cycles: List<BrokenCycle>, evidence: EdgeEvidence): List<Map<String, Any>> = cycles.map { cycle ->
        mapOf(
            "removedEdge" to mapOf("from" to cycle.removedFrom, "to" to cycle.removedTo),
            "cycle" to cycle.path,
            "edges" to cycle.edges.map { (from, to) ->
                mapOf(
                    "from" to from,
                    "to" to to,
                    "removed" to (from == cycle.removedFrom && to == cycle.removedTo),
                    "evidence" to evidence.of(from, to)
                )
            }
        )
    }
}
