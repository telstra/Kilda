package org.openkilda.functionaltests.spec.stats

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.testing.Constants.PROTECTED_PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import spock.lang.Ignore
import spock.lang.Narrative
import spock.lang.Shared

import javax.inject.Provider

@Tags(LOW_PRIORITY)
@Narrative("Verify that statistic is collected for different type of flow")
class FlowStatSpec extends HealthCheckSpecification {
    @Shared
    @Value('${opentsdb.metric.prefix}')
    String metricPrefix

    @Autowired
    Provider<TraffExamService> traffExamProvider

    // statsrouter.request.interval = 60, this test often fails on jenkins with this value
    // the statsrouter.request.interval is increased here to 120
    @Shared
    Integer statsRouterInterval = 120

    def "System is able to collect stats after intentional swapping flow path to protected"() {
        given: "Two active neighboring switches with two diverse paths at least"
        def traffGenSwitches = topology.activeTraffGens*.switchConnected*.dpId
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.src.dpId in traffGenSwitches && it.dst.dpId in traffGenSwitches &&
                    it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue(false, "No suiting switches found")

        and: "Flow with protected path"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelperV2.addFlow(flow)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)

        when: "Generate traffic on the given flow"
        Date startTime = new Date()
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildExam(flowHelperV2.toV1(flow),
                (int) flow.maximumBandwidth, 5)
        def waitInterval = 10
        def mainPathStat
        def tags = [switchid: switchPair.src.dpId.toOtsdFormat(), flowid: flow.flowId]
        def metric = metricPrefix + "flow.raw.bytes"
        //generate two points of stat just to be sure that stat is not collected for protected path
        2.times { count ->
            exam.setResources(traffExam.startExam(exam, true))
            assert traffExam.waitExam(exam).hasTraffic()
            Wrappers.wait(statsRouterInterval, waitInterval) {
                mainPathStat = otsdb.query(startTime, metric, tags).dps
                assert mainPathStat.size() == count + 1
            }
        }

