/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.support

import java.io.File

/** The two build script languages. The same configuration is written once and rendered in either. */
enum class Dsl(val label: String, val settingsFile: String, val buildFile: String) {
    GROOVY("Groovy", "settings.gradle", "build.gradle"),
    KOTLIN("Kotlin", "settings.gradle.kts", "build.gradle.kts");

    fun str(value: String): String = if (this == GROOVY) "'$value'" else "\"$value\""
}

/** What a `vendorDistributor { ... }` block in settings contains. Everything optional. */
data class DistributorSettings(
    val slimJars: Boolean? = null,
    val compilePackages: List<String> = emptyList(),
    /** classes and packages the dependency check does not see */
    val dependencyExcludeClasses: List<String> = emptyList(),
    /** jars (path prefix or Ant pattern) the dependency check does not see */
    val dependencyExcludeJars: List<String> = emptyList(),
    val defaultCategory: String? = null,
    /** category name to Ant style includes */
    val categories: Map<String, List<String>> = emptyMap(),
    /** a classifier that sends `server/...` to "server", `client/...` to "client" and leaves every other jar alone */
    val classifierByTopFolder: Boolean = false
)

/** One dependency of the consumer project. */
sealed interface Dep {
    /** `implementation(vendor.bundle("client"))` */
    data class Bundle(val name: String) : Dep

    /** `implementation(vendor.module(name = "x"))`, or with an explicit group */
    data class Module(val name: String, val group: String? = null) : Dep

    /** `implementation("g:a:v")` */
    data class Gav(val notation: String) : Dep
}

object Scripts {

    fun distributorSettings(dsl: Dsl, cfg: DistributorSettings = DistributorSettings(), rootName: String = "vendor-sdk"): String {
        val groovy = dsl == Dsl.GROOVY
        val body = ArrayList<String>()

        cfg.slimJars?.let { body += "slimJars = $it" }
        fun listProperty(name: String, values: List<String>) {
            if (values.isEmpty()) return
            val items = values.joinToString(", ") { dsl.str(it) }
            body += if (groovy) "$name = [$items]" else "$name = listOf($items)"
        }
        listProperty("compilePackages", cfg.compilePackages)
        listProperty("dependencyExcludeClasses", cfg.dependencyExcludeClasses)
        listProperty("dependencyExcludeJars", cfg.dependencyExcludeJars)
        cfg.defaultCategory?.let { body += "defaultCategory = ${dsl.str(it)}" }
        for ((name, globs) in cfg.categories) {
            val patterns = globs.joinToString(", ") { dsl.str(it) }
            body += "category(${dsl.str(name)}) {"
            body += if (groovy) "    include $patterns" else "    include($patterns)"
            body += "}"
        }
        if (cfg.classifierByTopFolder) {
            if (groovy) {
                body += "classifier { String path, File jar ->"
                body += "    path.startsWith('server/') ? 'server' : path.startsWith('client/') ? 'client' : null"
                body += "}"
            } else {
                body += "classifier { path, _ ->"
                body += "    when {"
                body += "        path.startsWith(\"server/\") -> \"server\""
                body += "        path.startsWith(\"client/\") -> \"client\""
                body += "        else -> null"
                body += "    }"
                body += "}"
            }
        }

        return buildString {
            appendLine("plugins {")
            appendLine(if (groovy) "    id 'lv.brain.vendor-distributor'" else "    id(\"lv.brain.vendor-distributor\")")
            appendLine("}")
            appendLine("rootProject.name = ${dsl.str(rootName)}")
            if (body.isNotEmpty()) {
                appendLine()
                appendLine("vendorDistributor {")
                body.forEach { appendLine("    $it") }
                appendLine("}")
            }
        }
    }

    fun consumerSettings(dsl: Dsl, rootName: String = "app", includes: List<String> = emptyList()): String = buildString {
        val groovy = dsl == Dsl.GROOVY
        appendLine("plugins {")
        appendLine(if (groovy) "    id 'lv.brain.vendor-consumer'" else "    id(\"lv.brain.vendor-consumer\")")
        appendLine("}")
        appendLine("rootProject.name = ${dsl.str(rootName)}")
        includes.forEach { appendLine(if (groovy) "include ${dsl.str(it)}" else "include(${dsl.str(it)})") }
    }

