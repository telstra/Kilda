package org.openkilda.functionaltests.spec.server42

import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.STATS_FROM_SERVER42_LOGGING_TIMEOUT
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.SwitchHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.model.system.FeatureTogglesDto
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.northbound.dto.v2.flows.SwapFlowPayload
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import groovy.time.TimeCategory
import org.springframework.beans.factory.annotation.Value
import spock.lang.Narrative
import spock.lang.Shared
import spock.lang.Unroll
import spock.util.mop.Use

import java.util.concurrent.TimeUnit

@Tags(HARDWARE) //unstable on jenkins, fix ASAP
@Use(TimeCategory)
@Narrative("Verify that statistic is collected from server42 Rtt")
/* On local environment these tests will use stubs without sending real rtt packets across the network.
see server42-control-server-stub.
Note that on hardware env it is very important for switch to have correct time, since data in otsdb it posted using
switch timestamps, thus we may see no stats in otsdb if time on switch is incorrect
 */
class Server42RttSpec extends HealthCheckSpecification {
    @Shared
    @Value('${opentsdb.metric.prefix}')
    String metricPrefix

    @Tidy
    @Unroll
    def "Create a #data.flowDescription flow with server42 Rtt feature and check datapoints in opentsdb"() {
        given: "Two active switches, src has server42 connected"
        def server42switches = topology.getActiveServer42Switches();
        assumeTrue("Unable to find active server42", (server42switches.size() > 0))
        def server42switchesDpIds = server42switches*.dpId;
        def switchPair = data.switchPair(server42switchesDpIds)
        assumeTrue("Was not able to find a switch with a server42 connected", switchPair != null)

        when: "Set server42FlowRtt toggle to true"
        def flowRttFeatureStartState = changeFlowRttToggle(true)

        and: "server42FlowRtt is enabled on src and dst switches"
        def server42Switch = switchPair.src
        def initialSwitchRtt = [server42Switch, switchPair.dst].collectEntries { [it, changeFlowRttSwitch(it, true)] }

        and: "Create a flow"
        def flowCreateTime = new Date()
        //take shorter flowid due to https://github.com/telstra/open-kilda/issues/3720
        def flow = flowHelperV2.randomFlow(switchPair).tap { it.flowId = it.flowId.take(25) }
        flow.tap(data.flowTap)
        flowHelperV2.addFlow(flow)

        then: "Check if stats for forward are available"
        def statsData = null
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            statsData = otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "forward"]).dps
            assert statsData && !statsData.empty
        }

        cleanup: "Revert system to original state"
        revertToOrigin([flow], flowRttFeatureStartState, initialSwitchRtt)

        where:
        data << [[
                         flowDescription: "default",
                         switchPair     : { List<SwitchId> switchIds -> getSwPairConnectedToS42ForSimpleFlow(switchIds) },
                         flowTap        : { FlowRequestV2 fl ->
                             fl.source.vlanId = 0
                             fl.destination.vlanId = 0
                         }
                 ],
                 [
                         flowDescription: "protected",
                         switchPair     : { List<SwitchId> switchIds -> getSwPairConnectedToS42ForProtectedFlow(switchIds) },
                         flowTap        : { FlowRequestV2 fl -> fl.allocateProtectedPath = true }
                 ],
                 [
                         flowDescription: "vxlan",
                         switchPair     : { List<SwitchId> switchIds -> getSwPairConnectedToS42ForVxlanFlow(switchIds) },
                         flowTap        : { FlowRequestV2 fl -> fl.encapsulationType = FlowEncapsulationType.VXLAN }
                 ],
                 [
                         flowDescription: "qinq",
                         switchPair     : { List<SwitchId> switchIds -> getSwPairConnectedToS42ForQinQ(switchIds) },
                         flowTap        : { FlowRequestV2 fl ->
                             fl.source.vlanId = 10
                             fl.source.innerVlanId = 100
                             fl.destination.vlanId = 20
                             fl.destination.innerVlanId = 200
                         }
                 ]
        ]
    }

    @Tidy
    def "Flow rtt stats are available in forward and reverse directions for new flows"() {
        given: "Two active switches with src switch having server42"
        def server42switches = topology.getActiveServer42Switches()
        def server42switchesDpIds = server42switches*.dpId
        def switchPair = topologyHelper.switchPairs.collectMany { [it, it.reversed] }.find {
            it.src.dpId in server42switchesDpIds && !server42switchesDpIds.contains(it.dst.dpId)
        }
        assumeTrue("Was not able to find a switch with a server42 connected", switchPair != null)
        and: "server42FlowRtt feature toggle is set to true"
        def flowRttFeatureStartState = changeFlowRttToggle(true)
        and: "server42FlowRtt is enabled on src and dst switches"
        def server42Switch = switchPair.src
        def initialSwitchRtt = [server42Switch, switchPair.dst].collectEntries { [it, changeFlowRttSwitch(it, true)] }
        when: "Create a flow for forward metric"
        def flowCreateTime = new Date()
        def flow = flowHelperV2.randomFlow(switchPair).tap { it.flowId = it.flowId.take(25) }
        flowHelperV2.addFlow(flow)
        and: "Create a reversed flow for backward metric"
        def reversedFlow = flowHelperV2.randomFlow(switchPair.reversed).tap { it.flowId = it.flowId.take(25) }
        flowHelperV2.addFlow(reversedFlow)
        then: "Involved switches pass switch validation"
        pathHelper.getInvolvedSwitches(flow.flowId).each { sw ->
            verifyAll(northbound.validateSwitch(sw.dpId)) {
                rules.missing.empty
                rules.excess.empty
                rules.misconfigured.empty
                meters.missing.empty
                meters.excess.empty
                meters.misconfigured.empty
            }
        }
        and: "Check if stats for forward are available"
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            def statsData = otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "forward"]).dps
            assert statsData && !statsData.empty
        }
        and: "Check if stats for reverse are available"
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            def statsData = otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : reversedFlow.flowId,
                     direction: "reverse"]).dps
            assert statsData && !statsData.empty
        }
        cleanup: "Revert system to original state"
        revertToOrigin([flow, reversedFlow], flowRttFeatureStartState, initialSwitchRtt)
    }

    @Tidy
    def "Flow rtt stats are available only if both global and switch toggles are 'on' on both endpoints"() {
        given: "Two active switches with src switch having server42"
        def server42switches = topology.getActiveServer42Switches()
        def server42switchesDpIds = server42switches*.dpId
        def switchPair = topologyHelper.switchPairs.collectMany { [it, it.reversed] }.find {
            it.src.dpId in server42switchesDpIds && !server42switchesDpIds.contains(it.dst.dpId)
        }
        assumeTrue("Was not able to find a switch with a server42 connected", switchPair != null)
        def statsWaitSeconds = 4
        and: "server42FlowRtt toggle is turned off"
        def flowRttFeatureStartState = changeFlowRttToggle(false)
        and: "server42FlowRtt is turned off on src and dst"
        def initialSwitchRtt = [switchPair.src, switchPair.dst].collectEntries { [it, changeFlowRttSwitch(it, false)] }
        and: "Flow for forward metric is created"
        def checkpointTime = new Date()
        def flow = flowHelperV2.randomFlow(switchPair).tap { it.flowId = it.flowId.take(25) }
        flowHelperV2.addFlow(flow)
        and: "Reversed flow for backward metric is created"
        def reversedFlow = flowHelperV2.randomFlow(switchPair.reversed).tap { it.flowId = it.flowId.take(25) }
        flowHelperV2.addFlow(reversedFlow)
        expect: "Involved switches pass switch validation"
        pathHelper.getInvolvedSwitches(flow.flowId).each { sw ->
            verifyAll(northbound.validateSwitch(sw.dpId)) {
                rules.missing.empty
                rules.excess.empty
                rules.misconfigured.empty
                meters.missing.empty
                meters.excess.empty
                meters.misconfigured.empty
            }
        }
        when: "Wait for several seconds"
        TimeUnit.SECONDS.sleep(statsWaitSeconds)
        then: "Expect no flow rtt stats for forward flow"
        otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                [flowid   : flow.flowId,
                 direction: "forward"]).dps.isEmpty()
        and: "Expect no flow rtt stats for reversed flow"
        otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                [flowid   : reversedFlow.flowId,
                 direction: "reverse"]).dps.isEmpty()
        when: "Enable global rtt toggle"
        changeFlowRttToggle(true)
        and: "Wait for several seconds"
        checkpointTime = new Date()
        TimeUnit.SECONDS.sleep(statsWaitSeconds)
        then: "Expect no flow rtt stats for forward flow"
        otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                [flowid   : flow.flowId,
                 direction: "forward"]).dps.isEmpty()
        and: "Expect no flow rtt stats for reversed flow"
        otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                [flowid   : reversedFlow.flowId,
                 direction: "reverse"]).dps.isEmpty()
        when: "Enable switch rtt toggle on src and dst"
        changeFlowRttSwitch(switchPair.src, true)
        changeFlowRttSwitch(switchPair.dst, true)
        checkpointTime = new Date()
        then: "Stats for forward flow are available"
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            def statsData = otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "forward"]).dps
            assert statsData && !statsData.empty
        }
        and: "Stats for reversed flow are available"
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            def statsData = otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                    [flowid   : reversedFlow.flowId,
                     direction: "reverse"]).dps
            assert statsData && !statsData.empty
        }
        //behavior below varies between physical switches and virtual stub due to 'turning rule'
        //will be resolved as part of https://github.com/telstra/open-kilda/issues/3809
