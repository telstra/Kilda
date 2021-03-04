package org.openkilda.functionaltests.spec.links

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.testing.Constants.DEFAULT_COST
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState

class LinkMaintenanceSpec extends HealthCheckSpecification {

    @Tags(SMOKE)
    def "Maintenance mode can be set/unset for a particular link"() {
        given: "An active link"
        def isl = topology.islsForActiveSwitches.first()

        when: "Set maintenance mode for the link"
        def response = northbound.setLinkMaintenance(islUtils.toLinkUnderMaintenance(isl, true, false))

        then: "Maintenance flag for forward and reverse ISLs is really set"
        response.each { assert it.underMaintenance }

        and: "Cost for ISLs is not changed"
        database.getIslCost(isl) == DEFAULT_COST
        database.getIslCost(isl.reversed) == DEFAULT_COST

        when: "Unset maintenance mode from the link"
        response = northbound.setLinkMaintenance(islUtils.toLinkUnderMaintenance(isl, false, false))

        then: "Maintenance flag for forward and reverse ISLs is really unset"
        response.each { assert !it.underMaintenance }

        and: "Cost for ISLs is changed to the default value"
        database.getIslCost(isl) == DEFAULT_COST
        database.getIslCost(isl.reversed) == DEFAULT_COST
    }

    def "Flows can be evacuated (rerouted) from a particular link when setting maintenance mode for it"() {
        given: "Two active not neighboring switches with two possible paths at least"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")

        and: "Create a couple of flows going through these switches"
        def flow1 = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow1)
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.flowId))

        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1])
        flowHelperV2.addFlow(flow2)
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow2.flowId))

        assert flow1Path == flow2Path

        when: "Set maintenance mode without flows evacuation flag for the first link involved in flow paths"
        def isl = pathHelper.getInvolvedIsls(flow1Path).first()
        northbound.setLinkMaintenance(islUtils.toLinkUnderMaintenance(isl, true, false))

        then: "Flows are not evacuated (rerouted) and have the same paths"
        PathHelper.convert(northbound.getFlowPath(flow1.flowId)) == flow1Path
        PathHelper.convert(northbound.getFlowPath(flow2.flowId)) == flow2Path

        when: "Set maintenance mode again with flows evacuation flag for the same link"
        northbound.setLinkMaintenance(islUtils.toLinkUnderMaintenance(isl, true, true))

        then: "Flows are evacuated (rerouted)"
        def flow1PathUpdated, flow2PathUpdated
        Wrappers.wait(PATH_INSTALLATION_TIME + WAIT_OFFSET) {
            [flow1, flow2].each { assert northboundV2.getFlowStatus(it.flowId).status == FlowState.UP }

            flow1PathUpdated = PathHelper.convert(northbound.getFlowPath(flow1.flowId))
            flow2PathUpdated = PathHelper.convert(northbound.getFlowPath(flow2.flowId))

            assert flow1PathUpdated != flow1Path
            assert flow2PathUpdated != flow2Path
        }

        and: "Link under maintenance is not involved in new flow paths"
        !(isl in pathHelper.getInvolvedIsls(flow1PathUpdated))
        !(isl in pathHelper.getInvolvedIsls(flow2PathUpdated))

        and: "Delete flows and unset maintenance mode"
        [flow1, flow2].each { flowHelperV2.deleteFlow(it.flowId) }
        northbound.setLinkMaintenance(islUtils.toLinkUnderMaintenance(isl, false, false))
    }

    def "Flows are rerouted to a path with link under maintenance when there are no other paths available"() {
        given: "Two active not neighboring switches with two possible paths at least"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")

        and: "Create a couple of flows going through these switches"
        def flow1 = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow1)
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.flowId))

        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1])
        flowHelperV2.addFlow(flow2)
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow2.flowId))

        assert flow1Path == flow2Path

        and: "Make only one alternative path available for both flows"
        def altPaths = switchPair.paths.findAll {
            it != flow1Path && it.first().portNo != flow1Path.first().portNo
        }.sort { it.size() }
        def availablePath = altPaths.first()

        List<PathNode> broughtDownPorts = []
        altPaths[1..-1].unique { it.first() }.findAll {
            it.first().portNo != availablePath.first().portNo
        }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(antiflapMin + WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        and: "Set maintenance mode for the first link involved in alternative path"
        def islUnderMaintenance = pathHelper.getInvolvedIsls(availablePath).first()
        northbound.setLinkMaintenance(islUtils.toLinkUnderMaintenance(islUnderMaintenance, true, false))

        when: "Force flows to reroute by bringing port down on the source switch"
        broughtDownPorts.add(flow1Path.first())
        antiflap.portDown(flow1Path.first().switchId, flow1Path.first().portNo)

        then: "Flows are rerouted to alternative path with link under maintenance"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET*2) {
            [flow1, flow2].each { assert northboundV2.getFlowStatus(it.flowId).status == FlowState.UP }

            def flow1PathUpdated = PathHelper.convert(northbound.getFlowPath(flow1.flowId))
            def flow2PathUpdated = PathHelper.convert(northbound.getFlowPath(flow2.flowId))

            assert flow1PathUpdated != flow1Path
            assert flow2PathUpdated != flow2Path

            assert islUnderMaintenance in pathHelper.getInvolvedIsls(flow1PathUpdated)
            assert islUnderMaintenance in pathHelper.getInvolvedIsls(flow2PathUpdated)
        }

        and: "Restore topology, delete flows, unset maintenance mode and reset costs"
        broughtDownPorts.each { antiflap.portUp(it.switchId, it.portNo) }
        [flow1, flow2].each { flowHelperV2.deleteFlow(it.flowId) }
        northbound.setLinkMaintenance(islUtils.toLinkUnderMaintenance(islUnderMaintenance, false, false))
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }
}
