/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import lv.brain.gradle.core.Templates
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
 * The `vendorReport` task: writes an HTML page of the module graph, by default `build/reports/vendor/index.html`.
 *
 * The page lists every vendor jar with its coordinates, origin, licence, Java version and compile classpath decision,
 * why each of its dependencies exists, which jars use it, and everything that needs attention: broken cycles, missing
 * classes, jars with the default group, module path problems, licences and pom check findings. It opens without a
 * network connection.
 */
@CacheableTask
abstract class GenerateVendorReportTask : DefaultTask() {
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

    /** The report page. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /** Writes [reportFile]. */
    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val graph = JsonSlurper().parse(dependencyGraphFile.get().asFile) as Map<String, Any?>
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(VendorReport.render(graph, versionString.get(), defaultGroup.get()))
        logger.lifecycle("Vendor report: {}", out.toURI())
    }
}

internal class ReportPart(val text: String, val anchor: String? = null, val muted: Boolean = false)

internal class ReportCell(val parts: List<ReportPart>, val separator: String = ", ")

internal class ReportTable(val head: List<String>, val rows: List<List<ReportCell>>)

internal class ReportCycleEdge(val from: String, val to: String, val removed: Boolean, val evidence: List<String>)

internal class ReportCycle(val removedFrom: ReportPart, val removedTo: ReportPart, val path: ReportCell, val edges: List<ReportCycleEdge>)

internal class ReportTopic(val title: String, val count: Int, val table: ReportTable? = null, val cycles: List<ReportCycle> = emptyList(), val links: ReportCell? = null)

internal class ReportStat(val value: String, val label: String)

internal class ReportDependency(val link: ReportPart, val evidence: List<String>)

internal class ReportRedundant(val to: ReportPart, val via: ReportPart)

internal class ReportJar(
    val id: String,
    val anchor: String,
    val coordinates: String,
    val categories: List<String>,
    val compileTag: String?,
    val flags: List<String>,
    val search: String,
    val compile: Boolean,
    val details: ReportTable,
    val compileReason: String?,
    val pluginIds: List<String>,
    val dependencies: List<ReportDependency>,
    val compileDependencies: ReportCell?,
    val redundant: List<ReportRedundant>,
    val users: ReportCell,
    val userCount: Int
) {
    val flagged: Boolean get() = flags.isNotEmpty()
}

@Suppress("UNCHECKED_CAST")
internal object VendorReport {
    private fun list(value: Any?): List<Map<String, Any?>> = (value as? List<Map<String, Any?>>).orEmpty()
    private fun strings(value: Any?): List<String> = (value as? List<*>).orEmpty().map { it.toString() }
    private fun map(value: Any?): Map<String, Any?> = (value as? Map<String, Any?>).orEmpty()

    private fun anchor(id: String) = "m-" + id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun text(value: Any?) = ReportCell(listOf(ReportPart(value?.toString().orEmpty())))

    private class GraphView(graph: Map<String, Any?>, defaultGroup: String) {
        val raw = graph
        val modules = list(graph["modules"])
        val byId = modules.associateBy { it["moduleId"].toString() }
        val usedBy: Map<String, List<String>> = HashMap<String, MutableList<String>>().also { users ->
            modules.forEach { m -> list(m["dependsOn"]).forEach { d -> users.getOrPut(d["moduleId"].toString()) { ArrayList() } += m["moduleId"].toString() } }
        }
        val redundant = list(graph["redundantEdges"]).groupBy { it["from"].toString() }
        val compileSplit = graph["compileSplit"] == true
        val defaulted = modules.filter {
            it["identity"] == VendorJarScanner.IDENTITY_FILE_NAME || (it["identity"] == VendorJarScanner.IDENTITY_MANIFEST && it["groupId"] == defaultGroup)
        }
        val cycles = list(graph["brokenCycles"])
        val missing = list(graph["missingClasses"])
        val missingVendor = missing.filter { it["openSourceOnly"] != true }
        val jpms = map(graph["jpms"])
        val categories = modules.flatMap { strings(it["categories"]) }.distinct().sorted()

