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

package org.openkilda.persistence.repositories;

import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.Isl;
import org.openkilda.model.PathId;
import org.openkilda.model.SwitchId;

import lombok.Value;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IslRepository extends Repository<Isl> {
    Collection<Isl> findAll();

    boolean existsByEndpoint(SwitchId switchId, int port);

    Collection<Isl> findByEndpoint(SwitchId switchId, int port);

    Collection<Isl> findBySrcEndpoint(SwitchId srcSwitchId, int srcPort);

    Collection<Isl> findByDestEndpoint(SwitchId dstSwitchId, int dstPort);

    Collection<Isl> findBySrcSwitch(SwitchId switchId);

    Collection<Isl> findByDestSwitch(SwitchId switchId);

    Optional<Isl> findByEndpoints(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort);

    Collection<Isl> findByPathIds(List<PathId> pathIds);

    /**
     * Finds ISLs by incomplete ISL information. If all parameters are null, will be returned a list of all ISLs.
     *
     * @param srcSwitchId       source switch id.
     * @param srcPort           source port.
     * @param dstSwitchId       destination switch id.
     * @param dstPort           destination port.
     */
    Collection<Isl> findByPartialEndpoints(SwitchId srcSwitchId, Integer srcPort,
                                           SwitchId dstSwitchId, Integer dstPort);

    /**
     * Finds active ISLs for the path occupied by the flow paths, filtering out ISLs that don't have enough available
     * bandwidth.
     * <p/>
     * ISLs must have available bandwidth to satisfy the difference between newly requested and already taken by the
     * same flow and support requested transit encapsulation type.
     *
     * @param pathId           the pathId.
     * @param requiredBandwidth required bandwidth amount that should be available on ISLs.
     * @param flowEncapsulationType required encapsulation support
     */
    Collection<IslImmutableView> findActiveByPathAndBandwidthAndEncapsulationType(PathId pathId, long requiredBandwidth,
                                                                           FlowEncapsulationType flowEncapsulationType);

    /**
     * Finds all active ISLs.
     */
    Collection<IslImmutableView> findAllActive();

    /**
     * Finds all active ISLs with encapsulation type support.
     *
     * @param flowEncapsulationType required encapsulation support
     */
    Collection<IslImmutableView> findActiveByEncapsulationType(FlowEncapsulationType flowEncapsulationType);

    /**
     * Finds all active ISLs, filtering out ISLs that don't have enough available bandwidth.
     *
     * @param requiredBandwidth required bandwidth amount that should be available on ISLs.
     * @param flowEncapsulationType required encapsulation support
     */
    Collection<IslImmutableView> findActiveByBandwidthAndEncapsulationType(long requiredBandwidth,
                                                                           FlowEncapsulationType flowEncapsulationType);

    /**
     * Finds all active ISLs, ignores ISLs if they have not enough bandwidth in any direction.
     * @param requiredBandwidth required available bandwidth amount.
     * @param flowEncapsulationType required encapsulation support
     * @return list of ISLs.
     */
    Collection<IslImmutableView> findSymmetricActiveByBandwidthAndEncapsulationType(long requiredBandwidth,
                                                                           FlowEncapsulationType flowEncapsulationType);

    /**
     * Update ISL available bandwidth according to the actual used bandwidth.
     * @return the result available bandwidth of the updated ISL.
     */
    long updateAvailableBandwidth(SwitchId srcSwitchId, int srcPort, SwitchId dstSwitchId, int dstPort);

    /**
     * Update ISL available bandwidth according to the actual used bandwidth.
     *
     * @return the endpoints of updated ISLs with the result available bandwidth.
     */
    Map<IslEndpoints, Long> updateAvailableBandwidthOnIslsOccupiedByPath(PathId pathId);

    @Value
    class IslEndpoints {
        String srcSwitch;
        int srcPort;
        String destSwitch;
        int destPort;
    }

    /**
     * Represents ISL as immutable plain data.
     */
    interface IslImmutableView {
        SwitchId getSrcSwitchId();

        int getSrcPort();

        String getSrcPop();

        SwitchId getDestSwitchId();

        int getDestPort();

        String getDestPop();

        long getLatency();

        int getCost();

        long getAvailableBandwidth();

        boolean isUnderMaintenance();

        boolean isUnstable();
    }
}
