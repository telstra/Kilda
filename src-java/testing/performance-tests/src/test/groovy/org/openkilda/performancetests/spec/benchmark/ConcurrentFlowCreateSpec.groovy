package org.openkilda.performancetests.spec.benchmark

import static groovyx.gpars.GParsPool.withPool

import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.cookie.Cookie
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.performancetests.BaseSpecification
import org.openkilda.performancetests.helpers.TopologyBuilder
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import spock.lang.Shared
import spock.lang.Unroll

import java.text.SimpleDateFormat

class ConcurrentFlowCreateSpec extends BaseSpecification {
    @Shared
    def r = new Random()

    @Unroll
    def "Flow creation (concurrent) on mesh topology"() {
        given: "A mesh topology"
        log( "creating topology")
        def topo = new TopologyBuilder(flHelper.fls,
                preset.islandCount, preset.regionsPerIsland, preset.switchesPerRegion).buildMeshes()
        topoHelper.createTopology(topo)
        flowHelperV2.setTopology(topo)
        log( "topology created")

        when: "A source switch"
        log( "src switch")
        def srcSw = topo.switches.first()
        def busyPorts = topo.getBusyPortsForSwitch(srcSw)
        def allowedPorts = (1..(preset.flowCount + busyPorts.size())) - busyPorts
        log( "src switch fin")

        and: "Create flows"
        log( "creating flows")
        List<FlowRequestV2> flows = []
        withPool {
            allowedPorts.eachParallel { port ->
                def flow = flowHelperV2.randomFlow(srcSw, pickRandom(topo.switches - srcSw), false, flows)
                flow.allocateProtectedPath = false
                flow.source.portNumber = port
                northboundV2.addFlow(flow)
                flows << flow
                log( "created flows: " + flows.size())
            }
        }

        then: "Flows are created"
        log( "flows created")
        def i = 0
        Wrappers.wait(flows.size()) {
            log( "check flows: " + i++)
            def j = 0
            flows.forEach {
                assert northbound.getFlowStatus(it.flowId).status == FlowState.UP
                log( "UP flows: " + j++)
            }
        }

        cleanup: "Remove all flows, delete topology"
        log( "Removing flows")
        i = 0
        flows.each {
            northbound.deleteFlow(it.flowId)
            log( "Removing flows: " + i++)
        }
        log( "Remove requests sended")
        Wrappers.wait(flows.size()) {
            topo.switches.each {
                assert northbound.getSwitchRules(it.dpId).flowEntries.findAll { !Cookie.isDefaultRule(it.cookie) }.empty
                log( "check switch $it.dpId")
            }
        }
        log( "removing topology")
        topoHelper.purgeTopology(topo)
        log( "topology removed")

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
