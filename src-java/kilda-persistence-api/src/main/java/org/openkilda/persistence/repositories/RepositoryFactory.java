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

import org.openkilda.persistence.repositories.history.FlowDumpRepository;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.persistence.repositories.history.FlowHistoryRepository;
import org.openkilda.persistence.repositories.history.PortHistoryRepository;

/**
 * Factory to obtain {@link Repository} instances.
 */
public interface RepositoryFactory {
    FlowCookieRepository createFlowCookieRepository();

    FlowMeterRepository createFlowMeterRepository();

    FlowPathRepository createFlowPathRepository();

    FlowRepository createFlowRepository();

    IslRepository createIslRepository();

    LinkPropsRepository createLinkPropsRepository();

    SwitchRepository createSwitchRepository();

    TransitVlanRepository createTransitVlanRepository();

    VxlanRepository createVxlanRepository();

    FeatureTogglesRepository createFeatureTogglesRepository();

    FlowEventRepository createFlowEventRepository();

    FlowHistoryRepository createFlowHistoryRepository();

    FlowDumpRepository createFlowDumpRepository();

    BfdSessionRepository createBfdSessionRepository();

    KildaConfigurationRepository createKildaConfigurationRepository();

    SwitchPropertiesRepository createSwitchPropertiesRepository();

    SwitchConnectedDeviceRepository createSwitchConnectedDeviceRepository();

    PortHistoryRepository createPortHistoryRepository();

    PortPropertiesRepository createPortPropertiesRepository();

    PathSegmentRepository createPathSegmentRepository();
}
