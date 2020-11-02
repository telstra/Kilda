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

import org.openkilda.model.GroupId;

import org.projectfloodlight.openflow.types.DatapathId;

public class SwitchMissingGroupException extends SwitchOperationException {
    private final GroupId groupId;

    public SwitchMissingGroupException(DatapathId dpId, GroupId groupId) {
        super(dpId, String.format("Group %d not found on %s", groupId.getValue(), dpId));
        this.groupId = groupId;
    }
}
