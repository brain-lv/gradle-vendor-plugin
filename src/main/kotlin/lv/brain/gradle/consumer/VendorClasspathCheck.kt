/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.consumer

import lv.brain.gradle.core.VendorLayout
import lv.brain.gradle.core.VendorRepositoryLayout
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

internal data class DependencyRequest(
    val requester: String,
    val fromVendor: Boolean,
    val fromProject: Boolean,
    val group: String,
    val module: String,
    val requested: String,
    val selected: String?,
    val graph: String
) {
    val coordinate: String get() = "$group:$module"
}

internal data class VendorClash(
    val coordinate: String,
    val vendorVersions: Set<String>,
    val consumerRequests: List<DependencyRequest>,
    val runtimeVersion: String?
)

internal object VendorClashDetector {
    fun find(requests: List<DependencyRequest>, ignore: List<Regex> = emptyList()): List<VendorClash> =
        requests.groupBy { it.coordinate }
            .toSortedMap()
            .mapNotNull { (coordinate, group) ->
                if (ignore.any { it.matches(coordinate) }) return@mapNotNull null
                val vendor = group.filter { it.fromVendor }
                val consumer = group.filterNot { it.fromVendor }
                if (vendor.isEmpty() || consumer.isEmpty()) return@mapNotNull null

                val vendorVersions = vendor.map { it.requested }.toSortedSet()
                val runtimeVersion = vendor.firstOrNull { it.graph == "runtime" && it.selected != null }?.selected
                val replaced = runtimeVersion != null && runtimeVersion !in vendorVersions

                val offenders = LinkedHashSet<DependencyRequest>()
                if (replaced) offenders += consumer.filter { effectiveVersion(it) == runtimeVersion }
                offenders += consumer.filter { it.fromProject && effectiveVersion(it) !in vendorVersions }

                if (offenders.isEmpty()) null
                else VendorClash(coordinate, vendorVersions, offenders.toList(), runtimeVersion)
            }

    private fun effectiveVersion(request: DependencyRequest): String =
        if (isExact(request.requested)) request.requested else request.selected ?: request.requested

    internal fun isExact(version: String): Boolean =
        version.isNotEmpty() && version.none { it in "+[]()*," } && !version.startsWith("latest.")

    fun ignoreRegexes(patterns: List<String>): List<Regex> =
        patterns.map { pattern -> Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }) }

    fun message(scope: String, clashes: List<VendorClash>): String = buildString {
        appendLine("Vendor runtime jars clash with the dependencies of $scope:")
        for (clash in clashes) {
            appendLine()
            appendLine("  ${clash.coordinate}")
            appendLine("      vendor distribution:  ${clash.vendorVersions.joinToString(", ")}")
            val perDeclaration = clash.consumerRequests.groupBy { Triple(it.requester, it.requested, it.selected) }
            for ((declaration, requests) in perDeclaration) {
                val (requester, requested, selected) = declaration
                val resolved = if (selected != null && selected != requested) " (resolved to $selected)" else ""
                val graphs = requests.map { it.graph }.distinct().sorted().joinToString(" + ")
                appendLine("      $requester asks for $requested$resolved  [$graphs classpath]")
            }
            appendLine("      runtime classpath gets:  ${clash.runtimeVersion ?: "-"}")
        }
        appendLine()
        appendLine("The vendor jars of these libraries are what the vendor software was built and tested with. Use the vendor")
        appendLine("version in the project, or remove the library from the project and take it from the vendor bundle.")
        appendLine("If both versions are really meant to differ, list the library in vendor.clashIgnore")
        append("(for example vendor.clashIgnore=org.lite:*), or turn the check off with vendor.clashCheck=false.")
    }

    fun collect(root: ResolvedComponentResult, graph: String, vendorCoordinates: Set<String>): List<DependencyRequest> {
        val requests = ArrayList<DependencyRequest>()
        val seen = HashSet<ResolvedComponentResult>()
        val pending = ArrayDeque<ResolvedComponentResult>()
        pending.add(root)

        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (!seen.add(component)) continue

            val id = component.id
            val fromVendor = id is ModuleComponentIdentifier && "${id.group}:${id.module}" in vendorCoordinates
            val fromProject = id is ProjectComponentIdentifier

            for (dependency in component.dependencies) {
                if (dependency !is ResolvedDependencyResult) continue
                pending.add(dependency.selected)

                val selector = dependency.requested as? ModuleComponentSelector ?: continue
                val constraint = selector.versionConstraint
                val requested = constraint.requiredVersion.ifEmpty { constraint.strictVersion }
                    .ifEmpty { constraint.preferredVersion }
                if (requested.isEmpty()) continue
                requests += DependencyRequest(
                    requester = if (id is ProjectComponentIdentifier) "project '${id.projectPath}'" else id.displayName,
                    fromVendor = fromVendor,
                    fromProject = fromProject,
                    group = selector.group,
                    module = selector.module,
                    requested = requested,
                    selected = dependency.selected.moduleVersion?.version,
                    graph = graph
                )
            }
        }
        return requests
    }
}

