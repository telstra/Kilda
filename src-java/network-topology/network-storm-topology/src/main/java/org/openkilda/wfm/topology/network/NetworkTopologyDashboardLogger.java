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

package org.openkilda.wfm.topology.network;

import org.openkilda.model.SwitchId;
import org.openkilda.reporting.AbstractDashboardLogger;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.controller.sw.AbstractPort;
import org.openkilda.wfm.topology.network.controller.sw.LogicalBfdPort;
import org.openkilda.wfm.topology.network.controller.sw.PhysicalPort;

import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.util.HashMap;
import java.util.Map;

public class NetworkTopologyDashboardLogger extends AbstractDashboardLogger {

    public NetworkTopologyDashboardLogger(Logger logger) {
        super(logger);
    }

    /**
     * Log a port add event.
     *
     * @param port a port number.
     */
    public void onPortAdd(AbstractPort port) {
        Map<String, String> context = makePortContext(port.getEndpoint(), port.makeDashboardPortLabel(this));
        populateEvent(context, "add");
        invokeLogger(Level.INFO,
                String.format("Add port %d on switch %s",
                              port.getPortNumber(), port.getEndpoint().getDatapath()), context);
    }

    /**
     * Log a port delete event.
     *
     * @param port a port number.
     */
    public void onPortDelete(AbstractPort port) {
        Map<String, String> context = makePortContext(port.getEndpoint(), port.makeDashboardPortLabel(this));
        populateEvent(context, "delete");
        invokeLogger(Level.INFO,
                String.format("Delete port %d on switch %s",
                              port.getPortNumber(), port.getEndpoint().getDatapath()), context);
    }

    public void onPortUp(Endpoint endpoint) {
        onPortUpDown(endpoint, "UP");
    }

    public void onPortDown(Endpoint endpoint) {
        onPortUpDown(endpoint, "DOWN");
    }

    public void onPortFlappingStart(Endpoint endpoint) {
        onPortFlappingStartStop(endpoint, "start");
    }

    public void onPortFlappingStop(Endpoint endpoint) {
        onPortFlappingStartStop(endpoint, "stop");
    }

    public void onIslUp(IslReference reference, String statusDetails) {
        onIslUpDownMoved(reference, "UP", statusDetails);
    }

    public void onIslDown(IslReference reference, String statusDetails) {
        onIslUpDownMoved(reference, "DOWN", statusDetails);
    }

    public void onIslMoved(IslReference reference, String statusDetails) {
        onIslUpDownMoved(reference, "MOVED", statusDetails);
    }

    public void onSwitchOnline(SwitchId switchId) {
        onSwitchOnlineOffline(switchId, "ONLINE");
    }

    public void onSwitchOffline(SwitchId switchId) {
        onSwitchOnlineOffline(switchId, "OFFLINE");
    }

    /**
     * Log on a switch add event.
     *
     * @param switchId a switch ID.
     */
    public void onSwitchAdd(SwitchId switchId) {
        Map<String, String> context = makeSwitchContext(switchId);
        populateEvent(context, "add");
        invokeLogger(Level.INFO, String.format("Switch '%s' connected", switchId), context);
    }

    /**
     * Log on a switch delete event.
     *
     * @param switchId a switch ID.
     */
    public void onSwitchDelete(SwitchId switchId) {
        Map<String, String> context = makeSwitchContext(switchId);
        populateEvent(context, "delete");
        invokeLogger(Level.INFO, String.format("Switch '%s' has been deleted", switchId), context);
    }

    public String makePortLabel(PhysicalPort port) {
        return "physical";
    }

    public String makePortLabel(LogicalBfdPort port) {
        return "logical-BFD";
    }

    private void onPortUpDown(Endpoint endpoint, String event) {
        Map<String, String> context = makePortContext(endpoint);
        populateEvent(context, event);
        String message = String.format("Port status event: switch_id=%s, port_id=%d, state=%s",
                                       endpoint.getDatapath().toString(), endpoint.getPortNumber(), event);
        invokeLogger(Level.INFO, message, context);
    }

    private void onPortFlappingStartStop(Endpoint endpoint, String event) {
        Map<String, String> context = makePortContext(endpoint);
        populateEvent(context, "flapping-" + event);
        invokeLogger(Level.INFO, String.format("Port %s %s flapping", endpoint, event), context);
    }

    private void onIslUpDownMoved(IslReference reference, String event, String statusDetails) {
        Map<String, String> context = makeContextTemplate("isl");
        populateEvent(context, event);

        Endpoint source = reference.getSource();
        context.put("src_switch", source.getDatapath().toString());
        context.put("src_port", String.valueOf(source.getPortNumber()));
        context.put("src_switch_port", source.toString());

        Endpoint dest = reference.getDest();
        context.put("dst_switch", dest.getDatapath().toString());
        context.put("dst_port", String.valueOf(dest.getPortNumber()));
        context.put("dst_switch_port", dest.toString());

        String message = String.format("ISL %s changed status to: %s [%s]", reference, event, statusDetails);
        invokeLogger(Level.INFO, message, context);
    }

    private void onSwitchOnlineOffline(SwitchId switchId, String event) {
        Map<String, String> context = makeSwitchContext(switchId);
        populateEvent(context, event);
        invokeLogger(Level.INFO, String.format("Switch '%s' change status to '%s'", switchId, event), context);
    }

    private Map<String, String> makeSwitchContext(SwitchId switchId) {
        Map<String, String> context = makeContextTemplate("switch");
        populateSwitch(context, switchId);
        return context;
    }

    private Map<String, String> makePortContext(Endpoint endpoint) {
        return makePortContext(endpoint, null);
    }

    private Map<String, String> makePortContext(Endpoint endpoint, String portType) {
        Map<String, String> context = makeContextTemplate("port");
        populateCommonPortContext(context, endpoint);
        if (portType != null) {
            context.put("port_type", portType);
        }
        return context;
    }

    private void populateEvent(Map<String, String> context, String event) {
        context.put("state", event);
    }

    private void populateSwitch(Map<String, String> context, SwitchId switchId) {
        context.put("switch_id", switchId.toString());
    }

    private void populateCommonPortContext(Map<String, String> context, Endpoint endpoint) {
        populateSwitch(context, endpoint.getDatapath());
        context.put("port", String.valueOf(endpoint.getPortNumber()));
        context.put("switch_port", endpoint.toString());
    }

    private Map<String, String> makeContextTemplate(String domain) {
        Map<String, String> context = new HashMap<>();
        // TODO(surabujin): drop `SWITCH_PORT_ISL_DASHBOARD` field after dashboard query switch to the `dashboard` field
        context.put("SWITCH_PORT_ISL_DASHBOARD", "switch-port-isl");

        context.put("dashboard", "switch-port-isl");
        context.put("event_type", domain);
        return context;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        public NetworkTopologyDashboardLogger build(Logger logger) {
            return new NetworkTopologyDashboardLogger(logger);
        }
    }
}
