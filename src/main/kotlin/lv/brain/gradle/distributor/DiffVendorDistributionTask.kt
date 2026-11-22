/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonOutput
import lv.brain.gradle.distributor.VendorIvyDependencyMapper.ResolvedModule
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.InputStream
import java.util.TreeSet
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The `diffVendorDistribution` task: compares the current distribution with an earlier distribution ZIP.
 *
 * Lists the vendor jars that were added, removed or changed, the dependencies that changed, the Java versions that
 * changed and the public classes, methods and fields that are gone from a jar. Pass the earlier ZIP with
 * `--baseline=<zip>` or `vendor.diff.baseline`; the report is written to `build/reports/vendor-diff/`.
 */
@DisableCachingByDefault(because = "Writes a report for people to read")
abstract class DiffVendorDistributionTask : DefaultTask() {
    /** The earlier distribution ZIP. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

    /** Sets [baseline] from the command line. */
    @Option(option = "baseline", description = "The earlier vendor distribution ZIP to compare with.")
    fun baselinePath(path: String) {
        baseline.set(project.layout.projectDirectory.file(path))
    }

    /** The directory that holds the vendor jars. */
    @get:Internal
    abstract val vendorDir: DirectoryProperty

    /** Folders and files below [vendorDir] that are never scanned. */
    @get:Input
    abstract val scanExcludes: ListProperty<String>

    /** The current vendor jars. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val vendorJars: FileTree
        get() = VendorJarScanner.jarTree(vendorDir, scanExcludes)

    /** The current module graph written by `resolveVendorModules`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyGraphFile: RegularFileProperty

    /** The report folder, by default `build/reports/vendor-diff`, with `diff.txt` and `diff.json`. */
    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    init {
        scanExcludes.convention(emptyList())
    }