        then: "Stats collects stat for main path cookies"
        def flowInfo = database.getFlow(flow.flowId)
        def mainForwardCookie = flowInfo.forwardPath.cookie.value
        def mainReverseCookie = flowInfo.reversePath.cookie.value
        def mainForwardCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainForwardCookie]).dps
        def mainReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainReverseCookie]).dps
        [mainForwardCookieStat, mainReverseCookieStat].each { stats ->
            stats.values().each { assert it != 0 }
        }

        and: "System collects stats for egress cookie of protected path with zero value"
        def protectedReverseCookie = flowInfo.protectedReversePath.cookie.value
        def protectedReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: protectedReverseCookie]).dps
        protectedReverseCookieStat.size() == 1
        protectedReverseCookieStat.values().first() == 0

        when: "Swap main and protected path"
        northbound.swapFlowPath(flow.flowId)
        Wrappers.wait(PROTECTED_PATH_INSTALLATION_TIME) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            def newFlowPathInfo = northbound.getFlowPath(flow.flowId)
            assert pathHelper.convert(newFlowPathInfo) == currentProtectedPath
            assert pathHelper.convert(newFlowPathInfo.protectedPath) == currentPath
        }

        and: "Generate traffic on the flow"
        def newProtectedReverseCookieStat
        //generate two points of stat to be sure that stat is not collected for a new protected path(after swapping)
        2.times { count ->
            exam.setResources(traffExam.startExam(exam, true))
            assert traffExam.waitExam(exam).hasTraffic()
            Wrappers.wait(statsRouterInterval, waitInterval) {
                assert otsdb.query(startTime, metric, tags).dps.size() > mainPathStat.size()
                newProtectedReverseCookieStat = otsdb.query(startTime, metric,
                        tags + [cookie: protectedReverseCookie]).dps
                assert newProtectedReverseCookieStat.size() == count + 2 // 2 because we have already one point of stat
            }
        }

        then: "System collects stats for previous egress cookie of protected path with non zero value"
        newProtectedReverseCookieStat.values().takeRight(2).each { assert it != 0 }

        and: "System doesn't collect stats anymore for previous ingress/egress cookie of main path"
        otsdb.query(startTime, metric, tags + [cookie: mainReverseCookie]).dps.size() == mainReverseCookieStat.size()
        otsdb.query(startTime, metric, tags + [cookie: mainForwardCookie]).dps.size() == mainForwardCookieStat.size()

        and: "Cleanup: revert system to original state"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Ignore("https://github.com/telstra/open-kilda/issues/2762")
    def "System collects stats when a protected flow was intentionally rerouted"() {
        given: "Two active not neighboring switches with three diverse paths at least"
        def traffGenSwitches = topology.activeTraffGens*.switchConnected*.dpId
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.src.dpId in traffGenSwitches && it.dst.dpId in traffGenSwitches &&
                    it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 3
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A flow with protected path"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelperV2.addFlow(flow)

        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        assert flowPathInfo.protectedPath
        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)

        when: "Generate traffic on the given flow"
        Date startTime = new Date()
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildExam(flowHelperV2.toV1(flow),
                (int) flow.maximumBandwidth, 3)
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stats is not empty for main path cookies"
        def metric = metricPrefix + "flow.raw.bytes"
        def tags = [switchid: switchPair.src.dpId.toOtsdFormat(), flowid: flow.flowId]
        def waitInterval = 10
        def mainPathStat
        Wrappers.wait(statsRouterInterval, waitInterval) {
            mainPathStat = otsdb.query(startTime, metric, tags).dps
            assert mainPathStat.size() >= 1
        }
        def flowInfo = database.getFlow(flow.flowId)
        def mainForwardCookie = flowInfo.forwardPath.cookie.value
        def mainReverseCookie = flowInfo.reversePath.cookie.value
        def mainForwardCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainForwardCookie]).dps
        def mainReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainReverseCookie]).dps
        [mainForwardCookieStat, mainReverseCookieStat].each { stats ->
            stats.values().each { assert it != 0 }
        }

        and: "Stats is empty for protected path egress cookie"
        def protectedReverseCookie = flowInfo.protectedReversePath.cookie.value
        def protectedReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: protectedReverseCookie]).dps
        protectedReverseCookieStat.values().each { assert it == 0 }

        when: "Make the current and protected path less preferable than alternatives"
        def alternativePaths = switchPair.paths.findAll { it != currentPath && it != currentProtectedPath }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentPath) }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentProtectedPath) }

        and: "Init intentional reroute"
        def rerouteResponse = northbound.rerouteFlow(flow.flowId)
        rerouteResponse.rerouted
        Wrappers.wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }

        def flowPathInfoAfterRerouting = northbound.getFlowPath(flow.flowId)
        def newCurrentPath = pathHelper.convert(flowPathInfoAfterRerouting)
        newCurrentPath != currentPath
        newCurrentPath != currentProtectedPath

        and: "Generate traffic on the flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "Stats is not empty for new main path cookies"
        def newFlowInfo = database.getFlow(flow.flowId)
        def newMainForwardCookie = newFlowInfo.forwardPath.cookie.value
        def newMainReverseCookie = newFlowInfo.reversePath.cookie.value
        Wrappers.wait(statsRouterInterval, waitInterval) {
            def newMainForwardCookieStat = otsdb.query(startTime, metric, tags + [cookie: newMainForwardCookie]).dps
            def newMainReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: newMainReverseCookie]).dps
            [newMainForwardCookieStat, newMainReverseCookieStat].each { stats ->
                stats.values().each { assert it != 0 }
            }
        }

        and: "Stats is empty for a new protected path egress cookie"
        def newProtectedReverseCookie = newFlowInfo.protectedReversePath.cookie.value
        def newProtectedReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: newProtectedReverseCookie]).dps
        newProtectedReverseCookieStat.values().each { assert it == 0 }

        and: "Cleanup: revert system to original state"
        flowHelperV2.deleteFlow(flow.flowId)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
    }

    def "System collects stats when a protected flow was automatically rerouted"() {
        given: "Two active not neighboring switches with three not overlapping paths at least"
        def traffGenSwitches = topology.activeTraffGens*.switchConnected*.dpId
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.src.dpId in traffGenSwitches && it.dst.dpId in traffGenSwitches &&
                    it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 3
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        def flowPathInfo = northbound.getFlowPath(flow.id)
        assert flowPathInfo.protectedPath
        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)

        when: "Generate traffic on the given flow"
        Date startTime = new Date()
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildExam(flow, (int) flow.maximumBandwidth, 3)
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "System collects stats for main path cookies"
        def metric = metricPrefix + "flow.raw.bytes"
        def tags = [switchid: switchPair.src.dpId.toOtsdFormat(), flowid: flow.id]
        def waitInterval = 10
        def mainPathStat
        Wrappers.wait(statsRouterInterval, waitInterval) {
            mainPathStat = otsdb.query(startTime, metric, tags).dps
            assert mainPathStat.size() >= 1
        }
        def flowInfo = database.getFlow(flow.id)
        def mainForwardCookie = flowInfo.forwardPath.cookie.value
        def mainReverseCookie = flowInfo.reversePath.cookie.value
        def mainForwardCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainForwardCookie]).dps
        def mainReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainReverseCookie]).dps
        [mainForwardCookieStat, mainReverseCookieStat].each { stats ->
            stats.values().each { assert it != 0 }
        }

        and: "System collects stats for egress cookie of protected path with zero value"
        def protectedReverseCookie = flowInfo.protectedReversePath.cookie.value
        def protectedReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: protectedReverseCookie]).dps
        protectedReverseCookieStat.values().each { assert it == 0 }

        when: "Break ISL on the main path (bring port down) to init auto swap"
        def islToBreak = pathHelper.getInvolvedIsls(currentPath)[0]
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(PROTECTED_PATH_INSTALLATION_TIME) {
            assert northboundV2.getFlowStatus(flow.id).status == FlowState.UP
            assert pathHelper.convert(northbound.getFlowPath(flow.id)) == currentProtectedPath
        }

        and: "Generate traffic on the flow"
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "System collects stats for previous egress cookie of protected path with non zero value"
        Wrappers.wait(statsRouterInterval, waitInterval) {
            def protectedPathStat = otsdb.query(startTime, metric, tags).dps
            assert protectedPathStat.size() > mainPathStat.size()
            def newProtectedReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: protectedReverseCookie]).dps
            assert !newProtectedReverseCookieStat.values().findAll { it != 0 }.empty
        }

        and: "System doesn't collect stats for previous main path cookies due to main path is broken"
        def newMainForwardCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainForwardCookie]).dps
        def newMainReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainReverseCookie]).dps
        newMainForwardCookieStat.size() == mainForwardCookieStat.size()
        newMainReverseCookieStat.size() == mainReverseCookieStat.size()

        and: "Cleanup: revert system to original state"
        flowHelper.deleteFlow(flow.id)
        antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED
        }
        database.resetCosts()
    }

    def "System collects stat when protected flow is DEGRADED"() {
        given: "Two active not neighboring switches with two not overlapping paths at least"
        def traffGenSwitches = topology.activeTraffGens*.switchConnected*.dpId
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.src.dpId in traffGenSwitches && it.dst.dpId in traffGenSwitches &&
                    it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A flow with protected path"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelperV2.addFlow(flow)

        and: "All alternative paths are unavailable (bring ports down on the source switch)"
        List<PathNode> broughtDownPorts = []
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        switchPair.paths.findAll {
            it.first() != pathHelper.convert(flowPathInfo).first() &&
                    it.first() != pathHelper.convert(flowPathInfo.protectedPath).first()
        }.unique {
            it.first()
        }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        when: "Break ISL on a protected path (bring port down) for changing the flow state to DEGRADED"
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        def protectedIsls = pathHelper.getInvolvedIsls(currentProtectedPath)
        antiflap.portDown(protectedIsls[0].dstSwitch.dpId, protectedIsls[0].dstPort)
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northboundV2.getFlow(flow.flowId)) {
                status == "Degraded"
                statusDetails.mainPath == "Up"
                statusDetails.protectedPath == "Down"
            }
        }

        and: "Generate traffic on the given flow"
        Date startTime = new Date()
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildExam(flowHelperV2.toV1(flow),
                (int) flow.maximumBandwidth, 3)
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "System collects stats for a new main path cookies"
        def metric = metricPrefix + "flow.raw.bytes"
        def tags = [switchid: switchPair.src.dpId.toOtsdFormat(), flowid: flow.flowId]
        def waitInterval = 10
        Wrappers.wait(statsRouterInterval, waitInterval) {
            def mainPathStat = otsdb.query(startTime, metric, tags).dps
            assert mainPathStat.size() >= 1
        }
        def flowInfo = database.getFlow(flow.flowId)
        def mainForwardCookie = flowInfo.forwardPath.cookie.value
        def mainReverseCookie = flowInfo.reversePath.cookie.value
        def mainForwardCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainForwardCookie]).dps
        def mainReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainReverseCookie]).dps
        [mainForwardCookieStat, mainReverseCookieStat].each { stats ->
            stats.values().each { assert it != 0 }
        }

        and: "Cleanup: Restore topology, delete flows and reset costs"
        flowHelperV2.deleteFlow(flow.flowId)
        antiflap.portUp(protectedIsls[0].srcSwitch.dpId, protectedIsls[0].srcPort)
        antiflap.portUp(protectedIsls[0].dstSwitch.dpId, protectedIsls[0].dstPort)
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    @Tags([SMOKE_SWITCHES])
    def "System collects stats when flow is pinned and unmetered"() {
        given: "Two active not neighboring switches"
        def traffGenSwitches = topology.activeTraffGens*.switchConnected*.dpId
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.src.dpId in traffGenSwitches && it.dst.dpId in traffGenSwitches
        } ?: assumeTrue(false, "No suiting switches found")

        and: "An unmetered flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.maximumBandwidth = 0
        flow.ignoreBandwidth = true
        flow.pinned = true
        flow.periodicPings = true
        flowHelperV2.addFlow(flow)

        when: "Generate traffic on the given flow"
        Date startTime = new Date()
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildExam(flowHelperV2.toV1(flow),
                (int) flow.maximumBandwidth, 3)
        exam.setResources(traffExam.startExam(exam, true))
        assert traffExam.waitExam(exam).hasTraffic()

        then: "System collects stats for egress/ingress cookies"
        def metric = metricPrefix + "flow.raw.bytes"
        def tags = [switchid: switchPair.src.dpId.toOtsdFormat(), flowid: flow.flowId]
        def waitInterval = 10
        Wrappers.wait(statsRouterInterval + WAIT_OFFSET, waitInterval) {
            assert otsdb.query(startTime, metric, tags).dps.size() >= 1
        }
        def flowInfo = database.getFlow(flow.flowId)
        def mainForwardCookie = flowInfo.forwardPath.cookie.value
        def mainReverseCookie = flowInfo.reversePath.cookie.value
        def mainForwardCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainForwardCookie]).dps
        def mainReverseCookieStat = otsdb.query(startTime, metric, tags + [cookie: mainReverseCookie]).dps
        [mainForwardCookieStat, mainReverseCookieStat].each { stats ->
            stats.values().each { assert it != 0 }
        }

        and: "Cleanup: Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }
}
