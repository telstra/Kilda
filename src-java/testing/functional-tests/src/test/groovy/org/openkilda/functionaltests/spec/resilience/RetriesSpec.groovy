package org.openkilda.functionaltests.spec.resilience

import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOCKKEEPER
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.DELETE_SUCCESS
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.PATH_SWAP_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_ACTION
import static org.openkilda.functionaltests.helpers.FlowHistoryConstants.REROUTE_FAIL
import static org.openkilda.functionaltests.helpers.Wrappers.timedLoop
import static org.openkilda.functionaltests.helpers.Wrappers.wait
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.SwitchFeature
import org.openkilda.model.SwitchStatus
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.testing.model.topology.TopologyDefinition.Isl
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.lockkeeper.model.TrafficControlData

import groovy.util.logging.Slf4j
import spock.lang.Shared
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

@Slf4j
class RetriesSpec extends HealthCheckSpecification {
    @Shared int globalTimeout = 30 //global timeout for h&s operation

    @Tidy
    def "System retries the reroute (global retry) if it fails to install rules on one of the current target path's switches"() {
        given: "Switch pair with at least 3 available paths, one path should have a transit switch that we will break \
and at least 1 path must remain safe"
        List<PathNode> mainPath, failoverPath, safePath
        Switch switchToBreak //will belong to failoverPath and be absent in safePath
        Isl islToBreak //will be used to break the mainPath. This ISL is not used in safePath or failoverPath
        def switchPair = topologyHelper.switchPairs.find { swPair ->
            if(swPair.paths.size() >= 3) {
                failoverPath = swPair.paths.find { failoverPathCandidate ->
                    def failoverSwitches = pathHelper.getInvolvedSwitches(failoverPathCandidate)
                    safePath = swPair.paths.find { safePathCandidate ->
                        def safeSwitches = pathHelper.getInvolvedSwitches(safePathCandidate)
                        mainPath = swPair.paths.find { it != failoverPathCandidate && it != safePathCandidate }
                        def mainSwitches = pathHelper.getInvolvedSwitches(mainPath)
                        switchToBreak = failoverSwitches.find { !(it in safeSwitches) && !(it in mainSwitches) }
                        def safeIsls = pathHelper.getInvolvedIsls(safePathCandidate).collectMany { [it, it.reversed] }
                        def failoverIsls = pathHelper.getInvolvedIsls(failoverPathCandidate).collectMany { [it, it.reversed] }
                        islToBreak = pathHelper.getInvolvedIsls(mainPath).collectMany { [it, it.reversed] }.find {
                            !(it in safeIsls) && !(it in failoverIsls) && it.srcSwitch != switchToBreak
                        }
                        switchToBreak && islToBreak
                    }
                }
            }
            failoverPath
        }
        assert switchPair, "Not able to find a switch pair with suitable paths"
        log.debug("main path: $mainPath\nfailover path: $failoverPath\nsafe path: $safePath\nisl to break: " +
                "$islToBreak\nswitch to break: $switchToBreak")

        and: "A flow using given switch pair"
        switchPair.paths.findAll { it != mainPath }.each { pathHelper.makePathMorePreferable(mainPath, it) }
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == mainPath

        and: "Switch on the preferred failover path will suddenly be unavailable for rules installation when the reroute starts"
        //select a required failover path beforehand
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        switchPair.paths.findAll { it != failoverPath }.each { pathHelper.makePathMorePreferable(failoverPath, it) }
        //disconnect the switch, but make it look like 'active'
        def blockData = switchHelper.knockoutSwitch(switchToBreak, RW)
        database.setSwitchStatus(switchToBreak.dpId, SwitchStatus.ACTIVE)

        when: "Main path of the flow breaks initiating a reroute"
        def portDown = antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        wait(WAIT_OFFSET) {
            assert northbound.getLink(islToBreak).state == IslChangeType.FAILED
        }

        then: "System fails to install rules on desired path and tries to retry reroute and find new path"
        wait(rerouteDelay + WAIT_OFFSET, 0.1) {
            assert northbound.getFlowHistory(flow.flowId).find {
                it.action == REROUTE_ACTION && it.taskId =~ (/.+ : retry #1/)
            }
        }

        when: "Switch is officially marked as offline"
        database.setSwitchStatus(switchToBreak.dpId, SwitchStatus.INACTIVE)

        then: "System finds another working path and successfully reroutes the flow (one of the retries succeeds)"
        wait(PATH_INSTALLATION_TIME) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }
        def currentPath = pathHelper.convert(northbound.getFlowPath(flow.flowId))
        currentPath != mainPath
        currentPath != failoverPath
        !pathHelper.getInvolvedSwitches(currentPath).contains(switchToBreak)
        !pathHelper.getInvolvedIsls(currentPath).contains(islToBreak)
        !pathHelper.getInvolvedIsls(currentPath).contains(islToBreak.reversed)

        and: "All related switches have no rule anomalies"
        [mainPath, failoverPath, currentPath].collectMany { pathHelper.getInvolvedSwitches(it) }.unique()
                .findAll { it != switchToBreak }.each {
            northbound.validateSwitch(it.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        if(blockData) {
            database.setSwitchStatus(switchToBreak.dpId, SwitchStatus.INACTIVE)
            switchHelper.reviveSwitch(switchToBreak, blockData)
        }
        if(portDown) {
            antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        }
        wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }

    @Tidy
    @Unroll
    @Tags([SMOKE_SWITCHES, LOCKKEEPER])
    def "System tries to retry rule installation during #data.description if previous one is failed"(){
        given: "Two active neighboring switches with two diverse paths at least"
        def allPaths
        def swPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            allPaths = it.paths
            allPaths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2 &&
                    allPaths.find { it.size() > 2 }
        } ?: assumeTrue("No switch pair with at least 2 diverse paths", false)

        List<PathNode> mainPath = allPaths.min { it.size() }
        //find path with more than two switches
        List<PathNode> protectedPath = allPaths.findAll { it != mainPath && it.size() != 2 }.min { it.size() }

        and: "All alternative paths unavailable (bring ports down)"
        def broughtDownIsls = []
        def otherIsls = []
        def involvedIsls = (pathHelper.getInvolvedIsls(mainPath) + pathHelper.getInvolvedIsls(protectedPath)).unique()
        allPaths.findAll { it != mainPath && it != protectedPath }.each {
            pathHelper.getInvolvedIsls(it).findAll { !(it in involvedIsls || it.reversed in involvedIsls) }.each {
                otherIsls.add(it)
            }
        }
        broughtDownIsls = otherIsls.unique { a, b -> a == b || a == b.reversed ? 0 : 1 }
        broughtDownIsls.every { antiflap.portDown(it.srcSwitch.dpId, it.srcPort) }
        wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == otherIsls.size() * 2
        }

        and: "A protected flow"
        /** At this point we have the following topology:
         *
         *   srcSwitch - - - - - dstSwitch <- swToManipulate
         *          \              /
         *           \           /
         *           transitSwitch
         *
         **/
        def flow = flowHelperV2.randomFlow(swPair)
        flow.allocateProtectedPath = true
        flowHelperV2.addFlow(flow)
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        assert pathHelper.convert(flowPathInfo) == mainPath
        assert pathHelper.convert(flowPathInfo.protectedPath) == protectedPath

        when: "Disconnect dst switch on protected path"
        def swToManipulate = swPair.dst
        def blockData = switchHelper.knockoutSwitch(swToManipulate, RW)
        def isSwitchActivated = false

        and: "Mark the transit switch as ACTIVE in db"
        database.setSwitchStatus(swToManipulate.dpId, SwitchStatus.ACTIVE)

        and: "Init flow #data.description"
        data.action(flow)

        then: "System retried to #data.description"
        wait(WAIT_OFFSET) {
            assert northbound.getFlowHistory(flow.flowId).findAll {
                it.action == data.historyAction
            }.last().payload*.details.findAll{ it =~ /.+ Retrying/}.size() == data.retriesAmount
        }

        then: "Flow is DOWN"
        wait(WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN
        }

        and: "Flow is not rerouted"
        def flowPathInfoAfterSwap = northbound.getFlowPath(flow.flowId)
        pathHelper.convert(flowPathInfoAfterSwap) == mainPath
        pathHelper.convert(flowPathInfoAfterSwap.protectedPath) == protectedPath


        and: "All involved switches pass switch validation(except dst switch)"
        def involvedSwitchIds = pathHelper.getInvolvedSwitches(protectedPath)[0..-2]*.dpId
        wait(WAIT_OFFSET / 2) {
            involvedSwitchIds.each { swId ->
                with(northbound.validateSwitch(swId)) { validation ->
                    validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                    validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
                }
            }
        }

        when: "Connect dst switch back to the controller"
        database.setSwitchStatus(swToManipulate.dpId, SwitchStatus.INACTIVE) //set real status
        switchHelper.reviveSwitch(swPair.dst, blockData)
        isSwitchActivated = true

        then: "Flow is UP"
        wait(discoveryInterval + rerouteDelay + WAIT_OFFSET) {
            northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        if (!isSwitchActivated && blockData) {
            database.setSwitchStatus(swToManipulate.dpId, SwitchStatus.INACTIVE)
            switchHelper.reviveSwitch(swToManipulate, blockData)
            northbound.synchronizeSwitch(swToManipulate.dpId, true)
        }
        broughtDownIsls.every { antiflap.portUp(it.srcSwitch.dpId, it.srcPort) }
        wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
        database.resetCosts()

        where:
        data << [
                //issue #3237
                //[
                //       description: "update",
                //        historyAction: UPDATE_ACTION,
                //        retriesAmount: 15, //
                //        //install: ingress 2 * 3(attempts) + remove: ingress 2 * 3(attempts) + 1 egress * 3(attempts)
                //        action: { FlowRequestV2 f ->
                //           getNorthboundV2().updateFlow(f.flowId, f.tap { it.description = "updated" }) }
                //],
                [
                        description: "swap paths",
                        historyAction: PATH_SWAP_ACTION,
                        retriesAmount: 9,
                        // swap:  install: 3 attempts, revert: delete 3 attempts + install 3 attempts
                        action:  { FlowRequestV2 f ->
                            getNorthbound().swapFlowPath(f.flowId) }
                ]
        ]
    }

    @Tidy
    def "Flow is successfully deleted from the system even if some rule delete commands fail (no rollback for delete)"() {
        given: "A flow"
        def swPair = topologyHelper.switchPairs.first()
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)

        when: "Send delete request for the flow"
        lockKeeper.shapeSwitchesTraffic([swPair.src], new TrafficControlData(1000))
        northboundV2.deleteFlow(flow.flowId)

        and: "One of the related switches does not respond"
        def blockData = switchHelper.knockoutSwitch(swPair.src, RW)

        then: "Flow history shows failed delete rule retry attempts but flow deletion is successful at the end"
        wait(WAIT_OFFSET) {
            def history = northbound.getFlowHistory(flow.flowId).last().payload
            //egress and ingress rule on a broken switch, 3 retries each = total 6
            assert history.count { it.details ==~ /Failed to remove the rule.*Retrying \(attempt \d+\)/ } == 6
            assert history.last().action == DELETE_SUCCESS
        }
        !northboundV2.getFlowStatus(flow.flowId)

        cleanup:
        lockKeeper.cleanupTrafficShaperRules(swPair.src.regions)
        switchHelper.reviveSwitch(swPair.src, blockData, true)
    }

