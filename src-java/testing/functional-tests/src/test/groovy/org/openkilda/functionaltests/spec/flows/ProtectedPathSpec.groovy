package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_FAIL
import static org.openkilda.functionaltests.helpers.SwitchHelper.isDefaultMeter
import static org.openkilda.model.MeterId.MAX_SYSTEM_RULE_METER_ID
import static org.openkilda.model.cookie.CookieBase.CookieType.SERVICE_OR_FLOW_SEGMENT
import static org.openkilda.testing.Constants.NON_EXISTENT_FLOW_ID
import static org.openkilda.testing.Constants.PROTECTED_PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.testing.model.topology.TopologyDefinition.Isl
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.Narrative
import spock.lang.See
import spock.lang.Shared
import spock.lang.Unroll

import java.time.Instant
import javax.inject.Provider

@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/solutions/protected-paths")
@Narrative("""Protected path - it is pre-calculated, reserved, and deployed (except ingress rule),
so we can switch traffic fast.

- flow object is extended with a boolean parameter 'allocate_protected_path' with values false(default)
- /flows/{flow-id}/path returns 'main_path' + 'protected_path'.

System can start to use protected path in two case:
- main path is down;
- we send the 'swap' request for a flow with protected path('/v1/flows/{flow_id}/swap')

A flow has the status degraded in case when the main path is up and the protected path is down.

Main and protected paths can't use the same link.""")
@Tags([LOW_PRIORITY])
class ProtectedPathSpec extends HealthCheckSpecification {

    @Autowired @Shared
    Provider<TraffExamService> traffExamProvider

    @Unroll
    def "Able to create a flow with protected path when maximumBandwidth=#bandwidth, vlan=#vlanId"() {
        given: "Two active not neighboring switches with two diverse paths at least"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue(false, "No suiting switches found")

        when: "Create flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flow.maximumBandwidth = bandwidth
        flow.ignoreBandwidth = bandwidth == 0
        flow.source.vlanId = vlanId
        flowHelper.addFlow(flow)

        then: "Flow is created with protected path"
        def flowPathInfo = northbound.getFlowPath(flow.id)
        flowPathInfo.protectedPath

        and: "Rules for main and protected paths are created"
        Wrappers.wait(WAIT_OFFSET) { flowHelper.verifyRulesOnProtectedFlow(flow.id) }

        and: "Validation of flow must be successful"
        northbound.validateFlow(flow.id).each { direction ->
            assert direction.discrepancies.empty
        }

        and: "Cleanup: delete the flow"
        flowHelper.deleteFlow(flow.id)

        where:
        bandwidth | vlanId
        1000      | 3378
        0         | 3378
        1000      | 0
        0         | 0
    }

    @Tags(SMOKE)
    def "Able to enable/disable protected path on a flow"() {
        given: "Two active not neighboring switches with two diverse paths at least"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue(false, "No suiting switches found")

        when: "Create flow without protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = false
        flowHelper.addFlow(flow)

        then: "Flow is created without protected path"
        !northbound.getFlowPath(flow.id).protectedPath
        def flowInfo = northbound.getFlow(flow.id)
        !flowInfo.flowStatusDetails

        when: "Update flow: enable protected path(allocateProtectedPath=true)"
        def currentLastUpdate = flowInfo.lastUpdated
        flowHelper.updateFlow(flow.id, flow.tap { it.allocateProtectedPath = true })

        then: "Protected path is enabled"
        def flowPathInfoAfterUpdating = northbound.getFlowPath(flow.id)
        flowPathInfoAfterUpdating.protectedPath
        northbound.getFlow(flow.id).flowStatusDetails
        def flowInfoFromDb = database.getFlow(flow.id)
        def protectedForwardCookie = flowInfoFromDb.protectedForwardPath.cookie.value
        def protectedReverseCookie = flowInfoFromDb.protectedReversePath.cookie.value

        Instant.parse(currentLastUpdate) < Instant.parse(northbound.getFlow(flow.id).lastUpdated)

        and: "Rules for main and protected paths are created"
        Wrappers.wait(WAIT_OFFSET) { flowHelper.verifyRulesOnProtectedFlow(flow.id) }

        when: "Update flow: disable protected path(allocateProtectedPath=false)"
        def protectedFlowPath = northbound.getFlowPath(flow.id).protectedPath.forwardPath
        northbound.updateFlow(flow.id, flow.tap { it.allocateProtectedPath = false })

        then: "Protected path is disabled"
        !northbound.getFlowPath(flow.id).protectedPath
        !northbound.getFlow(flow.id).flowStatusDetails

        and: "Rules for protected path are deleted"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getFlowStatus(flow.id).status == FlowState.UP
            protectedFlowPath.each { sw ->
                def rules = northbound.getSwitchRules(sw.switchId).flowEntries.findAll {
                    !new Cookie(it.cookie).serviceFlag
                }
                assert rules.every { it != protectedForwardCookie && it != protectedReverseCookie }
            }
        }

