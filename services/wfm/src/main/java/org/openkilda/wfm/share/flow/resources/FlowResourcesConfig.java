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

package org.openkilda.wfm.share.flow.resources;

import com.sabre.oss.conf4j.annotation.Configuration;
import com.sabre.oss.conf4j.annotation.Default;
import com.sabre.oss.conf4j.annotation.Key;

import java.io.Serializable;

@Configuration
public interface FlowResourcesConfig extends Serializable {
    /**
     * Minimum meter id value for flows.
     */
    @Key("flow.meter-id.min")
    @Default("32")
    int getMinFlowMeterId();

    /**
     * Maximum meter id value for flows.
     */
    @Key("flow.meter-id.max")
    @Default("2500")
    int getMaxFlowMeterId();

    /**
     * Minimum vlan value for flows.
     */
    @Key("flow.vlan.min")
    @Default("2")
    int getMinFlowTransitVlan();

    /**
     * Maximum vlan value for flows.
     */
    @Key("flow.vlan.max")
    @Default("4094")
    int getMaxFlowTransitVlan();

    /**
     * Minimum vxlan value for flows.
     */
    @Key("flow.vxlan.min")
    @Default("4096")
    int getMinFlowVxlan();

    /**
     * Maximum vxlan value for flows.
     */
    @Key("flow.vxlan.max")
    @Default("16777214")
    int getMaxFlowVxlan();

    /**
     * Minimum cookie value for flows.
     */
    @Key("flow.cookie.min")
    @Default("1")
    long getMinFlowCookie();

    /**
     * Maximum cookie value for flows.
     */
    @Key("flow.cookie.max")
    @Default("131072")
    long getMaxFlowCookie();
}
