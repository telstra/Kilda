package org.openkilda.functionaltests.spec.stats

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RO
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.FlowHelperV2
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import spock.lang.Narrative
import spock.lang.See
import spock.lang.Shared

import javax.inject.Provider

@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/fl-statistics")
@Narrative("""Now we have two FL instances: Management and Statistics.
- FL Stats: collect statistics only from the switches.
- FL Management: do the other work and can collect statistics as well when a switch doesn't connect to FL Stats.""")
class MflStatSpec extends HealthCheckSpecification {

    @Shared
    @Value('${opentsdb.metric.prefix}')
    String metricPrefix

    @Shared
    // statsrouter.request.interval = 60
    def statsRouterInterval = 60

    @Autowired
    Provider<TraffExamService> traffExamProvider

    @Tags([LOW_PRIORITY])
    def "System is able to collect stats from the statistic and management controllers"() {
        given: "A flow"
        assumeTrue(topology.activeTraffGens.size() > 1, "Require at least 2 switches with connected traffgen")
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeTraffGens*.switchConnected
        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        flow.maximumBandwidth = 100
        flowHelper.addFlow(flow)

        when: "Generate traffic on the given flow"
        Date startTime = new Date()
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildExam(flow, (int) flow.maximumBandwidth, 5)
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat in openTSDB is created"
        def metric = metricPrefix + "flow.raw.bytes"
        def tags = [switchid: srcSwitch.dpId.toOtsdFormat(), flowid: flow.id]
        def waitInterval = 10
        def initStat
        Wrappers.wait(statsRouterInterval + WAIT_OFFSET, waitInterval) {
            initStat = otsdb.query(startTime, metric, tags).dps
            assert initStat.size() >= 1
        }

        when: "Leave src switch only with management controller and disconnect from stats"
        def statsBlockData = lockKeeper.knockoutSwitch(srcSwitch, RO)

        and: "Generate traffic on the given flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should be collected because management controller is set"
        def statFromMgmtController
        //first 60 seconds - trying to retrieve stats from management controller, next 60 seconds from stat controller
        Wrappers.wait(statsRouterInterval * 2 + WAIT_OFFSET, waitInterval) {
            statFromMgmtController = otsdb.query(startTime, metric, tags).dps
            assert statFromMgmtController.size() > initStat.size()
            assert statFromMgmtController.entrySet()[-2].value < statFromMgmtController.entrySet()[-1].value
        }

        when: "Leave src switch only with stats controller and disconnect from management"
        lockKeeper.reviveSwitch(srcSwitch, statsBlockData)
        def mgmtBlockData = lockKeeper.knockoutSwitch(srcSwitch, RW)

        and: "Generate traffic on the given flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should be collected because statistic controller is set"
        def statFromStatsController
        Wrappers.wait(statsRouterInterval + WAIT_OFFSET, waitInterval) {
            statFromStatsController = otsdb.query(startTime, metric, tags).dps
            assert statFromStatsController.size() > statFromMgmtController.size()
            assert statFromStatsController.entrySet()[-2].value < statFromStatsController.entrySet()[-1].value
        }

        when: "Disconnect the src switch from both management and statistic controllers"
        statsBlockData = lockKeeper.knockoutSwitch(srcSwitch, RO)
        Wrappers.wait(WAIT_OFFSET) { assert !(srcSwitch.dpId in northbound.getActiveSwitches()*.switchId) }

        and: "Generate traffic on the given flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should not be collected because it is disconnected from controllers"
        def statAfterDeletingControllers
        Wrappers.timedLoop(statsRouterInterval) {
            statAfterDeletingControllers = otsdb.query(startTime, metric, tags).dps
            assert statAfterDeletingControllers.size() == statFromStatsController.size()
            sleep((waitInterval * 1000).toLong())
        }

        when: "Restore default controllers on the src switches"
        lockKeeper.reviveSwitch(srcSwitch, statsBlockData)
        lockKeeper.reviveSwitch(srcSwitch, mgmtBlockData)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert srcSwitch.dpId in northbound.getActiveSwitches()*.switchId
            assert northbound.getAllLinks().findAll { it.state == IslChangeType.FAILED }.empty
        }

        then: "Old statistic should be collected"
        Wrappers.wait(statsRouterInterval + WAIT_OFFSET, waitInterval) {
            def oldStats = otsdb.query(startTime, metric, tags).dps
            oldStats.size() > statAfterDeletingControllers.size()
            assert oldStats.entrySet()[-2].value < oldStats.entrySet()[-1].value
        }

