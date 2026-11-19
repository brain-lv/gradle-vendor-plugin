/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

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
        if (targetSlimJar.exists() && targetSlimJar.lastModified() >= sourceJar.lastModified()) {
            return
        }
        targetSlimJar.parentFile.mkdirs()

        JarFile(sourceJar).use { inputJar ->
            JarOutputStream(targetSlimJar.outputStream().buffered(64 * 1024)).use { outJar ->
                val seenEntries = HashSet<String>()
                val entries = inputJar.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !seenEntries.add(entry.name)) continue

                    when {
                        entry.name.endsWith(".class") && entry.name != "module-info.class" -> {
                            val rawBytes = inputJar.getInputStream(entry).use { it.readBytes() }
                            val slimBytes = stripClassBytecode(rawBytes)
                            if (slimBytes != null) {
                                outJar.putNextEntry(JarEntry(entry.name))
                                outJar.write(slimBytes)
                                outJar.closeEntry()
                            }
                        }
                        entry.name == "module-info.class" ||
                        entry.name == "META-INF/MANIFEST.MF" ||
                        entry.name.startsWith("META-INF/services/") ||
                        entry.name.endsWith(".kotlin_module") -> {
                            outJar.putNextEntry(JarEntry(entry.name))
                            inputJar.getInputStream(entry).use { it.copyTo(outJar) }
                            outJar.closeEntry()
                        }
                    }
                }
            }
        }
    }

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
