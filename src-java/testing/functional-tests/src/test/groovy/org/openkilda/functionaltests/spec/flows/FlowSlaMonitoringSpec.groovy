package org.openkilda.functionaltests.spec.flows

import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.helpers.Wrappers.wait
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.PathComputationStrategy
import org.openkilda.testing.model.topology.TopologyDefinition.Isl

import spock.lang.Ignore
import spock.lang.See
import spock.lang.Shared

@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/flow-monitoring")
class FlowSlaMonitoringSpec extends HealthCheckSpecification {
    @Shared
    List<PathNode> mainPath, alternativePath_1, alternativePath_2
    @Shared
    List<Isl> mainIsls, alternativeIsls_1, alternativeIsls_2, islsToBreak
    @Shared
    SwitchPair switchPair

    def setupOnce() {
        //setup: Two active switches with two diverse paths
        List<List<PathNode>> paths
        switchPair = topologyHelper.switchPairs.find {
            paths = it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }
            paths.size() >= 3
        } ?: assumeTrue("No suiting switches found", false)
        mainPath = paths[0]
        alternativePath_1 = paths[1]
        alternativePath_2 = paths[2]
        mainIsls = pathHelper.getInvolvedIsls(mainPath)
        alternativeIsls_1 = pathHelper.getInvolvedIsls(alternativePath_1)
        alternativeIsls_2 = pathHelper.getInvolvedIsls(alternativePath_2)
        //deactivate other paths for more clear experiment
        def isls = mainIsls + alternativeIsls_1 + alternativeIsls_2
        islsToBreak = switchPair.paths.findAll { !paths.contains(it) }
                .collect { pathHelper.getInvolvedIsls(it).find { !isls.contains(it) && !isls.contains(it.reversed) } }
                .unique { [it, it.reversed].sort() }
        islsToBreak.each { antiflap.portDown(it.srcSwitch.dpId, it.srcPort) }
    }

    @Tidy
    def "Able to detect and reroute a flow with MAX_LATENCY strategy when main path is not satisfy SLA"() {
        given: "A protected flow with MAX_LATENCY strategy, max_latency 16 and max_latency_tier_2 11"
        setLatencyForPaths(10, 15, 1)
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = true
            maxLatency = 16
            maxLatencyTier2 = 11
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        flowHelperV2.addFlow(flow)

        def path = northbound.getFlowPath(flow.flowId)
        pathHelper.convert(path) == alternativePath_1
        pathHelper.convert(path.protectedPath) == mainPath

        when: "Main path does not satisfy SLA(update isl latency via db)"
        setLatencyForPaths(1, 15, 10)

        then: "System detects and reroute the flow to the path which satisfy SLA"
        and: "Protected path is not changed"
        wait(WAIT_OFFSET) { // how often system check the SLA for all flows?
            with(northbound.getFlowPath(flow.flowId)) {
                pathHelper.convert(it) == alternativePath_2
                pathHelper.convert(it.protectedPath) == mainPath
            }
        }

        and: "Flow history contains information that flow was rerouted due to SLA check"

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Able to detect and reroute a flow with MAX_LATENCY strategy when protected path is not satisfy SLA"() {
        given: "A protected flow with MAX_LATENCY strategy, max_latency 16 and max_latency_tier_2 11"
        setLatencyForPaths(10, 15, 1)
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = true
            maxLatency = 16
            maxLatencyTier2 = 11
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        flowHelperV2.addFlow(flow)

        when: "Protected path does not satisfy SLA(update isl latency via db)"
        setLatencyForPaths(10, 1, 15)

        then: "System detects and reroute the flow to the path which satisfy SLA"
        and: "Main path is not changed"
        wait(WAIT_OFFSET) { // how often system check the SLA for all flows?
            with(northbound.getFlowPath(flow.flowId)) {
                pathHelper.convert(it) == alternativePath_1
                pathHelper.convert(it.protectedPath) == alternativePath_2
            }
        }

        and: "Flow history contains information that the flow was rerouted due to SLA check"

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Able to detect and reroute a flow with MAX_LATENCY strategy when main and protected paths \
are not satisfy SLA at the same time"() {
        given: "A protected flow with MAX_LATENCY strategy, max_latency 16 and max_latency_tier_2 11"
        setLatencyForPaths(10, 15, 1)
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = true
            maxLatency = 16
            maxLatencyTier2 = 11
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        flowHelperV2.addFlow(flow)

        when: "Main and protected paths does not satisfy SLA(update isl latency via db(alternativePath: 15))"
        setLatencyForPaths(1, 1, 15)

        then: "System detects and try to reroute the flow to the paths which satisfy SLA"
        and: "Main path is not changed, because new alternative path does not satisfy SLA for the main path"
        wait(WAIT_OFFSET) { // how often system check the SLA for all flows?
            with(northbound.getFlowPath(flow.flowId)) {
                pathHelper.convert(it) == alternativePath_1
                pathHelper.convert(it.protectedPath) == mainPath
            }
        }

        and: "Flow history contains information that flow was rerouted due to SLA check"
        //check retry in flowHistory for the main path

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags([LOW_PRIORITY])
    def "Able to detect and reroute a flow with MAX_LATENCY strategy when flow is DEGRADED and main path \
is not satisfying SLA"() {
        given: "A degraded flow with MAX_LATENCY strategy, max_latency 11"
        setLatencyForPaths(11, 15, 1)

        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = false
            maxLatency = 11
            maxLatencyTier2 = 16
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        northboundV2.addFlow(flow)

        when: "System detects more preferable path, but this path still does not satisfy SLA"
        setLatencyForPaths(11, 15, 14)

        then: "System detects new path and reroute the flow to the more preferable path"
        wait(WAIT_OFFSET) { // how often system check the SLA for all flows?
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == alternativePath_2
        }
        and: "The flow is still DEGRADED"
        with(northboundV2.getFlow(flow.flowId)) {
            it.status == FlowState.DEGRADED.toString()
            it.statusInfo == "An alternative way (back up strategy or max_latency_tier2 value) of" +
                    " building the path was used"
        }

        and: "Flow history contains information that flow was rerouted due to SLA check"
        //check retry in flowHistory for the main path

        when: "System detects more preferable path which satisfy SLA"
        setLatencyForPaths(10, 15, 14)

        then: "System detects new path and reroute the flow"
        wait(WAIT_OFFSET) { // how often system check the SLA for all flows?
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == mainPath
        }

        and: "The flow is UP"
        northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Ignore("not implemented, should take into account the 'maxLatency/maxLatencyTier2' fields for LATENCY strategy")
    def "Able to detect, do not reroute and move to DEGRADED state a flow with LATENCY strategy when \
path is not satisfy SLA"() {
        given: "A flow with LATENCY strategy, max_latency 16 and max_latency_tier_2 11"
        setLatencyForPaths(7, 10, 15)
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            maxLatency = 9
            pathComputationStrategy = PathComputationStrategy.LATENCY.toString()
        }
        flowHelperV2.addFlow(flow)

        def path = northbound.getFlowPath(flow.flowId)
        pathHelper.convert(path) == mainPath

        when: "Flow path does not satisfy SLA(update isl latency via db)"
        setLatencyForPaths(15, 10, 6)

        then: "System detects that flow path doesn't satisfy SLA and flow is DEGRADED"
        and: "Flow is not rerouted"
        wait(WAIT_OFFSET) { // how often system check the SLA for all flows?
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == mainPath
        }

        and: "Flow history contains information that flow was not rerouted due to SLA check"
        //just info about moving to the degraded state

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

     @Tidy
    def "System doesn't reroute a flow when flow path does not satisfy SLA and new preferable path \
does not have enough available bandwidth"() {
        given: "A protected flow with MAX_LATENCY strategy, max_latency 16 and max_latency_tier_2 11"
        setLatencyForPaths(10, 15, 20)
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            maxLatency = 11
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        flowHelperV2.addFlow(flow)

        and: "Alternative path ISLs do not have enough bandwidth to handle the flow"
        def altIsls = alternativeIsls_1 + alternativeIsls_2
        altIsls.each {
            database.updateIslAvailableBandwidth(it, flow.maximumBandwidth - 1)
            database.updateIslAvailableBandwidth(it.reversed, flow.maximumBandwidth - 1)
        }

        when: "Flow path does not satisfy SLA(update isl latency via db)"
        setLatencyForPaths(20, 15, 10)

        then: "System detects and try to reroute the flow to the path which satisfy SLA"
        and: "Flow is not rerouted(because not enough bandwidth)"
        wait(WAIT_OFFSET) { // how often system check the SLA for all flows?
            pathHelper.convert(northbound.getFlowPath(flow.flowId)) == mainPath
        }

        and: "Flow history contains information that flow was rerouted due to SLA check and the reason why it was not rerouted"

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        altIsls.each {
            database.resetIslBandwidth(it)
            database.resetIslBandwidth(it.reversed)
        }
        database.resetCosts()
    }

    @Tidy
    def "System ignores SLA check for a flow with COST strategy"() {
        given: "2 non-overlapping paths with 10 and 15 latency"
        setLatencyForPaths(10, 15, 18)

        when: "Create a flow with protected path, maxLatency 3 and maxLatencyTier2 10"
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = true
            maxLatency = 3
            maxLatencyTier2 = 10
            pathComputationStrategy = PathComputationStrategy.COST.toString()
        }
        northboundV2.addFlow(flow)

        then: "Flow is created in UP state because SLA check is ignored for the flow with COST strategy"
        wait(WAIT_OFFSET) {
            def flowInfo =  northboundV2.getFlow(flow.flowId)
            assert flowInfo.status == FlowState.UP.toString()
            assert flowInfo.statusInfo == ""
        }
        def path = northbound.getFlowPath(flow.flowId)
        pathHelper.convert(path) == mainPath

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    def setLatencyForPaths(int mainPathLatency, int alternativePathLatency_1, int alternativePathLatency_2) {
        def nanoMultiplier = 1000000
        def mainIslCost = mainPathLatency.intdiv(mainIsls.size()) * nanoMultiplier
        def alternativeIslCost_1 = alternativePathLatency_1.intdiv(alternativeIsls_1.size()) * nanoMultiplier
        def alternativeIslCost_2 = alternativePathLatency_2.intdiv(alternativeIsls_2.size()) * nanoMultiplier

        [mainIsls[0], mainIsls[0].reversed].each {
            database.updateIslLatency(it, mainIslCost + (mainPathLatency % mainIsls.size()) * nanoMultiplier)
        }
        mainIsls.tail().each { [it, it.reversed].each { database.updateIslLatency(it, mainIslCost) } }

        [alternativeIsls_1[0], alternativeIsls_1[0].reversed].each {
            database.updateIslLatency(it, alternativeIslCost_1 +
                    (alternativePathLatency_1 % alternativeIsls_1.size()) * nanoMultiplier)
        }
        alternativeIsls_1.tail().each { [it, it.reversed].each { database.updateIslLatency(it, alternativeIslCost_1) } }

        [alternativeIsls_2[0], alternativeIsls_2[0].reversed].each {
            database.updateIslLatency(it, alternativeIslCost_2 +
                    (alternativePathLatency_2 % alternativeIsls_2.size()) * nanoMultiplier)
        }
        alternativeIsls_2.tail().each { [it, it.reversed].each { database.updateIslLatency(it, alternativeIslCost_2) } }
    }

    def cleanupSpec() {
        islsToBreak.each { getAntiflap().portUp(it.srcSwitch.dpId, it.srcPort) }
        wait(getDiscoveryInterval() + WAIT_OFFSET) {
            assert getNorthbound().getActiveLinks().size() == getTopology().islsForActiveSwitches.size() * 2
        }
        getDatabase().resetCosts()
    }
}