        fun link(id: String) = ReportPart(id, if (id in byId) anchor(id) else null)

        fun links(ids: List<String>, empty: String? = null): ReportCell =
            if (ids.isEmpty() && empty != null) ReportCell(listOf(ReportPart(empty, muted = true))) else ReportCell(ids.map(::link))
    }

    fun render(graph: Map<String, Any?>, version: String, defaultGroup: String): String {
        val view = GraphView(graph, defaultGroup)
        val attention = attention(view)
        return Templates.render(
            "vendor-report.html.vm",
            mapOf("version" to version, "stats" to stats(view, attention.size), "attention" to attention, "information" to information(view), "jars" to jars(view))
        )
    }

    private fun attention(view: GraphView): List<ReportTopic> = listOf(
        ReportTopic("circular dependencies broken", view.cycles.size, cycles = view.cycles.map { cycle(view, it) }),
        ReportTopic("packages of classes that vendor jars use but no jar has", view.missingVendor.size, ReportTable(
            listOf("Package", "Classes", "Used by"),
            view.missingVendor.map { m -> listOf(text(m["package"]), text("${m["classCount"]}: " + strings(m["classes"]).take(5).joinToString(", ")), view.links(strings(m["usedBy"]))) }
        )),
        ReportTopic("jars with the default group (no pom, manifest group or rule names them)", view.defaulted.size, ReportTable(
            listOf("Module", "Jar", "Coordinates"),
            view.defaulted.map { listOf(view.links(listOf(it["moduleId"].toString())), text(it["jar"]), text("${it["groupId"]}:${it["artifactId"]}:${it["version"]}")) }
        )),
        list(view.jpms["duplicateModuleNames"]).let { names ->
            ReportTopic("module names used by more than one jar", names.size, ReportTable(
                listOf("Module name", "Jars"), names.map { listOf(text(it["moduleName"]), view.links(strings(it["jars"]))) }
            ))
        },
        list(view.jpms["splitPackages"]).filter { it["betweenNamedModules"] == true }.let { split ->
            ReportTopic("packages split between named modules", split.size, ReportTable(
                listOf("Package", "Jars"), split.map { listOf(text(it["package"]), view.links(strings(it["jars"]))) }
            ))
        },
        list(view.raw["pomCheck"]).let { findings ->
            ReportTopic("dependencies the pom.xml does not list (pom check)", findings.size, ReportTable(
                listOf("From", "To", "References", "Example", "Added"),
                findings.map {
                    listOf(view.links(listOf(it["from"].toString())), view.links(listOf(it["to"].toString())), text(it["references"]), text(it["example"]),
                        text(if (it["added"] == true) "yes" else "no"))
                }
            ))
        },
        strings(map(view.raw["licensePolicy"])["unknown"]).let { unknown ->
            ReportTopic("jars without a declared licence", unknown.size, links = view.links(unknown))
        }
    ).filter { it.count > 0 }

    private fun cycle(view: GraphView, c: Map<String, Any?>): ReportCycle {
        val removed = map(c["removedEdge"])
        return ReportCycle(
            view.link(removed["from"].toString()), view.link(removed["to"].toString()),
            ReportCell(strings(c["cycle"]).map(view::link), " &rarr; "),
            list(c["edges"]).map { e -> ReportCycleEdge(e["from"].toString(), e["to"].toString(), e["removed"] == true, strings(e["evidence"]).take(3)) }
        )
    }

