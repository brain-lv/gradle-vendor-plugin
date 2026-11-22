/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

import org.gradle.plugin.compatibility.compatibility

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.sam.with.receiver)
    `java-gradle-plugin`
    jacoco
    signing

    alias(libs.plugins.plugin.publish)
    alias(libs.plugins.shadow)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.spotless)
}

spotless {
    val mplHeader = """
        /* This Source Code Form is subject to the terms of the Mozilla Public
         * License, v. 2.0. If a copy of the MPL was not distributed with this
         * file, You can obtain one at https://mozilla.org/MPL/2.0/.
         *
         * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
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
version = "3.0.1"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

// The Kotlin standard library of Gradle 8.11, checked by KotlinStdlibFloorTest.
val kotlinStdlibFloor: Configuration = configurations.create("kotlinStdlibFloor") {
    isCanBeConsumed = false
    isTransitive = false
}

kotlin {
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.velocity) {
        exclude(group = "org.slf4j")
    }

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.wiremock)

    testRuntimeOnly(libs.junit.platform.launcher)

    kotlinStdlibFloor(libs.kotlin.stdlib.floor)
}

// Velocity, commons-lang3 and ASM are shaded into the plugin jar, so they never clash with other plugins.
val shadedPackage = "lv.brain.gradle.shaded"
val velocityDefaults = tasks.register<Sync>("relocateVelocityDefaults") {
    group = "build"
    description = "Copies Velocity's default settings with the class names of the shaded Velocity."
    val velocityJar = configurations.runtimeClasspath.map { classpath -> classpath.filter { it.name.startsWith("velocity-engine-core-") } }
    from(velocityJar.map { files -> zipTree(files.singleFile) }) {
        include("org/apache/velocity/runtime/defaults/*.properties")
        filter { line ->
            line.replace("org.apache.velocity", "$shadedPackage.velocity").replace("org.apache.commons.lang3", "$shadedPackage.commons.lang3")
        }
        eachFile { path = path.replace("org/apache/velocity/", "${shadedPackage.replace('.', '/')}/velocity/") }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("generated/velocity-defaults"))
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.apache.velocity", "$shadedPackage.velocity")
    relocate("org.apache.commons.lang3", "$shadedPackage.commons.lang3")
    relocate("org.objectweb.asm", "$shadedPackage.asm")
    exclude("org/apache/velocity/runtime/defaults/*.properties")
    exclude("META-INF/maven/**", "META-INF/versions/*/module-info.class", "module-info.class")
    from(velocityDefaults)
}

// TestKit builds run the shaded plugin in their own Gradle daemons; GradleEnv starts them with this agent.
val jacocoTestKitAgent = tasks.register<Sync>("jacocoTestKitAgent") {
    group = "verification"
    description = "Extracts the JaCoCo agent that the TestKit builds run with."
    from(configurations.named("jacocoAgent").map { agent -> zipTree(agent.singleFile) }) {
        include("jacocoagent.jar")
    }
    into(layout.buildDirectory.dir("jacoco/testkit-agent"))
}

tasks.withType<Test>().configureEach {
    val agentJar = jacocoTestKitAgent.map { it.destinationDir.resolve("jacocoagent.jar") }
    val execFile = layout.buildDirectory.file("jacoco/$name-testkit.exec")
    dependsOn(jacocoTestKitAgent)
    outputs.file(execFile).withPropertyName("testKitCoverage")
    doFirst { execFile.get().asFile.delete() }
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dvendor.it.jacocoAgent=${agentJar.get().absolutePath}", "-Dvendor.it.jacocoExec=${execFile.get().asFile.absolutePath}")
    }
}

val jacocoTestKitReport = tasks.register<JacocoReport>("jacocoTestKitReport") {
    group = "verification"
    description = "Coverage of the plugin as the TestKit builds ran it (the shaded jar)."
    executionData.from(fileTree(layout.buildDirectory.dir("jacoco")) { include("*-testkit.exec") })
    classDirectories.from(tasks.shadowJar.map { jar ->
        zipTree(jar.archiveFile).matching {
            include("lv/brain/gradle/**")
            exclude("lv/brain/gradle/shaded/**")
        }
    })
    sourceDirectories.from(sourceSets["main"].allSource.srcDirs)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    mustRunAfter(tasks.withType<Test>())
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

sonar {
    properties {
        property("sonar.projectKey", "brain-lv_gradle-vendor-plugin")
        property("sonar.organization", "brain-lv")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            listOf("reports/jacoco/test/jacocoTestReport.xml", "reports/jacoco/jacocoTestKitReport/jacocoTestKitReport.xml")
                .joinToString(",") { layout.buildDirectory.file(it).get().asFile.path }
        )
    }
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.setFrom(tasks.shadowJar)
}

tasks.test {
    val floor = kotlinStdlibFloor
    val mainClasses = sourceSets["main"].output.classesDirs
    inputs.files(floor).withPropertyName("kotlinStdlibFloor")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Dvendor.kotlinStdlibFloor=${floor.singleFile.absolutePath}",
            "-Dvendor.mainClasses=${mainClasses.files.joinToString(File.pathSeparator) { it.absolutePath }}"
        )
    }
    useJUnitPlatform {
        excludeTags("gradle-matrix-only")
    }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

// Integration tests on the latest Gradle 8 and 9; options are described in docs/development.md.
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
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
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
            displayName = "Vendor SDK Distributor: turn a folder of JARs into a versioned repository"
            description = "For vendor SDKs delivered as a flat folder of JARs without Maven coordinates. Identifies " +
                "every JAR, works out the dependencies between them from poms and bytecode, groups them into bundles " +
                "and packages one versioned ZIP with a ready-made repository. Checks the delivery before it ships: " +
                "missing classes, licences, Maven Central hashes, Java versions and module path problems, with an " +
                "SBOM, checksum, optional signature and an HTML report. A replacement for flatDir and fileTree(\"libs\")."
            tags.set(listOf("dependencies", "dependency-management", "flatdir", "local-jars", "vendor", "sdk", "repository", "sbom"))
            implementationClass = "lv.brain.gradle.distributor.VendorDistributorSettingsPlugin"
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
        create("vendorConsumer") {
            id = "lv.brain.vendor-consumer"
            displayName = "Vendor SDK Consumer: use a packaged vendor SDK like Maven dependencies"
            description = "Uses a vendor SDK packaged by lv.brain.vendor-distributor in every project of a build: " +
                "vendor.bundle(\"client\") and vendor.module(\"name\") instead of local JAR files. Downloads and " +
                "verifies the ZIP once per version, keeps branch switches offline, supports local overrides and " +
                "several vendors in one build, and checks for classpath clashes and duplicate classes. Works with the " +
                "configuration cache and Isolated Projects."
            tags.set(listOf("dependencies", "dependency-management", "flatdir", "local-jars", "vendor", "sdk", "offline", "isolated-projects"))
            implementationClass = "lv.brain.gradle.consumer.VendorConsumerSettingsPlugin"
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
        create("vendorPatcher") {
            id = "lv.brain.vendor-patcher"
            displayName = "Vendor Patcher: keep your fixes to vendor JARs as numbered patches"
            description = "Applies numbered steps to a copy of a vendor folder: unified diffs (also for text files " +
                "inside JARs), folders with replacement files and classes, and lists of files to remove. Explains a " +
                "failing step with its cause and fix, shows which steps are still needed after a vendor upgrade, and " +
                "turns edits in a workspace into new steps. Cacheable; used by lv.brain.vendor-distributor with " +
                "vendor.patcher=true or on its own."
            tags.set(listOf("patch", "diff", "hotfix", "vendor", "jar", "local-jars"))
            implementationClass = "lv.brain.gradle.patcher.VendorPatcherPlugin"
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
        create("vendorConsumerProject") {
            id = "lv.brain.vendor-consumer-project"
            displayName = "Vendor SDK Consumer (project part)"
            description = "The vendor { } helper of lv.brain.vendor-consumer in a project: vendor.bundle(...), " +
                "vendor.module(...) and vendor.files(...), plus the classpath clash check. Applied to every project by " +
                "lv.brain.vendor-consumer; declare it in a Kotlin build script only to get the typed accessor."
            tags.set(listOf("dependencies", "vendor", "sdk", "local-jars"))
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
                    name.set("Edgars Grinvalds")
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

    tasks.withType<Sign>().configureEach {
        onlyIf { isPublishingToPortal }
    }
}