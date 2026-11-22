/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import org.gradle.api.GradleException
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleSupportTest {

    @Test
    fun `Gradle 8_11 with its Kotlin 2 runtime is the minimum`() {
        assertEquals("8.11", GradleSupport.MINIMUM_VERSION.version)
    }

    @Test
    fun `Gradle 8_11 and newer are accepted`() {
        listOf("8.11", "8.14.3", "9.0", "9.9.1").forEach {
            GradleSupport.requireSupported("lv.brain.vendor-consumer", GradleVersion.version(it))
        }
    }

    @Test
    fun `an older Gradle is rejected with the plugin, both versions and the way out`() {
        val failure = assertThrows(GradleException::class.java) {
            GradleSupport.requireSupported("lv.brain.vendor-distributor", GradleVersion.version("8.10.2"))
        }

        val message = failure.message.orEmpty()
        assertTrue("lv.brain.vendor-distributor" in message, message)
        assertTrue("8.11" in message && "8.10.2" in message, message)
        assertTrue("wrapper" in message, message)
    }
}
