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

import org.openkilda.floodlight.command.SpeakerCommand;
import org.openkilda.floodlight.command.SpeakerCommandReport;
import org.openkilda.model.MirrorConfig;

import java.util.Optional;

public class GroupVerifyReport extends SpeakerCommandReport {
    private final MirrorConfig mirrorConfig;

    public GroupVerifyReport(SpeakerCommand<?> command, MirrorConfig mirrorConfig) {
        this(command, mirrorConfig, null);
    }

    public GroupVerifyReport(SpeakerCommand<?> command, Exception error) {
        this(command, null, error);
    }

    private GroupVerifyReport(SpeakerCommand<?> command, MirrorConfig mirrorConfig, Exception error) {
        super(command, error);
        this.mirrorConfig = mirrorConfig;
    }

    public Optional<MirrorConfig> getMirrorConfig() {
        return Optional.ofNullable(mirrorConfig);
    }
}
