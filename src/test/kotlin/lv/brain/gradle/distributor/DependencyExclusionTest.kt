/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// vendor.dependencyExcludeClasses / vendor.dependencyExcludeJars: what the dependency check does not see.
class DependencyExclusionTest {

    @TempDir
    lateinit var tempDir: File

    private fun clazz(
        name: String,
        superName: String = "java/lang/Object",
        fields: List<String> = emptyList(),
        publicMethodReturning: String? = null,
        bodyUses: String? = null
    ): ByteArray {
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

    /** `path` is relative to the vendor folder, `pomXml` makes it a Maven jar of group `pomGroup`. */
    private fun jar(vendorDir: File, path: String, vararg classes: Pair<String, ByteArray>, pomXml: String? = null, pomGroup: String = "org.test") {
        val target = File(vendorDir, path).apply { parentFile.mkdirs() }
        JarOutputStream(target.outputStream()).use { jos ->
            for ((internalName, bytes) in classes) {
                jos.putNextEntry(JarEntry("$internalName.class")); jos.write(bytes); jos.closeEntry()
            }
            if (pomXml != null) {
                val file = path.substringAfterLast('/')
                val artifact = file.substringBeforeLast('-')
                val version = file.substringAfterLast('-').removeSuffix(".jar")
                val base = "META-INF/maven/$pomGroup/$artifact"
                jos.putNextEntry(JarEntry("$base/pom.properties"))
                jos.write("groupId=$pomGroup\nartifactId=$artifact\nversion=$version\n".toByteArray()); jos.closeEntry()
                jos.putNextEntry(JarEntry("$base/pom.xml")); jos.write(pomXml.toByteArray()); jos.closeEntry()
            }
        }
    }

    private fun resolve(
        vendorDir: File,
        classes: List<String> = emptyList(),
        jars: List<String> = emptyList(),
        compilePackages: List<String> = emptyList()
    ): Map<String, Any?> {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val task = project.tasks.register("resolveDeps", ResolveModuleDependenciesTask::class.java) {
            this.vendorDir.set(vendorDir)
            this.dependencyGraphFile.set(File(tempDir, "build/module-dependencies.json"))
            this.compilePackages.set(compilePackages)
            this.dependencyExcludeClasses.set(classes)
            this.dependencyExcludeJars.set(jars)
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

    private fun deps(json: Map<String, Any?>, artifact: String) = names(modules(json).getValue(artifact), "dependsOn")

    private fun vendor() = File(tempDir, "vendor")

    // ---- excluded classes ----

    @Test
    fun `without an exclusion every reference is a dependency`() {
        val vendorDir = vendor()
        jar(vendorDir, "shaded-1.0.jar", "org/shaded/Hidden" to clazz("org/shaded/Hidden"))
        jar(vendorDir, "real-1.0.jar", "org/real/Real" to clazz("org/real/Real"))
        jar(vendorDir, "app-1.0.jar", "org/app/Main" to clazz("org/app/Main", fields = listOf("org/real/Real", "org/shaded/Hidden")))

        assertEquals(listOf("real", "shaded"), deps(resolve(vendorDir), "app"))
    }

    @Test
    fun `a reference to an excluded class creates no dependency`() {
        val vendorDir = vendor()
        jar(vendorDir, "shaded-1.0.jar", "org/shaded/Hidden" to clazz("org/shaded/Hidden"))
        jar(vendorDir, "real-1.0.jar", "org/real/Real" to clazz("org/real/Real"))
        jar(vendorDir, "app-1.0.jar", "org/app/Main" to clazz("org/app/Main", fields = listOf("org/real/Real", "org/shaded/Hidden")))

        val json = resolve(vendorDir, classes = listOf("org.shaded"))

        assertEquals(listOf("real"), deps(json, "app"))
        assertTrue("shaded" in modules(json), "the jar is still a module, only nothing depends on it because of that class")
    }

    @Test
    fun `an excluded class creates no dependencies of its own`() {
        val vendorDir = vendor()
        jar(vendorDir, "real-1.0.jar", "org/real/Real" to clazz("org/real/Real"))
        jar(vendorDir, "old-1.0.jar", "org/old/Old" to clazz("org/old/Old"))
        // Main needs Real, Legacy (a second class of the same jar) needs Old
        jar(
            vendorDir, "app-1.0.jar",
            "org/app/Main" to clazz("org/app/Main", fields = listOf("org/real/Real")),
            "org/app/Legacy" to clazz("org/app/Legacy", fields = listOf("org/old/Old")),
            "org/app/Legacy\$Helper" to clazz("org/app/Legacy\$Helper", bodyUses = "org/old/Old")
        )

        assertEquals(listOf("old", "real"), deps(resolve(vendorDir), "app"))
        assertEquals(listOf("real"), deps(resolve(vendorDir, classes = listOf("org.app.Legacy")), "app"), "the inner class goes with its outer class")
    }

    @Test
    fun `a class is only excluded as far as the pattern reaches`() {
        val vendorDir = vendor()
        jar(vendorDir, "util-1.0.jar", "org/util/Strings" to clazz("org/util/Strings"), "org/util/internal/Secret" to clazz("org/util/internal/Secret"))
        jar(
            vendorDir, "app-1.0.jar",
            "org/app/Main" to clazz("org/app/Main", fields = listOf("org/util/internal/Secret"))
        )

        // Secret is what app uses: excluding its sibling Strings changes nothing, excluding its package does
        assertEquals(listOf("util"), deps(resolve(vendorDir, classes = listOf("org.util.Strings")), "app"))
        assertEquals(emptyList<String>(), deps(resolve(vendorDir, classes = listOf("org.util.internal")), "app"))
    }

    @Test
    fun `excluding the class that closes a cycle removes the cycle`() {
        val vendorDir = vendor()
        // a -> b through A, and b -> a through Callback only
        jar(vendorDir, "a-1.0.jar", "org/a/A" to clazz("org/a/A", fields = listOf("org/b/B")))
        jar(
            vendorDir, "b-1.0.jar",
            "org/b/B" to clazz("org/b/B"),
            "org/b/Callback" to clazz("org/b/Callback", fields = listOf("org/a/A"))
        )

        val cyclic = resolve(vendorDir)
        assertEquals(1, (cyclic["brokenCycles"] as List<*>).size, "without the exclusion there is a cycle to break")

        val json = resolve(vendorDir, classes = listOf("org.b.Callback"))
        assertEquals(emptyList<Any>(), json["brokenCycles"])
        assertEquals(listOf("b"), deps(json, "a"))
        assertEquals(emptyList<String>(), deps(json, "b"))
    }

    @Test
    fun `a class that two jars define and that is excluded creates no dependency on either`() {
        val vendorDir = vendor()
        // both jars define org.dup.Dup; the first one (a-shaded) owns it, so app depends on that one
        jar(vendorDir, "a-shaded-1.0.jar", "org/dup/Dup" to clazz("org/dup/Dup"), "org/a/Shaded" to clazz("org/a/Shaded"))
        jar(vendorDir, "b-real-1.0.jar", "org/dup/Dup" to clazz("org/dup/Dup"))
        jar(vendorDir, "app-1.0.jar", "org/app/Main" to clazz("org/app/Main", fields = listOf("org/dup/Dup")))

        assertEquals(listOf("a-shaded"), deps(resolve(vendorDir), "app"))
        // with Dup out of the picture there is nothing left to depend on, neither the first nor the second copy
        assertEquals(emptyList<String>(), deps(resolve(vendorDir, classes = listOf("org.dup")), "app"))
    }

    // ---- excluded jars ----

    @Test
    fun `an excluded jar neither depends on others nor is depended on`() {
        val vendorDir = vendor()
        jar(vendorDir, "client/lib/core-1.0.jar", "org/core/Core" to clazz("org/core/Core"))
        jar(vendorDir, "legacy/old-1.0.jar", "org/old/Old" to clazz("org/old/Old", fields = listOf("org/core/Core")))
        jar(vendorDir, "client/lib/app-1.0.jar", "org/app/Main" to clazz("org/app/Main", fields = listOf("org/core/Core", "org/old/Old")))

        // app reaches core through old as well, so the direct edge app -> core is left out of the reduced tree
        val before = resolve(vendorDir)
        assertEquals(listOf("old"), deps(before, "app"))
        assertEquals(listOf("core"), deps(before, "old"))

        val json = resolve(vendorDir, jars = listOf("legacy/"))

        assertEquals(listOf("core"), deps(json, "app"))
        assertEquals(emptyList<String>(), deps(json, "old"))
        assertEquals("excluded", modules(json).getValue("old")["resolutionSource"])
        @Suppress("UNCHECKED_CAST")
        val excludes = json["dependencyExcludes"] as Map<String, Any?>
        assertEquals(listOf("old"), excludes["ignoredJars"])
        assertEquals(listOf("legacy/"), excludes["jars"])
    }

    @Test
    fun `an excluded jar is still packaged as a module`() {
        val vendorDir = vendor()
        jar(vendorDir, "legacy/old-1.0.jar", "org/old/Old" to clazz("org/old/Old"))
        jar(vendorDir, "app-1.0.jar", "org/app/Main" to clazz("org/app/Main"))

        val json = resolve(vendorDir, jars = listOf("legacy/old-"))

        val old = modules(json).getValue("old")
        assertEquals("legacy/old-1.0.jar", old["jar"])
        assertTrue("old" in (json["topologicalOrder"] as List<*>))
    }

    @Test
    fun `jars are excluded by a prefix, by a folder, by an Ant pattern and with the vendor folder name in front`() {
        val vendorDir = vendor()
        jar(vendorDir, "client/lib/core-1.0.jar", "org/core/Core" to clazz("org/core/Core"))
        jar(vendorDir, "client/lib/legacy-a-1.0.jar", "org/la/La" to clazz("org/la/La"))
        jar(vendorDir, "server/lib/legacy-b-1.0.jar", "org/lb/Lb" to clazz("org/lb/Lb"))
        jar(vendorDir, "tools/t-1.0.jar", "org/t/T" to clazz("org/t/T"))
        jar(vendorDir, "client/lib/ant-1.0.jar", "org/ant/Ant" to clazz("org/ant/Ant"))
        jar(
            vendorDir, "client/lib/app-1.0.jar",
            "org/app/Main" to clazz(
                "org/app/Main",
                fields = listOf("org/core/Core", "org/la/La", "org/lb/Lb", "org/t/T", "org/ant/Ant")
            )
        )

        fun appDeps(vararg jars: String) = deps(resolve(vendorDir, jars = jars.toList()), "app")

        assertEquals(listOf("ant", "core", "legacy-a", "legacy-b", "t"), appDeps())
        assertEquals(listOf("ant", "core", "legacy-b", "t"), appDeps("client/lib/legacy-"), "prefix")
        assertEquals(listOf("ant", "core", "legacy-a", "legacy-b"), appDeps("tools/"), "folder")
        assertEquals(listOf("ant", "core", "t"), appDeps("**/legacy-*.jar"), "Ant pattern")
        assertEquals(listOf("ant", "core", "legacy-b", "t"), appDeps("vendor/client/lib/legacy-"), "with the vendor folder name")
        assertEquals(listOf("core"), appDeps("tools/", "**/legacy-*.jar", "client/lib/ant-"), "several patterns")
    }

    @Test
    fun `a pom entry that points to an excluded jar is not a dependency either`() {
        val vendorDir = vendor()
        jar(vendorDir, "lib/core-1.0.jar", "org/core/Core" to clazz("org/core/Core"))
        jar(
            vendorDir, "lib/svc-1.0.jar", "org/svc/Svc" to clazz("org/svc/Svc"),
            pomXml = """
                <project>
                  <dependencies>
                    <dependency><groupId>org.test</groupId><artifactId>core</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
            """.trimIndent()
        )

        assertEquals(listOf("core"), deps(resolve(vendorDir), "svc"))
        assertEquals(emptyList<String>(), deps(resolve(vendorDir, jars = listOf("lib/core-")), "svc"))
    }

    // ---- compile classpath ----

    private fun compileSplitSdk(vendorDir: File) {
        // the API of com.vendor.Api returns org.leak.Leak; Leak is in a jar of its own
        jar(vendorDir, "leak-1.0.jar", "org/leak/Leak" to clazz("org/leak/Leak"))
        jar(vendorDir, "api-1.0.jar", "com/vendor/Api" to clazz("com/vendor/Api", publicMethodReturning = "org/leak/Leak"))
    }

    @Test
    fun `a jar that only an excluded class makes part of the api stays out of the compile classpath`() {
        val vendorDir = vendor()
        compileSplitSdk(vendorDir)

        val before = resolve(vendorDir, compilePackages = listOf("com.vendor"))
        assertEquals(listOf("api", "leak"), modules(before).filterValues { it["compile"] == true }.keys.sorted(), "promoted without the exclusion")

        val json = resolve(vendorDir, classes = listOf("org.leak"), compilePackages = listOf("com.vendor"))
        assertEquals(listOf("api"), modules(json).filterValues { it["compile"] == true }.keys.sorted())
        assertEquals(emptyList<String>(), names(modules(json).getValue("api"), "apiDependsOn"))
    }

    @Test
    fun `an excluded class does not add to the api of its jar`() {
        val vendorDir = vendor()
        jar(vendorDir, "leak-1.0.jar", "org/leak/Leak" to clazz("org/leak/Leak"))
        jar(
            vendorDir, "api-1.0.jar",
            "com/vendor/Api" to clazz("com/vendor/Api"),
            "com/vendor/Internal" to clazz("com/vendor/Internal", publicMethodReturning = "org/leak/Leak")
        )

        val before = resolve(vendorDir, compilePackages = listOf("com.vendor"))
        assertTrue(modules(before).getValue("leak")["compile"] == true)

        val json = resolve(vendorDir, classes = listOf("com.vendor.Internal"), compilePackages = listOf("com.vendor"))
        assertFalse(modules(json).getValue("leak")["compile"] == true)
        assertTrue(modules(json).getValue("api")["compile"] == true, "the jar still defines a class of a compile package")
    }

    @Test
    fun `exclusions do not decide which jars are compile jars`() {
        val vendorDir = vendor()
        jar(vendorDir, "api-1.0.jar", "com/vendor/Api" to clazz("com/vendor/Api"))
        jar(vendorDir, "other-1.0.jar", "org/other/Other" to clazz("org/other/Other"))

        val json = resolve(vendorDir, classes = listOf("com.vendor.Api"), compilePackages = listOf("com.vendor"))

        assertTrue(modules(json).getValue("api")["compile"] == true)
        assertFalse(modules(json).getValue("other")["compile"] == true)
    }

    @Test
    fun `an excluded jar in a compile package is still a compile jar but its api is not followed`() {
        val vendorDir = vendor()
        jar(vendorDir, "leak-1.0.jar", "org/leak/Leak" to clazz("org/leak/Leak"))
        jar(vendorDir, "legacy/api-1.0.jar", "com/vendor/Api" to clazz("com/vendor/Api", publicMethodReturning = "org/leak/Leak"))

        val json = resolve(vendorDir, jars = listOf("legacy/"), compilePackages = listOf("com.vendor"))

        assertTrue(modules(json).getValue("api")["compile"] == true)
        assertFalse(modules(json).getValue("leak")["compile"] == true)
        assertEquals(emptyList<String>(), deps(json, "api"))
    }

    // ---- no exclusions ----

    @Test
    fun `without patterns the result is the same as before`() {
        val vendorDir = vendor()
        jar(vendorDir, "core-1.0.jar", "org/core/Core" to clazz("org/core/Core"))
        jar(vendorDir, "app-1.0.jar", "org/app/Main" to clazz("org/app/Main", fields = listOf("org/core/Core")))

        val json = resolve(vendorDir)

        assertEquals(listOf("core"), deps(json, "app"))
        @Suppress("UNCHECKED_CAST")
        val excludes = json["dependencyExcludes"] as Map<String, Any?>
        assertEquals(emptyList<Any>(), excludes["classes"])
        assertEquals(emptyList<Any>(), excludes["jars"])
        assertEquals(emptyList<Any>(), excludes["ignoredJars"])
        assertEquals("bytecode-scan", modules(json).getValue("app")["resolutionSource"])
    }
}