    @Tidy
    def "System retries the intentional rerouteV1 if it fails to install rules on a switch"() {
        given: "Two active neighboring switches with two diverse paths at least(main and backup paths)"
        def allPaths
        def swPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            allPaths = it.paths
            allPaths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2 &&
                    allPaths.find { it.size() > 2 }
        }

        List<PathNode> mainPath = allPaths.min { it.size() }
        //find path with more than two switches
        List<PathNode> backupPath = allPaths.findAll { it != mainPath && it.size() != 2 }.min { it.size() }

        and: "All alternative paths unavailable (bring ports down)"
        def broughtDownIsls = []
        def otherIsls = []
        def involvedIsls = (pathHelper.getInvolvedIsls(mainPath) + pathHelper.getInvolvedIsls(backupPath)).unique()
        allPaths.findAll { it != mainPath && it != backupPath }.each {
            pathHelper.getInvolvedIsls(it).findAll { !(it in involvedIsls || it.reversed in involvedIsls) }.each {
                otherIsls.add(it)
            }
        }
        broughtDownIsls = otherIsls.unique { a, b -> a == b || a == b.reversed ? 0 : 1 }
        broughtDownIsls.every { antiflap.portDown(it.srcSwitch.dpId, it.srcPort) }
        wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == otherIsls.size() * 2
        }

        and: "A flow on the main path"
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == mainPath

        when: "Make backupPath more preferable than mainPath"
        pathHelper.makePathMorePreferable(backupPath, mainPath)

        and: "Disconnect the dst switch"
        def swToManipulate = swPair.dst
        def blockData = switchHelper.knockoutSwitch(swToManipulate, RW)
        def isSwitchActivated = false

        and: "Mark the dst switch as ACTIVE in db"
        database.setSwitchStatus(swToManipulate.dpId, SwitchStatus.ACTIVE)

        and: "Init intentional flow reroute(APIv1)"
        northbound.rerouteFlow(flow.flowId)

        then: "System retries to install/delete rules on the dst switch"
        wait(WAIT_OFFSET) {
            assert northbound.getFlowHistory(flow.flowId).findAll {
                it.action == REROUTE_ACTION
            }.last().payload*.details.findAll{ it =~ /.+ Retrying/}.size() == 9
            //install: 3 attempts, revert: delete 3 attempts + install 3 attempts
        }

        then: "Flow is not rerouted"
        pathHelper.convert(northbound.getFlowPath(flow.flowId)) == mainPath

        and: "All involved switches pass switch validation(except dst switch)"
        def involvedSwitchIds = pathHelper.getInvolvedSwitches(backupPath)[0..-2]*.dpId
        wait(WAIT_OFFSET / 2) {
            involvedSwitchIds.each { swId ->
                with(northbound.validateSwitch(swId)) { validation ->
                    validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                    validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
                }
            }
        }

        when: "Connect dst switch back to the controller"
        database.setSwitchStatus(swToManipulate.dpId, SwitchStatus.INACTIVE) //set real status
        switchHelper.reviveSwitch(swPair.dst, blockData)
        isSwitchActivated = true

        then: "Flow is UP"
        wait(discoveryInterval + rerouteDelay + WAIT_OFFSET) {
            northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        if (!isSwitchActivated && blockData) {
            database.setSwitchStatus(swToManipulate.dpId, SwitchStatus.INACTIVE)
            switchHelper.reviveSwitch(swToManipulate, blockData)
            northbound.synchronizeSwitch(swToManipulate.dpId, true)
        }
        broughtDownIsls.every { antiflap.portUp(it.srcSwitch.dpId, it.srcPort) }
        wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
        database.resetCosts()
    }

    @Tidy
    def "System does not retry after global timeout for reroute operation"() {
        given: "A flow with ability to reroute"
        def swPair = topologyHelper.switchPairs.find { it.paths.size() > 1 }
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)

        when: "Break current path to trigger a reroute"
        def islToBreak = pathHelper.getInvolvedIsls(flow.flowId).first()
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)

        and: "Connection to src switch is slow in order to simulate a global timeout on reroute operation"
        lockKeeper.shapeSwitchesTraffic([swPair.src], new TrafficControlData(7000))

        then: "After global timeout expect flow reroute to fail and flow to become DOWN"
        TimeUnit.SECONDS.sleep(globalTimeout)
        int eventsAmount
        wait(globalTimeout + WAIT_OFFSET, 1) { //long wait, may be doing some revert actions after global t/o
            def history = northbound.getFlowHistory(flow.flowId)
            def lastEvent = history.last().payload
            assert lastEvent.find { it.action == sprintf('Global timeout reached for reroute operation on flow "%s"', flow.flowId) }
            assert lastEvent.last().action == REROUTE_FAIL
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN
            eventsAmount = history.size()
        }

        and: "Flow remains down and no new history events appear for the next 3 seconds (no retry happens)"
        timedLoop(3) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN
            assert northbound.getFlowHistory(flow.flowId).size() == eventsAmount
        }

        and: "Src/dst switches are valid"
        wait(WAIT_OFFSET * 2) { //due to instability in multiTable mode
            [flow.source.switchId, flow.destination.switchId].each { verifySwitchRules(it) }
        }

        cleanup:
        lockKeeper.cleanupTrafficShaperRules(swPair.src.regions)
        flowHelperV2.deleteFlow(flow.flowId)
        antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        wait(WAIT_OFFSET) { northbound.activeLinks.size() == topology.islsForActiveSwitches.size() * 2 }
        database.resetCosts()
    }

    @Tidy
    def "System remains in consistent state when flow is reverted back after table mode change (failed reroute)"() {
        given: "A flow, with src switch supporting multi-table (currently in single-table)"
        def swPair = topologyHelper.switchPairs.find {
            it.src.features.contains(SwitchFeature.MULTI_TABLE) && it.paths.size() > 1 &&
                    it.paths.find { it.size() > 2 }
        }
        assumeTrue("Couldn't find a switch pair with src sw supporting Multi-table, 2+ paths and at least 1 " +
                "path having transit switch", swPair as boolean)
        def originalMode = swPair.src.changeMultitable(false)
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        def path = pathHelper.convert(northbound.getFlowPath(flow.flowId))

        and: "Only 1 alt path is available, that path has a transit switch"
        def otherPath = swPair.paths.find { it != path && it.size() > 2 }
        List<Isl> islsToKill = []
        def involvedIsls = pathHelper.getInvolvedIsls(path) + pathHelper.getInvolvedIsls(otherPath)
        swPair.paths.findAll { it != path && it != otherPath }.each {
            pathHelper.getInvolvedIsls(it).findAll { !(it in involvedIsls || it.reversed in involvedIsls) }.each {
                islsToKill.add(it)
            }
        }
        def broughtDownIsls = islsToKill.unique { a, b -> a == b || a == b.reversed ? 0 : 1 }
        broughtDownIsls.every { antiflap.portDown(it.srcSwitch.dpId, it.srcPort) }
        wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == islsToKill.size() * 2
        }

        when: "Change src switch mode to multi-table"
        swPair.src.changeMultitable(true)

        and: "Transit switch on alt path is false-active, so that on reroute system fails to install rules and rollback"
        def currentSwitches = pathHelper.getInvolvedSwitches(path)
        def otherSwitches = pathHelper.getInvolvedSwitches(otherPath)
        def brokenSwitch = otherSwitches.find { !currentSwitches.contains(it) }
        def blockData = switchHelper.knockoutSwitch(brokenSwitch, RW)
        def swDown = true
        database.setSwitchStatus(brokenSwitch.dpId, SwitchStatus.ACTIVE)

        and: "Break ISL on current path to init a reroute"
        def otherIsls = pathHelper.getInvolvedIsls(otherPath)
        def isl = pathHelper.getInvolvedIsls(path).find { !otherIsls.contains(it) }
        def portDown = antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)

        then: "After 4 reroute attempts flows goes Down" //reroute + 3 retries
        wait(WAIT_OFFSET * 4) { //some long wait here, multiple retry attempts
            assert northbound.getFlowHistory(flow.flowId).count { it.action == REROUTE_ACTION } == 4
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN
        }

        when: "Original path becomes available again (ISL goes UP)"
        def portUp = antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)

        then: "Flow goes UP"
        wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }

        and: "Flow and switches are valid"
        northbound.validateFlow(flow.flowId).every { it.asExpected }
        currentSwitches.each {
            def validation = northbound.validateSwitch(it.dpId)
            validation.verifyRuleSectionsAreEmpty(["excess", "missing", "misconfigured"])
            validation.verifyMeterSectionsAreEmpty(["excess", "missing", "misconfigured"])
        }
        def done = true

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        if(swDown) {
            database.setSwitchStatus(brokenSwitch.dpId, SwitchStatus.INACTIVE)
            switchHelper.reviveSwitch(brokenSwitch, blockData)
        }
        originalMode && swPair.src.changeMultitable(originalMode)
        !done && currentSwitches && (currentSwitches + otherSwitches).unique { it.dpId }.each {
            northbound.synchronizeSwitch(it.dpId, true) }
        broughtDownIsls && broughtDownIsls.each { antiflap.portUp(it.srcSwitch.dpId, it.srcPort) }
        isl && portDown && !portUp && antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
        wait(WAIT_OFFSET * 2) {
            northbound.getAllLinks().each { assert it.state == IslChangeType.DISCOVERED } }
        database.resetCosts()
    }

    @Tidy
    def "System remains in consistent state when flow is retries its reroute after table mode change"() {
        given: "A flow, with src switch supporting multi-table (currently in single-table)"
        def swPair = topologyHelper.switchPairs.find {
            it.src.features.contains(SwitchFeature.MULTI_TABLE) && it.paths.size() > 1 &&
                    it.paths.find { it.size() > 2 }
        }
        assumeTrue("Couldn't find a switch pair with src sw supporting Multi-table, 2+ paths and at least 1 " +
                "path having transit switch", swPair as boolean)
        def originalMode = swPair.src.changeMultitable(false)
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        def path = pathHelper.convert(northbound.getFlowPath(flow.flowId))

        and: "Only 1 alt path is available, that path has a transit switch"
        def otherPath = swPair.paths.find { it != path && it.size() > 2 }
        List<Isl> islsToKill = []
        def involvedIsls = pathHelper.getInvolvedIsls(path) + pathHelper.getInvolvedIsls(otherPath)
        swPair.paths.findAll { it != path && it != otherPath }.each {
            pathHelper.getInvolvedIsls(it).findAll { !(it in involvedIsls || it.reversed in involvedIsls) }.each {
                islsToKill.add(it)
            }
        }
        def broughtDownIsls = islsToKill.unique { a, b -> a == b || a == b.reversed ? 0 : 1 }
        broughtDownIsls.every { antiflap.portDown(it.srcSwitch.dpId, it.srcPort) }
        wait(WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.FAILED
            }.size() == islsToKill.size() * 2
        }

        when: "Change src switch mode to multi-table"
        swPair.src.changeMultitable(true)

        and: "Transit switch on alt path is false-active, so that on reroute system fails to install rules and rollback"
        def currentSwitches = pathHelper.getInvolvedSwitches(path)
        def otherSwitches = pathHelper.getInvolvedSwitches(otherPath)
        def brokenSwitch = otherSwitches.find { !currentSwitches.contains(it) }
        def blockData = switchHelper.knockoutSwitch(brokenSwitch, RW)
        def swDown = true
        database.setSwitchStatus(brokenSwitch.dpId, SwitchStatus.ACTIVE)

        and: "Break ISL on current path to init a reroute"
        def otherIsls = pathHelper.getInvolvedIsls(otherPath)
        def isl = pathHelper.getInvolvedIsls(path).find { !otherIsls.contains(it) }
        antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)

        then: "At least 2 reroute attempts happen, but more retries are about to go"
        wait(WAIT_OFFSET * 2) {
            //wait for 2, max is 4
            assert northbound.getFlowHistory(flow.flowId).count { it.action == REROUTE_ACTION } == 2
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN
        }

        when: "Switch one the new path becomes responsive and allows rule installation"
        switchHelper.reviveSwitch(brokenSwitch, blockData)
        swDown = false

        then: "Flow goes UP"
        wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }

        and: "Flow and switches are valid"
        northbound.validateFlow(flow.flowId).every { it.asExpected }
        currentSwitches.each {
            def validation = northbound.validateSwitch(it.dpId)
            validation.verifyRuleSectionsAreEmpty(["excess", "missing", "misconfigured"])
            validation.verifyMeterSectionsAreEmpty(["excess", "missing", "misconfigured"])
        }
        def done = true

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        if(swDown) {
            database.setSwitchStatus(brokenSwitch.dpId, SwitchStatus.INACTIVE)
            switchHelper.reviveSwitch(brokenSwitch, blockData)
        }
        originalMode && swPair.src.changeMultitable(originalMode)
        broughtDownIsls && broughtDownIsls.each { antiflap.portUp(it.srcSwitch.dpId, it.srcPort) }
        isl && antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
        wait(WAIT_OFFSET * 2) {
            northbound.getAllLinks().each { assert it.state == IslChangeType.DISCOVERED } }
        database.resetCosts()
        !done && currentSwitches && (currentSwitches + otherSwitches).unique { it.dpId }.each {
            northbound.synchronizeSwitch(it.dpId, true) }
    }
}
