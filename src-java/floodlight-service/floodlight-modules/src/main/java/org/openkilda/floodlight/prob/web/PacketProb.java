/* Copyright 2010 Telstra Open Source
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

import org.openkilda.floodlight.prob.IProbService;

import org.projectfloodlight.openflow.types.DatapathId;
import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketProb extends ServerResource {
    protected static Logger logger = LoggerFactory.getLogger(PacketProb.class);

    /**
     * Sends a discover packet.
     */
    @Get()
    public String sendDiscoverPacket() {
        IProbService pvs =
                (IProbService) getContext().getAttributes()
                        .get(IProbService.class.getCanonicalName());

        String srcSwitch = (String) getRequestAttributes().get("src_switch");
        String port = (String) getRequestAttributes().get("src_port");
        String vlan = (String) getRequestAttributes().get("src_vlan");
        String udpPort = (String) getRequestAttributes().get("udp_port");

        logger.debug("asking {} to send a discovery packet out port {}.", srcSwitch, port);

        DatapathId dpSrc = DatapathId.of(srcSwitch);
        int p = Integer.parseInt(port);
        int v = Integer.parseInt(vlan);
        int u = Integer.parseInt(udpPort);
        pvs.sendPacketProb(dpSrc, p, (short) v, u);
        return null;
    }


}