    /**
     * A Java project that depends on [deps]. It registers `printClasspaths` (prints `COMPILE=[...]` and `RUNTIME=[...]`
     * with the file names) and `runApp` (runs `app.App` on the runtime classpath). The project plugin is applied the
     * documented way, in the `plugins` block.
     */
    fun consumerBuild(dsl: Dsl, deps: List<Dep>, localMavenRepo: Boolean = false): String {
        val groovy = dsl == Dsl.GROOVY
        val depLines = deps.map { dep ->
            when (dep) {
                is Dep.Bundle -> if (groovy) "implementation vendor.bundle(${dsl.str(dep.name)})" else "implementation(vendor.bundle(${dsl.str(dep.name)}))"
                is Dep.Module -> {
                    val call = when {
                        groovy && dep.group == null -> "vendor.module(${dsl.str(dep.name)})"
                        groovy -> "vendor.module(${dsl.str(dep.group!!)}, ${dsl.str(dep.name)})"
                        dep.group == null -> "vendor.module(name = ${dsl.str(dep.name)})"
                        else -> "vendor.module(group = ${dsl.str(dep.group)}, name = ${dsl.str(dep.name)})"
                    }
                    if (groovy) "implementation $call" else "implementation($call)"
                }
                is Dep.Gav -> if (groovy) "implementation ${dsl.str(dep.notation)}" else "implementation(${dsl.str(dep.notation)})"
            }
        }
        val repo = if (!localMavenRepo) "" else if (groovy) {
            "repositories { maven { url = uri('local-repo') } }\n"
        } else {
            "repositories { maven { url = uri(\"local-repo\") } }\n"
        }
        val deps0 = depLines.joinToString("\n") { "    $it" }
        return if (groovy) """
            plugins {
                id 'java'
                id 'lv.brain.vendor-consumer-project'
            }
            ${repo}dependencies {
            $deps0
            }
            tasks.register('printClasspaths') {
                def compileFiles = configurations.compileClasspath.incoming.files
                def runtimeFiles = configurations.runtimeClasspath.incoming.files
                doLast {
                    println 'COMPILE=' + compileFiles.files*.name.sort()
                    println 'RUNTIME=' + runtimeFiles.files*.name.sort()
                }
            }
            tasks.register('runApp', JavaExec) {
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'app.App'
            }
        """.trimIndent() + "\n" else """
            plugins {
                java
                id("lv.brain.vendor-consumer-project")
            }
            ${repo}dependencies {
            $deps0
            }
            tasks.register("printClasspaths") {
                val compileFiles = configurations.compileClasspath.get().incoming.files
                val runtimeFiles = configurations.runtimeClasspath.get().incoming.files
                doLast {
                    println("COMPILE=" + compileFiles.files.map { it.name }.sorted())
                    println("RUNTIME=" + runtimeFiles.files.map { it.name }.sorted())
                }
            }
            tasks.register<JavaExec>("runApp") {
                classpath = sourceSets.main.get().runtimeClasspath
                mainClass.set("app.App")
            }
        """.trimIndent() + "\n"
    }

    /** `app.App` loads the three demo classes by name (so it compiles against nothing) and prints a line per class. */
    fun writeApp(appDir: File, extraSource: String? = null) {
        val dir = File(appDir, "src/main/java/app").apply { mkdirs() }
        File(dir, "App.java").writeText(
            """
            package app;
            public class App {
                public static void main(String[] args) throws Exception {
                    for (String name : new String[] {"com.demo.client.Api", "org.leak.Leak", "org.lite.Util"}) {
                        Class.forName(name);
                        System.out.println("LOADED " + name);
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        if (extraSource != null) File(dir, "Extra.java").writeText(extraSource)
    }

    /** A jar of org.lite:commons-lite:[version] in a Maven repository folder `local-repo` inside [appDir]. */
    fun writeLocalMavenLite(appDir: File, version: String) {
        val dir = File(appDir, "local-repo/org/lite/commons-lite/$version").apply { mkdirs() }
        File(dir, "commons-lite-$version.pom").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>org.lite</groupId><artifactId>commons-lite</artifactId><version>$version</version></project>"
        )
        SyntheticJars.write(
            File(dir, "commons-lite-$version.jar"),
            mapOf(
                "org/lite/Util.class" to SyntheticJars.clazz("org/lite/Util"),
                SyntheticJars.pomProperties("org.lite", "commons-lite", version)
            )
        )
    }
}
