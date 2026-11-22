/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.patcher

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * The `vendorPatcher { }` block, added by the `lv.brain.vendor-patcher` plugin.
 *
 * The patcher copies [sourceDir] to [outputDir] and applies the numbered patch files of [patchesDir] to the copy, in
 * number order (`001-fix-config.patch`, `002-...`). The original folder is never changed.
 *
 * ```kotlin
 * vendorPatcher {
 *     patchesDir.set(layout.projectDirectory.dir("internal-patches"))
 *     ignoreWhitespace.set(true)
 * }
 * ```
 */
abstract class VendorPatcherExtension {
    /** The folder to patch. Defaults to `vendor` in the project directory. Its `ivy/` folder is never copied. */
    abstract val sourceDir: DirectoryProperty

    /** The folder with the numbered patch files. Defaults to `internal-patches` in the project directory. */
    abstract val patchesDir: DirectoryProperty

    /** Where the patched copy is written. Defaults to `build/vendor-patched/<name of sourceDir>`. */
    abstract val outputDir: DirectoryProperty

    /** When `true`, a patch also applies where the file differs only in spaces, tabs or line endings. Defaults to `false`. */
    abstract val ignoreWhitespace: Property<Boolean>

    /**
     * When `true`, signature files are removed from jars that a patch changes, so the JVM loads them unsigned.
     * Defaults to `false`: patching a signed jar fails with an explanation.
     */
    abstract val stripJarSignatures: Property<Boolean>
}

/**
 * Inputs shared by [ApplyVendorPatchesTask] and [CheckVendorPatchesTask].
 *
 * A patch file is a unified diff (`diff -u` or `git diff`). Its paths are relative to [sourceDir]; a path such as
 * `client/lib/app.jar!/META-INF/app.xml` changes a text file inside a jar.
 */
@DisableCachingByDefault(because = "Base type; the concrete tasks decide")
abstract class VendorPatchTask : DefaultTask() {
    /** The folder to patch. */
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    /** The files of [sourceDir], without its `ivy/` folder. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sourceFiles: FileTree
        get() = sourceDir.asFileTree.matching { exclude("${VendorPatchEngine.IGNORED_TOP_LEVEL_DIR}/**") }

    /** The folder with the numbered patch files. */
    @get:Internal
    abstract val patchesDir: DirectoryProperty

    /** The patch files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val patchFiles: FileTree
        get() = patchesDir.asFileTree

    /** See [VendorPatcherExtension.ignoreWhitespace]. */
    @get:Input
    abstract val ignoreWhitespace: Property<Boolean>

    /** See [VendorPatcherExtension.stripJarSignatures]. */
    @get:Input
    abstract val stripJarSignatures: Property<Boolean>

    /** A text report of the last run, also written when a step fails. */
    @get:Internal
    abstract val reportFile: RegularFileProperty

    internal fun runSteps(workDir: File): PatchResult {
        val report = reportFile.get().asFile.apply { parentFile.mkdirs() }
        val source = sourceDir.get().asFile
        try {
            val steps = VendorPatchEngine.discover(patchesDir.get().asFile) { logger.warn(it) }
            VendorPatchEngine.copySource(source, workDir)
            val result = VendorPatchEngine.applyAll(
                steps, workDir, "the folder ${source.name}",
                PatchOptions(ignoreWhitespace.get(), stripJarSignatures.get())
            )
            report.writeText(
                "Applied ${result.applied.size} step(s) to ${source.path}:\n" +
                    result.applied.joinToString("") { "  $it\n" } +
                    result.notes.joinToString("") { "note: $it\n" } +
                    result.warnings.joinToString("") { "warning: $it\n" }
            )
            result.notes.forEach { logger.info("Vendor patches: {}", it) }
            result.warnings.forEach { logger.warn("Vendor patches: {}", it) }
            return result
        } catch (e: PatchException) {
            report.writeText(e.message.orEmpty() + "\n")
            throw GradleException("${e.message}\nReport: ${report.path}")
        }
    }
}

/**
 * The `applyVendorPatches` task: copies [sourceDir] to [outputDir] and applies every numbered patch to the copy.
 *
 * Stops at the first step that fails and prints which step, file and hunk failed, the likely cause and how to fix it.
 * Nothing is written then. Cacheable: the result depends only on the source files, the patch files and the options.
 */
