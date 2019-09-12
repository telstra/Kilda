package org.openkilda.functionaltests.helpers

import static groovyx.gpars.GParsPool.withPool
import static org.openkilda.testing.Constants.EGRESS_RULE_MULTI_TABLE_ID
import static org.openkilda.testing.Constants.INGRESS_RULE_MULTI_TABLE_ID
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.SINGLE_TABLE_ID
import static org.openkilda.testing.Constants.TRANSIT_RULE_MULTI_TABLE_ID
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.payload.flow.DetectConnectedDevicesPayload
import org.openkilda.messaging.payload.flow.FlowCreatePayload
import org.openkilda.messaging.payload.flow.FlowEndpointPayload
import org.openkilda.messaging.payload.flow.FlowPayload
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.messaging.payload.history.FlowHistoryPayload
import org.openkilda.model.Flow
import org.openkilda.northbound.dto.v2.flows.FlowEndpointV2
import org.openkilda.testing.model.topology.TopologyDefinition
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.database.Database
import org.openkilda.testing.service.northbound.NorthboundService

import com.github.javafaker.Faker
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException

import java.text.SimpleDateFormat

/**
 * Holds utility methods for manipulating flows.
 */
@Component
@Slf4j
class FlowHelper {
    @Autowired
    TopologyDefinition topology
    @Autowired
    NorthboundService northbound
    @Autowired
    Database db

    def random = new Random()
    def faker = new Faker()
    def allowedVlans = 101..4095

    /**
     * Creates a FlowCreatePayload instance with random vlan and flow id. Will try to build over traffgen ports or use
     * random port otherwise.
     * Since multi-switch and single-switch flows have a bit different algorithms to create a correct flow, this method
     * will delegate the job to the correct algo based on src and dst switches passed.
     */
    FlowCreatePayload randomFlow(Switch srcSwitch, Switch dstSwitch, boolean useTraffgenPorts = true,
                                 List<FlowPayload> existingFlows = []) {
        if (srcSwitch.dpId == dstSwitch.dpId) {
            return singleSwitchFlow(srcSwitch, useTraffgenPorts, existingFlows)
        } else {
            return randomMultiSwitchFlow(srcSwitch, dstSwitch, useTraffgenPorts, existingFlows)
        }
    }

    FlowCreatePayload randomFlow(SwitchPair switchPair, boolean useTraffgenPorts = true,
                                 List<FlowPayload> existingFlows = []) {
        randomFlow(switchPair.src, switchPair.dst, useTraffgenPorts, existingFlows)
    }

    FlowCreatePayload randomMultiSwitchFlow(Switch srcSwitch, Switch dstSwitch, boolean useTraffgenPorts = true,
                                            List<FlowPayload> existingFlows = []) {
        Wrappers.retry(3, 0) {
            def newFlow = new FlowCreatePayload(generateFlowId(), getFlowEndpoint(srcSwitch, useTraffgenPorts),
                    getFlowEndpoint(dstSwitch, useTraffgenPorts), 500, false, false, false, generateDescription(),
                    null, null, null, null, null, null, false, null, null)
            if (flowConflicts(newFlow, existingFlows)) {
                throw new Exception("Generated flow conflicts with existing flows. Flow: $newFlow")
            }
            return newFlow
        } as FlowCreatePayload
    }

    /**
     * Creates a FlowCreatePayload instance with random vlan and flow id suitable for a single-switch flow.
     * The flow will be on DIFFERENT PORTS. Will try to look for both ports to be traffgen ports.
     * But if such port is not available, will pick a random one. So in order to run a correct traffic
     * examination certain switch should have at least 2 traffgens connected to different ports.
     */
    FlowCreatePayload singleSwitchFlow(Switch sw, boolean useTraffgenPorts = true,
                                       List<FlowPayload> existingFlows = []) {
        def allowedPorts = topology.getAllowedPortsForSwitch(sw)
        Wrappers.retry(3, 0) {
            def srcEndpoint = getFlowEndpoint(sw, allowedPorts, useTraffgenPorts)
            def dstEndpoint = getFlowEndpoint(sw, allowedPorts - srcEndpoint.portNumber, useTraffgenPorts)
            def newFlow = new FlowCreatePayload(generateFlowId(), srcEndpoint, dstEndpoint, 500, false, false, false,
                    generateDescription(), null, null, null, null, null, null, false, null, null)
            if (flowConflicts(newFlow, existingFlows)) {
                throw new Exception("Generated flow conflicts with existing flows. Flow: $newFlow")
            }
            return newFlow
        } as FlowCreatePayload
    }

