/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

import org.gradle.plugin.compatibility.compatibility

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    signing

    id("com.gradle.plugin-publish") version "2.2.1"
    id("com.diffplug.spotless") version "8.11.0"
}

spotless {
    val mplHeader = """
        /* This Source Code Form is subject to the terms of the Mozilla Public
         * License, v. 2.0. If a copy of the MPL was not distributed with this
         * file, You can obtain one at https://mozilla.org/MPL/2.0/.
         *
         * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
         */
    """.trimIndent() + "\n\n"

    kotlin {
        target("src/**/*.kt")
        licenseHeader(mplHeader)
    }

    kotlinGradle {
        target("*.gradle.kts")
        licenseHeader(mplHeader, "(plugins|pluginManagement|dependencyResolutionManagement|rootProject|import|@file)")
    }
}
group = "lv.brain"
version = "2.0.2"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation("org.ow2.asm:asm:9.11.1")
    // ClassRemapper/Remapper live in asm-commons; the relocated copy inside Gradle (org.gradle.internal.impldep)
    // is internal API and not visible to published plugins at runtime.
    implementation("org.ow2.asm:asm-commons:9.11.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.+")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock-standalone:3.+")
}

tasks.test {
    useJUnitPlatform {
        // only run by the integrationTestGradle* tasks below, on the Gradle versions of the matrix
        excludeTags("gradle-matrix-only")
    }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

// Integration tests on the latest Gradle 8 and the latest Gradle 9: every test of the Groovy and the Kotlin DSL runs on both,
// plus the scenarios that only make sense there (download, configuration cache, laziness). The Gradle that builds the plugin
// is not necessarily the newest one, so the versions are pinned here and not taken from the wrapper.
//
//   ./gradlew integrationTest                        both versions
//   ./gradlew integrationTestGradle9                 one of them
//   ./gradlew check -x integrationTest               everything else
//   -PintegrationGradle9=9.9.2                       another version (same for integrationGradle8)
//   -PintegrationGradleHome9=/opt/gradle-9.9.1       an installed distribution instead of a download (air-gapped CI)
//   -PintegrationWarningMode=all                     print deprecation warnings instead of failing the build on them
//
// A deprecation warning fails the run by default: what is deprecated on Gradle 8 is removed or rejected later, so this is
// how the plugin is checked against the next major version.
val integrationGradleVersions = mapOf("8" to "8.14.6", "9" to "9.9.1")

val integrationTestTasks = integrationGradleVersions.map { (major, defaultVersion) ->
    tasks.register<Test>("integrationTestGradle$major") {
        group = "verification"
        description = "Runs the Groovy and Kotlin DSL integration tests on Gradle $major (-PintegrationGradle$major=<version>)."
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("gradle-matrix", "gradle-matrix-only") }
        systemProperty("vendor.it.gradleVersion", providers.gradleProperty("integrationGradle$major").getOrElse(defaultVersion))
        providers.gradleProperty("integrationGradleHome$major").orNull?.let { systemProperty("vendor.it.gradleHome", it) }
        systemProperty("vendor.it.warningMode", providers.gradleProperty("integrationWarningMode").getOrElse("fail"))
        testLogging {
            events("passed", "skipped", "failed")
        }
        shouldRunAfter(tasks.test)
    }
}

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs the integration tests on the latest Gradle 8 and Gradle 9."
    dependsOn(integrationTestTasks)
}

tasks.check {
    dependsOn("integrationTest")
}

