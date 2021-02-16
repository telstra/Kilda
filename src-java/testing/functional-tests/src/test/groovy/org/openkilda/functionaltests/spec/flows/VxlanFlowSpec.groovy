package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.Unroll

import java.time.Instant
import javax.inject.Provider

@Narrative("""This spec checks basic functionality(simple flow(rules, ping, traffic, validate), pinned flow,
flow with protected path, default flow) for a flow with VXLAN encapsulation.

NOTE: A flow with the 'VXLAN' encapsulation is supported on a Noviflow switches.
So, flow can be created on a Noviflow(src/dst/transit) switches only.""")
@Tags([LOW_PRIORITY])
class VxlanFlowSpec extends HealthCheckSpecification {
    @Autowired
    Provider<TraffExamService> traffExamProvider

    @Unroll
    @Tags(HARDWARE)
    def "System allows to create/update encapsulation type for a flow\
(#encapsulationCreate.toString() -> #encapsulationUpdate.toString())"() {
        given: "Two active neighboring VXLAN supported switches with traffgens"
        def allTraffgenSwitchIds = topology.activeTraffGens*.switchConnected.findAll {
            isVxlanEnabled(it.dpId)
        }*.dpId ?: assumeTrue("Should be at least two active traffgens connected to VXLAN supported switches",
                false)

        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            allTraffgenSwitchIds.contains(it.src.dpId) && allTraffgenSwitchIds.contains(it.dst.dpId)
        } ?: assumeTrue("Unable to find required switches in topology",false)

        when: "Create a flow with #encapsulationCreate.toString() encapsulation type"
        def flow = flowHelper.randomFlow(switchPair)
        flow.encapsulationType = encapsulationCreate
        flowHelper.addFlow(flow)

        then: "Flow is created with the #encapsulationCreate.toString() encapsulation type"
        def flowInfo = northbound.getFlow(flow.id)
        flowInfo.encapsulationType == encapsulationCreate.toString().toLowerCase()

