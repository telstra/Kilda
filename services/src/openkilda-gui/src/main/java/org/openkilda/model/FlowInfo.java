/* Copyright 2018 Telstra Open Source
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * The Class FlowResponse.
 *
 * @author Gaurav Chugh
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"flowid", "source_switch", "src_port", "src_vlan", "target_switch", "dst_port",
        "dst_vlan", "maximum_bandwidth", "status", "description", "diverse-flowid", "last-updated", "discrepancy"})
@Data
public class FlowInfo implements Serializable {

    @JsonProperty("flowid")
    private String flowid;

    @JsonProperty("source_switch")
    private String sourceSwitch;

    @JsonProperty("src_port")
    private int srcPort;

    @JsonProperty("src_vlan")
    private int srcVlan;

    @JsonProperty("target_switch_name")
    private String targetSwitchName;

    @JsonProperty("source_switch_name")
    private String sourceSwitchName;

    @JsonProperty("target_switch")
    private String targetSwitch;


    @JsonProperty("dst_port")
    private int dstPort;


    @JsonProperty("dst_vlan")
    private int dstVlan;
    
    @JsonProperty("diverse-flowid")
    private String diverseFlowid;


    @JsonProperty("maximum_bandwidth")
    private int maximumBandwidth;
    
    @JsonProperty("allocate_protected_path")
    private boolean allocateProtectedPath;

    @JsonProperty("status")
    private String status;


    @JsonProperty("description")
    private String description;


    @JsonProperty("last-updated")
    private String lastUpdated;
    
    @JsonProperty("discrepancy")
    private FlowDiscrepancy discrepancy;

    @JsonProperty("ignore-bandwidth")
    private Boolean ignoreBandwidth;

    @JsonProperty("state")
    private String state;
    
    @JsonProperty("controller-flow")
    private boolean controllerFlow;
    
    @JsonProperty("inventory-flow")
    private boolean inventoryFlow;
    
    @JsonProperty("diverse_with")
    private List<String> diverseWith;

    private static final long serialVersionUID = -7015976328478701934L;


}
