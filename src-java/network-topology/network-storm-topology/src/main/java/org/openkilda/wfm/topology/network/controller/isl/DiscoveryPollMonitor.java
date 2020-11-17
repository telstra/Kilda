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

package org.openkilda.wfm.topology.network.controller.isl;

import org.openkilda.model.Isl;
import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmContext;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmEvent;
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.IslEndpointPollStatus;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class DiscoveryPollMonitor extends DiscoveryMonitor<IslEndpointPollStatus> {
    public DiscoveryPollMonitor(IslReference reference) {
        super(reference);
        discoveryData.putBoth(new IslEndpointPollStatus(IslStatus.INACTIVE));
    }

    @Override
    public void load(Endpoint endpoint, Isl persistentView) {
        super.load(endpoint, persistentView);

        IslDataHolder islData = new IslDataHolder(persistentView);
        IslEndpointPollStatus status = new IslEndpointPollStatus(islData, persistentView.getStatus());

        discoveryData.put(endpoint, status);
        cache.put(endpoint, status);
    }

    @Override
    public Optional<IslStatus> evaluateStatus() {
        IslStatus forward = discoveryData.getForward().getStatus();
        IslStatus reverse = discoveryData.getReverse().getStatus();

        if (forward == reverse) {
            return Optional.of(forward);
        }
        if (forward == IslStatus.INACTIVE || reverse == IslStatus.INACTIVE) {
            return Optional.of(IslStatus.INACTIVE);
        }

        return Optional.empty();
    }

    @Override
    public IslDownReason getDownReason() {
        return IslDownReason.POLL_TIMEOUT;
    }

    @Override
    public String getName() {
        return "poll";
    }

    @Override
    public void actualUpdate(IslFsmEvent event, IslFsmContext context) {
        Endpoint endpoint = context.getEndpoint();
        IslEndpointPollStatus update = discoveryData.get(endpoint);
        switch (event) {
            case ISL_UP:
                update = new IslEndpointPollStatus(context.getIslData(), IslStatus.ACTIVE);
                log.info("ISL {} data update - bind:{} - {}", reference, endpoint, update.getIslData());
                break;

            case ISL_DOWN:
                update = new IslEndpointPollStatus(update.getIslData(), IslStatus.INACTIVE);
                break;

            case ISL_MOVE:
                // We must track MOVED state, because poll and moved monitor save it's status
                // inside same field {@link Isl.actualStatus}. In other case we will rewrite MOVED state
                // saved by {@link DiscoveryMovedMonitor} with our ovn "vision".
                update = new IslEndpointPollStatus(update.getIslData(), IslStatus.MOVED);
                break;

            default:
                // nothing to do here
        }
        discoveryData.put(endpoint, update);
    }

    @Override
    protected void actualFlush(Endpoint endpoint, Isl persistentView) {
        IslEndpointPollStatus pollStatus = discoveryData.get(endpoint);
        IslDataHolder islData = makeAggregatedIslData(
                pollStatus.getIslData(), discoveryData.get(reference.getOpposite(endpoint)).getIslData());
        if (islData == null) {
            log.error("There is no ISL data available for {}, unable to calculate available_bandwidth", reference);
        } else {
            persistentView.setSpeed(islData.getSpeed());
            persistentView.setMaxBandwidth(islData.getMaximumBandwidth());
            persistentView.setDefaultMaxBandwidth(islData.getEffectiveMaximumBandwidth());
        }
        persistentView.setActualStatus(pollStatus.getStatus());
    }

    private IslDataHolder makeAggregatedIslData(IslDataHolder local, IslDataHolder remote) {
        IslDataHolder aggregated;
        if (local != null) {
            if (remote != null) {
                aggregated = local.merge(remote);
            } else {
                aggregated = local;
            }
        } else {
            aggregated = remote;
        }

        return aggregated;
    }
}