@CacheableTask
abstract class ApplyVendorPatchesTask : VendorPatchTask() {
    /** Where the patched copy is written. Its `ivy/` folder, if any, is left alone. */
    @get:Internal
    abstract val outputDir: DirectoryProperty

    /** The top-level folders of the patched copy. */
    @get:OutputDirectories
    val outputDirectories: Map<String, File>
        get() = topLevelEntries().filterValues { it }.keys.associateWith { File(outputDir.get().asFile, it) }

    /** The files directly in the patched copy. */
    @get:OutputFiles
    val outputFiles: Map<String, File>
        get() = topLevelEntries().filterValues { !it }.keys.associateWith { File(outputDir.get().asFile, it) }

    private fun topLevelEntries(): Map<String, Boolean> {
        val entries = sortedMapOf<String, Boolean>()
        sourceDir.get().asFile.listFiles().orEmpty()
            .filter { !(it.isDirectory && it.name == VendorPatchEngine.IGNORED_TOP_LEVEL_DIR) }
            .forEach { entries[it.name] = it.isDirectory }
        patchesDir.get().asFile.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.forEach { folder ->
            folder.listFiles().orEmpty().filter { !it.name.startsWith(".") && it.name != VendorPatchEngine.IGNORED_TOP_LEVEL_DIR }
                .forEach { entries.putIfAbsent(it.name, it.isDirectory) }
        }
        patchesDir.get().asFile.listFiles().orEmpty().filter { it.isFile }.forEach { patch ->
            patch.useLines(Charsets.ISO_8859_1) { lines ->
                lines.filter { it.startsWith("+++ ") }
                    .map { it.substring(4).substringBefore('\t').trim().removePrefix("b/").removePrefix("./") }
                    .filter { it != "/dev/null" && it.isNotEmpty() }
                    .forEach { path ->
                        val first = path.substringBefore('/')
                        if (first != VendorPatchEngine.IGNORED_TOP_LEVEL_DIR) entries.putIfAbsent(first, '/' in path)
                    }
            }
        }
        return entries
    }

    /** Applies the patches and replaces the previous patched copy. */
    @TaskAction
    fun applyPatches() {
        val staging = File(temporaryDir, "staging")
        try {
            val result = runSteps(staging)
            val output = outputDir.get().asFile.apply { mkdirs() }
            output.listFiles().orEmpty()
                .filter { !(it.isDirectory && it.name == VendorPatchEngine.IGNORED_TOP_LEVEL_DIR) }
                .forEach { it.deleteRecursively() }
            staging.listFiles().orEmpty().forEach { entry ->
                val target = File(output, entry.name)
                if (!entry.renameTo(target)) {
                    entry.copyRecursively(target, overwrite = true)
                }
            }
            logger.lifecycle("Vendor patches: {} step(s) applied, patched copy in {}", result.applied.size, output)
        } finally {
            staging.deleteRecursively()
        }
    }
}

/**
 * The `checkVendorPatches` task: applies every numbered patch to a temporary copy and reports the first failure,
 * without writing a patched copy. Useful in CI and right after a vendor upgrade.
 */
@DisableCachingByDefault(because = "Only checks the patches; writes a marker file")
abstract class CheckVendorPatchesTask : VendorPatchTask() {
    /** Written when every step applies. */
    @get:OutputFile
    abstract val markerFile: RegularFileProperty

    /** Applies the patches to a temporary copy. */
    @TaskAction
    fun checkPatches() {
        val staging = File(temporaryDir, "staging")
        try {
            val result = runSteps(staging)
            markerFile.get().asFile.apply { parentFile.mkdirs() }.writeText(result.applied.joinToString("\n", postfix = "\n"))
            logger.lifecycle("Vendor patches: all {} step(s) apply cleanly", result.applied.size)
        } finally {
            staging.deleteRecursively()
        }
    }
}

/**
 * The `vendorPatchStatus` task: shows for every numbered step whether it applies, applies at moved lines, is already
 * in the vendor files or fails, without stopping at the first failure. Never fails the build.
 */
