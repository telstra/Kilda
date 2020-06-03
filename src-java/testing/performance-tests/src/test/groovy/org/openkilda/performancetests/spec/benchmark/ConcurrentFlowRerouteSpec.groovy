package org.openkilda.performancetests.spec.benchmark

import static groovyx.gpars.GParsPool.withPool

import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.performancetests.BaseSpecification
import org.openkilda.performancetests.helpers.TopologyBuilder
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import groovy.util.logging.Slf4j
import spock.lang.Shared
import spock.lang.Unroll

@Slf4j
class ConcurrentFlowRerouteSpec extends BaseSpecification {
    @Shared
    def r = new Random()

    @Unroll
    def "Flow reroute (concurrent) on mesh topology"() {
        given: "A mesh topology"
        def topo = new TopologyBuilder(flHelper.fls,
                preset.islandCount, preset.regionsPerIsland, preset.switchesPerRegion).buildMeshes()
        topoHelper.createTopology(topo)
        flowHelperV2.setTopology(topo)

        when: "A source switch"
        def srcSw = topo.switches.first()
        def busyPorts = topo.getBusyPortsForSwitch(srcSw)
        def allowedPorts = (1..(preset.flowCount + busyPorts.size())) - busyPorts
        def busyPortsFirstHalf = busyPorts.chop((int) (busyPorts.size() / 2))[0]

        and: "Create flows"
        northbound.updateLinkProps(topo.isls.findAll { isl ->
            isl.srcSwitch.dpId == srcSw.dpId
        }.collect { isl ->
            islUtils.toLinkProps(isl, [cost: busyPortsFirstHalf.contains(isl.srcPort) ? "1" : "5000"])
        })

        List<FlowRequestV2> flows = []
        allowedPorts.each { port ->
            def flow = flowHelperV2.randomFlow(srcSw, pickRandom(topo.switches - srcSw), false, flows)
            flow.allocateProtectedPath = false
            flow.source.portNumber = port
            flowHelperV2.addFlow(flow)
            flows << flow
        }
        Collections.shuffle(flows)

        and: "Flows are created"
        assert flows.size() == preset.flowCount

        then: "Reroute flows"
        (1..(int)(preset.maxConcurrentReroutes / 10)).each { iteration ->
            def concurrentReroutes = iteration * 10
            log.info("Running reroutes with #$concurrentReroutes concurrent requests")
            (1..preset.rerouteAttempts).each { attempt ->
                northbound.updateLinkProps(topo.isls.findAll { isl ->
                    isl.srcSwitch.dpId == srcSw.dpId
                }.collect { isl ->
                    islUtils.toLinkProps(isl, [cost: (busyPortsFirstHalf.contains(isl.srcPort) ^ attempt % 2 != 0) ? "1" : "5000"])
                })

                withPool(concurrentReroutes) {
                    flows[0..Math.min(flows.size() - 1, concurrentReroutes)].eachParallel { flow ->
                        Wrappers.wait(flows.size()) {
                            northboundV2.rerouteFlow(flow.flowId)
                        }
                    }
                }

                Wrappers.wait(flows.size()) {
                    flows.forEach { assert northbound.getFlowStatus(it.flowId).status == FlowState.UP }
                }
            }
        }

        where:
        preset << [
                [
                        islandCount       : 1,
                        regionsPerIsland  : 3,
                        switchesPerRegion : 10,
                        flowCount         : 300,
                        maxConcurrentReroutes: 100,
                        rerouteAttempts   : 10,
                ]
        ]
    }

    Switch pickRandom(List<Switch> switches) {
        switches[r.nextInt(switches.size())]
    }
}
