/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

// A vendor SDK as it is delivered in practice: open-source jars (the Spring jars carry no pom) next to the vendor's
// own jar. The plugin is built from the source of this repository.
pluginManagement {
    includeBuild("../..")
}

plugins {
    id("lv.brain.vendor-distributor")
}

rootProject.name = "acme-platform-sdk"

// The vendor's own code, built into acme-platform/modules/acme-core.jar.
include("acme-core")
