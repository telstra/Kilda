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

package org.openkilda.persistence.repositories.impl;

import org.openkilda.model.IslConfig;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.ApplicationRepository;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.repositories.ExclusionIdRepository;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowCookieRepository;
import org.openkilda.persistence.repositories.FlowGroupRepository;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.repositories.FlowPairRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.KildaConfigurationRepository;
import org.openkilda.persistence.repositories.LinkPropsRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.repositories.PortPropertiesRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchConnectedDeviceRepository;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.persistence.repositories.history.FlowHistoryRepository;
import org.openkilda.persistence.repositories.history.FlowStateRepository;
import org.openkilda.persistence.repositories.history.HistoryLogRepository;
import org.openkilda.persistence.repositories.history.PortHistoryRepository;
import org.openkilda.persistence.repositories.history.StateLogRepository;

import java.time.Duration;

/**
 * Neo4j OGM implementation of {@link RepositoryFactory}.
 */
public class Neo4jRepositoryFactory implements RepositoryFactory {

    private final Neo4jSessionFactory sessionFactory;
    private final TransactionManager transactionManager;
    private final IslConfig islConfig;

    public Neo4jRepositoryFactory(Neo4jSessionFactory sessionFactory, TransactionManager transactionManager,
                                  NetworkConfig networkConfig) {
        this.sessionFactory = sessionFactory;
        this.transactionManager = transactionManager;
        this.islConfig = IslConfig.builder()
                .unstableIslTimeout(Duration.ofSeconds(networkConfig.getIslUnstableTimeoutSec()))
                .build();
    }

    @Override
    public FlowCookieRepository createFlowCookieRepository() {
        return new Neo4jFlowCookieRepository(sessionFactory, transactionManager);
    }

    @Override
    public FlowMeterRepository createFlowMeterRepository() {
        return new Neo4jFlowMeterRepository(sessionFactory, transactionManager);
    }

    @Override
    public FlowPathRepository createFlowPathRepository() {
        return new Neo4jFlowPathRepository(sessionFactory, transactionManager);
    }

    @Override
    public FlowRepository createFlowRepository() {
        return new Neo4jFlowRepository(sessionFactory, transactionManager);
    }

    @Override
    public FlowPairRepository createFlowPairRepository() {
        return new Neo4jFlowPairRepository(sessionFactory, transactionManager);
    }

    @Override
    public IslRepository createIslRepository() {
        return new Neo4jIslRepository(sessionFactory, transactionManager, islConfig);
    }

    @Override
    public LinkPropsRepository createLinkPropsRepository() {
        return new Neo4jLinkPropsRepository(sessionFactory, transactionManager);
    }

    @Override
    public SwitchRepository createSwitchRepository() {
        return new Neo4jSwitchRepository(sessionFactory, transactionManager);
    }

    @Override
    public TransitVlanRepository createTransitVlanRepository() {
        return new Neo4jTransitVlanRepository(sessionFactory, transactionManager);
    }

    @Override
    public VxlanRepository createVxlanRepository() {
        return new Neo4jVxlanRepository(sessionFactory, transactionManager);
    }

    @Override
    public FeatureTogglesRepository createFeatureTogglesRepository() {
        return new Neo4jFeatureTogglesRepository(sessionFactory, transactionManager);
    }

    @Override
    public FlowEventRepository createFlowEventRepository() {
        return new Neo4jFlowEventRepository(sessionFactory, transactionManager);
    }

    @Override
    public FlowHistoryRepository createFlowHistoryRepository() {
        return new Neo4jFlowHistoryRepository(sessionFactory, transactionManager);
    }

    @Override
    public FlowStateRepository createFlowStateRepository() {
        return new Neo4jFlowStateRepository(sessionFactory, transactionManager);
    }

    @Override
    public HistoryLogRepository createHistoryLogRepository() {
        return new Neo4jHistoryLogRepository(sessionFactory, transactionManager);
    }

    @Override
    public StateLogRepository createStateLogRepository() {
        return new Neo4jStateLogRepository(sessionFactory, transactionManager);
    }

    @Override
    public BfdSessionRepository createBfdSessionRepository() {
        return new Neo4JBfdSessionRepository(sessionFactory, transactionManager);
    }

    @Override
    public KildaConfigurationRepository createKildaConfigurationRepository() {
        return new Neo4jKildaConfigurationRepository(sessionFactory, transactionManager);
    }

    @Override
    public SwitchPropertiesRepository createSwitchPropertiesRepository() {
        return new Neo4jSwitchPropertiesRepository(sessionFactory, transactionManager);
    }

    @Override
    public SwitchConnectedDeviceRepository createSwitchConnectedDeviceRepository() {
        return new Neo4jSwitchConnectedDevicesRepository(sessionFactory, transactionManager);
    }

    @Override
    public PortHistoryRepository createPortHistoryRepository() {
        return new Neo4jPortHistoryRepository(sessionFactory, transactionManager);
    }

    @Override
    public PortPropertiesRepository createPortPropertiesRepository() {
        return new Neo4jPortPropertiesRepository(sessionFactory, transactionManager);
    }

    @Override
    public PathSegmentRepository createPathSegmentRepository() {
        return new Neo4jPathSegmentRepository(sessionFactory, transactionManager);
    }

    @Override
    public ApplicationRepository createApplicationRepository() {
        return new Neo4jApplicationRepository(sessionFactory, transactionManager);
    }

    @Override
    public ExclusionIdRepository createExclusionIdRepository() {
        return new Neo4jExclusionIdRepository(sessionFactory, transactionManager);
    }

    @Override
    public FlowGroupRepository createFlowGroupRepository() {
        return new Neo4jFlowGroupRepository(sessionFactory, transactionManager);
    }
}