    private fun information(view: GraphView): List<ReportTopic> = listOf(
        view.missing.filter { it["openSourceOnly"] == true }.let { optional ->
            ReportTopic("packages only open-source jars use and no jar has (usually optional dependencies)", optional.size, ReportTable(
                listOf("Package", "Classes", "Used by"),
                optional.map { m -> listOf(text(m["package"]), text(m["classCount"]), view.links(strings(m["usedBy"]))) }
            ))
        },
        list(view.raw["sharedClasses"]).let { shared ->
            ReportTopic("groups of jars that define the same classes", shared.size, ReportTable(
                listOf("Jars", "Classes", "Examples"),
                shared.map { g -> listOf(view.links(strings(g["jars"])), text(g["classCount"]), text(strings(g["examples"]).take(3).joinToString(", "))) }
            ))
        }
    ).filter { it.count > 0 }

    private fun jars(view: GraphView): List<ReportJar> =
        view.modules.sortedBy { it["moduleId"].toString().lowercase() }.map { jar(view, it) }

    private fun flags(view: GraphView, m: Map<String, Any?>, id: String): List<String> = listOfNotNull(
        "default group".takeIf { m in view.defaulted },
        "missing classes".takeIf { view.missingVendor.any { id in strings(it["usedBy"]) } },
        "cycle".takeIf { view.cycles.any { id in strings(it["cycle"]) } }
    )

    private fun jar(view: GraphView, m: Map<String, Any?>): ReportJar {
        val id = m["moduleId"].toString()
        val licences = strings(m["licenseIds"]).ifEmpty { strings(m["licenses"]) }
        val compile = m["compile"] == true
        return ReportJar(
            id = id,
            anchor = anchor(id),
            coordinates = "${m["groupId"]}:${m["artifactId"]}:${m["version"]}",
            categories = strings(m["categories"]),
            compileTag = when {
                !view.compileSplit -> null
                compile -> "compile"
                else -> "runtime only"
            },
            flags = flags(view, m, id),
            search = listOf(id, m["groupId"], m["artifactId"], m["version"], m["jar"], m["origin"], licences.joinToString(" "), strings(m["categories"]).joinToString(" "))
                .joinToString(" ") { it.toString().lowercase() },
            compile = compile,
            details = ReportTable(
                listOf("Jar", "Identity", "Origin", "Licence", "Java", "Module name", "Found by"),
                listOf(listOf(
                    text(m["jar"]), text(m["identity"]), text(m["origin"]),
                    if (licences.isEmpty()) ReportCell(listOf(ReportPart("none declared", muted = true))) else ReportCell(licences.map { ReportPart(it) }),
                    text(m["javaVersion"] ?: "-"), text(m["moduleName"] ?: "-"), text(m["resolutionSource"])
                ))
            ),
            compileReason = m["compileReason"]?.toString(),
            pluginIds = strings(m["pluginIds"]),
            dependencies = list(m["dependsOn"]).map { d -> ReportDependency(view.link(d["moduleId"].toString()), strings(d["evidence"]).take(5)) },
            compileDependencies = m["apiDependsOn"]?.takeIf { compile }?.let { api -> view.links(list(api).map { it["moduleId"].toString() }, "none") },
            redundant = view.redundant[id].orEmpty().map { ReportRedundant(view.link(it["to"].toString()), view.link(it["via"].toString())) },
            users = view.links(view.usedBy[id].orEmpty().sorted(), "no other jar"),
            userCount = view.usedBy[id].orEmpty().size
        )
    }

    private fun stats(view: GraphView, attentionCount: Int): List<ReportStat> = listOfNotNull(
        ReportStat(view.modules.size.toString(), "jars"),
        ReportStat(view.categories.size.toString(), "categories: " + view.categories.joinToString(", ")),
        if (view.compileSplit) ReportStat(view.modules.count { it["compile"] == true }.toString(), "on the compile classpath") else null,
        ReportStat(view.modules.sumOf { list(it["dependsOn"]).size }.toString(), "dependencies"),
        ReportStat("${view.raw["skippedJarCount"] ?: 0}", "jars skipped"),
        ReportStat(attentionCount.toString(), "topics need attention")
    )
}