    /** Compares and writes the report. */
    @TaskAction
    fun diff() {
        val zip = baseline.orNull?.asFile
            ?: throw GradleException(
                "diffVendorDistribution needs the earlier distribution: run it with --baseline=<path to vendor-sdk-<version>.zip> " +
                    "or set vendor.diff.baseline."
            )
        if (!zip.isFile) throw GradleException("The baseline distribution $zip does not exist.")
        val old = baselineGraph(zip).associateBy { key(it) }
        val new = VendorIvyDependencyMapper.loadGraph(dependencyGraphFile.get().asFile).associateBy { key(it) }
        val root = vendorDir.get().asFile

        val added = (new.keys - old.keys).sorted().map { id -> new.getValue(id).let { "$id:${it.version} (${it.jarRelativePath})" } }
        val removed = (old.keys - new.keys).sorted().map { id -> old.getValue(id).let { "$id:${it.version} (${it.jarRelativePath})" } }
        val changed = (old.keys intersect new.keys).sorted().mapNotNull { id -> compare(id, old.getValue(id), new.getValue(id), zip, root) }

        val out = reportDir.get().asFile.apply { mkdirs() }
        val report = linkedMapOf("baseline" to zip.name, "added" to added, "removed" to removed, "changed" to changed)
        File(out, "diff.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + "\n")
        File(out, "diff.txt").writeText(text(zip.name, added, removed, changed))
        val breaking = changed.count { it.containsKey("apiRemoved") }
        logger.lifecycle(
            "Vendor diff against {}: {} jar(s) added, {} removed, {} changed ({} with removed public API). Report: {}",
            zip.name, added.size, removed.size, changed.size, breaking, File(out, "diff.txt")
        )
    }

    private fun baselineGraph(zip: File): List<ResolvedModule> {
        val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
        val oldGraph = File(work, "old-module-dependencies.json")
        ZipFile(zip).use { zf ->
            val entry = zf.getEntry("module-dependencies.json") ?: throw GradleException("$zip is not a vendor distribution: it has no module-dependencies.json.")
            zf.getInputStream(entry).use { input -> oldGraph.outputStream().use { input.copyTo(it) } }
        }
        return VendorIvyDependencyMapper.loadGraph(oldGraph)
    }

    internal fun compare(id: String, before: ResolvedModule, after: ResolvedModule, baselineZip: File, root: File): Map<String, Any?>? {
        val entry = linkedMapOf<String, Any?>("module" to id)
        if (before.version != after.version) entry["version"] = "${before.version} -> ${after.version}"
        val contentChanged = before.sha256 != null && after.sha256 != null && before.sha256 != after.sha256
        if (contentChanged) entry["content"] = "changed"
        if (before.javaVersion != after.javaVersion) entry["javaVersion"] = "${before.javaVersion ?: "-"} -> ${after.javaVersion ?: "-"}"
        val depsBefore = before.dependsOn.map { "${it.groupId}:${it.artifactId}" }.toSet()
        val depsAfter = after.dependsOn.map { "${it.groupId}:${it.artifactId}" }.toSet()
        (depsAfter - depsBefore).sorted().takeIf { it.isNotEmpty() }?.let { entry["dependenciesAdded"] = it }
        (depsBefore - depsAfter).sorted().takeIf { it.isNotEmpty() }?.let { entry["dependenciesRemoved"] = it }
        if (contentChanged || before.version != after.version) entry.putAll(apiChanges(baselineZip, before.jarRelativePath, File(root, after.jarRelativePath)))
        return entry.takeIf { it.size > 1 }
    }

    private fun apiChanges(baselineZip: File, oldJarPath: String, newJar: File): Map<String, Any> {
        val oldApi = ZipFile(baselineZip).use { zf -> zf.getEntry(oldJarPath)?.let { e -> zf.getInputStream(e).use { api(it) } } }
        if (oldApi == null || !newJar.isFile) return emptyMap()
        val newApi = newJar.inputStream().use { api(it) }
        val result = linkedMapOf<String, Any>()
        (oldApi - newApi).takeIf { it.isNotEmpty() }?.let { result["apiRemoved"] = it.toList() }
        (newApi - oldApi).takeIf { it.isNotEmpty() }?.let { result["apiAdded"] = it.size }
        return result
    }

    private fun key(module: ResolvedModule) = "${module.groupId}:${module.artifactId}"

    private fun text(baseline: String, added: List<String>, removed: List<String>, changed: List<Map<String, Any?>>): String = buildString {
        appendLine("Vendor distribution compared with $baseline")
        appendLine()
        appendLine("Added (${added.size}):")
        added.forEach { appendLine("  + $it") }
        appendLine()
        appendLine("Removed (${removed.size}):")
        removed.forEach { appendLine("  - $it") }
        appendLine()
        appendLine("Changed (${changed.size}):")
        for (entry in changed) {
            appendLine("  ${entry["module"]}")
            entry["version"]?.let { appendLine("      version: $it") }
            entry["content"]?.let { appendLine("      content: $it") }
            entry["javaVersion"]?.let { appendLine("      Java version: $it") }
            (entry["dependenciesAdded"] as? List<*>)?.let { appendLine("      new dependencies: ${it.joinToString()}") }
            (entry["dependenciesRemoved"] as? List<*>)?.let { appendLine("      dropped dependencies: ${it.joinToString()}") }
            (entry["apiAdded"] as? Int)?.let { appendLine("      public API added: $it") }
            (entry["apiRemoved"] as? List<*>)?.let { list ->
                appendLine("      public API removed (${list.size}):")
                list.take(MAX_SHOWN).forEach { appendLine("        $it") }
                if (list.size > MAX_SHOWN) appendLine("        ... ${list.size - MAX_SHOWN} more in diff.json")
            }
        }
    }

    companion object {
        private const val MAX_SHOWN = 20

        internal fun api(jar: InputStream): TreeSet<String> {
            val result = TreeSet<String>()
            ZipInputStream(jar.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.endsWith(".class") && !name.startsWith("META-INF/") &&
                        name != "module-info.class" && !name.endsWith("package-info.class")
                    ) {
                        ClassReader(zis.readBytes()).accept(ApiCollector(result), ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    }
                    entry = zis.nextEntry
                }
            }
            return result
        }
    }

    private class ApiCollector(private val out: MutableSet<String>) : ClassVisitor(Opcodes.ASM9) {
        private var name = ""
        private var visible = false

        private fun isApi(access: Int) = access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 && access and Opcodes.ACC_SYNTHETIC == 0

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            this.name = name.replace('/', '.')
            visible = access and Opcodes.ACC_PUBLIC != 0
            if (visible) out += "class ${this.name}"
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            if (visible && isApi(access)) out += "field ${this.name}.$name $descriptor"
            return null
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            if (visible && isApi(access) && name != "<clinit>") out += "method ${this.name}.$name$descriptor"
            return null
        }
    }
}
