/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
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
                    result.notes.joinToString("") { "note: $it\n" }
            )
            result.notes.forEach { logger.info("Vendor patches: {}", it) }
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
    /** Written when every step applies, so the check is up to date while nothing changes. */
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
 * The `lv.brain.vendor-patcher` plugin: applies numbered patch files to a copy of a vendor folder.
 *
 * Adds the `vendorPatcher { }` block ([VendorPatcherExtension]) and the tasks `applyVendorPatches`
 * ([ApplyVendorPatchesTask]) and `checkVendorPatches` ([CheckVendorPatchesTask]). Apply it in `build.gradle(.kts)`, or
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
    }

    private fun wire(task: VendorPatchTask, ext: VendorPatcherExtension) {
        task.sourceDir.set(ext.sourceDir)
        task.patchesDir.set(ext.patchesDir)
        task.ignoreWhitespace.set(ext.ignoreWhitespace)
        task.stripJarSignatures.set(ext.stripJarSignatures)
    }
}
