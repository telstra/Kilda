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

package org.openkilda.messaging.command.switches;

/**
 * Describes what to do about the switch default rules.
 */
public enum InstallRulesAction {
    // Install just the default / base drop rule
    INSTALL_DROP,

    // Install just the verification (broadcast) rule only
    INSTALL_BROADCAST,

    // Install just the verification (unicast) rule only
    INSTALL_UNICAST,

    // Install just the drop verification loop rule only
    INSTALL_DROP_VERIFICATION_LOOP,

    // Install BFD catch rule
    INSTALL_BFD_CATCH,

    // Install Round Trip Latency rule
    INSTALL_ROUND_TRIP_LATENCY,

    // Install Unicast for VXLAN
    INSTALL_UNICAST_VXLAN,

    // Install  Pre Ingress Table pass through default
    INSTALL_MULTITABLE_PRE_INGRESS_PASS_THROUGH,

    // Install  Ingress Drop rule
    INSTALL_MULTITABLE_INGRESS_DROP,

    // Install  Post Ingress Drop rule
    INSTALL_MULTITABLE_POST_INGRESS_DROP,

    // Install  Egress Table pass through default
    INSTALL_MULTITABLE_EGRESS_PASS_THROUGH,

    // Install  Transit Table Drop rule
    INSTALL_MULTITABLE_TRANSIT_DROP,

    // Install Input table LLDP pre drop rule
    INSTALL_LLDP_INPUT_PRE_DROP,

    // Install Ingress Table LLDP rule
    INSTALL_LLDP_INGRESS,

    // Install Post Ingress Table LLDP rule
    INSTALL_LLDP_POST_INGRESS,

    // Install Post Ingress Table LLDP vxlan rule
    INSTALL_LLDP_POST_INGRESS_VXLAN,

    // Install Post Ingress Table LLDP one switch rule
    INSTALL_LLDP_POST_INGRESS_ONE_SWITCH,

    // Install Transit table LLDP rule
    INSTALL_LLDP_TRANSIT,

    // Install Input table ARP pre drop rule
    INSTALL_ARP_INPUT_PRE_DROP,

    // Install Ingress Table ARP rule
    INSTALL_ARP_INGRESS,

    // Install Post Ingress Table ARP rule
    INSTALL_ARP_POST_INGRESS,

    // Install Post Ingress Table ARP vxlan rule
    INSTALL_ARP_POST_INGRESS_VXLAN,

    // Install Post Ingress Table ARP one switch rule
    INSTALL_ARP_POST_INGRESS_ONE_SWITCH,

    // Install Transit table ARP rule
    INSTALL_ARP_TRANSIT,
    SERVER_42_INPUT,
    SERVER_42_OUTPUT,
    SERVER_42_TURNING,

    // Install all default rules (ie a combination of the above)
    INSTALL_DEFAULTS;
}

