/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.support

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A vendor SDK folder `sdk` that is distributed with the distributor plugin, and an application folder `app` that
 * consumes it with the consumer plugin, below [root]. The scripts of both are generated in [dsl].
 */
class Workspace(val dsl: Dsl, val root: File) {

    val sdkDir: File get() = File(root, "sdk")
    val appDir: File get() = File(root, "app")

    /** Writes the demo SDK with a `vendorDistributor` block for [settings]; [properties] go into its `vendor.properties`. */
    fun createSdk(settings: DistributorSettings, vararg properties: String): DemoSdk {
        val sdk = DemoSdk(sdkDir).createJars()
        sdk.writeProperties("vendor.version=${DemoSdk.VERSION}", *properties)
        File(sdkDir, dsl.settingsFile).writeText(Scripts.distributorSettings(dsl, settings))
        File(sdkDir, dsl.buildFile).writeText("")
        return sdk
    }

    /** Writes the application. By default it takes the SDK from the folder next to it; [localProperties] replaces that. */
    fun createApp(
        deps: List<Dep>,
        includes: List<String> = emptyList(),
        localProperties: List<String> = listOf("vendor.localDir=../sdk/vendor"),
        localMavenRepo: Boolean = false,
        extraSource: String? = null,
        buildScriptExtra: String = ""
    ) {
        appDir.mkdirs()
        File(appDir, "vendor.properties").writeText(
            "vendor.version=${DemoSdk.VERSION}\nvendor.url=https://artifacts.invalid/vendor-sdk/vendor-sdk-{version}.zip\n"
        )
        File(appDir, "vendor.local.properties").writeText(localProperties.joinToString("\n") + "\n")
        File(appDir, dsl.settingsFile).writeText(Scripts.consumerSettings(dsl, includes = includes))
        File(appDir, dsl.buildFile).writeText(Scripts.consumerBuild(dsl, deps, localMavenRepo) + buildScriptExtra)
        Scripts.writeApp(appDir, extraSource)
    }

    fun distribution(): Map<String, ByteArray> =
        zipEntries(File(sdkDir, "build/distributions/vendor-sdk-${DemoSdk.VERSION}.zip"))

    fun gradle(dir: File, vararg args: String): BuildResult = GradleEnv.build(dir, *args)

    fun gradleFails(dir: File, vararg args: String): BuildResult = GradleEnv.buildAndFail(dir, *args)
}

/** The test body of a scenario is written once and a subclass per build script language runs it. */
abstract class PluginTestBase(protected val dsl: Dsl) {

    @TempDir
    lateinit var tmp: File

    protected val ws: Workspace by lazy { Workspace(dsl, tmp) }

    protected val sdkDir: File get() = ws.sdkDir
    protected val appDir: File get() = ws.appDir

    protected fun createSdk(settings: DistributorSettings, vararg properties: String) = ws.createSdk(settings, *properties)

    protected fun createApp(
        deps: List<Dep>,
        includes: List<String> = emptyList(),
        localProperties: List<String> = listOf("vendor.localDir=../sdk/vendor"),
        localMavenRepo: Boolean = false,
        extraSource: String? = null,
        buildScriptExtra: String = ""
    ) = ws.createApp(deps, includes, localProperties, localMavenRepo, extraSource, buildScriptExtra)

    protected fun gradle(dir: File, vararg args: String): BuildResult = ws.gradle(dir, *args)

    protected fun gradleFails(dir: File, vararg args: String): BuildResult = ws.gradleFails(dir, *args)

    protected fun distribution(): Map<String, ByteArray> = ws.distribution()

    /** Failure messages name the language (and with it, which of the two scripts broke). */
    protected fun why(detail: Any?): String = "[${dsl.label}] $detail"
}

/** Names of the modules a Gradle module metadata file lists as dependencies of [variant], sorted. */
@Suppress("UNCHECKED_CAST")
fun moduleDependencies(files: Map<String, ByteArray>, path: String, variant: String): List<String> {
    val json = JsonSlurper().parseText(String(files.getValue(path))) as Map<String, Any?>
    val variants = json["variants"] as List<Map<String, Any?>>
    val dependencies = variants.single { it["name"] == variant }["dependencies"] as? List<Map<String, Any?>> ?: emptyList()
    return dependencies.map { it["module"] as String }.sorted()
}
