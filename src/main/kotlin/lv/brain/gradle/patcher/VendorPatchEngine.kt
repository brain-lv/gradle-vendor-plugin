/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.patcher

import lv.brain.gradle.core.ReproducibleEntries.withFixedTime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class PatchOptions(val ignoreWhitespace: Boolean = false, val stripJarSignatures: Boolean = false)

internal data class PatchStep(val number: Long, val file: File) {
    val name: String get() = file.name
}

internal data class PatchResult(val applied: List<String>, val notes: List<String>)

internal class PatchException(message: String) : RuntimeException(message)

internal object VendorPatchEngine {
    private val NUMBERED = Regex("""^(\d+)(?:[-_. ].*)?$""")
    private val SUPPORTED = listOf(".patch", ".diff")
    private val SIGNATURE = Regex("""^META-INF/[^/]+\.(SF|RSA|DSA|EC)$""", RegexOption.IGNORE_CASE)
    const val IGNORED_TOP_LEVEL_DIR = "ivy"

    fun discover(patchesDir: File, warn: (String) -> Unit = {}): List<PatchStep> {
        if (!patchesDir.isDirectory) return emptyList()
        val numbered = patchesDir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") }
            .mapNotNull { file -> NUMBERED.matchEntire(file.name)?.let { PatchStep(it.groupValues[1].toLong(), file) } }
            .sortedWith(compareBy({ it.number }, { it.name }))

        val problems = ArrayList<String>()
        numbered.groupBy { it.number }.filterValues { it.size > 1 }.forEach { (number, steps) ->
            problems += "Steps share the number $number: ${steps.joinToString { it.name }}. Every step needs its own number; renumber one of them."
        }
        numbered.forEach { step ->
            val name = step.name.lowercase()
            when {
                SUPPORTED.any { name.endsWith(it) } -> Unit
                name.endsWith(".gradle") || name.endsWith(".gradle.kts") -> problems +=
                    "Step ${step.name}: Gradle script steps are not supported yet. Supported step files: .patch and .diff (unified diff)."
                else -> problems +=
                    "Step ${step.name}: unknown step type. Supported step files: .patch and .diff (unified diff). " +
                        "Files whose name does not start with a number, such as README.md, are ignored."
            }
        }
        if (problems.isNotEmpty()) {
            throw PatchException("Invalid patch steps in ${patchesDir.path}:\n" + problems.joinToString("\n") { "  - $it" })
        }

        val numbers = numbered.map { it.number }
        if (numbers.isNotEmpty()) {
            val missing = (numbers.first()..numbers.last()).filter { it !in numbers }
            if (missing.isNotEmpty()) {
                warn("Vendor patch steps skip number(s) ${missing.take(10).joinToString()}${if (missing.size > 10) ", ..." else ""}; is a step file missing?")
            }
        }
        return numbered
    }

    fun copySource(sourceDir: File, targetDir: File) {
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        if (!sourceDir.isDirectory) return
        sourceDir.listFiles().orEmpty()
            .filter { !(it.isDirectory && it.name == IGNORED_TOP_LEVEL_DIR) }
            .forEach { it.copyRecursively(File(targetDir, it.name), overwrite = true) }
    }

    fun applyAll(steps: List<PatchStep>, workDir: File, sourceLabel: String, options: PatchOptions): PatchResult {
        val applied = ArrayList<String>()
        val notes = ArrayList<String>()
        steps.forEachIndexed { index, step ->
            try {
                notes += applyStep(step, workDir, options).map { "${step.name}: $it" }
            } catch (e: StepFailure) {
                throw PatchException(e.render(step, index, steps.size, applied, sourceLabel))
            }
            applied += step.name
        }
        return PatchResult(applied, notes)
    }

    private fun applyStep(step: PatchStep, workDir: File, options: PatchOptions): List<String> {
        val text = String(step.file.readBytes(), Charsets.ISO_8859_1)
        val filePatches = try {
            UnifiedDiff.parse(text)
        } catch (e: MalformedPatchException) {
            throw StepFailure(
                target = null,
                problem = "The step file is not a valid unified diff: ${e.message}",
                likelyCause = "The file was edited by hand, cut off, or made by a tool that does not write unified diffs.",
                fixes = listOf("Create patches with `diff -u <original> <changed>` or `git diff`, and keep the number of the step.")
            )
        }

        val notes = ArrayList<String>()
        val jars = LinkedHashMap<String, JarEdit>()
        for (filePatch in filePatches) {
            val target = filePatch.targetPath.removePrefix("./")
            val jarSplit = target.indexOf("!/")
            if (jarSplit > 0) {
                val jarPath = target.substring(0, jarSplit)
                val entry = target.substring(jarSplit + 2)
                val jar = jars.getOrPut(jarPath) { JarEdit.open(workDir, jarPath, target) }
                notes += applyToEntry(jar, entry, target, filePatch, options)
            } else {
                notes += applyToFile(workDir, target, filePatch, options)
            }
        }
        jars.values.forEach { it.write(options.stripJarSignatures) }
        return notes
    }

