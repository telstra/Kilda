package org.openkilda.functionaltests.spec.switches

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOCKKEEPER
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_FAIL
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_SUCCESS
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.lockkeeper.model.TrafficControlData

import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.Narrative

@Narrative("""
This spec verifies different situations when Kilda switches suddenly disconnect from the controller.
Note: For now it is only runnable on virtual env due to no ability to disconnect hardware switches
""")
class SwitchFailuresSpec extends HealthCheckSpecification {

    @Tags([SMOKE, SMOKE_SWITCHES, LOCKKEEPER])
    def "ISL is still able to properly fail even if switches have reconnected"() {
        given: "A flow"
        def isl = topology.getIslsForActiveSwitches().find { it.aswitch && it.dstSwitch }
        assumeTrue(isl.asBoolean(), "No a-switch ISL found for the test")
        def flow = flowHelperV2.randomFlow(isl.srcSwitch, isl.dstSwitch)
        flowHelperV2.addFlow(flow)

        when: "Two neighbouring switches of the flow go down simultaneously"
        def srcBlockData = lockKeeper.knockoutSwitch(isl.srcSwitch, RW)
        def dstBlockData = lockKeeper.knockoutSwitch(isl.dstSwitch, RW)
        def timeSwitchesBroke = System.currentTimeMillis()
        def untilIslShouldFail = { timeSwitchesBroke + discoveryTimeout * 1000 - System.currentTimeMillis() }

        and: "ISL between those switches looses connection"
        lockKeeper.removeFlows([isl.aswitch])

        and: "Switches go back up"
        lockKeeper.reviveSwitch(isl.srcSwitch, srcBlockData)
        lockKeeper.reviveSwitch(isl.dstSwitch, dstBlockData)

        then: "ISL still remains up right before discovery timeout should end"
        sleep(untilIslShouldFail() - 2000)
        islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED

        and: "ISL fails after discovery timeout"
        //TODO(rtretiak): Using big timeout here. This is an abnormal behavior
        Wrappers.wait(untilIslShouldFail() / 1000 + WAIT_OFFSET * 1.5) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
        }

        //depends whether there are alt paths available
        and: "The flow goes down OR changes path to avoid failed ISL after reroute timeout"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            def currentIsls = pathHelper.getInvolvedIsls(PathHelper.convert(northbound.getFlowPath(flow.flowId)))
            def pathChanged = !currentIsls.contains(isl) && !currentIsls.contains(isl.reversed)
            assert pathChanged || (northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN &&
                    northbound.getFlowHistory(flow.flowId).find {
                        it.action == REROUTE_ACTION && it.taskId =~ (/.+ : retry #1 ignore_bw true/)
                    }?.payload?.last()?.action == REROUTE_FAIL)
        }

        and: "Cleanup: restore connection, remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        lockKeeper.addFlows([isl.aswitch])
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
    }

    @Tidy
    @Ignore("https://github.com/telstra/open-kilda/issues/3398")
    def "System is able to finish the reroute if switch blinks in the middle of it"() {
        given: "A flow"
        def swPair = topologyHelper.allNotNeighboringSwitchPairs.find { it.paths.size() > 1 }
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)

        when: "Current path breaks and reroute starts"
        lockKeeper.shapeSwitchesTraffic([swPair.dst], new TrafficControlData(3000))
        def islToBreak = pathHelper.getInvolvedIsls(northbound.getFlowPath(flow.flowId)).first()
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)

        and: "Switch reconnects in the middle of reroute"
        Wrappers.wait(WAIT_OFFSET, 0) {
            def reroute = northbound.getFlowHistory(flow.flowId).find { it.action == REROUTE_ACTION }
            assert reroute.payload.last().action == "Started validation of installed non ingress rules"
        }
        lockKeeper.reviveSwitch(swPair.src, lockKeeper.knockoutSwitch(swPair.src, RW))

        then: "Flow reroute is successful"
        Wrappers.wait(PATH_INSTALLATION_TIME * 2) { //double timeout since rerouted is slowed by delay
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert northbound.getFlowHistory(flow.flowId).last().payload.last().action == REROUTE_SUCCESS
        }

        and: "Blinking switch has no rule anomalies"
        def validation = northbound.validateSwitch(swPair.src.dpId)
        validation.verifyRuleSectionsAreEmpty(["missing", "misconfigured", "excess"])
        validation.verifyMeterSectionsAreEmpty(["missing", "misconfigured", "excess"])

        and: "Flow validation is OK"
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }

        cleanup:
        lockKeeper.cleanupTrafficShaperRules(swPair.dst.regions)
        flowHelperV2.deleteFlow(flow.flowId)
        antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        database.resetCosts()
    }

    def "System can handle situation when switch reconnects while flow is being created"() {
        when: "Start creating a flow between switches and lose connection to src before rules are set"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        northboundV2.addFlow(flow)
        sleep(50)
        def blockData = lockKeeper.knockoutSwitch(srcSwitch, RW)

        then: "Flow eventually goes DOWN"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getSwitch(srcSwitch.dpId).state == SwitchChangeType.DEACTIVATED
        }
        Wrappers.wait(WAIT_OFFSET) {
            def flowInfo = northboundV2.getFlow(flow.flowId)
            assert flowInfo.status == FlowState.DOWN.toString()
            assert flowInfo.statusInfo == "Failed to create flow $flow.flowId"
        }

        and: "Flow has no path associated"
        with(northbound.getFlowPath(flow.flowId)) {
            forwardPath.empty
            reversePath.empty
        }

        and: "Dst switch validation shows no missing rules"
        with(northbound.validateSwitch(dstSwitch.dpId)) {
            it.verifyRuleSectionsAreEmpty(["missing", "proper", "misconfigured"])
            it.verifyMeterSectionsAreEmpty(["missing", "misconfigured", "proper", "excess"])
        }

        when: "Try to validate flow"
        northbound.validateFlow(flow.flowId)

        then: "Error is returned, explaining that this is impossible for DOWN flows"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.UNPROCESSABLE_ENTITY
        e.responseBodyAsString.to(MessageError).errorDescription ==
                "Could not validate flow: Flow $flow.flowId is in DOWN state"

        when: "Switch returns back UP"
        lockKeeper.reviveSwitch(srcSwitch, blockData)
        Wrappers.wait(WAIT_OFFSET) { northbound.getSwitch(srcSwitch.dpId).state == SwitchChangeType.ACTIVATED }

        then: "Flow is still down, because ISLs had not enough time to fail, so no ISLs are discovered and no reroute happen"
        northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN

        when: "Reroute the flow"
        def rerouteResponse = northboundV2.rerouteFlow(flow.flowId)

        then: "Flow is rerouted and in UP state"
        rerouteResponse.rerouted
        Wrappers.wait(WAIT_OFFSET) { northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }

        and: "Has a path now"
        with(northbound.getFlowPath(flow.flowId)) {
            !forwardPath.empty
            !reversePath.empty
        }

        and: "Can be validated"
        northbound.validateFlow(flow.flowId).each { assert it.discrepancies.empty }

        and: "Flow can be removed"
        flowHelper.deleteFlow(flow.flowId)
    }

}
