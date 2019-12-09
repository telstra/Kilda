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

import org.openkilda.model.ApplicationRule;
import org.openkilda.model.Cookie;
import org.openkilda.model.Metadata;
import org.openkilda.model.SwitchId;

import java.util.Collection;
import java.util.Optional;

public interface ApplicationRepository extends Repository<ApplicationRule> {

    Optional<ApplicationRule> lookupRuleByMatchAndFlow(SwitchId switchId, String flowId, String srcIp,
                                                       Integer srcPort, String dstIp, Integer dstPort,
                                                       String proto, String ethType, Metadata metadata);

    Optional<ApplicationRule> lookupRuleByMatchAndCookie(SwitchId switchId, Cookie cookie, String srcIp,
                                                         Integer srcPort, String dstIp, Integer dstPort,
                                                         String proto, String ethType, Metadata metadata);

    Collection<ApplicationRule> findBySwitchId(SwitchId switchId);

    Collection<ApplicationRule> findByFlowId(String flowId);
}