    private fun applyToFile(workDir: File, target: String, filePatch: FilePatch, options: PatchOptions): List<String> {
        val file = File(workDir, target)
        val newFile = filePatch.newPath?.removePrefix("./")?.let { File(workDir, it) }
        return applyContent(
            target = target,
            exists = file.isFile,
            read = { file.readBytes() },
            filePatch = filePatch,
            options = options,
            missing = { missingFile(workDir, target) },
            write = { bytes ->
                val destination = newFile ?: file
                destination.parentFile.mkdirs()
                destination.writeBytes(bytes)
                if (newFile != null && newFile.canonicalPath != file.canonicalPath) file.delete()
            },
            delete = { file.delete() }
        )
    }

    private fun applyToEntry(jar: JarEdit, entry: String, target: String, filePatch: FilePatch, options: PatchOptions): List<String> {
        val newEntry = filePatch.newPath?.substringAfter("!/", missingDelimiterValue = entry)
        if (newEntry != null && newEntry != entry && !filePatch.isCreation) {
            throw StepFailure(target, "The patch renames an entry inside a jar ($entry -> $newEntry).",
                "Renaming entries inside jars is not supported.", listOf("Split it into a step that deletes the old entry and one that creates the new one."))
        }
        return applyContent(
            target = target,
            exists = jar.has(entry),
            read = { jar.read(entry) },
            filePatch = filePatch,
            options = options,
            missing = { jar.missingEntry(entry, target) },
            write = { bytes -> jar.put(entry, bytes) },
            delete = { jar.remove(entry) }
        )
    }

    private fun applyContent(
        target: String,
        exists: Boolean,
        read: () -> ByteArray,
        filePatch: FilePatch,
        options: PatchOptions,
        missing: () -> StepFailure,
        write: (ByteArray) -> Unit,
        delete: () -> Unit
    ): List<String> {
        if (filePatch.isCreation) {
            if (exists) {
                throw StepFailure(target, "The patch creates this file, but it already exists.",
                    "The vendor added the file in a newer version, or the step was applied to an already patched folder.",
                    listOf("If the vendor now ships the file, check its content and delete the step.",
                        "Make sure the patches are applied to the unpatched vendor folder."))
            }
            val content = TextContent(ArrayList(), endsWithNewline = true, crlf = false)
            HunkApplier.apply(content, filePatch.hunks, filePatch.newEndsWithoutNewline, options.ignoreWhitespace)
            write(content.toBytes())
            return emptyList()
        }
        if (!exists) throw missing()

        val bytes = read()
        if (TextContent.isBinary(bytes)) {
            throw StepFailure(target, "This is a binary file; a text patch cannot change it.",
                "Unified diffs only work on text files such as .properties, .xml, .yml or scripts.",
                listOf("For compiled code, patch the source, compile it and replace the class; class replacement steps are planned.",
                    "Check that the path in the '---' and '+++' lines points at the text file you meant."))
        }
        val content = TextContent.of(bytes)
        val notes = try {
            HunkApplier.apply(content, filePatch.hunks, filePatch.newEndsWithoutNewline, options.ignoreWhitespace)
        } catch (failure: HunkFailure) {
            throw hunkFailure(target, failure, filePatch.hunks.size)
        }
        if (filePatch.isDeletion) {
            if (content.lines.any { it.isNotEmpty() }) {
                throw StepFailure(target, "The patch deletes this file, but the file has content the patch does not know about.",
                    "The file changed since the patch was made, most likely in a vendor upgrade.",
                    listOf("Check the current file and recreate the step, or delete the step if the file should stay."))
            }
            delete()
        } else {
            write(content.toBytes())
        }
        return notes.map { "$target: $it" }
    }

