package org.openkilda.functionaltests.spec.multitable

import static groovyx.gpars.GParsPool.withPool
import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_SUCCESS
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.UPDATE_SUCCESS
import static org.openkilda.testing.Constants.EGRESS_RULE_MULTI_TABLE_ID
import static org.openkilda.testing.Constants.INGRESS_RULE_MULTI_TABLE_ID
import static org.openkilda.testing.Constants.SHARED_RULE_TABLE_ID
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.SINGLE_TABLE_ID
import static org.openkilda.testing.Constants.TRANSIT_RULE_MULTI_TABLE_ID
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.SwitchFeature
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.northbound.dto.v1.switches.SwitchPropertiesDto
import org.openkilda.northbound.dto.v1.switches.SwitchSyncResult
import org.openkilda.northbound.dto.v1.switches.SwitchValidationResult
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.See

import java.util.concurrent.TimeUnit
import javax.inject.Provider

@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/multi-table-pipelines")
class MultitableFlowsSpec extends HealthCheckSpecification {

    @Autowired
    Provider<TraffExamService> traffExamProvider

    @Tags([SMOKE, SMOKE_SWITCHES])
    def "System can use both single-table and multi-table switches in flow path at the same time, change switch table \
mode with existing flows and hold flows of different table-mode types"() {
        given: "A potential flow on a path of 4 switches: multi -> single -> multi -> single"
        List<PathNode> desiredPath = null
        List<Switch> involvedSwitches = null
        def allTraffgenSwitchIds = topology.activeTraffGens*.switchConnected.dpId ?:
                assumeTrue("Should be at least two active traffgens connected to switches", false)
        def swPair = topologyHelper.allNotNeighboringSwitchPairs.collectMany { [it, it.reversed] }.find { pair ->
            desiredPath = pair.paths.find { path ->
                involvedSwitches = pathHelper.getInvolvedSwitches(path)
                //4 switches total. First and third switches should allow multi-table
                involvedSwitches.size() == 4 && involvedSwitches[0].dpId in allTraffgenSwitchIds &&
                        involvedSwitches[-1].dpId in allTraffgenSwitchIds &&
                        involvedSwitches.every { it.features.contains(SwitchFeature.MULTI_TABLE) }
            }
        }
        assumeTrue("Unable to find a path that will allow 'multi -> single -> multi -> single' switch sequence",
                swPair.asBoolean())
        //make required path the most preferred
        swPair.paths.findAll { it != desiredPath }.each { pathHelper.makePathMorePreferable(desiredPath, it) }

        // get init switch props
        Map<SwitchId, SwitchPropertiesDto> initSwProps = involvedSwitches.collectEntries {
            [(it.dpId): northbound.getSwitchProperties(it.dpId)]
        }

        //Change switch properties so that path switches are multi -> single -> multi -> single -table
        [involvedSwitches[0], involvedSwitches[2]].each {
            northbound.updateSwitchProperties(it.dpId, changeSwitchPropsMultiTableValue(initSwProps[it.dpId], true))
        }
        [involvedSwitches[1], involvedSwitches[3]].each {
            northbound.updateSwitchProperties(it.dpId, changeSwitchPropsMultiTableValue(initSwProps[it.dpId], false))
        }
        checkDefaultRulesOnSwitches(involvedSwitches)

        when: "Create the prepared hybrid flow"
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        assert PathHelper.convert(northbound.getFlowPath(flow.flowId)) == desiredPath

        then: "Created flow is valid"
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }

        and: "Involved switches pass switch validation"
        involvedSwitches.each {
            with(northbound.validateSwitch(it.dpId)) { validation ->
                validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                validation.verifyHexRuleSectionsAreEmpty(["missingHex", "excessHex", "misconfiguredHex"])
                validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
            }
        }

