/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.patcher

import java.io.File
import java.util.zip.ZipInputStream

internal class WorkspaceChanges {
    val sections = ArrayList<String>()
    val replaced = LinkedHashMap<String, ByteArray>()
    val deleted = ArrayList<String>()
    val described = ArrayList<String>()

    val isEmpty: Boolean get() = sections.isEmpty() && replaced.isEmpty() && deleted.isEmpty()
}

internal data class RefreshResult(val refreshed: List<String>, val unchanged: List<String>, val failure: String?)

internal object PatchWorkspace {
    private val NUMBER = Regex("""^(\d+)""")

    fun compare(baseline: File, workspace: File): WorkspaceChanges {
        val changes = WorkspaceChanges()
        visit(baseline, workspace, "", changes)
        return changes
    }

    fun nextSteps(patchesDir: File, name: String, changes: WorkspaceChanges): List<File> {
        val existing = patchesDir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
            .mapNotNull { NUMBER.find(it.name)?.groupValues?.get(1) }
        var number = (existing.maxOfOrNull { it.toLong() } ?: 0L) + 1
        val width = maxOf(3, existing.maxOfOrNull { it.length } ?: 3)
        fun next(suffix: String): File = File(patchesDir, number.toString().padStart(width, '0') + "-" + name + suffix).also { number++ }

        patchesDir.mkdirs()
        val written = ArrayList<File>()
        if (changes.deleted.isNotEmpty()) {
            written += next(".delete").apply {
                writeText("# Removed by createVendorPatch, one path per line\n" + changes.deleted.joinToString("") { "$it\n" }, Charsets.ISO_8859_1)
            }
        }
        if (changes.sections.isNotEmpty()) {
            written += next(".patch").apply { writeBytes(changes.sections.joinToString("").toByteArray(Charsets.ISO_8859_1)) }
        }
        if (changes.replaced.isNotEmpty()) {
            written += next("").apply {
                changes.replaced.forEach { (path, bytes) -> File(this, path).apply { parentFile.mkdirs() }.writeBytes(bytes) }
            }
        }
        return written
    }

    fun refresh(steps: List<PatchStep>, workDir: File, options: PatchOptions, sourceLabel: String): RefreshResult {
        val refreshed = ArrayList<String>()
        val unchanged = ArrayList<String>()
        steps.forEachIndexed { index, step ->
            val patches = if (step.kind == StepKind.DIFF) {
                try {
                    VendorPatchEngine.parse(step)
                } catch (e: VendorPatchEngine.StepFailure) {
                    return RefreshResult(refreshed, unchanged, e.render(step, index, steps.size, refreshed + unchanged, sourceLabel))
                }
            } else null
            val before = patches?.flatMap { listOfNotNull(it.oldPath, it.newPath) }?.distinct()?.associateWith { read(workDir, it) }
            try {
                VendorPatchEngine.applyStep(step, workDir, options)
            } catch (e: VendorPatchEngine.StepFailure) {
                return RefreshResult(refreshed, unchanged, e.render(step, index, steps.size, refreshed + unchanged, sourceLabel))
            }
            if (patches == null || before == null || patches.all { exact(it, before) }) {
                unchanged += step.name
                return@forEachIndexed
            }
            val sections = patches.map { patch ->
                val old = patch.oldPath?.let { before[it] }
                val new = patch.newPath?.let { read(workDir, it) }
                TextDiff.section(patch.oldPath, patch.newPath, old, new)?.takeIf { TextDiff.reproduces(it, old, new) }
            }
            if (sections.any { it == null }) {
                unchanged += step.name
                return@forEachIndexed
            }
            val text = String(step.file.readBytes(), Charsets.ISO_8859_1)
            val preamble = text.lineSequence().takeWhile { !it.startsWith("--- ") && !it.startsWith("diff --git ") }
                .joinToString("") { "$it\n" }
            step.file.writeBytes((preamble + sections.joinToString("")).toByteArray(Charsets.ISO_8859_1))
            refreshed += step.name
        }
        return RefreshResult(refreshed, unchanged, null)
    }

    fun read(workDir: File, target: String): ByteArray? {
        val split = target.indexOf("!/")
        if (split < 0) return File(workDir, target).takeIf { it.isFile }?.readBytes()
        val jar = File(workDir, target.substring(0, split)).takeIf { it.isFile } ?: return null
        return entries(jar.readBytes())[target.substring(split + 2)]
    }