        and: "Cleanup: delete the flow"
        flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "Unable to create a single switch flow with protected path"() {
        given: "A switch"
        def sw = topology.activeSwitches.first()

        when: "Create single switch flow"
        def flow = flowHelper.singleSwitchFlow(sw)
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == "Couldn't setup protected path for one-switch flow"

        cleanup:
        !exc && flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "Unable to update a single switch flow to enable protected path"() {
        given: "A switch"
        def sw = topology.activeSwitches.first()

        and: "A flow without protected path"
        def flow = flowHelper.singleSwitchFlow(sw)
        flowHelper.addFlow(flow)

        when: "Update flow: enable protected path"
        northbound.updateFlow(flow.id, flow.tap { it.allocateProtectedPath = true })

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not update flow"
        errorDetails.errorDescription == "Couldn't setup protected path for one-switch flow"

        cleanup:
        flowHelper.deleteFlow(flow.id)
    }

    @Unroll
    def "System is able to switch #flowDescription flow to protected path"() {
        given: "Two active not neighboring switches with three diverse paths at least"
        def allIsls = northbound.getAllLinks()
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 3
        } ?: assumeTrue(false, "No suiting switches found")
        def uniquePathCount = switchPair.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size()

        when: "Create flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = bandwidth
        flow.ignoreBandwidth = bandwidth == 0
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        then: "Flow is created with protected path"
        def flowPathInfo = northbound.getFlowPath(flow.id)
        flowPathInfo.protectedPath

        and: "Current path is not equal to protected path"
        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        currentPath != currentProtectedPath

        and: "Bandwidth is reserved for protected path on involved ISLs"
        def protectedIsls = pathHelper.getInvolvedIsls(currentProtectedPath)
        def protectedIslsInfo = protectedIsls.collect { islUtils.getIslInfo(it).get() }

        allIsls.each { isl ->
            protectedIslsInfo.each { protectedIsl ->
                if (isl.id == protectedIsl.id) {
                    assert isl.availableBandwidth - protectedIsl.availableBandwidth == flow.maximumBandwidth
                }
            }
        }

        when: "Break ISL on the main path (bring port down) to init auto swap"
        def islToBreak = pathHelper.getInvolvedIsls(currentPath)[0]
        def portDown = antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getLink(islToBreak).state == IslChangeType.FAILED }

        then: "Flow is switched to protected path"
        Wrappers.wait(PROTECTED_PATH_INSTALLATION_TIME) {
            assert northbound.getFlowStatus(flow.id).status == FlowState.UP
            def flowPathInfoAfterRerouting = northbound.getFlowPath(flow.id)

            assert pathHelper.convert(flowPathInfoAfterRerouting) == currentProtectedPath
            if (4 <= uniquePathCount) {
                assert pathHelper.convert(flowPathInfoAfterRerouting.protectedPath) != currentPath
                assert pathHelper.convert(flowPathInfoAfterRerouting.protectedPath) != currentProtectedPath
            }
        }

