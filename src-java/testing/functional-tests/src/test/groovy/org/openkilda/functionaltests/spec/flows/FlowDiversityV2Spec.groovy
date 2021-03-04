package org.openkilda.functionaltests.spec.flows

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.CREATE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.DELETE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.UPDATE_ACTION
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowPathPayload
import org.openkilda.model.SwitchId
import org.openkilda.northbound.dto.v2.flows.FlowResponseV2
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import org.springframework.beans.factory.annotation.Value
import spock.lang.Ignore
import spock.lang.Issue
import spock.lang.Narrative
import spock.lang.See

@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/solutions/pce-diverse-flows")
@Narrative("""
This test suite verifies the ability to create diverse flows in the system. Diverse flows are flows that should not 
overlap at all or the overlapping should be minimal. Such flows form a so-called diversity group. Ideally, the diversity 
group should not have flows with overlapping paths. But it depends on the cost of paths. The paths of flows from 
the same diversity group may overlap if the cost of each non-overlapping path is more than the cost of the overlapping 
path. The cost of paths for diverse flows is calculated in real time and consists of the following parameters: 

1. The cost of ISL involved in the flow path (taken from DB);
2. (diversity.switch.cost) * (the number of diverse flows going through this switch);
3. (diversity.isl.cost) * (the number of diverse flows going through this ISL). 

Refer to https://github.com/telstra/open-kilda/issues/1231 for more details.
""")
class FlowDiversityV2Spec extends HealthCheckSpecification {

    @Value('${diversity.isl.cost}')
    int diversityIslCost

    @Value('${diversity.switch.cost}')
    int diversitySwitchCost

