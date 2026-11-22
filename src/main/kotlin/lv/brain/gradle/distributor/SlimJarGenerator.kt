/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.distributor

import lv.brain.gradle.core.ReproducibleEntries.withFixedTime
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

object SlimJarGenerator {
    fun generateSlimJar(sourceJar: File, targetSlimJar: File) {
        targetSlimJar.parentFile.mkdirs()
        JarFile(sourceJar).use { inputJar ->
            JarOutputStream(targetSlimJar.outputStream().buffered(64 * 1024)).use { outJar ->
                val seenEntries = HashSet<String>()
                inputJar.entries().asSequence()
                    .filter { !it.isDirectory && seenEntries.add(it.name) }
                    .forEach { entry -> copySlim(inputJar, entry, outJar) }
            }
        }
    }

    private fun copySlim(inputJar: JarFile, entry: JarEntry, outJar: JarOutputStream) {
        when {
            entry.name.endsWith(CLASS_SUFFIX) && entry.name != MODULE_INFO -> {
                val slimBytes = stripClassBytecode(inputJar.getInputStream(entry).use { it.readBytes() }) ?: return
                outJar.putNextEntry(JarEntry(entry.name).withFixedTime())
                outJar.write(slimBytes)
                outJar.closeEntry()
            }
            isKeptResource(entry.name) -> {
                outJar.putNextEntry(JarEntry(entry.name).withFixedTime())
                inputJar.getInputStream(entry).use { it.copyTo(outJar) }
                outJar.closeEntry()
            }
        }
    }

    private fun isKeptResource(name: String): Boolean =
        name == MODULE_INFO || name == "META-INF/MANIFEST.MF" || name.startsWith("META-INF/services/") || name.endsWith(".kotlin_module")

    private const val CLASS_SUFFIX = ".class"
    private const val MODULE_INFO = "module-info.class"

    private fun stripClassBytecode(classBytes: ByteArray): ByteArray? {
        val reader = ClassReader(classBytes)
        if ((reader.access and Opcodes.ACC_PRIVATE) != 0) {
            return null
        }

        val writer = ClassWriter(0)
        val visitor = object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                if ((access and Opcodes.ACC_PRIVATE) == 0) {
                    super.visitInnerClass(name, outerName, innerName, access)
                }
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?
            ): FieldVisitor? {
                if ((access and Opcodes.ACC_PRIVATE) != 0) return null
                return super.visitField(access, name, descriptor, signature, value)
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                if ((access and Opcodes.ACC_PRIVATE) != 0 || name == "<clinit>") return null
                return super.visitMethod(access, name, descriptor, signature, exceptions)
            }
        }

        reader.accept(
            visitor,
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
        )
        return writer.toByteArray()
    }
}
