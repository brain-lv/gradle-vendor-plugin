/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.patcher

import lv.brain.gradle.core.ReproducibleEntries.withFixedTime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class PatchOptions(val ignoreWhitespace: Boolean = false, val stripJarSignatures: Boolean = false)

internal enum class StepKind { DIFF, FOLDER, DELETE }

internal data class PatchStep(val number: Long, val file: File) {
    val name: String get() = file.name
    val kind: StepKind
        get() = when {
            file.isDirectory -> StepKind.FOLDER
            file.name.lowercase().endsWith(".delete") -> StepKind.DELETE
            else -> StepKind.DIFF
        }
}

internal data class PatchResult(val applied: List<String>, val notes: List<String>, val warnings: List<String> = emptyList())

internal enum class StepOutcome { APPLIED, MOVED, ALREADY_APPLIED, FAILED }

internal data class StepStatus(val step: String, val outcome: StepOutcome, val detail: String)

internal class PatchException(message: String) : RuntimeException(message)

internal object VendorPatchEngine {
    private val NUMBERED = Regex("""^(\d+)(?:[-_. ].*)?$""")
    private val SUPPORTED = listOf(".patch", ".diff", ".delete")
    private val SIGNATURE = Regex("""^META-INF/[^/]+\.(SF|RSA|DSA|EC)$""", RegexOption.IGNORE_CASE)
    private val VERSIONED = Regex("""^META-INF/versions/\d+/""")
    const val IGNORED_TOP_LEVEL_DIR = "ivy"
    const val SUPPORTED_TEXT = "Supported steps: .patch and .diff files (unified diff), .delete files (paths to remove) and " +
        "folders (files and classes to add or replace)."