    @Tidy
    @Tags(SMOKE)
    def "Able to create diverse flows"() {
        given: "Two active neighboring switches with three not overlapping paths at least"
        def switchPair = getSwitchPair(3)

        when: "Create three flows with diversity enabled"
        def flow1 = flowHelperV2.randomFlow(switchPair, false)
        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1]).tap { it.diverseFlowId = flow1.flowId }
        def flow3 = flowHelperV2.randomFlow(switchPair, false, [flow1, flow2]).tap { it.diverseFlowId = flow2.flowId }
        Map<SwitchId, FlowResponseV2> responseMap = [flow1, flow2, flow3].collectEntries{ [(it.flowId): flowHelperV2.addFlow(it)] }

        then: "Flow create response contains information about diverse flow"
        !responseMap[flow1.flowId].diverseWith
        responseMap[flow2.flowId].diverseWith.sort() == [flow1.flowId]
        responseMap[flow3.flowId].diverseWith.sort() == [flow1.flowId, flow2.flowId].sort()

        and: "All flows have diverse flow IDs in response"
        northboundV2.getFlow(flow1.flowId).diverseWith.sort() == [flow2.flowId, flow3.flowId].sort()
        northboundV2.getFlow(flow2.flowId).diverseWith.sort() == [flow1.flowId, flow3.flowId].sort()
        northboundV2.getFlow(flow3.flowId).diverseWith.sort() == [flow1.flowId, flow2.flowId].sort()

        and: "All flows have different paths"
        def allInvolvedIsls = [flow1, flow2, flow3].collectMany {
            pathHelper.getInvolvedIsls(PathHelper.convert(northbound.getFlowPath(it.flowId)))
        }
        allInvolvedIsls.unique(false) == allInvolvedIsls

        and: "Flows' histories contain 'groupId' information"
        [flow2, flow3].each {//flow1 had no diversity at the time of creation
            assert northbound.getFlowHistory(it.flowId).find { it.action == CREATE_ACTION }.dumps
                    .find { it.type == "stateAfter" }?.groupId
        }

        when: "Delete flows"
        [flow1, flow2, flow3].each { flowHelperV2.deleteFlow(it.flowId) }
        def flowsAreDeleted = true

        then: "Flows' histories contain 'groupId' information in 'delete' operation"
        [flow1, flow2, flow3].each {
            verifyAll(northbound.getFlowHistory(it.flowId).find { it.action == DELETE_ACTION }.dumps) {
                it.find { it.type == "stateBefore" }?.groupId
                !it.find { it.type == "stateAfter" }?.groupId
            }
        }

        cleanup:
        !flowsAreDeleted && [flow1, flow2, flow3].each { flowHelperV2.deleteFlow(it.flowId) }
    }

    @Tidy
    def "Able to update flows to become diverse"() {
        given: "Two active neighboring switches with three not overlapping paths at least"
        def switchPair = getSwitchPair(3)

        and: "Create three flows"
        def flow1 = flowHelperV2.randomFlow(switchPair, false)
        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1])
        def flow3 = flowHelperV2.randomFlow(switchPair, false, [flow1, flow2])
        [flow1, flow2, flow3].each { flowHelperV2.addFlow(it) }

        def (flow1Path, flow2Path, flow3Path) = [flow1, flow2, flow3].collect {
            PathHelper.convert(northbound.getFlowPath(it.flowId))
        }
        assert [flow1Path, flow2Path, flow3Path].toSet().size() == 1

        when: "Update the second flow to become diverse"
        FlowResponseV2 updateResponse = flowHelperV2.updateFlow(flow2.flowId,
                                                                flow2.tap { it.diverseFlowId = flow1.flowId })

        and: "Second flow's history contains 'groupId' information"
        verifyAll(northbound.getFlowHistory(flow2.flowId).find { it.action == UPDATE_ACTION }.dumps) {
            !it.find { it.type == "stateBefore" }?.groupId
            it.find { it.type == "stateAfter" }?.groupId
        }

        then: "Update response contains information about diverse flow"
        updateResponse.diverseWith.sort() == [flow1.flowId]

        and: "The flow became diverse and changed the path"
        def flow2PathUpdated = PathHelper.convert(northbound.getFlowPath(flow2.flowId))
        flow2PathUpdated != flow2Path

        and: "All flows except last one have the 'diverse_with' field"
        northboundV2.getFlow(flow1.flowId).diverseWith == [flow2.flowId].toSet()
        northboundV2.getFlow(flow2.flowId).diverseWith == [flow1.flowId].toSet()
        !northboundV2.getFlow(flow3.flowId).diverseWith

        when: "Update the third flow to become diverse"
        flowHelperV2.updateFlow(flow3.flowId, flow3.tap { it.diverseFlowId = flow2.flowId })

        then: "The flow became diverse and all flows have different paths"
        def flow3PathUpdated = PathHelper.convert(northbound.getFlowPath(flow3.flowId))
        [flow1Path, flow2PathUpdated, flow3PathUpdated].toSet().size() == 3

        def allInvolvedIsls = [flow1Path, flow2PathUpdated, flow3PathUpdated].collectMany {
            pathHelper.getInvolvedIsls(it)
        }
        allInvolvedIsls.unique(false) == allInvolvedIsls

        cleanup: "Delete flows"
        [flow1, flow2, flow3].each { flowHelperV2.deleteFlow(it.flowId) }
    }

    @Tidy
    @Tags(SMOKE)
    def "Able to update flows to become not diverse"() {
        given: "Two active neighboring switches with three not overlapping paths at least"
        def switchPair = getSwitchPair(3)

        and: "Create three flows with diversity enabled"
        def flow1 = flowHelperV2.randomFlow(switchPair, false)
        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1]).tap { it.diverseFlowId = flow1.flowId }
        def flow3 = flowHelperV2.randomFlow(switchPair, false, [flow1, flow2]).tap { it.diverseFlowId = flow2.flowId }
        [flow1, flow2, flow3].each { flowHelperV2.addFlow(it) }

        def (flow1Path, flow2Path, flow3Path) = [flow1, flow2, flow3].collect {
            PathHelper.convert(northbound.getFlowPath(it.flowId))
        }
        def allInvolvedIsls = [flow1Path, flow2Path, flow3Path].collectMany { pathHelper.getInvolvedIsls(it) }
        assert allInvolvedIsls.unique(false) == allInvolvedIsls

        and: "Flow1 path is the most preferable"
        switchPair.paths.findAll { it != flow1Path }
                .each { pathHelper.makePathMorePreferable(flow1Path, it) }

        when: "Update the second flow to become not diverse"
        flowHelperV2.updateFlow(flow2.flowId, flow2.tap { it.diverseFlowId = "" })

        then: "The flow became not diverse and rerouted to the more preferable path (path of the first flow)"
        def flow2PathUpdated = PathHelper.convert(northbound.getFlowPath(flow2.flowId))
        flow2PathUpdated != flow2Path
        flow2PathUpdated == flow1Path

        and: "The 'diverse_with' field is removed"
        !northboundV2.getFlow(flow2.flowId).diverseWith

        and: "The flow's history reflects the change of 'groupId' field"
        verifyAll(northbound.getFlowHistory(flow2.flowId).find { it.action == UPDATE_ACTION }.dumps) {
            //https://github.com/telstra/open-kilda/issues/3807
//            it.find { it.type == "stateBefore" }.groupId
            !it.find { it.type == "stateAfter" }.groupId
        }

        when: "Update the third flow to become not diverse"
        flowHelperV2.updateFlow(flow3.flowId, flow3.tap { it.diverseFlowId = "" })

        then: "The flow became not diverse and rerouted to the more preferable path (path of the first flow)"
        def flow3PathUpdated = PathHelper.convert(northbound.getFlowPath(flow3.flowId))
        flow3PathUpdated != flow3Path
        flow3PathUpdated == flow1Path

        and: "The 'diverse_with' field is removed"
        !northboundV2.getFlow(flow3.flowId).diverseWith

        cleanup: "Delete flows"
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        [flow1, flow2, flow3].each { flowHelperV2.deleteFlow(it.flowId) }
    }

    @Tidy
    @Tags(SMOKE)
    def "Diverse flows are built through the same path if there are no alternative paths available"() {
        given: "Two active neighboring switches with two not overlapping paths at least"
        def switchPair = getSwitchPair(2)

        and: "Create a flow going through these switches"
        def flow1 = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow1)
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.flowId))

        and: "Make all alternative paths unavailable (bring ports down on the source switch)"
        List<PathNode> broughtDownPorts = []
        switchPair.paths.findAll { it != flow1Path }.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        when: "Create the second flow with diversity enabled"
        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1]).tap { it.diverseFlowId = flow1.flowId }
        flowHelperV2.addFlow(flow2)
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow2.flowId))

        then: "The second flow is built through the same path as the first flow"
        flow2Path == flow1Path

        cleanup: "Restore topology, delete flows and reset costs"
        broughtDownPorts.each { antiflap.portUp(it.switchId, it.portNo) }
        [flow1, flow2].each { flowHelperV2.deleteFlow(it.flowId) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tidy
    @Tags(SMOKE)
    def "Links and switches get extra cost that is considered while calculating diverse flow paths"() {
        given: "Two active neighboring switches with three not overlapping paths at least"
        def switchPair = getSwitchPair(3)

        and: "Create a flow going through these switches"
        def flow1 = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow1)
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.flowId))

        and: "Make each alternative path less preferable than the first flow path"
        def altPaths = switchPair.paths
        altPaths.remove(flow1Path)

        def flow1PathCost = pathHelper.getCost(flow1Path) + diversityIslCost + diversitySwitchCost * 2
        altPaths.each { altPath ->
            def altPathCost = pathHelper.getCost(altPath) + diversitySwitchCost * 2
            int difference = flow1PathCost - altPathCost
            def firstAltPathIsl = pathHelper.getInvolvedIsls(altPath)[0]
            int firstAltPathIslCost = database.getIslCost(firstAltPathIsl)
            northbound.updateLinkProps([islUtils.toLinkProps(firstAltPathIsl,
                    ["cost": (firstAltPathIslCost + Math.abs(difference) + 1).toString()])])
        }

        when: "Create the second flow with diversity enabled"
        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1]).tap { it.diverseFlowId = flow1.flowId }
        flowHelperV2.addFlow(flow2)
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow2.flowId))

        then: "The flow is built through the most preferable path (path of the first flow)"
        flow2Path == flow1Path

        when: "Create the third flow with diversity enabled"
        def flow3 = flowHelperV2.randomFlow(switchPair, false, [flow1, flow2]).tap {
            it.diverseFlowId = flow2.flowId
        }
        flowHelperV2.addFlow(flow3)
        def flow3Path = PathHelper.convert(northbound.getFlowPath(flow3.flowId))

        then: "The flow is built through one of alternative paths because they are preferable already"
        def involvedIsls = [flow2Path, flow3Path].collectMany { pathHelper.getInvolvedIsls(it) }
        flow3Path != flow2Path
        involvedIsls.unique(false) == involvedIsls

        cleanup: "Delete flows and link props"
        [flow1, flow2, flow3].each { flowHelperV2.deleteFlow(it.flowId) }
        northbound.deleteLinkProps(northbound.getAllLinkProps())
    }

    @Tidy
    def "Able to get flow paths with correct overlapping segments stats (casual flows)"() {
        given: "Two active neighboring switches with three not overlapping paths at least"
        def switchPair = getSwitchPair(3)

        and: "Create three flows with diversity enabled"
        def flow1 = flowHelperV2.randomFlow(switchPair, false)
        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1]).tap { it.diverseFlowId = flow1.flowId }
        def flow3 = flowHelperV2.randomFlow(switchPair, false, [flow1, flow2]).tap { it.diverseFlowId = flow2.flowId }
        [flow1, flow2, flow3].each { flowHelperV2.addFlow(it) }

        when: "Get flow path for all flows"
        def (flow1Path, flow2Path, flow3Path) = [flow1, flow2, flow3].collect { northbound.getFlowPath(it.flowId) }

        then: "Flow path response for all flows has correct overlapping segments stats"
        def flow2SwitchCount = pathHelper.getInvolvedSwitches(PathHelper.convert(flow2Path)).size()
        def flow3SwitchCount = pathHelper.getInvolvedSwitches(PathHelper.convert(flow3Path)).size()
        def expectedValuesMap = [
                diverseGroup: [
                        (flow1.flowId): [islCount: 0, switchCount: 2, islPercent: 0, switchPercent: 100],
                        (flow2.flowId): [islCount     : 0, switchCount: 2, islPercent: 0,
                                     switchPercent: (2 * 100 / flow2SwitchCount).toInteger()],
                        (flow3.flowId): [islCount     : 0, switchCount: 2, islPercent: 0,
                                     switchPercent: (2 * 100 / flow3SwitchCount).toInteger()]
                ],
                otherFlows  : [
                        (flow1.flowId): [
                                (flow2.flowId): [islCount: 0, switchCount: 2, islPercent: 0, switchPercent: 100],
                                (flow3.flowId): [islCount: 0, switchCount: 2, islPercent: 0, switchPercent: 100]
                        ],
                        (flow2.flowId): [
                                (flow1.flowId): [islCount     : 0, switchCount: 2, islPercent: 0,
                                             switchPercent: (2 * 100 / flow2SwitchCount).toInteger()],
                                (flow3.flowId): [islCount     : 0, switchCount: 2, islPercent: 0,
                                             switchPercent: (2 * 100 / flow2SwitchCount).toInteger()]
                        ],
                        (flow3.flowId): [
                                (flow1.flowId): [islCount     : 0, switchCount: 2, islPercent: 0,
                                             switchPercent: (2 * 100 / flow3SwitchCount).toInteger()],
                                (flow2.flowId): [islCount     : 0, switchCount: 2, islPercent: 0,
                                             switchPercent: (2 * 100 / flow3SwitchCount).toInteger()]
                        ]
                ]
        ]
        verifySegmentsStats([flow1Path, flow2Path, flow3Path], expectedValuesMap)

        cleanup: "Delete flows"
        [flow1, flow2, flow3].each { flowHelperV2.deleteFlow(it.flowId) }
    }

    @Issue("https://github.com/telstra/open-kilda/issues/2072")
    @Ignore("Functionality is currently not supported yet")
    def "Able to get flow paths with correct overlapping segments stats (single-switch flows)"() {
        given: "Two active switches"
        def (Switch sw1, Switch sw2) = topology.getActiveSwitches()[0..1]

        and: "Create two single-switch flows with diversity enabled on the first switch"
        def flow1 = flowHelperV2.singleSwitchFlow(sw1, false)
        def flow2 = flowHelperV2.singleSwitchFlow(sw1, false, [flow1]).tap { it.diverseFlowId = flow1.flowId }
        [flow1, flow2].each { flowHelperV2.addFlow(it) }

        and: "Create the third single-switch flow with diversity enabled on the second switch"
        def flow3 = flowHelperV2.singleSwitchFlow(sw2, false).tap { it.diverseFlowId = flow2.flowId }
        flowHelperV2.addFlow(flow3)

        when: "Get flow path for all flows"
        def (flow1Path, flow2Path, flow3Path) = [flow1, flow2, flow3].collect { northbound.getFlowPath(it.flowId) }

        then: "Flow path response for all flows has correct overlapping segments stats"
        def expectedValuesMap = [
                diverseGroup: [
                        (flow1.flowId): [islCount: 0, switchCount: 1, islPercent: 0, switchPercent: 100],
                        (flow2.flowId): [islCount: 0, switchCount: 1, islPercent: 0, switchPercent: 100],
                        (flow3.flowId): [islCount: 0, switchCount: 0, islPercent: 0, switchPercent: 0]
                ],
                otherFlows  : [
                        (flow1.flowId): [
                                (flow2.flowId): [islCount: 0, switchCount: 1, islPercent: 0, switchPercent: 100],
                                (flow3.flowId): [islCount: 0, switchCount: 0, islPercent: 0, switchPercent: 0]
                        ],
                        (flow2.flowId): [
                                (flow1.flowId): [islCount: 0, switchCount: 1, islPercent: 0, switchPercent: 100],
                                (flow3.flowId): [islCount: 0, switchCount: 0, islPercent: 0, switchPercent: 0]
                        ],
                        (flow3.flowId): [
                                (flow1.flowId): [islCount: 0, switchCount: 0, islPercent: 0, switchPercent: 0],
                                (flow2.flowId): [islCount: 0, switchCount: 0, islPercent: 0, switchPercent: 0]
                        ]
                ]
        ]
        verifySegmentsStats([flow1Path, flow2Path, flow3Path], expectedValuesMap)

        and: "Delete flows"
        [flow1, flow2, flow3].each { flowHelperV2.deleteFlow(it.flowId) }
    }

    @Issue("https://github.com/telstra/open-kilda/issues/2072")
    @Ignore("Functionality is currently not supported yet")
    def "Able to get flow paths with correct overlapping segments stats (casual + single-switch flows)"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getNotNeighboringSwitchPair()

        and: "Create a casual flow going through these switches"
        def flow1 = flowHelperV2.randomFlow(switchPair, false)
        flowHelperV2.addFlow(flow1)

        and: "Create a single-switch with diversity enabled on the source switch of the first flow"
        def flow2 = flowHelperV2.singleSwitchFlow(switchPair.src, false, [flow1]).tap { it.diverseFlowId = flow1.flowId }
        flow2 = flowHelperV2.addFlow(flow2)

        and: "Create a single-switch with diversity enabled on the destination switch of the first flow"
        def flow3 = flowHelperV2.singleSwitchFlow(switchPair.dst, false, [flow1]).tap { it.diverseFlowId = flow2.flowId }
        flowHelperV2.addFlow(flow3)

        when: "Get flow path for all flows"
        def (flow1Path, flow2Path, flow3Path) = [flow1, flow2, flow3].collect { northbound.getFlowPath(it.flowId) }

        then: "Flow path response for all flows has correct overlapping segments stats"
        def flow1SwitchCount = pathHelper.getInvolvedSwitches(PathHelper.convert(flow1Path)).size()
        def expectedValuesMap = [
                diverseGroup: [
                        (flow1.flowId): [islCount     : 0, switchCount: 2, islPercent: 0,
                                     switchPercent: (2 * 100 / flow1SwitchCount).toInteger()],
                        (flow2.flowId): [islCount: 0, switchCount: 1, islPercent: 0, switchPercent: 100],
                        (flow3.flowId): [islCount: 0, switchCount: 1, islPercent: 0, switchPercent: 100]
                ],
                otherFlows  : [
                        (flow1.flowId): [
                                (flow2.flowId): [islCount: 0, switchCount: 1, islPercent: 0, switchPercent: 100],
                                (flow3.flowId): [islCount: 0, switchCount: 1, islPercent: 0, switchPercent: 100]
                        ],
                        (flow2.flowId): [
                                (flow1.flowId): [islCount     : 0, switchCount: 1, islPercent: 0,
                                             switchPercent: (2 * 100 / flow1SwitchCount).toInteger()],
                                (flow3.flowId): [islCount: 0, switchCount: 0, islPercent: 0, switchPercent: 0]
                        ],
                        (flow3.flowId): [
                                (flow1.flowId): [islCount     : 0, switchCount: 1, islPercent: 0,
                                             switchPercent: (2 * 100 / flow1SwitchCount).toInteger()],
                                (flow2.flowId): [islCount: 0, switchCount: 0, islPercent: 0, switchPercent: 0]
                        ]
                ]
        ]
        verifySegmentsStats([flow1Path, flow2Path, flow3Path], expectedValuesMap)

        and: "Delete flows"
        [flow1, flow2, flow3].each { flowHelperV2.deleteFlow(it.flowId) }
    }

    SwitchPair getSwitchPair(minNotOverlappingPaths) {
        topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.collect { pathHelper.getInvolvedIsls(it) }.unique { a, b -> a.intersect(b) ? 0 : 1 }.size() >=
                    minNotOverlappingPaths
        } ?: assumeTrue(false, "No suiting switches found")
    }

    void verifySegmentsStats(List<FlowPathPayload> flowPaths, Map expectedValuesMap) {
        flowPaths.each { flow ->
            with(flow.diverseGroupPayload) { diverseGroup ->
                verifyAll(diverseGroup.overlappingSegments) {
                    islCount == expectedValuesMap["diverseGroup"][flow.id]["islCount"]
                    switchCount == expectedValuesMap["diverseGroup"][flow.id]["switchCount"]
                    islPercent == expectedValuesMap["diverseGroup"][flow.id]["islPercent"]
                    switchPercent == expectedValuesMap["diverseGroup"][flow.id]["switchPercent"]
                }
                with(diverseGroup.otherFlows) { otherFlows ->
                    assert (flowPaths*.id - flow.id).containsAll(otherFlows*.id)
                    otherFlows.each { otherFlow ->
                        verifyAll(otherFlow.segmentsStats) {
                            islCount == expectedValuesMap["otherFlows"][flow.id][otherFlow.id]["islCount"]
                            switchCount == expectedValuesMap["otherFlows"][flow.id][otherFlow.id]["switchCount"]
                            islPercent == expectedValuesMap["otherFlows"][flow.id][otherFlow.id]["islPercent"]
                            switchPercent == expectedValuesMap["otherFlows"][flow.id][otherFlow.id]["switchPercent"]
                        }
                    }
                }
            }
        }
    }
}