    private fun exact(patch: FilePatch, before: Map<String, ByteArray?>): Boolean {
        val old = patch.oldPath?.let { before[it] }
        if (patch.oldPath != null && old == null) return false
        val content = old?.let { TextContent.of(it) } ?: TextContent(ArrayList(), endsWithNewline = true, crlf = false)
        return try {
            HunkApplier.apply(content, patch.hunks, patch.newEndsWithoutNewline, ignoreWhitespace = false).isEmpty()
        } catch (e: HunkFailure) {
            false
        }
    }

    private fun visit(baseline: File, workspace: File, relative: String, changes: WorkspaceChanges) {
        val names = (children(File(baseline, relative)) + children(File(workspace, relative))).toSortedSet()
        for (name in names) {
            val path = if (relative.isEmpty()) name else "$relative/$name"
            val old = File(baseline, path)
            val new = File(workspace, path)
            when {
                old.isDirectory && new.isDirectory -> visit(baseline, workspace, path, changes)
                !new.exists() -> removed(path, old, changes)
                !old.exists() || old.isDirectory != new.isDirectory -> replacedEntry(path, old, new, changes)
                else -> changed(path, old.readBytes(), new.readBytes(), changes)
            }
        }
    }

    private fun deletedPath(path: String, old: File) = if (old.isDirectory) "$path/" else path

    private fun removed(path: String, old: File, changes: WorkspaceChanges) {
        changes.deleted += deletedPath(path, old)
        changes.described += "removed $path"
    }

    private fun replacedEntry(path: String, old: File, new: File, changes: WorkspaceChanges) {
        if (old.exists()) changes.deleted += deletedPath(path, old)
        if (!new.isDirectory) {
            added(path, new.readBytes(), changes)
            return
        }
        new.walkTopDown().onEnter { it == new || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") }
            .forEach { added("$path/${it.relativeTo(new).invariantSeparatorsPath}", it.readBytes(), changes) }
    }

    private fun children(dir: File): List<String> =
        dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }.map { it.name }

    private fun added(path: String, bytes: ByteArray, changes: WorkspaceChanges) {
        changes.described += "added $path"
        if (bytes.isNotEmpty() && TextDiff.isText(bytes)) {
            val section = TextDiff.section(null, path, null, bytes)
            if (section != null && TextDiff.reproduces(section, null, bytes)) {
                changes.sections += section
                return
            }
        }
        changes.replaced[path] = bytes
    }

    private fun changed(path: String, old: ByteArray, new: ByteArray, changes: WorkspaceChanges) {
        if (old.contentEquals(new)) return
        if (path.lowercase().endsWith(".jar") && isZip(old) && isZip(new)) {
            changedJar(path, entries(old), entries(new), changes)
            return
        }
        changes.described += "changed $path"
        text(path, path, old, new, changes, path)
    }

    private fun changedJar(path: String, old: Map<String, ByteArray>, new: Map<String, ByteArray>, changes: WorkspaceChanges) {
        for (entry in (old.keys + new.keys).toSortedSet()) {
            val before = old[entry]
            val after = new[entry]
            val target = "$path!/$entry"
            when {
                before != null && after != null && before.contentEquals(after) -> Unit
                after == null -> {
                    changes.deleted += target
                    changes.described += "removed $target"
                }
                before == null -> {
                    changes.described += "added $target"
                    if (after.isNotEmpty() && TextDiff.isText(after)) {
                        val section = TextDiff.section(null, target, null, after)
                        if (section != null && TextDiff.reproduces(section, null, after)) {
                            changes.sections += section
                            continue
                        }
                    }
                    changes.replaced["$path/$entry"] = after
                }
                else -> {
                    changes.described += "changed $target"
                    text(target, target, before, after, changes, "$path/$entry")
                }
            }
        }
    }

    private fun text(oldPath: String, newPath: String, old: ByteArray, new: ByteArray, changes: WorkspaceChanges, replacePath: String) {
        if (TextDiff.isText(old) && TextDiff.isText(new)) {
            val section = TextDiff.section(oldPath, newPath, old, new)
            if (section != null && TextDiff.reproduces(section, old, new)) {
                changes.sections += section
                return
            }
        }
        changes.replaced[replacePath] = new
    }

    private fun isZip(bytes: ByteArray) = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
            }
        }
        return result
    }
}
