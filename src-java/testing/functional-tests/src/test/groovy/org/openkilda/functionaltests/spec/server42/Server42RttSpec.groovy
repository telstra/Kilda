package org.openkilda.functionaltests.spec.server42

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.STATS_FROM_SERVER42_LOGGING_TIMEOUT

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.SwitchHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.model.system.FeatureTogglesDto
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import groovy.time.TimeCategory
import org.springframework.beans.factory.annotation.Value
import spock.lang.Narrative
import spock.lang.Shared
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
    def "Create flow with server42 Rtt feature and check datapoints in opentsdb"() {
        given: "Two active switches, src has server42 connected"
        def server42switches = topology.getActiveServer42Switches();
        def server42switchesDpIds = server42switches*.dpId;
        def switchPair = topologyHelper.switchPairs.find {
            it.src.dpId in server42switchesDpIds
        }
        assumeTrue(switchPair != null, "Was not able to find a switch with a server42 connected")
        when: "Set server42FlowRtt toggle to true"
        def flowRttFeatureStartState = changeFlowRttToggle(true)
        and: "server42FlowRtt is enabled on src and dst switches"
        def server42Switch = switchPair.src
        def initialSwitchRtt = [server42Switch, switchPair.dst].collectEntries { [it, changeFlowRttSwitch(it, true)] }
        and: "Create a flow"
        def flowCreateTime = new Date()
        //take shorter flowid due to https://github.com/telstra/open-kilda/issues/3720
        def flow = flowHelperV2.randomFlow(switchPair).tap { it.flowId = it.flowId.take(25) }
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
    }
    @Tidy
    def "Flow rtt stats are available in forward and reverse directions for new flows"() {
        given: "Two active switches with src switch having server42"
        def server42switches = topology.getActiveServer42Switches()
        def server42switchesDpIds = server42switches*.dpId
        def switchPair = topologyHelper.switchPairs.collectMany { [it, it.reversed] }.find {
            it.src.dpId in server42switchesDpIds && !server42switchesDpIds.contains(it.dst.dpId)
        }
        assumeTrue(switchPair != null, "Was not able to find a switch with a server42 connected")
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
        assumeTrue(switchPair != null, "Was not able to find a switch with a server42 connected")
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
        assumeTrue(switchPair != null, "Was not able to find 2 switches on the same server42")
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
}