    private fun hunkFailure(target: String, failure: HunkFailure, hunkCount: Int): StepFailure {
        val hunk = failure.hunk
        val expectedBlock = hunk.oldLines
        val location = "hunk ${failure.hunkIndex + 1} of $hunkCount: ${hunk.header}"
        val expectedText = numbered(expectedBlock, failure.expectedAtLine, null)
        return when (failure.reason) {
            HunkCause.ALREADY_APPLIED -> StepFailure(target,
                "$location\nThe change of this hunk is already in the file, at line ${failure.foundAtLine!! + 1}:\n" +
                    numbered(failure.actual, failure.foundAtLine, null),
                "The vendor included this change in a newer version, or the patches ran on an already patched folder.",
                listOf("If the vendor now includes the fix, delete this step.",
                    "If only part of the step is in the vendor files now, remove the hunks that are already there from the step file."))
            HunkCause.WHITESPACE -> StepFailure(target,
                "$location\nThe lines are there at line ${failure.foundAtLine!! + 1}, but spaces, tabs or line endings differ.\n" +
                    "Expected (from the patch):\n$expectedText\nActual file:\n" +
                    numbered(failure.actual, failure.foundAtLine, expectedBlock),
                "The file was reformatted, or the patch was saved with different whitespace or line endings.",
                listOf("Recreate the patch from the current file.",
                    "Or ignore whitespace differences with `vendorPatcher { ignoreWhitespace.set(true) }` in the build script."))
            HunkCause.CHANGED -> {
                val equal = expectedBlock.indices.count { failure.actual.getOrNull(it) == expectedBlock[it] }
                StepFailure(target,
                    "$location\nExpected (from the patch), at line ${failure.expectedAtLine + 1}:\n$expectedText\n" +
                        "Closest match in the file, at line ${failure.foundAtLine!! + 1} ($equal of ${expectedBlock.size} lines equal, '!' marks a difference):\n" +
                        numbered(failure.actual, failure.foundAtLine, expectedBlock),
                    "The file changed since the patch was made, most likely in a vendor upgrade.",
                    listOf("Open the file in the unpatched vendor folder, make the change again by hand and recreate the patch " +
                        "(`diff -u` or `git diff`), keeping the number of the step.",
                        "If the vendor fixed the problem in another way, delete the step."))
            }
            HunkCause.NOT_FOUND -> StepFailure(target,
                "$location\nNone of the lines of this hunk exist in the file.\nExpected (from the patch):\n$expectedText",
                "The patch points at the wrong file, or the file was rewritten.",
                listOf("Check the path in the '---' and '+++' lines of the step file.",
                    "Recreate the patch from the current file."))
        }
    }

    private fun numbered(lines: List<String>, firstIndex: Int, compareWith: List<String>?): String {
        if (lines.isEmpty()) return "    (no lines)"
        return lines.mapIndexed { i, line ->
            val marker = if (compareWith != null && compareWith.getOrNull(i) != line) "!" else " "
            "  $marker ${(firstIndex + i + 1).toString().padStart(5)} | ${display(line)}"
        }.joinToString("\n")
    }

