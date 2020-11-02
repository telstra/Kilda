/* Copyright 2019 Telstra Open Source
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

package org.openkilda.floodlight.command.group;

import org.openkilda.floodlight.command.SpeakerCommandReport;
import org.openkilda.model.GroupId;

import java.util.Optional;

public class GroupInstallReport extends SpeakerCommandReport {
    private final GroupInstallCommand command;

    public GroupInstallReport(GroupInstallCommand command) {
        this(command, null);
    }

    public GroupInstallReport(GroupInstallCommand command, Exception error) {
        super(command, error);
        this.command = command;
    }

    /**
     * Return groupId of installed group, if it was installed.
     */
    public Optional<GroupId> getGroupId() {
        if (error != null) {
            return Optional.empty();
        }
        return Optional.of(command.getMirrorConfig().getGroupId());
    }
}
