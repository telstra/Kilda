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

package org.openkilda.model;

import static org.neo4j.ogm.annotation.Relationship.INCOMING;

import com.google.common.annotations.VisibleForTesting;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Property;
import org.neo4j.ogm.annotation.Relationship;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;


@Data
@NoArgsConstructor
@EqualsAndHashCode(exclude = {"entityId", "switchObj"})
@NodeEntity(label = "switch_properties")
@ToString(exclude = {"switchObj"})
public class SwitchProperties implements Serializable {
    private static final long serialVersionUID = 1L;

    public static Set<FlowEncapsulationType> DEFAULT_FLOW_ENCAPSULATION_TYPES = Collections.singleton(
            FlowEncapsulationType.TRANSIT_VLAN);
    @Id
    @GeneratedValue
    @Setter(AccessLevel.NONE)
    private Long entityId;

    @NonNull
    @Relationship(type = "has", direction = INCOMING)
    private Switch switchObj;

    @Property(name = "supported_transit_encapsulation")
    private Set<FlowEncapsulationType> supportedTransitEncapsulation;

    @Property(name = "multi_table")
    private boolean multiTable;

    @Property(name = "switch_lldp")
    private boolean switchLldp;

    @Property(name = "inbound_telescope_port")
    private Integer inboundTelescopePort;

    @Property(name = "outbound_telescope_port")
    private Integer outboundTelescopePort;

    @Property(name = "telescope_ingress_vlan")
    private Integer telescopeIngressVlan;

    @Property(name = "telescope_egress_vlan")
    private Integer telescopeEgressVlan;

    @Builder(toBuilder = true)
    public SwitchProperties(Switch switchObj, Set<FlowEncapsulationType> supportedTransitEncapsulation,
                            boolean multiTable, boolean switchLldp, Integer inboundTelescopePort,
                            Integer outboundTelescopePort, Integer telescopeIngressVlan,
                            Integer telescopeEgressVlan) {
        this.switchObj = switchObj;
        this.supportedTransitEncapsulation = supportedTransitEncapsulation;
        this.multiTable = multiTable;
        this.switchLldp = switchLldp;
        this.inboundTelescopePort = inboundTelescopePort;
        this.outboundTelescopePort = outboundTelescopePort;
        this.telescopeIngressVlan = telescopeIngressVlan;
        this.telescopeEgressVlan = telescopeEgressVlan;
    }

    @VisibleForTesting
    boolean validateProp(SwitchFeature feature) {
        if (!switchObj.getFeatures().contains(feature)) {
            String message = String.format("Switch %s doesn't support requested feature %s", switchObj.getSwitchId(),
                    feature);
            throw new IllegalArgumentException(message);
        }
        return true;
    }

    /**
     * Sets multi-table flag. Validates it against supported features under the hood.
     * @param multiTable target flag
     */
    public void setMultiTable(boolean multiTable) {
        if (multiTable) {
            validateProp(SwitchFeature.MULTI_TABLE);
        }
        this.multiTable = multiTable;
    }

    /**
     * Sets allowed transit encapsulations. Validates it against supported features under the hood.
     * @param supportedTransitEncapsulation target supported transit encapsulations.
     */
    public void setSupportedTransitEncapsulation(Set<FlowEncapsulationType> supportedTransitEncapsulation) {
        if (supportedTransitEncapsulation != null
                && supportedTransitEncapsulation.contains(FlowEncapsulationType.VXLAN)) {
            validateProp(SwitchFeature.NOVIFLOW_COPY_FIELD);
        }
        this.supportedTransitEncapsulation = supportedTransitEncapsulation;
    }
}
