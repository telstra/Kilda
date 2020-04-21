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

package org.openkilda.messaging.command.flow;

import static org.openkilda.messaging.Utils.FLOW_ID;
import static org.openkilda.messaging.Utils.TRANSACTION_ID;

import org.openkilda.messaging.Utils;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.OutputVlanType;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@JsonSerialize
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode(callSuper = true)
public class InstallServer42lIngressFlow extends InstallTransitFlow {
    private static final long serialVersionUID = 2944794560559058839L;

    @JsonProperty("input_vlan_id")
    protected Integer inputVlanId;

    @JsonProperty("output_vlan_type")
    protected OutputVlanType outputVlanType;

    @JsonProperty("meter_id")
    protected Long meterId;

    @JsonProperty("egress_switch_id")
    protected SwitchId egressSwitchId;

    /**
     * Instance constructor.
     *
     * @param transactionId  transaction id
     * @param id             id of the flow
     * @param cookie         flow cookie
     * @param switchId       switch ID for flow installation
     * @param inputPort      input port of the flow
     * @param outputPort     output port of the flow
     * @param inputVlanId    input vlan id value
     * @param transitEncapsulationId  transit encapsulation id value
     * @param transitEncapsulationType  transit encapsulation type value
     * @param outputVlanType output vlan type action
     * @param meterId        flow meter id
     * @param egressSwitchId id of the ingress switch
     * @throws IllegalArgumentException if any of mandatory parameters is null
     */
    @Builder
    @JsonCreator
    public InstallServer42lIngressFlow(@JsonProperty(TRANSACTION_ID) final UUID transactionId,
                                       @JsonProperty(FLOW_ID) final String id,
                                       @JsonProperty("cookie") final Long cookie,
                                       @JsonProperty("switch_id") final SwitchId switchId,
                                       @JsonProperty("input_port") final Integer inputPort,
                                       @JsonProperty("output_port") final Integer outputPort,
                                       @JsonProperty("input_vlan_id") final Integer inputVlanId,
                                       @JsonProperty("transit_encapsulation_id") final Integer transitEncapsulationId,
                                       @JsonProperty("transit_encapsulation_type") final FlowEncapsulationType
                                          transitEncapsulationType,
                                       @JsonProperty("output_vlan_type") final OutputVlanType outputVlanType,
                                       @JsonProperty("meter_id") final Long meterId,
                                       @JsonProperty("egress_switch_id") final SwitchId egressSwitchId) {
        super(transactionId, id, cookie, switchId, inputPort, outputPort, transitEncapsulationId,
                transitEncapsulationType, true);
        setInputVlanId(inputVlanId);
        setOutputVlanType(outputVlanType);
        setMeterId(meterId);
        setEgressSwitchId(egressSwitchId);
    }

    /**
     * Sets output action on the vlan tag.
     *
     * @param outputVlanType action on the vlan tag
     */
    public void setOutputVlanType(final OutputVlanType outputVlanType) {
        if (outputVlanType == null) {
            throw new IllegalArgumentException("need to set output_vlan_type");
        } else if (!Utils.validateInputVlanType(inputVlanId, outputVlanType)) {
            throw new IllegalArgumentException("need to set valid values for input_vlan_id and output_vlan_type");
        } else {
            this.outputVlanType = outputVlanType;
        }
    }

    /**
     * Sets input vlan id value.
     *
     * @param inputVlanId input vlan id value
     */
    public void setInputVlanId(final Integer inputVlanId) {
        if (inputVlanId == null) {
            this.inputVlanId = 0;
        } else if (Utils.validateVlanRange(inputVlanId)) {
            this.inputVlanId = inputVlanId;
        } else {
            throw new IllegalArgumentException("need to set valid value for input_vlan_id");
        }
    }

    /**
     * Sets meter id for the flow.
     *
     * @param meterId id for the flow
     */
    public void setMeterId(final Long meterId) {
        if (meterId != null && meterId <= 0L) {
            throw new IllegalArgumentException("Meter id value should be positive");
        }
        this.meterId = meterId;
    }
}
