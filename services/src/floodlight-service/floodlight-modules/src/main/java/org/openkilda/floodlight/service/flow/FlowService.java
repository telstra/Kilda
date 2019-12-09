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

package org.openkilda.floodlight.service.flow;

import org.openkilda.floodlight.KafkaChannel;
import org.openkilda.floodlight.command.Command;
import org.openkilda.floodlight.command.CommandContext;
import org.openkilda.floodlight.converter.EthTypeMapper;
import org.openkilda.floodlight.converter.IpProtocolMapper;
import org.openkilda.floodlight.model.OfInput;
import org.openkilda.floodlight.service.IService;
import org.openkilda.floodlight.service.kafka.IKafkaProducerService;
import org.openkilda.floodlight.service.kafka.KafkaUtilityService;
import org.openkilda.floodlight.service.of.IInputTranslator;
import org.openkilda.floodlight.service.of.InputService;
import org.openkilda.floodlight.utils.CorrelationContext;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.switches.RemoveExclusionRequest;
import org.openkilda.model.Cookie;
import org.openkilda.model.Metadata;
import org.openkilda.model.SwitchId;

import net.floodlightcontroller.core.module.FloodlightModuleContext;
import org.projectfloodlight.openflow.protocol.OFFlowRemoved;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.OFMetadata;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;


public class FlowService implements IService, IInputTranslator {
    private static final Logger logger = LoggerFactory.getLogger(FlowService.class);

    private IKafkaProducerService producerService;
    private String topic;

    @Override
    public Command makeCommand(CommandContext context, OfInput input) {
        return new Command(context) {
            @Override
            public Command call() {
                handleFlowRemoved(input);
                return null;
            }
        };
    }

    private void handleFlowRemoved(OfInput input) {
        OFFlowRemoved message = (OFFlowRemoved) input.getMessage();
        SwitchId switchId = new SwitchId(input.getDpId().getLong());
        long cookie = message.getCookie().getValue();

        if (Cookie.isMaskedAsExclusion(cookie)) {
            CommandMessage removeExcludeMessage = createRemoveExcludeMessage(switchId, cookie, message.getMatch());
            producerService.sendMessageAndTrack(topic, switchId.toString(), removeExcludeMessage);
        }
    }

    private CommandMessage createRemoveExcludeMessage(SwitchId switchId, long cookie, Match match) {
        EthType ethType = match.get(MatchField.ETH_TYPE);
        IpProtocol ipProtocol = match.get(MatchField.IP_PROTO);
        long metadata = Optional.ofNullable(match.get(MatchField.METADATA))
                .map(OFMetadata::getValue).map(U64::getValue).orElse(0L);
        RemoveExclusionRequest.RemoveExclusionRequestBuilder request = RemoveExclusionRequest.builder()
                .switchId(switchId)
                .cookie(cookie)
                .srcIp(Optional.ofNullable(match.get(MatchField.IPV4_SRC)).map(Objects::toString).orElse(null))
                .dstIp(Optional.ofNullable(match.get(MatchField.IPV4_DST)).map(Objects::toString).orElse(null))
                .ethType(Optional.ofNullable(ethType).map(EthTypeMapper.INSTANCE::convert).orElse(null))
                .proto(Optional.ofNullable(ipProtocol).map(IpProtocolMapper.INSTANCE::convert).orElse(null))
                .metadata(new Metadata(metadata));
        setProtocolPorts(request, ipProtocol, match);

        return new CommandMessage(request.build(), System.currentTimeMillis(), CorrelationContext.getId());
    }

    private void setProtocolPorts(RemoveExclusionRequest.RemoveExclusionRequestBuilder request,
                                  IpProtocol proto, Match match) {
        if (proto == null) {
            return;
        }

        if (IpProtocol.TCP.equals(proto)) {
            request.srcPort(Optional.ofNullable(match.get(MatchField.TCP_SRC))
                            .map(TransportPort::getPort).orElse(null))
                    .dstPort(Optional.ofNullable(match.get(MatchField.TCP_DST))
                            .map(TransportPort::getPort).orElse(null));
        } else if (IpProtocol.UDP.equals(proto)) {
            request.srcPort(Optional.ofNullable(match.get(MatchField.UDP_SRC))
                    .map(TransportPort::getPort).orElse(null))
                    .dstPort(Optional.ofNullable(match.get(MatchField.UDP_DST))
                            .map(TransportPort::getPort).orElse(null));
        } else {
            logger.debug("Unexpected IP protocol {}", proto);
        }
    }

    @Override
    public void setup(FloodlightModuleContext context) {
        logger.info("Starting {}", FlowService.class.getCanonicalName());
        KafkaChannel kafkaChannel = context.getServiceImpl(KafkaUtilityService.class).getKafkaChannel();
        logger.info("region: {}", kafkaChannel.getRegion());

        producerService = context.getServiceImpl(IKafkaProducerService.class);
        topic = kafkaChannel.getAppsRequestTopic();

        InputService inputService = context.getServiceImpl(InputService.class);
        inputService.addTranslator(OFType.FLOW_REMOVED, this);
    }
}
