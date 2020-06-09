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

class ConcurrentFlowRerouteSpec extends BaseSpecification {
    @Shared
    def r = new Random()

    @Unroll
    def "Flow reroute (concurrent) on mesh topology"() {
        given: "A mesh topology"
        def topo = new TopologyBuilder(topoHelper.regions, topoHelper.managementControllers, topoHelper.statControllers,
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

        and: "Flows are created"
        assert flows.size() == preset.flowCount

        then: "Reroute flows"
        //shuffle all isl costs
        def randomCost = { (r.nextInt(800) + 200).toString() }
        northbound.updateLinkProps(topo.isls.collect {
            islUtils.toLinkProps(it, [cost: randomCost()])
        })

        (1..preset.rerouteAttempts).each {
            Collections.shuffle(flows)
            withPool {
                flows[0..Math.min(flows.size() - 1, preset.concurrentReroutes)].eachParallel { flow ->
                    Wrappers.wait(flows.size()) {
                        northboundV2.rerouteFlow(flow.flowId)
                    }
                }
            }
        }

        and: "Flows are rerouted"
        Wrappers.wait(flows.size()) {
            flows.forEach { assert northbound.getFlowStatus(it.flowId).status == FlowState.UP }
        }

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
                        islandCount       : 1,
                        regionsPerIsland  : 3,
                        switchesPerRegion : 10,
                        flowCount         : 300,
                        concurrentReroutes: 100,
                        rerouteAttempts  : 10
                ]
        ]
    }

    Switch pickRandom(List<Switch> switches) {
        switches[r.nextInt(switches.size())]
    }
}