    fun discover(patchesDir: File, warn: (String) -> Unit = {}): List<PatchStep> {
        if (!patchesDir.isDirectory) return emptyList()
        val numbered = patchesDir.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .mapNotNull { file -> NUMBERED.matchEntire(file.name)?.let { PatchStep(it.groupValues[1].toLong(), file) } }
            .sortedWith(compareBy({ it.number }, { it.name }))

        val problems = ArrayList<String>()
        numbered.groupBy { it.number }.filterValues { it.size > 1 }.forEach { (number, steps) ->
            problems += "Steps share the number $number: ${steps.joinToString { it.name }}. Every step needs its own number; renumber one of them."
        }
        numbered.forEach { step ->
            val name = step.name.lowercase()
            when {
                step.file.isDirectory || SUPPORTED.any { name.endsWith(it) } -> Unit
                name.endsWith(".gradle") || name.endsWith(".gradle.kts") -> problems +=
                    "Step ${step.name}: Gradle script steps are not supported, because a script could change anything and the result " +
                        "could neither be checked nor cached. Put replaced files and classes into a folder step, list removed files " +
                        "in a .delete step, and change text with a .patch step."
                else -> problems +=
                    "Step ${step.name}: unknown step type. $SUPPORTED_TEXT " +
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
        val warnings = ArrayList<String>()
        steps.forEachIndexed { index, step ->
            try {
                val outcome = applyStep(step, workDir, options)
                notes += outcome.notes.map { "${step.name}: $it" }
                warnings += outcome.warnings.map { "${step.name}: $it" }
            } catch (e: StepFailure) {
                throw PatchException(e.render(step, index, steps.size, applied, sourceLabel))
            }
            applied += step.name
        }
        return PatchResult(applied, notes, warnings)
    }

    fun status(steps: List<PatchStep>, workDir: File, options: PatchOptions): List<StepStatus> = steps.map { step ->
        try {
            val outcome = applyStep(step, workDir, options)
            when {
                outcome.notes.isNotEmpty() -> StepStatus(step.name, StepOutcome.MOVED, outcome.notes.joinToString("; "))
                else -> StepStatus(step.name, StepOutcome.APPLIED, outcome.warnings.joinToString("; "))
            }
        } catch (e: StepFailure) {
            val where = e.target?.let { "$it: " }.orEmpty()
            if (e.alreadyApplied) StepStatus(step.name, StepOutcome.ALREADY_APPLIED, where + "the change is already in the vendor files")
            else StepStatus(step.name, StepOutcome.FAILED, where + e.problem.lineSequence().last { it.isNotBlank() }.trim())
        }
    }

    internal class StepOutcomeDetails(val notes: List<String>, val warnings: List<String>)

    internal fun applyStep(step: PatchStep, workDir: File, options: PatchOptions): StepOutcomeDetails {
        val changes = StepChanges(workDir)
        val notes = ArrayList<String>()
        val warnings = ArrayList<String>()
        when (step.kind) {
            StepKind.DIFF -> notes += applyDiff(step, changes, options)
            StepKind.FOLDER -> applyFolder(step, changes, notes, warnings)
            StepKind.DELETE -> applyDelete(step, changes)
        }
        changes.commit(options.stripJarSignatures)
        return StepOutcomeDetails(notes, warnings)
    }

    fun parse(step: PatchStep): List<FilePatch> {
        val text = String(step.file.readBytes(), Charsets.ISO_8859_1)
        return try {
            UnifiedDiff.parse(text)
        } catch (e: MalformedPatchException) {
            throw StepFailure(
                target = null,
                problem = "The step file is not a valid unified diff: ${e.message}",
                likelyCause = "The file was edited by hand, cut off, or made by a tool that does not write unified diffs.",
                fixes = listOf("Create patches with `diff -u <original> <changed>`, `git diff` or the createVendorPatch task, and keep the number of the step.")
            )
        }
    }

    private fun applyDiff(step: PatchStep, changes: StepChanges, options: PatchOptions): List<String> {
        val notes = ArrayList<String>()
        for (filePatch in parse(step)) {
            val target = filePatch.targetPath.removePrefix("./")
            val jarSplit = target.indexOf("!/")
            if (jarSplit > 0) {
                val jarPath = target.substring(0, jarSplit)
                val entry = target.substring(jarSplit + 2)
                notes += applyToEntry(changes.jar(jarPath, target), entry, target, filePatch, options)
            } else {
                notes += applyToFile(changes, target, filePatch, options)
            }
        }
        return notes
    }

    private fun applyFolder(step: PatchStep, changes: StepChanges, notes: MutableList<String>, warnings: MutableList<String>) {
        val files = folderFiles(step.file)
        if (files.isEmpty()) {
            throw StepFailure(null, "The folder step is empty.", "Its files were not added yet, or only hidden files are in it.",
                listOf("Put the files to add or replace into the folder, at their path in the vendor folder, or delete the step."))
        }
        val replaced = files.count { (path, file) -> putFolderFile(path, file.readBytes(), changes, warnings) }
        notes += "$replaced file(s) replaced, ${files.size - replaced} added"
    }

    internal fun folderFiles(folder: File): List<Pair<String, File>> = folder.walkTopDown()
        .onEnter { it == folder || !it.name.startsWith(".") }
        .filter { it.isFile && !it.name.startsWith(".") }
        .map { it.relativeTo(folder).invariantSeparatorsPath to it }
        .sortedBy { it.first }
        .toList()

    private fun putFolderFile(path: String, bytes: ByteArray, changes: StepChanges, warnings: MutableList<String>): Boolean {
        val jarPath = changes.jarPrefixOf(path)
        if (jarPath != null) {
            val entry = path.removePrefix("$jarPath/")
            val target = "$jarPath!/$entry"
            val jar = changes.jar(jarPath, target)
            if (entry.endsWith(".class")) checkClass(target, entry, bytes, jar.highestClassVersion(), warnings)
            return jar.has(entry).also { jar.put(entry, bytes) }
        }
        if (changes.isDirectory(path)) {
            throw StepFailure(path, "The folder step has a file where the vendor folder has a folder.",
                "A file of the step has the name of a folder of the vendor folder.",
                listOf("Move the file into that folder, or rename it."))
        }
        return changes.isFile(path).also { changes.write(path, bytes) }
    }

    private fun applyDelete(step: PatchStep, changes: StepChanges) {
        val paths = step.file.readLines(Charsets.ISO_8859_1).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (paths.isEmpty()) {
            throw StepFailure(null, "The .delete step lists no path.", "The file is empty or has only comments.",
                listOf("List one path per line, relative to the vendor folder; jar/path!/entry removes an entry of a jar."))
        }
        for (raw in paths) {
            val target = raw.removePrefix("./")
            val jarSplit = target.indexOf("!/")
            if (jarSplit > 0) {
                val jar = changes.jar(target.substring(0, jarSplit), target)
                val entry = target.substring(jarSplit + 2)
                if (!jar.removeAll(entry)) throw jar.missingEntry(entry, target)
            } else if (!changes.delete(target.removeSuffix("/"))) {
                throw missingFile(changes.workDir, target.removeSuffix("/"))
            }
        }
    }

    private fun checkClass(target: String, entry: String, bytes: ByteArray, highest: Int?, warnings: MutableList<String>) {
        val reader = try {
            org.objectweb.asm.ClassReader(bytes)
        } catch (e: RuntimeException) {
            throw StepFailure(target, "This is not a compiled class file.", "The file was not produced by a Java compiler, or it is damaged.",
                listOf("Copy the .class file from the compiler output (build/classes) into the step."))
        }
        val expected = VERSIONED.replace(entry, "").removeSuffix(".class")
        if (reader.className != expected && entry.contains('/')) {
            throw StepFailure(target, "The class file holds ${reader.className.replace('/', '.')}, but its path stands for ${expected.replace('/', '.')}.",
                "The class was put at the wrong path, so the JVM would refuse to load it.",
                listOf("Put the file at ${reader.className}.class, the path of its package and name."))
        }
        val major = reader.readUnsignedShort(6)
        if (highest != null && major > highest) {
            warnings += "$target needs Java ${major - 44}, but the other classes of the jar run on Java ${highest - 44}. Compile it with " +
                "--release ${highest - 44}, or the class fails to load on the older Java."
        }
    }

    private interface PatchTarget {
        val exists: Boolean
        fun read(): ByteArray
        fun missing(): StepFailure
        fun write(bytes: ByteArray)
        fun delete()
    }

    private fun applyToFile(changes: StepChanges, target: String, filePatch: FilePatch, options: PatchOptions): List<String> {
        val destination = filePatch.newPath?.removePrefix("./") ?: target
        val file = object : PatchTarget {
            override val exists = changes.isFile(target)
            override fun read() = changes.read(target) ?: throw missing()
            override fun missing() = missingFile(changes.workDir, target)
            override fun write(bytes: ByteArray) {
                changes.write(destination, bytes)
                if (destination != target) changes.delete(target)
            }
            override fun delete() {
                changes.delete(target)
            }
        }
        return applyContent(target, file, filePatch, options)
    }

    private fun applyToEntry(jar: JarEdit, entry: String, target: String, filePatch: FilePatch, options: PatchOptions): List<String> {
        val newEntry = filePatch.newPath?.substringAfter("!/", missingDelimiterValue = entry)
        if (newEntry != null && newEntry != entry && !filePatch.isCreation) {
            throw StepFailure(target, "The patch renames an entry inside a jar ($entry -> $newEntry).",
                "Renaming entries inside jars is not supported.", listOf("Split it into a step that deletes the old entry and one that creates the new one."))
        }
        val jarEntry = object : PatchTarget {
            override val exists = jar.has(entry)
            override fun read() = jar.read(entry)
            override fun missing() = jar.missingEntry(entry, target)
            override fun write(bytes: ByteArray) = jar.put(entry, bytes)
            override fun delete() = jar.remove(entry)
        }
        return applyContent(target, jarEntry, filePatch, options)
    }

    private fun applyContent(target: String, file: PatchTarget, filePatch: FilePatch, options: PatchOptions): List<String> {
        if (filePatch.isCreation) {
            createContent(target, file, filePatch, options)
            return emptyList()
        }
        if (!file.exists) throw file.missing()
        val bytes = file.read()
        if (TextContent.isBinary(bytes)) {
            throw StepFailure(target, "This is a binary file; a text patch cannot change it.",
                "Unified diffs only work on text files such as .properties, .xml, .yml or scripts.",
                listOf("To replace a binary file or a compiled class, put the new file into a folder step at the same path.",
                    "Check that the path in the '---' and '+++' lines points at the text file you meant."))
        }
        val content = TextContent.of(bytes)
        val notes = try {
            HunkApplier.apply(content, filePatch.hunks, filePatch.newEndsWithoutNewline, options.ignoreWhitespace)
        } catch (failure: HunkFailure) {
            throw hunkFailure(target, failure, filePatch.hunks.size)
        }
        if (!filePatch.isDeletion) {
            file.write(content.toBytes())
        } else if (content.lines.any { it.isNotEmpty() }) {
            throw StepFailure(target, "The patch deletes this file, but the file has content the patch does not know about.",
                "The file changed since the patch was made, most likely in a vendor upgrade.",
                listOf("Check the current file and recreate the step, or delete the step if the file should stay."))
        } else {
            file.delete()
        }
        return notes.map { "$target: $it" }
    }

    private fun createContent(target: String, file: PatchTarget, filePatch: FilePatch, options: PatchOptions) {
        if (file.exists) {
            throw StepFailure(alreadyApplied = true, target = target, problem = "The patch creates this file, but it already exists.", likelyCause =
                "The vendor added the file in a newer version, or the step was applied to an already patched folder.",
                fixes = listOf("If the vendor now ships the file, check its content and delete the step.",
                    "Make sure the patches are applied to the unpatched vendor folder."))
        }
        val content = TextContent(ArrayList(), endsWithNewline = true, crlf = false)
        HunkApplier.apply(content, filePatch.hunks, filePatch.newEndsWithoutNewline, options.ignoreWhitespace)
        file.write(content.toBytes())
    }

    private fun hunkFailure(target: String, failure: HunkFailure, hunkCount: Int): StepFailure {
        val hunk = failure.hunk
        val expectedBlock = hunk.oldLines
        val location = "hunk ${failure.hunkIndex + 1} of $hunkCount: ${hunk.header}"
        val expectedText = numbered(expectedBlock, failure.expectedAtLine, null)
        return when (failure.reason) {
            HunkCause.ALREADY_APPLIED -> StepFailure(alreadyApplied = true, target = target, problem =
                "$location\nThe change of this hunk is already in the file, at line ${failure.foundAtLine!! + 1}:\n" +
                    numbered(failure.actual, failure.foundAtLine, null),
                likelyCause = "The vendor included this change in a newer version, or the patches ran on an already patched folder.",
                fixes = listOf("If the vendor now includes the fix, delete this step.",
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
        val fixes: List<String>,
        val alreadyApplied: Boolean = false
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

    internal class StepChanges(val workDir: File) {
        private val files = LinkedHashMap<String, ByteArray?>()
        private val deletedDirs = ArrayList<String>()
        private val jars = LinkedHashMap<String, JarEdit>()

        private fun deleted(path: String) = deletedDirs.any { path == it || path.startsWith("$it/") }

        fun isFile(path: String): Boolean =
            if (path in files) files[path] != null else !deleted(path) && File(workDir, path).isFile

        fun isDirectory(path: String): Boolean = !deleted(path) && File(workDir, path).isDirectory

        fun read(path: String): ByteArray? = if (path in files) files[path] else File(workDir, path).takeIf { isFile(path) }?.readBytes()

        fun write(path: String, bytes: ByteArray) {
            files[path] = bytes
            jars.remove(path)
        }

        fun delete(path: String): Boolean = when {
            isFile(path) -> { files[path] = null; jars.remove(path); true }
            isDirectory(path) -> {
                deletedDirs += path
                files.keys.filter { it.startsWith("$path/") }.forEach { files[it] = null }
                jars.keys.filter { it.startsWith("$path/") }.forEach { jars.remove(it) }
                true
            }
            else -> false
        }

        fun jar(jarPath: String, target: String): JarEdit = jars.getOrPut(jarPath) {
            if (!isFile(jarPath)) throw JarEdit.missingJar(workDir, jarPath, target)
            JarEdit(File(workDir, jarPath), jarPath, read(jarPath)!!)
        }

        fun jarPrefixOf(path: String): String? {
            val segments = path.split('/')
            for (i in 1 until segments.size) {
                val prefix = segments.subList(0, i).joinToString("/")
                if (prefix.lowercase().endsWith(".jar") && isFile(prefix)) return prefix
            }
            return null
        }

        fun commit(stripSignatures: Boolean) {
            jars.values.forEach { it.checkWritable(stripSignatures) }
            deletedDirs.forEach { File(workDir, it).deleteRecursively() }
            files.forEach { (path, bytes) ->
                val file = File(workDir, path)
                if (bytes == null) java.nio.file.Files.deleteIfExists(file.toPath()) else file.apply { parentFile.mkdirs() }.writeBytes(bytes)
            }
            jars.values.forEach { it.write(stripSignatures) }
        }
    }

    internal class JarEdit(private val file: File, private val jarPath: String, bytes: ByteArray) {
        private val entries = LinkedHashMap<String, ByteArray?>()
        private val times = HashMap<String, java.time.LocalDateTime>()
        private var changed = false

        init {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = if (entry.isDirectory) null else zip.readBytes()
                    times[entry.name] = entry.timeLocal
                }
            }
        }

        fun has(entry: String) = entries[entry] != null
        fun read(entry: String): ByteArray = checkNotNull(entries[entry]) { "$jarPath has no entry $entry" }
        fun put(entry: String, bytes: ByteArray) { entries[entry] = bytes; changed = true }
        fun remove(entry: String) { entries.remove(entry); changed = true }

        fun removeAll(entry: String): Boolean {
            val folder = entry.removeSuffix("/") + "/"
            val names = entries.keys.filter { it == entry || it.startsWith(folder) }
            names.forEach { entries.remove(it) }
            if (names.isNotEmpty()) changed = true
            return names.isNotEmpty()
        }

        fun highestClassVersion(): Int? = entries.filter { (name, bytes) ->
            bytes != null && name.endsWith(".class") && !name.startsWith("META-INF/") && bytes.size > 8
        }.values.maxOfOrNull { ((it!![6].toInt() and 0xff) shl 8) or (it[7].toInt() and 0xff) }

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

        fun checkWritable(stripSignatures: Boolean) {
            if (!changed) return
            val signatures = entries.keys.filter { SIGNATURE.matches(it) }
            if (signatures.isNotEmpty() && !stripSignatures) {
                throw StepFailure(jarPath, "This jar is signed (${signatures.joinToString()}).",
                    "Changing a signed jar breaks its signature, and the JVM refuses to load classes from it.",
                    listOf("Set `vendorPatcher { stripJarSignatures.set(true) }` to remove the signature files from patched jars.",
                        "Or ask the vendor for an unsigned jar."))
            }
        }

        fun write(stripSignatures: Boolean) {
            if (!changed) return
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
            fun missingJar(workDir: File, jarPath: String, target: String): StepFailure {
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
                return StepFailure(target, "The jar '$jarPath' does not exist in the vendor folder.",
                    "The jar was renamed (often a new version number), moved or removed.", fixes)
            }
        }
    }
}