    private fun display(line: String): String = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)

    private fun missingFile(workDir: File, target: String): StepFailure {
        val fixes = ArrayList<String>()
        val segments = target.split('/')
        if (segments.size > 1 && File(workDir, segments.drop(1).joinToString("/")).exists()) {
            fixes += "The paths in the patch have one leading folder too many ('${segments.first()}/'), for example because the patch " +
                "was made from the parent folder. Remove it from the '---' and '+++' lines."
        }
        val name = segments.last()
        val similar = workDir.walkTopDown().filter { it.isFile && it.name == name }
            .map { it.relativeTo(workDir).invariantSeparatorsPath }.take(5).toList()
        if (similar.isNotEmpty()) fixes += "A file with this name exists at: ${similar.joinToString()}. If it moved, update the paths in the step."
        fixes += "If the vendor removed or renamed the file, update or delete the step."
        return StepFailure(target, "The file does not exist in the vendor folder.",
            "The file was moved, renamed or removed, most likely in a vendor upgrade, or the path in the patch is wrong.", fixes)
    }

    internal class StepFailure(
        val target: String?,
        val problem: String,
        val likelyCause: String,
        val fixes: List<String>
    ) : RuntimeException(problem) {
        fun render(step: PatchStep, index: Int, total: Int, applied: List<String>, sourceLabel: String): String = buildString {
            appendLine("Vendor patch step ${index + 1} of $total failed: ${step.name}")
            if (target != null) appendLine("File: $target")
            appendLine()
            appendLine(problem)
            appendLine()
            appendLine("Likely cause: $likelyCause")
            appendLine("How to fix:")
            fixes.forEachIndexed { i, fix -> appendLine("  ${i + 1}. $fix") }
            appendLine()
            appendLine(if (applied.isEmpty()) "No step was applied before this one." else "Applied before this one: ${applied.joinToString()}.")
            append("Nothing was written: $sourceLabel and the previous patched copy are unchanged.")
        }
    }

    private class JarEdit(private val file: File, private val jarPath: String) {
        private val entries = LinkedHashMap<String, ByteArray?>()
        private val times = HashMap<String, java.time.LocalDateTime>()
        private var changed = false

        init {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    entries[entry.name] = if (entry.isDirectory) null else zip.getInputStream(entry).use { it.readBytes() }
                    times[entry.name] = entry.timeLocal
                }
            }
        }

        fun has(entry: String) = entries[entry] != null
        fun read(entry: String): ByteArray = entries[entry]!!
        fun put(entry: String, bytes: ByteArray) { entries[entry] = bytes; changed = true }
        fun remove(entry: String) { entries.remove(entry); changed = true }

        fun missingEntry(entry: String, target: String): StepFailure {
            val name = entry.substringAfterLast('/')
            val similar = entries.filter { it.value != null && it.key.substringAfterLast('/') == name }.keys.take(5)
            val fixes = ArrayList<String>()
            if (similar.isNotEmpty()) fixes += "An entry with this name exists at: ${similar.joinToString()}. If it moved, update the path after '!/' in the step."
            fixes += "List the jar's content with `jar tf $jarPath` and correct the path after '!/'."
            fixes += "If the vendor removed the entry, delete the step."
            return StepFailure(target, "The jar has no entry '$entry'.",
                "The entry was moved or removed in a newer version of the jar, or the path in the patch is wrong.", fixes)
        }

        fun write(stripSignatures: Boolean) {
            if (!changed) return
            val signatures = entries.keys.filter { SIGNATURE.matches(it) }
            if (signatures.isNotEmpty() && !stripSignatures) {
                throw StepFailure(jarPath, "This jar is signed (${signatures.joinToString()}).",
                    "Changing a signed jar breaks its signature, and the JVM refuses to load classes from it.",
                    listOf("Set `vendorPatcher { stripJarSignatures.set(true) }` to remove the signature files from patched jars.",
                        "Or ask the vendor for an unsigned jar."))
            }
            val tmp = File(file.parentFile, "${file.name}.patching")
            ZipOutputStream(tmp.outputStream().buffered(64 * 1024)).use { out ->
                entries.forEach { (name, bytes) ->
                    if (stripSignatures && SIGNATURE.matches(name)) return@forEach
                    val entry = ZipEntry(name)
                    val time = times[name]
                    if (time != null) entry.timeLocal = time else entry.withFixedTime()
                    out.putNextEntry(entry)
                    if (bytes != null) out.write(bytes)
                    out.closeEntry()
                }
            }
            if (!file.delete() || !tmp.renameTo(file)) error("Could not replace $file")
        }

        companion object {
            fun open(workDir: File, jarPath: String, target: String): JarEdit {
                val file = File(workDir, jarPath)
                if (!file.isFile) {
                    val name = jarPath.substringAfterLast('/')
                    val similar = workDir.walkTopDown().filter { it.isFile && it.name == name }
                        .map { it.relativeTo(workDir).invariantSeparatorsPath }.take(5).toList()
                    val fixes = ArrayList<String>()
                    if (similar.isNotEmpty()) fixes += "A jar with this name exists at: ${similar.joinToString()}."
                    val base = name.substringBeforeLast('-', name).removeSuffix(".jar")
                    val versions = workDir.walkTopDown().filter { it.isFile && it.name.startsWith(base) && it.extension.equals("jar", true) }
                        .map { it.relativeTo(workDir).invariantSeparatorsPath }.filter { it !in similar }.take(5).toList()
                    if (versions.isNotEmpty()) fixes += "Jars with a similar name (another version?): ${versions.joinToString()}. After a vendor upgrade, update the jar name in the step."
                    fixes += "If the vendor removed the jar, delete the step."
                    throw StepFailure(target, "The jar '$jarPath' does not exist in the vendor folder.",
                        "The jar was renamed (often a new version number), moved or removed.", fixes)
                }
                return JarEdit(file, jarPath)
            }
        }
    }
}
