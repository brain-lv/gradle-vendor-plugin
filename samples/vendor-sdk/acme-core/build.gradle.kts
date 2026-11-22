/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    compileOnly("org.apache.commons:commons-lang3:3.14.0")
}

// Like many vendor jars: no version in the file name and no Maven metadata inside.
tasks.jar {
    archiveVersion.set("")
}
