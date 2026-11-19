/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import groovy.json.JsonSlurper
import lv.brain.gradle.core.CompilePackages
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// vendor.compilePackages: jars of the compile packages are compile jars, everything else is runtime only, except the
// jars that the public API of a compile jar needs (found by scanning signatures, never method bodies).
class VendorCompileSplitTest {

    @TempDir
    lateinit var tempDir: File

    private class Method(
        val name: String,
        val desc: String,
        val access: Int = Opcodes.ACC_PUBLIC,
        val signature: String? = null,
        val exceptions: Array<String>? = null,
        val bodyReferences: String? = null
    )

    private fun clazz(
        name: String,
        access: Int = Opcodes.ACC_PUBLIC,
        superName: String = "java/lang/Object",
        interfaces: Array<String>? = null,
        fields: List<Pair<Int, Pair<String, String>>> = emptyList(),
        methods: List<Method> = emptyList()
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, access, name, null, superName, interfaces)
        for ((fieldAccess, nameAndDesc) in fields) cw.visitField(fieldAccess, nameAndDesc.first, nameAndDesc.second, null, null).visitEnd()
        for (m in methods) {
            val mv: MethodVisitor = cw.visitMethod(m.access, m.name, m.desc, m.signature, m.exceptions)
            mv.visitCode()
            if (m.bodyReferences != null) {
                mv.visitTypeInsn(Opcodes.NEW, m.bodyReferences) // a reference in the body only: not part of the API
                mv.visitInsn(Opcodes.POP)
            }
            if (Type.getReturnType(m.desc).sort == Type.VOID) mv.visitInsn(Opcodes.RETURN)
            else {
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitInsn(Opcodes.ARETURN)
            }
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun jar(vendorDir: File, fileName: String, vararg classes: Pair<String, ByteArray>, pomXml: String? = null, pomGroup: String? = null) {
        val target = File(vendorDir, fileName).apply { parentFile.mkdirs() }
        JarOutputStream(target.outputStream()).use { jos ->
            for ((internalName, bytes) in classes) {
                jos.putNextEntry(JarEntry("$internalName.class")); jos.write(bytes); jos.closeEntry()
            }
            if (pomXml != null) {
                val artifact = fileName.substringBeforeLast('-')
                val version = fileName.substringAfterLast('-').removeSuffix(".jar")
                val base = "META-INF/maven/$pomGroup/$artifact"
                jos.putNextEntry(JarEntry("$base/pom.properties"))
                jos.write("groupId=$pomGroup\nartifactId=$artifact\nversion=$version\n".toByteArray()); jos.closeEntry()
                jos.putNextEntry(JarEntry("$base/pom.xml")); jos.write(pomXml.toByteArray()); jos.closeEntry()
            }
        }
    }

    private fun resolve(vendorDir: File, compilePackages: List<String>): Map<String, Any?> {
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
    private fun modules(json: Map<String, Any?>): Map<String, Map<String, Any?>> =
        (json["modules"] as List<Map<String, Any?>>).associateBy { it["artifactId"] as String }

    private fun compiles(json: Map<String, Any?>): Set<String> =
        modules(json).filterValues { it["compile"] == true }.keys

    @Suppress("UNCHECKED_CAST")
    private fun dependsOn(module: Map<String, Any?>): List<String> =
        (module["dependsOn"] as List<Map<String, Any?>>).map { it["artifactId"] as String }.sorted()

    @Test
    fun `compile package jars are compile jars and jars their public api needs are promoted, even transitively`() {
        val vendorDir = File(tempDir, "vendor")
        jar(
            vendorDir, "vendor-api-1.0.jar",
            "com/vendor/api/Api" to clazz(
                "com/vendor/api/Api",
                methods = listOf(
                    Method("getLib", "()Lorg/lib/Lib;"),
                    // only used inside the method body: javac never needs it, so util stays runtime only
                    Method("run", "()V", bodyReferences = "org/util/Util")
                )
            ),
            // not a public class, so what it exposes is not API
            "com/vendor/api/Hidden" to clazz(
                "com/vendor/api/Hidden", access = 0,
                methods = listOf(Method("leak", "()Lorg/hidden/Hidden;"))
            )
        )
        jar(vendorDir, "lib-1.0.jar", "org/lib/Lib" to clazz("org/lib/Lib", methods = listOf(Method("deep", "()Lorg/deep/Deep;"))))
        jar(vendorDir, "deep-1.0.jar", "org/deep/Deep" to clazz("org/deep/Deep"))
        jar(vendorDir, "util-1.0.jar", "org/util/Util" to clazz("org/util/Util"))
        jar(vendorDir, "hidden-1.0.jar", "org/hidden/Hidden" to clazz("org/hidden/Hidden"))
        jar(vendorDir, "other-1.0.jar", "org/other/Other" to clazz("org/other/Other"))

        val json = resolve(vendorDir, listOf("com.vendor"))

        assertEquals(true, json["compileSplit"])
        assertEquals(setOf("vendor-api", "lib", "deep"), compiles(json))
        val byName = modules(json)
        assertTrue((byName.getValue("vendor-api")["compileReason"] as String).contains("com.vendor.api.Api"), byName.getValue("vendor-api").toString())
        assertTrue((byName.getValue("lib")["compileReason"] as String).contains("exposed by vendor-api"), byName.getValue("lib").toString())
        assertTrue((byName.getValue("deep")["compileReason"] as String).contains("exposed by lib"), byName.getValue("deep").toString())
        assertNull(byName.getValue("util")["compileReason"])
        // every jar stays in the graph: runtime-only jars are still part of the distribution
        assertEquals(6, byName.size)
    }

    @Test
    fun `generics arrays exceptions super types fields and protected members all count as public api`() {
        val vendorDir = File(tempDir, "vendor")
        jar(
            vendorDir, "vendor-api-1.0.jar",
            "com/vendor/Api" to clazz(
                "com/vendor/Api",
                superName = "org/sup/Sup",
                interfaces = arrayOf("org/ifc/Ifc"),
                fields = listOf(Opcodes.ACC_PUBLIC to ("field" to "Lorg/fld/Fld;"), Opcodes.ACC_PRIVATE to ("secret" to "Lorg/privfld/PrivFld;")),
                methods = listOf(
                    Method("items", "()Ljava/util/List;", signature = "()Ljava/util/List<Lorg/gen/Gen;>;"),
                    Method("array", "()[Lorg/arr/Arr;"),
                    Method("fails", "()V", exceptions = arrayOf("org/exc/Exc")),
                    Method("inherit", "(Lorg/prot/Prot;)V", access = Opcodes.ACC_PROTECTED),
                    Method("internal", "()Lorg/priv/Priv;", access = Opcodes.ACC_PRIVATE),
                    Method("packagePrivate", "()Lorg/pkg/Pkg;", access = 0)
                )
            )
        )
        for (name in listOf("sup/Sup", "ifc/Ifc", "fld/Fld", "gen/Gen", "arr/Arr", "exc/Exc", "prot/Prot", "priv/Priv", "privfld/PrivFld", "pkg/Pkg")) {
            val artifact = name.substringBefore('/')
            jar(vendorDir, "$artifact-1.0.jar", "org/$name" to clazz("org/$name"))
        }

        val json = resolve(vendorDir, listOf("com.vendor"))

        assertEquals(setOf("vendor-api", "sup", "ifc", "fld", "gen", "arr", "exc", "prot"), compiles(json))
    }

    @Test
    fun `a pom that does not list the api type still gets an api edge so a single module works without the bundle`() {
        val vendorDir = File(tempDir, "vendor")
        jar(
            vendorDir, "vendor-api-1.0.jar",
            "com/vendor/Api" to clazz("com/vendor/Api", methods = listOf(Method("getLib", "()Lorg/lib/Lib;"))),
            // the pom lists util only, so the bytecode scan would not run and the edge to lib would be missing
            pomGroup = "com.vendor",
            pomXml = """
                <project><groupId>com.vendor</groupId><artifactId>vendor-api</artifactId><version>1.0</version>
                  <dependencies><dependency><groupId>org.util</groupId><artifactId>util</artifactId><version>1.0</version></dependency></dependencies>
                </project>
            """.trimIndent()
        )
        jar(vendorDir, "lib-1.0.jar", "org/lib/Lib" to clazz("org/lib/Lib"))
        jar(vendorDir, "util-1.0.jar", "org/util/Util" to clazz("org/util/Util"))

        val json = resolve(vendorDir, listOf("com.vendor"))

        assertEquals(listOf("lib", "util"), dependsOn(modules(json).getValue("vendor-api")))
        assertEquals(setOf("vendor-api", "lib"), compiles(json))
    }

    @Test
    fun `without compile packages every jar is a compile jar`() {
        val vendorDir = File(tempDir, "vendor")
        jar(vendorDir, "one-1.0.jar", "org/one/One" to clazz("org/one/One"))
        jar(vendorDir, "two-1.0.jar", "org/two/Two" to clazz("org/two/Two"))

        val json = resolve(vendorDir, emptyList())

        assertEquals(false, json["compileSplit"])
        assertEquals(setOf("one", "two"), compiles(json))
        assertTrue(modules(json).values.all { it["compileReason"] == null })
    }

    @Test
    fun `compile packages that match no class leave every jar runtime only`() {
        val vendorDir = File(tempDir, "vendor")
        jar(vendorDir, "one-1.0.jar", "org/one/One" to clazz("org/one/One"))

        val json = resolve(vendorDir, listOf("com.nothing"))

        assertEquals(true, json["compileSplit"])
        assertTrue(compiles(json).isEmpty())
    }

    @Test
    fun `package prefixes match whole packages only`() {
        assertEquals(listOf("com/vendor/"), CompilePackages.internalPrefixes(listOf("com.vendor", "com.vendor.", "com.vendor.*", "com/vendor")))
        assertEquals(listOf("com.vendor", "org.other"), CompilePackages.parse(" com.vendor.* , ,org.other. "))
        // com.vend is a different package than com.vendor
        assertFalse("com/vendor/Api".startsWith(CompilePackages.internalPrefixes(listOf("com.vend")).single()))
        assertTrue("com/vendor/api/Api".startsWith(CompilePackages.internalPrefixes(listOf("com.vendor")).single()))
    }
}