@UntrackedTask(because = "Prints the state of the patches; always runs")
abstract class VendorPatchStatusTask : VendorPatchTask() {
    /** Applies each step to a temporary copy and prints one line per step. */
    @TaskAction
    fun printStatus() {
        val staging = File(temporaryDir, "staging")
        try {
            val steps = VendorPatchEngine.discover(patchesDir.get().asFile) { logger.warn(it) }
            VendorPatchEngine.copySource(sourceDir.get().asFile, staging)
            val statuses = VendorPatchEngine.status(steps, staging, PatchOptions(ignoreWhitespace.get(), stripJarSignatures.get()))
            val width = statuses.maxOfOrNull { it.step.length } ?: 0
            val lines = statuses.map { status ->
                val label = when (status.outcome) {
                    StepOutcome.APPLIED -> "applies"
                    StepOutcome.MOVED -> "applies at moved lines, refreshVendorPatches updates it"
                    StepOutcome.ALREADY_APPLIED -> "already in the vendor files, delete the step"
                    StepOutcome.FAILED -> "fails"
                }
                "${status.step.padEnd(width)}  $label" + if (status.detail.isNotEmpty()) " (${status.detail})" else ""
            }
            val failed = statuses.count { it.outcome == StepOutcome.FAILED || it.outcome == StepOutcome.ALREADY_APPLIED }
            val text = (if (lines.isEmpty()) "No vendor patch steps in ${patchesDir.get().asFile.path}" else lines.joinToString("\n")) +
                "\n${statuses.size} step(s), $failed need attention\n"
            reportFile.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
            logger.lifecycle(text.trimEnd())
        } catch (e: PatchException) {
            throw GradleException(e.message.orEmpty())
        } finally {
            staging.deleteRecursively()
        }
    }
}

/**
 * Shared settings of [PrepareVendorPatchWorkspaceTask] and [CreateVendorPatchTask].
 */
@DisableCachingByDefault(because = "Base type; the concrete tasks decide")
abstract class VendorPatchWorkspaceTask : VendorPatchTask() {
    /** The folder to edit: the vendor folder with every step applied. Defaults to `build/vendor-patch-workspace/<name>`. */
    @get:Internal
    abstract val workspaceDir: DirectoryProperty

    /** The state of [workspaceDir] before editing, used to find the changes. */
    @get:Internal
    abstract val baselineDir: DirectoryProperty

    internal fun unsavedChanges(): WorkspaceChanges? {
        val workspace = workspaceDir.get().asFile
        val baseline = baselineDir.get().asFile
        if (!workspace.isDirectory || !baseline.isDirectory) return null
        return PatchWorkspace.compare(baseline, workspace)
    }
}

/**
 * The `prepareVendorPatchWorkspace` task: writes the vendor folder with every step applied to [workspaceDir], to be
 * edited by hand. `createVendorPatch` then turns the edits into the next numbered steps.
 *
 * Refuses to overwrite edits that are not saved as steps yet, unless `--reset` is given.
 */
@UntrackedTask(because = "Writes a folder to be edited by hand")
abstract class PrepareVendorPatchWorkspaceTask : VendorPatchWorkspaceTask() {
    /** Discards edits in [workspaceDir] that are not saved as steps. */
    @get:Input
    @get:Option(option = "reset", description = "Discards edits in the workspace that are not saved as patch steps.")
    abstract val reset: Property<Boolean>

    /** Writes [workspaceDir]. */
    @TaskAction
    fun prepare() {
        val pending = unsavedChanges()
        if (pending != null && !pending.isEmpty && !reset.get()) {
            throw GradleException(
                "The vendor patch workspace ${workspaceDir.get().asFile} has edits that are not saved as patch steps:\n" +
                    pending.described.take(20).joinToString("") { "  $it\n" } +
                    "Save them with `createVendorPatch --name=<what it fixes>`, or discard them with `prepareVendorPatchWorkspace --reset`."
            )
        }
        val baseline = baselineDir.get().asFile
        val workspace = workspaceDir.get().asFile
        val result = runSteps(baseline)
        workspace.deleteRecursively()
        baseline.copyRecursively(workspace, overwrite = true)
        logger.lifecycle(
            "Vendor patch workspace: {} step(s) applied in {}. Change files there (jar entries too, for example with `jar uf`), " +
                "then run createVendorPatch --name=<what it fixes>.",
            result.applied.size, workspace
        )
    }
}

