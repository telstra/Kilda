package org.openkilda.functionaltests.spec.switches

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.helpers.model.PortHistoryEvent.ANTI_FLAP_ACTIVATED
import static org.openkilda.functionaltests.helpers.model.PortHistoryEvent.ANTI_FLAP_DEACTIVATED
import static org.openkilda.functionaltests.helpers.model.PortHistoryEvent.ANTI_FLAP_PERIODIC_STATS
import static org.openkilda.functionaltests.helpers.model.PortHistoryEvent.PORT_DOWN
import static org.openkilda.functionaltests.helpers.model.PortHistoryEvent.PORT_UP
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.functionaltests.helpers.model.PortHistoryEvent
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.model.system.FeatureTogglesDto
import org.openkilda.model.SwitchId
import org.openkilda.northbound.dto.v2.switches.PortHistoryResponse
import org.openkilda.testing.service.northbound.NorthboundServiceV2
import org.openkilda.testing.tools.SoftAssertions

import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Ignore
import spock.lang.Narrative
import spock.lang.See
import spock.lang.Shared
import spock.lang.Unroll

@See(["https://github.com/telstra/open-kilda/blob/develop/docs/design/network-discovery/port-FSM.png",
        "https://github.com/telstra/open-kilda/blob/develop/docs/design/network-discovery/AF-FSM.png"])
@Narrative("Verify that port history is created for the port up/down actions.")
class PortHistorySpec extends HealthCheckSpecification {
    @Autowired
    NorthboundServiceV2 northboundV2

    @Shared
    //confd/templates/wfm/topology.properties.tmpl => port.antiflap.stats.dumping.interval.seconds = 60
    //it means how often the 'ANTI_FLAP_PERIODIC_STATS' is logged in port history
    def antiflapDumpingInterval = 60

    @Tidy
    @Unroll
    @IterationTag(tags = [SMOKE, SMOKE_SWITCHES], iterationNameRegex = /direct/)
    def "Port history are created for the port down/up actions when link is #islDescription"() {
        given: "A link"
        assumeTrue(isl as boolean, "Unable to find $islDescription ISL for this test")
        def timestampBefore = System.currentTimeMillis()

        when: "Execute port DOWN on the src switch"
        def portDown = antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
        }

