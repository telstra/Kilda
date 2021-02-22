package org.openkilda.functionaltests.spec.switches

import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.testing.Constants.STATS_LOGGING_TIMEOUT
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.testing.model.topology.TopologyDefinition.Isl

import org.springframework.beans.factory.annotation.Value
import spock.lang.Narrative
import spock.lang.Unroll

@Narrative("Verify that Kilda allows to properly control port state on switches (bring ports up or down).")
@Tags([SMOKE_SWITCHES])
class SwitchPortConfigSpec extends HealthCheckSpecification {

    @Value('${opentsdb.metric.prefix}')
    String metricPrefix

    def otsdbPortUp = 1
    def otsdbPortDown = 0

    @Tidy
    @Unroll
    @Tags([TOPOLOGY_DEPENDENT, SMOKE])
    def "Able to bring ISL-busy port down/up on an #isl.srcSwitch.ofVersion switch(#isl.srcSwitch.dpId)"() {
        when: "Bring port down on the switch"
        def portDownTime = new Date()
        antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)

        then: "Forward and reverse ISLs between switches becomes 'FAILED'"
        Wrappers.wait(WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            assert islUtils.getIslInfo(links, isl).get().state == IslChangeType.FAILED
            assert islUtils.getIslInfo(links, isl.reversed).get().state == IslChangeType.FAILED
        }

        and: "Port failure is logged in OpenTSDB"
        def statsData = [:]
        Wrappers.wait(STATS_LOGGING_TIMEOUT) {
            statsData = otsdb.query(portDownTime, metricPrefix + "switch.state",
                    [switchid: isl.srcSwitch.dpId.toOtsdFormat(), port: isl.srcPort]).dps
            assert statsData.size() == 1
        }
        statsData.values().first() == otsdbPortDown

        when: "Bring port up on the switch"
        def portUpTime = new Date()
        antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)

        then: "Forward and reverse ISLs between switches becomes 'DISCOVERED'"
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            def links = northbound.getAllLinks()
            assert islUtils.getIslInfo(links, isl).get().state == IslChangeType.DISCOVERED
            assert islUtils.getIslInfo(links, isl.reversed).get().state == IslChangeType.DISCOVERED
        }

        and: "Port UP event is logged in OpenTSDB"
        Wrappers.wait(STATS_LOGGING_TIMEOUT) {
            statsData = otsdb.query(portUpTime, metricPrefix + "switch.state",
                    [switchid: isl.srcSwitch.dpId.toOtsdFormat(), port: isl.srcPort]).dps
            assert statsData.size() == 1
        }
        statsData.values().first() == otsdbPortUp

        cleanup:
        if (portDownTime && !portUpTime) {
            antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
            Wrappers.wait(discoveryInterval + WAIT_OFFSET + discoveryAuxiliaryInterval) {
                def links = northbound.getAllLinks()
                assert islUtils.getIslInfo(links, isl).get().state == IslChangeType.DISCOVERED
                assert islUtils.getIslInfo(links, isl.reversed).get().state == IslChangeType.DISCOVERED
                assert islUtils.getIslInfo(links, isl).get().actualState == IslChangeType.DISCOVERED
                assert islUtils.getIslInfo(links, isl.reversed).get().actualState == IslChangeType.DISCOVERED
            }
        }
        database.resetCosts()

        where:
        isl << uniqueIsls
    }

    @Tidy
    @Unroll
    @Tags([HARDWARE, TOPOLOGY_DEPENDENT])
    def "Able to bring ISL-free port down/up on an #sw.ofVersion switch(#sw.dpId)"() {
        // Not checking OTSDB here, since Kilda won't log into OTSDB for isl-free ports, this is expected.

        when: "Bring port down on the switch"
        def port = topology.getAllowedPortsForSwitch(sw).find { "LINK_DOWN" in northbound.getPort(sw.dpId, it).state }
        antiflap.portDown(sw.dpId, port)

        then: "Port is really DOWN"
        Wrappers.wait(WAIT_OFFSET) { assert "PORT_DOWN" in northbound.getPort(sw.dpId, port).config }

        when: "Bring port up on the switch"
        def portUp = antiflap.portUp(sw.dpId, port)

        then: "Port is really UP"
        Wrappers.wait(WAIT_OFFSET) { assert !("PORT_DOWN" in northbound.getPort(sw.dpId, port).config) }

        cleanup:
        !portUp && antiflap.portUp(sw.dpId, port)

        where:
        // It is impossible to understand whether ISL-free port is UP/DOWN on OF_12 switches.
        // Such switches always have 'config: []'.
        //also, ban WB5164 due to #2636
        sw << getTopology().getActiveSwitches().findAll { it.ofVersion != "OF_12" && !it.wb5164 }
                .unique { it.nbFormat().with { [it.hardware, it.software] } }

    }

    List<Isl> getUniqueIsls() {
        def uniqueSwitches = getTopology().getActiveSwitches()
                .unique { it.nbFormat().with { [it.hardware, it.software] } }*.dpId
        def isls = topology.islsForActiveSwitches.collect { [it, it.reversed] }.flatten()
        return isls.unique { it.srcSwitch.dpId }.findAll { it.srcSwitch.dpId in uniqueSwitches }
    }
}
