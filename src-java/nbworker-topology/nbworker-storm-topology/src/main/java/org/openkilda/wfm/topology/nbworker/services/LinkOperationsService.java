/* Copyright 2018 Telstra Open Source
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

package org.openkilda.wfm.topology.nbworker.services;

import org.openkilda.messaging.nbtopology.response.BfdPropertiesResponse;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.BfdSessionStatus;
import org.openkilda.model.EffectiveBfdProperties;
import org.openkilda.model.FlowPath;
import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.error.IllegalIslStateException;
import org.openkilda.wfm.error.IslNotFoundException;
import org.openkilda.wfm.share.mappers.IslMapper;
import org.openkilda.wfm.share.mappers.LinkMapper;
import org.openkilda.wfm.share.model.Endpoint;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
public class LinkOperationsService {

    private final ILinkOperationsServiceCarrier carrier;
    private final IslRepository islRepository;
    private final FlowPathRepository flowPathRepository;
    private final BfdSessionRepository bfdSessionRepository;
    private final TransactionManager transactionManager;

    public LinkOperationsService(ILinkOperationsServiceCarrier carrier,
                                 RepositoryFactory repositoryFactory,
                                 TransactionManager transactionManager) {
        this.carrier = carrier;
        this.islRepository = repositoryFactory.createIslRepository();
        this.flowPathRepository = repositoryFactory.createFlowPathRepository();
        this.bfdSessionRepository = repositoryFactory.createBfdSessionRepository();
        this.transactionManager = transactionManager;
    }

    /**
     * Update the "Under maintenance" flag in ISL.
     *
     * @param srcSwitchId source switch id.
     * @param srcPort source port.
     * @param dstSwitchId destination switch id.
     * @param dstPort destination port.
     * @param underMaintenance "Under maintenance" flag.
     * @return updated isl.
     * @throws IslNotFoundException if there is no isl with these parameters.
     */
    public List<Isl> updateLinkUnderMaintenanceFlag(SwitchId srcSwitchId, Integer srcPort,
                                                    SwitchId dstSwitchId, Integer dstPort,
                                                    boolean underMaintenance) throws IslNotFoundException {
        return transactionManager.doInTransaction(() -> {
            Optional<Isl> foundIsl = islRepository.findByEndpoints(srcSwitchId, srcPort, dstSwitchId, dstPort);
            Optional<Isl> foundReverceIsl = islRepository.findByEndpoints(dstSwitchId, dstPort, srcSwitchId, srcPort);
            if (!(foundIsl.isPresent() && foundReverceIsl.isPresent())) {
                return Optional.<List<Isl>>empty();
            }

            Isl isl = foundIsl.get();
            Isl reverceIsl = foundReverceIsl.get();

            if (isl.isUnderMaintenance() == underMaintenance) {
                return Optional.of(Arrays.asList(isl, reverceIsl));
            }

            isl.setUnderMaintenance(underMaintenance);
            reverceIsl.setUnderMaintenance(underMaintenance);

            islRepository.createOrUpdate(isl);
            islRepository.createOrUpdate(reverceIsl);

            return Optional.of(Arrays.asList(isl, reverceIsl));
        }).orElseThrow(() -> new IslNotFoundException(srcSwitchId, srcPort, dstSwitchId, dstPort));
    }

    /**
     * Gets all ISLs.
     *
     * @return List of ISLs
     */
    public Collection<Isl> getAllIsls(SwitchId srcSwitch, Integer srcPort,
                                      SwitchId dstSwitch, Integer dstPort) {
        return islRepository.findByPartialEndpoints(srcSwitch, srcPort, dstSwitch, dstPort);
    }

    /**
     * Delete ISL.
     *
     * @param sourceSwitch ISL source switch
     * @param sourcePort ISL source port
     * @param destinationSwitch ISL destination switch
     * @param destinationPort ISL destination port
     *
     * @return True if ISL was deleted, False otherwise
     * @throws IslNotFoundException if ISL is not found
     * @throws IllegalIslStateException if ISL is in 'active' state
     */
    public Collection<Isl> deleteIsl(SwitchId sourceSwitch, int sourcePort, SwitchId destinationSwitch,
                                     int destinationPort, boolean force)
            throws IllegalIslStateException, IslNotFoundException {
        log.info("Delete ISL with following parameters: "
                        + "source switch '{}', source port '{}', destination switch '{}', destination port '{}', "
                        + "force '{}'",
                sourceSwitch, sourcePort, destinationSwitch, destinationPort, force);

        List<Isl> isls = new ArrayList<>();

        islRepository.findByEndpoints(sourceSwitch, sourcePort,
                destinationSwitch, destinationPort).ifPresent(isls::add);
        islRepository.findByEndpoints(destinationSwitch, destinationPort,
                sourceSwitch, sourcePort).ifPresent(isls::add);

        if (isls.isEmpty()) {
            throw new IslNotFoundException(sourceSwitch, sourcePort, destinationSwitch, destinationPort);
        }

        if (!force) {
            if (isls.stream().anyMatch(x -> x.getStatus() == IslStatus.ACTIVE)) {
                throw new IllegalIslStateException(sourceSwitch, sourcePort, destinationSwitch, destinationPort,
                        "ISL must NOT be in active state.");
            }

            Collection<FlowPath> flowPaths = flowPathRepository.findWithPathSegment(sourceSwitch, sourcePort,
                    destinationSwitch, destinationPort);
            if (!flowPaths.isEmpty()) {
                throw new IllegalIslStateException(sourceSwitch, sourcePort, destinationSwitch, destinationPort,
                        "This ISL is busy by flow paths.");
            }
        }

        isls.forEach(islRepository::delete);

        return isls;
    }

    /**
     * Read ISL bfd properties.
     */
    public BfdPropertiesResponse readBfdProperties(Endpoint source, Endpoint destination) throws IslNotFoundException {
        Isl leftToRight = findIsl(source, destination);
        Isl rightToLeft = findIsl(destination, source);
        return makeBfdPropertiesResponse(new IslPair(source, destination, leftToRight, rightToLeft));
    }

    /**
     * Update BFD properties for specified ISL (both directions).
     */
    public BfdPropertiesResponse writeBfdProperties(
            Endpoint source, Endpoint destination, BfdProperties properties) throws IslNotFoundException {
        IslPair link = transactionManager.doInTransaction(
                () -> writeBfdPropertiesGoalValue(source, destination, properties));
        return makeBfdPropertiesResponse(link);
    }

    private BfdPropertiesResponse makeBfdPropertiesResponse(IslPair link) {
        EffectiveBfdProperties effectiveLeftToRight = new EffectiveBfdProperties(
                readBfdPropertiesEffectiveValue(link.getLeft()), link.leftToRight.getBfdSessionStatus());
        EffectiveBfdProperties effectiveRightToLeft = new EffectiveBfdProperties(
                readBfdPropertiesEffectiveValue(link.getRight()), link.getRightToLeft().getBfdSessionStatus());
        return LinkMapper.INSTANCE.mapResponse(link.getLeftToRight(), effectiveLeftToRight, effectiveRightToLeft);
    }

    private BfdProperties readBfdPropertiesEffectiveValue(Endpoint endpoint) {
        return bfdSessionRepository.findBySwitchIdAndPhysicalPort(endpoint.getDatapath(), endpoint.getPortNumber())
                .map(IslMapper.INSTANCE::readBfdProperties)
                .orElse(new BfdProperties());
    }

    private IslPair writeBfdPropertiesGoalValue(
            Endpoint source, Endpoint destination, BfdProperties properties) throws IslNotFoundException {
        IslPair link = new IslPair(source, destination, findIsl(source, destination), findIsl(destination, source));

        boolean needPropagate = isBfdPropertiesUpdateNeedPropagation(link.getLeftToRight(), properties)
                || isBfdPropertiesUpdateNeedPropagation(link.getRightToLeft(), properties);

        writeBfdPropertiesGoalValue(link.getLeftToRight(), properties);
        writeBfdPropertiesGoalValue(link.getRightToLeft(), properties);

        if (needPropagate) {
            carrier.islBfdPropertiesChanged(source, destination);
        }

        return link;
    }

    private void writeBfdPropertiesGoalValue(Isl target, BfdProperties properties) {
        target.setBfdInterval(properties.getInterval());
        target.setBfdMultiplier(properties.getMultiplier());
        target.setEnableBfd(properties.isEnabled());
        islRepository.createOrUpdate(target);
    }

    private Isl findIsl(Endpoint leftEnd, Endpoint rightEnd) throws IslNotFoundException {
        return islRepository.findByEndpoints(
                leftEnd.getDatapath(), leftEnd.getPortNumber(), rightEnd.getDatapath(), rightEnd.getPortNumber())
                .orElseThrow(() -> new IslNotFoundException(leftEnd, rightEnd));
    }

    private static boolean isBfdPropertiesUpdateNeedPropagation(Isl target, BfdProperties properties) {
        if (target.getBfdSessionStatus() == BfdSessionStatus.FAIL) {
            return true;
        }
        BfdProperties current = IslMapper.INSTANCE.readBfdProperties(target);
        return ! properties.equals(current);
    }

    @Value
    private static class IslPair {
        Endpoint left;
        Endpoint right;
        Isl leftToRight;
        Isl rightToLeft;
    }
}