        and: "Cleanup: Delete the flow"
        Wrappers.wait(WAIT_OFFSET + rerouteDelay) {
            assert northbound.getFlowStatus(flow.id).status == FlowState.UP
        } // make sure that flow is UP after switchUP event
        Wrappers.retry(3, 2){
            /*we expect that the flow is UP at this point,
            but sometimes for no good reason the flow is IN_PROGRESS
            then as a result system can't delete the flow*/
            flowHelper.deleteFlow(flow.id)
        }
    }

    def "System is able to collect stats from the statistic and management controllers (v2)"() {
        given: "A flow"
        assumeTrue(topology.activeTraffGens.size() > 1, "Require at least 2 switches with connected traffgen")
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeTraffGens*.switchConnected
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flow.maximumBandwidth = 100
        flowHelperV2.addFlow(flow)

        when: "Generate traffic on the given flow"
        Date startTime = new Date()
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam)
                .buildExam(flowHelperV2.toV1(flow), (int) flow.maximumBandwidth, 5)
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat in openTSDB is created"
        def metric = metricPrefix + "flow.raw.bytes"
        def tags = [switchid: srcSwitch.dpId.toOtsdFormat(), flowid: flow.flowId]
        def waitInterval = 10
        def initStat
        Wrappers.wait(statsRouterInterval + WAIT_OFFSET, waitInterval) {
            initStat = otsdb.query(startTime, metric, tags).dps
            assert initStat.size() >= 1
        }

        when: "Src switch is only left with management controller (no stats controller)"
        def statsBlockData = lockKeeper.knockoutSwitch(srcSwitch, RO)

        and: "Generate traffic on the given flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should be collected because management controller is still set"
        def statFromMgmtController
        //first 60 seconds - trying to retrieve stats from management controller, next 60 seconds from stat controller
        Wrappers.wait(statsRouterInterval * 2 + WAIT_OFFSET, waitInterval) {
            statFromMgmtController = otsdb.query(startTime, metric, tags).dps
            assert statFromMgmtController.size() > initStat.size()
            assert statFromMgmtController.entrySet()[-2].value < statFromMgmtController.entrySet()[-1].value
        }

        when: "Set only statistic controller on the src switch and disconnect from management"
        lockKeeper.reviveSwitch(srcSwitch, statsBlockData)
        def mgmtBlockData = lockKeeper.knockoutSwitch(srcSwitch, RW)

        and: "Generate traffic on the given flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should be collected because statistic controller is set"
        def statFromStatsController
        Wrappers.wait(statsRouterInterval + WAIT_OFFSET, waitInterval) {
            statFromStatsController = otsdb.query(startTime, metric, tags).dps
            assert statFromStatsController.size() > statFromMgmtController.size()
            assert statFromStatsController.entrySet()[-2].value < statFromStatsController.entrySet()[-1].value
        }

        when: "Disconnect the src switch from both management and statistic controllers"
        statsBlockData = lockKeeper.knockoutSwitch(srcSwitch, RO)
        Wrappers.wait(WAIT_OFFSET) { assert !(srcSwitch.dpId in northbound.getActiveSwitches()*.switchId) }

        and: "Generate traffic on the given flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should not be collected because it is disconnected from controllers"
        def statAfterDeletingControllers
        Wrappers.timedLoop(statsRouterInterval) {
            sleep((waitInterval * 1000).toLong())
            statAfterDeletingControllers = otsdb.query(startTime, metric, tags).dps
            assert statAfterDeletingControllers.size() == statFromStatsController.size()
        }

        when: "Restore default controllers on the src switch"
        lockKeeper.reviveSwitch(srcSwitch, statsBlockData)
        lockKeeper.reviveSwitch(srcSwitch, mgmtBlockData)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert srcSwitch.dpId in northbound.getActiveSwitches()*.switchId
            assert northbound.getAllLinks().findAll { it.state == IslChangeType.FAILED }.empty
            assert northbound.getFlowStatus(flow.flowId).status != FlowState.DOWN
        }

        then: "Old statistic should be collected"
        Wrappers.wait(statsRouterInterval + WAIT_OFFSET, waitInterval) {
            def oldStats = otsdb.query(startTime, metric, tags).dps
            oldStats.size() > statAfterDeletingControllers.size()
            assert oldStats.entrySet()[-2].value < oldStats.entrySet()[-1].value
        }

        and: "Cleanup: Delete the flow"
        Wrappers.wait(WAIT_OFFSET + rerouteDelay) {
            assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP
        } // make sure that flow is UP after switchUP event
        Wrappers.retry(3, 2){
            /*we expect that the flow is UP at this point,
            but sometimes for no good reason the flow is IN_PROGRESS
            then as a result system can't delete the flow*/
            flowHelperV2.deleteFlow(flow.flowId)
        }
    }

    @Tags([TOPOLOGY_DEPENDENT])
    @Tidy
    def "System is able to collect stats if at least 1 stats or management controller is available"() {
        given: "A flow, src switch is connected to 2 RW and 2 RO floodlights"
        assumeTrue(topology.activeTraffGens.size() > 1, "Require at least 2 switches with connected traffgen")
        def srcSwitch = topology.activeTraffGens*.switchConnected.find { flHelper.filterRegionsByMode(it.regions, RW).size() == 2 &&
            flHelper.filterRegionsByMode(it.regions, RO).size() == 2 }
        assumeTrue(srcSwitch != null, "This test requires a tg switch in 2 RW regions and 2 RO regions")
        def dstSwitch = topology.activeTraffGens*.switchConnected.find { it.dpId != srcSwitch.dpId }
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flow.maximumBandwidth = 100
        flowHelperV2.addFlow(flow)

        when: "Src switch is only left with 1 management controller (no stats controllers)"
        def regionToStay = srcSwitch.regions.find { flHelper.getFlByRegion(it).mode == RW }
        def blockData = lockKeeper.knockoutSwitch(srcSwitch, srcSwitch.regions - regionToStay)
        switchHelper.waitForSwitchFlConnection(srcSwitch, false, flHelper.getFlsByRegions(srcSwitch.regions - regionToStay))

        and: "Generate traffic on the given flow"
        Date startTime = new Date()
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam)
                .buildExam(flowHelperV2.toV1(flow), (int) flow.maximumBandwidth, 5)
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should be collected (first RW switch available)"
        def metric = metricPrefix + "flow.raw.bytes"
        def tags = [switchid: srcSwitch.dpId.toOtsdFormat(), flowid: flow.flowId]
        def waitInterval = 10
        def initStats
        //first 60 seconds - trying to retrieve stats from management controller, next 60 seconds from stat controller
        Wrappers.wait(statsRouterInterval * 2 + WAIT_OFFSET, waitInterval) {
            initStats = otsdb.query(startTime, metric, tags).dps
            assert initStats.size() >= 1
        }

        when: "Src switch is only left with the other management controller (no stats controllers)"
        lockKeeper.reviveSwitch(srcSwitch, blockData)
        regionToStay = srcSwitch.regions.find { flHelper.getFlByRegion(it).mode == RW && it != regionToStay }
        blockData = lockKeeper.knockoutSwitch(srcSwitch, srcSwitch.regions - regionToStay)
        switchHelper.waitForSwitchFlConnection(srcSwitch, false, flHelper.getFlsByRegions(srcSwitch.regions - regionToStay))

        and: "Generate traffic on the given flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should be collected (second RW switch available)"
        def newStats
        //first 60 seconds - trying to retrieve stats from management controller, next 60 seconds from stat controller
        Wrappers.wait(statsRouterInterval * 2 + WAIT_OFFSET, waitInterval) {
            newStats = otsdb.query(startTime, metric, tags).dps
            assert newStats.size() > initStats.size()
            assert newStats.entrySet()[-2].value < newStats.entrySet()[-1].value
        }

        when: "Set only 1 statistic controller on the src switch and disconnect from management"
        initStats = newStats
        lockKeeper.reviveSwitch(srcSwitch, blockData)
        regionToStay = srcSwitch.regions.find { flHelper.getFlByRegion(it).mode == RO }
        blockData = lockKeeper.knockoutSwitch(srcSwitch, srcSwitch.regions - regionToStay)
        switchHelper.waitForSwitchFlConnection(srcSwitch, false, flHelper.getFlsByRegions(srcSwitch.regions - regionToStay))

        and: "Generate traffic on the given flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should be collected (first RO switch available)"
        Wrappers.wait(statsRouterInterval + WAIT_OFFSET, waitInterval) {
            newStats = otsdb.query(startTime, metric, tags).dps
            assert newStats.size() > initStats.size()
            assert newStats.entrySet()[-2].value < newStats.entrySet()[-1].value
        }

        when: "Set only other statistic controller on the src switch and disconnect from management"
        initStats = newStats
        lockKeeper.reviveSwitch(srcSwitch, blockData)
        regionToStay = srcSwitch.regions.find { flHelper.getFlByRegion(it).mode == RO && it != regionToStay }
        blockData = lockKeeper.knockoutSwitch(srcSwitch, srcSwitch.regions - regionToStay)
        switchHelper.waitForSwitchFlConnection(srcSwitch, false, flHelper.getFlsByRegions(srcSwitch.regions - regionToStay))

        and: "Generate traffic on the given flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stat on the src switch should be collected (second RO switch available)"
        Wrappers.wait(statsRouterInterval + WAIT_OFFSET, waitInterval) {
            newStats = otsdb.query(startTime, metric, tags).dps
            assert newStats.size() > initStats.size()
            assert newStats.entrySet()[-2].value < newStats.entrySet()[-1].value
        }

        cleanup:
        blockData && switchHelper.reviveSwitch(srcSwitch, blockData, true)
        // make sure that flow is UP after switchUP event
        Wrappers.wait(WAIT_OFFSET + rerouteDelay) {
            assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP
        }
        Wrappers.retry(3, 2){
            /*we expect that the flow is UP at this point,
            but sometimes for no good reason the flow is IN_PROGRESS
            then as a result system can't delete the flow*/
            flowHelperV2.deleteFlow(flow.flowId)
        }
    }
}
