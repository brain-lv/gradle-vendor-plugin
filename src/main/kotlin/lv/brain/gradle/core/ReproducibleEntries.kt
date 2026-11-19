/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.core

import java.time.LocalDateTime
import java.util.zip.ZipEntry

internal object ReproducibleEntries {
    private val FIXED_TIME: LocalDateTime = LocalDateTime.of(1980, 2, 1, 0, 0, 0)

    fun <T : ZipEntry> T.withFixedTime(): T = apply { timeLocal = FIXED_TIME }
}
