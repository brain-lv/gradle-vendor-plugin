/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

// An application that uses the sample distribution. Build it first:
//   ./gradlew -p samples/vendor-sdk packageVendorDistribution
pluginManagement {
    includeBuild("../..")
}

plugins {
    id("lv.brain.vendor-consumer")
}

rootProject.name = "acme-app"
