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

import org.openkilda.floodlight.utils.RequestCorrelationFilter;

import net.floodlightcontroller.restserver.RestletRoutable;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Filter;
import org.restlet.routing.Router;

public class ProbServiceWebRoutable implements RestletRoutable {

    public String basePath() {
        return "/wm/prob";
    }

    /**
     * Gets Restlet from the context.
     *
     * @param context the context.
     * @return the Restlet object.
     */
    public Restlet getRestlet(Context context) {
        Router router = new Router(context);
        router.attach("/send/{src_switch}/{src_port}/{src_vlan}/{dst_switch}/{udp_port}", PacketProb.class);

        Filter filter = new RequestCorrelationFilter();
        filter.setNext(router);

        return router;
    }
}
