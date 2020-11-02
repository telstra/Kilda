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

import org.openkilda.floodlight.command.SpeakerCommandProcessor;
import org.openkilda.floodlight.service.session.Session;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.GroupId;
import org.openkilda.model.SwitchId;

import net.floodlightcontroller.core.IOFSwitch;
import org.projectfloodlight.openflow.protocol.OFGroupDelete;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.types.OFGroup;

import java.util.concurrent.CompletableFuture;

public class GroupRemoveCommand extends GroupCommand<GroupRemoveReport> {
    private final GroupId groupId;

    public GroupRemoveCommand(MessageContext messageContext, SwitchId switchId, GroupId groupId) {
        super(messageContext, switchId);
        this.groupId = groupId;
    }

    @Override
    protected CompletableFuture<GroupRemoveReport> makeExecutePlan(
            SpeakerCommandProcessor commandProcessor) throws Exception {
        ensureSwitchSupportGroups();

        IOFSwitch sw = getSw();
        OFGroupDelete groupDelete = sw.getOFFactory().buildGroupDelete()
                .setGroup(OFGroup.of(groupId.intValue()))
                .setGroupType(OFGroupType.ALL)
                .build();
        try (Session session = getSessionService().open(messageContext, sw)) {
            return session.write(groupDelete)
                    .thenApply(ignore -> makeSuccessReport());
        }
    }

    @Override
    protected GroupRemoveReport makeReport(Exception error) {
        return new GroupRemoveReport(this, error);
    }

    private GroupRemoveReport makeSuccessReport() {
        return new GroupRemoveReport(this);
    }
}
