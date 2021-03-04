package org.openkilda.functionaltests.spec.flows

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.rule.FlowEntry
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import groovy.time.TimeCategory
import spock.lang.Shared

class FlowSyncV2Spec extends HealthCheckSpecification {

    @Shared
    int flowRulesCount = 2

    @Tidy
    @Tags([SMOKE_SWITCHES, SMOKE])
    def "Able to synchronize a flow (install missing flow rules, reinstall existing) without rerouting"() {
        given: "An intermediate-switch flow with deleted rules on src switch"
        def switchPair = topologyHelper.getNotNeighboringSwitchPair()
        assumeTrue(switchPair.asBoolean(), "Need a not-neighbouring switch pair for this test")

        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)
        List<Long> rulesToDelete = getFlowRules(switchPair.src)*.cookie
        rulesToDelete.each { northbound.deleteSwitchRules(switchPair.src.dpId, it) }
        Wrappers.wait(RULES_DELETION_TIME) {
            assert getFlowRules(switchPair.src).size() == flowRulesCount - rulesToDelete.size()
        }

        when: "Synchronize the flow"
        def syncTime = new Date()
        def rerouteResponse = northbound.synchronizeFlow(flow.flowId)
        Wrappers.wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }

        then: "The flow is not rerouted"
        int seqId = 0

        !rerouteResponse.rerouted
        rerouteResponse.path.path == flowPath
        rerouteResponse.path.path.each { assert it.seqId == seqId++ }

        PathHelper.convert(northbound.getFlowPath(flow.flowId)) == flowPath

        and: "Missing flow rules are installed (existing ones are reinstalled) on all switches"
        involvedSwitches.each { sw ->
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                def flowRules = getFlowRules(sw)
                assert flowRules.size() == flowRulesCount
                flowRules.each {
                    assert it.durationSeconds < TimeCategory.minus(new Date(), syncTime).toMilliseconds() / 1000.0
                }
            }
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Able to synchronize a flow (install missing flow rules, reinstall existing) with rerouting"() {
        given: "An intermediate-switch flow with two possible paths at least and deleted rules on src switch"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue("No suiting switches found to build an intermediate-switch flow " +
                        "with two possible paths at least.", false)

        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        def involvedSwitches = pathHelper.getInvolvedSwitches(flow.flowId)
        List<Long> rulesToDelete = getFlowRules(switchPair.src)*.cookie
        rulesToDelete.each { northbound.deleteSwitchRules(switchPair.src.dpId, it) }
        Wrappers.wait(RULES_DELETION_TIME) {
            assert getFlowRules(switchPair.src).size() == flowRulesCount - rulesToDelete.size()
        }

        and: "Make one of the alternative flow paths more preferable than the current one"
        switchPair.paths.findAll { it != flowPath }.each { pathHelper.makePathMorePreferable(it, flowPath) }

        when: "Synchronize the flow"
        def syncTime = new Date()
        def rerouteResponse = northbound.synchronizeFlow(flow.flowId)
        Wrappers.wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }

        then: "The flow is rerouted"
        def newFlowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))
        int seqId = 0

        rerouteResponse.rerouted
        rerouteResponse.path.path == newFlowPath
        rerouteResponse.path.path.each { assert it.seqId == seqId++ }

        newFlowPath != flowPath

        and: "Flow rules are installed/reinstalled on switches remained from the original flow path"
        def involvedSwitchesAfterSync = pathHelper.getInvolvedSwitches(flow.flowId)
        involvedSwitchesAfterSync.findAll { it in involvedSwitches }.each { sw ->
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                def flowRules = getFlowRules(sw)
                assert flowRules.size() == flowRulesCount
                flowRules.each {
                    assert it.durationSeconds < TimeCategory.minus(new Date(), syncTime).toMilliseconds() / 1000.0
                }
            }
        }

        and: "Flow rules are installed on new switches involved in the current flow path"
        involvedSwitchesAfterSync.findAll { !(it in involvedSwitches) }.each { sw ->
            Wrappers.wait(RULES_INSTALLATION_TIME) { assert getFlowRules(sw).size() == flowRulesCount }
        }

        and: "Flow rules are deleted from switches that are NOT involved in the current flow path"
        involvedSwitches.findAll { !(it in involvedSwitchesAfterSync) }.each { sw ->
            Wrappers.wait(RULES_DELETION_TIME) { assert getFlowRules(sw).empty }
        } || true  // switches after sync may include all switches involved in the flow before sync

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        cleanup: "Delete the flow and link props, reset link costs"
        flowHelperV2.deleteFlow(flow.flowId)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }

    List<FlowEntry> getFlowRules(Switch sw) {
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll { def cookie = new Cookie(it.cookie)
            cookie.type == CookieType.SERVICE_OR_FLOW_SEGMENT && !cookie.serviceFlag
        }.sort()
    }
}