/**
 * The `createVendorPatch` task: compares [workspaceDir] with the state `prepareVendorPatchWorkspace` wrote and saves
 * the edits as the next numbered steps of [patchesDir]: a `.patch` for text changes, a folder for binary files and
 * classes, a `.delete` file for removed files.
 *
 * Checks that the steps reproduce the workspace exactly before keeping them.
 */
@UntrackedTask(because = "Writes patch files into the project")
abstract class CreateVendorPatchTask : VendorPatchWorkspaceTask() {
    /** The name of the new steps, such as `longer-timeout`; it follows the number in the file names. */
    @get:Input
    @get:Option(option = "name", description = "The name of the new steps, such as longer-timeout.")
    abstract val stepName: Property<String>

    /** Writes the new steps. */
    @TaskAction
    fun create() {
        val changes = unsavedChanges() ?: throw GradleException(
            "There is no vendor patch workspace yet. Run prepareVendorPatchWorkspace, change the files in " +
                "${workspaceDir.get().asFile}, then run createVendorPatch --name=<what it fixes>."
        )
        if (changes.isEmpty) {
            logger.lifecycle("Vendor patch workspace: nothing changed in {}, no step written", workspaceDir.get().asFile)
            return
        }
        val name = stepName.get().trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')
        if (name.isEmpty()) throw GradleException("--name needs letters or digits, such as --name=longer-timeout")
        val written = PatchWorkspace.nextSteps(patchesDir.get().asFile, name, changes)
        val check = File(temporaryDir, "check")
        try {
            runSteps(check)
            val left = PatchWorkspace.compare(check, workspaceDir.get().asFile)
            if (!left.isEmpty) {
                written.forEach { it.deleteRecursively() }
                throw GradleException(
                    "The new steps did not reproduce the workspace, so they were not kept. Differences:\n" +
                        left.described.take(20).joinToString("") { "  $it\n" }
                )
            }
            baselineDir.get().asFile.deleteRecursively()
            check.copyRecursively(baselineDir.get().asFile, overwrite = true)
        } catch (e: GradleException) {
            written.forEach { it.deleteRecursively() }
            throw e
        } finally {
            check.deleteRecursively()
        }
        logger.lifecycle(
            "Vendor patches: {} written ({} change(s)):\n{}",
            written.joinToString { it.name }, changes.described.size,
            changes.described.joinToString("\n") { "  $it" }
        )
    }
}

/**
 * The `refreshVendorPatches` task: after a vendor upgrade, rewrites `.patch` steps that still apply, but only at moved
 * lines or with other whitespace, so they apply exactly again. Stops at the first step that fails and explains it.
 */
@UntrackedTask(because = "Rewrites patch files in the project")
abstract class RefreshVendorPatchesTask : VendorPatchTask() {
    /** Applies the steps to a temporary copy and rewrites the ones that moved. */
    @TaskAction
    fun refresh() {
        val staging = File(temporaryDir, "staging")
        try {
            val steps = try {
                VendorPatchEngine.discover(patchesDir.get().asFile) { logger.warn(it) }
            } catch (e: PatchException) {
                throw GradleException(e.message.orEmpty())
            }
            VendorPatchEngine.copySource(sourceDir.get().asFile, staging)
            val result = PatchWorkspace.refresh(
                steps, staging, PatchOptions(ignoreWhitespace.get(), stripJarSignatures.get()), "the folder ${sourceDir.get().asFile.name}"
            )
            val summary = "Vendor patches: ${result.refreshed.size} step(s) refreshed" +
                (if (result.refreshed.isEmpty()) "" else " (${result.refreshed.joinToString()})") +
                ", ${result.unchanged.size} unchanged"
            reportFile.get().asFile.apply { parentFile.mkdirs() }.writeText(summary + "\n" + (result.failure?.let { "\n$it\n" } ?: ""))
            if (result.failure != null) {
                throw GradleException("$summary\n\n${result.failure.replace("Nothing was written", "That step and the ones after it were not changed")}")
            }
            logger.lifecycle(summary)
        } finally {
            staging.deleteRecursively()
        }
    }
}