    /**
     * Creates a FlowPayload instance with random vlan and flow id suitable for a single-switch flow.
     * The flow will be on the same port.
     */
    FlowPayload singleSwitchSinglePortFlow(Switch sw) {
        def allowedPorts = topology.getAllowedPortsForSwitch(sw)
        def srcEndpoint = getFlowEndpoint(sw, allowedPorts)
        def dstEndpoint = getFlowEndpoint(sw, [srcEndpoint.portNumber])
        if (srcEndpoint.vlanId == dstEndpoint.vlanId) { //ensure same vlan is not randomly picked
            dstEndpoint.vlanId--
        }
        return FlowPayload.builder()
                .id(generateFlowId())
                .source(srcEndpoint)
                .destination(dstEndpoint)
                .maximumBandwidth(500)
                .description(generateDescription())
                .build()
    }

    /**
     * Adds flow with checking flow status and rules on source and destination switches.
     * It is supposed if rules are installed on source and destination switches, the flow is completely created.
     */
    FlowPayload addFlow(FlowPayload flow) {
        log.debug("Adding flow '${flow.id}'")
        def response = northbound.addFlow(flow)

        def flowEntry = null
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getFlowStatus(flow.id).status == FlowState.UP

            flowEntry = db.getFlow(flow.id)
            assert flowEntry
        }
        checkRulesOnSwitches(flowEntry, RULES_INSTALLATION_TIME, true)

