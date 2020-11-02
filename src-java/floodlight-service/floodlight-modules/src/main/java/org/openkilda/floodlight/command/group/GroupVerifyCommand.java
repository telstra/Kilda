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

import static java.util.stream.Collectors.toList;

import org.openkilda.floodlight.command.SpeakerCommandProcessor;
import org.openkilda.floodlight.converter.OfFlowStatsMapper;
import org.openkilda.floodlight.error.SwitchIncorrectMirrorGroupException;
import org.openkilda.floodlight.error.SwitchMissingGroupException;
import org.openkilda.floodlight.error.UnsupportedSwitchOperationException;
import org.openkilda.floodlight.utils.CompletableFutureAdapter;
import org.openkilda.messaging.MessageContext;
import org.openkilda.messaging.info.rule.GroupBucket;
import org.openkilda.messaging.info.rule.GroupEntry;
import org.openkilda.model.GroupId;
import org.openkilda.model.MirrorConfig;
import org.openkilda.model.SwitchId;

import org.projectfloodlight.openflow.protocol.OFGroupDescStatsEntry;
import org.projectfloodlight.openflow.protocol.OFGroupDescStatsReply;
import org.projectfloodlight.openflow.protocol.OFGroupDescStatsRequest;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFGroup;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class GroupVerifyCommand extends AbstractGroupInstall<GroupVerifyReport> {
    public GroupVerifyCommand(
            MessageContext messageContext, SwitchId switchId, MirrorConfig mirrorConfig) {
        super(messageContext, switchId, mirrorConfig);
    }

    @Override
    protected CompletableFuture<GroupVerifyReport> makeExecutePlan(SpeakerCommandProcessor commandProcessor)
            throws UnsupportedSwitchOperationException {
        ensureSwitchSupportGroups();

        return new CompletableFutureAdapter<>(
                messageContext, getSw().writeStatsRequest(makeGroupReadCommand()))
                .thenApply(this::handleGroupStats)
                .thenApply(this::makeSuccessReport);
    }

    @Override
    protected GroupVerifyReport makeReport(Exception error) {
        return new GroupVerifyReport(this, error);
    }

    private GroupVerifyReport makeSuccessReport(MirrorConfig mirrorConfig) {
        return new GroupVerifyReport(this, mirrorConfig);
    }

    private OFGroupDescStatsRequest makeGroupReadCommand() {
        return getSw().getOFFactory().buildGroupDescStatsRequest()
                .setGroup(OFGroup.of(mirrorConfig.getGroupId().intValue()))
                .build();
    }

    private MirrorConfig handleGroupStats(List<OFGroupDescStatsReply> groupDescStatsReply) {
        OFGroupDescStatsEntry target = null;
        List<OFGroupDescStatsEntry> groupDescStatsEntries = groupDescStatsReply.stream()
                .map(OFGroupDescStatsReply::getEntries)
                .flatMap(List::stream)
                .collect(toList());
        for (OFGroupDescStatsEntry entry : groupDescStatsEntries) {

            if (mirrorConfig.getGroupId().intValue() == entry.getGroup().getGroupNumber()) {
                target = entry;
                break;
            }
        }

        if (target == null) {
            throw maskCallbackException(new SwitchMissingGroupException(getSw().getId(), mirrorConfig.getGroupId()));
        }

        validateGroupConfig(target);

        return mirrorConfig;
    }

    private MirrorConfig fromStatsEntry(OFGroupDescStatsEntry entry) {
        GroupEntry groupEntry = OfFlowStatsMapper.INSTANCE.toFlowGroupEntry(entry);
        GroupId groupId = new GroupId(groupEntry.getGroupId());
        if (groupEntry.getBuckets().size() != 2) {
            return null;
        }

        GroupBucket mainBucket = groupEntry.getBuckets().get(0);
        int mainPort = Integer.valueOf(mainBucket.getApplyActions().getFlowOutput());
        int mainVlan = mainBucket.getApplyActions().getSetFieldActions().stream().filter(
                action -> (MatchField.VLAN_VID.getName().equals(action.getFieldName())))
                .map(action -> Integer.valueOf(action.getFieldValue())).findFirst().orElse(0);

        GroupBucket mirrorBucket = groupEntry.getBuckets().get(1);

        int mirrorPort = Integer.valueOf(mirrorBucket.getApplyActions().getFlowOutput());
        int mirrorVlan = mirrorBucket.getApplyActions().getSetFieldActions().stream().filter(
                action -> (MatchField.VLAN_VID.getName().equals(action.getFieldName())))
                .map(action -> Integer.valueOf(action.getFieldValue())).findFirst().orElse(0);

        return MirrorConfig.builder().groupId(groupId).mainPort(mainPort).mainVlan(mainVlan)
                .mirrorPort(mirrorPort).mirrorVlan(mirrorVlan).build();
    }


    private void validateGroupConfig(OFGroupDescStatsEntry group) {
        DatapathId datapathId = getSw().getId();
        MirrorConfig actual = fromStatsEntry(group);
        if (mirrorConfig.equals(actual)) {
            throw maskCallbackException(new SwitchIncorrectMirrorGroupException(
                    datapathId, mirrorConfig, actual));
        }
    }
}