        and: "Correct rules are installed"
        def vxlanRule = (flowInfo.encapsulationType == FlowEncapsulationType.VXLAN.toString().toLowerCase())
        def flowInfoFromDb = database.getFlow(flow.id)
        // ingressRule should contain "pushVxlan"
        // egressRule should contain "tunnel-id"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(northbound.getSwitchRules(switchPair.src.dpId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb.forwardPath.cookie.value
                }.instructions.applyActions.pushVxlan as boolean == vxlanRule
                assert rules.find {
                    it.cookie == flowInfoFromDb.reversePath.cookie.value
                }.match.tunnelId as boolean == vxlanRule
            }

            with(northbound.getSwitchRules(switchPair.dst.dpId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb.forwardPath.cookie.value
                }.match.tunnelId as boolean == vxlanRule
                assert rules.find {
                    it.cookie == flowInfoFromDb.reversePath.cookie.value
                }.instructions.applyActions.pushVxlan as boolean == vxlanRule
            }
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.id).each { direction -> assert direction.asExpected }

        and: "The flow allows traffic"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(flow, 0, 5)
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "Flow is pingable"
        verifyAll(northbound.pingFlow(flow.id, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        when: "Try to update the encapsulation type to #encapsulationUpdate.toString()"
        northbound.updateFlow(flow.id, flow.tap { it.encapsulationType = encapsulationUpdate })

        then: "The encapsulation type is changed to #encapsulationUpdate.toString()"
        def flowInfo2 = northbound.getFlow(flow.id)
        flowInfo2.encapsulationType == encapsulationUpdate.toString().toLowerCase()

        and: "Flow is valid"
        Wrappers.wait(PATH_INSTALLATION_TIME) {
            northbound.validateFlow(flow.id).each { direction -> assert direction.asExpected }
        }

        and: "The flow allows traffic"
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "Flow is pingable"
        verifyAll(northbound.pingFlow(flow.id, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "Rules are recreated"
        def flowInfoFromDb2 = database.getFlow(flow.id)
        [flowInfoFromDb.forwardPath.cookie.value, flowInfoFromDb.reversePath.cookie.value].sort() !=
                [flowInfoFromDb2.forwardPath.cookie.value, flowInfoFromDb2.reversePath.cookie.value].sort()

        and: "New rules are installed correctly"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(northbound.getSwitchRules(switchPair.src.dpId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb2.forwardPath.cookie.value
                }.instructions.applyActions.pushVxlan as boolean == !vxlanRule
                assert rules.find {
                    it.cookie == flowInfoFromDb2.reversePath.cookie.value
                }.match.tunnelId as boolean == !vxlanRule
            }

            with(northbound.getSwitchRules(switchPair.dst.dpId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb2.forwardPath.cookie.value
                }.match.tunnelId as boolean == !vxlanRule
                assert rules.find {
                    it.cookie == flowInfoFromDb2.reversePath.cookie.value
                }.instructions.applyActions.pushVxlan as boolean == !vxlanRule
            }
        }

        and: "Cleanup: Delete the flow"
        flowHelper.deleteFlow(flow.id)

        where:
        encapsulationCreate | encapsulationUpdate
        FlowEncapsulationType.TRANSIT_VLAN | FlowEncapsulationType.VXLAN
        FlowEncapsulationType.VXLAN | FlowEncapsulationType.TRANSIT_VLAN
    }

    @Tags(HARDWARE)
    def "Able to CRUD a metered pinned flow with 'VXLAN' encapsulation"() {
        when: "Create a flow"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { swP ->
            [swP.src, swP.dst].every { sw -> isVxlanEnabled(sw.dpId) }
        }
        assumeTrue("Unable to find required switches in topology", switchPair as boolean)

        def flow = flowHelper.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        flow.pinned = true
        flowHelper.addFlow(flow)

        then: "Flow is created"
        def flowInfo = northbound.getFlow(flow.id)
        flowInfo.pinned

        when: "Update the flow (pinned=false)"
        northbound.updateFlow(flow.id, flow.tap { it.pinned = false })

        then: "The pinned option is disabled"
        def newFlowInfo = northbound.getFlow(flow.id)
        !newFlowInfo.pinned
        Instant.parse(flowInfo.lastUpdated) < Instant.parse(newFlowInfo.lastUpdated)

        and: "Cleanup: Delete the flow"
        Wrappers.wait(WAIT_OFFSET) { northbound.getFlowStatus(flow.id).status == FlowState.UP }
        flowHelper.deleteFlow(flow.id)
    }

    @Tags(HARDWARE)
    def "Able to CRUD a vxlan flow with protected path"() {
        given: "Two active VXLAN supported switches with two available path at least"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { swP ->
            [swP.src, swP.dst].every { sw -> isVxlanEnabled(sw.dpId) }
        }
        assumeTrue("Unable to find required switches in topology", switchPair as boolean)

        def availablePaths = switchPair.paths.findAll { path ->
            pathHelper.getInvolvedSwitches(path).every { isVxlanEnabled(it.dpId) }
        }
        assumeTrue("Unable to find required paths between switches", availablePaths.size() >= 2)

        when: "Create a flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        flowHelper.addFlow(flow)

        then: "Flow is created with protected path"
        def flowPathInfo = northbound.getFlowPath(flow.id)
        flowPathInfo.protectedPath
        northbound.getFlow(flow.id).flowStatusDetails

        and: "Rules for main and protected paths are created"
        Wrappers.wait(WAIT_OFFSET) { flowHelper.verifyRulesOnProtectedFlow(flow.id) }
        def flowInfoFromDb = database.getFlow(flow.id)
        // ingressRule should contain "pushVxlan"
        // egressRule should contain "tunnel-id"
        // protected path creates engressRule
        def protectedForwardCookie = flowInfoFromDb.protectedForwardPath.cookie.value
        def protectedReverseCookie = flowInfoFromDb.protectedReversePath.cookie.value
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(northbound.getSwitchRules(switchPair.src.dpId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb.forwardPath.cookie.value
                }.instructions.applyActions.pushVxlan
                assert rules.find {
                    it.cookie == flowInfoFromDb.reversePath.cookie.value
                }.match.tunnelId
                assert rules.find {
                    it.cookie == flowInfoFromDb.protectedReversePath.cookie.value
                }.match.tunnelId
            }

            with(northbound.getSwitchRules(switchPair.dst.dpId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb.forwardPath.cookie.value
                }.match.tunnelId
                assert rules.find {
                    it.cookie == flowInfoFromDb.reversePath.cookie.value
                }.instructions.applyActions.pushVxlan
                assert rules.find {
                    it.cookie == flowInfoFromDb.protectedForwardPath.cookie.value
                }.match.tunnelId
            }
        }

        and: "Validation of flow must be successful"
        northbound.validateFlow(flow.id).each { direction ->
            assert direction.discrepancies.empty
        }

        when: "Update flow: disable protected path(allocateProtectedPath=false)"
        def protectedFlowPath = northbound.getFlowPath(flow.id).protectedPath.forwardPath
        northbound.updateFlow(flow.id, flow.tap { it.allocateProtectedPath = false })

        then: "Protected path is disabled"
        !northbound.getFlowPath(flow.id).protectedPath
        !northbound.getFlow(flow.id).flowStatusDetails

        and: "Rules for protected path are deleted"
        Wrappers.wait(WAIT_OFFSET) {
            protectedFlowPath.each { sw ->
                def rules = northbound.getSwitchRules(sw.switchId).flowEntries.findAll {
                    !new Cookie(it.cookie).serviceFlag
                }
                assert rules.every { it != protectedForwardCookie && it != protectedReverseCookie }
            }
        }

        and: "Rules for protected path are deleted"
        Wrappers.wait(WAIT_OFFSET) {
            protectedFlowPath.each { sw ->
                def rules = northbound.getSwitchRules(sw.switchId).flowEntries.findAll {
                    !new Cookie(it.cookie).serviceFlag
                }
                assert rules.every { it != protectedForwardCookie && it != protectedReverseCookie }
            }
        }
        def flowInfoFromDb2 = database.getFlow(flow.id)

        and: "Rules for main path are recreated"
        [flowInfoFromDb.forwardPath.cookie.value, flowInfoFromDb.reversePath.cookie.value].sort() !=
                [flowInfoFromDb2.forwardPath.cookie.value, flowInfoFromDb2.reversePath.cookie.value].sort()

        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(northbound.getSwitchRules(switchPair.src.dpId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb2.forwardPath.cookie.value
                }.instructions.applyActions.pushVxlan
                assert rules.find {
                    it.cookie == flowInfoFromDb2.reversePath.cookie.value
                }.match.tunnelId
            }

            with(northbound.getSwitchRules(switchPair.dst.dpId).flowEntries) { rules ->
                assert rules.find {
                    it.cookie == flowInfoFromDb2.forwardPath.cookie.value
                }.match.tunnelId
                assert rules.find {
                    it.cookie == flowInfoFromDb2.reversePath.cookie.value
                }.instructions.applyActions.pushVxlan
            }
        }

        and: "Validation of flow must be successful"
        northbound.validateFlow(flow.id).each { direction ->
            assert direction.discrepancies.empty
        }

        and: "Cleanup: Delete the flow and reset costs"
        flowHelper.deleteFlow(flow.id)
    }

    @Tags(HARDWARE)
    def "System allows tagged traffic via default flow(0<->0) with 'VXLAN' encapsulation"() {
        // we can't test (0<->20, 20<->0) because iperf is not able to establish a connection
        given: "Two active VXLAN supported switches with traffgens"
        def allTraffgenSwitchIds = topology.activeTraffGens*.switchConnected.findAll {
            isVxlanEnabled(it.dpId)
        }*.dpId ?: assumeTrue("Should be at least two active traffgens connected to VXLAN supported switches",
                false)
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            allTraffgenSwitchIds.contains(it.src.dpId) && allTraffgenSwitchIds.contains(it.dst.dpId)
        } ?: assumeTrue("Unable to find required switches in topology",false)

        when: "Create a default flow"
        def defaultFlow = flowHelper.randomFlow(switchPair)
        defaultFlow.source.vlanId = 0
        defaultFlow.destination.vlanId = 0
        defaultFlow.encapsulationType = FlowEncapsulationType.VXLAN
        flowHelper.addFlow(defaultFlow)

        def flow = flowHelper.randomFlow(switchPair)
        flow.source.vlanId = 10
        flow.destination.vlanId = 10

        then: "System allows tagged traffic on the default flow"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(flow, 0, 5)
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "Cleanup: Delete the flow"
        flowHelper.deleteFlow(defaultFlow.id)
    }

    @Tidy
    def "System doesn't allow to create a flow with 'VXLAN' encapsulation on non-supported switches"() {
        setup:
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { swP ->
            [swP.src, swP.dst].every { sw -> !isVxlanEnabled(sw.dpId) }
        }
        assumeTrue("Unable to find required switches in topology", switchPair as boolean)

        when: "Try to create a flow"
        def flow = flowHelper.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        northbound.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        // TODO(andriidovhan)fix errorMessage when the 2587 issue is fixed

        cleanup:
        !exc && flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    @Tags(HARDWARE)
    def "System doesn't allow to create a vxlan flow when transit switch doesn't support VXLAN"() {
        setup:
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { swP ->
            [swP.src, swP.dst].every { sw -> isVxlanEnabled(sw.dpId) } && swP.paths.find { path ->
                pathHelper.getInvolvedSwitches(path).find { !isVxlanEnabled(it.dpId) }
            }
        } ?: assumeTrue("Unable to find required switches in topology", false)
        // find path with needed transit switch
        def requiredPath = switchPair.paths.find {
            pathHelper.getInvolvedSwitches(it).find { !isVxlanEnabled(it.dpId) }
        }
        // make all alternative paths are unavailable (bring ports down on the srcSwitch)
        List<PathNode> broughtDownPorts = []
        switchPair.paths.findAll { it != requiredPath }.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        when: "Try to create a flow"
        def flow = flowHelper.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        northbound.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        //TODO(andriidovhan)add errorMessage when the 2587 issue is fixed

        cleanup:
        !exc && flowHelper.deleteFlow(flow.id)
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        Wrappers.wait(discoveryAuxiliaryInterval + discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.actualState != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tidy
    @Tags(HARDWARE)
    def "System doesn't allow to create a vxlan flow when dst switch doesn't support VXLAN"() {
        given: "VXLAN supported and not supported switches"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            isVxlanEnabled(it.src.dpId) && !isVxlanEnabled(it.dst.dpId)
        }
        assumeTrue("Unable to find required switches in topology", switchPair as boolean)

        when: "Try to create a flow"
        def flow = flowHelper.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        northbound.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        // TODO(andriidovhan) fix errorMessage when the 2587 issue is fixed

        cleanup:
        !exc && flowHelper.deleteFlow(flow.id)
    }

    @Unroll
    @Tags(HARDWARE)
    def "System allows to create/update encapsulation type for a one-switch flow\
(#encapsulationCreate.toString() -> #encapsulationUpdate.toString())"() {
        when: "Try to create a one-switch flow"
        def sw = topology.activeTraffGens*.switchConnected.find {
            isVxlanEnabled(it.dpId)
        } ?: assumeTrue("Should be at least one active traffgen connected to VXLAN supported switch",false)
        def flow = flowHelper.singleSwitchFlow(sw)
        flow.encapsulationType = encapsulationCreate
        northbound.addFlow(flow)

        then: "Flow is created with the #encapsulationCreate.toString() encapsulation type"
        def flowInfo1 = northbound.getFlow(flow.id)
        flowInfo1.encapsulationType == encapsulationCreate.toString().toLowerCase()

        and: "Correct rules are installed"
        def flowInfoFromDb = database.getFlow(flow.id)
        // vxlan rules are not creating for a one-switch flow
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                assert !rules.find {
                    it.cookie == flowInfoFromDb.forwardPath.cookie.value
                }.instructions.applyActions.pushVxlan
                assert !rules.find {
                    it.cookie == flowInfoFromDb.reversePath.cookie.value
                }.match.tunnelId
            }
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.id).each { direction -> assert direction.asExpected }

        when: "Try to update the encapsulation type to #encapsulationUpdate.toString()"
        northbound.updateFlow(flow.id, flow.tap { it.encapsulationType = encapsulationUpdate })

        then: "The encapsulation type is changed to #encapsulationUpdate.toString()"
        def flowInfo2 = northbound.getFlow(flow.id)
        flowInfo2.encapsulationType == encapsulationUpdate.toString().toLowerCase()

        and: "Flow is valid"
        Wrappers.wait(PATH_INSTALLATION_TIME) {
            northbound.validateFlow(flow.id).each { direction -> assert direction.asExpected }
        }

        and: "Rules are recreated"
        def flowInfoFromDb2 = database.getFlow(flow.id)
        [flowInfoFromDb.forwardPath.cookie.value, flowInfoFromDb.reversePath.cookie.value].sort() !=
                [flowInfoFromDb2.forwardPath.cookie.value, flowInfoFromDb2.reversePath.cookie.value].sort()

        and: "New rules are installed correctly"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(northbound.getSwitchRules(sw.dpId).flowEntries) { rules ->
                assert !rules.find {
                    it.cookie == flowInfoFromDb2.forwardPath.cookie.value
                }.instructions.applyActions.pushVxlan
                assert !rules.find {
                    it.cookie == flowInfoFromDb2.reversePath.cookie.value
                }.match.tunnelId
            }
        }

        and: "Cleanup: Delete the flow"
        flowHelper.deleteFlow(flow.id)

        where:
        encapsulationCreate | encapsulationUpdate
        FlowEncapsulationType.TRANSIT_VLAN | FlowEncapsulationType.VXLAN
        FlowEncapsulationType.VXLAN | FlowEncapsulationType.TRANSIT_VLAN
    }

    @Tidy
    def "System doesn't allow to enable a flow with 'VXLAN' encapsulation on non-supported switch"() {
        given: "A flow with 'TRANSIT_VLAN' encapsulation"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { swP ->
            [swP.src, swP.dst].every { sw -> !isVxlanEnabled(sw.dpId) }
        }
        def flow = flowHelper.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.TRANSIT_VLAN
        flowHelper.addFlow(flow)

        when: "Try to change the encapsulation type to VXLAN"
        flowHelper.updateFlow(flow.id, flow.tap { it.encapsulationType = FlowEncapsulationType.VXLAN })

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        //TODO(andriidovhan) fix errorMessage when the 2587 issue is fixed

        cleanup:
        flowHelper.deleteFlow(flow.id)
    }

    def isVxlanEnabled(SwitchId switchId) {
        return northbound.getSwitchProperties(switchId).supportedTransitEncapsulation
                .contains(FlowEncapsulationType.VXLAN.toString().toLowerCase())
    }
}
