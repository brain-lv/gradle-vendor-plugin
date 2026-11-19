/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.support

import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Which Gradle the TestKit builds run on, and how strict they are. Set by the `integrationTestGradle*` tasks:
 *
 * * `vendor.it.gradleVersion`: a released version, downloaded by TestKit (needs network)
 * * `vendor.it.gradleHome`: an installed distribution; wins over the version (offline and air-gapped CI)
 * * `vendor.it.warningMode`: `fail` makes any Gradle deprecation warning fail the build, which is how readiness for
 *   the next major version is proven. Not set: Gradle's default.
 *
 * With none of them set the builds run on the Gradle that runs the tests.
 */
object GradleEnv {
    private fun prop(name: String): String? = System.getProperty(name)?.takeIf { it.isNotBlank() }

    val gradleVersion: String? get() = prop("vendor.it.gradleVersion")
    val gradleHome: String? get() = prop("vendor.it.gradleHome")
    val warningMode: String? get() = prop("vendor.it.warningMode")

    /** The Gradle the builds run on: the pinned version, the installed distribution, or the one running the tests. */
    val targetVersion: GradleVersion
        get() = gradleVersion?.let { GradleVersion.version(it) }
            ?: gradleHome?.let { GradleVersion.version(File(it).name.removePrefix("gradle-")) }
            ?: GradleVersion.current()

    /**
     * Turns Isolated Projects on. Gradle 9.7 renamed the property, and with `--warning-mode=fail` the old name of a
     * newer Gradle is a failure of its own.
     */
    val isolatedProjectsFlag: String
        get() = if (targetVersion >= GradleVersion.version("9.7")) {
            "-Dorg.gradle.isolated-projects=true"
        } else {
            "-Dorg.gradle.unsafe.isolated-projects=true"
        }

    fun runner(projectDir: File, vararg args: String): GradleRunner {
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
        val home = gradleHome
        val version = gradleVersion
        when {
            home != null -> runner.withGradleInstallation(File(home))
            version != null -> runner.withGradleVersion(version)
        }
        val arguments = args.toMutableList()
        arguments += "--stacktrace"
        warningMode?.let { arguments += "--warning-mode=$it" }
        return runner.withArguments(arguments)
    }

    fun build(projectDir: File, vararg args: String): BuildResult = runner(projectDir, *args).build()

    fun buildAndFail(projectDir: File, vararg args: String): BuildResult = runner(projectDir, *args).buildAndFail()
}

/** The file names printed by `printClasspaths` for [prefix] (`COMPILE` or `RUNTIME`). */
fun classpath(output: String, prefix: String): List<String> {
    val line = output.lineSequence().firstOrNull { it.startsWith("$prefix=") }
        ?: error("no '$prefix=' line in the build output:\n$output")
    return line.removePrefix("$prefix=").removeSurrounding("[", "]").split(", ").filter { it.isNotBlank() }
}

/** All entries of a zip: name to content. */
fun zipEntries(zip: File): Map<String, ByteArray> = ZipFile(zip).use { zf ->
    zf.entries().asSequence().filterNot { it.isDirectory }.associate { it.name to zf.getInputStream(it).readBytes() }
}

/** Serves one zip over HTTP on a free local port and records every request. */
class ZipServer(private val zip: File, private val path: String) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val requests = CopyOnWriteArrayList<String>()

    val port: Int get() = server.address.port
    val urlPattern: String get() = "http://127.0.0.1:$port${path.replace(DemoSdk.VERSION, "{version}")}"
    val getCount: Int get() = requests.count { it == "GET $path" }

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use {
                requests += "${it.requestMethod} ${it.requestURI.path}"
                if (it.requestURI.path != path) {
                    it.sendResponseHeaders(404, -1)
                } else if (it.requestMethod == "HEAD") {
                    it.responseHeaders.add("Content-Length", zip.length().toString())
                    it.sendResponseHeaders(200, -1)
                } else {
                    val bytes = zip.readBytes()
                    it.sendResponseHeaders(200, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
