/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.core

import org.gradle.api.GradleException
import org.gradle.util.GradleVersion

object GradleSupport {
    val MINIMUM_VERSION: GradleVersion = GradleVersion.version("8.8")

    fun requireSupported(pluginId: String, current: GradleVersion = GradleVersion.current()) {
        if (current < MINIMUM_VERSION) {
            throw GradleException(
                "The plugin '$pluginId' needs Gradle ${MINIMUM_VERSION.version} or newer, this build runs on Gradle " +
                    "${current.version}. It registers its project setup with gradle.lifecycle.beforeProject, which is " +
                    "what Isolated Projects and the configuration cache require. Upgrade the Gradle wrapper " +
                    "(./gradlew wrapper --gradle-version ${MINIMUM_VERSION.version}, or newer)."
            )
        }
    }
}
