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

import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.ferma.FermaGraphFactory;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowCookieRepository;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.repositories.FlowPairRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
import org.openkilda.persistence.repositories.LinkPropsRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.persistence.repositories.history.FlowHistoryRepository;
import org.openkilda.persistence.repositories.history.FlowStateRepository;
import org.openkilda.persistence.repositories.history.HistoryLogRepository;
import org.openkilda.persistence.repositories.history.StateLogRepository;

/**
 * Ferma implementation of {@link RepositoryFactory}.
 */
public class FermaRepositoryFactory {

    private final FermaGraphFactory txFactory;
    private final TransactionManager transactionManager;

    public FermaRepositoryFactory(FermaGraphFactory txFactory, TransactionManager transactionManager) {
        this.txFactory = txFactory;
        this.transactionManager = transactionManager;
    }

    public FlowCookieRepository createFlowCookieRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public FlowMeterRepository createFlowMeterRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public FlowPathRepository createFlowPathRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public FlowRepository createFlowRepository() {
        return new FermaFlowRepository(txFactory, transactionManager);
    }

    public FlowPairRepository createFlowPairRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public IslRepository createIslRepository() {
        return new FermaIslRepository(txFactory, transactionManager);
    }

    public LinkPropsRepository createLinkPropsRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public SwitchRepository createSwitchRepository() {
        return new FermaSwitchRepository(txFactory, transactionManager);
    }

    public TransitVlanRepository createTransitVlanRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public VxlanRepository createVxlanRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public FeatureTogglesRepository createFeatureTogglesRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public FlowEventRepository createFlowEventRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public FlowHistoryRepository createFlowHistoryRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public FlowStateRepository createFlowStateRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public HistoryLogRepository createHistoryLogRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public StateLogRepository createStateLogRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public BfdSessionRepository createBfdSessionRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public KildaConfigurationRepository createKildaConfigurationRepository() {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
