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

package org.openkilda.persistence.repositories;

import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.persistence.repositories.history.FlowHistoryRepository;
import org.openkilda.persistence.repositories.history.FlowStateRepository;
import org.openkilda.persistence.repositories.history.HistoryLogRepository;
import org.openkilda.persistence.repositories.history.PortHistoryRepository;
import org.openkilda.persistence.repositories.history.StateLogRepository;

/**
 * Factory to create {@link Repository} instances.
 */
public interface RepositoryFactory {
    FlowCookieRepository createFlowCookieRepository();

    FlowMeterRepository createFlowMeterRepository();

    FlowPathRepository createFlowPathRepository();

    FlowRepository createFlowRepository();

    FlowPairRepository createFlowPairRepository();

    IslRepository createIslRepository();

    LinkPropsRepository createLinkPropsRepository();

    SwitchRepository createSwitchRepository();

    TransitVlanRepository createTransitVlanRepository();

    VxlanRepository createVxlanRepository();

    FeatureTogglesRepository createFeatureTogglesRepository();

    FlowEventRepository createFlowEventRepository();

    FlowHistoryRepository createFlowHistoryRepository();

    FlowStateRepository createFlowStateRepository();

    HistoryLogRepository createHistoryLogRepository();

    StateLogRepository createStateLogRepository();

    BfdSessionRepository createBfdSessionRepository();

    KildaConfigurationRepository createKildaConfigurationRepository();

    SwitchPropertiesRepository createSwitchPropertiesRepository();


    SwitchConnectedDeviceRepository createSwitchConnectedDeviceRepository();

    PortHistoryRepository createPortHistoryRepository();

    PortPropertiesRepository createPortPropertiesRepository();

    PathSegmentRepository createPathSegmentRepository();

    ApplicationRepository createApplicationRepository();

    ExclusionIdRepository createExclusionIdRepository();

}
