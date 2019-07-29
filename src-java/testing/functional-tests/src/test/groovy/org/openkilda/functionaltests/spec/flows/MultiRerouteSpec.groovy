package org.openkilda.functionaltests.spec.flows

import static org.junit.Assume.assumeTrue
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2

import spock.lang.Ignore

import java.util.concurrent.TimeUnit

@Ignore
class MultiRerouteSpec extends HealthCheckSpecification {

    @Tidy
    def "Simultaneous reroute of multiple flows should not oversubscribe any ISLs"() {
        given: "Two flows on the same path, with alt paths available"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 2 } ?:
                assumeTrue("No suiting switches found", false)
        List<FlowRequestV2> flows = []
        30.times {
            def flow = flowHelperV2.randomFlow(switchPair, false, flows)
            flow.maximumBandwidth = 10000
            flowHelperV2.addFlow(flow)
            flows << flow
        }
        def currentPath = pathHelper.convert(northbound.getFlowPath(flows[0].flowId))
        //ensure all flows are on the same path
        flows[1..-1].each {
            assert pathHelper.convert(northbound.getFlowPath(it.flowId)) == currentPath
        }

        when: "Make another path more preferable"
        def newPath = switchPair.paths.find { it != currentPath }
        switchPair.paths.findAll { it != newPath }.each { pathHelper.makePathMorePreferable(newPath, it) }

        and: "Make preferable path's ISL to have bandwidth to host only half of the rerouting flows"
        def currentIsls = pathHelper.getInvolvedIsls(currentPath)
        def newIsls = pathHelper.getInvolvedIsls(newPath)
        def otherIsls = switchPair.paths.findAll { it != newPath }.collectMany {
            def isls = pathHelper.getInvolvedIsls(it)
            [isls, isls*.reversed]
        }.unique(false)
        def thinIsl = newIsls.find { !otherIsls.contains(it) }
        def halfOfFlows = flows[0..flows.size() / 2 - 1]
        long newBw = halfOfFlows.sum { it.maximumBandwidth }
        [thinIsl, thinIsl.reversed].each { database.updateIslMaxBandwidth(it, newBw) }
        [thinIsl, thinIsl.reversed].each { database.updateIslAvailableBandwidth(it, newBw) }

        and: "Init simultaneous reroute of all flows by bringing current path's ISL down"
        def islToBreak = currentIsls.find { !newIsls.contains(it) }
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        TimeUnit.SECONDS.sleep(rerouteDelay - 1)

        then: "Half of the flows are hosted on the preferable path"
        def flowsOnPrefPath
        Wrappers.wait(PATH_INSTALLATION_TIME * 1.5) {
            flowsOnPrefPath = flows.findAll {
                pathHelper.convert(northbound.getFlowPath(it.flowId)) == newPath
            }
            assert flowsOnPrefPath.size() == halfOfFlows.size()
            flowsOnPrefPath.each { assert northboundV2.getFlowStatus(it.flowId).status == FlowState.UP }
        }

        and: "Rest of the flows are hosted on another alternative path"
        def restFlows = flows.findAll { !flowsOnPrefPath*.flowId.contains(it.flowId) }
        def restFlowsPath = pathHelper.convert(northbound.getFlowPath(restFlows[0].flowId))
        restFlows[1..-1].each {
            assert pathHelper.convert(northbound.getFlowPath(it.flowId)) == restFlowsPath
        }

        and: "None ISLs are oversubscribed"
        northbound.getAllLinks().each {
            assert it.availableBandwidth >= 0
        }

        cleanup: "revert system to original state"
        flows.each { flowHelperV2.deleteFlow(it.flowId) }
        antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        [thinIsl, thinIsl.reversed].each { database.resetIslBandwidth(it) }
        database.resetCosts()
        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED
        }
    }
}
