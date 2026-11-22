/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LicensePolicyTest {

    @Test
    fun `the common spellings of a licence map to one SPDX id`() {
        mapOf(
            "The Apache Software License, Version 2.0" to "Apache-2.0",
            "https://www.apache.org/licenses/LICENSE-2.0.txt" to "Apache-2.0",
            "The MIT License" to "MIT",
            "GNU Lesser General Public License, Version 2.1" to "LGPL-2.1",
            "GPL2 w/ CPE" to "GPL-2.0-with-classpath-exception",
            "GNU General Public License v3.0" to "GPL-3.0",
            "Eclipse Public License - v 2.0" to "EPL-2.0",
            "Eclipse Distribution License - v 1.0" to "BSD-3-Clause",
            "BSD 2-Clause License" to "BSD-2-Clause",
            "Some Vendor Licence" to "Some Vendor Licence"
        ).forEach { (name, id) -> assertEquals(id, LicensePolicy.normalize(name), name) }
    }

    @Test
    fun `a jar is accepted when one of its licences is allowed and not denied`() {
        val policy = LicensePolicy(allow = listOf("Apache-2.0", "MIT", "BSD-*", "EPL-2.0"), deny = listOf("*GPL*"))

        assertTrue(policy.accepts(listOf("The Apache Software License, Version 2.0")))
        assertTrue(policy.accepts(listOf("GPL2 w/ CPE", "Eclipse Public License - v 2.0")))
        assertFalse(policy.accepts(listOf("GNU General Public License v3.0")))
        assertFalse(policy.accepts(listOf("Some Vendor Licence")))
        assertTrue(LicensePolicy(deny = listOf("AGPL-*")).accepts(listOf("Some Vendor Licence")))
    }
}
