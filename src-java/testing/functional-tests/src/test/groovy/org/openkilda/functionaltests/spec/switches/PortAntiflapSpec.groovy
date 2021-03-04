package org.openkilda.functionaltests.spec.switches

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.helpers.model.PortHistoryEvent.ANTI_FLAP_DEACTIVATED
import static org.openkilda.messaging.info.event.IslChangeType.DISCOVERED
import static org.openkilda.messaging.info.event.IslChangeType.FAILED
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.functionaltests.helpers.thread.PortBlinker
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PortChangeType
import org.openkilda.messaging.model.system.FeatureTogglesDto
import org.openkilda.model.SwitchFeature
import org.openkilda.testing.model.topology.TopologyDefinition.Isl

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import spock.lang.Ignore
import spock.lang.Issue
import spock.lang.Narrative

import java.util.concurrent.TimeUnit

@Slf4j
@Narrative("""
Anti-flap feature is aimed to address the problem of blinking ports. Sometimes it happens that certain port on a switch
begins to blink for a very short period of time. If it is a short-time behavior, then we don't want to init a
port down event and reroute the flow. Otherwise we wait for 'antiflapWarmup' time and assign that blinking port a DOWN 
status. Then, when it comes back up it should remain in a stable 'up' state for at least 'antiflapCooldown' seconds to
actually become UP for the rest of the system.
Initially, port is considered 'flapping' if it changes status quicker than once in 'antiflap.min' seconds (port will
change status from UP to DOWN only after 'antiflap.min' in case of a single-time change of status)
""")
@Issue("https://github.com/telstra/open-kilda/issues/1729")
class PortAntiflapSpec extends HealthCheckSpecification {

    @Value('${antiflap.min}')
    int antiflapMin
    @Value('${antiflap.warmup}')
    int antiflapWarmup
    @Value('${antiflap.cooldown}')
    int antiflapCooldown
    @Value("#{kafkaTopicsConfig.getTopoDiscoTopic()}")
    String topoDiscoTopic
    @Autowired
    @Qualifier("kafkaProducerProperties")
    Properties producerProps

    def setupSpec() {
        northbound.toggleFeature(FeatureTogglesDto.builder().floodlightRoutePeriodicSync(false).build())
    }

    def cleanupSpec() {
        getNorthbound().toggleFeature(FeatureTogglesDto.builder().floodlightRoutePeriodicSync(true).build())
    }

    @Tags(SMOKE)
    def "Flapping port is brought down only after antiflap warmup and stable port is brought up only after cooldown \
timeout"() {
        given: "Switch, port and ISL related to that port"
        Isl isl = topology.islsForActiveSwitches.find { it.aswitch && it.dstSwitch }
        assumeTrue(isl as boolean, "Required a-switch isl is not found")

        when: "ISL port begins to blink"
        def interval = (long) (antiflapMin * 1000 / 2)
        def blinker = new PortBlinker(producerProps, topoDiscoTopic, isl.srcSwitch, isl.srcPort, interval)
        def untilWarmupEnds = { blinker.timeStarted.time + antiflapWarmup * 1000 - new Date().time }
        def untilCooldownEnds = { blinker.timeStopped.time + antiflapCooldown * 1000 - new Date().time }
        blinker.start()

        then: "Right before warmup timeout runs out the related ISL remains up"
        sleep(untilWarmupEnds() - 1000)
        islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED

        and: "After warmup timeout the related ISL goes down"
        Wrappers.wait(untilWarmupEnds() / 1000.0 + WAIT_OFFSET / 2) {
            islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
        }

        and: "ISL remains down even after cooldown timeout"
        TimeUnit.SECONDS.sleep(antiflapCooldown + 1)
        islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED

        when: "Port stops flapping, ending in UP state"
        blinker.stop(true)

        then: "Right before cooldown timeout runs out the ISL remains down"
        sleep(untilCooldownEnds() - 1000)
        islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED

        and: "After cooldown timeout the ISL goes up"
        Wrappers.wait(untilCooldownEnds() / 1000.0 + WAIT_OFFSET / 2 + discoveryInterval) {
            islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }

        cleanup:
        blinker?.isRunning() && blinker.stop(true)
    }

    @Tidy
    def "Port goes down in 'antiflap.min' seconds if no flapping occurs"() {
        given: "Switch, port and ISL related to that port"
        def sw = topology.activeSwitches.first()
        Isl isl = topology.islsForActiveSwitches.find { it.srcSwitch == sw || it.dstSwitch == sw }
        int islPort = isl.with { it.srcSwitch == sw ? it.srcPort : it.dstPort }
        assert islPort

        when: "Port goes down"
        antiflap.portDown(sw.dpId, islPort)

        then: "Related ISL goes down in about 'antiflapMin' seconds"
        Wrappers.wait(antiflapMin + 2) {
            islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
        }

        cleanup: "Bring port up"
        antiflap.portUp(sw.dpId, islPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }
    }

