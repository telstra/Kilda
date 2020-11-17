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
import org.openkilda.wfm.topology.network.model.BiIslDataHolder;

import java.util.Objects;
import java.util.Optional;

abstract class DiscoveryMonitor<T> {
    protected final IslReference reference;
    protected final BiIslDataHolder<T> discoveryData;
    protected final BiIslDataHolder<T> cache;

    protected DiscoveryMonitor(IslReference reference) {
        this.reference = reference;
        discoveryData = new BiIslDataHolder<>(reference);
        cache = new BiIslDataHolder<>(reference);
    }

    public void load(Endpoint endpoint, Isl persistentView) {
        // successors can sync their state to persistent state
    }

    public boolean update(IslFsmEvent event, IslFsmContext context) {
        actualUpdate(event, context);
        return isFlushRequired();
    }

    public void flush(Endpoint endpoint, Isl persistentView) {
        actualFlush(endpoint, persistentView);
        cache.put(endpoint, discoveryData.get(endpoint));
    }

    public abstract Optional<IslStatus> evaluateStatus();

    public abstract IslDownReason getDownReason();

    public abstract String getName();

    protected abstract void actualUpdate(IslFsmEvent event, IslFsmContext context);

    protected abstract void actualFlush(Endpoint endpoint, Isl persistentView);

    protected boolean isFlushRequired() {
        return !isDataMatchCache(reference.getSource()) || !isDataMatchCache(reference.getDest());
    }

    private boolean isDataMatchCache(Endpoint endpoint) {
        T effective = discoveryData.get(endpoint);
        T cached = cache.get(endpoint);
        return Objects.equals(effective, cached);
    }
}
