/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package com.example.app;

import com.acme.platform.Core;

public class Main {
    public static void main(String[] args) throws Exception {
        Core core = new Core();
        // mapper() returns a Jackson ObjectMapper: Jackson is on the compile classpath because the vendor API exposes it.
        System.out.println(core.mapper().writeValueAsString(java.util.Map.of("title", core.title("acme platform"))));
        // commons-lang3 is only used inside Core, so it is runtime only; this line would not compile:
        // org.apache.commons.lang3.StringUtils.capitalize("x");
    }
}
