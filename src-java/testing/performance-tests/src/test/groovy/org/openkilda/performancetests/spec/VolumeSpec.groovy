package org.openkilda.performancetests.spec

import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.model.cookie.Cookie
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.performancetests.BaseSpecification
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import spock.lang.Shared

class VolumeSpec extends BaseSpecification {

    @Shared
    def r = new Random()

    def setupSpec() {
        topoHelper.purgeTopology()
    }

    def "Able to validate a switch with a lot of flows on different ports"() {
        given: "A small topology"
        def topo = topoHelper.createRandomTopology(5, 15)
        topoHelper.setTopology(topo)
        flowHelperV2.setTopology(topo)

        and: "A switch under test"
        def sw = topo.switches.first()
        def busyPorts = topo.getBusyPortsForSwitch(sw)
        def allowedPorts = (1..200 + busyPorts.size()) - busyPorts //200 total

        when: "Create total 200 flows on a switch, each flow uses free non-isl port"
        List<FlowRequestV2> flows = []
        allowedPorts.each { port ->
            def flow = flowHelperV2.randomFlow(sw, pickRandom(topo.switches - sw), false, flows)
            flow.allocateProtectedPath = r.nextBoolean()
            flow.source.portNumber = port
            northboundV2.addFlow(flow)
            flows << flow
        }

        then: "Each flow passes flow validation"
        Wrappers.wait(flows.size()) {
            flows.forEach { northbound.validateFlow(it.flowId).each { assert it.asExpected } }
        }

        and: "Target switch passes switch validation"
        verifyAll(northbound.validateSwitch(sw.dpId)) {
            it.verifyRuleSectionsAreEmpty(["excess", "missing"])
            it.verifyMeterSectionsAreEmpty(["excess", "missing", "misconfigured"])
        }

        cleanup: "Remove all flows, delete topology"
        flows.each { northbound.deleteFlow(it.flowId) }
        Wrappers.wait(flows.size()) {
            topo.switches.each {
                assert northbound.getSwitchRules(it.dpId).flowEntries.findAll { !Cookie.isDefaultRule(it.cookie) }.empty
            }
        }
        topoHelper.purgeTopology(topo)
    }

    Switch pickRandom(List<Switch> switches) {
        switches[r.nextInt(switches.size())]
    }
}
