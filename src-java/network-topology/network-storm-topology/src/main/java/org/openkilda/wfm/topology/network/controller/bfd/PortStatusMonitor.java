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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmContext;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmFactory;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.Event;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.service.IBfdSessionCarrier;

class PortStatusMonitor {
    private final BfdSessionFsm consumer;

    private LinkStatus status = LinkStatus.DOWN;

    private boolean haveTransitions = false;

    PortStatusMonitor(BfdSessionFsm consumer) {
        this.consumer = consumer;
    }

    void update(IBfdSessionCarrier carrier, LinkStatus update) {
        haveTransitions |= status != update;
        status = update;

        propagate(carrier);
    }

    void cleanTransitions() {
        haveTransitions = false;
    }

    void pull(IBfdSessionCarrier carrier) {
        if (haveTransitions) {
            propagate(carrier);
        }
    }

    private void propagate(IBfdSessionCarrier carrier) {
        BfdSessionFsmContext context = BfdSessionFsmContext.builder(carrier).build();
        Event event;
        switch (status) {
            case UP:
                event = Event.PORT_UP;
                break;
            case DOWN:
                event = Event.PORT_DOWN;
                break;
            default:
                throw new IllegalArgumentException(String.format(
                        "Unsupported %s.%s link state. Can't handle event for %s",
                        LinkStatus.class.getName(), status, consumer.getLogicalEndpoint()));
        }
        BfdSessionFsmFactory.EXECUTOR.fire(consumer, event, context);
    }
}
