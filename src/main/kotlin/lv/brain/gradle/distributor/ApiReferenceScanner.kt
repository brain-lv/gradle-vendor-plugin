/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.io.File
import java.util.TreeMap
import java.util.jar.JarFile

internal object ApiReferenceScanner {
    data class ApiReference(val fromClass: String, val toClass: String)

    fun scan(
        file: File,
        owners: Map<String, String>,
        selfModule: String,
        skipClass: (String) -> Boolean = { false }
    ): Map<String, ApiReference> {
        val found = TreeMap<String, ApiReference>()
        var currentClass = ""

        fun note(internalName: String?) {
            if (internalName == null) return
            val owner = owners[internalName] ?: return
            if (owner == selfModule) return
            val candidate = ApiReference(currentClass, internalName)
            val existing = found[owner]
            if (existing == null || compareValuesBy(candidate, existing, { it.fromClass }, { it.toClass }) < 0) {
                found[owner] = candidate
            }
        }

        fun noteType(type: Type) {
            val element = if (type.sort == Type.ARRAY) type.elementType else type
            if (element.sort == Type.OBJECT) note(element.internalName)
        }

        fun noteDescriptor(descriptor: String) {
            val type = Type.getType(descriptor)
            if (type.sort == Type.METHOD) {
                type.argumentTypes.forEach(::noteType)
                noteType(type.returnType)
            } else {
                noteType(type)
            }
        }

        val signatureCollector = object : SignatureVisitor(Opcodes.ASM9) {
            override fun visitClassType(name: String) = note(name)
        }

        fun noteSignature(signature: String?) {
            if (signature != null) SignatureReader(signature).accept(signatureCollector)
        }

        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            private var apiClass = false

            override fun visit(
                version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?
            ) {
                apiClass = access and Opcodes.ACC_PUBLIC != 0
                if (!apiClass) return
                note(superName)
                interfaces?.forEach { note(it) }
                noteSignature(signature)
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                if (apiClass && isApiMember(access)) {
                    noteDescriptor(descriptor)
                    noteSignature(signature)
                }
                return null
            }

            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?
            ): MethodVisitor? {
                if (apiClass && isApiMember(access)) {
                    noteDescriptor(descriptor)
                    noteSignature(signature)
                    exceptions?.forEach { note(it) }
                }
                return null
            }

            private fun isApiMember(access: Int) =
                access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 && access and Opcodes.ACC_SYNTHETIC == 0
        }

        JarFile(file).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory ||
                    !entry.name.endsWith(".class") ||
                    entry.name.startsWith("META-INF/") ||
                    entry.name == "module-info.class" ||
                    entry.name == "package-info.class"
                ) continue
                currentClass = entry.name.removeSuffix(".class")
                if (skipClass(currentClass)) continue
                jar.getInputStream(entry).use { stream ->
                    ClassReader(stream).accept(
                        visitor,
                        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
                    )
                }
            }
        }
        return found
    }
}
