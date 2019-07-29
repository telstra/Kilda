package org.openkilda.functionaltests.spec.switches

import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.VIRTUAL
import static org.openkilda.testing.Constants.DEFAULT_COST
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.messaging.payload.flow.FlowState

import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore

class SwitchMaintenanceSpec extends HealthCheckSpecification {

    @Tidy
    @Tags(SMOKE)
    def "Maintenance mode can be set/unset for a particular switch"() {
        given: "An active switch"
        def sw = topology.activeSwitches.first()

        when: "Set maintenance mode for the switch"
        def setMaintenance = northbound.setSwitchMaintenance(sw.dpId, true, false)

        then: "Maintenance flag for the switch is really set"
        setMaintenance.underMaintenance
        northbound.getSwitch(sw.dpId).underMaintenance

        and: "Maintenance flag for all ISLs going through the switch is set as well"
        northbound.getAllLinks().findAll { sw.dpId in [it.source, it.destination]*.switchId }.each {
            assert it.underMaintenance
        }

        and: "Cost for ISLs is not changed"
        topology.islsForActiveSwitches.findAll { sw.dpId in [it.srcSwitch, it.dstSwitch]*.dpId }.each {
            assert database.getIslCost(it) == DEFAULT_COST
            assert database.getIslCost(it.reversed) == DEFAULT_COST
        }

        when: "Unset maintenance mode from the switch"
        def unsetMaintenance = northbound.setSwitchMaintenance(sw.dpId, false, false)

        then: "Maintenance flag for the switch is really unset"
        !unsetMaintenance.underMaintenance
        !northbound.getSwitch(sw.dpId).underMaintenance

        and: "Maintenance flag for all ISLs going through the switch is unset as well"
        northbound.getAllLinks().findAll { sw.dpId in [it.source, it.destination]*.switchId }.each {
            assert !it.underMaintenance
        }

        and: "Cost for ISLs is not changed"
        topology.islsForActiveSwitches.findAll { sw.dpId in [it.srcSwitch, it.dstSwitch]*.dpId }.each {
            assert database.getIslCost(it) == DEFAULT_COST
            assert database.getIslCost(it.reversed) == DEFAULT_COST
        }

        cleanup:
        setMaintenance && !unsetMaintenance && northbound.setSwitchMaintenance(sw.dpId, false, false)
    }

