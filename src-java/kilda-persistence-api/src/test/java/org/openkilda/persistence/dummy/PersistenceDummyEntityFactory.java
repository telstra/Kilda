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

package org.openkilda.persistence.dummy;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowCookie;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowMeter;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.Isl;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.TransitVlan;
import org.openkilda.model.Vxlan;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FlowCookieRepository;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.persistence.tx.TransactionManager;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PersistenceDummyEntityFactory {
    private TransactionManager txManager;
    private final SwitchRepository switchRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;
    private final IslRepository islRepository;
    private final FlowRepository flowRepository;
    private final FlowPathRepository flowPathRepository;
    private final FlowMeterRepository flowMeterRepository;
    private final FlowCookieRepository flowCookieRepository;
    private final TransitVlanRepository transitVlanRepository;
    private final VxlanRepository transitVxLanRepository;

    private final IdProvider idProvider = new IdProvider();

    @Getter
    private final SwitchDefaults switchDefaults = new SwitchDefaults();

    @Getter
    private final SwitchPropertiesDefaults switchPropertiesDefaults = new SwitchPropertiesDefaults();

    @Getter
    private final IslDefaults islDefaults = new IslDefaults();

    @Getter
    private final FlowDefaults flowDefaults = new FlowDefaults();

    @Getter
    private final FlowPathDefaults flowPathDefaults = new FlowPathDefaults();


    public PersistenceDummyEntityFactory(PersistenceManager persistenceManager) {
        txManager = persistenceManager.getTransactionManager();

        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        switchRepository = repositoryFactory.createSwitchRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        islRepository = repositoryFactory.createIslRepository();
        flowRepository = repositoryFactory.createFlowRepository();
        flowPathRepository = repositoryFactory.createFlowPathRepository();
        flowMeterRepository = repositoryFactory.createFlowMeterRepository();
        flowCookieRepository = repositoryFactory.createFlowCookieRepository();
        transitVlanRepository = repositoryFactory.createTransitVlanRepository();
        transitVxLanRepository = repositoryFactory.createVxlanRepository();
    }

    /**
     * Lookup {@link Switch} object, create new if missing.
     */
    public Switch fetchOrCreateSwitch(SwitchId switchId) {
        return switchRepository.findById(switchId)
                .orElseGet(() -> makeSwitch(switchId));
    }

    public Isl fetchOrCreateIsl(IslDirectionalReference reference) {
        return fetchOrCreateIsl(reference.getSourceEndpoint(), reference.getDestEndpoint());
    }

    /**
     * Lookup {@link Isl} object, create new if missing.
     */
    public Isl fetchOrCreateIsl(IslEndpoint source, IslEndpoint dest) {
        return islRepository.findByEndpoints(
                source.getSwitchId(), source.getPortNumber(),
                dest.getSwitchId(), dest.getPortNumber())
                .orElseGet(() -> makeIsl(source, dest));
    }

    /**
     * Create {@link Switch} object.
     */
    public Switch makeSwitch(SwitchId switchId) {
        Switch sw = switchDefaults.fill(Switch.builder())
                .switchId(switchId)
                .build();
        switchRepository.add(sw);

        switchPropertiesRepository.add(
                switchPropertiesDefaults.fill(SwitchProperties.builder())
                        .switchObj(sw)
                        .build());

        return sw;
    }

    /**
     * Create {@link Isl} object.
     */
    public Isl makeIsl(IslEndpoint source, IslEndpoint dest) {
        Switch destSwitch = fetchOrCreateSwitch(source.getSwitchId());
        Switch sourceSwitch = fetchOrCreateSwitch(dest.getSwitchId());

        Isl isl = islDefaults.fill(Isl.builder())
                .srcSwitch(destSwitch).srcPort(source.getPortNumber())
                .destSwitch(sourceSwitch).destPort(dest.getPortNumber())
                .build();
        islRepository.add(isl);

        return isl;
    }

    public Flow makeFlow(FlowEndpoint source, FlowEndpoint dest, IslDirectionalReference... trace) {
        return makeFlow(source, dest, Arrays.asList(trace));
    }

    /**
     * Create {@link Flow} object.
     */
    public Flow makeFlow(FlowEndpoint source, FlowEndpoint dest, List<IslDirectionalReference> pathHint) {
        Flow flow = flowDefaults.fill(Flow.builder())
                .flowId(idProvider.provideFlowId())
                .srcSwitch(fetchOrCreateSwitch(source.getSwitchId()))
                .srcPort(source.getPortNumber())
                .srcVlan(source.getOuterVlanId())
                .destSwitch(fetchOrCreateSwitch(dest.getSwitchId()))
                .destPort(dest.getPortNumber())
                .destVlan(dest.getOuterVlanId())
                .build();
        return txManager.doInTransaction(() -> {
            makeFlowPathPair(flow, source, dest, pathHint);
            if (flow.isAllocateProtectedPath()) {
                makeFlowPathPair(flow, source, dest, pathHint, Collections.singletonList("protected"));
            }
            flowRepository.add(flow);
            allocateFlowBandwidth(flow);
            flowRepository.detach(flow);
            return flow;
        });
    }

    private void makeFlowPathPair(
            Flow flow, FlowEndpoint source, FlowEndpoint dest, List<IslDirectionalReference> forwardTrace) {
        makeFlowPathPair(flow, source, dest, forwardTrace, Collections.emptyList());
    }

    private void makeFlowPathPair(
            Flow flow, FlowEndpoint source, FlowEndpoint dest, List<IslDirectionalReference> forwardPathHint,
            List<String> tags) {
        long flowEffectiveId = idProvider.provideFlowEffectiveId();
        makeFlowCookie(flow.getFlowId(), flowEffectiveId);

        List<IslDirectionalReference> reversePathHint = forwardPathHint.stream()
                .map(IslDirectionalReference::makeOpposite)
                .collect(Collectors.toList());
        Collections.reverse(reversePathHint);  // inline

        PathId forwardPathId = idProvider.providePathId(flow.getFlowId(),
                Stream.concat(tags.stream(), Stream.of("forward")));
        List<PathSegment> forwardSegments = makePathSegments(forwardPathId, source.getSwitchId(), dest.getSwitchId(),
                forwardPathHint);
        flow.setForwardPath(makePath(
                flow, source, dest, forwardPathId, forwardSegments,
                new FlowSegmentCookie(FlowPathDirection.FORWARD, flowEffectiveId)));

        PathId reversePathId = idProvider.providePathId(flow.getFlowId(),
                Stream.concat(tags.stream(), Stream.of("reverse")));
        List<PathSegment> reverseSegments = makePathSegments(reversePathId, dest.getSwitchId(), source.getSwitchId(),
                reversePathHint);
        flow.setReversePath(makePath(
                flow, dest, source, reversePathId, reverseSegments,
                new FlowSegmentCookie(FlowPathDirection.REVERSE, flowEffectiveId)));
    }

    private FlowPath makePath(
            Flow flow, FlowEndpoint ingress, FlowEndpoint egress, PathId pathId, List<PathSegment> segments,
            FlowSegmentCookie cookie) {

        if (FlowEncapsulationType.TRANSIT_VLAN == flow.getEncapsulationType()) {
            makeTransitVlan(flow.getFlowId(), pathId);
        } else if (FlowEncapsulationType.VXLAN == flow.getEncapsulationType()) {
            makeTransitVxLan(flow.getFlowId(), pathId);
        } else {
            throw new IllegalArgumentException(String.format(
                    "Unsupported flow transit encapsulation %s", flow.getEncapsulationType()));
        }

        // caller responsible for saving this entity into persistence storage
        return flowPathDefaults.fill(FlowPath.builder())
                .pathId(pathId)
                .srcSwitch(fetchOrCreateSwitch(ingress.getSwitchId()))
                .destSwitch(fetchOrCreateSwitch(egress.getSwitchId()))
                .cookie(cookie)
                .meterId(makeFlowMeter(ingress.getSwitchId(), flow.getFlowId(), pathId).getMeterId())
                .bandwidth(flow.getBandwidth())
                .segments(segments)
                .build();
    }

    private List<PathSegment> makePathSegments(PathId pathId, SwitchId sourceSwitchId, SwitchId destSwitchId,
                                               List<IslDirectionalReference> pathHint) {
        List<PathSegment> results = new ArrayList<>();

        IslDirectionalReference first = null;
        IslDirectionalReference last = null;
        for (IslDirectionalReference entry : pathHint) {
            last = entry;
            if (first == null) {
                first = entry;
            }

            IslEndpoint source = entry.getSourceEndpoint();
            Switch sourceSwitch = fetchOrCreateSwitch(source.getSwitchId());

            IslEndpoint dest = entry.getDestEndpoint();
            Switch destSwitch = fetchOrCreateSwitch(dest.getSwitchId());

            fetchOrCreateIsl(entry);

            results.add(PathSegment.builder()
                    .pathId(pathId)
                    .srcSwitch(sourceSwitch).srcPort(source.getPortNumber())
                    .destSwitch(destSwitch).destPort(dest.getPortNumber())
                    .build());
        }

        if (first != null && ! sourceSwitchId.equals(first.getSourceEndpoint().getSwitchId())) {
            throw new IllegalArgumentException(String.format(
                    "Flow's trace do not start on flow endpoint (a-end switch %s, first path's hint entry %s)",
                    sourceSwitchId, first));
        }
        if (last != null && ! destSwitchId.equals(last.getDestEndpoint().getSwitchId())) {
            throw new IllegalArgumentException(String.format(
                    "Flow's trace do not end on flow endpoint (z-end switch %s, last path's hint entry %s)",
                    destSwitchId, last));
        }

        return results;
    }

    private FlowCookie makeFlowCookie(String flowId, long effectiveFlowId) {
        FlowCookie flowCookie = FlowCookie.builder()
                .flowId(flowId)
                .unmaskedCookie(effectiveFlowId)
                .build();
        flowCookieRepository.add(flowCookie);
        return flowCookie;
    }

    private FlowMeter makeFlowMeter(SwitchId swId, String flowId, PathId pathId) {
        FlowMeter meter = FlowMeter.builder()
                .switchId(swId)
                .meterId(idProvider.provideMeterId(swId))
                .pathId(pathId)
                .flowId(flowId)
                .build();
        flowMeterRepository.add(meter);
        return meter;
    }

    private TransitVlan makeTransitVlan(String flowId, PathId pathId) {
        TransitVlan entity = TransitVlan.builder()
                .flowId(flowId)
                .pathId(pathId)
                .vlan(idProvider.provideTransitVlanId())
                .build();
        transitVlanRepository.add(entity);
        return entity;
    }

    private Vxlan makeTransitVxLan(String flowId, PathId pathId) {
        Vxlan entity = Vxlan.builder()
                .flowId(flowId)
                .pathId(pathId)
                .vni(idProvider.provideTransitVxLanId())
                .build();
        transitVxLanRepository.add(entity);
        return entity;
    }

    private void allocateFlowBandwidth(Flow flow) {
        for (PathId pathId : flow.getPathIds()) {
            islRepository.updateAvailableBandwidthOnIslsOccupiedByPath(pathId);
        }
    }
}
