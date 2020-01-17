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

/**
 * The Class FlowsCount.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"source", "target", "flow_count"})
public class FlowCount {

    @JsonProperty("source")
    private String srcSwitch;

    @JsonProperty("target")
    private String dstSwitch;

    @JsonProperty("source_switch_name")
    private String srcSwitchName;

    @JsonProperty("target_switch_name")
    private String dstSwitchName;

    @JsonProperty("flow_count")
    private Integer flowCount;

    /**
     * Gets the src switch.
     *
     * @return the src switch
     */
    public String getSrcSwitch() {
        return srcSwitch;
    }

    /**
     * Sets the src switch.
     *
     * @param srcSwitch the new src switch
     */
    public void setSrcSwitch(final String srcSwitch) {
        this.srcSwitch = srcSwitch;
    }

    /**
     * Gets the dst switch.
     *
     * @return the dst switch
     */
    public String getDstSwitch() {
        return dstSwitch;
    }

    /**
     * Sets the dst switch.
     *
     * @param dstSwitch the new dst switch
     */
    public void setDstSwitch(final String dstSwitch) {
        this.dstSwitch = dstSwitch;
    }

    public String getSrcSwitchName() {
        return srcSwitchName;
    }

    public void setSrcSwitchName(String srcSwitchName) {
        this.srcSwitchName = srcSwitchName;
    }

    public String getDstSwitchName() {
        return dstSwitchName;
    }

    public void setDstSwitchName(String dstSwitchName) {
        this.dstSwitchName = dstSwitchName;
    }

    /**
     * Gets the flow count.
     *
     * @return the flow count
     */
    public Integer getFlowCount() {
        return flowCount;
    }

    /**
     * Sets the flow count.
     *
     * @param flowCount the new flow count
     */
    public void setFlowCount(final Integer flowCount) {
        this.flowCount = flowCount;
    }

    public void incrementFlowCount() {
        flowCount++;
    }

    @Override
    public int hashCode() {
        return srcSwitch.hashCode() + dstSwitch.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj instanceof FlowCount) {
            FlowCount flowsCount = (FlowCount) obj;
            return (flowsCount.srcSwitch.equalsIgnoreCase(srcSwitch)
                    && flowsCount.dstSwitch.equalsIgnoreCase(dstSwitch))
                    || (flowsCount.srcSwitch.equalsIgnoreCase(dstSwitch)
                            && flowsCount.dstSwitch.equalsIgnoreCase(srcSwitch));
        }
        return false;
    }

}
