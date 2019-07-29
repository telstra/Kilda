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

import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.error.IllegalIslStateException;
import org.openkilda.wfm.error.IslNotFoundException;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
public class LinkOperationsService {

    private final ILinkOperationsServiceCarrier carrier;
    private IslRepository islRepository;
    private TransactionManager transactionManager;


    public LinkOperationsService(ILinkOperationsServiceCarrier carrier,
                                 RepositoryFactory repositoryFactory,
                                 TransactionManager transactionManager) {
        this.carrier = carrier;
        this.islRepository = repositoryFactory.createIslRepository();
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
                                     int destinationPort)
            throws IllegalIslStateException, IslNotFoundException {
        log.info("Delete ISL with following parameters: "
                        + "source switch '{}', source port '{}', destination switch '{}', destination port '{}'",
                sourceSwitch, sourcePort, destinationSwitch, destinationPort);

        List<Isl> isls = new ArrayList<>();

        islRepository.findByEndpoints(sourceSwitch, sourcePort,
                destinationSwitch, destinationPort).ifPresent(isls::add);
        islRepository.findByEndpoints(destinationSwitch, destinationPort,
                sourceSwitch, sourcePort).ifPresent(isls::add);

        if (isls.isEmpty()) {
            throw new IslNotFoundException(sourceSwitch, sourcePort, destinationSwitch, destinationPort);
        }

        if (isls.stream().anyMatch(x -> x.getStatus() == IslStatus.ACTIVE)) {
            throw new IllegalIslStateException(sourceSwitch, sourcePort, destinationSwitch, destinationPort,
                    "ISL must NOT be in active state.");
        }

        isls.forEach(islRepository::remove);

        return isls;
    }

    /**
     * Update the "Under maintenance" flag in ISL.
     *
     * @param srcSwitchId source switch id.
     * @param srcPort source port.
     * @param dstSwitchId destination switch id.
     * @param dstPort destination port.
     * @param enableBfd "Enable bfd" flag.
     * @return updated isl.
     * @throws IslNotFoundException if there is no isl with these parameters.
     */
    public Collection<Isl> updateLinkEnableBfdFlag(SwitchId srcSwitchId, Integer srcPort,
                                                    SwitchId dstSwitchId, Integer dstPort,
                                                    boolean enableBfd) throws IslNotFoundException {
        return transactionManager.doInTransaction(() -> {
            Optional<Isl> foundIsl = islRepository.findByEndpoints(srcSwitchId, srcPort, dstSwitchId, dstPort);
            if (!foundIsl.isPresent()) {
                return Optional.<List<Isl>>empty();
            }

            Isl isl = foundIsl.get();

            if (isl.isEnableBfd() == enableBfd) {
                return Optional.of(Arrays.asList(isl));
            }

            isl.setEnableBfd(enableBfd);

            carrier.islBfdFlagChanged(isl);

            return Optional.of(Arrays.asList(isl));
        }).orElseThrow(() -> new IslNotFoundException(srcSwitchId, srcPort, dstSwitchId, dstPort));
    }
}
