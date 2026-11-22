/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.patcher

internal object TextDiff {
    private const val NO_NEWLINE = "\\ No newline at end of file"

    fun isText(bytes: ByteArray): Boolean = !TextContent.isBinary(bytes)

    fun section(oldPath: String?, newPath: String?, old: ByteArray?, new: ByteArray?, context: Int = 3): String? {
        val oldText = content(old)
        val newText = content(new)
        val ops = edits(keys(oldText), keys(newText))
        if (ops.none { it.kind != ' ' }) return null
        val body = StringBuilder()
        body.append("--- ").append(oldPath?.let { "a/$it" } ?: "/dev/null").append('\n')
        body.append("+++ ").append(newPath?.let { "b/$it" } ?: "/dev/null").append('\n')
        for (hunk in hunks(ops, context)) appendHunk(body, ops.subList(hunk.first, hunk.last + 1), oldText, newText)
        return body.toString()
    }

    private fun content(bytes: ByteArray?): TextContent =
        bytes?.let { TextContent.of(it) } ?: TextContent(ArrayList(), endsWithNewline = true, crlf = false)

    private fun appendHunk(body: StringBuilder, slice: List<Op>, oldText: TextContent, newText: TextContent) {
        val first = slice.first()
        val oldCount = slice.count { it.kind != '+' }
        val newCount = slice.count { it.kind != '-' }
        val oldStart = if (oldCount == 0) first.oldIndex else first.oldIndex + 1
        val newStart = if (newCount == 0) first.newIndex else first.newIndex + 1
        body.append("@@ -").append(range(oldStart, oldCount)).append(" +").append(range(newStart, newCount)).append(" @@\n")
        for (op in slice) {
            val text = if (op.kind == '+') newText.lines[op.newIndex] else oldText.lines[op.oldIndex]
            body.append(op.kind).append(text).append('\n')
            if (endsWithoutNewline(op, oldText, newText)) body.append(NO_NEWLINE).append('\n')
        }
    }

    private fun endsWithoutNewline(op: Op, oldText: TextContent, newText: TextContent): Boolean {
        val lastOld = op.kind != '+' && op.oldIndex == oldText.lines.lastIndex && !oldText.endsWithNewline
        val lastNew = op.kind != '-' && op.newIndex == newText.lines.lastIndex && !newText.endsWithNewline
        return lastOld || lastNew
    }

    fun reproduces(section: String, old: ByteArray?, new: ByteArray?): Boolean = try {
        val patch = UnifiedDiff.parse(section).single()
        val content = if (old == null) TextContent(ArrayList(), endsWithNewline = true, crlf = false) else TextContent.of(old)
        HunkApplier.apply(content, patch.hunks, patch.newEndsWithoutNewline, ignoreWhitespace = false).isEmpty() &&
            new != null && content.toBytes().contentEquals(new)
    } catch (e: RuntimeException) {
        false
    }

    private fun range(start: Int, count: Int) = if (count == 1) "$start" else "$start,$count"

    private fun keys(text: TextContent): List<String> = text.lines.mapIndexed { i, line ->
        if (i == text.lines.lastIndex && !text.endsWithNewline) line + "\u0000" else line
    }

    private class Op(val kind: Char, val oldIndex: Int, val newIndex: Int)

    private fun hunks(ops: List<Op>, context: Int): List<IntRange> {
        val changes = ops.indices.filter { ops[it].kind != ' ' }
        val result = ArrayList<IntRange>()
        var start = -1
        var end = -1
        for (index in changes) {
            if (start >= 0 && index - end > 2 * context) {
                result += maxOf(0, start - context)..minOf(ops.lastIndex, end + context)
                start = -1
            }
            if (start < 0) start = index
            end = index
        }
        if (start >= 0) result += maxOf(0, start - context)..minOf(ops.lastIndex, end + context)
        return result
    }

    private fun edits(a: List<String>, b: List<String>): List<Op> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val ops = ArrayList<Op>(a.size + b.size)
        for (i in 0 until prefix) ops += Op(' ', i, i)
        ops += middle(a.subList(prefix, a.size - suffix), b.subList(prefix, b.size - suffix), prefix, prefix)
        for (k in suffix downTo 1) ops += Op(' ', a.size - k, b.size - k)
        return ops
    }

    private fun middle(a: List<String>, b: List<String>, aOffset: Int, bOffset: Int): List<Op> {
        if (a.isEmpty()) return b.indices.map { Op('+', aOffset, bOffset + it) }
        if (b.isEmpty()) return a.indices.map { Op('-', aOffset + it, bOffset) }
        val trace = MyersSearch(a, b).run()
        return deletesFirst(backtrack(trace, a.size, b.size, aOffset, bOffset).asReversed())
    }

    private class MyersSearch(private val a: List<String>, private val b: List<String>) {
        private val max = a.size + b.size
        private val v = IntArray(2 * max + 2)

        fun run(): List<IntArray> {
            val trace = ArrayList<IntArray>()
            for (d in 0..max) {
                trace += v.copyOfRange(max - d, max + d + 2)
                if (step(d)) break
            }
            return trace
        }

        private fun step(d: Int): Boolean {
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[max + k - 1] < v[max + k + 1])) v[max + k + 1] else v[max + k - 1] + 1
                var y = x - k
                while (x < a.size && y < b.size && a[x] == b[y]) { x++; y++ }
                v[max + k] = x
                if (x >= a.size && y >= b.size) return true
            }
            return false
        }
    }

    private fun backtrack(trace: List<IntArray>, n: Int, m: Int, aOffset: Int, bOffset: Int): List<Op> {
        val reversed = ArrayList<Op>()
        var x = n
        var y = m
        for (d in trace.lastIndex downTo 0) {
            val row = trace[d]
            val k = x - y
            val down = k == -d || (k != d && row[k - 1 + d] < row[k + 1 + d])
            val prevK = if (down) k + 1 else k - 1
            val prevX = if (d == 0) 0 else row[prevK + d]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { x--; y--; reversed += Op(' ', aOffset + x, bOffset + y) }
            if (d == 0) continue
            if (x == prevX) { y--; reversed += Op('+', aOffset + x, bOffset + y) } else { x--; reversed += Op('-', aOffset + x, bOffset + y) }
        }
        return reversed
    }

    private fun deletesFirst(forward: List<Op>): List<Op> {
        val ordered = ArrayList<Op>(forward.size)
        val block = ArrayList<Op>()
        fun flush() {
            ordered += block.filter { it.kind == '-' }
            ordered += block.filter { it.kind == '+' }
            block.clear()
        }
        for (op in forward) {
            if (op.kind == ' ') { flush(); ordered += op } else block += op
        }
        flush()
        return ordered
    }
}
