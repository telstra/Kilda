package org.openkilda.functionaltests.spec.switches

import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs
import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.functionaltests.extension.tags.Tag.VIRTUAL
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static spock.util.matcher.HamcrestSupport.expect

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.command.switches.InstallRulesAction
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.info.rule.FlowEntry
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.Cookie
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.SwitchId
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.northbound.dto.v2.flows.FlowEndpointV2
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.Narrative
import spock.lang.Shared
import spock.lang.Unroll

@Narrative("""Verify how Kilda behaves with switch rules (either flow rules or default rules) under different 
circumstances: e.g. persisting rules on newly connected switch, installing default rules on new switch etc.""")
class FlowRulesSpec extends HealthCheckSpecification {
    @Value('${use.multitable}')
    boolean useMultiTable

    @Shared
    Switch srcSwitch, dstSwitch
    @Shared
    List srcSwDefaultRules
    @Shared
    List dstSwDefaultRules
    @Shared
    int flowRulesCount = 2

    @Shared
    // multiTableFlowRule is an extra rule which is created after creating a flow
    int multiTableFlowRulesCount = 1

    def setupOnce() {
        (srcSwitch, dstSwitch) = topology.getActiveSwitches()[0..1]
        srcSwDefaultRules = northbound.getSwitchRules(srcSwitch.dpId).flowEntries
        dstSwDefaultRules = northbound.getSwitchRules(dstSwitch.dpId).flowEntries
    }

    @Tags([VIRTUAL, SMOKE])
    def "Pre-installed flow rules are not deleted from a new switch connected to the controller"() {
        given: "A switch with proper flow rules installed (including default) and not connected to the controller"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        def defaultPlusFlowRules = []
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            defaultPlusFlowRules = northbound.getSwitchRules(srcSwitch.dpId).flowEntries
            def multiTableFlowRules = 0
            if (northbound.getSwitchProperties(srcSwitch.dpId).multiTable) {
                multiTableFlowRules = 1
            }
            assert defaultPlusFlowRules.size() == srcSwDefaultRules.size() + flowRulesCount + multiTableFlowRules
        }

        def blockData = switchHelper.knockoutSwitch(srcSwitch, mgmtFlManager)

        when: "Connect the switch to the controller"
        switchHelper.reviveSwitch(srcSwitch, blockData)

        then: "Previously installed rules are not deleted from the switch"
        compareRules(northbound.getSwitchRules(srcSwitch.dpId).flowEntries, defaultPlusFlowRules)

