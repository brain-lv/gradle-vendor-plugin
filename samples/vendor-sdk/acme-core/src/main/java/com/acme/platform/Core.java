/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package com.acme.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

/** The vendor API: its public methods expose Jackson, its code uses commons-lang3 only inside. */
public class Core {
    public ObjectMapper mapper() {
        return new ObjectMapper();
    }

    public String title(String text) {
        return StringUtils.capitalize(text);
    }
}
