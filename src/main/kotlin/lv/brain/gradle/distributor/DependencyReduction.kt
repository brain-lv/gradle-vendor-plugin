/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import java.util.BitSet

internal data class RedundantEdge(val from: String, val to: String, val via: String)

internal class ReducedGraph(
    val edges: Map<String, LinkedHashSet<String>>,
    val redundant: List<RedundantEdge>
)

internal object DependencyReduction {
    fun contract(edges: Map<String, Set<String>>, keep: Set<String>): Map<String, LinkedHashSet<String>> {
        val result = LinkedHashMap<String, LinkedHashSet<String>>()
        for (from in edges.keys.filter { it in keep }) {
            val targets = LinkedHashSet<String>()
            val seen = HashSet<String>()
            val stack = ArrayDeque(edges[from].orEmpty().sorted().reversed())
            while (stack.isNotEmpty()) {
                val next = stack.removeLast()
                if (!seen.add(next) || next == from) continue
                if (next in keep) targets.add(next) else edges[next].orEmpty().sorted().reversed().forEach { stack.addLast(it) }
            }
            result[from] = targets.sorted().toCollection(LinkedHashSet())
        }
        return result
    }

    fun reduce(edges: Map<String, Set<String>>, order: List<String>): ReducedGraph {
        val index = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { i, id -> index[id] = i }

        val reach = arrayOfNulls<BitSet>(order.size)
        val reduced = LinkedHashMap<String, LinkedHashSet<String>>()
        val redundant = ArrayList<RedundantEdge>()

        for (id in order) {
            val direct = edges[id].orEmpty().filter { it in index }
            val covered = BitSet()
            for (dep in direct) covered.or(reach[index.getValue(dep)] ?: BitSet())

            val kept = LinkedHashSet<String>()
            for (dep in direct) {
                if (!covered.get(index.getValue(dep))) {
                    kept.add(dep)
                } else {
                    val via = direct.filter { other -> reach[index.getValue(other)]?.get(index.getValue(dep)) == true }.min()
                    redundant.add(RedundantEdge(id, dep, via))
                }
            }
            reduced[id] = kept

            val all = covered.clone() as BitSet
            for (dep in direct) all.set(index.getValue(dep))
            reach[index.getValue(id)] = all
        }
        return ReducedGraph(reduced, redundant)
    }
}
