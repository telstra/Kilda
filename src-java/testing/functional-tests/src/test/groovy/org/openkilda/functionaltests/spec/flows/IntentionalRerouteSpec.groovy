package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.testing.Constants.DEFAULT_COST
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Narrative
import spock.lang.Shared

import javax.inject.Provider

@Narrative("Verify that on-demand reroute operations are performed accurately.")
@Tags([LOW_PRIORITY])
class IntentionalRerouteSpec extends HealthCheckSpecification {

    @Autowired @Shared
    Provider<TraffExamService> traffExamProvider

    def "Not able to reroute to a path with not enough bandwidth available"() {
        given: "A flow with alternate paths available"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")
        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = 10000
        flowHelper.addFlow(flow)
        def currentPath = PathHelper.convert(northbound.getFlowPath(flow.id))

        when: "Make the current path less preferable than alternatives"
        def alternativePaths = switchPair.paths.findAll { it != currentPath }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentPath) }

        and: "Make all alternative paths to have not enough bandwidth to handle the flow"
        def currentIsls = pathHelper.getInvolvedIsls(currentPath)
        def changedIsls = alternativePaths.collect { altPath ->
            def thinIsl = pathHelper.getInvolvedIsls(altPath).find {
                !currentIsls.contains(it) && !currentIsls.contains(it.reversed)
            }
            def newBw = flow.maximumBandwidth - 1
            [thinIsl, thinIsl.reversed].each {
                database.updateIslMaxBandwidth(it, newBw)
                database.updateIslAvailableBandwidth(it, newBw)
            }
            thinIsl
        }

        and: "Init a reroute to a more preferable path"
        def rerouteResponse = northbound.rerouteFlow(flow.id)

        then: "The flow is NOT rerouted because of not enough bandwidth on alternative paths"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }
        !rerouteResponse.rerouted
        rerouteResponse.path.path == currentPath
        int seqId = 0
        rerouteResponse.path.path.each { assert it.seqId == seqId++ }
        PathHelper.convert(northbound.getFlowPath(flow.id)) == currentPath

        and: "Remove the flow, restore the bandwidth on ISLs, reset costs"
        flowHelper.deleteFlow(flow.id)
        changedIsls.each {
            database.resetIslBandwidth(it)
            database.resetIslBandwidth(it.reversed)
        }
    }

    def "Able to reroute to a better path if it has enough bandwidth"() {
        given: "A flow with alternate paths available"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")
        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = 10000
        flowHelper.addFlow(flow)
        def currentPath = PathHelper.convert(northbound.getFlowPath(flow.id))

        when: "Make one of the alternative paths to be the most preferable among all others"
        def preferableAltPath = switchPair.paths.find { it != currentPath }
        switchPair.paths.findAll { it != preferableAltPath }.each {
            pathHelper.makePathMorePreferable(preferableAltPath, it)
        }

        and: "Make the future path to have exact bandwidth to handle the flow"
        def currentIsls = pathHelper.getInvolvedIsls(currentPath)
        def thinIsl = pathHelper.getInvolvedIsls(preferableAltPath).find {
            !currentIsls.contains(it) && !currentIsls.contains(it.reversed)
        }
        [thinIsl, thinIsl.reversed].each {
            database.updateIslMaxBandwidth(it, flow.maximumBandwidth)
            database.updateIslAvailableBandwidth(it, flow.maximumBandwidth)
        }

        and: "Init a reroute of the flow"
        def rerouteResponse = northbound.rerouteFlow(flow.id)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }

        then: "The flow is successfully rerouted and goes through the preferable path"
        def newPath = PathHelper.convert(northbound.getFlowPath(flow.id))
        int seqId = 0

        rerouteResponse.rerouted
        rerouteResponse.path.path == newPath
        rerouteResponse.path.path.each { assert it.seqId == seqId++ }

        newPath == preferableAltPath
        pathHelper.getInvolvedIsls(newPath).contains(thinIsl)

        and: "'Thin' ISL has 0 available bandwidth left"
        Wrappers.wait(WAIT_OFFSET) { assert islUtils.getIslInfo(thinIsl).get().availableBandwidth == 0 }

        and: "Remove the flow, restore bandwidths on ISLs, reset costs"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }
        flowHelper.deleteFlow(flow.id)
        [thinIsl, thinIsl.reversed].each { database.resetIslBandwidth(it) }
    }

    /**
     * Select a longest available path between 2 switches, then reroute to another long path. Run traffexam during the
     * reroute and expect no packet loss.
     */
    @Tidy
    @Tags([HARDWARE]) //hw only due to instability on virtual env. reproduces rarely only on jenkins env though
    def "Intentional flow reroute is not causing any packet loss"() {
        given: "An unmetered flow going through a long not preferable path(reroute potential)"
        //will be available on virtual as soon as we get the latest iperf installed in lab-service images
        assumeTrue(topology.activeTraffGens.size() >= 2,
"There should be at least two active traffgens for test execution")

        def src = topology.activeTraffGens[0].switchConnected
        def dst = topology.activeTraffGens[1].switchConnected
        //first adjust costs to use the longest possible path between switches
        List<List<PathNode>> allPaths = database.getPaths(src.dpId, dst.dpId)*.path
        def longestPath = allPaths.max { it.size() }
        def changedIsls = allPaths.findAll { it != longestPath }
                                  .collect { pathHelper.makePathMorePreferable(longestPath, it) }.findAll()
        //and create the flow that uses the long path
        def flow = flowHelper.randomFlow(src, dst)
        flow.maximumBandwidth = 0
        flow.ignoreBandwidth = true
        flowHelper.addFlow(flow)
        assert pathHelper.convert(northbound.getFlowPath(flow.id)) == longestPath
        //now make another long path more preferable, for reroute to rebuild the rules on other switches in the future
        northbound.updateLinkProps((changedIsls + changedIsls*.reversed)
                .collect { islUtils.toLinkProps(it, [cost: DEFAULT_COST.toString()]) })
        def potentialNewPath = allPaths.findAll { it != longestPath }.max { it.size() }
        allPaths.findAll { it != potentialNewPath }.each { pathHelper.makePathMorePreferable(potentialNewPath, it) }

        when: "Start traffic examination"
        def traffExam = traffExamProvider.get()
        def bw = 100000 // 100 Mbps
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(flow, bw)
        [exam.forward, exam.reverse].each { direction ->
            def resources = traffExam.startExam(direction, true)
            direction.setResources(resources)
        }

        and: "While traffic flow is active, request a flow reroute"
        [exam.forward, exam.reverse].each { assert !traffExam.isFinished(it) }
        def reroute = northbound.rerouteFlow(flow.id)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }

        then: "Flow is rerouted"
        reroute.rerouted
        reroute.path.path == potentialNewPath

        and: "Traffic examination result shows acceptable packet loss percentage"
        def examReports = [exam.forward, exam.reverse].collect { traffExam.waitExam(it) }
        examReports.each {
            //Minor packet loss is considered a measurement error and happens regardless of reroute
            if (profile == "virtual") { //due to instability on virtual env. reproduces rarely only on jenkins env
                assert it.consumerReport.lostPercent < 1.5
            } else {
                assert it.consumerReport.lostPercent < 1
            }
        }

        cleanup: "Remove the flow"
        flowHelper.deleteFlow(flow.id)
    }

    def "Able to reroute to a path with not enough bandwidth available in case ignoreBandwidth=true"() {
        given: "A flow with alternate paths available"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")
        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = 10000
        flow.ignoreBandwidth = true
        flowHelper.addFlow(flow)
        def currentPath = PathHelper.convert(northbound.getFlowPath(flow.id))

        when: "Make the current path less preferable than alternatives"
        def alternativePaths = switchPair.paths.findAll { it != currentPath }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentPath) }

        and: "Make all alternative paths to have not enough bandwidth to handle the flow"
        def currentIsls = pathHelper.getInvolvedIsls(currentPath)
        def newBw = flow.maximumBandwidth - 1
        def changedIsls = alternativePaths.collect { altPath ->
            def thinIsl = pathHelper.getInvolvedIsls(altPath).find {
                !currentIsls.contains(it) && !currentIsls.contains(it.reversed)
            }
            [thinIsl, thinIsl.reversed].each {
                database.updateIslMaxBandwidth(it, newBw)
                database.updateIslAvailableBandwidth(it, newBw)
            }
            thinIsl
        }

        and: "Init a reroute to a more preferable path"
        def rerouteResponse = northbound.rerouteFlow(flow.id)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }

        then: "The flow is rerouted because ignoreBandwidth=true"
        int seqId = 0

        rerouteResponse.rerouted
        rerouteResponse.path.path != currentPath
        rerouteResponse.path.path.each { assert it.seqId == seqId++ }

        def updatedPath = PathHelper.convert(northbound.getFlowPath(flow.id))
        updatedPath != currentPath

        and: "Available bandwidth was not changed while rerouting due to ignoreBandwidth=true"
        def allLinks = northbound.getAllLinks()
        changedIsls.each {
            islUtils.getIslInfo(allLinks, it).each {
                assert it.value.availableBandwidth == newBw
            }
        }

        and: "Remove the flow, restore the bandwidth on ISLs, reset costs"
        flowHelper.deleteFlow(flow.id)
        changedIsls.each {
            database.resetIslBandwidth(it)
            database.resetIslBandwidth(it.reversed)
        }
    }

    @Tags(HARDWARE)
    def "Intentional flow reroute with VXLAN encapsulation is not causing any packet loss"() {
        given: "A vxlan flow"
        def allTraffgenSwitchIds = topology.activeTraffGens*.switchConnected.findAll {
            northbound.getSwitchProperties(it.dpId).supportedTransitEncapsulation
                    .contains(FlowEncapsulationType.VXLAN.toString().toLowerCase())
        }*.dpId ?: assumeTrue(false, "Should be at least two active traffgens connected to NoviFlow switches")
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { swP ->
            allTraffgenSwitchIds.contains(swP.src.dpId) && allTraffgenSwitchIds.contains(swP.dst.dpId) &&
                    swP.paths.findAll { path ->
                        pathHelper.getInvolvedSwitches(path).every {
                            northbound.getSwitchProperties(it.dpId).supportedTransitEncapsulation
                                    .contains(FlowEncapsulationType.VXLAN.toString().toLowerCase())
                        }
                    }.size() > 1
        } ?: assumeTrue(false, "Unable to find required switches/paths in topology")
        def availablePaths = switchPair.paths.findAll { pathHelper.getInvolvedSwitches(it).find { it.noviflow }}

        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = 0
        flow.ignoreBandwidth = true
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        flowHelper.addFlow(flow)
        def altPaths = availablePaths.findAll { it != pathHelper.convert(northbound.getFlowPath(flow.id)) }
        def potentialNewPath = altPaths[0]
        availablePaths.findAll { it != potentialNewPath }.each { pathHelper.makePathMorePreferable(potentialNewPath, it) }

        when: "Start traffic examination"
        def traffExam = traffExamProvider.get()
        def bw = 100000 // 100 Mbps
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(flow, bw, 30)
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction, true)
                direction.setResources(resources)
            }
        }

        and: "While traffic flow is active, request a flow reroute"
        [exam.forward, exam.reverse].each { assert !traffExam.isFinished(it) }
        def reroute = northbound.rerouteFlow(flow.id)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }

        then: "Flow is rerouted"
        reroute.rerouted
        reroute.path.path == potentialNewPath

        and: "Traffic examination result shows acceptable packet loss percentage"
        def examReports = [exam.forward, exam.reverse].collect { traffExam.waitExam(it) }
        examReports.each {
            //Minor packet loss is considered a measurement error and happens regardless of reroute
            assert it.consumerReport.lostPercent < 1
        }

        and: "Remove the flow"
        flowHelper.deleteFlow(flow.id)
    }

    def cleanup() {
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }
}