/**
 * The `lv.brain.vendor-patcher` plugin: applies numbered patch files to a copy of a vendor folder.
 *
 * Adds the `vendorPatcher { }` block ([VendorPatcherExtension]) and the tasks `applyVendorPatches`
 * ([ApplyVendorPatchesTask]), `checkVendorPatches` ([CheckVendorPatchesTask]), `vendorPatchStatus`
 * ([VendorPatchStatusTask]), `prepareVendorPatchWorkspace` ([PrepareVendorPatchWorkspaceTask]), `createVendorPatch`
 * ([CreateVendorPatchTask]) and `refreshVendorPatches` ([RefreshVendorPatchesTask]). Apply it in `build.gradle(.kts)`, or
 * let `lv.brain.vendor-distributor` apply it with `vendor.patcher=true`.
 */
class VendorPatcherPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("vendorPatcher", VendorPatcherExtension::class.java)
        ext.sourceDir.convention(project.layout.projectDirectory.dir("vendor"))
        ext.patchesDir.convention(project.layout.projectDirectory.dir("internal-patches"))
        ext.outputDir.convention(project.layout.buildDirectory.dir(ext.sourceDir.map { "vendor-patched/${it.asFile.name}" }))
        ext.ignoreWhitespace.convention(false)
        ext.stripJarSignatures.convention(false)

        project.tasks.register("applyVendorPatches", ApplyVendorPatchesTask::class.java) {
            group = "vendor"
            description = "Copies the vendor folder and applies the numbered patch files to the copy."
            wire(this, ext)
            outputDir.set(ext.outputDir)
            reportFile.set(project.layout.buildDirectory.file("reports/vendor-patches/apply.txt"))
        }
        project.tasks.register("checkVendorPatches", CheckVendorPatchesTask::class.java) {
            group = "verification"
            description = "Checks that every numbered patch file applies, without writing a patched copy."
            wire(this, ext)
            markerFile.set(project.layout.buildDirectory.file("vendor-patches/check.ok"))
            reportFile.set(project.layout.buildDirectory.file("reports/vendor-patches/check.txt"))
        }
        project.tasks.register("vendorPatchStatus", VendorPatchStatusTask::class.java) {
            group = "vendor"
            description = "Shows for every numbered patch step whether it applies, moved, is already in the vendor files or fails."
            wire(this, ext)
            reportFile.set(project.layout.buildDirectory.file("reports/vendor-patches/status.txt"))
        }
        val workspace = project.layout.buildDirectory.dir(ext.sourceDir.map { "vendor-patch-workspace/${it.asFile.name}" })
        val baseline = project.layout.buildDirectory.dir("vendor-patch-workspace/.baseline")
        project.tasks.register("prepareVendorPatchWorkspace", PrepareVendorPatchWorkspaceTask::class.java) {
            group = "vendor"
            description = "Writes the vendor folder with every patch step applied, to be changed by hand."
            wire(this, ext)
            workspaceDir.set(workspace)
            baselineDir.set(baseline)
            reset.convention(false)
            reportFile.set(project.layout.buildDirectory.file("reports/vendor-patches/workspace.txt"))
        }
        project.tasks.register("createVendorPatch", CreateVendorPatchTask::class.java) {
            group = "vendor"
            description = "Saves the changes made in the patch workspace as the next numbered patch steps."
            wire(this, ext)
            workspaceDir.set(workspace)
            baselineDir.set(baseline)
            stepName.convention("change")
            reportFile.set(project.layout.buildDirectory.file("reports/vendor-patches/create.txt"))
        }
        project.tasks.register("refreshVendorPatches", RefreshVendorPatchesTask::class.java) {
            group = "vendor"
            description = "Rewrites patch steps that apply only at moved lines, so they apply exactly again."
            wire(this, ext)
            reportFile.set(project.layout.buildDirectory.file("reports/vendor-patches/refresh.txt"))
        }
    }

    private fun wire(task: VendorPatchTask, ext: VendorPatcherExtension) {
        task.sourceDir.set(ext.sourceDir)
        task.patchesDir.set(ext.patchesDir)
        task.ignoreWhitespace.set(ext.ignoreWhitespace)
        task.stripJarSignatures.set(ext.stripJarSignatures)
    }
}
