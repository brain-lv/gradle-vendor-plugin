/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

internal data class BrokenCycle(val path: List<String>, val removedFrom: String, val removedTo: String) {
    val edges: List<Pair<String, String>> get() = path.zipWithNext()
}

internal object DependencyCycles {
    fun breakCycles(
        graph: Map<String, LinkedHashSet<String>>,
        strength: EdgeStrength
    ): Pair<Map<String, LinkedHashSet<String>>, List<BrokenCycle>> {
        val result = graph.mapValuesTo(LinkedHashMap()) { LinkedHashSet(it.value) }
        val broken = ArrayList<BrokenCycle>()
        while (true) {
            val cycle = CycleFinder.find(result) ?: break
            val removed = cycle.zipWithNext().minWith(compareBy<Pair<String, String>>({ strength.of(it.first, it.second) }, { it.first }, { it.second }))
            result.getValue(removed.first).remove(removed.second)
            broken.add(BrokenCycle(cycle, removed.first, removed.second))
        }
        return result to broken
    }

    fun topologicalSort(dag: Map<String, Set<String>>): List<String> {
        val visited = HashSet<String>()
        val order = ArrayList<String>(dag.size)

        fun visit(node: String) {
            if (!visited.add(node)) return
            for (dep in dag[node].orEmpty()) visit(dep)
            order.add(node)
        }

        for (node in dag.keys) visit(node)
        return order
    }
}

internal object CycleFinder {
    fun find(graph: Map<String, Set<String>>): List<String>? {
        val visited = HashSet<String>()
        val onPath = HashSet<String>()
        val path = ArrayList<String>()

        fun dfs(node: String): List<String>? {
            visited.add(node)
            onPath.add(node)
            path.add(node)
            for (target in graph[node].orEmpty().sorted()) {
                if (target in onPath) return path.subList(path.indexOf(target), path.size) + target
                if (target !in visited && target in graph) dfs(target)?.let { return it }
            }
            path.removeAt(path.lastIndex)
            onPath.remove(node)
            return null
        }

        for (node in graph.keys.sorted()) {
            if (node !in visited) dfs(node)?.let { return it }
        }
        return null
    }
}
