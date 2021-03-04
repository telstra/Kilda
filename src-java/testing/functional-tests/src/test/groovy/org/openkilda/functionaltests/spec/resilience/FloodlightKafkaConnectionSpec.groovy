package org.openkilda.functionaltests.spec.resilience

import static groovyx.gpars.dataflow.Dataflow.task
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.helpers.Wrappers.timedLoop
import static org.openkilda.functionaltests.helpers.Wrappers.wait
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.SwitchFeature

import org.springframework.beans.factory.annotation.Value

import java.util.concurrent.TimeUnit

class FloodlightKafkaConnectionSpec extends HealthCheckSpecification {
    static final int PERIODIC_SYNC_TIME = 60

    @Value('${floodlight.alive.timeout}')
    int floodlightAliveTimeout
    @Value('${antiflap.cooldown}')
    int antiflapCooldown

    @Tidy
    def "System properly handles ISL statuses during connection problems between Floodlights and Kafka"() {
        setup: "All switches that have multiple management floodlights now remain with only 1"
        def updatedRegions = topology.switches.collectEntries{ [(it.dpId): it.regions] }
        def knockoutData = []
        topology.switches.eachWithIndex { sw, i ->
            def rwRegions = flHelper.filterRegionsByMode(sw.regions, RW)
            def otherRegions = sw.regions - rwRegions
            def regionToStay = rwRegions[i % rwRegions.size()]
            def regionsToDc = rwRegions - regionToStay
            knockoutData << [(sw): lockKeeper.knockoutSwitch(sw, regionsToDc)]
            updatedRegions[sw.dpId] = [regionToStay] + otherRegions
        }
        assumeTrue(updatedRegions.values().flatten().unique().size() > 1,
"Can be run only if there are switches in 2+ regions")

        and: "Pick a region to break, find which isls are between regions"
        def regionToBreak = flHelper.fls.findAll{ it.mode == RW }*.region.first()
        def islsBetweenRegions = topology.islsForActiveSwitches.findAll {
            [it.srcSwitch, it.dstSwitch].any { updatedRegions[it.dpId].contains(regionToBreak) } &&
                    updatedRegions[it.srcSwitch.dpId] != updatedRegions[it.dstSwitch.dpId]
        }

        when: "Region 1 controller loses connection to Kafka"
        lockKeeper.knockoutFloodlight(regionToBreak)
        def flOut = true

        then: "Non-rtl links between failed region and alive regions fail due to discovery timeout"
        def nonRtlTransitIsls = islsBetweenRegions.findAll { isl ->
            [isl.srcSwitch, isl.dstSwitch].any { !it.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD) }
        }
        def nonRtlShouldFail = task {
            wait(WAIT_OFFSET + discoveryTimeout) {
                nonRtlTransitIsls.forEach { assert northbound.getLink(it).state == IslChangeType.FAILED }
            }
        }

        and: "Right before controller alive timeout: switches are still active"
        and: "links inside regions are discovered"
        and: "rtl links between regions are discovered"
        double interval = floodlightAliveTimeout * 0.4
        def linksToRemainAlive = topology.islsForActiveSwitches.findAll { !nonRtlTransitIsls.contains(it) }
        timedLoop(floodlightAliveTimeout - interval) {
            assert northbound.activeSwitches.size() == topology.activeSwitches.size()
            def isls = northbound.getAllLinks()
            linksToRemainAlive.each { assert islUtils.getIslInfo(isls, it).get().state == IslChangeType.DISCOVERED }
            sleep(500)
        }

        and: "After controller alive timeout switches in broken region become inactive but links are still discovered"
        wait(interval + WAIT_OFFSET) {
            assert northbound.activeSwitches.size() == topology.activeSwitches.findAll {
                !updatedRegions[it.dpId].contains(regionToBreak) }.size()
        }
        linksToRemainAlive.each { assert northbound.getLink(it).state == IslChangeType.DISCOVERED }

        when: "System remains in this state for discovery timeout for ISLs"
        TimeUnit.SECONDS.sleep(discoveryTimeout + 1)
        nonRtlShouldFail.get()

        then: "All links except for non-rtl transit ones are still discovered"
        linksToRemainAlive.each { assert northbound.getLink(it).state == IslChangeType.DISCOVERED }

        when: "Controller restores connection to Kafka"
        lockKeeper.reviveFloodlight(regionToBreak)
        flOut = false

        then: "All links are discovered and switches become active"
        wait(PERIODIC_SYNC_TIME) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
            assert northbound.activeSwitches.size() == topology.activeSwitches.size()
        }

        and: "System is able to successfully create a valid flow between regions"
        def swPair = topologyHelper.switchPairs.find { pair ->
            [pair.src, pair.dst].any { updatedRegions[it.dpId].contains(regionToBreak) }  &&
                    updatedRegions[pair.src.dpId] != updatedRegions[pair.dst.dpId]
        }
        def flow = flowHelperV2.randomFlow(swPair)
        northboundV2.addFlow(flow)
        wait(WAIT_OFFSET * 2) { //FL may be a bit laggy right after comming up, so this may take a bit longer than usual
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }

        cleanup:
        nonRtlShouldFail?.join()
        flow && flowHelperV2.deleteFlow(flow.flowId)
        knockoutData.each { it.each { sw, data -> lockKeeper.reviveSwitch(sw, data) } }
        if(flOut) {
            lockKeeper.reviveFloodlight(regionToBreak)
            wait(PERIODIC_SYNC_TIME) {
                assert northbound.activeSwitches.size() == topology.activeSwitches.size()
                assert northbound.getAllLinks().size() == topology.islsForActiveSwitches.size() * 2
            }
        }
    }

    @Tidy
    def "System can detect switch changes if they happen while Floodlight was disconnected after it reconnects"() {
        when: "Controllers lose connection to kafka"
        def regions = flHelper.fls*.region
        regions.each { lockKeeper.knockoutFloodlight(it) }
        def regionsOut = true
        wait(floodlightAliveTimeout + WAIT_OFFSET) { assert northbound.activeSwitches.size() == 0 }

        and: "Switch port for certain ISL goes down"
        def isl = topology.islsForActiveSwitches.find { it.aswitch?.inPort && it.aswitch?.outPort }
        //port down on A-switch will lead to a port down on a connected Kilda switch
        lockKeeper.portsDown([isl.aswitch.inPort])

        and: "Controller restores connection to kafka"
        regions.each { lockKeeper.reviveFloodlight(it) }
        regionsOut = false

        then: "System detects that certain port has been brought down and fails the related link"
        wait(WAIT_OFFSET) {
            def isls = northbound.getAllLinks()
            assert islUtils.getIslInfo(isls, isl).get().state == IslChangeType.FAILED
            assert islUtils.getIslInfo(isls, isl.reversed).get().state == IslChangeType.FAILED
        }

        cleanup:
        regionsOut && regions.each { lockKeeper.reviveFloodlight(it) }
        lockKeeper.portsUp([isl.aswitch.inPort])
        wait(WAIT_OFFSET) { assert northbound.activeSwitches.size() == topology.activeSwitches.size() }
        wait(WAIT_OFFSET + discoveryInterval + antiflapCooldown) {
            def isls = northbound.getAllLinks()
            assert islUtils.getIslInfo(isls, isl).get().state == IslChangeType.DISCOVERED
            assert islUtils.getIslInfo(isls, isl.reversed).get().state == IslChangeType.DISCOVERED
        }
        database.resetCosts()
    }
}
