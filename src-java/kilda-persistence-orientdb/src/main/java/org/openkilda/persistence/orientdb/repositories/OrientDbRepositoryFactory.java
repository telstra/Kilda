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

package org.openkilda.persistence.orientdb.repositories;

import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.ferma.repositories.FermaRepositoryFactory;
import org.openkilda.persistence.orientdb.OrientDbGraphFactory;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.repositories.FlowCookieRepository;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.PathSegmentRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.persistence.repositories.VxlanRepository;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.persistence.tx.TransactionManager;

import lombok.extern.slf4j.Slf4j;

/**
 * OrientDB implementation of {@link RepositoryFactory}.
 */
@Slf4j
public class OrientDbRepositoryFactory extends FermaRepositoryFactory {
    private final OrientDbGraphFactory orientDbGraphFactory;

    public OrientDbRepositoryFactory(OrientDbGraphFactory orientDbGraphFactory, TransactionManager transactionManager,
                                     NetworkConfig networkConfig) {
        super(orientDbGraphFactory, transactionManager, networkConfig);

        this.orientDbGraphFactory = orientDbGraphFactory;
    }

    @Override
    public FlowCookieRepository createFlowCookieRepository() {
        return new OrientDbFlowCookieRepository(orientDbGraphFactory, transactionManager);
    }

    @Override
    public FlowMeterRepository createFlowMeterRepository() {
        return new OrientDbFlowMeterRepository(orientDbGraphFactory, transactionManager);
    }

    @Override
    public FlowPathRepository createFlowPathRepository() {
        return new OrientDbFlowPathRepository(orientDbGraphFactory, transactionManager);
    }

    @Override
    public FlowRepository createFlowRepository() {
        return new OrientDbFlowRepository(orientDbGraphFactory, createFlowPathRepository(),
                transactionManager);
    }

    @Override
    public IslRepository createIslRepository() {
        return new OrientDbIslRepository(orientDbGraphFactory, transactionManager,
                (OrientDbFlowPathRepository) createFlowPathRepository(), islConfig);
    }

    @Override
    public SwitchRepository createSwitchRepository() {
        return new OrientDbSwitchRepository(orientDbGraphFactory, transactionManager);
    }

    @Override
    public TransitVlanRepository createTransitVlanRepository() {
        return new OrientDbTransitVlanRepository(orientDbGraphFactory, transactionManager);
    }

    @Override
    public VxlanRepository createVxlanRepository() {
        return new OrientDbVxlanRepository(orientDbGraphFactory, transactionManager);
    }

    @Override
    public FlowEventRepository createFlowEventRepository() {
        return new OrientDbFlowEventRepository(orientDbGraphFactory, transactionManager);
    }

    @Override
    public BfdSessionRepository createBfdSessionRepository() {
        return new OrientDbBfdSessionRepository(orientDbGraphFactory, transactionManager);
    }

    @Override
    public PathSegmentRepository createPathSegmentRepository() {
        return new OrientDbPathSegmentRepository(orientDbGraphFactory, transactionManager, createIslRepository());
    }
}
