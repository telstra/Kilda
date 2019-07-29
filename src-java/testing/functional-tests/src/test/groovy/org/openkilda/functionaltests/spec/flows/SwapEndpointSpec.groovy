package org.openkilda.functionaltests.spec.flows

import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.helpers.thread.FlowHistoryConstants.REROUTE_FAIL
import static org.openkilda.testing.Constants.NON_EXISTENT_FLOW_ID
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowCreatePayload
import org.openkilda.messaging.payload.flow.FlowEndpointPayload
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.SwitchId
import org.openkilda.northbound.dto.v2.flows.FlowEndpointV2
import org.openkilda.northbound.dto.v2.flows.SwapFlowPayload
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.Unroll

import javax.inject.Provider

@Ignore
class SwapEndpointSpec extends HealthCheckSpecification {

    @Autowired
    Provider<TraffExamService> traffExamProvider

    @Tidy
    @Unroll
    def "Able to swap endpoints(#data.description)"() {
        given: "Some flows in the system according to preconditions"
        flows.each { flowHelper.addFlow(it) }

        when: "Try to swap endpoints with #data.descirption"
        def response = northbound.swapFlowEndpoint(firstSwap, secondSwap)

        then: "Endpoints are successfully swapped"
        verifyEndpoints(response, firstSwap.source, firstSwap.destination, secondSwap.source, secondSwap.destination)
        verifyEndpoints(firstSwap.flowId, secondSwap.flowId, firstSwap.source, firstSwap.destination,
                secondSwap.source, secondSwap.destination)

        and: "Flows validation doesn't show any rule discrepancies"
        Wrappers.wait(RULES_DELETION_TIME + RULES_INSTALLATION_TIME) {
            flows.each {
                assert northbound.validateFlow(it.id).each { direction -> assert direction.asExpected }
            }
        }

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(flows.collectMany { 
            [it.source.datapath, it.destination.datapath].collect { findSw(it) }
        }.unique())

        cleanup: "Delete flows"
        flows.each { flowHelper.deleteFlow(it.id) }

        where:
        data << [
                [description: "no vlan vs vlan on the same port on src switch"].tap {
                    def switchPair = getTopologyHelper().getNotNeighboringSwitchPair()
                    def flow1 = getFlowHelper().randomFlow(switchPair)
                    flow1.source.portNumber = getFreePort(switchPair.src, [switchPair.dst])
                    flow1.source.vlanId = 0
                    def flow2 = getFlowHelper().randomFlow(switchPair, false, [flow1])
                    flow2.source.portNumber = flow1.source.portNumber
                    it.flows = [flow1, flow2]
                    it.firstSwap = new SwapFlowPayload(flow1.id, getFlowHelper().toFlowEndpointV2(flow2.source),
                            getFlowHelper().toFlowEndpointV2(flow1.destination))
                    it.secondSwap = new SwapFlowPayload(flow2.id, getFlowHelper().toFlowEndpointV2(flow1.source),
                            getFlowHelper().toFlowEndpointV2(flow2.destination))
                },
                [description: "same port, swap vlans on dst switch + third idle novlan flow on that port"].tap {
                    def switchPair = getTopologyHelper().getNotNeighboringSwitchPair()
                    def flow1 = getFlowHelper().randomFlow(switchPair)
                    def flow2 = getFlowHelper().randomFlow(switchPair, false, [flow1])
                    flow1.destination.portNumber = getFreePort(switchPair.dst, [switchPair.src])
                    flow2.destination.portNumber = flow1.destination.portNumber
                    flow2.destination.vlanId = getFreeVlan(flow2.destination.datapath, [flow1])
                    def flow3 = getFlowHelper().randomFlow(switchPair, false, [flow1, flow2])
                    flow3.destination.portNumber = flow1.destination.portNumber
                    flow3.destination.vlanId = 0
                    it.flows = [flow1, flow2, flow3]
                    it.firstSwap = new SwapFlowPayload(flow1.id, getFlowHelper().toFlowEndpointV2(flow1.source),
                            getFlowHelper().toFlowEndpointV2(flow2.destination))
                    it.secondSwap = new SwapFlowPayload(flow2.id, getFlowHelper().toFlowEndpointV2(flow2.source),
                            getFlowHelper().toFlowEndpointV2(flow1.destination))
                },
                [description: "vlan on src1 <-> vlan on dst2, same port numbers"].tap {
                    def switchPair = getTopologyHelper().getNotNeighboringSwitchPair()
                    def flow1 = getFlowHelper().randomFlow(switchPair)
                    def flow2 = getFlowHelper().randomFlow(switchPair, false, [flow1])
                    flow1.source.portNumber = getFreePort(switchPair.src, [switchPair.dst])
                    flow2.destination.portNumber = flow1.source.portNumber
                    it.flows = [flow1, flow2]
                    it.firstSwap = new SwapFlowPayload(flow1.id,
                            getFlowHelper().toFlowEndpointV2(flow1.source).tap { it.vlanId = flow2.destination.vlanId },
                            getFlowHelper().toFlowEndpointV2(flow1.destination))
                    it.secondSwap = new SwapFlowPayload(flow2.id,
                            getFlowHelper().toFlowEndpointV2(flow2.source),
                            getFlowHelper().toFlowEndpointV2(flow2.destination).tap { it.vlanId = flow1.source.vlanId })
                },
                [description: "port on dst1 <-> port on src2, vlans are equal"].tap {
                    def switchPair = getTopologyHelper().getNotNeighboringSwitchPair()
                    def flow1 = getFlowHelper().randomFlow(switchPair, false)
                    def flow2 = getFlowHelper().randomFlow(switchPair, false, [flow1])
                    flow1.destination.portNumber = getFreePort(switchPair.dst, [switchPair.src],
                            [flow1.source.portNumber, flow2.source.portNumber])
                    flow2.source.portNumber = getFreePort(switchPair.src, [switchPair.dst],
                            [flow2.destination.portNumber, flow1.source.portNumber])
                    flow2.source.vlanId = flow1.source.vlanId
                    it.flows = [flow1, flow2]
                    it.firstSwap = new SwapFlowPayload(flow1.id,
                            getFlowHelper().toFlowEndpointV2(flow1.source),
                            getFlowHelper().toFlowEndpointV2(flow1.destination)
                                .tap { it.portNumber = flow2.source.portNumber })
                    it.secondSwap = new SwapFlowPayload(flow2.id,
                            getFlowHelper().toFlowEndpointV2(flow2.source)
                                .tap { it.portNumber = flow1.destination.portNumber },
                            getFlowHelper().toFlowEndpointV2(flow2.destination))
                },
                [description: "switch on src1 <-> switch on dst2, other params random"].tap {
                    def switchPair = getTopologyHelper().getNotNeighboringSwitchPair()
                    def flow1 = getFlowHelper().randomFlow(switchPair)
                    def flow2 = getFlowHelper().randomFlow(switchPair, false, [flow1])
                    flow1.source.portNumber = getFreePort(switchPair.src, [switchPair.dst])
                    flow2.destination.portNumber = getFreePort(switchPair.dst, [switchPair.src])
                    it.flows = [flow1, flow2]
                    it.firstSwap = new SwapFlowPayload(flow1.id,
                            getFlowHelper().toFlowEndpointV2(flow1.source)
                                .tap { it.switchId = flow2.destination.datapath },
                            getFlowHelper().toFlowEndpointV2(flow1.destination))
                    it.secondSwap = new SwapFlowPayload(flow2.id,
                            getFlowHelper().toFlowEndpointV2(flow2.source),
                            getFlowHelper().toFlowEndpointV2(flow2.destination)
                                .tap { it.switchId = flow1.source.datapath })
                },
                [description: "both endpoints swap, same switches"].tap {
                    def switchPair = getTopologyHelper().getNotNeighboringSwitchPair()
                    def flow1 = getFlowHelper().randomFlow(switchPair)
                    def flow2 = getFlowHelper().randomFlow(switchPair, false, [flow1])
                    flow1.source.portNumber = getFreePort(switchPair.src, [switchPair.dst])
                    flow1.destination.portNumber = getFreePort(switchPair.dst, [switchPair.src])
                    flow2.source.portNumber = getFreePort(switchPair.src, [switchPair.dst])
                    flow2.destination.portNumber = getFreePort(switchPair.dst, [switchPair.src])
                    it.flows = [flow1, flow2]
                    it.firstSwap = new SwapFlowPayload(flow1.id,
                            getFlowHelper().toFlowEndpointV2(flow2.source),
                            getFlowHelper().toFlowEndpointV2(flow2.destination))
                    it.secondSwap = new SwapFlowPayload(flow2.id,
                            getFlowHelper().toFlowEndpointV2(flow1.source),
                            getFlowHelper().toFlowEndpointV2(flow1.destination))
                },
                [description: "endpoints src1 <-> dst2, same switches"].tap {
                    def switchPair = getTopologyHelper().getNotNeighboringSwitchPair()
                    def flow1 = getFlowHelper().randomFlow(switchPair)
                    def flow2 = getFlowHelper().randomFlow(switchPair, false, [flow1])
                    flow1.source.portNumber = getFreePort(switchPair.src, [switchPair.dst])
                    flow1.destination.portNumber = getFreePort(switchPair.dst, [switchPair.src])
                    flow2.source.portNumber = getFreePort(switchPair.src, [switchPair.dst], [flow1.source.portNumber])
                    flow2.destination.portNumber = getFreePort(switchPair.dst, [switchPair.src], [flow1.destination.portNumber])
                    flow1.source.vlanId = getFreeVlan(flow2.destination.datapath, [flow2])
                    flow2.destination.vlanId = getFreeVlan(flow1.destination.datapath, [flow1])
                    it.flows = [flow1, flow2]
                    it.firstSwap = new SwapFlowPayload(flow1.id,
                            getFlowHelper().toFlowEndpointV2(flow2.destination),
                            getFlowHelper().toFlowEndpointV2(flow1.destination))
                    it.secondSwap = new SwapFlowPayload(flow2.id,
                            getFlowHelper().toFlowEndpointV2(flow2.source),
                            getFlowHelper().toFlowEndpointV2(flow1.source))
                },
                [description: "endpoints src1 <-> src2, different src switches, same dst"].tap {
                    List<SwitchPair> switchPairs = getTopologyHelper().getAllNotNeighboringSwitchPairs()
                        .inject(null) { result, switchPair ->
                            if (result) return result
                            def halfDifferent = getHalfDifferentNotNeighboringSwitchPair(switchPair, "dst")
                            if (halfDifferent) result = [switchPair, halfDifferent]
                            return result
                    }
                    def flow1 = getFlowHelper().randomFlow(switchPairs[0])
                    def flow2 = getFlowHelper().randomFlow(switchPairs[1], false, [flow1])
                    flow1.source.portNumber = getFreePort(switchPairs[0].src, [switchPairs[1].src])
                    flow2.source.portNumber = getFreePort(switchPairs[1].src, [switchPairs[0].src])
                    it.flows = [flow1, flow2]
                    it.firstSwap = new SwapFlowPayload(flow1.id,
                            getFlowHelper().toFlowEndpointV2(flow2.source),
                            getFlowHelper().toFlowEndpointV2(flow1.destination))
                    it.secondSwap = new SwapFlowPayload(flow2.id,
                            getFlowHelper().toFlowEndpointV2(flow1.source),
                            getFlowHelper().toFlowEndpointV2(flow2.destination))
                }
        ]
        flows = data.flows as List<FlowCreatePayload>
        firstSwap = data.firstSwap as SwapFlowPayload
        secondSwap = data.secondSwap as SwapFlowPayload
    }

