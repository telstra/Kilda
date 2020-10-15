package org.openkilda.performancetests.spec.benchmark

import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.model.cookie.Cookie
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.performancetests.BaseSpecification
import org.openkilda.performancetests.helpers.TopologyBuilder
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import spock.lang.Shared
import spock.lang.Unroll

import java.text.SimpleDateFormat

class FlowCreateSpec extends BaseSpecification {
    @Shared
    def r = new Random()

    @Unroll
    def "Flow creation on mesh topology"() {
        given: "A mesh topology"
        log("Start time " + System.currentTimeMillis())
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
            long time = System.currentTimeMillis();
            flowHelperV2.addFlow(flow)
            log("Created flows: " + flows.size() + ". time for creating last: " + (System.currentTimeMillis() - time))
            flows << flow
        }

        log("current time " + System.currentTimeMillis())

        then: "Flows are created"
        assert flows.size() == preset.flowCount

        log("sleeping")
        sleep(30 * 1000)
        log("wake up")

        cleanup: "Remove all flows, delete topology"
        flows.each { northbound.deleteFlow(it.flowId) }
        Wrappers.wait(flows.size()) {
            topo.switches.each {
                assert northbound.getSwitchRules(it.dpId).flowEntries.findAll { !Cookie.isDefaultRule(it.cookie) }.empty
            }
        }
        topoHelper.purgeTopology(topo)

        where:
        preset << [
                [
                        islandCount      : 1,
                        regionsPerIsland : 3,
                        switchesPerRegion: 10,
                        flowCount        : 300
                ]
        ]
    }

    Switch pickRandom(List<Switch> switches) {
        switches[r.nextInt(switches.size())]
    }

    void log(String message) {
        SimpleDateFormat formatter= new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z")
        Date date = new Date(System.currentTimeMillis())
        println formatter.format(date) + " " + message
    }
}
