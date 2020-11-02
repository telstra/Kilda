/* Copyright 2020 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.floodlight.error;

import org.openkilda.model.MirrorConfig;

import org.projectfloodlight.openflow.types.DatapathId;

public class SwitchIncorrectMirrorGroupException extends SwitchOperationException {
    private final MirrorConfig expectedConfig;
    private final MirrorConfig actualConfig;

    public SwitchIncorrectMirrorGroupException(
            DatapathId dpId, MirrorConfig expectedConfig, MirrorConfig actualConfig) {
        super(dpId, formatMessage(dpId, expectedConfig, actualConfig));
        this.expectedConfig = expectedConfig;
        this.actualConfig = actualConfig;
    }

    private static String formatMessage(
            DatapathId dpId, MirrorConfig expected, MirrorConfig actual) {
        return String.format(
                "Group %d on %s have incorrect config - actual value is %s while expected value is %s",
                expected.getGroupId().intValue(), dpId, actual, expected);
    }
}
