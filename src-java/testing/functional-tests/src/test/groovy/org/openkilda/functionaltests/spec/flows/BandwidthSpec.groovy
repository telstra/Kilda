package org.openkilda.functionaltests.spec.flows

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslInfoData
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState

import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative

@Narrative("Verify that ISL's bandwidth behaves consistently and does not allow any oversubscribtions etc.")
@Tags([LOW_PRIORITY])
class BandwidthSpec extends HealthCheckSpecification {

    @Tags(SMOKE)
    def "Available bandwidth on ISLs changes respectively when creating/updating/deleting a flow"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getNotNeighboringSwitchPair()

        when: "Create a flow with a valid bandwidth"
        def linksBeforeFlowCreate = northbound.getAllLinks()
        def maximumBandwidth = 1000

        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = maximumBandwidth
        flowHelper.addFlow(flow)
        assert northbound.getFlow(flow.id).maximumBandwidth == maximumBandwidth

        then: "Available bandwidth on ISLs is changed in accordance with flow maximum bandwidth"
        def linksAfterFlowCreate = northbound.getAllLinks()
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.id))
        checkBandwidth(flowPath, linksBeforeFlowCreate, linksAfterFlowCreate, -maximumBandwidth)

        when: "Update the flow with a valid bandwidth"
        def maximumBandwidthUpdated = 2000

        flow.maximumBandwidth = maximumBandwidthUpdated
        northbound.updateFlow(flow.id, flow)

        then: "The flow is successfully updated and has 'Up' status"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }
        northbound.getFlow(flow.id).maximumBandwidth == maximumBandwidthUpdated

        and: "Available bandwidth on ISLs is changed in accordance with new flow maximum bandwidth"
        def linksBeforeFlowUpdate = linksAfterFlowCreate
        def linksAfterFlowUpdate = northbound.getAllLinks()
        def flowPathAfterUpdate = PathHelper.convert(northbound.getFlowPath(flow.id))

        flowPathAfterUpdate == flowPath
        checkBandwidth(flowPathAfterUpdate, linksBeforeFlowUpdate, linksAfterFlowUpdate,
                maximumBandwidth - maximumBandwidthUpdated)

        when: "Delete the flow"
        flowHelper.deleteFlow(flow.id)

        then: "Available bandwidth on ISLs is changed to the initial value before flow creation"
        def linksAfterFlowDelete = northbound.getAllLinks()
        checkBandwidth(flowPathAfterUpdate, linksBeforeFlowCreate, linksAfterFlowDelete)
    }

    def "Longer path is chosen in case of not enough available bandwidth on a shorter path"() {
        given: "Two active switches with two possible flow paths at least"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")

        // Make the first path more preferable than others.
        switchPair.paths[1..-1].each { pathHelper.makePathMorePreferable(switchPair.paths.first(), it) }

        // Get min available bandwidth on the preferable path.
        def involvedBandwidths = []
        def allLinks = northbound.getAllLinks()
        pathHelper.getInvolvedIsls(switchPair.paths.first()).each {
            involvedBandwidths.add(islUtils.getIslInfo(allLinks, it).get().availableBandwidth)
        }
        def minAvailableBandwidth = involvedBandwidths.min()

        when: "Create a flow to reduce available bandwidth on links of the expected preferable path"
        def flow1 = flowHelper.randomFlow(switchPair)
        flow1.maximumBandwidth = minAvailableBandwidth - 100
        flowHelper.addFlow(flow1)
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.id))

        then: "The flow is really built through the expected preferable path"
        flow1Path == switchPair.paths.first()

        when: "Create another flow. One path is shorter but available bandwidth is not enough, another path is longer"
        def flow2 = flowHelper.randomFlow(switchPair, false, [flow1])
        flow2.maximumBandwidth = 101
        flowHelper.addFlow(flow2)

        then: "The flow is built through longer path where available bandwidth is enough"
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow2.id))
        pathHelper.getCost(flow2Path) > pathHelper.getCost(flow1Path)

        and: "Delete created flows"
        [flow1.id, flow2.id].each { flowHelper.deleteFlow(it) }
    }

    @Tidy
    def "Unable to exceed bandwidth limit on ISL when creating a flow"() {
        given: "Two active switches"
        def switchPair = topologyHelper.getNeighboringSwitchPair()

        when: "Create a flow with a bandwidth that exceeds available bandwidth on ISL"
        def involvedBandwidths = []
        switchPair.paths.each { path ->
            pathHelper.getInvolvedIsls(path).each { link ->
                involvedBandwidths.add(islUtils.getIslInfo(link).get().availableBandwidth)
            }
        }

        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = involvedBandwidths.max() + 1
        northbound.addFlow(flow)

        then: "The flow is not created because flow path should not be found"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404

        cleanup:
        !exc && flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "Unable to exceed bandwidth limit on ISL when updating a flow"() {
        given: "Two active switches"
        def switchPair = topologyHelper.getNeighboringSwitchPair()

        when: "Create a flow with a valid bandwidth"
        def maximumBandwidth = 1000

        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = maximumBandwidth
        flowHelper.addFlow(flow)
        assert northbound.getFlow(flow.id).maximumBandwidth == maximumBandwidth

        and: "Update the flow with a bandwidth that exceeds available bandwidth on ISL"
        List<Long> involvedBandwidths = []
        switchPair.paths.each { path ->
            pathHelper.getInvolvedIsls(path).each { link ->
                involvedBandwidths.add(islUtils.getIslInfo(link).get().availableBandwidth)
            }
        }

        flow.maximumBandwidth += involvedBandwidths.max() + 1
        northbound.updateFlow(flow.id, flow)

        then: "The flow is not updated because flow path should not be found"
        def e = thrown(HttpClientErrorException)
        e.rawStatusCode == 404

        cleanup:
        flowHelper.deleteFlow(flow.id)
    }

    def "Able to exceed bandwidth limit on ISL when creating/updating a flow with ignore_bandwidth=true"() {
        given: "Two active switches"
        def switchPair = topologyHelper.getNeighboringSwitchPair()

        when: "Create a flow with a bandwidth that exceeds available bandwidth on ISL (ignore_bandwidth=true)"
        def linksBeforeFlowCreate = northbound.getAllLinks()
        def flow = flowHelper.randomFlow(switchPair)
        long maxBandwidth = northbound.getAllLinks()*.availableBandwidth.max()
        flow.maximumBandwidth = maxBandwidth + 1
        flow.ignoreBandwidth = true
        /*This creates a 40G+ flow, which is invalid for Centecs (due to too high meter rate). Ignoring this issue,
        since we are focused on proper path computation and link bw change, not the meter requirements, thus not
        using flowHelper.addFlow in order not to validate successful rules installation in this case*/
        northbound.addFlow(flow)
        Wrappers.wait(WAIT_OFFSET) { northbound.getFlowStatus(flow.id).status == FlowState.UP }
        assert northbound.getFlow(flow.id).maximumBandwidth == flow.maximumBandwidth

        then: "Available bandwidth on ISLs is not changed in accordance with flow maximum bandwidth"
        def linksAfterFlowCreate = northbound.getAllLinks()
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.id))
        checkBandwidth(flowPath, linksBeforeFlowCreate, linksAfterFlowCreate)

        when: "Update the flow with a bandwidth that exceeds available bandwidth on ISL (ignore_bandwidth = true)"
        flow.maximumBandwidth = maxBandwidth + 2
        northbound.updateFlow(flow.id, flow)

        then: "The flow is successfully updated and has 'Up' status"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }
        northbound.getFlow(flow.id).maximumBandwidth == flow.maximumBandwidth

        and: "Available bandwidth on ISLs is not changed in accordance with new flow maximum bandwidth"
        def linksAfterFlowUpdate = northbound.getAllLinks()
        def flowPathAfterUpdate = PathHelper.convert(northbound.getFlowPath(flow.id))

        flowPathAfterUpdate == flowPath
        checkBandwidth(flowPathAfterUpdate, linksBeforeFlowCreate, linksAfterFlowUpdate)

        and: "Delete the flow"
        flowHelper.deleteFlow(flow.id)
    }

    def "Able to update bandwidth to maximum link speed without using alternate links"() {
        given: "Two active neighboring switches"
        def switchPair = topologyHelper.getNeighboringSwitchPair()

        // We need to handle the case when there are parallel links between chosen switches. So we make all parallel
        // links except the first link not preferable to avoid flow reroute when updating the flow.
        List<List<PathNode>> parallelPaths = switchPair.paths.findAll { it.size() == 2 }
        if (parallelPaths.size() > 1) {
            parallelPaths[1..-1].each { pathHelper.makePathMorePreferable(parallelPaths.first(), it) }
        }

        when: "Create a flow with a valid small bandwidth"
        def maximumBandwidth = 1000

        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = maximumBandwidth
        flowHelper.addFlow(flow)
        assert northbound.getFlow(flow.id).maximumBandwidth == maximumBandwidth

        then: "Only one link is involved in flow path"
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.id))
        def involvedIsls = pathHelper.getInvolvedIsls(flowPath)
        involvedIsls.size() == 1
        involvedIsls.first() == pathHelper.getInvolvedIsls(parallelPaths.first()).first()

        when: "Update flow bandwidth to maximum link speed"
        def linkSpeed = islUtils.getIslInfo(involvedIsls.first()).get().speed
        flow.maximumBandwidth = linkSpeed
        northbound.updateFlow(flow.id, flow)

        then: "The flow is successfully updated and has 'Up' status"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }
        northbound.getFlow(flow.id).maximumBandwidth == linkSpeed

        and: "The same path is used by updated flow"
        PathHelper.convert(northbound.getFlowPath(flow.id)) == flowPath

        and: "Delete the flow"
        flowHelper.deleteFlow(flow.id)
    }

    def cleanup() {
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }

    private def checkBandwidth(List<PathNode> flowPath, List<IslInfoData> linksBefore, List<IslInfoData> linksAfter,
                               long offset = 0) {
        pathHelper.getInvolvedIsls(flowPath).each { link ->
            [link, link.reversed].each {
                def bwBefore = islUtils.getIslInfo(linksBefore, it).get().availableBandwidth
                def bwAfter = islUtils.getIslInfo(linksAfter, it).get().availableBandwidth
                assert bwAfter == bwBefore + offset
            }
        }
    }
}