        when: "Restore port status"
        def portUp = antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED
        }

        then: "Path of the flow is not changed"
        pathHelper.convert(northbound.getFlowPath(flow.id)) == currentProtectedPath

        cleanup: "Revert system to original state"
        flowHelper.deleteFlow(flow.id)
        if (portDown && !portUp) {
            antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
            Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
                assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED
            }
        }
        database.resetCosts()
        northbound.deleteLinkProps(northbound.getAllLinkProps())

        where:
        flowDescription | bandwidth
        "a metered"     | 1000
        "an unmetered"  | 0
    }

    @Unroll
    def "System reroutes #flowDescription flow to more preferable path and ignores protected path when reroute\
 is intentional"() {
        // 'and ignores protected path' means that the main path won't changed to protected
        given: "Two active neighboring switches with four diverse paths at least"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 4
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = bandwidth
        flow.ignoreBandwidth = bandwidth == 0
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        def flowPathInfo = northbound.getFlowPath(flow.id)
        assert flowPathInfo.protectedPath

        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        assert currentPath != currentProtectedPath

        when: "Make the current and protected path less preferable than alternatives"
        def alternativePaths = switchPair.paths.findAll { it != currentPath && it != currentProtectedPath }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentPath) }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentProtectedPath) }

        and: "Init intentional reroute"
        def rerouteResponse = northbound.rerouteFlow(flow.id)

        then: "Flow is rerouted"
        rerouteResponse.rerouted
        Wrappers.wait(WAIT_OFFSET) {
            northbound.getFlowStatus(flow.id).status == FlowState.UP
        }

        and: "Path is not changed to protected path"
        def flowPathInfoAfterRerouting = northbound.getFlowPath(flow.id)
        def newCurrentPath = pathHelper.convert(flowPathInfoAfterRerouting)
        newCurrentPath != currentPath
        newCurrentPath != currentProtectedPath
        //protected path is rerouted too, because more preferable path is exist
        def newCurrentProtectedPath = pathHelper.convert(flowPathInfoAfterRerouting.protectedPath)
        newCurrentProtectedPath != currentPath
        newCurrentProtectedPath != currentProtectedPath
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }

        and: "Cleanup: revert system to original state"
        flowHelper.deleteFlow(flow.id)
        northbound.deleteLinkProps(northbound.getAllLinkProps())

        where:
        flowDescription | bandwidth
        "a metered"     | 1000
        "an unmetered"  | 0
    }

    @Unroll
    def "System is able to switch #flowDescription flow to protected path and ignores more preferable path when reroute\
 is automatical"() {
        given: "Two active not neighboring switches with three diverse paths at least"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 3
        } ?: assumeTrue(false, "No suiting switches found")

        def uniquePathCount = switchPair.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size()

        and: "A flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.maximumBandwidth = bandwidth
        flow.ignoreBandwidth = bandwidth == 0
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        def flowPathInfo = northbound.getFlowPath(flow.id)
        assert flowPathInfo.protectedPath

        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        assert currentPath != currentProtectedPath

        when: "Make the current and protected path less preferable than alternatives"
        def alternativePaths = switchPair.paths.findAll { it != currentPath && it != currentProtectedPath }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentPath) }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentProtectedPath) }

        and: "Break ISL on the main path (bring port down) to init auto swap"
        def islToBreak = pathHelper.getInvolvedIsls(currentPath)[0]
        def portDown = antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getLink(islToBreak).state == IslChangeType.FAILED }

        then: "Flow is switched to protected path"
        Wrappers.wait(PROTECTED_PATH_INSTALLATION_TIME) {
            def newPathInfo = northbound.getFlowPath(flow.id)
            def newCurrentPath = pathHelper.convert(newPathInfo)
            assert northbound.getFlowStatus(flow.id).status == FlowState.UP
            assert newCurrentPath != currentPath

            def newCurrentProtectedPath = pathHelper.convert(newPathInfo.protectedPath)
            assert newCurrentPath == currentProtectedPath
            if (4 <= uniquePathCount) {
                assert newCurrentProtectedPath != currentPath
                assert newCurrentProtectedPath != currentProtectedPath
            }
        }

        when: "Restore port status"
        def portUp = antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED
        }

        then: "Path of the flow is not changed"
        pathHelper.convert(northbound.getFlowPath(flow.id)) == currentProtectedPath

        cleanup: "Revert system to original state"
        flowHelper.deleteFlow(flow.id)
        if (portDown && !portUp) {
            antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
            Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
                assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED
            }
        }
        database.resetCosts()
        northbound.deleteLinkProps(northbound.getAllLinkProps())

        where:
        flowDescription | bandwidth
        "a metered"     | 1000
        "an unmetered"  | 0
    }

    @Unroll
    def "A flow with protected path does not get rerouted if already on the best path"() {
        given: "Two active neighboring switches and a flow"
        def isls = topology.getIslsForActiveSwitches()
        def (srcSwitch, dstSwitch) = [isls.first().srcSwitch, isls.first().dstSwitch]

        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        def flowPathInfo = northbound.getFlowPath(flow.id)
        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        assert flowPathInfo.protectedPath

        when: "Init intentional reroute"
        def rerouteResponse = northbound.rerouteFlow(flow.id)

        then: "Flow is not rerouted"
        !rerouteResponse.rerouted

        def newFlowPathInfo = northbound.getFlowPath(flow.id)
        pathHelper.convert(newFlowPathInfo) == currentPath
        pathHelper.convert(newFlowPathInfo.protectedPath) == currentProtectedPath

        and: "Cleanup: revert system to original state"
        flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "Unable to create a flow with protected path when there is not enough bandwidth"() {
        given: "Two active neighboring switches"
        def isls = topology.getIslsForActiveSwitches()
        def (srcSwitch, dstSwitch) = [isls.first().srcSwitch, isls.first().dstSwitch]

        and: "Update all ISLs which can be used by protected path"
        def bandwidth = 100
        isls[1..-1].each { database.updateIslAvailableBandwidth(it, 90) }

        when: "Create flow with protected path"
        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        flow.maximumBandwidth = bandwidth
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == "Not enough bandwidth or no path found. " +
                "Couldn't find non overlapping protected path"

        cleanup:
        !exc && flowHelper.deleteFlow(flow.id)
        isls.each { database.resetIslBandwidth(it) }
        !exc && flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "Able to update a flow to enable protected path when there is not enough bandwidth"() {
        given: "Two active neighboring switches"
        def isls = topology.getIslsForActiveSwitches()
        def (srcSwitch, dstSwitch) = [isls.first().srcSwitch, isls.first().dstSwitch]

        and: "Update all ISLs which can be used by protected path"
        def bandwidth = 100
        isls[1..-1].each { database.updateIslAvailableBandwidth(it, 90) }

        when: "Create flow without protected path"
        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        flow.maximumBandwidth = bandwidth
        flow.allocateProtectedPath = false
        flowHelper.addFlow(flow)

        then: "Flow is created without protected path"
        !northbound.getFlowPath(flow.id).protectedPath

        when: "Update flow: enable protected path"
        northbound.updateFlow(flow.id, flow.tap { it.allocateProtectedPath = true })

        then: "Flow is updated and protected path is enabled"
        northbound.getFlowPath(flow.id).protectedPath

        and: "Protected path is DOWN"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.DEGRADED }
        verifyAll(northbound.getFlow(flow.id).flowStatusDetails) {
            mainFlowPathStatus == "Up"
            protectedFlowPathStatus == "Down"
        }

        cleanup:
        flowHelper.deleteFlow(flow.id)
        isls.each { database.resetIslBandwidth(it) }
    }

    def "Able to create a flow with protected path when there is not enough bandwidth and ignoreBandwidth=true"() {
        given: "Two active neighboring switches"
        def isls = topology.getIslsForActiveSwitches()
        def (srcSwitch, dstSwitch) = [isls.first().srcSwitch, isls.first().dstSwitch]

        and: "Update all ISLs which can be used by protected path"
        def bandwidth = 100
        isls[1..-1].each { database.updateIslAvailableBandwidth(it, 90) }

        when: "Create flow with protected path"
        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        flow.maximumBandwidth = bandwidth
        flow.allocateProtectedPath = true
        flow.ignoreBandwidth = true
        flowHelper.addFlow(flow)

        then: "Flow is created with protected path"
        northbound.getFlowPath(flow.id).protectedPath

        and: "One transit vlan is created for main and protected paths"
        def flowInfo = database.getFlow(flow.id)
        database.getTransitVlans(flowInfo.forwardPathId, flowInfo.reversePathId).size() == 1
        database.getTransitVlans(flowInfo.protectedForwardPathId, flowInfo.protectedReversePathId).size() == 1

        and: "Cleanup: delete the flow and restore available bandwidth"
        flowHelper.deleteFlow(flow.id)
        isls.each { database.resetIslBandwidth(it) }
    }

    def "System is able to recalculate protected path when protected path is broken"() {
        given: "Two active not neighboring switches with two diverse paths at least"
        def allIsls = northbound.getAllLinks()
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 3
        } ?: assumeTrue(false, "No suiting switches found")

        when: "Create a flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        then: "Flow is created with protected path"
        def flowPathInfo = northbound.getFlowPath(flow.id)
        flowPathInfo.protectedPath

        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        currentPath != currentProtectedPath

        def protectedIsls = pathHelper.getInvolvedIsls(currentProtectedPath)
        def protectedIslsInfo = protectedIsls.collect { islUtils.getIslInfo(it).get() }

        allIsls.each { isl ->
            protectedIslsInfo.each { protectedIsl ->
                if (isl.id == protectedIsl.id) {
                    assert isl.availableBandwidth - protectedIsl.availableBandwidth == flow.maximumBandwidth
                }
            }
        }

        when: "Break ISL on the protected path (bring port down) to init the recalculate procedure"
        def islToBreakProtectedPath = protectedIsls[0]
        def portDown = antiflap.portDown(islToBreakProtectedPath.dstSwitch.dpId, islToBreakProtectedPath.dstPort)

        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(islToBreakProtectedPath).get().state == IslChangeType.FAILED
        }

        then: "Protected path is recalculated"
        def newProtectedPath
        Wrappers.wait(PROTECTED_PATH_INSTALLATION_TIME) {
            newProtectedPath = pathHelper.convert(northbound.getFlowPath(flow.id).protectedPath)
            assert newProtectedPath != currentProtectedPath
            assert northbound.getFlowStatus(flow.id).status == FlowState.UP
        }

        and: "Current path is not changed"
        currentPath == pathHelper.convert(northbound.getFlowPath(flow.id))

        and: "Bandwidth is reserved for new protected path on involved ISLs"
        def allLinks
        Wrappers.wait(PROTECTED_PATH_INSTALLATION_TIME) {
            def newProtectedIsls = pathHelper.getInvolvedIsls(newProtectedPath)
            allLinks = northbound.getAllLinks()
            def newProtectedIslsInfo = newProtectedIsls.collect { islUtils.getIslInfo(allLinks, it).get() }

            allIsls.each { isl ->
                newProtectedIslsInfo.each { protectedIsl ->
                    if (isl.id == protectedIsl.id) {
                        assert isl.availableBandwidth - protectedIsl.availableBandwidth == flow.maximumBandwidth
                    }
                }
            }
        }

        and: "Reservation is deleted on the broken ISL"
        def originInfoBrokenIsl = allIsls.find {
            it.destination.switchId == islToBreakProtectedPath.dstSwitch.dpId &&
                    it.destination.portNo == islToBreakProtectedPath.dstPort
        }
        def currentInfoBrokenIsl = islUtils.getIslInfo(allLinks, islToBreakProtectedPath).get()

        originInfoBrokenIsl.availableBandwidth == currentInfoBrokenIsl.availableBandwidth

        when: "Restore port status"
        def portUp = antiflap.portUp(islToBreakProtectedPath.dstSwitch.dpId, islToBreakProtectedPath.dstPort)
        Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
            assert islUtils.getIslInfo(islToBreakProtectedPath).get().state == IslChangeType.DISCOVERED
        }

        then: "Path is not recalculated again"
        pathHelper.convert(northbound.getFlowPath(flow.id).protectedPath) == newProtectedPath

        and: "Cleanup: revert system to original state"
        flowHelper.deleteFlow(flow.id)
        if (portDown && !portUp) {
            antiflap.portUp(islToBreakProtectedPath.dstSwitch.dpId, islToBreakProtectedPath.dstPort)
            Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
                assert islUtils.getIslInfo(islToBreakProtectedPath).get().state == IslChangeType.DISCOVERED
            }
        }
        database.resetCosts()
    }

    @Tidy
    @Unroll
    @IterationTag(tags = [LOW_PRIORITY], iterationNameRegex = /unmetered/)
    def "Unable to create #flowDescription flow with protected path if all alternative paths are unavailable"() {
        given: "Two active neighboring switches without alt paths"
        def switchPair = topologyHelper.getNeighboringSwitchPair()
        List<PathNode> broughtDownPorts = []

        switchPair.paths.sort { it.size() }[1..-1].unique {
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

        when: "Try to create a new flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flow.maximumBandwidth = bandwidth
        flow.ignoreBandwidth = bandwidth == 0
        flowHelper.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == "Not enough bandwidth or no path found." +
                " Couldn't find non overlapping protected path"

        cleanup:
        !exc && flowHelper.deleteFlow(flow.id)
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()

        where:
        flowDescription | bandwidth
        "a metered"     | 1000
        "an unmetered"  | 0
    }

    @Tidy
    @Unroll
    @IterationTag(tags = [LOW_PRIORITY], iterationNameRegex = /unmetered/)
    def "Able to update #flowDescription flow to enable protected path if all alternative paths are unavailable"() {
        given: "Two active neighboring switches with two not overlapping paths at least"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A flow without protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = false
        flow.maximumBandwidth = bandwidth
        flow.ignoreBandwidth = bandwidth == 0
        flowHelper.addFlow(flow)

        and: "All alternative paths are unavailable (bring ports down on the source switch)"
        List<PathNode> broughtDownPorts = []
        switchPair.paths.findAll { it != pathHelper.convert(northbound.getFlowPath(flow.id)) }.unique { it.first() }
                .each { path ->
                    def src = path.first()
                    broughtDownPorts.add(src)
                    antiflap.portDown(src.switchId, src.portNo)
                }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        when: "Update flow: enable protected path(allocateProtectedPath=true)"
        northbound.updateFlow(flow.id, flow.tap { it.allocateProtectedPath = true })

        then: "Flow is updated and protected path is enabled"
        northbound.getFlowPath(flow.id).protectedPath

        and: "Protected path is DOWN"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.DEGRADED }
        verifyAll(northbound.getFlow(flow.id).flowStatusDetails) {
            mainFlowPathStatus == "Up"
            protectedFlowPathStatus == "Down"
        }

        cleanup:
        flowHelper.deleteFlow(flow.id)
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()

        where:
        flowDescription | bandwidth
        "a metered"     | 1000
        "an unmetered"  | 0
    }

    @Tags(SMOKE)
    def "Able to swap main and protected paths manually"() {
        given: "A simple flow"
        def tgSwitches = topology.getActiveTraffGens()*.getSwitchConnected()
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            def allowsTraffexam = it.src in tgSwitches && it.dst in tgSwitches
            def usesUniqueSwitches = it.paths.collectMany { pathHelper.getInvolvedSwitches(it) }
                    .unique { it.dpId }.size() > 3
            return allowsTraffexam && usesUniqueSwitches
        } ?: assumeTrue(false, "No suiting switches found")

        def flow = flowHelper.randomFlow(switchPair, true)
        flow.allocateProtectedPath = false
        flowHelper.addFlow(flow)
        assert !northbound.getFlowPath(flow.id).protectedPath

        and: "Cookies are created by flow"
        def createdCookiesSrcSw = northbound.getSwitchRules(switchPair.src.dpId).flowEntries.findAll {
            !new Cookie(it.cookie).serviceFlag
        }*.cookie
        def createdCookiesDstSw = northbound.getSwitchRules(switchPair.dst.dpId).flowEntries.findAll {
            !new Cookie(it.cookie).serviceFlag
        }*.cookie
        def srcSwProps = northbound.getSwitchProperties(switchPair.src.dpId)
        def amountOfserver42Rules = srcSwProps.server42FlowRtt ? 1 : 0
        def amountOfFlowRulesSrcSw = srcSwProps.multiTable ? (3 + amountOfserver42Rules) : (2 + amountOfserver42Rules)
        assert createdCookiesSrcSw.size() == amountOfFlowRulesSrcSw
        def dstSwProps = northbound.getSwitchProperties(switchPair.dst.dpId)
        def amountOfserver42RulesDstSw = dstSwProps.server42FlowRtt ? 1 : 0
        def amountOfFlowRulesDstSw = dstSwProps.multiTable ? (3 + amountOfserver42RulesDstSw) : (2 + amountOfserver42RulesDstSw)
        assert createdCookiesDstSw.size() == amountOfFlowRulesDstSw

        when: "Update flow: enable protected path(allocateProtectedPath=true)"
        flowHelper.updateFlow(flow.id, flow.tap { it.allocateProtectedPath = true })

        then: "Protected path is enabled"
        def flowPathInfo = northbound.getFlowPath(flow.id)
        flowPathInfo.protectedPath
        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        currentPath != currentProtectedPath

        and: "Rules for main and protected paths are created"
        Wrappers.wait(WAIT_OFFSET) {
            flowHelper.verifyRulesOnProtectedFlow(flow.id)
            def cookiesAfterEnablingProtectedPath = northbound.getSwitchRules(switchPair.src.dpId).flowEntries.findAll {
                !new Cookie(it.cookie).serviceFlag
            }*.cookie
            // amountOfFlowRules for main path + one for protected path
            cookiesAfterEnablingProtectedPath.size() == amountOfFlowRulesSrcSw + 1
        }

        and: "No rule discrepancies on every switch of the flow on the main path"
        def mainSwitches = pathHelper.getInvolvedSwitches(currentPath)
        mainSwitches.each { verifySwitchRules(it.dpId) }

        and: "No rule discrepancies on every switch of the flow on the protected path)"
        def protectedSwitches = pathHelper.getInvolvedSwitches(currentProtectedPath)
        protectedSwitches.each { verifySwitchRules(it.dpId) }

        and: "The flow allows traffic(on the main path)"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(flow, 1000, 5)
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        when: "Swap flow paths"
        def srcSwitchCreatedMeterIds = getCreatedMeterIds(switchPair.src.dpId)
        def dstSwitchCreatedMeterIds = getCreatedMeterIds(switchPair.dst.dpId)
        def currentLastUpdate = northbound.getFlow(flow.id).lastUpdated
        northbound.swapFlowPath(flow.id)

        then: "Flow paths are swapped"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }
        def flowPathInfoAfterSwapping = northbound.getFlowPath(flow.id)
        def newCurrentPath = pathHelper.convert(flowPathInfoAfterSwapping)
        def newCurrentProtectedPath = pathHelper.convert(flowPathInfoAfterSwapping.protectedPath)
        newCurrentPath != currentPath
        newCurrentPath == currentProtectedPath
        newCurrentProtectedPath != currentProtectedPath
        newCurrentProtectedPath == currentPath

        Instant.parse(currentLastUpdate) < Instant.parse(northbound.getFlow(flow.id).lastUpdated)

        and: "New meter is created on the src and dst switches"
        def newSrcSwitchCreatedMeterIds = getCreatedMeterIds(switchPair.src.dpId)
        def newDstSwitchCreatedMeterIds = getCreatedMeterIds(switchPair.dst.dpId)
        //added || x.empty to allow situation when meters are not available on src or dst
        newSrcSwitchCreatedMeterIds.sort() != srcSwitchCreatedMeterIds.sort() || srcSwitchCreatedMeterIds.empty
        newDstSwitchCreatedMeterIds.sort() != dstSwitchCreatedMeterIds.sort() || dstSwitchCreatedMeterIds.empty

        and: "Rules are updated"
        Wrappers.wait(WAIT_OFFSET) { flowHelper.verifyRulesOnProtectedFlow(flow.id) }

        and: "Old meter is deleted on the src and dst switches"
        Wrappers.wait(WAIT_OFFSET) {
            [switchPair.src.dpId, switchPair.dst.dpId].each { switchId ->
                def switchValidateInfo = northbound.validateSwitch(switchId)
                if(switchValidateInfo.meters) {
                    assert switchValidateInfo.meters.proper.findAll({dto -> !isDefaultMeter(dto)}).size() == 1
                    switchHelper.verifyMeterSectionsAreEmpty(switchValidateInfo, ["missing", "misconfigured", "excess"])
                }
                assert switchValidateInfo.rules.proper.findAll { def cookie = new Cookie(it)
                    !cookie.serviceFlag && cookie.type == SERVICE_OR_FLOW_SEGMENT }.size() ==
                        (switchId == switchPair.src.dpId) ? amountOfFlowRulesSrcSw + 1 : amountOfFlowRulesDstSw + 1
                switchHelper.verifyRuleSectionsAreEmpty(switchValidateInfo, ["missing", "excess"])
            }
        }

        and: "Transit switches store the correct info about rules and meters"
        def involvedTransitSwitches = (currentPath[1..-2].switchId + currentProtectedPath[1..-2].switchId).unique()
        Wrappers.wait(WAIT_OFFSET) {
            involvedTransitSwitches.each { switchId ->
                def amountOfRules = (switchId in currentProtectedPath*.switchId &&
                        switchId in currentPath*.switchId) ? 4 : 2
                if (northbound.getSwitch(switchId).description.contains("OF_12")) {
                    def switchValidateInfo = northbound.validateSwitchRules(switchId)
                    assert switchValidateInfo.properRules.findAll { !new Cookie(it).serviceFlag }.size() == amountOfRules
                    assert switchValidateInfo.missingRules.size() == 0
                    assert switchValidateInfo.excessRules.size() == 0
                } else {
                    def switchValidateInfo = northbound.validateSwitch(switchId)
                    assert switchValidateInfo.rules.proper.findAll { !new Cookie(it).serviceFlag }.size() == amountOfRules
                    switchHelper.verifyRuleSectionsAreEmpty(switchValidateInfo, ["missing", "excess"])
                    switchHelper.verifyMeterSectionsAreEmpty(switchValidateInfo)
                }
            }
        }

        and: "No rule discrepancies when doing flow validation"
        northbound.validateFlow(flow.id).each { assert it.discrepancies.empty }

        and: "All rules for main and protected paths are updated"
        Wrappers.wait(WAIT_OFFSET) { flowHelper.verifyRulesOnProtectedFlow(flow.id) }

        and: "No rule discrepancies on every switch of the flow on the main path"
        def newMainSwitches = pathHelper.getInvolvedSwitches(newCurrentPath)
        newMainSwitches.each { verifySwitchRules(it.dpId) }

        and: "No rule discrepancies on every switch of the flow on the protected path)"
        def newProtectedSwitches = pathHelper.getInvolvedSwitches(newCurrentProtectedPath)
        newProtectedSwitches.each { verifySwitchRules(it.dpId) }

        and: "The flow allows traffic(on the protected path)"
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "Cleanup: revert system to original state"
        flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "Unable to perform the 'swap' request for a flow without protected path"() {
        given: "Two active neighboring switches"
        def isls = topology.getIslsForActiveSwitches()
        def (srcSwitch, dstSwitch) = [isls.first().srcSwitch, isls.first().dstSwitch]

        and: "A flow without protected path"
        def flow = flowHelper.randomFlow(srcSwitch, dstSwitch)
        flow.allocateProtectedPath = false
        flowHelper.addFlow(flow)
        !northbound.getFlowPath(flow.id).protectedPath

        when: "Try to swap paths for flow that doesn't have protected path"
        northbound.swapFlowPath(flow.id)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.to(MessageError).errorDescription ==
                "Could not swap paths: Flow $flow.id doesn't have protected path"

        cleanup:
        flowHelper.deleteFlow(flow.id)
    }

    @Tidy
    def "Unable to swap paths for a non-existent flow"() {
        when: "Try to swap path on a non-existent flow"
        northbound.swapFlowPath(NON_EXISTENT_FLOW_ID)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        exc.responseBodyAsString.to(MessageError).errorDescription ==
                "Could not swap paths: Flow $NON_EXISTENT_FLOW_ID not found"
    }

    def "Unable to swap paths for an inactive flow"() {
        given: "Two active neighboring switches with two not overlapping paths at least"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        and: "All alternative paths are unavailable (bring ports down on the source switch)"
        List<PathNode> broughtDownPorts = []
        switchPair.paths.findAll {
            it != pathHelper.convert(northbound.getFlowPath(flow.id)) &&
                    it != pathHelper.convert(northbound.getFlowPath(flow.id).protectedPath)
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
        def flowPathInfo = northbound.getFlowPath(flow.id)
        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        def protectedIsls = pathHelper.getInvolvedIsls(currentProtectedPath)
        def currentIsls = pathHelper.getInvolvedIsls(currentPath)
        antiflap.portDown(protectedIsls[0].dstSwitch.dpId, protectedIsls[0].dstPort)

        then: "Flow state is changed to DEGRADED"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.DEGRADED }
        verifyAll(northbound.getFlow(flow.id).flowStatusDetails) {
            mainFlowPathStatus == "Up"
            protectedFlowPathStatus == "Down"
        }

        when: "Break ISL on the main path (bring port down) for changing the flow state to DOWN"
        antiflap.portDown(currentIsls[0].dstSwitch.dpId, currentIsls[0].dstPort)
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getLink(currentIsls[0]).state == IslChangeType.FAILED }

        then: "Flow state is changed to DOWN"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getFlowStatus(flow.id).status == FlowState.DOWN
            assert northbound.getFlowHistory(flow.id).find {
                it.action == REROUTE_ACTION && it.taskId =~ (/.+ : retry #1 ignore_bw true/)
            }?.payload?.last()?.action == REROUTE_FAIL
        }
        verifyAll(northbound.getFlow(flow.id).flowStatusDetails) {
            mainFlowPathStatus == "Down"
            protectedFlowPathStatus == "Down"
        }

        when: "Try to swap paths when main/protected paths are not available"
        northbound.swapFlowPath(flow.id)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.to(MessageError).errorDescription ==
                "Could not swap paths: Protected flow path $flow.id is not in ACTIVE state"

        when: "Restore ISL for the main path only"
        antiflap.portUp(currentIsls[0].srcSwitch.dpId, currentIsls[0].srcPort)
        antiflap.portUp(currentIsls[0].dstSwitch.dpId, currentIsls[0].dstPort)

        then: "Flow state is still DEGRADED"
        Wrappers.wait(PROTECTED_PATH_INSTALLATION_TIME) {
            assert northbound.getFlowStatus(flow.id).status == FlowState.DEGRADED
            verifyAll(northbound.getFlow(flow.id).flowStatusDetails) {
                mainFlowPathStatus == "Up"
                protectedFlowPathStatus == "Down"
            }
        }

        when: "Try to swap paths when the main path is available and the protected path is not available"
        northbound.swapFlowPath(flow.id)

        then: "Human readable error is returned"
        def exc1 = thrown(HttpClientErrorException)
        exc1.rawStatusCode == 400
        def errorDetails = exc1.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not swap paths for flow"
        errorDetails.errorDescription ==
                "Could not swap paths: Protected flow path $flow.id is not in ACTIVE state"

        when: "Restore ISL for the protected path"
        antiflap.portUp(protectedIsls[0].srcSwitch.dpId, protectedIsls[0].srcPort)
        antiflap.portUp(protectedIsls[0].dstSwitch.dpId, protectedIsls[0].dstPort)

        then: "Flow state is changed to UP"
        //it often fails in scope of the whole spec on the hardware env, that's why '* 1.5' is added
        Wrappers.wait(discoveryInterval * 1.5 + WAIT_OFFSET) {
            assert northbound.getFlowStatus(flow.id).status == FlowState.UP
        }

        and: "Cleanup: Restore topology, delete flows and reset costs"
        flowHelper.deleteFlow(flow.id)
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()
    }

    def "System doesn't reroute main flow path when protected path is broken and new alt path is available\
(altPath is more preferable than mainPath)"() {
        given: "Two active neighboring switches with three diverse paths at least"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 3
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        and: "All alternative paths are unavailable (bring ports down on the source switch)"
        def flowPathInfo = northbound.getFlowPath(flow.id)
        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        List<PathNode> broughtDownPorts = []
        switchPair.paths.findAll { it != currentPath && it != currentProtectedPath }.unique {
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

        and: "ISL on a protected path is broken(bring port down) for changing the flow state to DEGRADED"
        def protectedIslToBreak = pathHelper.getInvolvedIsls(currentProtectedPath)[0]
        antiflap.portDown(protectedIslToBreak.dstSwitch.dpId, protectedIslToBreak.dstPort)

        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.DEGRADED }

        when: "Make the current path less preferable than alternative path"
        def alternativePath = switchPair.paths.find { it != currentPath && it != currentProtectedPath }
        def currentIsl = pathHelper.getInvolvedIsls(currentPath)[0]
        def alternativeIsl = pathHelper.getInvolvedIsls(alternativePath)[0]

        switchPair.paths.findAll { it != alternativePath }.each {
            pathHelper.makePathMorePreferable(alternativePath, it)
        }
        assert northbound.getLink(currentIsl).cost > northbound.getLink(alternativeIsl).cost

        and: "Make alternative path available(bring port up on the source switch)"
        antiflap.portUp(alternativeIsl.srcSwitch.dpId, alternativeIsl.srcPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert islUtils.getIslInfo(alternativeIsl).get().state == IslChangeType.DISCOVERED
        }

        then: "Flow state is changed to UP"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }

        and: "Protected path is recalculated only"
        def newFlowPathInfo = northbound.getFlowPath(flow.id)
        pathHelper.convert(newFlowPathInfo) == currentPath
        pathHelper.convert(newFlowPathInfo.protectedPath) == alternativePath

        and: "Cleanup: Restore topology, delete flow and reset costs"
        flowHelper.deleteFlow(flow.id)
        antiflap.portUp(protectedIslToBreak.dstSwitch.dpId, protectedIslToBreak.dstPort)
        broughtDownPorts.each { antiflap.portUp(it.switchId, it.portNo) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }

    @Unroll
    @IterationTag(tags = [LOW_PRIORITY], iterationNameRegex = /unmetered/)
    def "#flowDescription flow is DEGRADED when protected and alternative paths are not available"() {
        given: "Two active neighboring switches with two not overlapping paths at least"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flow.maximumBandwidth = bandwidth
        flow.ignoreBandwidth = bandwidth == 0
        flowHelper.addFlow(flow)
        def flowInfoPath = northbound.getFlowPath(flow.id)
        assert flowInfoPath.protectedPath

        when: "All alternative paths are unavailable"
        def mainPath = pathHelper.convert(flowInfoPath)
        def untouchableIsls = pathHelper.getInvolvedIsls(mainPath).collectMany { [it, it.reversed] }
        def altPaths = switchPair.paths.findAll { [it, it.reverse()].every { it != mainPath }}
        def islsToBreak = altPaths.collectMany { pathHelper.getInvolvedIsls(it) }
                                                 .collectMany { [it, it.reversed] }.unique()
                                                 .findAll { !untouchableIsls.contains(it) }.unique { [it, it.reversed].sort() }
        withPool { islsToBreak.eachParallel { Isl isl -> antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort) } }
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == islsToBreak.size() * 2
        }

        then: "Flow status is DEGRADED"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getFlowStatus(flow.id).status == FlowState.DEGRADED
            assert northbound.getFlowHistory(flow.id).last().payload.find { it.action == REROUTE_FAIL }
        }


        when: "Update flow: disable protected path(allocateProtectedPath=false)"
        northbound.updateFlow(flow.id, flow.tap { it.allocateProtectedPath = false })

        then: "Flow status is UP"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.id).status == FlowState.UP }

        and: "Cleanup: Restore topology, delete flow and reset costs"
        flowHelper.deleteFlow(flow.id)
        withPool { islsToBreak.eachParallel { Isl isl -> antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort) } }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state != IslChangeType.FAILED }
        }
        database.resetCosts()

        where:
        flowDescription | bandwidth
        "A metered"     | 1000
        "An unmetered"  | 0
    }

    def "System doesn't allow to enable the pinned flag on a protected flow"() {
        given: "A protected flow"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue(false, "No suiting switches found")
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        when: "Update flow: enable the pinned flag(pinned=true)"
        northbound.updateFlow(flow.id, flow.tap { it.pinned = true })

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not update flow"
        errorDetails.errorDescription == "Flow flags are not valid, unable to process pinned protected flow"

        and: "Cleanup: Delete the flow"
        flowHelper.deleteFlow(flow.id)
    }

    @Ignore("https://github.com/telstra/open-kilda/issues/2762")
    def "System reuses current protected path when can't find new non overlapping protected path while intentional\
 rerouting"() {
        given: "Two active neighboring switches with three diverse paths"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() == 3
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A flow with protected path"
        def flow = flowHelper.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelper.addFlow(flow)

        def flowPathInfo = northbound.getFlowPath(flow.id)
        assert flowPathInfo.protectedPath

        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        assert currentPath != currentProtectedPath

        when: "Make the current and protected path less preferable than alternatives"
        def alternativePaths = switchPair.paths.findAll { it != currentPath && it != currentProtectedPath }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentPath) }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentProtectedPath) }

        and: "Init intentional reroute"
        def rerouteResponse = northbound.rerouteFlow(flow.id)

        then: "Flow should be rerouted"
        rerouteResponse.rerouted

        and: "Flow main path should be rerouted to a new path and ignore protected path"
        def flowPathInfoAfterRerouting = northbound.getFlowPath(flow.id)
        def newCurrentPath = pathHelper.convert(flowPathInfoAfterRerouting)
        newCurrentPath != currentPath
        newCurrentPath != currentProtectedPath

        and: "Flow protected path shouldn't be rerouted due to lack of non overlapping path"
        pathHelper.convert(flowPathInfoAfterRerouting.protectedPath) == currentProtectedPath

        and: "Flow and both its paths are UP"
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northbound.getFlow(flow.id)) {
                status == "Up"
                flowStatusDetails.mainFlowPathStatus == "Up"
                flowStatusDetails.protectedFlowPathStatus == "Up"
            }
        }

        and: "Cleanup: revert system to original state"
        flowHelper.deleteFlow(flow.id)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
    }

    List<Integer> getCreatedMeterIds(SwitchId switchId) {
        return northbound.getAllMeters(switchId).meterEntries.findAll {
            it.meterId > MAX_SYSTEM_RULE_METER_ID
        }*.meterId
    }
}