        and: "Cleanup: Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Unroll
    @Tags([SMOKE])
    @IterationTag(tags = [SMOKE_SWITCHES], iterationNameRegex = /delete-action=DROP_ALL\)/)
    def "Able to delete rules from a switch (delete-action=#data.deleteRulesAction)"() {
        given: "A switch with some flow rules installed"
        assumeTrue("Multi table should be disabled on the src switch",
                !northbound.getSwitchProperties(srcSwitch.dpId).multiTable)
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        when: "Delete rules from the switch"
        def expectedRules = data.getExpectedRules(srcSwitch, srcSwDefaultRules)
        def deletedRules = northbound.deleteSwitchRules(srcSwitch.dpId, data.deleteRulesAction)

        then: "The corresponding rules are really deleted"
        deletedRules.size() == data.rulesDeleted
        Wrappers.wait(RULES_DELETION_TIME) {
            compareRules(northbound.getSwitchRules(srcSwitch.dpId).flowEntries, expectedRules)
        }

        cleanup: "Delete the flow and install default rules if necessary"
        flowHelperV2.deleteFlow(flow.flowId)
        if (data.deleteRulesAction in [DeleteRulesAction.DROP_ALL, DeleteRulesAction.REMOVE_DEFAULTS]) {
            northbound.installSwitchRules(srcSwitch.dpId, InstallRulesAction.INSTALL_DEFAULTS)
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                assert northbound.getSwitchRules(srcSwitch.dpId).flowEntries.size() == srcSwDefaultRules.size()
            }
        }

        where:
        data << [
                [// Drop all rules in single-table mode
                 deleteRulesAction: DeleteRulesAction.DROP_ALL,
                 rulesDeleted     : srcSwDefaultRules.size() + flowRulesCount,
                 getExpectedRules : { sw, defaultRules -> [] }
                ],
                [// Drop all rules, add back in the base default rules in single-table mode
                 deleteRulesAction: DeleteRulesAction.DROP_ALL_ADD_DEFAULTS,
                 rulesDeleted     : srcSwDefaultRules.size() + flowRulesCount,
                 getExpectedRules : { sw, defaultRules -> defaultRules }
                ],
                [// Don't drop the default rules, but do drop everything else
                 deleteRulesAction: DeleteRulesAction.IGNORE_DEFAULTS,
                 rulesDeleted     : flowRulesCount,
                 getExpectedRules : { sw, defaultRules -> defaultRules }
                ],
                [// Drop all non-base rules (ie IGNORE), and add base rules back (eg overwrite)
                 deleteRulesAction: DeleteRulesAction.OVERWRITE_DEFAULTS,
                 rulesDeleted     : flowRulesCount,
                 getExpectedRules : { sw, defaultRules -> defaultRules }
                ],
                [// Drop all default rules in single-table mode
                 deleteRulesAction: DeleteRulesAction.REMOVE_DEFAULTS,
                 rulesDeleted     : srcSwDefaultRules.size(),
                 getExpectedRules : { sw, defaultRules -> getFlowRules(sw) }
                ],
                [// Drop the default, add them back in single-table mode
                 deleteRulesAction: DeleteRulesAction.REMOVE_ADD_DEFAULTS,
                 rulesDeleted     : srcSwDefaultRules.size(),
                 getExpectedRules : { sw, defaultRules -> defaultRules + getFlowRules(sw) }
                ]
        ]
    }

    @Tidy
    @Unroll
    @Tags([SMOKE])
    @IterationTag(tags = [SMOKE_SWITCHES], iterationNameRegex = /delete-action=DROP_ALL\)/)
    def "Able to delete rules from a switch with multi table mode (delete-action=#data.deleteRulesAction)"() {
        given: "A switch with some flow rules installed"
        assumeTrue("Multi table should be enabled on the src switch",
                northbound.getSwitchProperties(srcSwitch.dpId).multiTable)
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        when: "Delete rules from the switch"
        def expectedRules = data.getExpectedRules(srcSwitch, srcSwDefaultRules)
        def extraIngressFlowRule
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            extraIngressFlowRule = (northbound.getSwitchRules(srcSwitch.dpId).flowEntries - expectedRules).findAll {
                Cookie.isIngressRulePassThrough(it.cookie)
            }
            assert extraIngressFlowRule.size() == multiTableFlowRulesCount
        }

        if (data.deleteRulesAction in [DeleteRulesAction.DROP_ALL_ADD_DEFAULTS,
                                       DeleteRulesAction.IGNORE_DEFAULTS,
                                       DeleteRulesAction.REMOVE_ADD_DEFAULTS,
                                       DeleteRulesAction.OVERWRITE_DEFAULTS]) {
            expectedRules = (expectedRules + extraIngressFlowRule)
        }
        def deletedRules = northbound.deleteSwitchRules(srcSwitch.dpId, data.deleteRulesAction)

        then: "The corresponding rules are really deleted"
        deletedRules.size() == data.rulesDeleted
        Wrappers.wait(RULES_DELETION_TIME) {
            compareRules(northbound.getSwitchRules(srcSwitch.dpId).flowEntries, expectedRules)
        }

        cleanup: "Delete the flow and install default rules if necessary"
        flowHelperV2.deleteFlow(flow.flowId)
        if (data.deleteRulesAction in [DeleteRulesAction.DROP_ALL, DeleteRulesAction.REMOVE_DEFAULTS]) {
            northbound.installSwitchRules(srcSwitch.dpId, InstallRulesAction.INSTALL_DEFAULTS)
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                assert northbound.getSwitchRules(srcSwitch.dpId).flowEntries.size() == srcSwDefaultRules.size()
            }
        }

        where:
        data << [
                [// Drop all rules
                 deleteRulesAction: DeleteRulesAction.DROP_ALL,
                 rulesDeleted     : srcSwDefaultRules.size() + flowRulesCount + multiTableFlowRulesCount,
                 getExpectedRules : { sw, defaultRules -> [] }
                ],
                [// Drop all rules, add back in the base default rules
                 deleteRulesAction: DeleteRulesAction.DROP_ALL_ADD_DEFAULTS,
                 rulesDeleted     : srcSwDefaultRules.size() + flowRulesCount + multiTableFlowRulesCount,
                 getExpectedRules : { sw, defaultRules -> defaultRules }
                ],
                [// Don't drop the default rules, but do drop everything else
                 deleteRulesAction: DeleteRulesAction.IGNORE_DEFAULTS,
                 rulesDeleted     : flowRulesCount,
                 getExpectedRules : { sw, defaultRules -> defaultRules }
                ],
                [// Drop all non-base rules (ie IGNORE), and add base rules back (eg overwrite)
                 deleteRulesAction: DeleteRulesAction.OVERWRITE_DEFAULTS,
                 rulesDeleted     : flowRulesCount,
                 getExpectedRules : { sw, defaultRules -> defaultRules }
                ],
                [// Drop all default rules
                 deleteRulesAction: DeleteRulesAction.REMOVE_DEFAULTS,
                 rulesDeleted     : srcSwDefaultRules.size() + multiTableFlowRulesCount,
                 getExpectedRules : { sw, defaultRules -> getFlowRules(sw) }
                ],
                [// Drop the default, add them back
                 deleteRulesAction: DeleteRulesAction.REMOVE_ADD_DEFAULTS,
                 rulesDeleted     : srcSwDefaultRules.size() + multiTableFlowRulesCount,
                 getExpectedRules : { sw, defaultRules -> defaultRules + getFlowRules(sw) }
                ]
        ]
    }

    @Tidy
    @Unroll("Able to delete switch rules by #data.description")
    @Tags([SMOKE, SMOKE_SWITCHES])
    def "Able to delete switch rules by cookie/priority"() {
        given: "A switch with some flow rules installed"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        if (northbound.getSwitchProperties(srcSwitch.dpId).multiTable ) {
            def ingressRule = (northbound.getSwitchRules(srcSwitch.dpId).flowEntries - data.defaultRules).find {
                Cookie.isDefaultRule(it.cookie)
            }
            if (ingressRule) {
                data.defaultRules = (data.defaultRules + ingressRule)
            }

        }
        when: "Delete switch rules by #data.description"
        def deletedRules = northbound.deleteSwitchRules(
                data.switch.dpId, getFlowRules(data.switch).first()."$data.description")

        then: "The requested rules are really deleted"
        deletedRules.size() == data.rulesDeleted
        Wrappers.wait(RULES_DELETION_TIME) {
            def actualRules = northbound.getSwitchRules(data.switch.dpId).flowEntries
            assert actualRules.size() == data.defaultRules.size() + flowRulesCount - data.rulesDeleted
            assert actualRules.findAll { it.cookie in deletedRules }.empty
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [[description : "cookie",
                  switch      : srcSwitch,
                  defaultRules: srcSwDefaultRules,
                  rulesDeleted: 1
                 ],
                 [description : "priority",
                  switch      : dstSwitch,
                  defaultRules: dstSwDefaultRules,
                  rulesDeleted: 2
                 ]
        ]
    }

    @Tidy
    @Unroll("Attempt to delete switch rules by supplying non-existing #data.description leaves all rules intact")
    def "Attempt to delete switch rules by supplying non-existing cookie/priority leaves all rules intact"() {
        given: "A switch with some flow rules installed"
        //TODO(ylobankov): Remove this code once the issue #1701 is resolved.
        assumeTrue("Test is skipped because of the issue #1701", data.description != "priority")

        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        if (northbound.getSwitchProperties(srcSwitch.dpId).multiTable ) {
            def ingressRule = (northbound.getSwitchRules(srcSwitch.dpId).flowEntries - data.defaultRules).find {
                Cookie.isDefaultRule(it.cookie)
            }
            if (ingressRule) {
                data.defaultRules = (data.defaultRules + ingressRule)
            }

        }

        when: "Delete switch rules by non-existing #data.description"
        def deletedRules = northbound.deleteSwitchRules(data.switch.dpId, data.value)

        then: "All rules are kept intact"
        deletedRules.size() == 0
        northbound.getSwitchRules(data.switch.dpId).flowEntries.size() == data.defaultRules.size() + flowRulesCount

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [[description : "cookie",
                  switch      : srcSwitch,
                  defaultRules: srcSwDefaultRules,
                  value       : 0x8000000000000000L
                 ],
                 [description : "priority",
                  switch      : dstSwitch,
                  defaultRules: dstSwDefaultRules,
                  value       : 0
                 ]
        ]
    }

    @Tidy
    @Unroll("Able to delete switch rules by #data.description")
    @Tags(SMOKE_SWITCHES)
    @IterationTag(tags = [SMOKE], iterationNameRegex = /inPort/)
    def "Able to delete switch rules by inPort/inVlan/outPort"() {
        given: "A switch with some flow rules installed"
        flowHelperV2.addFlow(flow)
        def expectedRemovedRules = 1
        if (northbound.getSwitchProperties(srcSwitch.dpId).multiTable) {
            def ingressRule = (northbound.getSwitchRules(srcSwitch.dpId).flowEntries - data.defaultRules).find {
                Cookie.isDefaultRule(it.cookie)
            }
            if (ingressRule && data.removedRules == 1) {
                data.defaultRules = (data.defaultRules + ingressRule)
            }
            expectedRemovedRules = data.removedRules
        }

        when: "Delete switch rules by #data.description"
        def deletedRules = northbound.deleteSwitchRules(data.switch.dpId, data.inPort, data.inVlan,
                data.encapsulationType, data.outPort)

        then: "The requested rules are really deleted"
        deletedRules.size() == expectedRemovedRules
        Wrappers.wait(RULES_DELETION_TIME) {
            def actualRules = northbound.getSwitchRules(data.switch.dpId).flowEntries
            assert actualRules.size() == data.defaultRules.size() + flowRulesCount - 1
            assert actualRules.findAll { it.cookie in deletedRules }.empty
            assert filterRules(actualRules, data.inPort, data.inVlan, data.outPort).empty
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        flow << [buildFlow()] * 4
        data << [[description      : "inPort",
                  switch           : srcSwitch,
                  defaultRules     : srcSwDefaultRules,
                  inPort           : flow.source.portNumber,
                  inVlan           : null,
                  encapsulationType: null,
                  outPort          : null,
                  removedRules     : 2
                 ],
                 [description      : "inVlan",
                  switch           : srcSwitch,
                  defaultRules     : srcSwDefaultRules,
                  inPort           : null,
                  inVlan           : flow.source.vlanId,
                  encapsulationType: "TRANSIT_VLAN",
                  outPort          : null,
                  removedRules     : 1
                 ],
                 [description      : "inPort and inVlan",
                  switch           : srcSwitch,
                  defaultRules     : srcSwDefaultRules,
                  inPort           : flow.source.portNumber,
                  inVlan           : flow.source.vlanId,
                  encapsulationType: "TRANSIT_VLAN",
                  outPort          : null,
                  removedRules     : 1
                 ],
                 [description      : "outPort",
                  switch           : dstSwitch,
                  defaultRules     : dstSwDefaultRules,
                  inPort           : null,
                  inVlan           : null,
                  encapsulationType: null,
                  outPort          : flow.destination.portNumber,
                  removedRules     : 1
                 ]
        ]
    }

    @Tidy
    @Unroll("Attempt to delete switch rules by supplying non-existing #data.description leaves all rules intact")
    @IterationTag(tags = [SMOKE], iterationNameRegex = /inVlan/)
    def "Attempt to delete switch rules by supplying non-existing inPort/inVlan/outPort leaves all rules intact"() {
        given: "A switch with some flow rules installed"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        if (northbound.getSwitchProperties(srcSwitch.dpId).multiTable) {
            def ingressRule = (northbound.getSwitchRules(srcSwitch.dpId).flowEntries - data.defaultRules).find {
                Cookie.isDefaultRule(it.cookie)
            }
            if (ingressRule) {
                data.defaultRules = (data.defaultRules + ingressRule)
            }

        }

        when: "Delete switch rules by non-existing #data.description"
        def deletedRules = northbound.deleteSwitchRules(data.switch.dpId, data.inPort, data.inVlan,
                data.encapsulationType, data.outPort)

        then: "All rules are kept intact"
        deletedRules.size() == 0
        northbound.getSwitchRules(data.switch.dpId).flowEntries.size() == data.defaultRules.size() + flowRulesCount

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [[description      : "inPort",
                  switch           : srcSwitch,
                  defaultRules     : srcSwDefaultRules,
                  inPort           : Integer.MAX_VALUE - 1,
                  inVlan           : null,
                  encapsulationType: null,
                  outPort          : null
                 ],
                 [description      : "inVlan",
                  switch           : srcSwitch,
                  defaultRules     : srcSwDefaultRules,
                  inPort           : null,
                  inVlan           : 4095,
                  encapsulationType: "TRANSIT_VLAN",
                  outPort          : null
                 ],
                 [description      : "inPort and inVlan",
                  switch           : srcSwitch,
                  defaultRules     : srcSwDefaultRules,
                  inPort           : Integer.MAX_VALUE - 1,
                  inVlan           : 4095,
                  encapsulationType: "TRANSIT_VLAN",
                  outPort          : null
                 ],
                 [description      : "outPort",
                  switch           : dstSwitch,
                  defaultRules     : dstSwDefaultRules,
                  inPort           : null,
                  inVlan           : null,
                  encapsulationType: null,
                  outPort          : Integer.MAX_VALUE - 1
                 ]
        ]
    }

    @Tidy
    @Unroll
    @Tags([TOPOLOGY_DEPENDENT])
    def "Able to validate and sync missing rules for #description on terminating/transit switches"() {
        given: "Two active not neighboring switches with the longest available path"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().max { pair ->
            pair.paths.max { it.size() }.size()
        }
        assumeTrue("Unable to find required switches in topology", switchPair as boolean)
        def longPath = switchPair.paths.max { it.size() }
        switchPair.paths.findAll { it != longPath }.each { pathHelper.makePathMorePreferable(longPath, it) }

        and: "Create a transit-switch flow going through these switches"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.maximumBandwidth = maximumBandwidth
        flow.ignoreBandwidth = maximumBandwidth ? false : true
        flowHelperV2.addFlow(flow)

        and: "Remove flow rules so that they become 'missing'"
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)*.dpId
        def defaultPlusFlowRulesMap = involvedSwitches.collectEntries { switchId ->
            [switchId, northbound.getSwitchRules(switchId).flowEntries]
        }

        involvedSwitches.each { switchId ->
            northbound.deleteSwitchRules(switchId, DeleteRulesAction.IGNORE_DEFAULTS)
            Wrappers.wait(RULES_DELETION_TIME) {
                assert northbound.validateSwitchRules(switchId).missingRules.size() == flowRulesCount
            }
        }

        when: "Synchronize rules on switches"
        def synchronizedRulesMap = involvedSwitches.collectEntries { switchId ->
            [switchId, northbound.synchronizeSwitchRules(switchId)]
        }

        then: "The corresponding rules are installed on switches"
        involvedSwitches.each { switchId ->
            assert synchronizedRulesMap[switchId].installedRules.size() == flowRulesCount
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                compareRules(northbound.getSwitchRules(switchId).flowEntries, defaultPlusFlowRulesMap[switchId])
            }
        }

        and: "No missing rules were found after rules validation"
        involvedSwitches.each { switchId ->
            verifyAll(northbound.validateSwitchRules(switchId)) {
                properRules.findAll { !Cookie.isDefaultRule(it) }.size() == flowRulesCount
                missingRules.empty
                excessRules.empty
            }
        }

        cleanup: "Delete the flow and reset costs"
        flowHelperV2.deleteFlow(flow.flowId)
        northbound.deleteLinkProps(northbound.getAllLinkProps())

        where:
        description         | maximumBandwidth
        "a flow"            | 1000
        "an unmetered flow" | 0
    }

    @Tidy
    @Unroll
    def "Unable to #action rules on a non-existent switch"() {
        when: "Try to #action rules on a non-existent switch"
        northbound."$method"(NON_EXISTENT_SWITCH_ID)

        then: "An error is received (404 code)"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        exc.responseBodyAsString.to(MessageError).errorMessage == "Switch '$NON_EXISTENT_SWITCH_ID' not found"

        where:
        action        | method
        "synchronize" | "synchronizeSwitchRules"
        "validate"    | "validateSwitchRules"
    }

    @Ignore
    @Tidy
    @Tags([LOW_PRIORITY])//uses legacy 'rules validation', has a switchValidate analog in SwitchValidationSpec
    def "Able to synchronize rules for a flow with protected path"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue("No suiting switches found", false)

        and: "Create a flow with protected path"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelperV2.addFlow(flow)

        flowHelper.verifyRulesOnProtectedFlow(flow.flowId)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        def mainFlowPath = flowPathInfo.forwardPath
        def protectedFlowPath = flowPathInfo.protectedPath.forwardPath
        def commonNodeIds = mainFlowPath*.switchId.intersect(protectedFlowPath*.switchId)
        def uniqueNodes = protectedFlowPath.findAll { !commonNodeIds.contains(it.switchId) } + mainFlowPath.findAll {
            !commonNodeIds.contains(it.switchId)
        }

        and: "Delete flow rules(for main and protected paths) on involved switches for creating missing rules"
        def amountOfRules = { SwitchId switchId ->
            (switchId == switchPair.src.dpId || switchId == switchPair.dst.dpId) ? 3 : 4
        }
        commonNodeIds.each { northbound.deleteSwitchRules(it, DeleteRulesAction.IGNORE_DEFAULTS) }
        uniqueNodes.each { northbound.deleteSwitchRules(it.switchId, DeleteRulesAction.IGNORE_DEFAULTS) }

        commonNodeIds.each { switchId ->
            assert northbound.validateSwitchRules(switchId).missingRules.size() == amountOfRules(switchId)
        }

        uniqueNodes.each { assert northbound.validateSwitchRules(it.switchId).missingRules.size() == 2 }

        when: "Synchronize rules on switches"
        commonNodeIds.each {
            def response = northbound.synchronizeSwitchRules(it)
            assert response.missingRules.size() == amountOfRules(it)
            assert response.installedRules.sort() == response.missingRules.sort()
            assert response.properRules.findAll { !Cookie.isDefaultRule(it) }.empty
            assert response.excessRules.empty
        }
        uniqueNodes.each {
            def response = northbound.synchronizeSwitchRules(it.switchId)
            assert response.missingRules.size() == 2
            assert response.installedRules.sort() == response.missingRules.sort()
            assert response.properRules.findAll { !Cookie.isDefaultRule(it) }.empty, it
            assert response.excessRules.empty
        }

        then: "No missing rules were found after rules synchronization"
        commonNodeIds.each { switchId ->
            verifyAll(northbound.validateSwitchRules(switchId)) {
                properRules.findAll { !Cookie.isDefaultRule(it) }.size() == amountOfRules(switchId)
                missingRules.empty
                excessRules.empty
            }
        }

        uniqueNodes.each {
            verifyAll(northbound.validateSwitchRules(it.switchId)) {
                properRules.findAll { !Cookie.isDefaultRule(it) }.size() == 2
                missingRules.empty
                excessRules.empty
            }
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags([SMOKE, SMOKE_SWITCHES])
    def "Traffic counters in ingress rule are reset on flow rerouting"() {
        given: "Two active neighboring switches and two possible flow paths at least"
        List<List<PathNode>> possibleFlowPaths = []
        def isl = topology.getIslsForActiveSwitches().find {
            possibleFlowPaths = database.getPaths(it.srcSwitch.dpId, it.dstSwitch.dpId)*.path.sort { it.size() }
            possibleFlowPaths.size() > 1 && !it.srcSwitch.centec && !it.dstSwitch.centec &&
                 it.srcSwitch.ofVersion != "OF_12" && it.dstSwitch.ofVersion != "OF_12"
        } ?: assumeTrue("No suiting switches found", false)
        def (srcSwitch, dstSwitch) = [isl.srcSwitch, isl.dstSwitch]

        and: "Create a flow going through these switches"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)
        def flowCookiesMap = [srcSwitch, dstSwitch].collectEntries { [it, getFlowRules(it)*.cookie] }

        when: "Ping the flow"
        def response = northbound.pingFlow(flow.flowId, new PingInput())

        then: "Operation is successful"
        response.forward.pingSuccess
        response.reverse.pingSuccess

        and: "Traffic counters in ingress rule on source and destination switches represent packets movement"
        checkTrafficCountersInRules(flow.source, true)
        checkTrafficCountersInRules(flow.destination, true)

        when: "Break the flow ISL (bring switch port down) to cause flow rerouting"
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))
        // Switches may have parallel links, so we need to get involved ISLs.
        def islToFail = pathHelper.getInvolvedIsls(flowPath).first()
        def portDown = antiflap.portDown(islToFail.srcSwitch.dpId, islToFail.srcPort)

        then: "The flow was rerouted after reroute timeout"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert PathHelper.convert(northbound.getFlowPath(flow.flowId)) != flowPath
            [srcSwitch, dstSwitch].each { sw -> getFlowRules(sw).each { assert !(it.cookie in flowCookiesMap[sw]) } }
        }

        and: "Traffic counters in ingress rule on source and destination switches are reset"
        checkTrafficCountersInRules(flow.source, false)
        checkTrafficCountersInRules(flow.destination, false)

        cleanup: "Revive the ISL back (bring switch port up), delete the flow and reset costs"
        flowHelperV2.deleteFlow(flow.flowId)
        portDown && antiflap.portUp(islToFail.srcSwitch.dpId, islToFail.srcPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tidy
    @Tags([HARDWARE, LOW_PRIORITY])//uses legacy 'rules validation', has a switchValidate analog in SwitchValidationSpec
    @Ignore("https://github.com/telstra/open-kilda/issues/3021")
    def "Able to synchronize rules for a flow with VXLAN encapsulation"() {
        given: "Two active not neighboring Noviflow switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { swP ->
            swP.src.noviflow && !swP.src.wb5164 && swP.dst.noviflow && !swP.dst.wb5164 && swP.paths.find { path ->
                pathHelper.getInvolvedSwitches(path).every { it.noviflow && !it.wb5164 }
            }
        } ?: assumeTrue("Unable to find required switches in topology", false)

        and: "Create a flow with vxlan encapsulation"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        flowHelperV2.addFlow(flow)

        and: "Delete flow rules so that they become 'missing'"
        def flowInfoFromDb = database.getFlow(flow.flowId)
        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)*.dpId
        def transitSwitchIds = involvedSwitches[1..-2]
        def defaultPlusFlowRulesMap = involvedSwitches.collectEntries { switchId ->
            [switchId, northbound.getSwitchRules(switchId).flowEntries]
        }

        involvedSwitches.each { switchId ->
            northbound.deleteSwitchRules(switchId, DeleteRulesAction.IGNORE_DEFAULTS)
            Wrappers.wait(RULES_DELETION_TIME) {
                assert northbound.validateSwitchRules(switchId).missingRules.size() == flowRulesCount
            }
        }

        when: "Synchronize rules on switches"
        def synchronizedRulesMap = involvedSwitches.collectEntries { switchId ->
            [switchId, northbound.synchronizeSwitchRules(switchId)]
        }

        then: "The corresponding rules are installed on switches"
        involvedSwitches.each { switchId ->
            assert synchronizedRulesMap[switchId].installedRules.size() == flowRulesCount
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                compareRules(northbound.getSwitchRules(switchId).flowEntries, defaultPlusFlowRulesMap[switchId])
            }
        }

        and: "Rules are synced correctly"
        // ingressRule should contain "pushVxlan"
        // egressRule should contain "tunnel-id"
        with(northbound.getSwitchRules(switchPair.src.dpId).flowEntries) { rules ->
            assert rules.find {
                it.cookie == flowInfoFromDb.forwardPath.cookie.value
            }.instructions.applyActions.pushVxlan
            assert rules.find {
                it.cookie == flowInfoFromDb.reversePath.cookie.value
            }.match.tunnelId
        }

        with(northbound.getSwitchRules(switchPair.dst.dpId).flowEntries) { rules ->
            assert rules.find {
                it.cookie == flowInfoFromDb.forwardPath.cookie.value
            }.match.tunnelId
            assert rules.find {
                it.cookie == flowInfoFromDb.reversePath.cookie.value
            }.instructions.applyActions.pushVxlan
        }

        transitSwitchIds.each { swId ->
            with(northbound.getSwitchRules(swId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb.forwardPath.cookie.value
                }.match.tunnelId
                assert rules.find {
                    it.cookie == flowInfoFromDb.reversePath.cookie.value
                }.match.tunnelId
            }
        }

        and: "No missing rules were found after rules validation"
        involvedSwitches.each { switchId ->
            verifyAll(northbound.validateSwitchRules(switchId)) {
                properRules.size().findAll { !Cookie.isDefaultRule(it) }.size() == flowRulesCount
                missingRules.empty
                excessRules.empty
            }
        }

        cleanup: "Delete the flow and reset costs"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    void compareRules(actualRules, expectedRules) {
        assert expect(actualRules.sort { it.cookie }, sameBeanAs(expectedRules.sort { it.cookie })
                .ignoring("byteCount")
                .ignoring("packetCount")
                .ignoring("durationNanoSeconds")
                .ignoring("durationSeconds"))
    }

    FlowRequestV2 buildFlow() {
        flowHelperV2.randomFlow(srcSwitch, dstSwitch)
    }

    List<FlowEntry> filterRules(List<FlowEntry> rules, inPort, inVlan, outPort) {
        if (inPort) {
            rules = rules.findAll { it.match.inPort == inPort.toString() }
        }
        if (inVlan) {
            rules = rules.findAll { it.match.vlanVid == inVlan.toString() }
        }
        if (outPort) {
            rules = rules.findAll { it.instructions?.applyActions?.flowOutput == outPort.toString() }
        }

        return rules
    }

    void checkTrafficCountersInRules(FlowEndpointV2 flowEndpoint, isTrafficThroughIngressRuleExpected) {
        def rules = northbound.getSwitchRules(flowEndpoint.switchId).flowEntries
        def ingressRule = filterRules(rules, flowEndpoint.portNumber, flowEndpoint.vlanId, null)[0]
        def egressRule = filterRules(rules, null, null, flowEndpoint.portNumber).find {
            it.instructions.applyActions.fieldAction.fieldValue == flowEndpoint.vlanId.toString()
        }

        assert ingressRule.flags.contains("RESET_COUNTS")
        assert isTrafficThroughIngressRuleExpected == (ingressRule.packetCount > 0)
        assert isTrafficThroughIngressRuleExpected == (ingressRule.byteCount > 0)

        assert !egressRule.flags.contains("RESET_COUNTS")
        assert egressRule.packetCount == 0
        assert egressRule.byteCount == 0
    }

    List<FlowEntry> getFlowRules(Switch sw) {
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll { !(it.cookie in sw.defaultCookies) }.sort()
    }
}
