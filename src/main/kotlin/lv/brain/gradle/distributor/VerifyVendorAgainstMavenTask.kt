/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonOutput
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import lv.brain.gradle.core.GroupRule
import lv.brain.gradle.core.LicensePolicy
import lv.brain.gradle.distributor.VendorIvyDependencyMapper.ResolvedModule
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element

/**
 * The `verifyVendorAgainstMaven` task: checks the coordinates and dependencies the plugin worked out against a Maven
 * repository, Maven Central by default.
 *
 * It looks up the jars that can be public: those marked `open-source` or `vendor-public` in their `vendor.rule.<n>`, and
 * jars without a rule that carry their own Maven metadata. Jars marked `vendor-private` and jars named only by their file
 * are never looked up. For each it compares `<group>:<name>:<version>` and the jar's hash with the published one: `match`, `differs` (the vendor ships a changed copy), `not found` (the
 * coordinates are wrong, or it is not a public library) or `error`. For each match it also compares the published
 * `pom.xml` with the dependencies the plugin found. The report is written to `build/reports/vendor-maven-check/`.
 */
@DisableCachingByDefault(because = "Asks a remote repository")
abstract class VerifyVendorAgainstMavenTask : DefaultTask() {
    /** The module graph written by `resolveVendorModules`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyGraphFile: RegularFileProperty

    /** The directory that holds the vendor jars. */
    @get:Internal
    abstract val vendorDir: DirectoryProperty

    /** The Maven repository to compare with; `vendor.mavenCheck.url`, Maven Central by default. */
    @get:Input
    abstract val repositoryUrl: Property<String>

    /** The base group of the distribution; jars still in it have no real coordinates and are not looked up. */
    @get:Input
    abstract val defaultGroup: Property<String>

    /** Licence patterns a jar may have, see `vendor.licenses.allow`. */
    @get:Input
    abstract val licenseAllow: ListProperty<String>

    /** Licence patterns no jar may have, see `vendor.licenses.deny`. */
    @get:Input
    abstract val licenseDeny: ListProperty<String>

    /** Which findings fail the build: `differs`, `notfound`, `dependencies`, `licenses`; none by default. */
    @get:Input
    abstract val failOn: ListProperty<String>

    /** The report folder, by default `build/reports/vendor-maven-check`, with `report.txt` and `report.json`. */
    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    init {
        repositoryUrl.convention(MAVEN_CENTRAL)
        failOn.convention(emptyList())
        licenseAllow.convention(emptyList())
        licenseDeny.convention(emptyList())
        outputs.upToDateWhen { false }
    }