        then: "Port history is created on the src switch"
        Wrappers.wait(WAIT_OFFSET) {
            Long timestampAfterDown = System.currentTimeMillis()
            with(northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampBefore, timestampAfterDown)) {
                it.size() == 2
                checkPortHistory(it.find { it.event == ANTI_FLAP_ACTIVATED.toString() },
                        isl.srcSwitch.dpId, isl.srcPort, ANTI_FLAP_ACTIVATED)
                checkPortHistory(it.find { it.event == PORT_DOWN.toString() }, isl.srcSwitch.dpId,
                        isl.srcPort, PORT_DOWN)
            }
        }

        when: "Execute port UP on the src switch"
        def portUp = antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }

        then: "Port history is updated on the src switch"
        Wrappers.wait(WAIT_OFFSET) {
            with(northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampBefore, System.currentTimeMillis())) {
                it.size() == 4
                checkPortHistory(it.find { it.event == PORT_UP.toString() },
                        isl.srcSwitch.dpId, isl.srcPort, PORT_UP)
                def deactivateEvent = it.find { it.event == ANTI_FLAP_DEACTIVATED.toString() }
                checkPortHistory(deactivateEvent, isl.srcSwitch.dpId, isl.srcPort,
                        ANTI_FLAP_DEACTIVATED)
                // no flapping occurs during cooldown, so antiflap stat doesn't exist in the ANTI_FLAP_DEACTIVATED event
                !deactivateEvent.downCount
                !deactivateEvent.upCount
            }
        }

        and: "Port history on the dst switch is not empty when link is direct"
        Wrappers.wait(WAIT_OFFSET / 2) {
            with(northboundV2.getPortHistory(isl.dstSwitch.dpId, isl.dstPort, timestampBefore, System.currentTimeMillis())) {
                it.size() == historySizeOnDstSw
                if (historySizeOnDstSw as boolean) {
                    checkPortHistory(it.find { it.event == PORT_UP.toString() },
                            isl.dstSwitch.dpId, isl.dstPort, PORT_UP)
                    def deactivateEvent = it.find { it.event == ANTI_FLAP_DEACTIVATED.toString() }
                    checkPortHistory(deactivateEvent, isl.dstSwitch.dpId, isl.dstPort,
                            ANTI_FLAP_DEACTIVATED)
                    // no flapping occurs during cooldown, so antiflap stat doesn't exist in the ANTI_FLAP_DEACTIVATED event
                    !deactivateEvent.downCount
                    !deactivateEvent.upCount
                }
            }
        }

        and: "Port history on the src switch is also available using default timeline"
        northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort).size() >= 4

        cleanup:
        if (portDown && !portUp) {
            antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
            Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
                assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
            }
        }
        database.resetCosts()

        where:
        [islDescription, historySizeOnDstSw, isl] << [
                ["direct", 4, getTopology().islsForActiveSwitches.find { !it.aswitch }],
                ["a-switch", 0, getTopology().islsForActiveSwitches.find {
                    it.aswitch?.inPort && it.aswitch?.outPort
                }]
        ]
    }

    @Tidy
    def "Port history should not be returned in case timeline is incorrect (timeBefore > timeAfter)"() {
        given: "A direct link with port history"
        def timestampBefore = System.currentTimeMillis()
        def isl = getTopology().islsForActiveSwitches.find { !it.aswitch }

        def portDown = antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
        }

        def portUp = antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }

        def timestampAfter = System.currentTimeMillis()
        def portHistory = northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampBefore, timestampAfter)
        assert portHistory.size() == 4 // PORT_DOWN, ANTI_FLAP_ACTIVATED, PORT_UP, ANTI_FLAP_DEACTIVATED

        when: "Get port history on the src switch for incorrect timeline"
        def portH = northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampAfter, timestampBefore)

        then: "Port history is NOT returned"
        portH.isEmpty()

        cleanup:
        if (portDown && !portUp) {
            antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
            Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
                assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
            }
        }
        database.resetCosts()
    }

    @Tidy
    def "Port history should not be returned in case port/switch have never existed"() {
        when: "Try to get port history for incorrect port and switch"
        def portHistory = northboundV2.getPortHistory(NON_EXISTENT_SWITCH_ID, 99999)

        then: "Port history is empty"
        portHistory.isEmpty()
    }

    def "Port history is available when switch is DEACTIVATED"() {
        given: "A direct link"
        def timestampBefore = System.currentTimeMillis()
        def isl = getTopology().islsForActiveSwitches.find { !it.aswitch }

        when: "Execute port DOWN/UP on the src switch"
        def portDown = antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
        }

        def portUp = antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }
        def timestampAfter = System.currentTimeMillis()
        northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampBefore, timestampAfter).size() == 4

        and: "Deactivate the src switch"
        def switchToDisconnect = isl.srcSwitch
        def blockData = switchHelper.knockoutSwitch(switchToDisconnect, RW)

        then: "Port history on the src switch is still available"
        northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampBefore, timestampAfter).size() == 4

        cleanup: "Revive the src switch"
        if (portDown && !portUp) {
            antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
            Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
                assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
            }
        }
        database.resetCosts()
        switchToDisconnect && switchHelper.reviveSwitch(switchToDisconnect, blockData)
    }

    def "Port history is able to show ANTI_FLAP statistic"() {
        given: "floodlightRoutePeriodicSync is disabled"
        northbound.toggleFeature(FeatureTogglesDto.builder()
                                                  .floodlightRoutePeriodicSync(false)
                                                  .build())

        and: "A port in a stable state"
        def isl = getTopology().islsForActiveSwitches.first()
        Wrappers.wait(antiflapCooldown + WAIT_OFFSET) {
            antiflap.assertPortIsStable(isl.srcSwitch.dpId, isl.srcPort)
        }

        when: "Execute port DOWN on the port"
        def timestampBefore = System.currentTimeMillis()
        northbound.portDown(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
        }

        then: "Port history is created for that port"
        Wrappers.wait(WAIT_OFFSET) {
            Long timestampAfterDown = System.currentTimeMillis()
            with(northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampBefore, timestampAfterDown)) {
                it.size() == 2 // PORT_DOWN, ANTI_FLAP_ACTIVATED
            }
        }

        when: "Blink port to generate antiflap statistic"
        Wrappers.timedLoop(antiflapDumpingInterval - antiflapCooldown + 1) {
            northbound.portUp(isl.srcSwitch.dpId, isl.srcPort)
            northbound.portDown(isl.srcSwitch.dpId, isl.srcPort)
        }

        then: "Antiflap statistic is available in port history"
        Wrappers.wait(WAIT_OFFSET + antiflapCooldown) {
            new SoftAssertions().with {
                def history = northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort,
                        timestampBefore, System.currentTimeMillis())
                checkSucceeds { assert history*.event
                        .containsAll([PORT_DOWN, ANTI_FLAP_ACTIVATED, ANTI_FLAP_PERIODIC_STATS]*.toString()) }
                def antiflapStat = history.find { it.event == ANTI_FLAP_PERIODIC_STATS.toString() }
                checkSucceeds {
                    checkPortHistory(antiflapStat, isl.srcSwitch.dpId, isl.srcPort,
                            ANTI_FLAP_PERIODIC_STATS)
                }
                //unstable place below. Doing weak check that at least something is counted =(
                checkSucceeds { assert antiflapStat.upCount > 0 }
                checkSucceeds { assert antiflapStat.downCount > 0 }
                verify()
            }
        }

        and: "Cleanup: revert system to original state"
        northbound.portUp(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET + discoveryInterval + antiflapCooldown) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }
        northbound.toggleFeature(FeatureTogglesDto.builder()
                .floodlightRoutePeriodicSync(true)
                .build())
        Wrappers.wait(antiflapCooldown + WAIT_OFFSET) {
            antiflap.assertPortIsStable(isl.srcSwitch.dpId, isl.srcPort)
        }
        database.resetCosts()
    }

    @Ignore("https://github.com/telstra/open-kilda/issues/3007")
    def "System shows antiflap statistic in the ANTI_FLAP_DEACTIVATED event when antiflap is deactivated\
 before collecting ANTI_FLAP_PERIODIC_STATS"() {
        assumeTrue(antiflapCooldown + 3 < antiflapDumpingInterval,
"It can't be run when antiflap.cooldown + flap_duration > port.antiflap.stats.dumping.interval.seconds")
        //port up/down procedure is done once in this test, so it can't take more than 3 seconds

        given: "A direct link"
        def isl = getTopology().islsForActiveSwitches.find { !it.aswitch }
        assumeTrue(isl as boolean, "Unable to find ISL for this test")
        def timestampBefore = System.currentTimeMillis()

        when: "Execute port DOWN on the src switch for activating antiflap"
        northbound.portDown(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
            Long timestampAfterDown = System.currentTimeMillis()
            with(northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampBefore, timestampAfterDown)) {
                it.size() == 2 // PORT_DOWN, ANTI_FLAP_ACTIVATED
            }
        }

        and: "Generate antiflap statistic"
        northbound.portUp(isl.srcSwitch.dpId, isl.srcPort)
        northbound.portDown(isl.srcSwitch.dpId, isl.srcPort)

        then: "Antiflap statistic is available in port history inside the ANTI_FLAP_DEACTIVATED event"
        Wrappers.wait(antiflapCooldown + WAIT_OFFSET) {
            Long timestampAfterStat = System.currentTimeMillis()
            with(northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampBefore, timestampAfterStat)) {
                it.findAll { it.event == ANTI_FLAP_PERIODIC_STATS.toString() }.empty
                it.size() == 3 // PORT_DOWN, ANTI_FLAP_ACTIVATED, ANTI_FLAP_DEACTIVATED
                def antiflapStat = it[-1]
                checkPortHistory(antiflapStat, isl.srcSwitch.dpId, isl.srcPort,
                        ANTI_FLAP_DEACTIVATED)
                antiflapStat.downCount == 1
                antiflapStat.upCount == 1
            }
        }

        and: "Cleanup: revert system to original state"
        northbound.portUp(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(WAIT_OFFSET + discoveryInterval + antiflapCooldown) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }
        database.resetCosts()
    }

    void checkPortHistory(PortHistoryResponse portHistory, SwitchId switchId, Integer port, PortHistoryEvent event) {
        verifyAll(portHistory) {
            id != null
            switchId == switchId
            portNumber == port
            it.event == event.toString()
            date != null
        }
    }
}
