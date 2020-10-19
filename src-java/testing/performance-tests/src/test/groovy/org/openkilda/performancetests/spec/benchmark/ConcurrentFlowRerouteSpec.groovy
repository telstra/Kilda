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
import java.util.concurrent.CopyOnWriteArrayList

class ConcurrentFlowRerouteSpec extends BaseSpecification {
    @Shared
    def r = new Random()

    @Unroll
    def "Flow reroute (concurrent) on mesh topology"() {
        given: "A mesh topology"
        log("start time " + System.currentTimeMillis())
        log( "creating topology")
        def topo = new TopologyBuilder(flHelper.fls,
                preset.islandCount, preset.regionsPerIsland, preset.switchesPerRegion).buildMeshesWithRegionRing()
        topoHelper.createTopology(topo)
        flowHelperV2.setTopology(topo)
        log( "topology created")
        and: "init ISLs cost"
        log("init ISLs cost")
        northbound.updateLinkProps([
            islUtils.toLinkProps(topo.islands[0].islsBetweenRegions[2], [cost: "1000000"]),
            islUtils.toLinkProps(topo.islands[0].islsBetweenRegions[3], [cost: "1000000"]),
        ])

        when: "A source switch"
        def srcSw = topo.islands[0].regions[0].switches.first()
        def busyPorts = topo.getBusyPortsForSwitch(srcSw)
        def allowedPorts = (1..(preset.flowCount + busyPorts.size())) - busyPorts
        and: "Create flows"
        log( "creating flows")
        CopyOnWriteArrayList<FlowRequestV2> flows = new CopyOnWriteArrayList<>()
        log("total flow count " + flows.size())
        withPool {
            allowedPorts.eachParallel { port ->
                def flow = flowHelperV2.randomFlow(srcSw, pickRandom(topo.islands[0].regions[2].switches), false, flows)
                flow.allocateProtectedPath = false
                flow.source.portNumber = port
                northboundV2.addFlow(flow)
                flows << flow
                log( "created flows: " + flows.size())
            }
        }
        then: "Flows are created"
        log("total flow count " + flows.size())
        log( "flows created")
        def f = 0
        Wrappers.wait(flows.size()) {
            log( "check flows: " + f++)
            def j = 0
            flows.forEach {
                assert northbound.getFlowStatus(it.flowId).status == FlowState.UP
                log( "Created UP flows: " + j++)
            }
        }

        and: "Flows are created"
        log( "flows created and UP " + System.currentTimeMillis())
        assert flows.size() == preset.flowCount

        then: "Reroute flows"
        //shuffle all isl costs
        log( "updating link costs")
        northbound.updateLinkProps([
                islUtils.toLinkProps(topo.islands[0].islsBetweenRegions[0], [cost: "1000000"]),
                islUtils.toLinkProps(topo.islands[0].islsBetweenRegions[1], [cost: "1000000"]),
                islUtils.toLinkProps(topo.islands[0].islsBetweenRegions[2], [cost: "0"]),
                islUtils.toLinkProps(topo.islands[0].islsBetweenRegions[3], [cost: "0"])
        ])
        (1..preset.rerouteAttempts).each {
            int count = 1
            withPool {
                flows[0..Math.min(flows.size() - 1, preset.concurrentReroutes)].eachParallel { flow ->
                    Wrappers.wait(flows.size()) {
                        northboundV2.rerouteFlow(flow.flowId)
                        log("rerouted " + count++ + " flows")
                    }
                }
            }
        }
        and: "Flows are rerouted"
        log("waiting for reroute completion " + System.currentTimeMillis())
        def i = 0
        Wrappers.wait(flows.size()) {
            log( "check flows: " + i++)
            def j = 0
            flows.forEach {
                assert northbound.getFlowStatus(it.flowId).status == FlowState.UP
                log( "UP flows: " + j++)
            }
        }
        log("current time " + System.currentTimeMillis())
        log("sleeping")
        sleep(30 * 1000)
        log("wake up")

        cleanup: "Remove all flows, delete topology"
        log("Removing flows")
        i = 0
        flows.each {
            northbound.deleteFlow(it.flowId)
            log( "Removing flows: " + i++)
        }
        log( "Remove requests sended at " + System.currentTimeMillis())
        Wrappers.wait(flows.size()) {
            topo.switches.each {
                assert northbound.getSwitchRules(it.dpId).flowEntries.findAll { !Cookie.isDefaultRule(it.cookie) }.empty
                log( "check switch $it.dpId")
            }
        }
        topoHelper.purgeTopology(topo)
        where:
        preset << [
                [
                        islandCount       : 1,
                        regionsPerIsland  : 4,
                        switchesPerRegion : 10,
                        flowCount         : 300,
                        concurrentReroutes: 200,
                        rerouteAttempts  : 1
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