    @Ignore
    @Tidy
    @Tags(VIRTUAL) //TODO (andriidovhan) select two path with different transit switches, then set the SMOKE tag
    def "Flows can be evacuated (rerouted) from a particular switch when setting maintenance mode for it"() {
        given: "Two active not neighboring switches with two possible paths at least"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue("No suiting switches found", false)

        and: "Create a couple of flows going through these switches"
        def flow1 = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow1)
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.flowId))

        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1])
        flowHelperV2.addFlow(flow2)
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow2.flowId))

        assert flow1Path == flow2Path

        when: "Set maintenance mode without flows evacuation flag for some intermediate switch involved in flow paths"
        def sw = pathHelper.getInvolvedSwitches(flow1Path)[1]
        northbound.setSwitchMaintenance(sw.dpId, true, false)

        then: "Flows are not evacuated (rerouted) and have the same paths"
        PathHelper.convert(northbound.getFlowPath(flow1.flowId)) == flow1Path
        PathHelper.convert(northbound.getFlowPath(flow2.flowId)) == flow2Path

        when: "Set maintenance mode again with flows evacuation flag for the same switch"
        northbound.setSwitchMaintenance(sw.dpId, true, true)

        then: "Flows are evacuated (rerouted)"
        def flow1PathUpdated, flow2PathUpdated
        Wrappers.wait(PATH_INSTALLATION_TIME) {
            [flow1, flow2].each { assert northboundV2.getFlowStatus(it.flowId).status == FlowState.UP }

            flow1PathUpdated = PathHelper.convert(northbound.getFlowPath(flow1.flowId))
            flow2PathUpdated = PathHelper.convert(northbound.getFlowPath(flow2.flowId))

            assert flow1PathUpdated != flow1Path
            assert flow2PathUpdated != flow2Path
        }

        and: "Switch under maintenance is not involved in new flow paths"
        !(sw in pathHelper.getInvolvedSwitches(flow1PathUpdated))
        !(sw in pathHelper.getInvolvedSwitches(flow2PathUpdated))

        cleanup: "Delete flows and unset maintenance mode"
        [flow1, flow2].each { flowHelperV2.deleteFlow(it.flowId) }
        northbound.setSwitchMaintenance(sw.dpId, false, false)
    }

    def "Link discovered by a switch under maintenance is marked as maintained"() {
        given: "An active link"
        def isl = topology.islsForActiveSwitches.first()

        and: "Bring port down on the switch to fail the link"
        antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET) { assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED }

        and: "Delete the link"
        northbound.deleteLink(islUtils.toLinkParameters(isl))
        assert !islUtils.getIslInfo(isl)
        assert !islUtils.getIslInfo(isl.reversed)

        when: "Set maintenance mode for the switch"
        northbound.setSwitchMaintenance(isl.srcSwitch.dpId, true, false)

        and: "Bring port up to discover the deleted link"
        antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)

        then: "The link is discovered and marked as maintained"
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            def islInfo = islUtils.getIslInfo(links, isl).get()
            def reverseIslInfo = islUtils.getIslInfo(links, isl.reversed).get()

            [islInfo, reverseIslInfo].each {
                assert it.state == IslChangeType.DISCOVERED
                assert it.underMaintenance
            }
        }

        and: "Unset maintenance mode and reset costs"
        northbound.setSwitchMaintenance(isl.srcSwitch.dpId, false, false)
        def links = northbound.getAllLinks()
        !islUtils.getIslInfo(links, isl).get().underMaintenance
        !islUtils.getIslInfo(links, isl.reversed).get().underMaintenance
        database.resetCosts()
    }

    // That logic will be reworked to fit new use cases
    @Ignore("Not implemented in new discovery-topology")
    @Tags(VIRTUAL)
    def "System is correctly handling actions performing on a maintained switch disconnected from the controller"() {
        given: "An active switch under maintenance disconnected from the controller"
        def sw = topology.activeSwitches.first()
        northbound.setSwitchMaintenance(sw.dpId, true, false)
        def blockData = lockKeeper.knockoutSwitch(sw, mgmtFlManager)
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET) {
            northbound.getAllLinks().findAll { sw.dpId in [it.source, it.destination]*.switchId }.each {
                assert it.state == IslChangeType.FAILED
            }
        }

        when: "Try to get switch info"
        def response = northbound.getSwitch(sw.dpId)

        then: "Detailed switch info is returned"
        response.switchId == sw.dpId
        response.state == SwitchChangeType.ACTIVATED
        response.underMaintenance

        when: "Try to get switch rules"
        northbound.getSwitchRules(sw.dpId)

        then: "An error is received (404 code)"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        exc.responseBodyAsString.to(MessageError).errorMessage == "Switch $sw.dpId was not found"

        when: "Try to create a flow, using the switch"
        def flow = flowHelperV2.randomFlow(sw, topology.activeSwitches.last())
        northboundV2.addFlow(flow)

        then: "An error is received (404 code)"
        exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        exc.responseBodyAsString.to(MessageError).errorMessage ==
                "Could not create flow: Not enough bandwidth found or path not found. Failed to find path with " +
                "requested bandwidth=$flow.maximumBandwidth: Switch $sw.dpId doesn't have links with enough bandwidth"

        and: "Connect the switch back to the controller and unset maintenance mode"
        lockKeeper.reviveSwitch(sw, blockData)
        northbound.setSwitchMaintenance(sw.dpId, false, false)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
    }
}