//        when: "Disable switch rtt toggle on dst (still enabled on src)"
//        changeFlowRttSwitch(switchPair.dst, false)
//        checkpointTime = new Date()
//
//        then: "Stats for forward flow are available"
//        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
//            def statsData = otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
//                    [flowid   : flow.flowId,
//                     direction: "forward"]).dps
//            assert statsData && !statsData.empty
//        }
//
//        and: "Stats for reversed flow are available"
//        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
//            def statsData = otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
//                    [flowid   : reversedFlow.flowId,
//                     direction: "reverse"]).dps
//            assert statsData && !statsData.empty
//        }
        when: "Disable global toggle"
        changeFlowRttToggle(false)
        and: "Wait for several seconds"
        checkpointTime = new Date()
        TimeUnit.SECONDS.sleep(statsWaitSeconds)
        then: "Expect no flow rtt stats for forward flow"
        otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                [flowid   : flow.flowId,
                 direction: "forward"]).dps.isEmpty()
        and: "Expect no flow rtt stats for reversed flow"
        otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                [flowid   : reversedFlow.flowId,
                 direction: "reverse"]).dps.isEmpty()
        cleanup: "Revert system to original state"
        revertToOrigin([flow, reversedFlow], flowRttFeatureStartState, initialSwitchRtt)
    }

    @Tidy
    @Tags([TOPOLOGY_DEPENDENT])
    def "Flow rtt stats are available if both endpoints are conected to the same server42 (same pop)"() {
        given: "Two active switches connected to the same server42 instance"
        def switchPair = topologyHelper.switchPairs.collectMany { [it, it.reversed] }.find {
            it.src.prop?.server42MacAddress != null && it.src.prop?.server42MacAddress == it.dst.prop?.server42MacAddress
        }
        assumeTrue("Was not able to find 2 switches on the same server42", switchPair != null)
        and: "server42FlowRtt feature enabled globally and on src/dst switch"
        def flowRttFeatureStartState = changeFlowRttToggle(true)
        def initialSwitchRtt = [switchPair.src, switchPair.dst].collectEntries { [it, changeFlowRttSwitch(it, true)] }
        when: "Create a flow"
        def checkpointTime = new Date()
        def flow = flowHelperV2.randomFlow(switchPair).tap { it.flowId = it.flowId.take(25) }
        flowHelperV2.addFlow(flow)
        then: "Involved switches pass switch validation"
        pathHelper.getInvolvedSwitches(flow.flowId).each { sw ->
            verifyAll(northbound.validateSwitch(sw.dpId)) {
                rules.missing.empty
                rules.excess.empty
                rules.misconfigured.empty
                meters.missing.empty
                meters.excess.empty
                meters.misconfigured.empty
            }
        }
        and: "Stats for both directions are available"
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            def fwData = otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "forward"]).dps
            assert fwData && !fwData.empty
            def reverseData = otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "reverse"]).dps
            assert reverseData && !reverseData.empty
        }
        when: "Disable flow rtt on dst switch"
        changeFlowRttSwitch(switchPair.dst, false)
        sleep(1000)
        checkpointTime = new Date()
        then: "Stats are available in forward direction"
        TimeUnit.SECONDS.sleep(4) //remove after removing workaround below
        //not until https://github.com/telstra/open-kilda/issues/3809