        and: "Flow is pingable"
        verifyAll(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "The flow allows traffic"
        def traffExam = traffExamProvider.get()
        def examFlow1 = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flow), 1000, 8)
        withPool {
            [examFlow1.forward, examFlow1.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        when: "Update table mode for involved switches so that it becomes 'single -> multi -> single -> multi'"
        [involvedSwitches[0], involvedSwitches[2]].each {
            northbound.updateSwitchProperties(it.dpId, changeSwitchPropsMultiTableValue(initSwProps[it.dpId], false))
        }
        [involvedSwitches[1], involvedSwitches[3]].each {
            northbound.updateSwitchProperties(it.dpId, changeSwitchPropsMultiTableValue(initSwProps[it.dpId], true))
        }

        then: "Flow remains valid and pingable, switch validation passes"
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }
        involvedSwitches.each {
            with(northbound.validateSwitch(it.dpId)) { validation ->
                validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                validation.verifyHexRuleSectionsAreEmpty(["missingHex", "excessHex", "misconfiguredHex"])
                validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
            }
        }
        verifyAll(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        when: "Create one more similar flow on the target path"
        def flow2 = flowHelperV2.randomFlow(swPair).tap {
            it.source.portNumber = flow.source.portNumber
            it.source.vlanId = flow.source.vlanId - 1
            it.destination.portNumber = flow.destination.portNumber
            it.destination.vlanId = flow.destination.vlanId - 1
        }
        flowHelperV2.addFlow(flow2)

        then: "Both existing flows are valid"
        [flow, flow2].each {
            northbound.validateFlow(it.flowId).each { assert it.asExpected }
        }

        and: "Involved switches pass switch validation"
        involvedSwitches.each {
            with(northbound.validateSwitch(it.dpId)) { validation ->
                validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                validation.verifyHexRuleSectionsAreEmpty(["missingHex", "excessHex", "misconfiguredHex"])
                validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
            }
        }

        and: "Both flows are pingable"
        [flow, flow2].each {
            verifyAll(northbound.pingFlow(it.flowId, new PingInput())) {
                it.forward.pingSuccess
                it.reverse.pingSuccess
            }
        }

        and: "Both flows allow traffic"
        def examFlow2 = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flow2), 1000, 8)
        withPool {
            [examFlow1.forward, examFlow1.reverse, examFlow2.forward, examFlow2.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        when: "Delete flows"
        def flow1InfoFromDb = database.getFlow(flow.flowId)
        def flow2InfoFromDb = database.getFlow(flow2.flowId)
        def flowsCookies = [flow1InfoFromDb.forwardPath.cookie.value, flow1InfoFromDb.reversePath.cookie.value,
                            flow2InfoFromDb.forwardPath.cookie.value, flow2InfoFromDb.reversePath.cookie.value]
        [involvedSwitches[0], involvedSwitches[3]].each { sw ->
            northbound.getSwitchRules(sw.dpId).flowEntries.each { rule ->
                Cookie.isIngressRulePassThrough(rule.cookie) && flowsCookies << rule.cookie
            }
        }
        [flow, flow2].each { flowHelperV2.deleteFlow(it.flowId) }

        then: "Flow rules are deleted from switches"
        involvedSwitches.each { sw ->
            with(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                rules.findAll { it.cookie in flowsCookies }.empty
            }
        }

        and: "Cleanup: Revert system to original state"
        revertSwitchesToInitState(involvedSwitches, initSwProps)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
    }

    def "Single-switch flow rules are (re)installed according to switch property while rerouting,syncing,updating"() {
        given: "An active switch"
        def sw = topology.activeSwitches.find { it.features.contains(SwitchFeature.MULTI_TABLE) }
        SwitchPropertiesDto initSwProps = northbound.getSwitchProperties(sw.dpId)

        and: "Multi table mode is enabled on it"
        !initSwProps.multiTable && northbound.updateSwitchProperties(sw.dpId,
                changeSwitchPropsMultiTableValue(initSwProps, true))
        checkDefaultRulesOnSwitch(sw)

        when: "Create a single-switch flow"
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flowHelperV2.addFlow(flow)

        then: "Flow rules are created in multi table mode"
        def flowInfoFromDb
        def sharedRules
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            flowInfoFromDb = database.getFlow(flow.flowId)
            sharedRules = northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
                new Cookie(it.cookie).type == CookieType.SHARED_OF_FLOW
            }
            verifyAll(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.findAll { it.cookie in sharedRules*.cookie }*.tableId.unique() == [SHARED_RULE_TABLE_ID]
            }
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        when: "Update switch properties(multi_table: false) on the switch"
        def defaultMultiTableSwRules = northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
            new Cookie(it.cookie).serviceFlag
        }
        northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps, false))

        then: "Default switch rules are still in multi table mode"
        Wrappers.timedLoop(RULES_INSTALLATION_TIME / 3) {
            with(northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
                new Cookie(it.cookie).serviceFlag
            }) { rules ->
                rules.size() == defaultMultiTableSwRules.size()
                rules*.tableId.unique().sort() == defaultMultiTableSwRules*.tableId.unique().sort()
            }
        }

        and: "Flow rules are still in multi table mode"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            verifyAll(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.findAll { it.cookie in sharedRules*.cookie }*.tableId.unique() == [SHARED_RULE_TABLE_ID]
            }
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        when: "Synchronize the flow"
        with(northbound.synchronizeFlow(flow.flowId)) {
            !it.rerouted
        }

        then: "Rules on the switch are reinstalled in single table mode"
        def flowInfoFromDb2
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            flowInfoFromDb2 = database.getFlow(flow.flowId)
            verifyAll(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb2.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb2.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
                //shared rules should be deleted, , issue #3546
                rules.findAll { it.cookie in sharedRules*.cookie }*.tableId.unique() == [SHARED_RULE_TABLE_ID]
            }
        }

        when: "Update switch properties(multi_table: true) on the switch"
        northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps, true))

        then: "Flow rules are still in single table mode on the switch"
        verifyAll(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb2.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb2.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
            //shared rules should be deleted, issue #3546
            rules.findAll { it.cookie in sharedRules*.cookie }*.tableId.unique() == [SHARED_RULE_TABLE_ID]
        }

        and: "Flow is valid and UP"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        Wrappers.wait(WAIT_OFFSET / 2) { assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP }

        when: "Update the flow"
        flowHelperV2.updateFlow(flow.flowId, flow.tap { it.description = it.description + " updated" })

        then: "Flow rules on the switch are recreated in multi table mode"
        def flowInfoFromDb3
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            flowInfoFromDb3 = database.getFlow(flow.flowId)
            verifyAll(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb3.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb3.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.findAll { it.cookie in sharedRules*.cookie }*.tableId.unique() == [SHARED_RULE_TABLE_ID]
            }
        }

        when: "Update switch properties(multi_table: false) on the switch"
        northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps, false))

        and: "Reroute(intentional) the flow via APIv1"
        with(northbound.rerouteFlow(flow.flowId)) { !it.rerouted }
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        then: "Flow rules on the switch are not recreated in single table mode because flow wasn't rerouted"
        verifyAll(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb3.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb3.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.findAll { it.cookie in sharedRules*.cookie }*.tableId.unique() == [SHARED_RULE_TABLE_ID]
        }

        when: "Reroute(intentional) the flow via APIv2"
        with(northboundV2.rerouteFlow(flow.flowId)) { !it.rerouted }
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        then: "Flow rules on the switch are not recreated in single table mode because the flow wasn't rerouted"
        verifyAll(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb3.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb3.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Flow rules are deleted"
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
            def cookie = new Cookie(it.cookie)
            cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES || !cookie.serviceFlag
        }.empty

        and: "Cleanup: revert system to original state"
        revertSwitchToInitState(sw, initSwProps)
    }

    @Tags([SMOKE_SWITCHES])
    @Ignore("https://github.com/telstra/open-kilda/issues/3341")
    def "Flow rules are (re)installed according to switch property while syncing and updating"() {
        given: "Three active switches"
        List<PathNode> desiredPath = null
        List<Switch> involvedSwitches = null
        def switchPair = topologyHelper.allNotNeighboringSwitchPairs.collectMany { [it, it.reversed] }.find { pair ->
            def allPaths = pair.paths.findAll { path ->
                pathHelper.getInvolvedSwitches(path).every { it.features.contains(SwitchFeature.MULTI_TABLE) }
            }
            desiredPath = allPaths.find {
                involvedSwitches = pathHelper.getInvolvedSwitches(it)
                involvedSwitches.size() == 3
            }
            // make sure that alternative path for protected path is available
            allPaths.findAll { it.intersect(desiredPath) == [] }.size() > 0
        }
        assumeTrue("Unable to find a path with three switches", switchPair.asBoolean())
        //make required path the most preferred
        switchPair.paths.findAll { it != desiredPath }.each { pathHelper.makePathMorePreferable(desiredPath, it) }
        Map<SwitchId, SwitchPropertiesDto> initSwProps = involvedSwitches.collectEntries {
            [(it.dpId): northbound.getSwitchProperties(it.dpId)]
        }

        and: "Multi table is enabled for them"
        involvedSwitches.findAll { !initSwProps[it.dpId].multiTable }.each { sw ->
            northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps[sw.dpId], true))
        }
        checkDefaultRulesOnSwitches(involvedSwitches)

        when: "Create a protected flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelperV2.addFlow(flow)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        assert PathHelper.convert(flowPathInfo) == desiredPath

        then: "Flow rules are created in multi table mode"
        def flowInfoFromDb = database.getFlow(flow.flowId)
        flowHelper.verifyRulesOnProtectedFlow(flow.flowId)

        when: "Update switch properties(multi_table: false) on the transit switch"
        northbound.updateSwitchProperties(involvedSwitches[1].dpId,
                changeSwitchPropsMultiTableValue(initSwProps[involvedSwitches[1].dpId], false))

        then: "Flow rules are still in multi table mode on all switches"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
                rules.find {
                    it.cookie == flowInfoFromDb.protectedReversePath.cookie.value
                }.tableId == EGRESS_RULE_MULTI_TABLE_ID
            }
            verifyAll(northbound.getSwitchRules(involvedSwitches[1].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
            }
            verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.find {
                    it.cookie == flowInfoFromDb.protectedForwardPath.cookie.value
                }.tableId == EGRESS_RULE_MULTI_TABLE_ID
            }
        }

        when: "Synchronize the flow"
        with(northbound.synchronizeFlow(flow.flowId)) { !it.rerouted }
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        then: "Rules on the transit switch are recreated in single table mode"
        def flowInfoFromDb2
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            flowInfoFromDb2 = database.getFlow(flow.flowId)
            flowHelper.verifyRulesOnProtectedFlow(flow.flowId)
        }

        when: "Update switch properties(multi_table: false) on the src switch"
        northbound.updateSwitchProperties(involvedSwitches[0].dpId,
                changeSwitchPropsMultiTableValue(initSwProps[involvedSwitches[0].dpId], false))

        then: "Flow rules are still in multi table mode on the src and dst switches"
        verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb2.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb2.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
            rules.find {
                it.cookie == flowInfoFromDb2.protectedReversePath.cookie.value
            }.tableId == EGRESS_RULE_MULTI_TABLE_ID
        }
        verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb2.forwardPath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb2.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.find {
                it.cookie == flowInfoFromDb2.protectedForwardPath.cookie.value
            }.tableId == EGRESS_RULE_MULTI_TABLE_ID
        }

        and: "Flow rules are still in single table mode on the transit switch"
        verifyAll(northbound.getSwitchRules(involvedSwitches[1].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb2.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb2.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
        }

        when: "Update the flow"
        //TODO: unable to use v2 here due to https://github.com/telstra/open-kilda/issues/3341
        // flowHelperV2.updateFlow(flow.flowId, flowHelperV2.toRequest(northboundV2.getFlow(flow.flowId).tap {
        //      it.description = it.description + " updated"
        //  }))
        flowHelper.updateFlow(flow.flowId, northbound.getFlow(flow.flowId).tap {
            it.description = it.description + " updated"
            it.description = it.description + " updated"
        })

        then: "Flow rules on the src switch are recreated in single table mode"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            flowHelper.verifyRulesOnProtectedFlow(flow.flowId)
        }

        cleanup: "Restore init switch properties and delete the flow"
        flowHelper.deleteFlow(flow.flowId)
        revertSwitchesToInitState(involvedSwitches, initSwProps)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
    }

    @Ignore("https://github.com/telstra/open-kilda/issues/3341")
    def "Flow rules are (re)installed according to switch property while rerouting"() {
        given: "Three active switches, src and dst switches are connected to traffgen"
        List<PathNode> desiredPath = null
        List<Switch> involvedSwitches = null

        def allTraffgenSwitchIds = topology.activeTraffGens*.switchConnected.dpId ?:
                assumeTrue("Should be at least two active traffgens connected to switches",
                        allTraffgenSwitchIds.size() > 1)
        def switchPair = topologyHelper.allNotNeighboringSwitchPairs.collectMany { [it, it.reversed] }.find { pair ->
            def allPaths = pair.paths.findAll { path ->
                pathHelper.getInvolvedSwitches(path).every { it.features.contains(SwitchFeature.MULTI_TABLE) }
            }
            desiredPath = allPaths.find { path ->
                involvedSwitches = pathHelper.getInvolvedSwitches(path)
                //3 switches total. the first switch and the last one are connected to traffgen
                involvedSwitches.size() == 3 && involvedSwitches[0].dpId in allTraffgenSwitchIds &&
                        involvedSwitches[-1].dpId in allTraffgenSwitchIds
            }
            if (desiredPath) {
                allPaths.findAll { it.intersect(desiredPath) == [] }.size() > 0
            }
        }
        assumeTrue("Unable to find a switch pair with two diverse paths", switchPair.asBoolean())
        //make required path the most preferred
        switchPair.paths.findAll { it != desiredPath }.each { pathHelper.makePathMorePreferable(desiredPath, it) }
        Map<SwitchId, SwitchPropertiesDto> initSwProps = involvedSwitches.collectEntries {
            [(it.dpId): northbound.getSwitchProperties(it.dpId)]
        }

        and: "Multi table is disabled for them"
        involvedSwitches.findAll { initSwProps[it.dpId].multiTable }.each { sw ->
            northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps[sw.dpId], false))
        }
        checkDefaultRulesOnSwitches(involvedSwitches)

        when: "Create a protected flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelperV2.addFlow(flow)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        assert PathHelper.convert(flowPathInfo) == desiredPath

        then: "Flow rules are created in single table mode"
        def flowInfoFromDb = database.getFlow(flow.flowId)
        Wrappers.wait(RULES_INSTALLATION_TIME) { flowHelper.verifyRulesOnProtectedFlow(flow.flowId) }

        when: "Update switch properties(multi_table: true) on the transit switch"
        northbound.updateSwitchProperties(involvedSwitches[1].dpId,
                changeSwitchPropsMultiTableValue(initSwProps[involvedSwitches[1].dpId], true))

        then: "Flow rules are still in single table mode on the transit switch"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            verifyAll(northbound.getSwitchRules(involvedSwitches[1].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
            }
        }

        when: "Reroute(intentional) the flow via APIv1"
        with(northbound.rerouteFlow(flow.flowId)) { !it.rerouted }
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        then: "Flow rules are still in single table on the transit switch mode because the flow wasn't rerouted"
        verifyAll(northbound.getSwitchRules(involvedSwitches[1].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
        }

        when: "Update switch properties(multi_table: true) on the dst switch"
        northbound.updateSwitchProperties(involvedSwitches[2].dpId,
                changeSwitchPropsMultiTableValue(initSwProps[involvedSwitches[2].dpId], true))

        and: "Reroute(intentional) the flow via APIv2"
        with(northboundV2.rerouteFlow(flow.flowId)) { !it.rerouted }

        then: "Flow rules are still in single table mode on the dst switch because the flow wasn't rerouted"
        verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
        }

        and: "The flow allows traffic"
        def traffExam = traffExamProvider.get()
        def examFlow = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flow), 1000, 8)
        withPool {
            [examFlow.forward, examFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        when: "Make current path not preferable"
        pathHelper.makePathNotPreferable(desiredPath)

        and: "Init intentional reroute via APIv2"
        with(northboundV2.rerouteFlow(flow.flowId)) { it.rerouted }
        def newFlowPath
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            newFlowPath = northbound.getFlowPath(flow.flowId)
            assert PathHelper.convert(newFlowPath) != desiredPath
        }
        def isProtectedPathRerouted = (flowPathInfo.protectedPath != newFlowPath.protectedPath)

        then: "Flow rules are recreated in multi table mode on the dst switch(the flow was rerouted)"
        //flow rules are still in single table mode on the src switch
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(database.getFlow(flow.flowId)) { flowInfo ->
                verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
                    rules.find { it.cookie == flowInfo.protectedReversePath.cookie.value }.tableId == SINGLE_TABLE_ID
                }
                verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                    rules.find {
                        it.cookie == flowInfo.protectedForwardPath.cookie.value
                    }.tableId == (isProtectedPathRerouted ? EGRESS_RULE_MULTI_TABLE_ID : SINGLE_TABLE_ID)
                }
            }
        }

        and: "The flow allows traffic"
        withPool {
            [examFlow.forward, examFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        when: "Disable protected path on the flow"
        //https://github.com/telstra/open-kilda/issues/3341
        flowHelperV2.updateFlow(flow.flowId, flowHelperV2.toV2(northbound.getFlow(flow.flowId).tap { it.allocateProtectedPath = false }))

        and: "Update switch properties(multi_table: false) on the dst and (multi_table: true) on the src switches"
        northbound.updateSwitchProperties(involvedSwitches[0].dpId,
                changeSwitchPropsMultiTableValue(initSwProps[involvedSwitches[0].dpId], true))
        northbound.updateSwitchProperties(involvedSwitches[2].dpId,
                changeSwitchPropsMultiTableValue(initSwProps[involvedSwitches[2].dpId], false))
        sleep(2000) // TODO(andriidovhan) delete sleep when 3034 is fixed

        and: "Init auto reroute(Fail a flow ISL (bring switch port down))"
        def flowIsls = pathHelper.getInvolvedIsls(PathHelper.convert(northbound.getFlowPath(flow.flowId)))
        def islToBreak = flowIsls[0]
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert PathHelper.convert(northbound.getFlowPath(flow.flowId)) != PathHelper.convert(newFlowPath)
        }

        then: "Flow rules on the src and dst switches are recreated according to the new switch properties"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(database.getFlow(flow.flowId)) { flowInfo ->
                verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
                }
                verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
                }
            }
        }

        and: "The flow allows traffic"
        withPool {
            [examFlow.forward, examFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        cleanup: "Restore init switch properties and delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        revertSwitchesToInitState(involvedSwitches, initSwProps)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }

    @Ignore("https://github.com/telstra/open-kilda/issues/4043")
    def "Flow rules are not reinstalled according to switch property while swapping to protected path"() {
        given: "Three active switches with 3 diverse paths at least"
        List<PathNode> desiredPath = null
        List<Switch> involvedSwitches = null
        def switchPair = topologyHelper.allNotNeighboringSwitchPairs.collectMany { [it, it.reversed] }.find { pair ->
            def allPaths = pair.paths.findAll { path ->
                pathHelper.getInvolvedSwitches(path).every { it.features.contains(SwitchFeature.MULTI_TABLE) }
            }
            desiredPath = allPaths.find {
                involvedSwitches = pathHelper.getInvolvedSwitches(it)
                involvedSwitches.size() == 3
            }
            // make sure that alternative path for protected path is available
            allPaths.findAll { it.intersect(desiredPath) == [] }.size() > 1
        }
        assumeTrue("Unable to find a path with three switches", switchPair.asBoolean())
        //make required path the most preferred
        switchPair.paths.findAll { it != desiredPath }.each { pathHelper.makePathMorePreferable(desiredPath, it) }
        Map<SwitchId, SwitchPropertiesDto> initSwProps = involvedSwitches.collectEntries {
            [(it.dpId): northbound.getSwitchProperties(it.dpId)]
        }

        and: "Multi table is enabled for them"
        involvedSwitches.findAll { !initSwProps[it.dpId].multiTable }.each { sw ->
            northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps[sw.dpId], true))
        }
        checkDefaultRulesOnSwitches(involvedSwitches)

        when: "Create a flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        assert PathHelper.convert(flowPathInfo) == desiredPath

        and: "Update switch properties(multi_table: false) on the dst switch"
        northbound.updateSwitchProperties(involvedSwitches[2].dpId,
                changeSwitchPropsMultiTableValue(initSwProps[involvedSwitches[2].dpId], false))
        sleep(3000) // TODO(andriidovhan) delete sleep when 3034 is fixed

        and: "Enable protected path on the flow"
        northboundV2.updateFlow(flow.flowId, flow.tap { it.allocateProtectedPath = true })
        Wrappers.wait(WAIT_OFFSET, 1) {
            with(northboundV2.getFlow(flow.flowId).statusDetails) {
                mainPath == "Up"
                protectedPath == "Up"
            }
        }

        then: "Flow rules on the dst switch are recreated in the single table mode"
        Wrappers.wait(PATH_INSTALLATION_TIME) {
            flowHelper.verifyRulesOnProtectedFlow(flow.flowId)
        }

        when: "Update switch properties(multi_table: false) on the src switch"
        northbound.updateSwitchProperties(involvedSwitches[0].dpId,
                changeSwitchPropsMultiTableValue(initSwProps[involvedSwitches[0].dpId], false))
        sleep(3000) // TODO(andriidovhan) delete sleep when 3034 is fixed

        and: "Swap main and protected path"
        def currentProtectedPath = PathHelper.convert(northbound.getFlowPath(flow.flowId).protectedPath)
        northbound.swapFlowPath(flow.flowId)
        def newFlowPath
        Wrappers.wait(WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert northbound.getFlowHistory(flow.flowId).last().payload.last().action == UPDATE_SUCCESS
            newFlowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))
            assert newFlowPath == currentProtectedPath
        }

        then: "Flow rules are still in the same table mode"
        with(database.getFlow(flow.flowId)) { flowInfo ->
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
                    rules.find {
                        it.cookie == flowInfo.protectedReversePath.cookie.value
                    }.tableId == EGRESS_RULE_MULTI_TABLE_ID
                }
                verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
                    rules.find { it.cookie == flowInfo.protectedForwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                }
            }
        }

        when: "Init auto swap path(Fail a flow ISL (bring switch port down))"
        def flowIsls = pathHelper.getInvolvedIsls(newFlowPath)
        def islToBreak = flowIsls[0]
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getLink(islToBreak).state == IslChangeType.FAILED }
        def newFlowPath2
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP ||
                    northboundV2.getFlowStatus(flow.flowId).status == FlowState.DEGRADED
            newFlowPath2 = PathHelper.convert(northbound.getFlowPath(flow.flowId))
            assert newFlowPath2 == desiredPath
            //update + 1st swap + this swap = 3
            assert northbound.getFlowHistory(flow.flowId).count { it.payload.last().action == UPDATE_SUCCESS } == 3
        }

        then: "Flow rules are still in the same table mode as previously"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(database.getFlow(flow.flowId)) { flowInfo ->
                verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
                    rules.find {
                        it.cookie == flowInfo.protectedReversePath.cookie.value
                    }.tableId == EGRESS_RULE_MULTI_TABLE_ID
                }
                verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
                    rules.find { it.cookie == flowInfo.protectedForwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                }
            }
        }

        cleanup: "Restore init switch properties and delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        revertSwitchesToInitState(involvedSwitches, initSwProps)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }

    def "Single switch flow rules are not reinstalled according to switch props when the update procedure is failed"() {
        given: "An active switch"
        def sw = topology.activeSwitches.find { it.features.contains(SwitchFeature.MULTI_TABLE) }
        SwitchPropertiesDto initSwProps = northbound.getSwitchProperties(sw.dpId)

        and: "Multi table mode is enabled on it"
        !initSwProps.multiTable && northbound.updateSwitchProperties(sw.dpId,
                changeSwitchPropsMultiTableValue(initSwProps, true))
        checkDefaultRulesOnSwitch(sw)

        when: "Create a flow"
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flowHelperV2.addFlow(flow)

        def flowInfoFromDb
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            flowInfoFromDb = database.getFlow(flow.flowId)
            verifyAll(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            }
        }

        and: "Update switch properties(multi_table: false) on the switch"
        northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps, false))

        and: "Update the flow: enable protected path"
        northboundV2.updateFlow(flow.flowId, flowHelperV2.toRequest(northboundV2.getFlow(flow.flowId)
                                                                                .tap { it.allocateProtectedPath = true }))
        Wrappers.wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not update flow"
        errorDetails.errorDescription == "Couldn't setup protected path for one-switch flow"

        and: "Flow rules are still in multi table mode"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            verifyAll(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            }
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "Flow rules are deleted"
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
            def cookie = new Cookie(it.cookie)
            cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES || !cookie.serviceFlag
        }.empty

        and: "Cleanup: revert system to original state"
        revertSwitchToInitState(sw, initSwProps)
    }

    def "Flow rules are not recreated when pinned flow changes state to up/down"() {
        given: "Three active switches"
        List<PathNode> desiredPath = null
        List<Switch> involvedSwitches = null
        def switchPair = topologyHelper.allNotNeighboringSwitchPairs.collectMany { [it, it.reversed] }.find { pair ->
            desiredPath = pair.paths.find { path ->
                involvedSwitches = pathHelper.getInvolvedSwitches(path)
                involvedSwitches.size() == 3 &&
                        involvedSwitches.every { it.features.contains(SwitchFeature.MULTI_TABLE) }
            }
        }
        assumeTrue("Unable to find a path with three switches", switchPair.asBoolean())
        //make required path the most preferred
        switchPair.paths.findAll { it != desiredPath }.each { pathHelper.makePathMorePreferable(desiredPath, it) }
        Map<SwitchId, SwitchPropertiesDto> initSwProps = involvedSwitches.collectEntries {
            [(it.dpId): northbound.getSwitchProperties(it.dpId)]
        }

        and: "Multi table is disabled for them"
        involvedSwitches.findAll { initSwProps[it.dpId].multiTable }.each { sw ->
            northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps[sw.dpId], false))
        }
        checkDefaultRulesOnSwitches(involvedSwitches)

        when: "Create a pinned flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.pinned = true
        flowHelperV2.addFlow(flow)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        assert PathHelper.convert(flowPathInfo) == desiredPath
        def flowInfoFromDb = database.getFlow(flow.flowId)

        and: "Update switch properties(multi_table: true) on the transit switch"
        northbound.updateSwitchProperties(involvedSwitches[1].dpId,
                northbound.getSwitchProperties(involvedSwitches[1].dpId).tap { it.multiTable = true })

        and: "Fail a flow ISL (bring switch port down)"
        def flowIsls = pathHelper.getInvolvedIsls(desiredPath)
        def islToBreak = flowIsls[0]
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        //flow is pinned, so that's why the flow is not rerouted
        Wrappers.wait(WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == desiredPath
        }

        and: "Update switch properties(multi_table: true) on the dst switch"
        northbound.updateSwitchProperties(involvedSwitches[2].dpId,
                changeSwitchPropsMultiTableValue(initSwProps[involvedSwitches[2].dpId], true))

        and: "Restore the failed flow ISL (bring switch port up)"
        antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == desiredPath
        }

        then: "Flow rules are still in single table mode on the transit switch"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
            }
            verifyAll(northbound.getSwitchRules(involvedSwitches[1].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
            }
            verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
            }
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        //https://github.com/telstra/open-kilda/issues/3639
//        when: "Synchronize the flow"
//        with(northbound.synchronizeFlow(flow.flowId)) { it.rerouted }
//        Wrappers.wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }
//
//        then: "Flow rules are reinstalled according to switch props"
//        Wrappers.wait(RULES_INSTALLATION_TIME + WAIT_OFFSET) {
//            with(database.getFlow(flow.flowId)) { flowInfo ->
//                verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
//                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
//                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
//                }
//                verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
//                    rules.find { it.cookie == flowInfo.forwardPath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
//                    rules.find { it.cookie == flowInfo.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
//                }
//            }
//        }

        when: "Delete the flow"
        northboundV2.deleteFlow(flow.flowId)

        then: "Flow rules are deleted"
        Wrappers.wait(RULES_DELETION_TIME) {
            involvedSwitches.each { sw ->
                northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
                    def cookie = new Cookie(it.cookie)
                    cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES || !cookie.serviceFlag
                }.empty
            }
        }

        and: "Cleanup: revert system to original state"
        revertSwitchesToInitState(involvedSwitches, initSwProps)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }

    @Tags([SMOKE_SWITCHES])
    def "Flow rules are re(installed) according to switch props while syncing switch and rules"() {
        given: "Three active switches"
        List<PathNode> desiredPath = null
        List<Switch> involvedSwitches = null
        def switchPair = topologyHelper.allNotNeighboringSwitchPairs.collectMany { [it, it.reversed] }.find { pair ->
            desiredPath = pair.paths.find { path ->
                involvedSwitches = pathHelper.getInvolvedSwitches(path)
                involvedSwitches.size() == 3 &&
                        involvedSwitches.every { it.features.contains(SwitchFeature.MULTI_TABLE) }
            }
        }
        assumeTrue("Unable to find a path with three switches", switchPair.asBoolean())
        //make required path the most preferred
        switchPair.paths.findAll { it != desiredPath }.each { pathHelper.makePathMorePreferable(desiredPath, it) }
        Map<SwitchId, SwitchPropertiesDto> initSwProps = involvedSwitches.collectEntries {
            [(it.dpId): northbound.getSwitchProperties(it.dpId)]
        }

        and: "Multi table is enabled for them"
        involvedSwitches.findAll { !initSwProps[it.dpId].multiTable }.each { sw ->
            northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps[sw.dpId], true))
        }
        checkDefaultRulesOnSwitches(involvedSwitches)

        when: "Create a flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        assert PathHelper.convert(flowPathInfo) == desiredPath

        and: "Update switch properties(multi_table: false) on the transit and dst switch"
        involvedSwitches[-2..-1].each { sw ->
            northbound.updateSwitchProperties(sw.dpId,
                    changeSwitchPropsMultiTableValue(initSwProps[sw.dpId], false))
        }

        then: "Flow rules are still in multi table on all involved switches"
        def flowInfoFromDb
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            flowInfoFromDb = database.getFlow(flow.flowId)
            verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
            }
            verifyAll(northbound.getSwitchRules(involvedSwitches[1].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
            }
            verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
                rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
                rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            }
        }

        when: "Synchronize all involved switches"
        involvedSwitches.each { sw ->
            verifyAll(northbound.synchronizeSwitch(sw.dpId, true)) {
                it.rules.misconfigured.empty
                it.rules.installed.empty
                it.rules.missing.empty
                it.rules.removed.empty
                it.rules.excess.empty

                it.meters.misconfigured.empty
                it.meters.installed.empty
                it.meters.missing.empty
                it.meters.removed.empty
                it.meters.excess.empty
            }
        }

        then: "Flow rules are still in multi table on all involved switches"
        verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
        }
        verifyAll(northbound.getSwitchRules(involvedSwitches[1].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
        }
        verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
        }

        when: "Synchronize switch rules on all involved switches switches"
        involvedSwitches.each { sw ->
            verifyAll(northbound.synchronizeSwitchRules(sw.dpId)) {
                it.missingRules.empty
                it.excessRules.empty
                it.installedRules.empty
            }
        }

        then: "Flow rules are still in multi table on all involved switches"
        verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
        }
        verifyAll(northbound.getSwitchRules(involvedSwitches[1].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
        }
        verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
        }

        when: "Delete the forward flow rule on the transit switches"
        northbound.deleteSwitchRules(involvedSwitches[1].dpId, flowInfoFromDb.forwardPath.cookie.value)

        then: "Flow is not valid in forward direction"
        northbound.validateFlow(flow.flowId).findAll { it.discrepancies.empty && it.asExpected }.size() == 1

        when: "Delete the flow rules on the dst switch"
        def sharedRuleOnDstSwitch = northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries.find {
                new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW
        }
        [flowInfoFromDb.forwardPath.cookie.value, flowInfoFromDb.reversePath.cookie.value, sharedRuleOnDstSwitch.cookie].each {
            northbound.deleteSwitchRules(involvedSwitches[2].dpId, it)
        }

        then: "Flow is not valid in both directions"
        northbound.validateFlow(flow.flowId).each { direction -> assert !direction.asExpected }

        and: "Rule info is moved into the 'missing' section on the transit and dst switches"
        verifyAll(northbound.validateSwitch(involvedSwitches[2].dpId)) { validateInfo ->
            validateInfo.rules.excess.empty
            validateInfo.rules.misconfigured.empty
            validateInfo.rules.missing.sort() ==
                    [flowInfoFromDb.forwardPath.cookie.value, flowInfoFromDb.reversePath.cookie.value, sharedRuleOnDstSwitch.cookie].sort()
            validateInfo.meters.excess.empty
            validateInfo.meters.missing.empty
            validateInfo.meters.misconfigured.empty
        }

        verifyAll(northbound.validateSwitchRules(involvedSwitches[1].dpId)) { validateInfo ->
            validateInfo.excessRules.empty
            validateInfo.missingRules == [flowInfoFromDb.forwardPath.cookie.value]
        }

        when: "Synchronize the dst switch"
        verifyAll(northbound.synchronizeSwitch(involvedSwitches[2].dpId, true)) { syncInfo ->
            syncInfo.rules.misconfigured.empty
            syncInfo.rules.excess.empty
            syncInfo.rules.removed.empty
            [syncInfo.rules.missing, syncInfo.rules.installed].each {
                it.sort() == [flowInfoFromDb.forwardPath.cookie.value, flowInfoFromDb.reversePath.cookie.value].sort()
            }
            syncInfo.meters.excess.empty
            syncInfo.meters.misconfigured.empty
            syncInfo.meters.missing.empty
            syncInfo.meters.removed.empty
            syncInfo.meters.installed.empty
        }

        and: "Synchronize switch rules on the transit switch"
        verifyAll(northbound.synchronizeSwitchRules(involvedSwitches[1].dpId)) { syncInfo ->
            syncInfo.excessRules.empty
            [syncInfo.missingRules, syncInfo.installedRules].each {
                it == [flowInfoFromDb.forwardPath.cookie.value]
            }
        }

        then: "Flow rules are still in multi table mode"
        verifyAll(northbound.getSwitchRules(involvedSwitches[0].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
        }
        verifyAll(northbound.getSwitchRules(involvedSwitches[1].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == TRANSIT_RULE_MULTI_TABLE_ID
        }
        verifyAll(northbound.getSwitchRules(involvedSwitches[2].dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == sharedRuleOnDstSwitch.cookie }.tableId == SHARED_RULE_TABLE_ID
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "All involved switches pass switch validation"
        involvedSwitches.each {
            with(northbound.validateSwitch(it.dpId)) { validation ->
                validation.verifyRuleSectionsAreEmpty(["missing", "excess"])
                validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
            }
        }

        cleanup: "Revert system to origin state"
        flowHelper.deleteFlow(flow.flowId)
        revertSwitchesToInitState(involvedSwitches, initSwProps)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
    }

    def "System detects excess rules after removing multi table flow from a switch with single table mode"() {
        given: "Three active switches"
        List<PathNode> desiredPath = null
        List<Switch> involvedSwitches = null
        def switchPair = topologyHelper.allNotNeighboringSwitchPairs.collectMany { [it, it.reversed] }.find { pair ->
            desiredPath = pair.paths.find { path ->
                involvedSwitches = pathHelper.getInvolvedSwitches(path)
                involvedSwitches.size() == 3 &&
                        involvedSwitches.every { it.features.contains(SwitchFeature.MULTI_TABLE) }
            }
        }
        assumeTrue("Unable to find a path with three switches", switchPair.asBoolean())
        //make required path the most preferred
        switchPair.paths.findAll { it != desiredPath }.each { pathHelper.makePathMorePreferable(desiredPath, it) }
        Map<SwitchId, SwitchPropertiesDto> initSwProps = involvedSwitches.collectEntries {
            [(it.dpId): northbound.getSwitchProperties(it.dpId)]
        }

        and: "Multi table is enabled for them"
        involvedSwitches.findAll { !initSwProps[it.dpId].multiTable }.each { sw ->
            northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps[sw.dpId], true))
        }
        checkDefaultRulesOnSwitches(involvedSwitches)

        and: "A flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        assert PathHelper.convert(flowPathInfo) == desiredPath

        when: "Update switch props(multi_table: false) on the src and transit switches"
        involvedSwitches[0..1].each { sw ->
            northbound.updateSwitchProperties(sw.dpId,
                    changeSwitchPropsMultiTableValue(initSwProps[sw.dpId], false))
        }

        and: "Delete the flow"
        flowHelper.deleteFlow(flow.flowId)
        def isFlowDeleted = true

        then: "System detects  excess rules on the src and transit switches"
        Map<SwitchId, SwitchValidationResult> validationResultsMap = involvedSwitches.collectEntries {
            [it.dpId, northbound.validateSwitch(it.dpId)]
        }
        involvedSwitches[0..1].each {
            assert validationResultsMap[it.dpId].rules.missing.empty
            assert validationResultsMap[it.dpId].rules.misconfigured.empty
            assert !validationResultsMap[it.dpId].rules.excess.empty
        }
        with(northbound.validateSwitch(involvedSwitches[2].dpId)) { validation ->
            validationResultsMap[involvedSwitches[2].dpId].rules.missing.empty
            validationResultsMap[involvedSwitches[2].dpId].rules.misconfigured.empty
            validationResultsMap[involvedSwitches[2].dpId].rules.excess.empty
        }

        when: "Synchronize src and transit switches(removeExcess: true)"
        Map<SwitchId, SwitchSyncResult> syncResultsMap = involvedSwitches.collectEntries {
            [it.dpId, northbound.synchronizeSwitch(it.dpId, true)]
        }

        then: "Excess rules are removed on the src and dst switches"
        involvedSwitches.each {
            assert syncResultsMap[it.dpId].rules.missing.empty
            assert syncResultsMap[it.dpId].rules.misconfigured.empty
            assert syncResultsMap[it.dpId].rules.installed.empty
            assert syncResultsMap[it.dpId].rules.excess.containsAll(validationResultsMap[it.dpId].rules.excess)
            assert syncResultsMap[it.dpId].rules.removed.containsAll(validationResultsMap[it.dpId].rules.excess)
        }

        and: "Involved switches pass switch validation"
        involvedSwitches.each {
            with(northbound.validateSwitch(it.dpId)) { validation ->
                validation.verifyRuleSectionsAreEmpty()
                validation.verifyMeterSectionsAreEmpty()
            }
        }

        cleanup: "Revert the system to origin state"
        !isFlowDeleted && flowHelper.deleteFlow(flow.flowId)
        revertSwitchesToInitState(involvedSwitches, initSwProps)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
    }

    @Tags(TOPOLOGY_DEPENDENT)
    def "System does not allow to enable the multiTable mode on an unsupported switch"() {
        given: "Unsupported switch"
        def sw = topology.activeSwitches.find { !it.features.contains(SwitchFeature.MULTI_TABLE) }
        assumeTrue("Unable to find required switch", sw as boolean)

        when: "Try to enable the multiTable mode on the switch"
        northbound.updateSwitchProperties(sw.dpId, northbound.getSwitchProperties(sw.dpId).tap {
            it.multiTable = true
        })

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage == "Failed to update switch properties."
        exc.responseBodyAsString.to(MessageError).errorDescription ==
                "Switch $sw.dpId doesn't support requested feature MULTI_TABLE"
    }

    @Tags(TOPOLOGY_DEPENDENT)
    @Ignore("wait until knockout switch is fixed for staging")
    def "System connects a new switch with disabled multiTable mode when the switch does not support that mode"() {
        given: "Unsupported switch"
        def sw = topology.activeSwitches.find { !it.features.contains(SwitchFeature.MULTI_TABLE) }
        assumeTrue("Unable to find required switch", sw as boolean)

        and: "Multi table is enabled in the kilda configuration"
        def initConf = northbound.getKildaConfiguration()
        !initConf.useMultiTable && northbound.updateKildaConfiguration(northbound.getKildaConfiguration().tap {
            it.useMultiTable = true
        })
        def isls = topology.getRelatedIsls(sw)
        assert !northbound.getSwitchProperties(sw.dpId).multiTable

        when: "Disconnect the switch and remove it from DB. Pretend this switch never existed"
        def blockData = switchHelper.knockoutSwitch(sw, RW, true)
        isls.each { islUtils.changePortDiscovery(it, false) }
        TimeUnit.SECONDS.sleep(discoveryInterval)
        isls.each { northbound.deleteLink(islUtils.toLinkParameters(it)) }
        northbound.deleteSwitch(sw.dpId, false)

        and: "New switch connects"
        switchHelper.reviveSwitch(sw, blockData, false)
        isls.each { islUtils.changePortDiscovery(it, true) }

        then: "Switch is added with disabled multiTable mode"
        !northbound.getSwitchProperties(sw.dpId).multiTable
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.sort() == sw.defaultCookies.sort()
                rules.findAll { it.instructions.goToTable }.empty
                rules.findAll { it.tableId }.empty
            }
        }

        and: "Cleanup: Revert system to origin state"
        !initConf.useMultiTable && northbound.updateKildaConfiguration(initConf)
        isls && Wrappers.wait(WAIT_OFFSET) {
            def allIsls = northbound.getAllLinks()
            isls.each {
                assert islUtils.getIslInfo(allIsls, it).get().state == IslChangeType.DISCOVERED
                assert islUtils.getIslInfo(allIsls, it.reversed).get().state == IslChangeType.DISCOVERED
            }
        }
    }

    @Ignore("https://github.com/telstra/open-kilda/issues/3341")
    def "System can manipulate protected flow, where paths are in different table modes"() {
        given: "Switches with 3 diverse paths"
        List<PathNode> desiredPath = null
        List<Switch> mainPathSwitches = null
        def switchPair = topologyHelper.switchPairs.find { pair ->
            def allPaths = pair.paths.findAll { path ->
                pathHelper.getInvolvedSwitches(path).every { it.features.contains(SwitchFeature.MULTI_TABLE) }
            }
            desiredPath = allPaths.find { thePath ->
                mainPathSwitches = pathHelper.getInvolvedSwitches(thePath)
                mainPathSwitches.size() == 3 && allPaths.findAll { it.intersect(thePath) == [] }.size() > 2
            }
        }
        assumeTrue("Unable to find a switch pair with two diverse paths", switchPair.asBoolean())
        //make required path the most preferred
        switchPair.paths.findAll { it != desiredPath }.each { pathHelper.makePathMorePreferable(desiredPath, it) }
        Map<SwitchId, SwitchPropertiesDto> initSwProps = mainPathSwitches.collectEntries {
            [(it.dpId): northbound.getSwitchProperties(it.dpId)]
        }

        and: "Multi table is disabled for them"
        mainPathSwitches.findAll { initSwProps[it.dpId].multiTable }.each { sw ->
            northbound.updateSwitchProperties(sw.dpId, changeSwitchPropsMultiTableValue(initSwProps[sw.dpId], false))
        }

        when: "Create a protected flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelperV2.addFlow(flow)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        assert PathHelper.convert(flowPathInfo) == desiredPath

        and: "Change table mode on src switch to multitable"
        northbound.updateSwitchProperties(switchPair.src.dpId,
                changeSwitchPropsMultiTableValue(initSwProps[switchPair.src.dpId], true))

        and: "Reroute main path to another path, but leave protected path the same"
        def targetPath = switchPair.paths.find {
            it.intersect(desiredPath) == [] && it != PathHelper.convert(flowPathInfo.protectedPath) &&
                    pathHelper.getInvolvedSwitches(it).size() > 2
        }
        //main and protected paths should have the same cost so that only one reroute
        def pathsWithCosts = [PathHelper.convert(flowPathInfo), PathHelper.convert(flowPathInfo.protectedPath)]
                .collectEntries {
                    [(it): pathHelper.getInvolvedIsls(it).sum { northbound.getLink(it).cost }]
                }
        def diff = Math.abs(pathsWithCosts.entrySet()[0].value - pathsWithCosts.entrySet()[1].value)
        pathsWithCosts.min { it.value }.with {
            def isl = pathHelper.getInvolvedIsls(it.key)[0]
            northbound.updateLinkProps([islUtils.toLinkProps(isl, [cost: (northbound.getLink(isl).cost + diff).toString()])])
        }
        switchPair.paths.findAll { it != targetPath }
                  .each { pathHelper.makePathMorePreferable(targetPath, it) }
        assert northboundV2.rerouteFlow(flow.flowId).rerouted

        then: "Reroute is done successfully"
        Wrappers.wait(RULES_INSTALLATION_TIME * 2) {
            def reroutes = northbound.getFlowHistory(flow.flowId).findAll { it.action == REROUTE_ACTION }
            assert reroutes.size() == 1
            assert reroutes.last().payload.last().action == REROUTE_SUCCESS
            northbound.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        and: "Flow rules for main path are in multitable mode, protected in single table mode on src"
        def flowInfoFromDb = database.getFlow(flow.flowId)
        verifyAll(northbound.getSwitchRules(switchPair.src.dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == INGRESS_RULE_MULTI_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
            //note that protected path remains in single table mode since it was not rerouted
            rules.find { it.cookie == flowInfoFromDb.protectedReversePath.cookie.value }.tableId == SINGLE_TABLE_ID
        }

        and: "No involved switches have rule discrepencies"
        def path = northbound.getFlowPath(flow.flowId)
        def allInvolvedSwitches = (pathHelper.getInvolvedSwitches(pathHelper.convert(path)) +
                pathHelper.getInvolvedSwitches(pathHelper.convert(path.protectedPath))).unique()
        allInvolvedSwitches.each {
            def validation = northbound.validateSwitch(it.dpId)
            validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
            validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }

        when: "Swap flow paths"
        northbound.swapFlowPath(flow.flowId)
        Wrappers.wait(WAIT_OFFSET) { northbound.getFlowStatus(flow.flowId).status == FlowState.UP }
        flowInfoFromDb = database.getFlow(flow.flowId)

        then: "Flow rules for main path are in singletable mode, protected in multitable mode on src"
        verifyAll(northbound.getSwitchRules(switchPair.src.dpId).flowEntries) { rules ->
            rules.find { it.cookie == flowInfoFromDb.forwardPath.cookie.value }.tableId == SINGLE_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.reversePath.cookie.value }.tableId == SINGLE_TABLE_ID
            rules.find { it.cookie == flowInfoFromDb.protectedReversePath.cookie.value }.tableId == EGRESS_RULE_MULTI_TABLE_ID
        }

        and: "No involved switches have rule discrepencies"
        allInvolvedSwitches.each {
            def validation = northbound.validateSwitch(it.dpId)
            validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
            validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }

        when: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "No involved switches have rule discrepencies"
        allInvolvedSwitches.each {
            def validation = northbound.validateSwitch(it.dpId)
            validation.verifyRuleSectionsAreEmpty()
            validation.verifyMeterSectionsAreEmpty()
        }

        cleanup:
        revertSwitchesToInitState(mainPathSwitches, initSwProps)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
    }

    void checkDefaultRulesOnSwitches(List<Switch> switches) {
        withPool {
            switches.eachParallel { Switch sw ->
                checkDefaultRulesOnSwitch(sw)
            }
        }
    }

    void checkDefaultRulesOnSwitch(Switch sw) {
        // sometimes it takes too much time on jenkins(up to 17 seconds)
        Wrappers.wait(RULES_INSTALLATION_TIME + WAIT_OFFSET, 1) {
            assert northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.sort() == sw.defaultCookies.sort()
        }
    }

    void revertSwitchesToInitState(List<Switch> switches, Map<SwitchId, SwitchPropertiesDto> initSwProps) {
        withPool {
            switches.eachParallel { Switch sw ->
                revertSwitchToInitState(sw, initSwProps[sw.dpId])
            }
        }
    }

    void revertSwitchToInitState(Switch sw, SwitchPropertiesDto initSwProps) {
        //system leaves excess rules after removing multi table flow from a switch with single table mode
        northbound.synchronizeSwitch(sw.dpId, true)
        checkDefaultRulesOnSwitch(sw) // should wait when rules are installed/removed to avoid race condition
        northbound.updateSwitchProperties(sw.dpId, initSwProps)
        checkDefaultRulesOnSwitch(sw)
    }

    def changeSwitchPropsMultiTableValue(SwitchPropertiesDto swProps, boolean newValue) {
        // Deep copy of object
        def mapper = new ObjectMapper()
        return mapper.readValue(mapper.writeValueAsString(swProps), SwitchPropertiesDto).tap {
            it.multiTable = newValue
        }
    }
}
