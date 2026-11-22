/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import org.gradle.api.GradleException
import org.gradle.api.initialization.Settings
import java.io.File

internal object VendorSample {
    private const val KEY = "vendor.sample"
    private const val MARKER = "$KEY=DELETE THIS LINE after adjusting the values below"
    private const val DOCS = "https://github.com/brain-lv/gradle-vendor-plugin#recommended-setup"

    enum class Role(val pluginId: String) { DISTRIBUTOR("lv.brain.vendor-distributor"), CONSUMER("lv.brain.vendor-consumer") }

    fun check(settings: Settings, role: Role) {
        val file = File(settings.rootDir, "vendor.properties")
        val local = File(settings.rootDir, "vendor.local.properties")
        if (!file.exists() && !local.exists() && !configuredElsewhere(settings, role)) {
            file.writeText(sample(role))
            throw GradleException(
                "No vendor.properties was found, so the ${role.pluginId} plugin created a sample at $file.\n" +
                    "Adjust the values in it as its comments explain, then delete the line its first comment points to. " +
                    "Until then every build stops here.\nSee $DOCS"
            )
        }
        val sample = listOf(file, local).firstOrNull(::hasMarker) ?: return
        throw GradleException(
            "$sample is still the generated sample. Adjust the values in it as its comments explain, then delete the line " +
                "its first comment points to. Until then every build stops here.\nSee $DOCS"
        )
    }

    private fun configuredElsewhere(settings: Settings, role: Role): Boolean {
        val providers = settings.providers
        val keys = if (role == Role.CONSUMER) listOf("vendor.version", "vendor.distributions") else listOf("vendor.version")
        return keys.any { !providers.gradleProperty(it).orNull.isNullOrBlank() || !providers.systemProperty(it).orNull.isNullOrBlank() } ||
            !providers.environmentVariable("VENDOR_VERSION").orNull.isNullOrBlank()
    }

    private fun hasMarker(file: File): Boolean = file.isFile && file.readLines().any { line ->
        val trimmed = line.trim()
        !trimmed.startsWith("#") && !trimmed.startsWith("!") &&
            trimmed.substringBefore('=').substringBefore(':').trim() == KEY
    }

    fun sample(role: Role): String = when (role) {
        Role.DISTRIBUTOR -> """
            # ---------------------------------------------------------------------------------------------------------
            # vendor.properties of the lv.brain.vendor-distributor plugin
            #
            # The plugin created this sample because the project had no vendor.properties. Every build stops while
            # the next line is in this file: adjust the values below to your vendor SDK, then DELETE THE NEXT LINE.
            # ---------------------------------------------------------------------------------------------------------
            $MARKER

            # The version of the distribution this project builds.
            # It names the ZIP (build/distributions/vendor-sdk-<version>.zip) and is the version of every bundle,
            # so consumers write it into their own vendor.version. Use a new version for every release you publish.
            vendor.version=1.0.0

            # The folder that holds the vendor jars, relative to this file.
            # Every jar below it is scanned; sub-folders are fine. Hidden folders, a top-level build/ and the generated
            # ivy/ folder are always skipped.
            vendor.dir=vendor

            # Optional: categories. Each category becomes one bundle that consumers add with vendor.bundle("<name>").
            # The value lists folders below vendor.dir or Ant-style patterns, comma separated. A jar goes into the first
            # category that matches it; jars that match none are left out.
            # Without any category line, every jar goes into one bundle named "core".
            #vendor.category.client=client
            #vendor.category.server=server

            # Optional, recommended for large SDKs: the Java packages of the vendor's own API, comma separated.
            # Only jars with classes in these packages (and the jars their public API needs) are on the compile classpath
            # of consumers; all other jars are runtime only, which keeps compilation fast and the classpath small.
            #vendor.compilePackages=com.example.vendor

            # Optional: compile against API-only copies of the compile jars (-slim.jar). Runtime keeps the full jars.
            #vendor.slimJars=true

            # More options (signing, checks, ZIP contents, patches): $DOCS
            """.trimIndent() + "\n"
        Role.CONSUMER -> """
            # ---------------------------------------------------------------------------------------------------------
            # vendor.properties of the lv.brain.vendor-consumer plugin
            #
            # The plugin created this sample because the project had no vendor.properties. Every build stops while
            # the next line is in this file: adjust the values below to your distribution, then DELETE THE NEXT LINE.
            # ---------------------------------------------------------------------------------------------------------
            $MARKER

            # The version of the vendor distribution this project uses: the vendor.version the distributor built.
            # Each version is downloaded once and kept in ~/.gradle/caches/vendor-sdk/<version>.
            vendor.version=1.0.0

            # Where the distribution ZIP is downloaded from: an https:// URL of your artifact server, release page or
            # file share. {version} is replaced with vendor.version, so the line stays the same for every release.
            vendor.url=https://repo.example.com/vendor/vendor-sdk-{version}.zip

            # Optional: use a ZIP built on this machine instead of downloading. Put this line into
            # vendor.local.properties next to this file; that file stays out of Git and overrides this one.
            #vendor.localZip=../vendor-sdk/build/distributions/vendor-sdk-1.0.0.zip

            # Optional, recommended: the public key of the distributor. The ZIP is unpacked only when its signature
            # matches. The distributor's task generateVendorSigningKey prints the value.
            #vendor.signing.publicKey=MCowBQYDK2VwAyEA...

            # Optional: a token for a server that needs one (better given as the environment variable VENDOR_AUTH_TOKEN).
            #vendor.authToken=

            # More options (several vendors, checks, caching): $DOCS
            """.trimIndent() + "\n"
    }
}
