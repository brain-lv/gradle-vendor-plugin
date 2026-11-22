/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import java.io.StringWriter
import java.util.Properties
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader

internal object Templates {
    private const val ROOT = "lv/brain/gradle/templates/"

    private val engine: VelocityEngine by lazy {
        val properties = Properties().apply {
            setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath")
            setProperty("resource.loader.classpath.class", ClasspathResourceLoader::class.java.name)
            setProperty("resource.loader.classpath.cache", "true")
            setProperty(RuntimeConstants.RUNTIME_REFERENCES_STRICT, "true")
            setProperty(RuntimeConstants.SPACE_GOBBLING, "lines")
            setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8")
        }
        VelocityEngine(properties).also { it.init() }
    }

    fun render(name: String, model: Map<String, Any?>): String {
        val context = VelocityContext().apply {
            put("esc", Escape)
            model.forEach { (key, value) -> put(key, value) }
        }
        val out = StringWriter()
        engine.getTemplate(ROOT + name, "UTF-8").merge(context, out)
        return out.toString().trimStart()
    }

    object Escape {
        fun xml(value: Any?): String = value?.toString().orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        fun html(value: Any?): String = value?.toString().orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
