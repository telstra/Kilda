package org.openkilda.performancetests.spec.benchmark

import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.model.cookie.Cookie
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.performancetests.BaseSpecification
import org.openkilda.performancetests.helpers.TopologyBuilder
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import spock.lang.Shared
import spock.lang.Unroll

class FlowCreateSpec extends BaseSpecification {
    @Shared
    def r = new Random()

    @Unroll
    def "Flow creation on mesh topology"() {
        given: "A mesh topology"
        def topo = new TopologyBuilder(flHelper.fls,
                preset.islandCount, preset.regionsPerIsland, preset.switchesPerRegion).buildMeshes()
        topoHelper.createTopology(topo)
        flowHelperV2.setTopology(topo)

        when: "A source switch"
        def srcSw = topo.switches.first()
        def busyPorts = topo.getBusyPortsForSwitch(srcSw)
        def allowedPorts = (1..(preset.flowCount + busyPorts.size())) - busyPorts

        and: "Create flows"
        List<FlowRequestV2> flows = []
        allowedPorts.each { port ->
            def flow = flowHelperV2.randomFlow(srcSw, pickRandom(topo.switches - srcSw), false, flows)
            flow.allocateProtectedPath = false
            flow.source.portNumber = port
            flowHelperV2.addFlow(flow)
            flows << flow
        }

        then: "Flows are created"
        assert flows.size() == preset.flowCount

        where:
        preset << [
                [
                        islandCount      : 1,
                        regionsPerIsland : 3,
                        switchesPerRegion: 10,
                        flowCount        : 1000
                ]
        ]
    }

    Switch pickRandom(List<Switch> switches) {
        switches[r.nextInt(switches.size())]
    }
}