    /** Looks every candidate up and writes the report. */
    @TaskAction
    fun verify() {
        val modules = VendorIvyDependencyMapper.loadGraph(dependencyGraphFile.get().asFile)
        val root = vendorDir.get().asFile
        val repository = VendorMavenLookup(repositoryUrl.get())
        val base = defaultGroup.get()
        val (candidates, skipped) = modules.partition { isPublic(it, base) }

        val executor = Executors.newFixedThreadPool(8)
        val results = try {
            executor.invokeAll(candidates.map { module -> Callable { check(module, File(root, module.jarRelativePath), repository) } }).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val dependencyFindings = MavenCheckFindings.dependencyFindings(modules, results)
        val licenseFindings = MavenCheckFindings.licenseFindings(results, LicensePolicy(licenseAllow.get(), licenseDeny.get()))
        licenseFindings.forEach {
            logger.warn("Vendor Maven check: {} has a licence the licence policy does not accept: {}", it["module"], (it["licenses"] as List<*>).joinToString())
        }

        val out = reportDir.get().asFile.apply { mkdirs() }
        val reportText = File(out, "report.txt")
        val report = linkedMapOf(
            "repository" to repository.base,
            "jars" to results.sortedBy { it.module.moduleId }.map {
                linkedMapOf(
                    "module" to it.module.moduleId, "coordinates" to it.coordinates, "status" to it.status, "detail" to it.detail,
                    "publishedLicenses" to it.publishedLicenses
                )
            },
            "skipped" to skipped.map { it.moduleId }.sorted(),
            "dependencies" to dependencyFindings,
            "licenses" to licenseFindings
        )
        File(out, "report.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n")
        reportText.writeText(text(repository.base, results, skipped, dependencyFindings))

        val counts = results.groupingBy { it.status }.eachCount()
        val missingCount = dependencyFindings.sumOf { (it["missing"] as List<*>).size }
        logger.lifecycle(
            "Vendor Maven check against {}: {} jar(s) checked: {} match, {} differ, {} not found, {} error(s); {} skipped " +
                "(vendor-private, or not known to be public); {} published dependency(ies) the graph does not reach. Report: {}",
            repository.base, results.size, counts[MATCH] ?: 0, counts[DIFFERS] ?: 0, counts[NOT_FOUND] ?: 0, counts[ERROR] ?: 0,
            skipped.size, missingCount, reportText
        )
        results.filter { it.status == DIFFERS }.forEach {
            logger.warn("Vendor Maven check: {} ({}) is not the published jar: {}", it.module.jarRelativePath, it.coordinates, it.detail)
        }

        val failures = MavenCheckFindings.failures(failOn.get().map { it.trim().lowercase() }.toSet(), counts, missingCount, licenseFindings.size)
        if (failures.isNotEmpty()) {
            throw GradleException("Vendor Maven check failed (vendor.mavenCheck.failOn): ${failures.joinToString("; ")}. See $reportText")
        }
    }

    private fun isPublic(module: ResolvedModule, base: String): Boolean {
        if (module.groupId == base || module.groupId.startsWith("$base.")) return false
        return when (module.origin) {
            in GroupRule.PUBLISHED_ORIGINS -> true
            ORIGIN_UNKNOWN -> module.identity == VendorJarScanner.IDENTITY_POM_PROPERTIES || module.identity == VendorJarScanner.IDENTITY_POM_XML
            else -> false
        }
    }

    private fun check(module: ResolvedModule, jar: File, repository: VendorMavenLookup): MavenResult {
        val coordinates = "${module.groupId}:${module.artifactId}:${module.version}"
        val path = "${module.groupId.replace('.', '/')}/${module.artifactId}/${module.version}/${module.artifactId}-${module.version}"
        return try {
            val published = repository.text("$path.jar.sha1")?.trim()?.substringBefore(' ')?.lowercase()
                ?: return MavenResult(module, coordinates, NOT_FOUND, "no $path.jar in the repository")
            val local = VendorMavenLayout.digest(jar, "SHA-1")
            if (local != published) return MavenResult(module, coordinates, DIFFERS, "SHA-1 $local, published $published")
            val pom = repository.text("$path.pom")
            MavenResult(module, coordinates, MATCH, "SHA-1 $local", pom?.let(::pomDependencies), pom?.let(::pomLicenses).orEmpty())
        } catch (e: Exception) {
            MavenResult(module, coordinates, ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun pomLicenses(pom: String): List<String> {
        val root = VendorJarScanner.parsePom(pom.toByteArray()) ?: return emptyList()
        val nodes = root.getElementsByTagName("license")
        return (0 until nodes.length).mapNotNull { i ->
            val el = nodes.item(i) as? Element ?: return@mapNotNull null
            fun text(tag: String) = el.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            text("name") ?: text("url")
        }.distinct()
    }

    private fun pomDependencies(pom: String): Set<String>? {
        val root = VendorJarScanner.parsePom(pom.toByteArray()) ?: return null
        fun child(parent: Element, tag: String): Element? =
            (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? Element }.firstOrNull { it.tagName == tag }
        fun text(parent: Element, tag: String) = child(parent, tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val group = text(root, "groupId") ?: child(root, "parent")?.let { text(it, "groupId") }
        val dependencies = child(root, "dependencies") ?: return emptySet()
        return (0 until dependencies.childNodes.length).mapNotNull { dependencies.childNodes.item(it) as? Element }
            .filter { it.tagName == "dependency" }
            .filter { (text(it, "scope") ?: "compile") in setOf("compile", "runtime") && text(it, "optional") != "true" }
            .mapNotNull { dep ->
                val g = text(dep, "groupId")?.replace("\${project.groupId}", group.orEmpty())?.replace("\${pom.groupId}", group.orEmpty())
                val a = text(dep, "artifactId")
                if (g == null || a == null) null else "$g:$a"
            }.toSet()
    }

    private fun text(base: String, results: List<MavenResult>, skipped: List<ResolvedModule>, dependencies: List<Map<String, Any>>) = buildString {
        appendLine("Vendor jars compared with $base")
        for (status in listOf(DIFFERS, NOT_FOUND, ERROR, MATCH)) {
            val list = results.filter { it.status == status }.sortedBy { it.module.moduleId }
            appendLine()
            appendLine("$status (${list.size}):")
            list.forEach { appendLine("  ${it.module.jarRelativePath}  ${it.coordinates}  ${it.detail}") }
        }
        appendLine()
        appendLine("skipped (${skipped.size}, vendor-private, or not known to be public):")
        skipped.sortedBy { it.moduleId }.forEach { appendLine("  ${it.jarRelativePath}") }
        appendLine()
        appendLine("dependencies that differ from the published pom.xml (${dependencies.size}):")
        for (finding in dependencies) {
            appendLine("  ${finding["module"]}")
            (finding["missing"] as List<*>).takeIf { it.isNotEmpty() }?.let { appendLine("      published, but the graph does not reach: ${it.joinToString()}") }
            (finding["extra"] as List<*>).takeIf { it.isNotEmpty() }?.let { appendLine("      found in the code, not in the published pom: ${it.joinToString()}") }
        }
    }

    internal data class MavenResult(
        val module: ResolvedModule,
        val coordinates: String,
        val status: String,
        val detail: String,
        val publishedDependencies: Set<String>? = null,
        val publishedLicenses: List<String> = emptyList()
    )

    companion object {
        /** The default repository: Maven Central. */
        const val MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2"
        internal const val MATCH = "match"
        internal const val DIFFERS = "differs"
        internal const val NOT_FOUND = "not found"
        internal const val ERROR = "error"
    }
}

internal class VendorMavenLookup(url: String) {
    val base: String = url.trimEnd('/')
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    fun text(path: String): String? {
        val uri = URI("$base/$path")
        if (uri.scheme.equals("file", ignoreCase = true)) return File(uri).takeIf { it.isFile }?.readText()
        val response = client.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        return when (response.statusCode()) {
            200 -> response.body()
            404 -> null
            else -> error("HTTP ${response.statusCode()} for $uri")
        }
    }
}

internal object MavenCheckFindings {
    fun dependencyFindings(modules: List<ResolvedModule>, results: List<VerifyVendorAgainstMavenTask.MavenResult>): List<Map<String, Any>> {
        val byKey = modules.associateBy { "${it.groupId}:${it.artifactId}" }
        val byId = modules.associateBy { it.moduleId }
        return results.filter { it.status == VerifyVendorAgainstMavenTask.MATCH && it.publishedDependencies != null }.mapNotNull { result ->
            val module = result.module
            val published = result.publishedDependencies.orEmpty()
            val expected = published.mapNotNull { byKey[it]?.moduleId }.filter { it != module.moduleId }.toSortedSet()
            val reachable = reachable(module.moduleId, byId)
            val missing = expected.filterNot { it in reachable }
            val extra = module.dependsOn.mapNotNull { byId[it.moduleId] }
                .filter { "${it.groupId}:${it.artifactId}" !in published }
                .map { it.moduleId }
                .sorted()
            if (missing.isEmpty() && extra.isEmpty()) null else mapOf("module" to module.moduleId, "missing" to missing, "extra" to extra)
        }
    }

    fun licenseFindings(results: List<VerifyVendorAgainstMavenTask.MavenResult>, policy: LicensePolicy): List<Map<String, Any>> {
        if (!policy.active) return emptyList()
        return results.mapNotNull { result ->
            val licenses = result.module.licenses.ifEmpty { result.publishedLicenses }
            if (licenses.isNotEmpty() && !policy.accepts(licenses)) mapOf("module" to result.module.moduleId, "licenses" to licenses) else null
        }
    }

    fun failures(failOn: Set<String>, counts: Map<String, Int>, missingCount: Int, licenseCount: Int): List<String> = buildList {
        if ("differs" in failOn && (counts[VerifyVendorAgainstMavenTask.DIFFERS] ?: 0) > 0) add("${counts[VerifyVendorAgainstMavenTask.DIFFERS]} jar(s) differ from the published ones")
        if ("notfound" in failOn && (counts[VerifyVendorAgainstMavenTask.NOT_FOUND] ?: 0) > 0) add("${counts[VerifyVendorAgainstMavenTask.NOT_FOUND]} jar(s) are not in the repository")
        if ("dependencies" in failOn && missingCount > 0) add("$missingCount published dependency(ies) are not in the graph")
        if ("licenses" in failOn && licenseCount > 0) add("$licenseCount jar(s) have a licence the policy does not accept")
    }

    fun reachable(start: String, byId: Map<String, ResolvedModule>): Set<String> {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            byId[id]?.dependsOn?.forEach { if (seen.add(it.moduleId)) queue.addLast(it.moduleId) }
        }
        return seen
    }
}
