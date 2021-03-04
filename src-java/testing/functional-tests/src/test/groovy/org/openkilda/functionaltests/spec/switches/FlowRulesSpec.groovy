package org.openkilda.functionaltests.spec.switches

import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs
import static groovyx.gpars.GParsPool.withPool
import static org.junit.jupiter.api.Assumptions.assumeTrue
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
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW
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
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.northbound.dto.v2.flows.FlowEndpointV2
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.Narrative
import spock.lang.Shared
import spock.lang.Unroll

import javax.inject.Provider

@Narrative("""Verify how Kilda behaves with switch rules (either flow rules or default rules) under different 
circumstances: e.g. persisting rules on newly connected switch, installing default rules on new switch etc.""")
class FlowRulesSpec extends HealthCheckSpecification {

    @Autowired
    Provider<TraffExamService> traffExamProvider

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
    @Shared
    int sharedRulesCount = 1

    def setupSpec() {
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
                multiTableFlowRules = multiTableFlowRulesCount + sharedRulesCount
            }
            assert defaultPlusFlowRules.size() == srcSwDefaultRules.size() + flowRulesCount + multiTableFlowRules
        }

        def blockData = switchHelper.knockoutSwitch(srcSwitch, RW)

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
        assumeTrue(!northbound.getSwitchProperties(srcSwitch.dpId).multiTable,
"Multi table should be disabled on the src switch")
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
        assumeTrue(northbound.getSwitchProperties(srcSwitch.dpId).multiTable,
"Multi table should be enabled on the src switch")
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
                 rulesDeleted     : srcSwDefaultRules.size() + flowRulesCount + multiTableFlowRulesCount + sharedRulesCount,
                 getExpectedRules : { sw, defaultRules -> [] }
                ],
                [// Drop all rules, add back in the base default rules
                 deleteRulesAction: DeleteRulesAction.DROP_ALL_ADD_DEFAULTS,
                 rulesDeleted     : srcSwDefaultRules.size() + flowRulesCount + multiTableFlowRulesCount + sharedRulesCount,
                 getExpectedRules : { sw, defaultRules -> defaultRules }
                ],
                [// Don't drop the default rules, but do drop everything else
                 deleteRulesAction: DeleteRulesAction.IGNORE_DEFAULTS,
                 rulesDeleted     : flowRulesCount + sharedRulesCount,
                 getExpectedRules : { sw, defaultRules -> defaultRules }
                ],
                [// Drop all non-base rules (ie IGNORE), and add base rules back (eg overwrite)
                 deleteRulesAction: DeleteRulesAction.OVERWRITE_DEFAULTS,
                 rulesDeleted     : flowRulesCount + sharedRulesCount,
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
    @Unroll("Able to delete switch rules by #data.identifier")
    @Tags([SMOKE, SMOKE_SWITCHES])
    def "Able to delete switch rules by cookie/priority"() {
        given: "A switch with some flow rules installed"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        when: "Delete switch rules by #data.identifier"
        //exclude the "SERVER_42_INPUT" rule, this rule has less priority than usual flow rule
        def ruleToDelete = getFlowRules(data.switch).find { !new Cookie(it.cookie).serviceFlag }
        def expectedDeletedRules = northbound.getSwitchRules(data.switch.dpId).flowEntries
                .findAll { it."$data.identifier" ==  ruleToDelete."$data.identifier" &&
                !new Cookie(it.cookie).serviceFlag }
        def deletedRules = northbound.deleteSwitchRules(data.switch.dpId, ruleToDelete."$data.identifier")

        then: "The requested rules are really deleted"
        deletedRules.size() == expectedDeletedRules.size()
        Wrappers.wait(RULES_DELETION_TIME) {
            def actualRules = northbound.getSwitchRules(data.switch.dpId).flowEntries
            assert actualRules.findAll { it.cookie in expectedDeletedRules*.cookie }.empty
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [
                [identifier : "cookie",
                  switch      : srcSwitch
                 ],
                 [identifier : "priority",
                  switch      : dstSwitch
                 ]
        ]
    }

    @Tidy
    @Unroll("Attempt to delete switch rules by supplying non-existing #data.description leaves all rules intact")
    def "Attempt to delete switch rules by supplying non-existing cookie/priority leaves all rules intact"() {
        given: "A switch with some flow rules installed"
        //TODO(ylobankov): Remove this code once the issue #1701 is resolved.
        assumeTrue(data.description != "priority", "Test is skipped because of the issue #1701")

        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        if (northbound.getSwitchProperties(srcSwitch.dpId).multiTable ) {
            def ingressRule = (northbound.getSwitchRules(srcSwitch.dpId).flowEntries - data.defaultRules).find {
                new Cookie(it.cookie).serviceFlag
            }
            if (ingressRule) {
                data.defaultRules = (data.defaultRules + ingressRule + sharedRulesCount)
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
        def cookiesBefore = northbound.getSwitchRules(data.switch.dpId).flowEntries*.cookie.sort()

        when: "Delete switch rules by #data.description"
        def deletedRules = northbound.deleteSwitchRules(data.switch.dpId, data.inPort, data.inVlan,
                data.encapsulationType, data.outPort)

        then: "The requested rules are really deleted"
        deletedRules.size() == data.removedRules
        Wrappers.wait(RULES_DELETION_TIME) {
            def actualRules = northbound.getSwitchRules(data.switch.dpId).flowEntries
            assert actualRules*.cookie.sort() == cookiesBefore - deletedRules
            assert filterRules(actualRules, data.inPort, data.inVlan, data.outPort).empty
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [[description      : "inPort",
                  flow             : buildFlow(),
                  switch           : srcSwitch,
                  inVlan           : null,
                  encapsulationType: null,
                  outPort          : null,
                  removedRules     : 1 + (getUseMultitable() ? 2 : 0)
                 ].tap { inPort = flow.source.portNumber },
                 [description      : "inVlan",
                  flow             : buildFlow(),
                  switch           : srcSwitch,
                  inPort           : null,
                  encapsulationType: "TRANSIT_VLAN",
                  outPort          : null,
                  removedRules     : 1
                 ].tap { inVlan = flow.source.vlanId },
                 [description      : "inPort and inVlan",
                  flow             : buildFlow(),
                  switch           : srcSwitch,
                  encapsulationType: "TRANSIT_VLAN",
                  outPort          : null,
                  removedRules     : 1
                 ].tap { inVlan = flow.source.vlanId; inPort = flow.source.portNumber },
                 [description      : "outPort",
                  flow             : buildFlow(),
                  switch           : dstSwitch,
                  inPort           : null,
                  inVlan           : null,
                  encapsulationType: null,
                  removedRules     : 1
                 ].tap { outPort = flow.destination.portNumber },
        ]
        flow = data.flow as FlowRequestV2
    }

    @Tidy
    @Unroll("Attempt to delete switch rules by supplying non-existing #data.description leaves all rules intact")
    @IterationTag(tags = [SMOKE], iterationNameRegex = /inVlan/)
    def "Attempt to delete switch rules by supplying non-existing inPort/inVlan/outPort leaves all rules intact"() {
        given: "A switch with some flow rules installed"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)
        def originalRules = northbound.getSwitchRules(data.switch.dpId).flowEntries*.cookie.sort()

        when: "Delete switch rules by non-existing #data.description"
        def deletedRules = northbound.deleteSwitchRules(data.switch.dpId, data.inPort, data.inVlan,
                data.encapsulationType, data.outPort)

        then: "All rules are kept intact"
        deletedRules.size() == 0
        northbound.getSwitchRules(data.switch.dpId).flowEntries*.cookie.sort() == originalRules

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
        assumeTrue(switchPair as boolean, "Unable to find required switches in topology")
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

        def amountOfRulesMap = involvedSwitches.collectEntries { switchId ->
            def swProps = northbound.getSwitchProperties(switchId)
            def switchIdInSrcOrDst = (switchId in [switchPair.src.dpId, switchPair.dst.dpId])
            def defaultAmountOfFlowRules = 2 // ingress + egress
            def rulesCount = defaultAmountOfFlowRules + (switchIdInSrcOrDst && swProps.multiTable ? 1 : 0) +
                    (switchIdInSrcOrDst && swProps.server42FlowRtt ? 1 : 0)
            [switchId, (rulesCount)]
        }
        involvedSwitches.each { switchId ->
            northbound.deleteSwitchRules(switchId, DeleteRulesAction.IGNORE_DEFAULTS)
            Wrappers.wait(RULES_DELETION_TIME) {
                assert northbound.validateSwitchRules(switchId).missingRules.size() == amountOfRulesMap[switchId]
            }
        }

        when: "Synchronize rules on switches"
        def synchronizedRulesMap = involvedSwitches.collectEntries { switchId ->
            [switchId, northbound.synchronizeSwitchRules(switchId)]
        }

        then: "The corresponding rules are installed on switches"
        involvedSwitches.each { switchId ->
            assert synchronizedRulesMap[switchId].installedRules.size() == amountOfRulesMap[switchId]
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                compareRules(northbound.getSwitchRules(switchId).flowEntries, defaultPlusFlowRulesMap[switchId])
            }
        }

        and: "No missing rules were found after rules validation"
        involvedSwitches.each { switchId ->
            verifyAll(northbound.validateSwitchRules(switchId)) {
                properRules.findAll { !new Cookie(it).serviceFlag }.size() == amountOfRulesMap[switchId]
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

    @Tidy
    @Tags([LOW_PRIORITY])//uses legacy 'rules validation', has a switchValidate analog in SwitchValidationSpec
    def "Able to synchronize rules for a flow with protected path"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue(false, "No suiting switches found")

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
        def rulesOnCommonNodesBefore = commonNodeIds.collectEntries {
            [it, northbound.getSwitchRules(it).flowEntries*.cookie.sort()]
        }

        and: "Delete flow rules(for main and protected paths) on involved switches for creating missing rules"
        commonNodeIds.each { northbound.deleteSwitchRules(it, DeleteRulesAction.IGNORE_DEFAULTS) }
        uniqueNodes.each { northbound.deleteSwitchRules(it.switchId, DeleteRulesAction.IGNORE_DEFAULTS) }
        commonNodeIds.each { switchId ->
            assert northbound.validateSwitchRules(switchId).missingRules.size() > 0
        }
        uniqueNodes.each { assert northbound.validateSwitchRules(it.switchId).missingRules.size() == 2 }

        when: "Synchronize rules on switches"
        commonNodeIds.each {
            def response = northbound.synchronizeSwitchRules(it)
            assert response.missingRules.size() > 0
            assert response.installedRules.sort() == response.missingRules.sort()
            assert response.properRules.findAll { !new Cookie(it).serviceFlag }.empty
            assert response.excessRules.empty
        }
        uniqueNodes.each {
            def response = northbound.synchronizeSwitchRules(it.switchId)
            assert response.missingRules.size() == 2
            assert response.installedRules.sort() == response.missingRules.sort()
            assert response.properRules.findAll { !new Cookie(it).serviceFlag }.empty, it
            assert response.excessRules.empty
        }

        then: "No missing rules were found after rules synchronization"
        commonNodeIds.each { switchId ->
            verifyAll(northbound.validateSwitchRules(switchId)) {
                properRules.sort() == rulesOnCommonNodesBefore[switchId]
                missingRules.empty
                excessRules.empty
            }
        }
        uniqueNodes.each {
            verifyAll(northbound.validateSwitchRules(it.switchId)) {
                properRules.findAll { !new Cookie(it).serviceFlag }.size() == 2
                missingRules.empty
                excessRules.empty
            }
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags([SMOKE, SMOKE_SWITCHES])
    def "Traffic counters in ingress rule are reset on flow rerouting(singleTable mode)"() {
        given: "Two active neighboring switches and two possible flow paths at least"
        assumeTrue(!useMultitable, "This test is not ready to be run under multiTable mode")
        def allTraffgenSwitchIds = topology.activeTraffGens*.switchConnected*.dpId
        List<List<PathNode>> possibleFlowPaths = []
        def isl = topology.getIslsForActiveSwitches().find {
            possibleFlowPaths = database.getPaths(it.srcSwitch.dpId, it.dstSwitch.dpId)*.path.sort { it.size() }
            possibleFlowPaths.size() > 1 && allTraffgenSwitchIds.containsAll([it.srcSwitch.dpId, it.dstSwitch.dpId]) &&
                 it.srcSwitch.ofVersion != "OF_12" && it.dstSwitch.ofVersion != "OF_12"
        } ?: assumeTrue(false, "No suiting switches found")
        def (srcSwitch, dstSwitch) = [isl.srcSwitch, isl.dstSwitch]

        and: "Create a flow going through these switches"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)
        def flowCookiesMap = [srcSwitch, dstSwitch].collectEntries { [it, getFlowRules(it)*.cookie] }

        when: "Start traffic examination"
        def traffExam = traffExamProvider.get()
        def examVlanFlow = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(
                flowHelperV2.toV1(flow), 100, 2
        )
        withPool {
            [examVlanFlow.forward, examVlanFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        then: "Traffic counters in ingress/egress rules on source and destination switches represent packets movement"
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
    @Tags([SMOKE, SMOKE_SWITCHES])
    def "Traffic counters in ingress rule are reset on flow rerouting(multiTable mode)"() {
        given: "Two active neighboring switches and two possible flow paths at least"
        assumeTrue(useMultitable, "This test can be run in multiTable mode")
        def allTraffgenSwitchIds = topology.activeTraffGens*.switchConnected*.dpId
        List<List<PathNode>> possibleFlowPaths = []
        def isl = topology.getIslsForActiveSwitches().find {
            possibleFlowPaths = database.getPaths(it.srcSwitch.dpId, it.dstSwitch.dpId)*.path.sort { it.size() }
            possibleFlowPaths.size() > 1 && allTraffgenSwitchIds.containsAll([it.srcSwitch.dpId, it.dstSwitch.dpId]) &&
                 it.srcSwitch.ofVersion != "OF_12" && it.dstSwitch.ofVersion != "OF_12"
        } ?: assumeTrue(false, "No suiting switches found")
        def (srcSwitch, dstSwitch) = [isl.srcSwitch, isl.dstSwitch]

        and: "Create a flow going through these switches"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)
        def flowInfo = database.getFlow(flow.flowId)
        def flowRulesSrcSw = getFlowRules(srcSwitch)
        def flowRulesDstSw = getFlowRules(dstSwitch)
        def sharedRuleSrcSw = flowRulesSrcSw.find { new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW }.cookie
        def sharedRuleDstSw = flowRulesDstSw.find { new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW }.cookie

        def ingressSrcSw = flowInfo.forwardPath.cookie.value
        def egressSrcSw = flowInfo.reversePath.cookie.value

        def ingressDstSw = flowInfo.reversePath.cookie.value
        def egressDstSw = flowInfo.forwardPath.cookie.value

        when: "Start traffic examination"
        def traffExam = traffExamProvider.get()
        def examVlanFlow = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(
                flowHelperV2.toV1(flow), 100, 2
        )
        withPool {
            [examVlanFlow.forward, examVlanFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        then: "Traffic counters in shared/ingress/egress rule on source and destination switches represent packets movement"
        def rulesAfterPassingTrafficSrcSw = getFlowRules(srcSwitch)
        def rulesAfterPassingTrafficDstSw = getFlowRules(dstSwitch)
         //srcSw
         with(rulesAfterPassingTrafficSrcSw.find { it.cookie == sharedRuleSrcSw}) {
            !it.flags
            it.packetCount > 0
            it.byteCount > 0
         }
         with(rulesAfterPassingTrafficSrcSw.find { it.cookie == ingressSrcSw}) {
            it.flags.contains("RESET_COUNTS")
            it.packetCount > 0
            it.byteCount > 0
         }
         with(rulesAfterPassingTrafficSrcSw.find { it.cookie == egressSrcSw}) {
            !it.flags.contains("RESET_COUNTS")
            it.packetCount > 0
            it.byteCount > 0
         }
         //dstSw
         with(rulesAfterPassingTrafficDstSw.find { it.cookie == sharedRuleDstSw}) {
            !it.flags
            it.packetCount > 0
            it.byteCount > 0
         }
         with(rulesAfterPassingTrafficDstSw.find { it.cookie == ingressDstSw}) {
            it.flags.contains("RESET_COUNTS")
            it.packetCount > 0
            it.byteCount > 0
         }
         with(rulesAfterPassingTrafficDstSw.find { it.cookie == egressDstSw}) {
            !it.flags.contains("RESET_COUNTS")
            it.packetCount > 0
            it.byteCount > 0
         }

        when: "Break the flow ISL (bring switch port down) to cause flow rerouting"
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))
        // Switches may have parallel links, so we need to get involved ISLs.
        def islToFail = pathHelper.getInvolvedIsls(flowPath).first()
        def portDown = antiflap.portDown(islToFail.srcSwitch.dpId, islToFail.srcPort)

        then: "The flow was rerouted after reroute timeout"
        def flowInfoAfterReroute
        def rulesAfterRerouteSrcSw
        def rulesAfterRerouteDstSw
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert PathHelper.convert(northbound.getFlowPath(flow.flowId)) != flowPath
            flowInfoAfterReroute = database.getFlow(flow.flowId)
            rulesAfterRerouteSrcSw = getFlowRules(srcSwitch)
            rulesAfterRerouteDstSw = getFlowRules(dstSwitch)
            //system doesn't reinstall shared rule
            assert rulesAfterRerouteSrcSw.find { new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW }.cookie == sharedRuleSrcSw
            assert rulesAfterRerouteDstSw.find { new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW }.cookie == sharedRuleDstSw
            rulesAfterRerouteSrcSw.findAll { new Cookie(it.cookie).getType() != CookieType.SHARED_OF_FLOW }.cookie.each {
                assert !(it in [ingressSrcSw, egressSrcSw])
            }
            rulesAfterRerouteDstSw.findAll { new Cookie(it.cookie).getType() != CookieType.SHARED_OF_FLOW }.cookie.each {
                assert !(it in [ingressDstSw, egressDstSw])
            }
        }

        and: "Traffic counters in ingress rule on source and destination switches are reset"
        //srcSw
         with(rulesAfterRerouteSrcSw.find { it.cookie == sharedRuleSrcSw}) {
            !it.flags
            it.packetCount > 0
            it.byteCount > 0
         }
         with(rulesAfterRerouteSrcSw.find { it.cookie == flowInfoAfterReroute.forwardPath.cookie.value}) {
            it.flags.contains("RESET_COUNTS")
            it.packetCount == 0
            it.byteCount == 0
         }
         with(rulesAfterRerouteSrcSw.find { it.cookie == flowInfoAfterReroute.reversePath.cookie.value}) {
            !it.flags.contains("RESET_COUNTS")
            it.packetCount == 0
            it.byteCount == 0
         }
         //dstSw
         with(rulesAfterRerouteDstSw.find { it.cookie == sharedRuleDstSw}) {
            !it.flags
            it.packetCount > 0
            it.byteCount > 0
         }
         //ingress
         with(rulesAfterRerouteDstSw.find { it.cookie == flowInfoAfterReroute.reversePath.cookie.value}) {
            it.flags.contains("RESET_COUNTS")
            it.packetCount == 0
            it.byteCount == 0
         }
         //egress
         with(rulesAfterRerouteDstSw.find { it.cookie == flowInfoAfterReroute.forwardPath.cookie.value}) {
            !it.flags.contains("RESET_COUNTS")
            it.packetCount == 0
            it.byteCount == 0
         }

        cleanup: "Revive the ISL back (bring switch port up), delete the flow and reset costs"
        flowHelperV2.deleteFlow(flow.flowId)
        portDown && antiflap.portUp(islToFail.srcSwitch.dpId, islToFail.srcPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tidy
    @Tags([HARDWARE, LOW_PRIORITY, SMOKE_SWITCHES])//uses legacy 'rules validation', has a switchValidate analog in SwitchValidationSpec
    @Ignore("https://github.com/telstra/open-kilda/issues/3021")
    def "Able to synchronize rules for a flow with VXLAN encapsulation"() {
        given: "Two active not neighboring Noviflow switches"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { swP ->
            swP.src.noviflow && !swP.src.wb5164 && swP.dst.noviflow && !swP.dst.wb5164 && swP.paths.find { path ->
                pathHelper.getInvolvedSwitches(path).every { it.noviflow && !it.wb5164 }
            }
        } ?: assumeTrue(false, "Unable to find required switches in topology")

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
            def amountOfRules = northbound.getSwitchProperties(switchId).multiTable ? 3 : 2
            Wrappers.wait(RULES_DELETION_TIME) {
                assert northbound.validateSwitchRules(switchId).missingRules.size() == amountOfRules
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
                properRules.size().findAll { !new Cookie(it).serviceFlag }.size() == flowRulesCount
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

    void checkTrafficCountersInRules(FlowEndpointV2 flowEndpoint, isTrafficThroughRuleExpected) {
        def rules = northbound.getSwitchRules(flowEndpoint.switchId).flowEntries
        def ingressRule = filterRules(rules, flowEndpoint.portNumber, flowEndpoint.vlanId, null)[0]
        def egressRule = filterRules(rules, null, null, flowEndpoint.portNumber).find {
            it.instructions.applyActions.setFieldActions*.fieldValue.contains(flowEndpoint.vlanId.toString())
        }

        assert ingressRule.flags.contains("RESET_COUNTS")
        assert isTrafficThroughRuleExpected == (ingressRule.packetCount > 0)
        assert isTrafficThroughRuleExpected == (ingressRule.byteCount > 0)

        assert !egressRule.flags.contains("RESET_COUNTS")
        assert isTrafficThroughRuleExpected == (egressRule.packetCount > 0)
        assert isTrafficThroughRuleExpected == (egressRule.byteCount > 0)

    }

    List<FlowEntry> getFlowRules(Switch sw) {
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll { !(it.cookie in sw.defaultCookies) }.sort()
    }
}
