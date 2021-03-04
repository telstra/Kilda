package org.openkilda.functionaltests.spec.flows

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.CREATE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.CREATE_SUCCESS
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.UPDATE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.UPDATE_SUCCESS
import static org.openkilda.testing.Constants.NON_EXISTENT_FLOW_ID

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.messaging.payload.history.FlowHistoryEntry
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.PathComputationStrategy
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import org.springframework.beans.factory.annotation.Value
import spock.lang.Narrative
import spock.lang.Shared

import java.util.concurrent.TimeUnit

@Narrative("""Verify that history records are created for the create/update actions.
History record is created in case the create/update action is completed successfully.""")
@Tags([LOW_PRIORITY])
class FlowHistorySpec extends HealthCheckSpecification {

    @Shared
    Long timestampBefore

    def setupSpec() {
        timestampBefore = System.currentTimeSeconds() - 5
    }

    @Tidy
    def "History records are created for the create/update actions using custom timeline"() {
        when: "Create a flow"
        assumeTrue(useMultitable, "Multi table is not enabled in kilda configuration")
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        //set non default values
        flow.ignoreBandwidth = true
        flow.periodicPings = true
        flow.allocateProtectedPath = true
        flow.source.innerVlanId = flow.destination.vlanId
        flow.destination.innerVlanId = flow.source.vlanId
        flow.encapsulationType = FlowEncapsulationType.TRANSIT_VLAN
        flow.pathComputationStrategy = PathComputationStrategy.LATENCY
        flow.maxLatency = 12345678
        flowHelper.addFlow(flow)

        then: "History record is created"
        Long timestampAfterCreate = System.currentTimeSeconds()
        def flowHistory = northbound.getFlowHistory(flow.id, timestampBefore, timestampAfterCreate)
        assert flowHistory.size() == 1
        checkHistoryCreateV1Action(flowHistory[0], flow.id)

        and: "Flow history contains all flow properties in the dump section"
        with(flowHistory[0].dumps[0]) { dump ->
            dump.type == "stateAfter"
            dump.bandwidth == flow.maximumBandwidth
            dump.ignoreBandwidth == flow.ignoreBandwidth
            dump.forwardCookie > 0
            dump.reverseCookie > 0
            dump.sourceSwitch == flow.source.datapath.toString()
            dump.destinationSwitch == flow.destination.datapath.toString()
            dump.sourcePort == flow.source.portNumber
            dump.destinationPort == flow.destination.portNumber
            dump.sourceVlan == flow.source.vlanId
            dump.destinationVlan == flow.destination.vlanId
            dump.forwardMeterId > 0
            dump.forwardStatus == "IN_PROGRESS" // issue 3038
            dump.reverseStatus == "IN_PROGRESS"
            dump.reverseMeterId > 0
            dump.sourceInnerVlan == flow.source.innerVlanId
            dump.destinationInnerVlan == flow.destination.innerVlanId
            dump.allocateProtectedPath == flow.allocateProtectedPath
            dump.encapsulationType.toString() == flow.encapsulationType
            dump.pinned == flow.pinned
            dump.pathComputationStrategy.toString() == flow.pathComputationStrategy
            dump.periodicPings == flow.periodicPings
            dump.maxLatency == flow.maxLatency * 1000000
        }

        when: "Update the created flow"
        flowHelper.updateFlow(flow.id, flow.tap { it.description = it.description + "updated" })

        then: "History record is created after updating the flow"
        Long timestampAfterUpdate = System.currentTimeSeconds()
        def flowHistory1 = northbound.getFlowHistory(flow.id, timestampBefore, timestampAfterUpdate)
        assert flowHistory1.size() == 2
        checkHistoryUpdateAction(flowHistory1[1], flow.id)

        while((System.currentTimeSeconds() - timestampAfterUpdate) < 1) {
            TimeUnit.MILLISECONDS.sleep(100);
        }

        when: "Delete the updated flow"
        def deleteResponse = flowHelper.deleteFlow(flow.id)

        then: "History is still available for the deleted flow"
        def flowHistory3 = northbound.getFlowHistory(flow.id, timestampBefore, timestampAfterUpdate)
        assert flowHistory3.size() == 2
        checkHistoryDeleteAction(flowHistory3, flow.id)

        cleanup:
        !deleteResponse && flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    @Tags(SMOKE)
    def "History records are created for the create/update actions using custom timeline (v2)"() {
        when: "Create a flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        flowHelper.addFlow(flow)

        then: "History record is created"
        Long timestampAfterCreate = System.currentTimeSeconds()
        verifyAll(northbound.getFlowHistory(flow.id, timestampBefore, timestampAfterCreate)) { flowH ->
            flowH.size() == 1
            checkHistoryCreateV1Action(flowH[0], flow.id)
        }

        when: "Update the created flow"
        def flowInfo = northbound.getFlow(flow.id)
        flowHelper.updateFlow(flowInfo.id, flowInfo.tap { it.description = it.description + "updated" })

        then: "History record is created after updating the flow"
        Long timestampAfterUpdate = System.currentTimeSeconds()
        verifyAll(northbound.getFlowHistory(flow.id, timestampBefore, timestampAfterUpdate)){ flowH ->
            flowH.size() == 2
            checkHistoryUpdateAction(flowH[1], flow.id)
        }

        while((System.currentTimeSeconds() - timestampAfterUpdate) < 1) {
            TimeUnit.MILLISECONDS.sleep(100);
        }

        when: "Delete the updated flow"
        def deleteResponse = flowHelper.deleteFlow(flow.id)

        then: "History is still available for the deleted flow"
        northbound.getFlowHistory(flow.id, timestampBefore, timestampAfterUpdate).size() == 2

        cleanup:
        !deleteResponse && flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "History records are created for the create/update actions using default timeline"() {
        when: "Create a flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        flowHelper.addFlow(flow)

        then: "History record is created"
        def flowHistory = northbound.getFlowHistory(flow.id)
        assert flowHistory.size() == 1
        checkHistoryCreateV1Action(flowHistory[0], flow.id)

        when: "Update the created flow"
        flowHelper.updateFlow(flow.id, flow.tap { it.description = it.description + "updated" })

        then: "History record is created after updating the flow"
        def flowHistory1 = northbound.getFlowHistory(flow.id)
        assert flowHistory1.size() == 2
        checkHistoryUpdateAction(flowHistory1[1], flow.id)

        when: "Delete the updated flow"
        def deleteResponse = flowHelper.deleteFlow(flow.id)

        then: "History is still available for the deleted flow"
        def flowHistory3 = northbound.getFlowHistory(flow.id)
        assert flowHistory3.size() == 3
        checkHistoryDeleteAction(flowHistory3, flow.id)

        cleanup:
        !deleteResponse && flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "History should not be returned in case timeline is incorrect (timeBefore > timeAfter)"() {
        when: "Create a flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        flowHelper.addFlow(flow)

        then: "History record is created"
        Long timestampAfterCreate = System.currentTimeSeconds()
        def flowHistory = northbound.getFlowHistory(flow.id, timestampBefore, timestampAfterCreate)
        assert flowHistory.size() == 1
        checkHistoryCreateV1Action(flowHistory[0], flow.id)

        when: "Try to get history for incorrect timeline"
        def flowH = northbound.getFlowHistory(flow.id, timestampAfterCreate, timestampBefore)

        then: "History record is NOT returned"
        flowH.isEmpty()

        cleanup: "Restore default state(remove created flow)"
        flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "History should not be returned in case flow was never created"() {
        when: "Try to get history for incorrect flowId"
        def flowHistory = northbound.getFlowHistory(NON_EXISTENT_FLOW_ID)

        then: "History record is NOT returned"
        flowHistory.isEmpty()
    }

    void checkHistoryCreateV1Action(FlowHistoryEntry flowHistory, String flowId) {
        assert flowHistory.action == CREATE_ACTION
        assert flowHistory.payload.action[-1] == CREATE_SUCCESS
        checkHistoryCommonStuff(flowHistory, flowId)
    }

    void checkHistoryUpdateAction(FlowHistoryEntry flowHistory, String flowId) {
        assert flowHistory.action == UPDATE_ACTION
        assert flowHistory.payload.action[-1] == UPDATE_SUCCESS
        checkHistoryCommonStuff(flowHistory, flowId)
    }

    void checkHistoryCommonStuff(FlowHistoryEntry flowHistory, String flowId) {
        assert flowHistory.flowId == flowId
        assert flowHistory.taskId
    }

    /** We pass latest timestamp when changes were done.
     * Just for getting all records from history */
    void checkHistoryDeleteAction(List<FlowHistoryEntry> flowHistory, String flowId) {
        checkHistoryCreateV1Action(flowHistory[0], flowId)
        checkHistoryUpdateAction(flowHistory[1], flowId)
    }
}
