/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

repositories {
    mavenCentral()
}

// The open-source jars of the delivery. A real vendor ships them in its folder; the sample downloads them instead of
// committing binaries.
val openSource = configurations.create("openSource") {
    isTransitive = false
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

dependencies {
    add("openSource", "org.apache.commons:commons-lang3:3.14.0")
    add("openSource", "commons-io:commons-io:2.15.1")
    add("openSource", "com.google.guava:guava:33.0.0-jre")
    add("openSource", "com.google.guava:failureaccess:1.0.2")
    add("openSource", "com.fasterxml.jackson.core:jackson-databind:2.16.1")
    add("openSource", "com.fasterxml.jackson.core:jackson-core:2.16.1")
    add("openSource", "com.fasterxml.jackson.core:jackson-annotations:2.16.1")
    add("openSource", "org.springframework:spring-core:6.1.3")
    add("openSource", "org.springframework:spring-jcl:6.1.3")
    add("openSource", "org.springframework:spring-beans:6.1.3")
    add("openSource", "org.springframework:spring-context:6.1.3")
    add("openSource", "org.springframework:spring-aop:6.1.3")
    add("openSource", "org.springframework:spring-expression:6.1.3")
    add("openSource", "org.springframework.boot:spring-boot:3.2.2")
    add("openSource", "org.springframework.boot:spring-boot-autoconfigure:3.2.2")
}

val downloadOpenSourceJars = tasks.register<Sync>("downloadOpenSourceJars") {
    group = "vendor sample"
    description = "Puts the open-source jars of the sample delivery into acme-platform/lib."
    from(openSource)
    into(layout.projectDirectory.dir("acme-platform/lib"))
}

val copyVendorJars = tasks.register<Sync>("copyVendorJars") {
    group = "vendor sample"
    description = "Puts the vendor's own jar into acme-platform/modules."
    from(project(":acme-core").tasks.named("jar"))
    into(layout.projectDirectory.dir("acme-platform/modules"))
}

listOf(
    "resolveVendorModules", "generateVendorIvyRepo", "generateVendorSbom", "packageVendorDistribution",
    "verifyVendorAgainstMaven", "exportVendorMavenRepository", "diffVendorDistribution"
).forEach { name ->
    tasks.named(name) { dependsOn(downloadOpenSourceJars, copyVendorJars) }
}
