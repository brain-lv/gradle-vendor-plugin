/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.support

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Builds tiny but real class files and jars, so no test depends on downloading anything. */
object SyntheticJars {

    /**
     * @param publicReturns internal name of a class that the public method `leak()` returns (part of the public API)
     * @param bodyUses internal name of a class that only the body of `use()` instantiates (not part of the API)
     */
    fun clazz(
        internalName: String,
        publicReturns: String? = null,
        bodyUses: String? = null,
        superName: String = "java/lang/Object"
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        if (publicReturns != null) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "leak", "()L$publicReturns;", null, null)
            mv.visitCode(); mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(0, 0); mv.visitEnd()
        }
        if (bodyUses != null) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "use", "()V", null, null)
            mv.visitCode(); mv.visitTypeInsn(Opcodes.NEW, bodyUses); mv.visitInsn(Opcodes.POP); mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0); mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    fun write(target: File, entries: Map<String, ByteArray>) {
        target.parentFile.mkdirs()
        JarOutputStream(target.outputStream()).use { jos ->
            for ((name, bytes) in entries) {
                jos.putNextEntry(JarEntry(name)); jos.write(bytes); jos.closeEntry()
            }
        }
    }

    /** `META-INF/maven/<group>/<artifact>/pom.properties`: the jar says who it is. */
    fun pomProperties(group: String, artifact: String, version: String): Pair<String, ByteArray> =
        "META-INF/maven/$group/$artifact/pom.properties" to "groupId=$group\nartifactId=$artifact\nversion=$version\n".toByteArray()
}

/**
 * A small vendor SDK folder:
 *
 * ```
 * client/lib/demo-client-1.0.0.jar   com.demo.client.Api   public API returns org.leak.Leak, its code instantiates org.lite.Util
 * client/lib/leak-1.0.0.jar          org.leak.Leak
 * client/lib/commons-lite-3.8.jar    org.lite.Util         (org.lite:commons-lite:3.8, declared in pom.properties)
 * server/lib/demo-server-1.0.0.jar   com.demo.server.Impl  its code instantiates com.demo.client.Api
 * tools/unmatched-1.0.jar            tools.Unmatched       (matched by no category)
 * ```
 *
 * With `vendor.compilePackages=com.demo` the compile jars are demo-client and demo-server, leak joins them because the
 * public API of demo-client returns it, and commons-lite is runtime only.
 */
class DemoSdk(val root: File) {
    val vendorDir: File get() = File(root, "vendor")

    fun createJars(): DemoSdk {
        File(root, ".git").mkdirs()
        SyntheticJars.write(
            File(vendorDir, "client/lib/demo-client-1.0.0.jar"),
            mapOf("com/demo/client/Api.class" to SyntheticJars.clazz("com/demo/client/Api", publicReturns = "org/leak/Leak", bodyUses = "org/lite/Util"))
        )
        SyntheticJars.write(
            File(vendorDir, "client/lib/leak-1.0.0.jar"),
            mapOf("org/leak/Leak.class" to SyntheticJars.clazz("org/leak/Leak"))
        )
        SyntheticJars.write(
            File(vendorDir, "client/lib/commons-lite-3.8.jar"),
            mapOf(
                "org/lite/Util.class" to SyntheticJars.clazz("org/lite/Util"),
                SyntheticJars.pomProperties("org.lite", "commons-lite", "3.8")
            )
        )
        SyntheticJars.write(
            File(vendorDir, "server/lib/demo-server-1.0.0.jar"),
            mapOf("com/demo/server/Impl.class" to SyntheticJars.clazz("com/demo/server/Impl", bodyUses = "com/demo/client/Api"))
        )
        SyntheticJars.write(
            File(vendorDir, "tools/unmatched-1.0.jar"),
            mapOf("tools/Unmatched.class" to SyntheticJars.clazz("tools/Unmatched"))
        )
        return this
    }

    fun writeProperties(vararg lines: String): DemoSdk {
        File(root, "vendor.properties").writeText(lines.joinToString("\n") + "\n")
        return this
    }

    companion object {
        const val VERSION = "2026.1.0"
    }
}
