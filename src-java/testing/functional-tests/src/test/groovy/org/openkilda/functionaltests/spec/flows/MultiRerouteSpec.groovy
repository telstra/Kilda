package org.openkilda.functionaltests.spec.flows

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.helpers.Wrappers.wait
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.testing.tools.SoftAssertions

import java.util.concurrent.TimeUnit

class MultiRerouteSpec extends HealthCheckSpecification {

    @Tidy
    def "Simultaneous reroute of multiple flows should not oversubscribe any ISLs"() {
        given: "Many flows on the same path, with alt paths available"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 2 } ?:
                assumeTrue(false, "No suiting switches found")
        List<FlowRequestV2> flows = []
        def currentPath = switchPair.paths.first()
        switchPair.paths.findAll { it != currentPath }.each { pathHelper.makePathMorePreferable(currentPath, it) }
        30.times {
            def flow = flowHelperV2.randomFlow(switchPair, false, flows)
            flow.maximumBandwidth = 10000
            flowHelperV2.addFlow(flow)
            flows << flow
        }
        //ensure all flows are on the same path
        flows[1..-1].each {
            assert pathHelper.convert(northbound.getFlowPath(it.flowId)) == currentPath
        }

        when: "Make another path more preferable"
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        def prefPath = switchPair.paths.find { it != currentPath }
        switchPair.paths.findAll { it != prefPath }.each { pathHelper.makePathMorePreferable(prefPath, it) }

        and: "Make preferable path's ISL to have bandwidth to host only half of the rerouting flows"
        def currentIsls = pathHelper.getInvolvedIsls(currentPath)
        def prefIsls = pathHelper.getInvolvedIsls(prefPath)
        def notPrefIsls = switchPair.paths.findAll { it != prefPath }.collectMany {
            def isls = pathHelper.getInvolvedIsls(it)
            isls + isls*.reversed
        }.unique(false)
        def thinIsl = prefIsls.find { !notPrefIsls.contains(it) }
        def halfOfFlows = flows[0..flows.size() / 2 - 1]
        long newBw = halfOfFlows.sum { it.maximumBandwidth }
        [thinIsl, thinIsl.reversed].each { database.updateIslMaxBandwidth(it, newBw) }
        [thinIsl, thinIsl.reversed].each { database.updateIslAvailableBandwidth(it, newBw) }

        and: "Init simultaneous reroute of all flows by bringing current path's ISL down"
        def notCurrentIsls = switchPair.paths.findAll { it != currentPath }.collectMany {
            def isls = pathHelper.getInvolvedIsls(it)
            isls + isls*.reversed
        }.unique()
        def islToBreak = currentIsls.find { !notCurrentIsls.contains(it) }
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        TimeUnit.SECONDS.sleep(rerouteDelay - 1)

        then: "Half of the flows are hosted on the preferable path"
        def flowsOnPrefPath
        wait(WAIT_OFFSET * 3) {
            def assertions = new SoftAssertions()
            flowsOnPrefPath = flows.findAll {
                pathHelper.convert(northbound.getFlowPath(it.flowId)) == prefPath
            }
            flowsOnPrefPath.each { flow ->
                assertions.checkSucceeds { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }
            }
            assertions.checkSucceeds { assert flowsOnPrefPath.size() == halfOfFlows.size() }
            assertions.verify()
        }

        and: "Rest of the flows are hosted on another alternative path"
        def restFlows = flows.findAll { !flowsOnPrefPath*.flowId.contains(it.flowId) }
        def exampleFlow = restFlows[0].flowId
        wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(exampleFlow).status == FlowState.UP }
        def restFlowsPath = pathHelper.convert(northbound.getFlowPath(exampleFlow))
        restFlowsPath != prefPath
        wait(WAIT_OFFSET) {
            def assertions = new SoftAssertions()
            restFlows[1..-1].each { flow ->
                assertions.checkSucceeds { assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == restFlowsPath }
                assertions.checkSucceeds { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }
            }
            assertions.verify()
        }

        and: "None ISLs are oversubscribed"
        northbound.getAllLinks().each { assert it.availableBandwidth >= 0 }

        cleanup: "revert system to original state"
        flows.each { flowHelperV2.deleteFlow(it.flowId) }
        antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        [thinIsl, thinIsl.reversed].each { database.resetIslBandwidth(it) }
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        wait(WAIT_OFFSET + discoveryInterval) {
            assert northbound.getLink(islToBreak).state == IslChangeType.DISCOVERED
        }
        database.resetCosts()
    }
}