        return response
    }

    /**
     * Deletes flow with checking rules on source and destination switches.
     * It is supposed if rules absent on source and destination switches, the flow is completely deleted.
     */
    FlowPayload deleteFlow(String flowId) {
        deleteFlow(flowId, false)
    }

    FlowPayload deleteFlow(String flowId, boolean verifyMeters) {
        def flowEntry = db.getFlow(flowId)

        log.debug("Deleting flow '$flowId'")
        def response = northbound.deleteFlow(flowId)

        checkRulesOnSwitches(flowEntry, RULES_DELETION_TIME, false)

        if(verifyMeters && !flowEntry.ignoreBandwidth) {
            checkMetersOnSwitches(flowEntry, (RULES_DELETION_TIME / 2).toInteger(), false)
        }
        return response
    }

    /**
     * Updates flow with checking flow status and rules on source and destination switches.
     * It is supposed if rules are installed on source and destination switches, the flow is completely updated.
     */
    FlowPayload updateFlow(String flowId, FlowPayload flow) {
        def flowEntryBeforeUpdate = db.getFlow(flowId)

        log.debug("Updating flow '${flow.id}'")
        def response = northbound.updateFlow(flowId, flow)
        Wrappers.wait(PATH_INSTALLATION_TIME) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }

        def flowEntryAfterUpdate = db.getFlow(flowId)

        // TODO(ylobankov): Delete check for rules installation once we add a new test to verify this functionality.
        checkRulesOnSwitches(flowEntryAfterUpdate, RULES_INSTALLATION_TIME, true)
        checkRulesOnSwitches(flowEntryBeforeUpdate, RULES_DELETION_TIME, false)

        return response
    }

    /**
     * Check whether given potential flow is conflicting with any of flows in the given list.
     * Usually used to ensure that some new flow is by accident is not conflicting with any of existing flows.
     * Verifies conflicts by flow id and by port-vlan conflict on source or destination switch.
     *
     * @param newFlow this flow will be validated against the passed list
     * @param existingFlows the passed flow will be validated against this list
     * @return true if passed flow conflicts with any of the flows in the list
     */
    static boolean flowConflicts(FlowPayload newFlow, List<FlowPayload> existingFlows) {
        List<FlowEndpointPayload> existingEndpoints = existingFlows.collectMany { [it.source, it.destination] }
        [newFlow.source, newFlow.destination].any { newEp ->
            existingEndpoints.find {
                newEp.datapath == it.datapath && newEp.portNumber == it.portNumber &&
                        (newEp.vlanId == it.vlanId || it.vlanId == 0 || newEp.vlanId == 0)
            }
        } || existingFlows*.id.contains(newFlow.id)
    }

    /**
     * Converts a given FlowEndpointPayload object to FlowEndpointV2 object.
     *
     * @param endpoint FlowEndpointPayload object to convert
     */
    static FlowEndpointV2 toFlowEndpointV2(FlowEndpointPayload endpoint) {
        new FlowEndpointV2(endpoint.datapath, endpoint.portNumber, endpoint.vlanId)
    }

    /**
     * Check that all needed rules are created for a flow with protected path.<br>
     * Protected path creates the 'egress' rule only on the src and dst switches
     * and creates 2 rules(input/output) on the transit switches.<br>
     * if (switchId == src/dst): 2 rules for main flow path + 1 egress for protected path = 3<br>
     * if (switchId != src/dst): 2 rules for main flow path + 2 rules for protected path = 4<br>
     *
     * @param flowId
     */
    void verifyRulesOnProtectedFlow(String flowId) {
        def flowPathInfo = northbound.getFlowPath(flowId)
        def mainFlowPath = flowPathInfo.forwardPath
        def srcMainSwitch = mainFlowPath[0]
        def dstMainSwitch = mainFlowPath[-1]
        def mainFlowTransitSwitches = (mainFlowPath.size() > 2) ? mainFlowPath[1..-2] : []
        def protectedFlowPath = flowPathInfo.protectedPath.forwardPath
        def protectedFlowTransitSwitches = (protectedFlowPath.size() > 2) ? protectedFlowPath[1..-2] : []

        def commonSwitches = mainFlowPath*.switchId.intersect(protectedFlowPath*.switchId)
        def commonTransitSwitches = mainFlowTransitSwitches*.switchId.intersect(protectedFlowTransitSwitches*.switchId)
        def multiTableIsEnabled = (mainFlowPath + protectedFlowPath).unique {
            it.switchId
        }*.switchId.collectEntries { swId ->
            [swId, northbound.getSwitchProperties(swId).multiTable]
        }

        def flowInfo = db.getFlow(flowId)
        def mainForwardCookie = flowInfo.forwardPath.cookie.value
        def mainReverseCookie = flowInfo.reversePath.cookie.value
        def protectedForwardCookie = flowInfo.protectedForwardPath.cookie.value
        def protectedReverseCookie = flowInfo.protectedReversePath.cookie.value

        def rulesOnSrcSwitch = northbound.getSwitchRules(srcMainSwitch.switchId).flowEntries
        def multiTableStateSrcSw = multiTableIsEnabled[srcMainSwitch.switchId]
        assert rulesOnSrcSwitch.find {
            it.cookie == mainForwardCookie
        }.tableId == (multiTableStateSrcSw ? INGRESS_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID)
        assert rulesOnSrcSwitch.find {
            it.cookie == mainReverseCookie
        }.tableId == (multiTableStateSrcSw ? EGRESS_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID)
        assert rulesOnSrcSwitch.find {
            it.cookie == protectedReverseCookie
        }.tableId == (multiTableStateSrcSw ? EGRESS_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID)
        assert !rulesOnSrcSwitch*.cookie.contains(protectedForwardCookie)

        def rulesOnDstSwitch = northbound.getSwitchRules(dstMainSwitch.switchId).flowEntries
        def multiTableStateDstSw = multiTableIsEnabled[dstMainSwitch.switchId]
        assert rulesOnDstSwitch.find {
            it.cookie == mainForwardCookie
        }.tableId == (multiTableStateDstSw ? EGRESS_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID)
        assert rulesOnDstSwitch.find {
            it.cookie == mainReverseCookie
        }.tableId == (multiTableStateDstSw ? INGRESS_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID)
        assert rulesOnDstSwitch.find {
            it.cookie == protectedForwardCookie
        }.tableId == (multiTableStateDstSw ? EGRESS_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID)
        assert !rulesOnDstSwitch*.cookie.contains(protectedReverseCookie)

        //this loop checks rules on common nodes(except src and dst switches)
        withPool {
            commonTransitSwitches.eachParallel { sw ->
                def rules = northbound.getSwitchRules(sw).flowEntries
                def transitTableId = multiTableIsEnabled[sw] ? TRANSIT_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID
                assert rules.find { it.cookie == mainForwardCookie }.tableId == transitTableId
                assert rules.find { it.cookie == mainReverseCookie }.tableId == transitTableId
                assert rules.find { it.cookie == protectedForwardCookie }.tableId == transitTableId
                assert rules.find { it.cookie == protectedReverseCookie }.tableId == transitTableId
            }
        }

        //this loop checks rules on unique transit nodes
        withPool {
            protectedFlowTransitSwitches.findAll { !commonSwitches.contains(it.switchId) }.eachParallel { node ->
                def rules = northbound.getSwitchRules(node.switchId).flowEntries
                def transitTableId = multiTableIsEnabled[node.switchId] ? TRANSIT_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID
                assert rules.find { it.cookie == protectedForwardCookie }.tableId == transitTableId
                assert rules.find { it.cookie == protectedReverseCookie }.tableId == transitTableId
            }
        }

        //this loop checks rules on unique main nodes
        withPool {
            mainFlowTransitSwitches.findAll { !commonSwitches.contains(it.switchId) }.eachParallel { node ->
                def rules = northbound.getSwitchRules(node.switchId).flowEntries
                def transitTableId = multiTableIsEnabled[node.switchId] ? TRANSIT_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID
                assert rules.find { it.cookie == mainForwardCookie }.tableId == transitTableId
                assert rules.find { it.cookie == mainReverseCookie }.tableId == transitTableId
            }
        }
    }

    /**
     * Check that flow state is changed correctly after corresponding flow history action.
     *
     * @param flowId
     * @param timeFrom history will requested from this timestamp
     * @param event sequence of flow history actions
     * @param state expected flow state
     */
    void verifyFlowState(String flowId, Long timeFrom, FlowHistoryEvent event, state) {
        def timeTo = timeFrom ? System.currentTimeSeconds() : null
        def flowHistory = northbound.getFlowHistory(flowId, timeFrom, timeTo).findAll {
            it.action == event.initAction.toString()
        }
        assert flowHistory.size() == 1
        assert flowHistory.first().histories.last().action == event.finalAction.toString()
        assert northbound.getFlowStatus(flowId).status == state
    }

    void verifyFlowState(String flowId, FlowHistoryEvent event, FlowState state) {
        verifyFlowState(flowId, null, event, state)
    }

    /**
     * Returns flow endpoint with randomly chosen vlan.
     *
     * @param useTraffgenPorts whether to try finding a traffgen port
     */
    private FlowEndpointPayload getFlowEndpoint(Switch sw, boolean useTraffgenPorts = true) {
        getFlowEndpoint(sw, topology.getAllowedPortsForSwitch(sw), useTraffgenPorts)
    }

    /**
     * Returns flow endpoint with randomly chosen vlan.
     *
     * @param allowedPorts list of ports to randomly choose port from
     * @param useTraffgenPorts if true, will try to use a port attached to a traffgen. The port must be present
     * in 'allowedPorts'
     */
    private FlowEndpointPayload getFlowEndpoint(Switch sw, List<Integer> allowedPorts,
                                                boolean useTraffgenPorts = true) {
        def port = allowedPorts[random.nextInt(allowedPorts.size())]
        if (useTraffgenPorts) {
            def connectedTraffgens = topology.activeTraffGens.findAll { it.switchConnected == sw }
            if (!connectedTraffgens.empty) {
                port = connectedTraffgens.find { allowedPorts.contains(it.switchPort) }?.switchPort ?: port
            }
        }
        return new FlowEndpointPayload(sw.dpId, port, allowedVlans[random.nextInt(allowedVlans.size())],
                new DetectConnectedDevicesPayload(false, false))
    }

    /**
     * Generates a unique name for all auto-tests flows.
     */
    private String generateFlowId() {
        return new SimpleDateFormat("ddMMMHHmmss_SSS", Locale.US).format(new Date()) + "_" +
                faker.food().ingredient().toLowerCase().replaceAll(/\W/, "") + faker.number().digits(4)
    }

    private String generateDescription() {
        //The health of autotest flows is always questionable
        def descpription = [faker.shakespeare().asYouLikeItQuote(),
                            faker.shakespeare().kingRichardIIIQuote(),
                            faker.shakespeare().romeoAndJulietQuote(),
                            faker.shakespeare().hamletQuote()]
        def r = new Random()
        "autotest flow: ${descpription[r.nextInt(descpription.size())]}"
    }

    /**
     * Checks flow rules presence (or absence) on source and destination switches.
     */
    private void checkRulesOnSwitches(Flow flowEntry, int timeout, boolean rulesPresent) {
        def cookies = [flowEntry.forwardPath.cookie.value, flowEntry.reversePath.cookie.value]
        def switches = [flowEntry.srcSwitch.switchId, flowEntry.destSwitch.switchId].toSet()
        withPool {
            switches.eachParallel { sw ->
                Wrappers.wait(timeout) {
                    try {
                        def result = northbound.getSwitchRules(sw).flowEntries*.cookie
                        assert rulesPresent ? result.containsAll(cookies) : !result.any { it in cookies }, sw
                    } catch (HttpClientErrorException exc) {
                        if (exc.rawStatusCode == 404) {
                            log.warn("Switch '$sw' was not found when checking rules after flow "
                                    + (rulesPresent ? "creation" : "deletion"))
                        } else {
                            throw exc
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks flow meters presence (or absence) on source and destination switches.
     */
    private void checkMetersOnSwitches(Flow flowEntry, int timeout, boolean metersPresent) {
        def meters = [flowEntry.forwardPath.meterId.value, flowEntry.reversePath.meterId.value]
        def switches = [flowEntry.srcSwitch.switchId, flowEntry.destSwitch.switchId].toSet()
        withPool {
            switches.eachParallel { sw ->
                Wrappers.wait(timeout) {
                    try {
                        def result = northbound.getAllMeters(sw).meterEntries*.meterId
                        assert metersPresent ? result.containsAll(meters) : !result.any { it in metersPresent }, sw
                    } catch (HttpClientErrorException exc) {
                        if (exc.rawStatusCode == 404) {
                            log.warn("Switch '$sw' was not found when checking meters after flow "
                                    + (metersPresent ? "creation" : "deletion"))
                        } else {
                            throw exc
                        }
                    }
                }
            }
        }
    }
}
