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

package org.openkilda.persistence.ferma.repositories;

import org.openkilda.model.ApplicationRule;
import org.openkilda.model.ApplicationRule.ApplicationRuleData;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.ExclusionCookie;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.ferma.frames.ApplicationRuleFrame;
import org.openkilda.persistence.ferma.frames.KildaBaseVertexFrame;
import org.openkilda.persistence.ferma.frames.converters.ExclusionCookieConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.repositories.ApplicationRepository;
import org.openkilda.persistence.tx.TransactionManager;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ferma (Tinkerpop) implementation of {@link ApplicationRepository}.
 */
public class FermaApplicationRepository extends FermaGenericRepository<ApplicationRule, ApplicationRuleData,
        ApplicationRuleFrame> implements ApplicationRepository {
    public FermaApplicationRepository(FramedGraphFactory<?> graphFactory, TransactionManager transactionManager) {
        super(graphFactory, transactionManager);
    }

    @Override
    public Optional<ApplicationRule> lookupRuleByMatchAndFlow(SwitchId switchId, String flowId, String srcIp,
                                                              Integer srcPort, String dstIp, Integer dstPort,
                                                              String proto, String ethType, Long metadata) {
        List<? extends ApplicationRuleFrame> applicationRuleFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(ApplicationRuleFrame.FRAME_LABEL)
                .has(ApplicationRuleFrame.SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(ApplicationRuleFrame.FLOW_ID_PROPERTY, flowId)
                .has(ApplicationRuleFrame.SRC_IP_PROPERTY, srcIp)
                .has(ApplicationRuleFrame.SRC_PORT_PROPERTY, srcPort)
                .has(ApplicationRuleFrame.DST_IP_PROPERTY, dstIp)
                .has(ApplicationRuleFrame.DST_PORT_PROPERTY, dstPort)
                .has(ApplicationRuleFrame.PROTO_PROPERTY, proto)
                .has(ApplicationRuleFrame.ETH_TYPE_PROPERTY, ethType)
                .has(ApplicationRuleFrame.METADATA_PROPERTY, metadata))
                .toListExplicit(ApplicationRuleFrame.class);
        return applicationRuleFrames.isEmpty() ? Optional.empty() : Optional.of(applicationRuleFrames.get(0))
                .map(ApplicationRule::new);
    }

    @Override
    public Optional<ApplicationRule> lookupRuleByMatchAndCookie(SwitchId switchId, ExclusionCookie cookie, String srcIp,
                                                                Integer srcPort, String dstIp, Integer dstPort,
                                                                String proto, String ethType, Long metadata) {
        List<? extends ApplicationRuleFrame> applicationRuleFrames = framedGraph().traverse(g -> g.V()
                .hasLabel(ApplicationRuleFrame.FRAME_LABEL)
                .has(ApplicationRuleFrame.SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId))
                .has(ApplicationRuleFrame.COOKIE_PROPERTY, ExclusionCookieConverter.INSTANCE.toGraphProperty(cookie))
                .has(ApplicationRuleFrame.SRC_IP_PROPERTY, srcIp)
                .has(ApplicationRuleFrame.SRC_PORT_PROPERTY, srcPort)
                .has(ApplicationRuleFrame.DST_IP_PROPERTY, dstIp)
                .has(ApplicationRuleFrame.DST_PORT_PROPERTY, dstPort)
                .has(ApplicationRuleFrame.PROTO_PROPERTY, proto)
                .has(ApplicationRuleFrame.ETH_TYPE_PROPERTY, ethType)
                .has(ApplicationRuleFrame.METADATA_PROPERTY, metadata))
                .toListExplicit(ApplicationRuleFrame.class);
        return applicationRuleFrames.isEmpty() ? Optional.empty() : Optional.of(applicationRuleFrames.get(0))
                .map(ApplicationRule::new);
    }

    @Override
    public Collection<ApplicationRule> findBySwitchId(SwitchId switchId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(ApplicationRuleFrame.FRAME_LABEL)
                .has(ApplicationRuleFrame.SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .toListExplicit(ApplicationRuleFrame.class).stream()
                .map(ApplicationRule::new)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<ApplicationRule> findByFlowId(String flowId) {
        return framedGraph().traverse(g -> g.V()
                .hasLabel(ApplicationRuleFrame.FRAME_LABEL)
                .has(ApplicationRuleFrame.FLOW_ID_PROPERTY, flowId))
                .toListExplicit(ApplicationRuleFrame.class).stream()
                .map(ApplicationRule::new)
                .collect(Collectors.toList());
    }

    @Override
    protected ApplicationRuleFrame doAdd(ApplicationRuleData data) {
        ApplicationRuleFrame frame = KildaBaseVertexFrame.addNewFramedVertex(framedGraph(),
                ApplicationRuleFrame.FRAME_LABEL, ApplicationRuleFrame.class);
        ApplicationRule.ApplicationRuleCloner.INSTANCE.copy(data, frame);
        return frame;
    }

    @Override
    protected void doRemove(ApplicationRuleFrame frame) {
        frame.remove();
    }

    @Override
    protected ApplicationRuleData doDetach(ApplicationRule entity, ApplicationRuleFrame frame) {
        return ApplicationRule.ApplicationRuleCloner.INSTANCE.deepCopy(frame);
    }
}
