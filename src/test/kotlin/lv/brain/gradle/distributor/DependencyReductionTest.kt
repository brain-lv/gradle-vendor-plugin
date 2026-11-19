/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// 1 depends on 2, 3 and 4, 2 depends on 4, 3 depends on 4: the jar 1 does not need 4 as a direct dependency,
// it is brought in by 2 and 3 anyway.
class DependencyReductionTest {

    @TempDir
    lateinit var tempDir: File

    private fun reduce(edges: Map<String, List<String>>, order: List<String>) =
        DependencyReduction.reduce(edges.mapValues { it.value.toSet() }, order)

    @Test
    fun `a dependency that another dependency already brings in is dropped`() {
        val result = reduce(
            mapOf("1" to listOf("2", "3", "4"), "2" to listOf("4"), "3" to listOf("4"), "4" to emptyList()),
            order = listOf("4", "3", "2", "1")
        )

        assertEquals(listOf("2", "3"), result.edges.getValue("1").toList())
        assertEquals(listOf("4"), result.edges.getValue("2").toList())
        assertEquals(listOf("4"), result.edges.getValue("3").toList())
        assertEquals(listOf(RedundantEdge("1", "4", via = "2")), result.redundant)
    }

    @Test
    fun `longer paths count too and independent dependencies stay`() {
        // a -> b -> c -> d, a -> d (covered by the long path), a -> x (nothing else reaches x)
        val result = reduce(
            mapOf("a" to listOf("b", "d", "x"), "b" to listOf("c"), "c" to listOf("d"), "d" to emptyList(), "x" to emptyList()),
            order = listOf("d", "c", "b", "x", "a")
        )

        assertEquals(listOf("b", "x"), result.edges.getValue("a").toList())
        assertEquals(listOf(RedundantEdge("a", "d", via = "b")), result.redundant)
    }

    @Test
    fun `everything that was reachable stays reachable`() {
        val edges = mapOf(
            "a" to listOf("b", "c", "d", "e"), "b" to listOf("d", "e"), "c" to listOf("e"),
            "d" to listOf("e"), "e" to emptyList(), "f" to listOf("a", "e")
        )
        val order = listOf("e", "d", "c", "b", "a", "f")

        val result = reduce(edges, order)

        fun closure(graph: Map<String, Collection<String>>, start: String): Set<String> {
            val seen = LinkedHashSet<String>()
            val stack = ArrayDeque(graph.getValue(start))
            while (stack.isNotEmpty()) { val n = stack.removeLast(); if (seen.add(n)) stack.addAll(graph.getValue(n)) }
            return seen
        }
        for (node in order) assertEquals(closure(edges, node), closure(result.edges, node), "closure of $node")
        // and nothing redundant is left: no kept edge is reachable through a sibling
        for ((node, deps) in result.edges) for (dep in deps) {
            assertTrue(deps.none { other -> other != dep && dep in closure(result.edges, other) }, "$node -> $dep is still redundant")
        }
    }

    @Test
    fun `a dense graph reduces to a chain`() {
        val n = 600
        val order = (0 until n).map { "m$it" }                      // m0 is a leaf
        val edges = order.mapIndexed { i, id -> id to (0 until i).map { "m$it" } }.toMap()

        val result = reduce(edges, order)

        assertEquals(n - 1, result.edges.values.sumOf { it.size })
        assertEquals(n * (n - 1) / 2 - (n - 1), result.redundant.size)
        for (i in 1 until n) assertEquals(listOf("m${i - 1}"), result.edges.getValue("m$i").toList(), "m$i")
    }

    @Test
    fun `no dependencies and no redundancy leave the graph as it is`() {
        val result = reduce(mapOf("a" to emptyList(), "b" to listOf("a")), order = listOf("a", "b"))

        assertEquals(listOf("a"), result.edges.getValue("b").toList())
        assertTrue(result.redundant.isEmpty())
    }

    // --- through the resolve task and the generator ---

