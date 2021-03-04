package org.openkilda.functionaltests.spec.flows

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_SUCCESS
import static org.openkilda.functionaltests.helpers.Wrappers.wait
import static org.openkilda.messaging.info.event.IslChangeType.DISCOVERED
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.PathComputationStrategy
import org.openkilda.testing.model.topology.TopologyDefinition.Isl

import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.See
import spock.lang.Shared
import spock.lang.Unroll

@See("https://github.com/telstra/open-kilda/blob/develop/docs/design/pce/design.md")
class MaxLatencySpec extends HealthCheckSpecification {
    @Shared
    List<PathNode> mainPath, alternativePath
    @Shared
    List<Isl> mainIsls, alternativeIsls, islsToBreak
    @Shared
    SwitchPair switchPair

    def setupSpec() {
        //setup: Two active switches with two diverse paths
        List<List<PathNode>> paths
        switchPair = topologyHelper.switchPairs.find {
            paths = it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }
            paths.size() >= 2
        } ?: assumeTrue(false, "No suiting switches found")
        mainPath = paths[0]
        alternativePath = paths[1]
        mainIsls = pathHelper.getInvolvedIsls(mainPath)
        alternativeIsls = pathHelper.getInvolvedIsls(alternativePath)
        //deactivate other paths for more clear experiment
        def isls = mainIsls + alternativeIsls
        islsToBreak = switchPair.paths.findAll { !paths.contains(it) }
                .collect { pathHelper.getInvolvedIsls(it).find { !isls.contains(it) && !isls.contains(it.reversed) } }
                .unique { [it, it.reversed].sort() }
        islsToBreak.each { antiflap.portDown(it.srcSwitch.dpId, it.srcPort) }
    }

    @Tidy
    def "Able to create protected flow with max_latency strategy if both paths satisfy SLA"() {
        given: "2 non-overlapping paths with 10 and 15 latency"
        setLatencyForPaths(10, 15)

        when: "Create a flow with protected path, max_latency 16 and max_latency_tier_2 11"
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = true
            maxLatency = 16
            maxLatencyTier2 = 11
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        flowHelperV2.addFlow(flow)

        then: "Flow is created, main path is the 15 latency path, protected is 10 latency"
        def path = northbound.getFlowPath(flow.flowId)
        pathHelper.convert(path) == alternativePath
        pathHelper.convert(path.protectedPath) == mainPath

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Unroll
    @Tags([LOW_PRIORITY])
    def "Unable to create protected flow with max_latency strategy if #condition"() {
        given: "2 non-overlapping paths with 10 and 9 latency"
        setLatencyForPaths(10, 9)

        when: "Create a flow with protected path and max_latency #testMaxLatency"
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = true
            maxLatency = testMaxLatency
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        flowHelperV2.addFlow(flow)

        then: "Flow is not created, error returned describing that no paths found"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        def errorDetails = e.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription.startsWith("Not enough bandwidth or no path found. Failed to find path")

        cleanup:
        !e && flowHelperV2.deleteFlow(flow.flowId)

        where:
        testMaxLatency | condition
        10             | "only 1 path satisfies SLA"
        9              | "both paths do not satisfy SLA"
    }

    @Tidy
    def "Able to create DEGRADED protected flow with max_latency strategy if maxLatency < protectedPathLatency < maxLatencyTier2"() {
        given: "2 non-overlapping paths with 10 and 15 latency"
        setLatencyForPaths(10, 15)

        when: "Create a flow with protected path, maxLatency 11 and maxLatencyTier2 16"
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = true
            maxLatency = 11
            maxLatencyTier2 = 16  // maxLatency < pathLatency < maxLatencyTier2
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        northboundV2.addFlow(flow)

        then: "Flow is created, main path is the 10 latency path, protected is 15 latency"
        and: "Flow goes to DEGRADED state"
        wait(WAIT_OFFSET) {
            def flowInfo = northboundV2.getFlow(flow.flowId)
            assert flowInfo.status == FlowState.DEGRADED.toString()
            assert flowInfo.statusDetails.mainPath == "Up"
            assert flowInfo.statusDetails.protectedPath == "degraded"
            assert flowInfo.statusInfo == "An alternative way (back up strategy or max_latency_tier2 value) of" +
                    " building the path was used."
        }
        def path = northbound.getFlowPath(flow.flowId)
        pathHelper.convert(path) == mainPath
        pathHelper.convert(path.protectedPath) == alternativePath

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags([LOW_PRIORITY])
    def "Able to create DEGRADED flow with max_latency strategy if maxLatencyTier2 > pathLatency > maxLatency"() {
        given: "2 non-overlapping paths with 11 and 15 latency"
        setLatencyForPaths(11, 15)

        when: "Create a flow with max_latency 11 and max_latency_tier2 16"
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = false
            maxLatency = 11
            maxLatencyTier2 = 16
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        northboundV2.addFlow(flow)

        then: "Flow is created, flow path is the 15 latency path"
        wait(WAIT_OFFSET) {
            def flowInfo = northboundV2.getFlow(flow.flowId)
            assert flowInfo.status == FlowState.DEGRADED.toString()
            assert flowInfo.statusInfo == "An alternative way (back up strategy or max_latency_tier2 value) of" +
                    " building the path was used."
            //uncomment once 4080 is merged
            //assert northboundV2.getFlowHistoryStatuses(flow.flowId).historyStatuses*.statusBecome == ["DEGRADED"]
        }
        pathHelper.convert(northbound.getFlowPath(flow.flowId)) == alternativePath

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags([LOW_PRIORITY])
    def "Able to update DEGRADED flow with max_latency strategy if maxLatencyTier2 > pathLatency > maxLatency"() {
        given: "2 non-overlapping paths with 10 and 15 latency"
        setLatencyForPaths(10, 15)

        when: "Create a flow with max_latency 11 and max_latency_tier2 16"
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = false
            maxLatency = 11
            maxLatencyTier2 = 16
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        flowHelperV2.addFlow(flow)
        //flow path is the 10 latency path
        assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == mainPath

        and: "Update the flow(maxLatency: 10)"
        def newMaxLatency = 10
        northboundV2.updateFlow(flow.flowId, flow.tap { maxLatency = newMaxLatency })

        then: "Flow is updated and goes to the DEGRADED state"
        wait(WAIT_OFFSET) {
            def flowInfo = northboundV2.getFlow(flow.flowId)
            assert flowInfo.maxLatency == newMaxLatency
            assert flowInfo.status == FlowState.DEGRADED.toString()
            assert flowInfo.statusInfo == "An alternative way (back up strategy or max_latency_tier2 value) of" +
                    " building the path was used"
            //uncomment once 4080 is merged
            //assert northboundV2.getFlowHistoryStatuses(flow.flowId).historyStatuses*.statusBecome == ["UP", "DEGRADED"]
        }
        pathHelper.convert(northbound.getFlowPath(flow.flowId)) == alternativePath

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Able to reroute a flow if maxLatencyTier2 > pathLatency > maxLatency"() {
        given: "2 non-overlapping paths with 10 and 15 latency"
        setLatencyForPaths(10, 15)

        when: "Create a flow with max_latency 11 and max_latency_tier2 16"
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            allocateProtectedPath = false
            maxLatency = 11
            maxLatencyTier2 = 16
            pathComputationStrategy = PathComputationStrategy.MAX_LATENCY.toString()
        }
        flowHelperV2.addFlow(flow)
        assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == mainPath

        and: "Init auto reroute (bring port down on the src switch)"
        def islToBreak = pathHelper.getInvolvedIsls(mainPath).first()
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)

        then: "Flow is rerouted and goes to the DEGRADED state"
        wait(rerouteDelay + WAIT_OFFSET) {
            def flowHistory = northbound.getFlowHistory(flow.flowId).last()
            flowHistory.payload.last().action == REROUTE_SUCCESS
            // https://github.com/telstra/open-kilda/issues/4049
            flowHistory.payload.last().details == "Flow reroute completed with status DEGRADED  and error null"
            def flowInfo = northboundV2.getFlow(flow.flowId)
            assert flowInfo.status == FlowState.DEGRADED.toString()
            assert flowInfo.statusInfo == "An alternative way (back up strategy or max_latency_tier2 value) of" +
                    " building the path was used"
        }
        pathHelper.convert(northbound.getFlowPath(flow.flowId)) == alternativePath

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        if (islToBreak) {
            antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
            wait(discoveryInterval + WAIT_OFFSET) { assert islUtils.getIslInfo(islToBreak).get().state == DISCOVERED }
        }
        database.resetCosts()
    }

    def setLatencyForPaths(int mainPathLatency, int alternativePathLatency) {
        def nanoMultiplier = 1000000
        def mainIslCost = mainPathLatency.intdiv(mainIsls.size()) * nanoMultiplier
        def alternativeIslCost = alternativePathLatency.intdiv(alternativeIsls.size()) * nanoMultiplier
        [mainIsls[0], mainIsls[0].reversed].each {
            database.updateIslLatency(it, mainIslCost + (mainPathLatency % mainIsls.size()) * nanoMultiplier)
        }
        mainIsls.tail().each { [it, it.reversed].each { database.updateIslLatency(it, mainIslCost) } }
        [alternativeIsls[0], alternativeIsls[0].reversed].each {
            database.updateIslLatency(it, alternativeIslCost + (alternativePathLatency % alternativeIsls.size()) * nanoMultiplier)
        }
        alternativeIsls.tail().each { [it, it.reversed].each { database.updateIslLatency(it, alternativeIslCost) } }
    }

    def cleanupSpec() {
        islsToBreak.each { getAntiflap().portUp(it.srcSwitch.dpId, it.srcPort) }
        wait(getDiscoveryInterval() + WAIT_OFFSET) {
            assert getNorthbound().getActiveLinks().size() == getTopology().islsForActiveSwitches.size() * 2
        }
        getDatabase().resetCosts()
    }
}
