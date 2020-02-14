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

package org.openkilda.floodlight.prob.web;

import static org.openkilda.messaging.Utils.MAPPER;

import org.openkilda.floodlight.prob.IProbService;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArpPacket extends ServerResource {
    protected static Logger logger = LoggerFactory.getLogger(ArpPacket.class);

    /**
     * Sends ARP packet.
     */
    @Post("json")
    @Put("json")
    public String sendArpPacket(String json) throws JsonProcessingException {
        ProbResult result = new ProbResult(true, "ok");
        IProbService pvs =
                (IProbService) getContext().getAttributes()
                        .get(IProbService.class.getCanonicalName());
        try {
            ArpPacketData request = MAPPER.readValue(json, ArpPacketData.class);
            pvs.sendArpPacket(request);
        } catch (Exception e) {
            result.setDetails(e.getMessage());
            result.setSuccess(false);
        }
        return MAPPER.writeValueAsString(result);
    }
}