    private fun clazz(name: String, superName: String = "java/lang/Object", fields: List<String> = emptyList(), publicMethodReturning: String? = null, bodyUses: String? = null): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null)
        fields.forEachIndexed { i, type -> cw.visitField(Opcodes.ACC_PUBLIC, "f$i", "L$type;", null, null).visitEnd() }
        if (publicMethodReturning != null || bodyUses != null) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m", "()" + (publicMethodReturning?.let { "L$it;" } ?: "V"), null, null)
            mv.visitCode()
            if (bodyUses != null) { mv.visitTypeInsn(Opcodes.NEW, bodyUses); mv.visitInsn(Opcodes.POP) }
            if (publicMethodReturning != null) { mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN) } else mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0); mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun jar(vendorDir: File, fileName: String, internalName: String, bytes: ByteArray) {
        val target = File(vendorDir, fileName).apply { parentFile.mkdirs() }
        JarOutputStream(target.outputStream()).use { jos -> jos.putNextEntry(JarEntry("$internalName.class")); jos.write(bytes); jos.closeEntry() }
    }

    private fun resolve(vendorDir: File, compilePackages: List<String> = emptyList()): Map<String, Any?> {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val task = project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.dependencyGraphFile.set(File(tempDir, "build/module-dependencies.json"))
            this.compilePackages.set(compilePackages)
        }.get()
        task.resolveDependencies()
        @Suppress("UNCHECKED_CAST")
        return JsonSlurper().parse(task.dependencyGraphFile.get().asFile) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun modules(json: Map<String, Any?>) = (json["modules"] as List<Map<String, Any?>>).associateBy { it["artifactId"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun names(module: Map<String, Any?>, key: String): List<String>? =
        (module[key] as List<Map<String, Any?>>?)?.map { it["artifactId"] as String }?.sorted()

    @Test
    fun `the resolve task lists only the dependencies a jar does not get through another one`() {
        val vendorDir = File(tempDir, "vendor")
        jar(vendorDir, "four-1.0.jar", "org/four/Four", clazz("org/four/Four"))
        jar(vendorDir, "three-1.0.jar", "org/three/Three", clazz("org/three/Three", superName = "org/four/Four"))
        jar(vendorDir, "two-1.0.jar", "org/two/Two", clazz("org/two/Two", superName = "org/four/Four"))
        // one touches classes of all three jars
        jar(vendorDir, "one-1.0.jar", "org/one/One", clazz("org/one/One", fields = listOf("org/two/Two", "org/three/Three", "org/four/Four")))

        val json = resolve(vendorDir)

        val byName = modules(json)
        assertEquals(listOf("three", "two"), names(byName.getValue("one"), "dependsOn"))
        assertEquals(listOf("four"), names(byName.getValue("two"), "dependsOn"))
        assertEquals(listOf("four"), names(byName.getValue("three"), "dependsOn"))
        assertEquals(emptyList<String>(), names(byName.getValue("four"), "dependsOn"))
        assertEquals(listOf(mapOf("from" to "one", "to" to "four", "via" to "three")), json["redundantEdges"])
        assertNull(byName.getValue("one")["apiDependsOn"], "only written when compile and runtime jars are split")
    }

    @Test
    fun `a reference made only inside a method body is a dependency`() {
        val vendorDir = File(tempDir, "vendor")
        jar(vendorDir, "callee-1.0.jar", "org/callee/Callee", clazz("org/callee/Callee"))
        // the class touches Callee in no signature, field or supertype: only a `new` inside its method
        jar(vendorDir, "caller-1.0.jar", "org/caller/Caller", clazz("org/caller/Caller", bodyUses = "org/callee/Callee"))

        val byName = modules(resolve(vendorDir))

        assertEquals(listOf("callee"), names(byName.getValue("caller"), "dependsOn"))
    }

    @Test
    fun `a path through a runtime only jar does not make a compile dependency redundant at compile time`() {
        val vendorDir = File(tempDir, "vendor")
        // vendor-api (compile package) returns Lib in its public API and uses Rt only inside a method body;
        // Rt extends Lib. In the full graph vendor-api -> lib is covered by vendor-api -> rt -> lib ...
        jar(vendorDir, "vendor-api-1.0.jar", "com/vendor/Api", clazz("com/vendor/Api", publicMethodReturning = "org/lib/Lib", bodyUses = "org/rt/Rt"))
        jar(vendorDir, "rt-1.0.jar", "org/rt/Rt", clazz("org/rt/Rt", superName = "org/lib/Lib"))
        jar(vendorDir, "lib-1.0.jar", "org/lib/Lib", clazz("org/lib/Lib"))

        val json = resolve(vendorDir, listOf("com.vendor"))

        val byName = modules(json)
        // ... so the runtime graph drops it,
        assertEquals(listOf("rt"), names(byName.getValue("vendor-api"), "dependsOn"))
        assertEquals(listOf(mapOf("from" to "vendor-api", "to" to "lib", "via" to "rt")), json["redundantEdges"])
        // but rt is not on the compile classpath, so the compile graph has to keep the direct edge
        assertEquals(listOf("lib"), names(byName.getValue("vendor-api"), "apiDependsOn"))
        assertEquals(true, byName.getValue("lib")["compile"])
        assertEquals(false, byName.getValue("rt")["compile"])

        // and the generator publishes exactly that
        val vendorRoot = vendorDir
        val ivyDir = File(vendorRoot, "ivy")
        IvyRepositoryGenerator.generate(
            VendorIvyDependencyMapper.loadGraph(File(tempDir, "build/module-dependencies.json")),
            vendorRoot, ivyDir, "2026.1.0", "lv.brain.vendor.bundle", slimJars = false
        )
        @Suppress("UNCHECKED_CAST")
        val variants = (JsonSlurper().parse(ivyDir.walkTopDown().single { it.name == "vendor-api-1.0.module" }) as Map<String, Any?>)["variants"] as List<Map<String, Any?>>
        fun deps(variant: String) = (variants.single { it["name"] == variant }["dependencies"] as List<Map<String, Any?>>).map { it["module"] }
        assertEquals(listOf("lib"), deps("apiElements"))
        assertEquals(listOf("rt"), deps("runtimeElements"))
    }
}