    /**
     * In this test no actual port action is taken. We simulate port flapping by producing messages with OF events
     * directly to kafka, because currently it is the only way to simulate an incredibly rapid port flapping that
     * may sometimes occur on hardware switches(overheat?)
     */
    @Tags(SMOKE)
    def "System properly registers events order when port flaps incredibly fast (end with Up)"() {

        when: "Port blinks rapidly for longer than 'antiflapWarmup' seconds, ending in UP state"
        def isl = topology.islsForActiveSwitches[0]
        def blinker = new PortBlinker(producerProps, topoDiscoTopic, isl.srcSwitch, isl.srcPort, 1)
        blinker.start()
        TimeUnit.SECONDS.sleep(antiflapWarmup + 1)
        blinker.stop(true)

        then: "Related ISL is FAILED"
        Wrappers.wait(WAIT_OFFSET / 2.0) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
        }

        and: "After the port cools down the ISL is discovered again"
        TimeUnit.SECONDS.sleep(antiflapCooldown - 1)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }
    }

    /**
     * In this test no actual port action is taken. We simulate port flapping by producing messages with OF events
     * directly to kafka, because currently it is the only way to simulate an incredibly rapid port flapping that
     * may sometimes occur on hardware switches(overheat?)
     */
    @Ignore("https://github.com/telstra/open-kilda/issues/3847")
    @Tags(SMOKE)
    def "System properly registers events order when port flaps incredibly fast (end with Down)"() {

        when: "Port blinks rapidly for longer than 'antiflapWarmup' seconds, ending in DOWN state"
        def isl = topology.islsForActiveSwitches[0]
        def blinker = new PortBlinker(producerProps, topoDiscoTopic, isl.srcSwitch, isl.srcPort, 1)
        blinker.kafkaChangePort(PortChangeType.DOWN)
        blinker.start()
        TimeUnit.SECONDS.sleep(antiflapWarmup + 1)
        blinker.stop(false)

        then: "Related ISL is FAILED"
        Wrappers.wait(WAIT_OFFSET / 2.0) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
        }

        and: "ISL remains failed even after the port cools down"
        Wrappers.timedLoop(antiflapCooldown + discoveryInterval + WAIT_OFFSET / 2) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.FAILED
            TimeUnit.SECONDS.sleep(1)
        }

        and: "cleanup: restore broken ISL"
        new PortBlinker(producerProps, topoDiscoTopic, isl.srcSwitch, isl.srcPort, 0)
                .kafkaChangePort(PortChangeType.UP)
        Wrappers.wait(WAIT_OFFSET) { islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED }
    }

    @Tidy
    @Tags([SMOKE_SWITCHES, HARDWARE])
    def "A round-trip latency non-direct ISL goes UP according to antiflap"() {
        given: "An active round-trip a-switch link"
        def isl = topology.islsForActiveSwitches.find { it.aswitch?.inPort && it.aswitch?.outPort &&
                [it.srcSwitch, it.dstSwitch].every { it.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD) }
        }
        assumeTrue(isl != null, "Wasn't able to find round-trip ISL with a-switch")

        when: "Port down event happens"
        def timestampBefore = System.currentTimeMillis()
        northbound.portDown(isl.srcSwitch.dpId, isl.srcPort)
        def portIsDown = true
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getLink(isl).state == FAILED
            assert northbound.getLink(isl.reversed).state == FAILED
        }

        and: "Port up event happens"
        northbound.portUp(isl.srcSwitch.dpId, isl.srcPort)
        portIsDown = false

        then: "The ISL is failed till 'antiflap' is deactivated"
        Wrappers.timedLoop(antiflapCooldown * 0.8) {
            with(islUtils.getIslInfo(isl).get()) {
                // it.state == FAILED //https://github.com/telstra/open-kilda/issues/4005
                it.actualState == FAILED
            }
            TimeUnit.SECONDS.sleep(1)
        }
        Wrappers.wait(antiflapCooldown * 0.2 + WAIT_OFFSET) {
            Long timestampAfter = System.currentTimeMillis()
            assert northboundV2.getPortHistory(isl.srcSwitch.dpId, isl.srcPort, timestampBefore, timestampAfter)
                    .findAll { it.event == ANTI_FLAP_DEACTIVATED.toString() }.size() == 1
        }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            def fr = northbound.getLink(isl)
            def rv = northbound.getLink(isl.reversed)
            assert fr.state == DISCOVERED
            assert fr.actualState == DISCOVERED
            assert rv.state == DISCOVERED
            assert rv.actualState == DISCOVERED
        }

        cleanup:
        portIsDown && antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
        Wrappers.wait(antiflapCooldown + discoveryInterval + WAIT_OFFSET) {
            def fr = northbound.getLink(isl)
            def rv = northbound.getLink(isl.reversed)
            assert fr.state == DISCOVERED
            assert fr.actualState == DISCOVERED
            assert rv.state == DISCOVERED
            assert rv.actualState == DISCOVERED
        }
        database.resetCosts()
    }

    def cleanup() {
        database.resetCosts()
    }
}