/**
 * Fails when a vendor runtime jar clashes with a dependency of the project: same `group:artifact`, another version.
 *
 * Registered as `verify<SourceSet>VendorClasspath` for each source set and run before its Java compilation. Controlled
 * by `vendor.clashCheck` (`auto`, `true`, `false`); `vendor.clashIgnore` lists `group:artifact` patterns to skip.
 */
@DisableCachingByDefault(because = "Quick check of resolved dependency graphs; only writes a marker file")
abstract class VerifyVendorClasspathTask : DefaultTask() {
    /** The resolved compile classpath of the source set. */
    @get:Input
    abstract val compileGraph: Property<ResolvedComponentResult>

    /** The resolved runtime classpath of the source set. */
    @get:Input
    abstract val runtimeGraph: Property<ResolvedComponentResult>

    /** The version of the vendor distribution. */
    @get:Input
    abstract val vendorVersion: Property<String>

    /** `group:artifact` patterns (`*` is a wildcard) that are not checked; from `vendor.clashIgnore`. */
    @get:Input
    abstract val clashIgnore: ListProperty<String>

    /** Names the checked classpath in the error message, e.g. `project ':app' (source set 'main')`. */
    @get:Input
    abstract val scope: Property<String>

    /** The unpacked distribution. */
    @get:Internal
    abstract val vendorRoot: DirectoryProperty

    /** Written when the check passes, so the task is up to date while nothing changes. */
    @get:OutputFile
    abstract val okMarker: RegularFileProperty

    init {
        clashIgnore.convention(emptyList())
    }

    /**
     * Runs the check.
     *
     * @throws org.gradle.api.GradleException listing every clash found.
     */
    @TaskAction
    fun verify() {
        val vendor = VendorRepositoryLayout.moduleCoordinates(vendorRoot.get().asFile)
        val requests = VendorClashDetector.collect(compileGraph.get(), "compile", vendor) +
            VendorClashDetector.collect(runtimeGraph.get(), "runtime", vendor)

        val clashes = VendorClashDetector.find(requests, VendorClashDetector.ignoreRegexes(clashIgnore.get()))
        if (clashes.isNotEmpty()) {
            throw GradleException(VendorClashDetector.message(scope.get(), clashes))
        }

        okMarker.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

internal object VendorClasspathChecks {
    fun enabled(mode: String, layout: VendorLayout): Boolean = when (mode) {
        "true" -> true
        "false" -> false
        else -> layout.compileSplit
    }

    fun register(project: Project, ctx: ResolvedVendorContext) {
        if (!enabled(ctx.config.clashCheck, ctx.layout)) return

        project.plugins.withType(JavaBasePlugin::class.java) {
            project.extensions.getByType(JavaPluginExtension::class.java).sourceSets.configureEach {
                val sourceSet = this
                val taskName = sourceSet.getTaskName("verify", "vendorClasspath")
                if (project.tasks.names.contains(taskName)) return@configureEach

                val verify = project.tasks.register(taskName, VerifyVendorClasspathTask::class.java) {
                    group = "verification"
                    description =
                        "Fails when a vendor runtime jar clashes (same group:artifact, other version) with a dependency of the '${sourceSet.name}' source set."
                    compileGraph.set(
                        project.configurations.named(sourceSet.compileClasspathConfigurationName)
                            .flatMap { it.incoming.resolutionResult.rootComponent }
                    )
                    runtimeGraph.set(
                        project.configurations.named(sourceSet.runtimeClasspathConfigurationName)
                            .flatMap { it.incoming.resolutionResult.rootComponent }
                    )
                    vendorVersion.set(ctx.config.version)
                    clashIgnore.set(ctx.config.clashIgnore)
                    scope.set("${project.displayName} (source set '${sourceSet.name}')")
                    vendorRoot.set(ctx.vendorHomeDir)
                    okMarker.set(project.layout.buildDirectory.file("vendor/$taskName.ok"))
                }

                project.tasks.matching { it.name == sourceSet.compileJavaTaskName }.configureEach {
                    dependsOn(verify)
                }
            }
        }
    }
}
