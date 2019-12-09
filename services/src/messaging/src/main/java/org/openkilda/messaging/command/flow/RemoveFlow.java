/* Copyright 2017 Telstra Open Source
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

import org.openkilda.messaging.command.switches.DeleteRulesCriteria;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;

import java.util.UUID;

/**
 * Class represents flow deletion info.
 */
@Value
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "command",
        TRANSACTION_ID,
        FLOW_ID,
        "cookie",
        "switch_id",
        "meter_id"})
public class RemoveFlow extends BaseFlow {

    @JsonProperty("meter_id")
    private Long meterId;

    @JsonProperty("criteria")
    private DeleteRulesCriteria criteria;

    @JsonProperty("multi_table")
    private boolean multiTable;

    @JsonProperty("rule_type")
    private RuleType ruleType;

    @JsonProperty("clean_up_ingress")
    private boolean cleanUpIngress;

    @JsonProperty("clean_up_ingress_lldp")
    private boolean cleanUpIngressLldp;


    /**
     * Instance constructor.
     *
     * @param transactionId transaction id
     * @param flowId flow id
     * @param cookie cookie of the flow
     * @param switchId switch ID for flow removing
     * @param meterId meter id
     * @param criteria criteria to strictly match a rule.
     * @param ruleType type of flow
     * @param cleanUpIngress remove customer port ingress rule
     * @param cleanUpIngressLldp remove customer port ingress LLDP rule
     * @throws IllegalArgumentException if any of parameters parameters is null
     */
    @Builder
    @JsonCreator
    public RemoveFlow(@JsonProperty(TRANSACTION_ID) UUID transactionId,
                      @JsonProperty(FLOW_ID) String flowId,
                      @JsonProperty("cookie") Long cookie,
                      @JsonProperty("switch_id") SwitchId switchId,
                      @JsonProperty("meter_id") Long meterId,
                      @JsonProperty("criteria") DeleteRulesCriteria criteria,
                      @JsonProperty("multi_table") boolean multiTable,
                      @JsonProperty("rule_type") RuleType ruleType,
                      @JsonProperty("clean_up_ingress") boolean cleanUpIngress,
                      @JsonProperty("clean_up_ingress_lldp") boolean cleanUpIngressLldp) {
        super(transactionId, flowId, cookie, switchId);

        if (meterId != null && meterId <= 0L) {
            throw new IllegalArgumentException("need to set non negative meter_id");
        }
        this.meterId = meterId;
        this.multiTable = multiTable;
        this.criteria = criteria;
        this.ruleType = ruleType;
        this.cleanUpIngress = cleanUpIngress;
        this.cleanUpIngressLldp = cleanUpIngressLldp;
    }
}