//        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
//            def fwData = otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
//                    [flowid   : flow.flowId,
//                     direction: "forward"]).dps
//            assert fwData && !fwData.empty
//        }
        and: "Stats are not available in reverse direction"
        otsdb.query(checkpointTime, metricPrefix + "flow.rtt",
                [flowid   : flow.flowId,
                 direction: "reverse"]).dps.isEmpty()
        cleanup: "Revert system to original state"
        revertToOrigin([flow], flowRttFeatureStartState, initialSwitchRtt)
    }

    @Tidy
    @Tags(HARDWARE) //not supported on a local env (the 'stub' service doesn't send real traffic through a switch)
    def "Able to synchronize a flow (install missing server42 rules)"() {
        given: "A switch pair connected to server42"
        def server42switches = topology.getActiveServer42Switches();
        def server42switchesDpIds = server42switches*.dpId;
        def switchPair = getSwPairConnectedToS42ForSimpleFlow(server42switchesDpIds)
        assumeTrue("Was not able to find a switch with a server42 connected", switchPair != null)
        //enable server42 in featureToggle and on the switches
        def flowRttFeatureStartState = changeFlowRttToggle(true)
        def server42Switch = switchPair.src
        def initialSwitchRtt = [server42Switch, switchPair.dst].collectEntries { [it, changeFlowRttSwitch(it, true)] }

        and: "A flow on the given switch pair"
        def flowCreateTime = new Date()
        //take shorter flowid due to https://github.com/telstra/open-kilda/issues/3720
        def flow = flowHelperV2.randomFlow(switchPair).tap { it.flowId = it.flowId.take(25) }
        flowHelperV2.addFlow(flow)

        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            assert otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "forward"]).dps.size() > 0
            assert otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "reverse"]).dps.size() > 0
        }

        when: "Delete ingress server42 rule related to the flow on the src switch"
        def cookieToDelete = northbound.getSwitchRules(switchPair.src.dpId).flowEntries.find {
            new Cookie(it.cookie).getType() == CookieType.SERVER_42_INGRESS
        }.cookie
        northbound.deleteSwitchRules(switchPair.src.dpId, cookieToDelete)

        then: "System detects missing rule on the src switch"
        Wrappers.wait(RULES_DELETION_TIME) {
            assert northbound.validateSwitch(switchPair.src.dpId).rules.missing == [cookieToDelete]
        }

        and: "Flow is valid and UP"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        northbound.getFlowStatus(flow.flowId).status == FlowState.UP

        and: "server42 stats for forward direction are not increased"
        and: "server42 stats for reverse direction are increased"
        TimeUnit.SECONDS.sleep(STATS_FROM_SERVER42_LOGGING_TIMEOUT + WAIT_OFFSET)
        def statsDataForward = otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                [flowid   : flow.flowId,
                 direction: "forward"]).dps
        def statsDataReverse = otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                [flowid   : flow.flowId,
                 direction: "reverse"]).dps
        def newStatsDataReverse
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            newStatsDataReverse = otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "reverse"]).dps
            assert newStatsDataReverse.size() > statsDataReverse.size()
        }
        otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                [flowid   : flow.flowId,
                 direction: "forward"]).dps.size() == statsDataForward.size()

        when: "Synchronize the flow"
        with(northbound.synchronizeFlow(flow.flowId)) { !it.rerouted }

        then: "Missing ingress server42 rule is reinstalled on the src switch"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northbound.validateSwitch(switchPair.src.dpId).rules.missing.empty
            assert northbound.getSwitchRules(switchPair.src.dpId).flowEntries.findAll {
                new Cookie(it.cookie).getType() == CookieType.SERVER_42_INGRESS
            }*.cookie.size() == 1
        }

        then: "server42 stats for forward direction are available again"
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT + WAIT_OFFSET, 1) {
            assert otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "forward"]).dps.size() > statsDataForward.size()
            assert otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow.flowId,
                     direction: "reverse"]).dps.size() > newStatsDataReverse.size()
        }

        cleanup: "Revert system to original state"
        revertToOrigin([flow], flowRttFeatureStartState, initialSwitchRtt)
    }

    @Tidy
    def "Able to swapEndpoint for a flow with enabled server42 on it"() {
        given: "Two switch pairs with different src switches and the same dst switch"
        def switchIdsConnectedToS42 = topology.getActiveServer42Switches()*.dpId
        SwitchPair fl2SwPair = null
        SwitchPair fl1SwPair = topologyHelper.switchPairs.collectMany { [it, it.reversed] }.find { firstSwP ->
            def firstOk =
                    firstSwP.src.dpId in switchIdsConnectedToS42 && !switchIdsConnectedToS42.contains(firstSwP.dst.dpId)
            fl2SwPair = topologyHelper.switchPairs.collectMany { [it, it.reversed] }.find { secondSwP ->
                secondSwP.dst.dpId == firstSwP.dst.dpId && secondSwP.src.dpId != firstSwP.src.dpId &&
                        !switchIdsConnectedToS42.contains(secondSwP.src.dpId)
            }
            firstOk && fl2SwPair
        }
        assumeTrue("Required switch pairs were not found in the given topology",
                fl1SwPair.asBoolean() && fl2SwPair.asBoolean())

        and: "server42 is enabled on the src sw of the first switch pair"
        def flowRttFeatureStartState = changeFlowRttToggle(true)
        def initialSwitchRtt = [].collectEntries { [fl1SwPair.src, changeFlowRttSwitch(fl1SwPair.src, true)] }

        and: "Two flows on the given switch pairs"
        def flowCreateTime = new Date()
        //take shorter flowid due to https://github.com/telstra/open-kilda/issues/3720
        def flow1 = flowHelperV2.randomFlow(fl1SwPair).tap { it.flowId = it.flowId.take(25) }
        def flow2 = flowHelperV2.randomFlow(fl2SwPair).tap { it.flowId = it.flowId.take(25) }
        flowHelperV2.addFlow(flow1)
        flowHelperV2.addFlow(flow2)

        //make sure stats for the flow1 in forward directions are available and not available for the flow2
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            assert !otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow1.flowId,
                     direction: "forward"]).dps.isEmpty()
            assert otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow2.flowId,
                     direction: "forward"]).dps.isEmpty()
        }

        when: "Try to swap src endpoints for two flows"
        def flow1Src = flow2.source
        def flow1Dst = flow1.destination
        def flow2Src = flow1.source
        def flow2Dst = flow2.destination
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.flowId, flow1Src, flow1Dst),
                new SwapFlowPayload(flow2.flowId, flow2Src, flow2Dst))

        then: "Endpoints are successfully swapped"
        with(response) {
            it.firstFlow.source == flow1Src
            it.firstFlow.destination == flow1Dst
            it.secondFlow.source == flow2Src
            it.secondFlow.destination == flow2Dst
        }

        def flow1Updated = northboundV2.getFlow(flow1.flowId)
        def flow2Updated = northboundV2.getFlow(flow2.flowId)
        flow1Updated.source == flow1Src
        flow1Updated.destination == flow1Dst
        flow2Updated.source == flow2Src
        flow2Updated.destination == flow2Dst

        and: "Flows validation doesn't show any discrepancies"
        [flow1, flow2].each {
            northbound.validateFlow(it.flowId).each { direction -> assert direction.asExpected }
        }

        and: "All switches are valid"
        def involvedSwitches = [fl1SwPair.src, fl1SwPair.dst, fl2SwPair.src, fl2SwPair.dst]*.dpId.unique()
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            involvedSwitches.each { swId ->
                northbound.validateSwitch(swId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
            }
        }
        def switchesAreValid = true

        and: "server42 stats are available for the flow2 in the forward direction"
        TimeUnit.SECONDS.sleep(STATS_FROM_SERVER42_LOGGING_TIMEOUT + WAIT_OFFSET)
        def flow1Stat = otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                [flowid   : flow1.flowId,
                 direction: "forward"]).dps
        Wrappers.wait(STATS_FROM_SERVER42_LOGGING_TIMEOUT, 1) {
            assert !otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                    [flowid   : flow2.flowId,
                     direction: "forward"]).dps.isEmpty()
        }

        and: "server42 stats are not available any more for the flow1 in the forward direction"
        otsdb.query(flowCreateTime, metricPrefix + "flow.rtt",
                [flowid   : flow1.flowId,
                 direction: "forward"]).dps.size() == flow1Stat.size()

        cleanup:
        revertToOrigin([flow1, flow2], flowRttFeatureStartState, initialSwitchRtt)
        if (!switchesAreValid) {
            involvedSwitches.each { northbound.synchronizeSwitch(it, true) }
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                involvedSwitches.each { swId ->
                    assert northbound.validateSwitch(swId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                }
            }
        }
    }

    def changeFlowRttSwitch(Switch sw, boolean requiredState) {
        def originalProps = northbound.getSwitchProperties(sw.dpId)
        if (originalProps.server42FlowRtt != requiredState) {
            northbound.updateSwitchProperties(sw.dpId, originalProps.jacksonCopy().tap {
                server42FlowRtt = requiredState
                def props = sw.prop ?: SwitchHelper.dummyServer42Props
                server42MacAddress = props.server42MacAddress
                server42Port = props.server42Port
                server42Vlan = props.server42Vlan
            })
        }
        return originalProps.server42FlowRtt
    }
    def changeFlowRttToggle(boolean requiredState) {
        def originalState = northbound.featureToggles.server42FlowRtt
        if (originalState != requiredState) {
            northbound.toggleFeature(FeatureTogglesDto.builder().server42FlowRtt(requiredState).build())
        }
        return originalState
    }
    def revertToOrigin(flows,  flowRttFeatureStartState, initialSwitchRtt) {
        flowRttFeatureStartState != null && changeFlowRttToggle(flowRttFeatureStartState)
        initialSwitchRtt.each { sw, state -> changeFlowRttSwitch(sw, state)  }
        flows.each { flowHelperV2.deleteFlow(it.flowId) }
        initialSwitchRtt.keySet().each { sw ->
            Wrappers.wait(RULES_INSTALLATION_TIME) {
                assert northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.sort() == sw.defaultCookies.sort()
            }
        }
    }

    def "getSwPairConnectedToS42ForSimpleFlow"(List<SwitchId> switchIdsConnectedToS42) {
        getTopologyHelper().getSwitchPairs().collectMany { [it, it.reversed] }.find { swP ->
            [swP.dst, swP.src].every { it.dpId in switchIdsConnectedToS42 }
        }
    }

    def "getSwPairConnectedToS42ForProtectedFlow"(List<SwitchId> switchIdsConnectedToS42) {
        getTopologyHelper().getSwitchPairs().find {
            [it.dst, it.src].every { it.dpId in switchIdsConnectedToS42 } && it.paths.unique(false) {
                a, b -> a.intersect(b) == [] ? 1 : 0
            }.size() >= 2
        }
    }

    def "getSwPairConnectedToS42ForVxlanFlow"(List<SwitchId> switchIdsConnectedToS42) {
        getTopologyHelper().getSwitchPairs().find { swP ->
            [swP.dst, swP.src].every { it.dpId in switchIdsConnectedToS42 } && swP.paths.findAll { path ->
                pathHelper.getInvolvedSwitches(path).every {
                    getNorthbound().getSwitchProperties(it.dpId).supportedTransitEncapsulation
                            .contains(FlowEncapsulationType.VXLAN.toString().toLowerCase())
                }
            }
        }
    }

    def "getSwPairConnectedToS42ForQinQ"(List<SwitchId> switchIdsConnectedToS42) {
        getTopologyHelper().getSwitchPairs().find { swP ->
            [swP.dst, swP.src].every { it.dpId in switchIdsConnectedToS42 } && swP.paths.findAll { path ->
                pathHelper.getInvolvedSwitches(path).every { getNorthbound().getSwitchProperties(it.dpId).multiTable }
            }
        }
    }
}
