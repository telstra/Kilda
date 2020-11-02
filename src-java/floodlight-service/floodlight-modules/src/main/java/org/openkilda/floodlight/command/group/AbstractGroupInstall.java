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
import org.openkilda.messaging.MessageContext;
import org.openkilda.messaging.Utils;
import org.openkilda.model.MirrorConfig;
import org.openkilda.model.SwitchId;

import lombok.Getter;
import org.projectfloodlight.openflow.protocol.OFBucket;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFGroupAdd;
import org.projectfloodlight.openflow.protocol.OFGroupMod;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Getter
abstract class AbstractGroupInstall<T extends SpeakerCommandReport> extends GroupCommand<T> {
    // payload
    protected final MirrorConfig mirrorConfig;

    AbstractGroupInstall(MessageContext messageContext, SwitchId switchId, MirrorConfig mirrorConfig) {
        super(messageContext, switchId);
        this.mirrorConfig = mirrorConfig;
    }

    protected OFGroupMod makeGroupAddMessage() {
        final OFFactory ofFactory = getSw().getOFFactory();
        OFActionOutput mainOutput = ofFactory.actions().buildOutput()
                .setPort(OFPort.of(mirrorConfig.getMainPort())).build();
        OFBucket mainBucket = ofFactory.buildBucket()
                .setActions(Collections.singletonList(mainOutput))
                .setWatchGroup(OFGroup.ANY)
                .build();

        List<OFAction> mirrorActions = new ArrayList<>();
        mirrorActions.add(ofFactory.actions().buildOutput()
                .setPort(OFPort.of(mirrorConfig.getMirrorPort())).build());
        int mirrorVlan = mirrorConfig.getMirrorVlan();
        if (mirrorVlan > 0) {
            mirrorActions.add(ofFactory.actions().buildPushVlan().setEthertype(EthType.of(Utils.ETH_TYPE)).build());
            mirrorActions.add(ofFactory.actions().buildSetField().setField(ofFactory.oxms().buildVlanVid()
                    .setValue(OFVlanVidMatch.ofVlan(mirrorVlan)).build())
                    .build());
        }
        OFBucket mirrorBucket = ofFactory.buildBucket().setActions(mirrorActions).setWatchGroup(OFGroup.ANY).build();
        OFGroupAdd groupMod = ofFactory.buildGroupAdd()
                .setGroup(OFGroup.of((int) mirrorConfig.getGroupId().getValue()))
                .setGroupType(OFGroupType.ALL)
                .setBuckets(Arrays.asList(mainBucket, mirrorBucket)).build();
        return groupMod;
    }
}