gradlePlugin {
    website.set("https://github.com/brain-lv/gradle-vendor-plugin")
    vcsUrl.set("https://github.com/brain-lv/gradle-vendor-plugin.git")

    plugins {
        create("vendorDistributor") {
            id = "lv.brain.vendor-distributor"
            displayName = "Vendor JARs Distributor: package local JARs as a dependency repository"
            description = "Turns a folder of third-party or proprietary JARs without Maven coordinates or a repository " +
                "into one versioned ZIP with proper dependency metadata. A modern replacement for flatDir and " +
                "fileTree(\"libs\"): automatic dependency detection between JARs, category bundles, slim compile " +
                "JARs, build cache and configuration cache support."
            tags.set(listOf("dependencies", "local-jars", "flatdir", "vendor", "sdk", "repository", "packaging", "ivy"))
            implementationClass = "lv.brain.gradle.distributor.VendorDistributorSettingsPlugin"
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
        create("vendorConsumer") {
            id = "lv.brain.vendor-consumer"
            displayName = "Vendor JARs Consumer: use a packaged vendor SDK like normal dependencies"
            description = "Downloads, caches and wires a vendor JAR distribution into every project of a build, so " +
                "unpublished JARs are used like Maven dependencies: vendor.bundle(\"client\"), a version per branch, " +
                "local overrides and a classpath clash check. Works with the configuration cache and Isolated Projects."
            tags.set(listOf("dependencies", "local-jars", "flatdir", "vendor", "sdk", "offline", "cache"))
            implementationClass = "lv.brain.gradle.consumer.VendorConsumerSettingsPlugin"
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
        create("vendorPatcher") {
            id = "lv.brain.vendor-patcher"
            displayName = "Vendor Patcher: apply numbered patch files to vendor JARs and folders"
            description = "Applies numbered unified-diff patches (001-fix.patch, 002-...) to a copy of a vendor " +
                "folder, including text files inside JARs (app.jar!/META-INF/app.xml). Stops at the first failing step " +
                "with the file, hunk, likely cause and fix. Cacheable; applied automatically by " +
                "lv.brain.vendor-distributor with vendor.patcher=true."
            tags.set(listOf("patch", "diff", "vendor", "jar", "local-jars", "dependencies"))
            implementationClass = "lv.brain.gradle.patcher.VendorPatcherPlugin"
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
        create("vendorConsumerProject") {
            id = "lv.brain.vendor-consumer-project"
            displayName = "Vendor JARs Consumer (project helper)"
            description = "Typed vendor.bundle(...) and vendor.module(...) accessors for the Kotlin DSL. Applied " +
                "automatically by lv.brain.vendor-consumer."
            tags.set(listOf("vendor", "kotlin-dsl", "dependencies"))
            implementationClass = "lv.brain.gradle.consumer.VendorConsumerProjectPlugin"
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Vendor JARs Gradle Plugins")
            description.set("Package local third-party or proprietary JARs as a versioned dependency repository and use them like Maven dependencies; a modern replacement for flatDir.")
            url.set("https://github.com/brain-lv/gradle-vendor-plugin")
            licenses {
                license {
                    name.set("Mozilla Public License 2.0")
                    url.set("https://www.mozilla.org/en-US/MPL/2.0/")
                }
            }
            developers {
                developer {
                    id.set("brain-lv")
                    name.set("brain-lv")
                    url.set("https://brain.lv")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/brain-lv/gradle-vendor-plugin.git")
                developerConnection.set("scm:git:ssh://github.com/brain-lv/gradle-vendor-plugin.git")
                url.set("https://github.com/brain-lv/gradle-vendor-plugin")
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("GRADLE_PUBLISH_SIGNING_KEY").orNull
    val signingKeyId = providers.environmentVariable("GRADLE_PUBLISH_SIGNING_KEY_ID").orNull
    val signingPassword = providers.environmentVariable("GRADLE_PUBLISH_SIGNING_SECRET").orNull

    // Default false during configuration; updated safely in taskGraph.whenReady below
    isRequired = false

    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    }

    sign(publishing.publications)
}

gradle.taskGraph.whenReady {
    val isPublishingToPortal = allTasks.any { it.name == "publishPlugins" }

    extensions.configure<SigningExtension> {
        isRequired = isPublishingToPortal
    }

    // Guarantees Sign tasks never run during publishToMavenLocal even if env vars exist locally
    tasks.withType<Sign>().configureEach {
        onlyIf { isPublishingToPortal }
    }
}