    @Tidy
    @Unroll
    @IterationTag(tags = [LOW_PRIORITY], iterationNameRegex = /src1/)
    def "Able to swap #endpointsPart (#description) for two flows with the same source and different destination \
switches"() {
        given: "Two flows with the same source and different destination switches"
        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        when: "Try to swap #endpointsPart for flows"
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "#endpointsPart.capitalize() are successfully swapped"
        verifyEndpoints(response, flow1Src, flow1Dst, flow2Src, flow2Dst)
        verifyEndpoints(flow1.id, flow2.id, flow1Src, flow1Dst, flow2Src, flow2Dst)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(switchPairs[0])
        validateSwitches(switchPairs[1])

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }

        where:
        endpointsPart << ["vlans", "ports", "switches"]
        description << ["src1 <-> dst2, dst1 <-> src2"] * 3
        switchPairs << [getTopologyHelper().getAllNotNeighboringSwitchPairs().inject(null) { result, switchPair ->
            if (result) return result
            def halfDifferent = getHalfDifferentNotNeighboringSwitchPair(switchPair, "src")
            if (halfDifferent) result = [switchPair, halfDifferent]
            return result
        }] * 3
        flow1 << [getFirstFlow(switchPairs[0], switchPairs[1])] * 3
        flow2 << [getSecondFlow(switchPairs[0], switchPairs[1], flow1)] * 3
        [flow1Src, flow1Dst, flow2Src, flow2Dst] << [
                [changePropertyValue(flow1.source, "vlanId", flow2.destination.vlanId),
                 changePropertyValue(flow1.destination, "vlanId", flow2.source.vlanId),
                 changePropertyValue(flow2.source, "vlanId", flow1.destination.vlanId),
                 changePropertyValue(flow2.destination, "vlanId", flow1.source.vlanId)],

                [changePropertyValue(flow1.source, "portNumber", flow2.destination.portNumber),
                 changePropertyValue(flow1.destination, "portNumber", flow2.source.portNumber),
                 changePropertyValue(flow2.source, "portNumber", flow1.destination.portNumber),
                 changePropertyValue(flow2.destination, "portNumber", flow1.source.portNumber)],

                [changePropertyValue(flow1.source, "datapath", flow2.destination.datapath),
                 changePropertyValue(flow1.destination, "datapath", flow2.source.datapath),
                 changePropertyValue(flow2.source, "datapath", flow1.destination.datapath),
                 changePropertyValue(flow2.destination, "datapath", flow1.source.datapath)],
        ]
    }

    @Tidy
    @Unroll
    @IterationTag(tags = [LOW_PRIORITY], iterationNameRegex = /src1/)
    def "Able to swap endpoints (#description) for two flows with the same source and different destination \
switches"() {
        given: "Two flows with the same source and different destination switches"
        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        when: "Try to swap endpoints for flows"
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "Endpoints are successfully swapped"
        verifyEndpoints(response, flow1Src, flow1Dst, flow2Src, flow2Dst)
        verifyEndpoints(flow1.id, flow2.id, flow1Src, flow1Dst, flow2Src, flow2Dst)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(switchPairs[0])
        validateSwitches(switchPairs[1])

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }

        where:
        description << ["src1 <-> src2", "dst1 <-> dst2", "src1 <-> dst2", "dst1 <-> src2"]
        switchPairs << [getTopologyHelper().getAllNotNeighboringSwitchPairs().inject(null) { result, switchPair ->
            if (result) return result
            def halfDifferent = getHalfDifferentNotNeighboringSwitchPair(switchPair, "src")
            if (halfDifferent) result = [switchPair, halfDifferent]
            return result
        }] * 4
        flow1 << [getFirstFlow(switchPairs[0], switchPairs[1])] * 4
        flow2 << [getSecondFlow(switchPairs[0], switchPairs[1], flow1)] * 4
        [flow1Src, flow1Dst, flow2Src, flow2Dst] << [
                [flow2.source, flow1.destination, flow1.source, flow2.destination],
                [flow1.source, flow2.destination, flow2.source, flow1.destination],
                [flow2.destination, flow1.destination, flow2.source, flow1.source],
                [flow1.source, flow2.source, flow1.destination, flow2.destination]
        ]
    }

    @Tidy
    @Unroll
    @Tags(LOW_PRIORITY)
    def "Able to swap #endpointsPart (#description) for two flows with different source and the same destination \
switches"() {
        given: "Two flows with different source and the same destination switches"
        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        when: "Try to swap #endpointsPart for flows"
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "#endpointsPart.capitalize() are successfully swapped"
        verifyEndpoints(response, flow1Src, flow1Dst, flow2Src, flow2Dst)
        verifyEndpoints(flow1.id, flow2.id, flow1Src, flow1Dst, flow2Src, flow2Dst)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(switchPairs[0])
        validateSwitches(switchPairs[1])

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }

        where:
        endpointsPart << ["vlans", "ports", "switches"]
        description << ["src1 <-> dst2, dst1 <-> src2"] * 3
        switchPairs << [getTopologyHelper().getAllNotNeighboringSwitchPairs().inject(null) { result, switchPair ->
            if (result) return result
            def halfDifferent = getHalfDifferentNotNeighboringSwitchPair(switchPair, "dst")
            if (halfDifferent) result = [switchPair, halfDifferent]
            return result
        }] * 3
        flow1 << [getFirstFlow(switchPairs[0], switchPairs[1])] * 3
        flow2 << [getSecondFlow(switchPairs[0], switchPairs[1], flow1)] * 3
        [flow1Src, flow1Dst, flow2Src, flow2Dst] << [
                [changePropertyValue(flow1.source, "vlanId", flow2.destination.vlanId),
                 changePropertyValue(flow1.destination, "vlanId", flow2.source.vlanId),
                 changePropertyValue(flow2.source, "vlanId", flow1.destination.vlanId),
                 changePropertyValue(flow2.destination, "vlanId", flow1.source.vlanId)],

                [changePropertyValue(flow1.source, "portNumber", flow2.destination.portNumber),
                 changePropertyValue(flow1.destination, "portNumber", flow2.source.portNumber),
                 changePropertyValue(flow2.source, "portNumber", flow1.destination.portNumber),
                 changePropertyValue(flow2.destination, "portNumber", flow1.source.portNumber)],

                [changePropertyValue(flow1.source, "datapath", flow2.destination.datapath),
                 changePropertyValue(flow1.destination, "datapath", flow2.source.datapath),
                 changePropertyValue(flow2.source, "datapath", flow1.destination.datapath),
                 changePropertyValue(flow2.destination, "datapath", flow1.source.datapath)],
        ]
    }

    @Tidy
    @Unroll
    @IterationTag(tags = [LOW_PRIORITY], iterationNameRegex = /dst1/)
    def "Able to swap #endpointsPart (#description) for two flows with different source and destination switches"() {
        given: "Two flows with different source and destination switches"
        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        when: "Try to swap #endpointsPart for flows"
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "#endpointsPart.capitalize() are successfully swapped"
        verifyEndpoints(response, flow1Src, flow1Dst, flow2Src, flow2Dst)
        verifyEndpoints(flow1.id, flow2.id, flow1Src, flow1Dst, flow2Src, flow2Dst)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(flow1SwitchPair)
        validateSwitches(flow2SwitchPair)

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }

        where:
        endpointsPart << ["vlans", "ports", "switches"]
        description << ["src1 <-> dst2, dst1 <-> src2"] * 3
        flow1SwitchPair << [getTopologyHelper().getNotNeighboringSwitchPair()] * 3
        flow2SwitchPair << [getDifferentNotNeighboringSwitchPair(flow1SwitchPair)] * 3
        flow1 << [getFirstFlow(flow1SwitchPair, flow2SwitchPair)] * 3
        flow2 << [getSecondFlow(flow1SwitchPair, flow2SwitchPair, flow1)] * 3
        [flow1Src, flow1Dst, flow2Src, flow2Dst] << [
                [changePropertyValue(flow1.source, "vlanId", flow2.destination.vlanId),
                 changePropertyValue(flow1.destination, "vlanId", flow2.source.vlanId),
                 changePropertyValue(flow2.source, "vlanId", flow1.destination.vlanId),
                 changePropertyValue(flow2.destination, "vlanId", flow1.source.vlanId)],

                [changePropertyValue(flow1.source, "portNumber", flow2.destination.portNumber),
                 changePropertyValue(flow1.destination, "portNumber", flow2.source.portNumber),
                 changePropertyValue(flow2.source, "portNumber", flow1.destination.portNumber),
                 changePropertyValue(flow2.destination, "portNumber", flow1.source.portNumber)],

                [changePropertyValue(flow1.source, "datapath", flow2.destination.datapath),
                 changePropertyValue(flow1.destination, "datapath", flow2.source.datapath),
                 changePropertyValue(flow2.source, "datapath", flow1.destination.datapath),
                 changePropertyValue(flow2.destination, "datapath", flow1.source.datapath)],
        ]
    }

    @Tidy
    @Unroll
    @Tags(LOW_PRIORITY)
    def "Able to swap endpoints (#description) for two flows with different source and destination switches"() {
        given: "Two flows with different source and destination switches"
        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        when: "Try to swap endpoints for flows"
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "Endpoints are successfully swapped"
        verifyEndpoints(response, flow1Src, flow1Dst, flow2Src, flow2Dst)
        verifyEndpoints(flow1.id, flow2.id, flow1Src, flow1Dst, flow2Src, flow2Dst)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(flow1SwitchPair)
        validateSwitches(flow2SwitchPair)

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }

        where:
        description << ["src1 <-> src2", "dst1 <-> dst2", "src1 <-> dst2", "dst1 <-> src2"]
        flow1SwitchPair << [getTopologyHelper().getNotNeighboringSwitchPair()] * 4
        flow2SwitchPair << [getDifferentNotNeighboringSwitchPair(flow1SwitchPair)] * 4
        flow1 << [getFirstFlow(flow1SwitchPair, flow2SwitchPair)] * 4
        flow2 << [
                changePropertyValue(
                        changePropertyValue(getFlowHelper().randomFlow(flow2SwitchPair), "source", "portNumber",
                                flow1.source.portNumber), "source", "vlanId", flow1.source.vlanId),
                changePropertyValue(
                        changePropertyValue(getFlowHelper().randomFlow(flow2SwitchPair), "destination", "portNumber",
                                flow1.destination.portNumber), "destination", "vlanId", flow1.destination.vlanId),
                changePropertyValue(
                        changePropertyValue(getFlowHelper().randomFlow(flow2SwitchPair), "destination", "portNumber",
                                flow1.source.portNumber), "destination", "vlanId", flow1.source.vlanId),
                changePropertyValue(
                        changePropertyValue(getFlowHelper().randomFlow(flow2SwitchPair), "source", "portNumber",
                                flow1.destination.portNumber), "source", "vlanId", flow1.destination.vlanId)
        ]
        [flow1Src, flow1Dst, flow2Src, flow2Dst] << [
                [flow2.source, flow1.destination, flow1.source, flow2.destination],
                [flow1.source, flow2.destination, flow2.source, flow1.destination],
                [flow2.destination, flow1.destination, flow2.source, flow1.source],
                [flow1.source, flow2.source, flow1.destination, flow2.destination]
        ]
    }

    @Tidy
    def "Unable to swap endpoints for existing flow and non-existing flow"() {
        given: "An active flow"
        def switchPair = topologyHelper.getNeighboringSwitchPair()
        def flow1 = flowHelper.randomFlow(switchPair)
        def flow2 = flowHelper.randomFlow(switchPair)
        flowHelper.addFlow(flow1)
        flow2.id = NON_EXISTENT_FLOW_ID

        when: "Try to swap endpoints for existing flow and non-existing flow"
        northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1.source),
                        flowHelper.toFlowEndpointV2(flow2.destination)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2.source),
                        flowHelper.toFlowEndpointV2(flow1.destination)))

        then: "An error is received (404 code)"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        exc.responseBodyAsString.to(MessageError).errorMessage == "Can not swap endpoints for flows: " +
                "Flow ${flow2.id} not found"

        cleanup: "Delete the flow"
        flowHelper.deleteFlow(flow1.id)
    }

    @Tidy
    @Unroll
    @Tags(LOW_PRIORITY)
    def "Unable to swap #endpointsPart for two flows (#description)"() {
        given: "Three active flows"
        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)
        flowHelper.addFlow(flow3)

        when: "Try to swap #endpointsPart for two flows"
        northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "An error is received (409 code)"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 409
        exc.responseBodyAsString.to(MessageError).errorMessage.contains("Can not swap endpoints for flows: " +
                "Requested flow '$flow1.id' conflicts with existing flow '$flow3.id'.")

        cleanup: "Delete flows"
        [flow1, flow2, flow3].each { flowHelper.deleteFlow(it.id) }

        where:
        endpointsPart << ["ports and vlans", "ports and vlans", "vlans", "vlans", "ports", "ports"]
        description << ["the same ports and vlans on src switch",
                        "the same ports and vlans on dst switch",
                        "the same vlans on the same port on src switch",
                        "the same vlans on the same port on dst switch",
                        "no vlans, both flows are on the same port on src switch",
                        "no vlans, both flows are on the same port on dst switch"]
        flow1SwitchPair << [getTopologyHelper().getNotNeighboringSwitchPair()] * 6
        flow2SwitchPair << [getDifferentNotNeighboringSwitchPair(flow1SwitchPair)] * 6
        flow1 << [getFirstFlow(flow1SwitchPair, flow2SwitchPair)] * 4 +
                [getFirstFlow(flow1SwitchPair, flow2SwitchPair, true)] * 2
        flow2 << [getSecondFlow(flow1SwitchPair, flow2SwitchPair, flow1)] * 4 +
                [getSecondFlow(flow1SwitchPair, flow2SwitchPair, flow1, true)] * 2
        flow3 << [
                getConflictingFlow(flow1SwitchPair, flow1, "source", changePropertyValue(
                        changePropertyValue(flow1.source, "portNumber", flow2.destination.portNumber), "vlanId",
                        flow2.destination.vlanId)),
                getConflictingFlow(flow1SwitchPair, flow1, "destination", changePropertyValue(
                        changePropertyValue(flow1.destination, "portNumber", flow2.source.portNumber), "vlanId",
                        flow2.source.vlanId)),

                getConflictingFlow(flow1SwitchPair, flow1, "source",
                        changePropertyValue(flow1.source, "vlanId", flow2.destination.vlanId)),
                getConflictingFlow(flow1SwitchPair, flow1, "destination",
                        changePropertyValue(flow1.destination, "vlanId", flow2.source.vlanId)),

                getConflictingFlow(flow1SwitchPair, flow1, "source",
                        changePropertyValue(flow1.source, "portNumber", flow2.destination.portNumber)),
                getConflictingFlow(flow1SwitchPair, flow1, "destination",
                        changePropertyValue(flow1.destination, "portNumber", flow2.source.portNumber))
        ]
        [flow1Src, flow1Dst, flow2Src, flow2Dst] << [
                [changePropertyValue(changePropertyValue(flow1.source, "portNumber",
                        flow2.destination.portNumber), "vlanId", flow2.destination.vlanId), flow1.destination,
                 flow2.source, changePropertyValue(changePropertyValue(flow2.destination, "portNumber",
                        flow1.source.portNumber), "vlanId", flow1.source.vlanId)],
                [flow1.source, changePropertyValue(changePropertyValue(flow1.destination, "portNumber",
                        flow2.source.portNumber), "vlanId", flow2.source.vlanId),
                 changePropertyValue(changePropertyValue(flow2.source, "portNumber",
                         flow1.destination.portNumber), "vlanId", flow1.destination.vlanId), flow2.destination],

                [changePropertyValue(flow1.source, "vlanId", flow2.destination.vlanId), flow1.destination,
                 flow2.source, changePropertyValue(flow2.destination, "vlanId", flow1.source.vlanId)],
                [flow1.source, changePropertyValue(flow1.destination, "vlanId", flow2.source.vlanId),
                 changePropertyValue(flow2.source, "vlanId", flow1.destination.vlanId), flow2.destination],

                [changePropertyValue(flow1.source, "portNumber", flow2.destination.portNumber), flow1.destination,
                 flow2.source, changePropertyValue(flow2.destination, "portNumber", flow1.source.portNumber)],
                [flow1.source, changePropertyValue(flow1.destination, "portNumber", flow2.source.portNumber),
                 changePropertyValue(flow2.source, "portNumber", flow1.destination.portNumber), flow2.destination]
        ]
        conflictingEndpoint << ["source", "destination"] * 3
    }

    @Tidy
    @Unroll
    @IterationTag(tags = [LOW_PRIORITY], iterationNameRegex = /the same src endpoint for flows/)
    def "Unable to swap endpoints for two flows (#description)"() {
        given: "Two active flows"
        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        when: "Try to swap endpoints for two flows"
        northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "An error is received (400 code)"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.to(MessageError).errorMessage == "Can not swap endpoints for flows: " +
                "New requested endpoint for '$flow2.id' conflicts with existing endpoint for '$flow1.id'"

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }

        where:
        description << ["the same src endpoint for flows", "the same dst endpoint for flows"]
        flow1SwitchPair << [getTopologyHelper().getNotNeighboringSwitchPair()] * 2
        flow2SwitchPair << [getDifferentNotNeighboringSwitchPair(flow1SwitchPair)] * 2
        flow1 << [getFirstFlow(flow1SwitchPair, flow2SwitchPair)] * 2
        flow2 << [getSecondFlow(flow1SwitchPair, flow2SwitchPair, flow1)] * 2
        [flow1Src, flow1Dst, flow2Src, flow2Dst] << [
                [flow1.source, changePropertyValue(flow1.destination, "portNumber", flow2.destination.portNumber),
                 flow1.source, changePropertyValue(flow2.destination, "portNumber", flow1.destination.portNumber)],
                [changePropertyValue(flow1.source, "portNumber", flow2.source.portNumber), flow1.destination,
                 changePropertyValue(flow2.source, "portNumber", flow1.source.portNumber), flow1.destination]
        ]
    }

    @Tidy
    def "Unable to swap ports for two flows (port is occupied by ISL on src switch)"() {
        given: "Two active flows"
        def islPort
        def swPair = topologyHelper.switchPairs.find {
            def busyPorts = topology.getBusyPortsForSwitch(it.src)
            islPort = topology.getAllowedPortsForSwitch(it.dst).find { it in busyPorts }
        }
        assert islPort
        def flow1 = flowHelper.randomFlow(swPair)
        def flow2 = changePropertyValue(flowHelper.randomFlow(swPair, false, [flow1]),
                "destination", "portNumber", islPort)
        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        when: "Try to swap ports for two flows"
        northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(
                        changePropertyValue(flow1.source, "portNumber", flow2.destination.portNumber)),
                        flowHelper.toFlowEndpointV2(flow1.destination)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2.source),
                        flowHelper.toFlowEndpointV2(changePropertyValue(
                                flow1.destination, "portNumber", flow2.source.portNumber))))

        then: "An error is received (400 code)"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.to(MessageError).errorMessage == "Can not swap endpoints for flows: " +
                "The port $islPort on the switch '${swPair.src.dpId}' is occupied by an ISL."

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }
    }

    @Tidy
    def "Able to swap endpoints for two flows when all bandwidth on ISL is consumed"() {
        setup: "Create two flows with different source and the same destination switches"
        List<SwitchPair> switchPairs = topologyHelper.allNeighboringSwitchPairs.inject(null) { result, switchPair ->
            if (result) return result
            def halfDifferent = getHalfDifferentNeighboringSwitchPair(switchPair, "dst")
            if (halfDifferent) result = [switchPair, halfDifferent]
            return result
        }
        SwitchPair flow1SwitchPair = switchPairs[0]
        SwitchPair flow2SwitchPair = switchPairs[1]
        def flow1 = getFirstFlow(flow1SwitchPair, flow2SwitchPair)
        def flow2 = getSecondFlow(flow1SwitchPair, flow2SwitchPair, flow1)

        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        and: "Update the first flow so that it consumes all bandwidth on the link"
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.id))
        def flow1Isl = pathHelper.getInvolvedIsls(flow1Path)[0]
        def flow1IslMaxBw = islUtils.getIslInfo(flow1Isl).get().maxBandwidth
        northbound.updateFlow(flow1.id, flow1.tap { it.maximumBandwidth = flow1IslMaxBw })

        and: "Break all alternative paths for the first flow"
        def altPaths = flow1SwitchPair.paths.findAll { it != flow1Path }
        List<PathNode> broughtDownPorts = []
        altPaths.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        and: "Update max bandwidth for the second flow's link so that it is equal to max bandwidth of the first flow"
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow2.id))
        def flow2Isl = pathHelper.getInvolvedIsls(flow2Path)[0]
        northbound.updateLinkMaxBandwidth(flow2Isl.srcSwitch.dpId, flow2Isl.srcPort, flow2Isl.dstSwitch.dpId,
                flow2Isl.dstPort, flow1IslMaxBw)

        and: "Break all alternative paths for the second flow"
        altPaths = flow2SwitchPair.paths.findAll { it != flow2Path && it[1].switchId != flow1SwitchPair.src.dpId }
        altPaths.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        when: "Try to swap endpoints for two flows"
        def flow1Src = flow2.source
        def flow1Dst = changePropertyValue(flow1.destination, "portNumber", flow2.destination.portNumber)
        def flow2Src = flow1.source
        def flow2Dst = changePropertyValue(flow2.destination, "portNumber", flow1.destination.portNumber)
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "Endpoints are successfully swapped"
        verifyEndpoints(response, flow1Src, flow1Dst, flow2Src, flow2Dst)
        verifyEndpoints(flow1.id, flow2.id, flow1Src, flow1Dst, flow2Src, flow2Dst)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(flow1SwitchPair)
        validateSwitches(flow2SwitchPair)

        cleanup: "Restore topology and delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tidy
    def "Unable to swap endpoints for two flows when not enough bandwidth on ISL"() {
        setup: "Create two flows with different source and the same destination switches"
        List<SwitchPair> switchPairs = topologyHelper.allNeighboringSwitchPairs.inject(null) { result, switchPair ->
            if (result) return result
            def halfDifferent = getHalfDifferentNeighboringSwitchPair(switchPair, "dst")
            if (halfDifferent) result = [switchPair, halfDifferent]
            return result
        }
        SwitchPair flow1SwitchPair = switchPairs[0]
        SwitchPair flow2SwitchPair = switchPairs[1]
        def flow1 = getFirstFlow(flow1SwitchPair, flow2SwitchPair)
        def flow2 = getSecondFlow(flow1SwitchPair, flow2SwitchPair, flow1)

        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        and: "Update the first flow so that it consumes all bandwidth on the link"
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.id))
        def flow1Isl = pathHelper.getInvolvedIsls(flow1Path)[0]
        def flow1IslMaxBw = islUtils.getIslInfo(flow1Isl).get().maxBandwidth
        northbound.updateFlow(flow1.id, flow1.tap { it.maximumBandwidth = flow1IslMaxBw })

        and: "Break all alternative paths for the first flow"
        def altPaths = flow1SwitchPair.paths.findAll { it != flow1Path }
        List<PathNode> broughtDownPorts = []
        altPaths.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        and: "Update max bandwidth for the second flow's link so that it is not enough bandwidth for the first flow"
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow2.id))
        def flow2Isl = pathHelper.getInvolvedIsls(flow2Path)[0]
        northbound.updateLinkMaxBandwidth(flow2Isl.srcSwitch.dpId, flow2Isl.srcPort, flow2Isl.dstSwitch.dpId,
                flow2Isl.dstPort, flow1IslMaxBw - 1)

        and: "Break all alternative paths for the second flow"
        altPaths = flow2SwitchPair.paths.findAll { it != flow2Path && it[1].switchId != flow1SwitchPair.src.dpId }
        altPaths.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        when: "Try to swap endpoints for two flows"
        def flow1Src = flow2.source
        def flow1Dst = changePropertyValue(flow1.destination, "portNumber", flow2.destination.portNumber)
        def flow2Src = flow1.source
        def flow2Dst = changePropertyValue(flow2.destination, "portNumber", flow1.destination.portNumber)
        northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "An error is received (400 code)"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.to(MessageError).errorMessage == "Can not swap endpoints for flows: " +
                "Failed to find path with requested bandwidth=${flow1.maximumBandwidth}: " +
                "Switch ${flow2SwitchPair.src.dpId} doesn't have links with enough bandwidth"

        cleanup: "Restore topology and delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Able to swap endpoints for two flows when not enough bandwidth on ISL and ignore_bandwidth=true"() {
        setup: "Create two flows with different source and the same destination switches"
        List<SwitchPair> switchPairs = topologyHelper.allNeighboringSwitchPairs.inject(null) { result, switchPair ->
            if (result) return result
            def halfDifferent = getHalfDifferentNeighboringSwitchPair(switchPair, "dst")
            if (halfDifferent) result = [switchPair, halfDifferent]
            return result
        }
        SwitchPair flow1SwitchPair = switchPairs[0]
        SwitchPair flow2SwitchPair = switchPairs[1]
        def flow1 = getFirstFlow(flow1SwitchPair, flow2SwitchPair)
        def flow2 = getSecondFlow(flow1SwitchPair, flow2SwitchPair, flow1)

        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2.tap { it.ignoreBandwidth = true })

        and: "Update the first flow so that it consumes all bandwidth on the link"
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.id))
        def flow1Isl = pathHelper.getInvolvedIsls(flow1Path)[0]
        def flow1IslMaxBw = islUtils.getIslInfo(flow1Isl).get().maxBandwidth
        northbound.updateFlow(flow1.id, flow1.tap { it.maximumBandwidth = flow1IslMaxBw })

        and: "Break all alternative paths for the first flow"
        def altPaths = flow1SwitchPair.paths.findAll { it != flow1Path }
        List<PathNode> broughtDownPorts = []
        altPaths.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        and: "Update max bandwidth for the second flow's link so that it is not enough bandwidth for the first flow"
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow2.id))
        def flow2Isl = pathHelper.getInvolvedIsls(flow2Path)[0]
        northbound.updateLinkMaxBandwidth(flow2Isl.srcSwitch.dpId, flow2Isl.srcPort, flow2Isl.dstSwitch.dpId,
                flow2Isl.dstPort, flow1IslMaxBw - 1)

        and: "Break all alternative paths for the second flow"
        altPaths = flow2SwitchPair.paths.findAll { it != flow2Path && it[1].switchId != flow1SwitchPair.src.dpId }
        altPaths.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        when: "Try to swap endpoints for two flows"
        def flow1Src = flow2.source
        def flow1Dst = changePropertyValue(flow1.destination, "portNumber", flow2.destination.portNumber)
        def flow2Src = flow1.source
        def flow2Dst = changePropertyValue(flow2.destination, "portNumber", flow1.destination.portNumber)
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "Endpoints are successfully swapped"
        verifyEndpoints(response, flow1Src, flow1Dst, flow2Src, flow2Dst)
        verifyEndpoints(flow1.id, flow2.id, flow1Src, flow1Dst, flow2Src, flow2Dst)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(flow1SwitchPair)
        validateSwitches(flow2SwitchPair)

        cleanup: "Restore topology and delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tidy
    def "Unable to swap endpoints for two flows when one of them is inactive"() {
        setup: "Create two flows with different source and the same destination switches"
        List<SwitchPair> switchPairs = topologyHelper.allNeighboringSwitchPairs.inject(null) { result, switchPair ->
            if (result) return result
            def halfDifferent = getHalfDifferentNeighboringSwitchPair(switchPair, "dst")
            if (halfDifferent) result = [switchPair, halfDifferent]
            return result
        }
        SwitchPair flow1SwitchPair = switchPairs[0]
        SwitchPair flow2SwitchPair = switchPairs[1]
        def flow1 = getFirstFlow(flow1SwitchPair, flow2SwitchPair)
        def flow2 = getSecondFlow(flow1SwitchPair, flow2SwitchPair, flow1)

        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        and: "Break all paths for the first flow"
        List<PathNode> broughtDownPorts = []
        flow1SwitchPair.paths.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
            assert northbound.getFlowStatus(flow1.id).status == FlowState.DOWN
            assert northbound.getFlowHistory(flow1.id).last().histories.find { it.action == REROUTE_FAIL }
        }

        when: "Try to swap endpoints for two flows"
        northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow2.source),
                        flowHelper.toFlowEndpointV2(flow1.destination)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow1.source),
                        flowHelper.toFlowEndpointV2(flow2.destination)))

        then: "An error is received (400 code)"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.to(MessageError).errorMessage == "Can not swap endpoints for flows: " +
                "Failed to find path with requested bandwidth=${flow2.maximumBandwidth}: " +
                "Switch ${flow1SwitchPair.src.dpId} doesn't have links with enough bandwidth"

        cleanup: "Restore topology and delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tidy
    @Unroll
    @Tags(LOW_PRIORITY)
    def "Able to swap endpoints (#description) for two protected flows"() {
        given: "Two protected flows with different source and destination switches"
        flowHelper.addFlow(flow1.tap { it.allocateProtectedPath = true })
        flowHelper.addFlow(flow2.tap { it.allocateProtectedPath = true })

        when: "Try to swap endpoints for flows"
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "Endpoints are successfully swapped"
        verifyEndpoints(response, flow1Src, flow1Dst, flow2Src, flow2Dst)
        verifyEndpoints(flow1.id, flow2.id, flow1Src, flow1Dst, flow2Src, flow2Dst)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(flow1SwitchPair)
        validateSwitches(flow2SwitchPair)

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }

        where:
        description << ["src1 <-> src2", "dst1 <-> dst2", "src1 <-> dst2", "dst1 <-> src2"]
        flow1SwitchPair << [getTopologyHelper().getNotNeighboringSwitchPair()] * 4
        flow2SwitchPair << [getDifferentNotNeighboringSwitchPair(flow1SwitchPair)] * 4
        flow1 << [getFlowHelper().randomFlow(flow1SwitchPair)] * 4
        flow2 << [getFlowHelper().randomFlow(flow2SwitchPair)] * 4
        [flow1Src, flow1Dst, flow2Src, flow2Dst] << [
                [flow2.source, flow1.destination, flow1.source, flow2.destination],
                [flow1.source, flow2.destination, flow2.source, flow1.destination],
                [flow2.destination, flow1.destination, flow2.source, flow1.source],
                [flow1.source, flow2.source, flow1.destination, flow2.destination]
        ]
    }

    @Tidy
    @Unroll
    def "A protected flow with swapped endpoint allows traffic on main and protected paths"() {
        given: "Two protected flows with different source and destination switches"
        def tgSwitches = topology.getActiveTraffGens()*.getSwitchConnected()
        assumeTrue("Not enough traffgen switches found", tgSwitches.size() > 1)

        SwitchPair flow2SwitchPair = null
        SwitchPair flow1SwitchPair = topologyHelper.getAllNeighboringSwitchPairs().find { firstPair ->
            def firstOk = !(firstPair.src in tgSwitches) && firstPair.dst in tgSwitches
            flow2SwitchPair = topologyHelper.getAllNeighboringSwitchPairs().find { secondPair ->
                !(secondPair.src in [firstPair.src, firstPair.dst]) &&
                        !(secondPair.dst in [firstPair.src, firstPair.dst]) &&
                        secondPair.src in tgSwitches && !(secondPair.dst in tgSwitches)
            }
            firstOk && flow2SwitchPair
        }
        assumeTrue("Required switch pairs not found in given topology",
                flow1SwitchPair.asBoolean() && flow2SwitchPair.asBoolean())

        def flow1 = flowHelper.randomFlow(flow1SwitchPair)
        def flow2 = flowHelper.randomFlow(flow2SwitchPair)

        flowHelper.addFlow(flow1.tap { it.allocateProtectedPath = true })
        flowHelper.addFlow(flow2.tap { it.allocateProtectedPath = true })

        when: "Try to swap endpoints for flows"
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow2.source),
                        flowHelper.toFlowEndpointV2(flow1.destination)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow1.source),
                        flowHelper.toFlowEndpointV2(flow2.destination)))

        then: "Endpoints are successfully swapped"
        verifyEndpoints(response, flow2.source, flow1.destination, flow1.source, flow2.destination)
        verifyEndpoints(flow1.id, flow2.id, flow2.source, flow1.destination, flow1.source, flow2.destination)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(flow1SwitchPair)
        validateSwitches(flow2SwitchPair)

        and: "The first flow allows traffic on the main path"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(northbound.getFlow(flow1.id), 0, 5)
        [exam.forward, exam.reverse].each { direction ->
            def resources = traffExam.startExam(direction)
            direction.setResources(resources)
            assert traffExam.waitExam(direction).hasTraffic()
        }

        and: "The first flow allows traffic on the protected path"
        northbound.swapFlowPath(flow1.id)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow1.id).status == FlowState.UP }

        def newExam = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(northbound.getFlow(flow1.id), 0, 5)
        [newExam.forward, newExam.reverse].each { direction ->
            def resources = traffExam.startExam(direction)
            direction.setResources(resources)
            assert traffExam.waitExam(direction).hasTraffic()
        }

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }
    }

    @Tidy
    @Unroll
    @Tags(HARDWARE)
    def "Able to swap endpoints (#description) for two vxlan flows with the same source and destination switches"() {
        given: "Two flows with the same source and destination switches"
        flow1.encapsulationType = FlowEncapsulationType.VXLAN
        flow2.encapsulationType = FlowEncapsulationType.VXLAN
        flowHelper.addFlow(flow1)
        flowHelper.addFlow(flow2)

        when: "Try to swap endpoints for flows"
        def response = northbound.swapFlowEndpoint(
                new SwapFlowPayload(flow1.id, flowHelper.toFlowEndpointV2(flow1Src),
                        flowHelper.toFlowEndpointV2(flow1Dst)),
                new SwapFlowPayload(flow2.id, flowHelper.toFlowEndpointV2(flow2Src),
                        flowHelper.toFlowEndpointV2(flow2Dst)))

        then: "Endpoints are successfully swapped"
        verifyEndpoints(response, flow1Src, flow1Dst, flow2Src, flow2Dst)
        verifyEndpoints(flow1.id, flow2.id, flow1Src, flow1Dst, flow2Src, flow2Dst)

        and: "Flows validation doesn't show any discrepancies"
        validateFlows(flow1, flow2)

        and: "Switch validation doesn't show any missing/excess rules and meters"
        validateSwitches(switchPair)

        cleanup: "Delete flows"
        [flow1, flow2].each { flowHelper.deleteFlow(it.id) }

        where:
        description << ["src1 <-> src2", "dst1 <-> dst2"]
        switchPair << [getTopologyHelper().getAllNeighboringSwitchPairs().find {
            it.src.noviflow && !it.src.wb5164 && it.dst.noviflow && !it.dst.wb5164
        }] * 2
        flow1 << [getFirstFlow(switchPair, switchPair)] * 2
        flow2 << [getSecondFlow(switchPair, switchPair, flow1)] * 2
        [flow1Src, flow1Dst, flow2Src, flow2Dst] << [
                [flow2.source, flow1.destination, flow1.source, flow2.destination],
                [flow1.source, flow2.destination, flow2.source, flow1.destination]
        ]
    }

    void verifyEndpoints(response, FlowEndpointPayload flow1SrcExpected, FlowEndpointPayload flow1DstExpected,
            FlowEndpointPayload flow2SrcExpected, FlowEndpointPayload flow2DstExpected) {
        verifyEndpoints(response, flowHelper.toFlowEndpointV2(flow1SrcExpected),
                flowHelper.toFlowEndpointV2(flow1DstExpected), flowHelper.toFlowEndpointV2(flow2SrcExpected),
                flowHelper.toFlowEndpointV2(flow2DstExpected))
    }

    void verifyEndpoints(response, FlowEndpointV2 flow1SrcExpected, FlowEndpointV2 flow1DstExpected,
            FlowEndpointV2 flow2SrcExpected, FlowEndpointV2 flow2DstExpected) {
        assert response.firstFlow.source == flow1SrcExpected
        assert response.firstFlow.destination == flow1DstExpected
        assert response.secondFlow.source == flow2SrcExpected
        assert response.secondFlow.destination == flow2DstExpected
    }

    void verifyEndpoints(flow1Id, flow2Id, FlowEndpointV2 flow1SrcExpected, FlowEndpointV2 flow1DstExpected,
            FlowEndpointV2 flow2SrcExpected, FlowEndpointV2 flow2DstExpected) {
        def flow1Updated = northbound.getFlow(flow1Id)
        def flow2Updated = northbound.getFlow(flow2Id)

        assert flowHelper.toFlowEndpointV2(flow1Updated.source) == flow1SrcExpected
        assert flowHelper.toFlowEndpointV2(flow1Updated.destination) == flow1DstExpected
        assert flowHelper.toFlowEndpointV2(flow2Updated.source) == flow2SrcExpected
        assert flowHelper.toFlowEndpointV2(flow2Updated.destination) == flow2DstExpected
    }

    void verifyEndpoints(flow1Id, flow2Id, FlowEndpointPayload flow1SrcExpected, FlowEndpointPayload flow1DstExpected,
            FlowEndpointPayload flow2SrcExpected, FlowEndpointPayload flow2DstExpected) {
        verifyEndpoints(flow1Id, flow2Id, flowHelper.toFlowEndpointV2(flow1SrcExpected),
                flowHelper.toFlowEndpointV2(flow1DstExpected), flowHelper.toFlowEndpointV2(flow2SrcExpected),
                flowHelper.toFlowEndpointV2(flow2DstExpected))
    }

    void validateFlows(flow1, flow2) {
        Wrappers.wait(RULES_DELETION_TIME + RULES_INSTALLATION_TIME) {
            [flow1, flow2].each {
                assert northbound.validateFlow(it.id).each { direction -> assert direction.asExpected }
            }
        }
    }

    void validateSwitches(SwitchPair switchPair) {
        validateSwitches([switchPair.src, switchPair.dst])
    }

    void validateSwitches(List<Switch> switches) {
        Wrappers.wait(RULES_DELETION_TIME + RULES_INSTALLATION_TIME) {
            switches.each {
                if (it.ofVersion == "OF_13") {
                    def validationResult = northbound.validateSwitch(it.dpId)
                    switchHelper.verifyRuleSectionsAreEmpty(validationResult, ["missing", "excess"])
                    switchHelper.verifyMeterSectionsAreEmpty(validationResult, ["missing", "misconfigured", "excess"])
                } else {
                    def validationResult = northbound.validateSwitchRules(it.dpId)
                    assert validationResult.missingRules.size() == 0
                    assert validationResult.excessRules.size() == 0
                }
            }
        }
    }

    /**
     * Get port number which is not busy on _any_ of the specified switches
     *
     * @param target switch on which to look for a port
     * @param switches list of switches where resulting port should not be an ISL-busy port
     * @return portnumber which is not an ISL-port on any of the switches
     */
    Integer getFreePort(Switch target, List<Switch> switches, List<Integer> excludePorts = []) {
        pickRandom(topology.getAllowedPortsForSwitch(target) -
                switches.collectMany { topology.getBusyPortsForSwitch(it) } - excludePorts)
    }

    /**
     * Get a free vlan which is not used in any of the given flows.
     */
    def getFreeVlan(SwitchId swId, List<FlowCreatePayload> existingFlows = []) {
        def r = new Random()
        def vlans =  (flowHelper.allowedVlans - existingFlows.collectMany { [it.source, it.destination] }.findAll {
            it.datapath == swId
        }.collect { it.vlanId })
        return vlans[r.nextInt(vlans.size())]
    }

    /**
     * Get a FlowCreatePayload instance for the flow. The instance is built considering ISL ports on source and
     * destination switches of the first and second switch pairs. So no ISL port conflicts should appear while creating
     * the flow and swapping flow endpoints.
     *
     * @param firstFlowSwitchPair Switch pair for the first flow
     * @param secondFlowSwitchPair Switch pair for the second flow
     * @return a FlowCreatePayload instance
     */
    def getFirstFlow(SwitchPair firstFlowSwitchPair, SwitchPair secondFlowSwitchPair, noVlans = false) {
        def firstFlow = flowHelper.randomFlow(firstFlowSwitchPair)
        firstFlow.source.portNumber = (topology.getAllowedPortsForSwitch(firstFlowSwitchPair.src) -
                topology.getBusyPortsForSwitch(secondFlowSwitchPair.src) -
                topology.getBusyPortsForSwitch(secondFlowSwitchPair.dst))[0]
        firstFlow.destination.portNumber = (topology.getAllowedPortsForSwitch(firstFlowSwitchPair.dst) -
                topology.getBusyPortsForSwitch(secondFlowSwitchPair.src) -
                topology.getBusyPortsForSwitch(secondFlowSwitchPair.dst) - [firstFlow.source.portNumber])[0]

        if (noVlans) {
            firstFlow.source.vlanId = null
            firstFlow.destination.vlanId = null
        }

        return firstFlow
    }

    private static <T> T pickRandom(List<T> c) {
        def r = new Random()
        c[r.nextInt(c.size())]
    }

    /**
     * Get a FlowCreatePayload instance for the second flow. The instance is built considering ISL ports on source and
     * destination switches of the first and second switch pairs. Also ports of the first flow are considered as well.
     * So no conflicts should appear while creating the flow and swapping flow endpoints.
     *
     * @param firstFlowSwitchPair Switch pair for the first flow
     * @param secondFlowSwitchPair Switch pair for the second flow
     * @param firstFlow The first flow instance
     * @param noVlans Whether use vlans or not
     * @return a FlowCreatePayload instance
     */
    def getSecondFlow(firstFlowSwitchPair, secondFlowSwitchPair, firstFlow, noVlans = false) {
        def secondFlow = flowHelper.randomFlow(secondFlowSwitchPair)
        secondFlow.source.portNumber = (topology.getAllowedPortsForSwitch(secondFlowSwitchPair.src) -
                topology.getBusyPortsForSwitch(firstFlowSwitchPair.src) -
                topology.getBusyPortsForSwitch(firstFlowSwitchPair.dst) -
                [firstFlow.source.portNumber, firstFlow.destination.portNumber])[0]
        secondFlow.destination.portNumber = (topology.getAllowedPortsForSwitch(secondFlowSwitchPair.dst) -
                topology.getBusyPortsForSwitch(firstFlowSwitchPair.src) -
                topology.getBusyPortsForSwitch(firstFlowSwitchPair.dst) -
                [secondFlow.source.portNumber, firstFlow.source.portNumber, firstFlow.destination.portNumber])[0]

        if (noVlans) {
            secondFlow.source.vlanId = null
            secondFlow.destination.vlanId = null
        }

        return secondFlow
    }

    def getConflictingFlow(switchPair, neighboringFlow, conflictingEndpointName, conflictingEndpointValue) {
        def conflictingFlow = flowHelper.randomFlow(switchPair, false, [neighboringFlow])
        conflictingFlow."$conflictingEndpointName" = conflictingEndpointValue

        return conflictingFlow
    }

    def changePropertyValue(flowEndpoint, propertyName, newValue) {
        // Deep copy of object
        def mapper = new ObjectMapper()
        return mapper.readValue(mapper.writeValueAsString(flowEndpoint), FlowEndpointPayload).tap {
            it."$propertyName" = newValue
        }
    }

    def changePropertyValue(flow, endpointName, propertyName, newValue) {
        // Deep copy of object
        def mapper = new ObjectMapper()
        return mapper.readValue(mapper.writeValueAsString(flow), FlowCreatePayload).tap {
            it."$endpointName"."$propertyName" = newValue
        }
    }

    def getHalfDifferentNotNeighboringSwitchPair(switchPairToAvoid, equalEndpoint) {
        def differentEndpoint = (equalEndpoint == "src" ? "dst" : "src")
        topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it."$equalEndpoint" == switchPairToAvoid."$equalEndpoint" &&
                    it."$differentEndpoint" != switchPairToAvoid."$differentEndpoint"
        }
    }

    def getDifferentNotNeighboringSwitchPair(switchPairToAvoid) {
        topologyHelper.getAllNotNeighboringSwitchPairs().find {
            !(it.src in [switchPairToAvoid.src, switchPairToAvoid.dst]) &&
                    !(it.dst in [switchPairToAvoid.src, switchPairToAvoid.dst])
        }
    }

    def findSw(SwitchId swId) {
        topology.switches.find { it.dpId == swId }
    }

    def getHalfDifferentNeighboringSwitchPair(switchPairToAvoid, equalEndpoint) {
        def differentEndpoint = (equalEndpoint == "src" ? "dst" : "src")
        topologyHelper.getAllNeighboringSwitchPairs().find {
            it."$equalEndpoint" == switchPairToAvoid."$equalEndpoint" &&
                    it."$differentEndpoint" != switchPairToAvoid."$differentEndpoint"
        }
    }
}
