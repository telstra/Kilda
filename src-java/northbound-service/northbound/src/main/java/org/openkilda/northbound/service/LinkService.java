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

package org.openkilda.northbound.service;

import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.messaging.nbtopology.response.BfdPropertiesResponse;
import org.openkilda.messaging.payload.flow.FlowResponsePayload;
import org.openkilda.model.SwitchId;
import org.openkilda.northbound.dto.BatchResults;
import org.openkilda.northbound.dto.v1.links.LinkDto;
import org.openkilda.northbound.dto.v1.links.LinkMaxBandwidthDto;
import org.openkilda.northbound.dto.v1.links.LinkMaxBandwidthRequest;
import org.openkilda.northbound.dto.v1.links.LinkParametersDto;
import org.openkilda.northbound.dto.v1.links.LinkPropsDto;
import org.openkilda.northbound.dto.v1.links.LinkUnderMaintenanceDto;
import org.openkilda.northbound.dto.v2.links.BfdProperties;
import org.openkilda.northbound.dto.v2.links.BfdPropertiesPayload;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface LinkService {

    /**
     * Returns all links at the controller.
     */
    CompletableFuture<List<LinkDto>> getLinks(SwitchId srcSwitch, Integer srcPort,
                                              SwitchId dstSwitch, Integer dstPort);

    /**
     * These results are not related to the ISL links per se .. they are based on any link
     * properties that have been uploaded through setLinkProps.
     *
     * @param srcSwitch source switch dpid.
     * @param srcPort source port number.
     * @param dstSwitch destination switch dpid.
     * @param dstPort destination port number.
     * @return one or more link properties from the static link_props table.
     */
    CompletableFuture<List<LinkPropsDto>> getLinkProps(SwitchId srcSwitch, Integer srcPort,
                                                       SwitchId dstSwitch, Integer dstPort);

    /**
     * All linkPropsList link properties will be created/updated, and pushed to ISL links if they exit.
     *
     * @param linkPropsList the list of link properties to create / update
     * @return the number of successes, failures, and any failure messages
     */
    CompletableFuture<BatchResults> setLinkProps(List<LinkPropsDto> linkPropsList);

    /**
     * All linkPropsList link properties will be deleted, and deleted from ISL links if they exist.
     *
     * @param linkPropsList the list of link properties to delete
     * @return the number of successes (rows affected), failures, and any failure messages
     */
    CompletableFuture<BatchResults> delLinkProps(List<LinkPropsDto> linkPropsList);

    /**
     * Get all flows for a particular link.
     *
     * @param srcSwitch source switch dpid.
     * @param srcPort source port number.
     * @param dstSwitch destination switch dpid.
     * @param dstPort destination port number.
     * @return all flows for a particular link.
     */
    CompletableFuture<List<FlowResponsePayload>> getFlowsForLink(SwitchId srcSwitch, Integer srcPort,
                                                                 SwitchId dstSwitch, Integer dstPort);

    /**
     * Reroute all flows for a particular link.
     *
     * @param srcSwitch source switch dpid.
     * @param srcPort source port number.
     * @param dstSwitch destination switch dpid.
     * @param dstPort destination port number.
     * @return list of flow ids which was sent to reroute.
     */
    CompletableFuture<List<String>> rerouteFlowsForLink(SwitchId srcSwitch, Integer srcPort,
                                                        SwitchId dstSwitch, Integer dstPort);

    /**
     * Update "Under maintenance" flag.
     *
     * @param link link parameters.
     * @return updated link.
     */
    CompletableFuture<List<LinkDto>> updateLinkUnderMaintenance(LinkUnderMaintenanceDto link);

    /**
     * Link with corresponding parameters will be deleted.
     *
     * @param linkParameters properties to find a link for delete.
     * @return list of removed links.
     */
    CompletableFuture<List<LinkDto>> deleteLink(LinkParametersDto linkParameters, boolean force);

    CompletableFuture<List<LinkDto>> writeBfdProperties(
            NetworkEndpoint source, NetworkEndpoint dest, boolean isEnabled);

    CompletableFuture<BfdPropertiesPayload> writeBfdProperties(
            NetworkEndpoint source, NetworkEndpoint dest, BfdProperties properties);

    CompletableFuture<BfdPropertiesPayload> readBfdProperties(NetworkEndpoint source, NetworkEndpoint dest);

    CompletableFuture<BfdPropertiesResponse> readBfdProperties(
            NetworkEndpoint source, NetworkEndpoint dest, String correlationId);

    CompletableFuture<BfdPropertiesPayload> deleteBfdProperties(NetworkEndpoint source, NetworkEndpoint dest);

    /**
     * Update maximum bandwidth for link.
     *
     * @param srcSwitch a source switch id.
     * @param srcPort a source port id.
     * @param dstSwitch a destination switch id.
     * @param dstPort a destination port id.
     * @param linkMaxBandwidth a link parameters.
     * @return updated link parameters.
     */
    CompletableFuture<LinkMaxBandwidthDto> updateLinkBandwidth(
            SwitchId srcSwitch, Integer srcPort, SwitchId dstSwitch, Integer dstPort,
            LinkMaxBandwidthRequest linkMaxBandwidth);
}
