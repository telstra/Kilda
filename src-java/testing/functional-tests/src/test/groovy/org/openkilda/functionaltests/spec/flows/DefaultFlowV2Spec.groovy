package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.messaging.error.MessageError
import org.openkilda.model.SwitchFeature
import org.openkilda.model.SwitchId
import org.openkilda.northbound.dto.v1.switches.SwitchPropertiesDto
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.Shared

import javax.inject.Provider

@Narrative("""System allows to create default port(vlan=0) and simple flow(vlan=<any number>) on the same port.
Default flow has lower priority than simple flow.
Also system allows to pass tagged traffic via default flow.""")
class DefaultFlowV2Spec extends HealthCheckSpecification {

    @Autowired @Shared
    Provider<TraffExamService> traffExamProvider

    @Tidy
    @Tags([SMOKE_SWITCHES])
   def "Systems allows to pass traffic via default/vlan and qinq flow when they are on the same port"() {
        given: "At least 3 traffGen switches"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue(allTraffGenSwitches.size() > 2, "Unable to find required switches in topology")

        when: "Create a vlan flow"
        def (Switch srcSwitch, Switch dstSwitch) = allTraffGenSwitches
        Switch newDstSwitch = allTraffGenSwitches.find { it != dstSwitch && it != srcSwitch }
        assumeTrue([srcSwitch, dstSwitch, newDstSwitch].every { it.features.contains(SwitchFeature.MULTI_TABLE) },
 "MultiTable mode should be supported by the src and dst switches")

        Map<SwitchId, SwitchPropertiesDto> initSwProps = [srcSwitch, dstSwitch, newDstSwitch].collectEntries {
            [(it): northbound.getSwitchProperties(it.dpId)]
        }
        initSwProps.each { sw, swProps ->
            switchHelper.updateSwitchProperties(sw, swProps.jacksonCopy().tap {
                it.multiTable = true
            })
        }

        def bandwidth = 100
        def vlanFlow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        vlanFlow.maximumBandwidth = bandwidth
        vlanFlow.allocateProtectedPath = true
        flowHelperV2.addFlow(vlanFlow)

        and: "Create a default flow with the same srcSwitch and different dstSwitch"
        def defaultFlow = flowHelperV2.randomFlow(srcSwitch, newDstSwitch)
        defaultFlow.maximumBandwidth = bandwidth
        defaultFlow.source.vlanId = 0
        defaultFlow.destination.vlanId = 0
        defaultFlow.allocateProtectedPath = true
        flowHelperV2.addFlow(defaultFlow)

        and: "Create a QinQ flow with the same src and dst switch"
        def qinqFlow = flowHelperV2.randomFlow(srcSwitch, newDstSwitch)
        qinqFlow.maximumBandwidth = bandwidth
        qinqFlow.source.vlanId = vlanFlow.source.vlanId
        qinqFlow.destination.vlanId = vlanFlow.destination.vlanId
        qinqFlow.source.innerVlanId = vlanFlow.destination.vlanId
        qinqFlow.destination.innerVlanId = vlanFlow.source.vlanId
        qinqFlow.allocateProtectedPath = true
        flowHelperV2.addFlow(qinqFlow)

        then: "System allows traffic on the vlan flow"
        def traffExam = traffExamProvider.get()
        def examVlanFlow = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(
                flowHelperV2.toV1(vlanFlow), bandwidth, 5
        )
        withPool {
            [examVlanFlow.forward, examVlanFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "System allows traffic on the default flow"
        def examDefaultFlow = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(
                flowHelperV2.toV1(defaultFlow), 1000, 5
        )
        withPool {
            [examDefaultFlow.forward, examDefaultFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "System allows traffic on the QinQ flow"
        def examQinqFlow = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(
                flowHelperV2.toV1(qinqFlow), 1000, 5
        )
        withPool {
            [examQinqFlow.forward, examQinqFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        cleanup:
        [vlanFlow, defaultFlow, qinqFlow].each { flow -> flowHelperV2.deleteFlow(flow.flowId) }
        initSwProps.each { sw, swProps ->
            switchHelper.updateSwitchProperties(sw, swProps)
        }
    }

    @Tidy
    @Tags([SMOKE_SWITCHES])
    def "System allows tagged traffic via default flow(0<->0)"() {
        // we can't test (0<->20, 20<->0) because iperf is not able to establish a connection
        given: "At least 2 traffGen switches"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue(allTraffGenSwitches.size() > 1, "Unable to find required switches in topology")

        when: "Create a default flow"
        def (Switch srcSwitch, Switch dstSwitch) = allTraffGenSwitches
        def defaultFlow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        defaultFlow.source.vlanId = 0
        defaultFlow.destination.vlanId = 0
        flowHelperV2.addFlow(defaultFlow)

        // Issue #3472
        //and: "Create a qinq flow"
        //def qinqFlow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        //qinqFlow.source.vlanId = 10
        //qinqFlow.source.innerVlanId = 200
        //qinqFlow.destination.vlanId = 10
        //qinqFlow.destination.innerVlanId = 300
        //flowHelperV2.addFlow(qinqFlow)

        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flow.source.vlanId = 10
        flow.destination.vlanId = 10

        then: "System allows tagged traffic on the default flow"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(flowHelperV2.toV1(flow), 1000, 3)
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        cleanup: "Delete the flows"
        flowHelperV2.deleteFlow(defaultFlow.flowId)
    }

    @Tidy
    @Tags([SMOKE_SWITCHES])
    def "Unable to send traffic from simple flow into default flow and vice versa"() {
        given: "At least 2 traffGen switches"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue(allTraffGenSwitches.size() > 1, "Unable to find required switches in topology")

        and: "A default flow"
        def (Switch srcSwitch, Switch dstSwitch) = allTraffGenSwitches
        def defaultFlow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        defaultFlow.source.vlanId = 0
        defaultFlow.destination.vlanId = 0
        flowHelperV2.addFlow(defaultFlow)

        and: "A simple flow"
        def simpleflow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        simpleflow.source.vlanId = 10
        simpleflow.destination.vlanId = 10
        flowHelperV2.addFlow(simpleflow)

        when: "Try to send traffic from simple flow into default flow and vice versa"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flow.source.vlanId = 0
        flow.destination.vlanId = 10

        then: "System doesn't allow to send traffic in these directions"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(flowHelperV2.toV1(flow), 1000, 3)
        [exam.forward, exam.reverse].each { direction ->
            def resources = traffExam.startExam(direction)
            direction.setResources(resources)
            assert !traffExam.waitExam(direction).hasTraffic()
        }

        cleanup: "Delete the flows"
        [defaultFlow, simpleflow].each { it && flowHelperV2.deleteFlow(it.flowId) }
    }

    @Tidy
    def "Unable to create two default flow on the same port"() {
        when: "Create first default flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def defaultFlow1 = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        defaultFlow1.source.vlanId = 0
        flowHelperV2.addFlow(defaultFlow1)

        and: "Try to create second default flow on the same port"
        def defaultFlow2 = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        defaultFlow2.source.vlanId = 0
        defaultFlow2.source.portNumber = defaultFlow1.source.portNumber
        flowHelperV2.addFlow(defaultFlow2)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 409
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == "Requested flow '$defaultFlow2.flowId' conflicts with existing flow \
'$defaultFlow1.flowId'. Details: requested flow '$defaultFlow2.flowId' source: switchId=\"$defaultFlow2.source.switchId\" \
port=$defaultFlow2.source.portNumber, existing flow '$defaultFlow1.flowId' \
source: switchId=\"$defaultFlow1.source.switchId\" port=$defaultFlow1.source.portNumber"

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(defaultFlow1.flowId)
        defaultFlow2 && !exc && flowHelperV2.deleteFlow(defaultFlow2.flowId)
    }
}
