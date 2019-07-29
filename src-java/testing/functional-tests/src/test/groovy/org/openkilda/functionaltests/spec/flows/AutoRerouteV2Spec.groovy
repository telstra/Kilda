package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.VIRTUAL
import static org.openkilda.functionaltests.helpers.thread.FlowHistoryConstants.REROUTE_ACTION
import static org.openkilda.functionaltests.helpers.thread.FlowHistoryConstants.REROUTE_FAIL
import static org.openkilda.functionaltests.helpers.thread.FlowHistoryConstants.REROUTE_SUCCESS
import static org.openkilda.messaging.info.event.IslChangeType.FAILED
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.messaging.model.system.FeatureTogglesDto
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.SwitchFeature
import org.openkilda.model.SwitchId
import org.openkilda.model.SwitchStatus
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.testing.model.topology.TopologyDefinition.Isl
import org.openkilda.testing.service.lockkeeper.model.TrafficControlData

import groovy.util.logging.Slf4j
import spock.lang.Ignore
import spock.lang.Narrative

import java.util.concurrent.TimeUnit

@Slf4j
@Narrative("Verify different cases when Kilda is supposed to automatically reroute certain flow(s).")
class AutoRerouteV2Spec extends HealthCheckSpecification {

    @Tidy
    @Tags(SMOKE)
    def "Flow is rerouted when one of the flow ISLs fails"() {
        given: "A flow with one alternative path at least"
        def (FlowRequestV2 flow, allFlowPaths) = noIntermediateSwitchFlow(1, true)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        when: "Fail a flow ISL (bring switch port down)"
        Set<Isl> altFlowIsls = []
        def flowIsls = pathHelper.getInvolvedIsls(flowPath)
        allFlowPaths.findAll { it != flowPath }.each { altFlowIsls.addAll(pathHelper.getInvolvedIsls(it)) }
        def islToFail = flowIsls.find { !(it in altFlowIsls) && !(it.reversed in altFlowIsls) }
        antiflap.portDown(islToFail.srcSwitch.dpId, islToFail.srcPort)

        then: "The flow was rerouted after reroute timeout"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert PathHelper.convert(northbound.getFlowPath(flow.flowId)) != flowPath
        }

        and: "Flow writes stats"
        statsHelper.verifyFlowWritesStats(flow.flowId)

        cleanup: "Revive the ISL back (bring switch port up) and delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        antiflap.portUp(islToFail.srcSwitch.dpId, islToFail.srcPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
        database.resetCosts()
    }

    @Tidy
    @Tags(SMOKE)
    def "Flow goes to 'Down' status when one of the flow ISLs fails and there is no ability to reroute"() {
        given: "A flow without alternative paths"
        def (flow, allFlowPaths) = noIntermediateSwitchFlow(0, true)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        def altPaths = allFlowPaths.findAll { it != flowPath }
        List<PathNode> broughtDownPorts = []
        altPaths.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }

        when: "One of the flow ISLs goes down"
        def isl = pathHelper.getInvolvedIsls(flowPath).first()
        def portDown = antiflap.portDown(isl.dstSwitch.dpId, isl.dstPort)

        then: "The flow becomes 'Down'"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN
            assert northbound.getFlowHistory(flow.flowId).last().histories.find { it.action == REROUTE_FAIL }
        }

        when: "ISL goes back up"
        def portUp = antiflap.portUp(isl.dstSwitch.dpId, isl.dstPort)
        Wrappers.wait(antiflapCooldown + discoveryInterval + WAIT_OFFSET) {
            assert islUtils.getIslInfo(isl).get().state == IslChangeType.DISCOVERED
        }

        then: "The flow becomes 'Up'"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        cleanup: "Restore topology to the original state, remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        portDown && !portUp && antiflap.portUp(isl.dstSwitch.dpId, isl.dstPort)
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
        database.resetCosts()
    }

    @Tags([VIRTUAL, LOW_PRIORITY])
    //the actual reroute is caused by the ISL down event which follows the initial sw disconnect
    def "Flow is rerouted when an intermediate switch is disconnected"() {
        given: "An intermediate-switch flow with one alternative path at least"
        def flow = intermediateSwitchFlow(1)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        when: "An intermediate switch is disconnected"
        def sw = findSw(flowPath[1].switchId)
        def blockData = lockKeeper.knockoutSwitch(sw, mgmtFlManager)

        then: "All ISLs going through the intermediate switch are 'FAILED'"
        Wrappers.wait(discoveryTimeout * 1.5 + WAIT_OFFSET) {
            northbound.getAllLinks().findAll {
                flowPath[1].switchId == it.source.switchId || flowPath[1].switchId == it.destination.switchId
            }.each {
                assert it.state == IslChangeType.FAILED
            }
        }

        and: "The flow was rerouted after reroute timeout"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert PathHelper.convert(northbound.getFlowPath(flow.flowId)) != flowPath
        }

        and: "Connect the intermediate switch back and delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        switchHelper.reviveSwitch(sw, blockData)
        northbound.deleteSwitchRules(flowPath[1].switchId, DeleteRulesAction.IGNORE_DEFAULTS) || true
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
    }

    @Tags(SMOKE)
    def "Flow in 'Down' status is rerouted when discovering a new ISL"() {
        given: "An intermediate-switch flow with one alternative path at least"
        def (flow, allFlowPaths) = noIntermediateSwitchFlow(1, true)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        when: "Bring all ports down on the source switch that are involved in the current and alternative paths"
        List<PathNode> broughtDownPorts = []
        allFlowPaths.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }

        then: "The flow goes to 'Down' status"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN
            assert northbound.getFlowHistory(flow.flowId).last().histories.find { it.action == REROUTE_FAIL }
        }

        when: "Bring all ports up on the source switch that are involved in the alternative paths"
        broughtDownPorts.findAll {
            it.portNo != flowPath.first().portNo
        }.each {
            antiflap.portUp(it.switchId, it.portNo)
        }

        then: "The flow goes to 'Up' status"
        Wrappers.wait(rerouteDelay + discoveryInterval + WAIT_OFFSET * 2) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        and: "The flow was rerouted"
        PathHelper.convert(northbound.getFlowPath(flow.flowId)) != flowPath
        Wrappers.wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP }

        and: "Bring port involved in the original path up and delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        antiflap.portUp(flowPath.first().switchId, flowPath.first().portNo)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
        database.resetCosts()
    }

    @Tags(SMOKE)
    def "Flow in 'Up' status is not rerouted when discovering a new ISL and more preferable path is available"() {
        given: "A flow with one alternative path at least"
        def (flow, allFlowPaths) = noIntermediateSwitchFlow(1, true)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        and: "Make the current flow path less preferable than others"
        allFlowPaths.findAll { it != flowPath }.each { pathHelper.makePathMorePreferable(it, flowPath) }

        when: "One of the links not used by flow goes down"
        def involvedIsls = pathHelper.getInvolvedIsls(flowPath)
        def islToFail = topology.islsForActiveSwitches.find {
            !involvedIsls.contains(it) && !involvedIsls.contains(it.reversed)
        }
        antiflap.portDown(islToFail.srcSwitch.dpId, islToFail.srcPort)

        then: "Link status becomes 'FAILED'"
        Wrappers.wait(WAIT_OFFSET) { assert islUtils.getIslInfo(islToFail).get().state == IslChangeType.FAILED }

        when: "Failed link goes up"
        antiflap.portUp(islToFail.srcSwitch.dpId, islToFail.srcPort)

        then: "Link status becomes 'DISCOVERED'"
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert islUtils.getIslInfo(islToFail).get().state == IslChangeType.DISCOVERED
        }

        and: "The flow is not rerouted and doesn't use more preferable path"
        TimeUnit.SECONDS.sleep(rerouteDelay + WAIT_OFFSET)
        northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        PathHelper.convert(northbound.getFlowPath(flow.flowId)) == flowPath

        and: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        database.resetCosts()
    }

    @Tags([VIRTUAL, SMOKE])
    def "Flow in 'Up' status is not rerouted when connecting a new switch and more preferable path is available"() {
        given: "A flow with one alternative path at least"
        def (flow, allFlowPaths) = noIntermediateSwitchFlow(1, true)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        and: "Make the current flow path less preferable than others"
        allFlowPaths.findAll { it != flowPath }.each { pathHelper.makePathMorePreferable(it, flowPath) }

        when: "Disconnect one of the switches not used by flow"
        def involvedSwitches = pathHelper.getInvolvedSwitches(flowPath)
        def switchToDisconnect = topology.getActiveSwitches().find { !involvedSwitches.contains(it) }
        def blockData = lockKeeper.knockoutSwitch(switchToDisconnect, mgmtFlManager)

        then: "The switch is really disconnected from the controller"
        Wrappers.wait(WAIT_OFFSET) { assert !(switchToDisconnect.dpId in northbound.getActiveSwitches()*.switchId) }

        when: "Connect the switch back to the controller"
        lockKeeper.reviveSwitch(switchToDisconnect, blockData)

        then: "The switch is really connected to the controller"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getSwitch(switchToDisconnect.dpId).state == SwitchChangeType.ACTIVATED
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }

        and: "The flow is not rerouted and doesn't use more preferable path"
        TimeUnit.SECONDS.sleep(rerouteDelay + WAIT_OFFSET)
        northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        PathHelper.convert(northbound.getFlowPath(flow.flowId)) == flowPath

        and: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tags([HARDWARE, SMOKE])
    def "Flow is not rerouted when one of the flow ports goes down"() {
        given: "An intermediate-switch flow with one alternative path at least"
        def (FlowRequestV2 flow, List<List<PathNode>> allFlowPaths) = intermediateSwitchFlow(1, true)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        and: "Make the current flow path less preferable than others"
        allFlowPaths.findAll { it != flowPath }.each { pathHelper.makePathMorePreferable(it, flowPath) }

        when: "Bring the flow port down on the source switch"
        antiflap.portDown(flow.source.switchId, flow.source.portNumber)

        then: "The flow is not rerouted"
        TimeUnit.SECONDS.sleep(rerouteDelay)
        PathHelper.convert(northbound.getFlowPath(flow.flowId)) == flowPath

        when: "Bring the flow port down on the destination switch"
        antiflap.portDown(flow.destination.switchId, flow.destination.portNumber)

        then: "The flow is not rerouted"
        TimeUnit.SECONDS.sleep(rerouteDelay)
        PathHelper.convert(northbound.getFlowPath(flow.flowId)) == flowPath

        and: "Bring flow ports up and delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        ["source", "destination"].each { antiflap.portUp(flow."$it".switchId, flow."$it".portNumber) }
        database.resetCosts()
    }

    def "System doesn't reroute flow to a path with not enough bandwidth available"() {
        given: "A flow with alt path available"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { it.paths.size() > 1 } ?:
                assumeTrue("No suiting switches found", false)

        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)

        and: "Bring all ports down on the source switch that are not involved in the current and alternative paths"
        def currentPath = pathHelper.convert(northbound.getFlowPath(flow.flowId))
        def altPath = switchPair.paths.find { it != currentPath }
        List<PathNode> broughtDownPorts = []
        switchPair.paths.findAll { it != currentPath }
                .unique { it.first() }
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

        when: "Make alt path ISLs to have not enough bandwidth to handle the flow"
        def altIsls = pathHelper.getInvolvedIsls(altPath)
        altIsls.each {
            database.updateIslAvailableBandwidth(it, flow.maximumBandwidth - 1)
            database.updateIslAvailableBandwidth(it.reversed, flow.maximumBandwidth - 1)
        }

        and: "Break isl on the main path(bring port down on the source switch) to init auto reroute"
        def islToBreak = pathHelper.getInvolvedIsls(currentPath).first()
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(antiflapMin + 2) {
            assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.FAILED
        }

        then: "Flow state is changed to DOWN"
        Wrappers.wait(WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.DOWN
            assert northbound.getFlowHistory(flow.flowId).last().histories.find { it.action == REROUTE_FAIL }
        }

        and: "Flow is not rerouted"
        Wrappers.timedLoop(rerouteDelay) {
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == currentPath
        }

        and: "Cleanup: Restore topology, delete flow and reset costs/bandwidth"
        flowHelperV2.deleteFlow(flow.flowId)
        broughtDownPorts.every { antiflap.portUp(it.switchId, it.portNo) }
        antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
        altIsls.each {
            database.resetIslBandwidth(it)
            database.resetIslBandwidth(it.reversed)
        }
        database.resetCosts()
    }

    @Ignore
    @Tidy
    @Tags(VIRTUAL)
    def "Flow in 'Down' status is rerouted after switchUp event"() {
        given: "First switch pair with two parallel links and two available paths"
        assumeTrue("Reroute should be completed before link is FAILED", rerouteDelay * 2 < discoveryTimeout)
        def switchPair1 = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.findAll { it.size() == 2 }.size() > 1
        } ?: assumeTrue("No suiting switches found for the first flow", false)
        // disable auto-reroute on islDiscovery event
        northbound.toggleFeature(FeatureTogglesDto.builder().flowsRerouteOnIslDiscoveryEnabled(false).build())

        and: "Second switch pair where the srс switch from the first switch pair is a transit switch"
        List<PathNode> secondFlowPath
        def switchPair2 = topologyHelper.getAllNotNeighboringSwitchPairs().find { swP ->
            (swP.paths + swP.getReversed().paths).find { pathCandidate ->
                secondFlowPath = pathCandidate
                def involvedSwitches = pathHelper.getInvolvedSwitches(pathCandidate)
                involvedSwitches.size() == 3 && involvedSwitches[1].dpId == switchPair1.src.dpId &&
                        involvedSwitches[-1].dpId == switchPair1.dst.dpId
                /**
                 * Because of this condition we have to include all reversed(mirrored) switch pairs during search.
                 * Because all remaining switch pairs may use switchPair1.dst.dpId as their src
                 */
            }
        } ?: assumeTrue("No suiting switches found for the second flow", false)

        //Main and backup paths of firstFlow for further manipulation with them
        def firstFlowMainPath = switchPair1.paths.min { it.size() }
        def firstFlowBackupPath = switchPair1.paths.findAll { it != firstFlowMainPath }.min { it.size() }
        def untouchableIsls = [firstFlowMainPath, firstFlowBackupPath, secondFlowPath]
                .collectMany { pathHelper.getInvolvedIsls(it) }.unique().collectMany { [it, it.reversed] }

        //All alternative paths for both flows are unavailable
        def altPaths1 = switchPair1.paths.findAll {  it != firstFlowMainPath &&  it != firstFlowBackupPath }
        def altPaths2 = switchPair2.paths.findAll {  it != secondFlowPath && it != secondFlowPath.reverse() }
        def islsToBreak = (altPaths1 + altPaths2).collectMany { pathHelper.getInvolvedIsls(it) }
                .collectMany { [it, it.reversed] }.unique()
                .findAll { !untouchableIsls.contains(it) }.unique { [it, it.reversed].sort() }
        withPool {
            islsToBreak.eachParallel { Isl isl -> antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort) }
        }
        Wrappers.wait(antiflapMin + WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == FAILED
            }.size() == islsToBreak.size() * 2
        }

        //firstFlowMainPath path more preferable than the firstFlowBackupPath
        pathHelper.makePathMorePreferable(firstFlowMainPath, firstFlowBackupPath)

        and: "First flow without transit switches"
        def firstFlow = flowHelperV2.randomFlow(switchPair1)
        flowHelperV2.addFlow(firstFlow)
        assert PathHelper.convert(northbound.getFlowPath(firstFlow.flowId)) == firstFlowMainPath

        and: "Second flow with transit switch"
        def secondFlow = flowHelperV2.randomFlow(switchPair2)
        flowHelperV2.addFlow(secondFlow)
        //we are not confident which of 2 parallel isls are picked, so just recheck it
        secondFlowPath = pathHelper.convert(northbound.getFlowPath(secondFlow.flowId))

        when: "Disconnect the src switch of the first flow from the controller"
        def islToBreak = pathHelper.getInvolvedIsls(firstFlowMainPath).first()
        def blockData = lockKeeper.knockoutSwitch(switchPair1.src, mgmtFlManager)
        Wrappers.wait(discoveryTimeout + WAIT_OFFSET) {
            assert northbound.getSwitch(switchPair1.src.dpId).state == SwitchChangeType.DEACTIVATED
        }
        def isSwitchActivated = false

        and: "Mark the switch as ACTIVE in db" // just to reproduce #3131
        database.setSwitchStatus(switchPair1.src.dpId, SwitchStatus.ACTIVE)

        and: "Init auto reroute (bring ports down on the dstSwitch)"
        antiflap.portDown(islToBreak.dstSwitch.dpId, islToBreak.dstPort)

        then: "Flows are not rerouted and system tries to reroute a flow with transit switch"
        def flowPathMap = [(firstFlow.flowId): firstFlowMainPath, (secondFlow.flowId): secondFlowPath]
        Wrappers.wait(WAIT_OFFSET * 2) {
            def firstFlowHistory = northbound.getFlowHistory(firstFlow.flowId).findAll { it.action == REROUTE_ACTION }
            assert firstFlowHistory.last().histories.find { it.action == REROUTE_FAIL }
            //check that system doesn't retry to reroute the firstFlow
            assert !firstFlowHistory.find { it.taskId =~ /.+ : retry #1/ }
            def secondFlowHistory = northbound.getFlowHistory(secondFlow.flowId).findAll { it.action == REROUTE_ACTION }
            assert secondFlowHistory.findAll { it.taskId =~ /.+ : retry #2/ }.size() >= 1
            // reroute caused by failed ISL on backup path
            assert secondFlowHistory.findAll {
                it.details =~ /Reason: ISL (.*) become INACTIVE because of FAIL TIMEOUT (.*)/
            }.size() == 1
            /* NOTE: retry is available for a flow when switchUp event appears on a transit switch
            We can't check that 3 attempts of reroute are available in flow history, system can't guarantee it.
            Reason: during retrying ISL on backup path can fail -> new reroute event(e.g. REROUTE_FAIL_ISL)
            will be triggered and put in the queue of reroute -> for instance: in the queue we have 3rd attempt
            and REROUTE_FAIL_ISL -> these two reroutes will be merged based on some algorithm ->
            system execute one reroute only.
            (System doesn't merge reason of reroute, it just pick any reason from queue) */
            withPool {
                [firstFlow.flowId, secondFlow.flowId].eachParallel { String flowId ->
                    assert PathHelper.convert(northbound.getFlowPath(flowId)) == flowPathMap[flowId]
                }
            }
        }

        and: "Flows are 'Down'"
        Wrappers.wait(WAIT_OFFSET / 2) {
            withPool {
                [firstFlow.flowId, secondFlow.flowId].eachParallel { String flowId ->
                    assert northbound.getFlowStatus(flowId).status == FlowState.DOWN
                }
            }
        }

        when: "Connect the switch back to the controller"
        database.setSwitchStatus(switchPair1.src.dpId, SwitchStatus.INACTIVE) // set real status
        switchHelper.reviveSwitch(switchPair1.src, blockData)
        isSwitchActivated = true

        then: "System tries to reroute the flow on switchUp event"
        /* there is a risk that flows won't find a path during reroute, because switch is online
         but ISLs are not discovered yet, that's why we check that system tries to reroute flow on the switchUp event
         and don't check that flow is UP */
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert northbound.getFlowHistory(firstFlow.flowId).findAll {
                it.action == REROUTE_ACTION
            }.last().details == "Reason: Switch '$switchPair1.src.dpId' online"
            assert northbound.getFlowHistory(secondFlow.flowId).findAll {
                it.action == REROUTE_ACTION
            }.last().details == "Reason: Switch '$switchPair1.src.dpId' online"
        }

        cleanup: "Restore topology, delete the flow and reset costs"
        firstFlow && flowHelperV2.deleteFlow(firstFlow.flowId)
        secondFlow && flowHelperV2.deleteFlow(secondFlow.flowId)
        !isSwitchActivated && blockData && lockKeeper.reviveSwitch(switchPair1.src, blockData)
        northbound.toggleFeature(FeatureTogglesDto.builder().flowsRerouteOnIslDiscoveryEnabled(true).build())
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getSwitch(switchPair1.src.dpId).state == SwitchChangeType.ACTIVATED
        }
        islToBreak && antiflap.portUp(islToBreak.dstSwitch.dpId, islToBreak.dstPort)
        islsToBreak && withPool { islsToBreak.eachParallel { antiflap.portUp(it.srcSwitch.dpId, it.srcPort) } }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
    }

    @Tidy
    @Tags(HARDWARE)
    def "Flow in 'UP' status is not rerouted after switchUp event"() {
        given: "Two active neighboring switches which support round trip latency"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { swP ->
            swP.paths.findAll { path ->
                path.size() == 2 && pathHelper.getInvolvedSwitches(path).every {
                    it.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)
                }
            }
        } ?: assumeTrue("No suiting switches found.", false)

        and: "A flow on the given switch pair"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)

        when: "Deactivate the src switch"
        def swToDeactivate = switchPair.src
        def blockData = lockKeeper.knockoutSwitch(swToDeactivate, mgmtFlManager)
        def isSwDeactivated = true
        // it takes more time to DEACTIVATE a switch via the 'knockoutSwitch' method on the stage env
        Wrappers.wait(WAIT_OFFSET * 4) {
            assert northbound.getSwitch(swToDeactivate.dpId).state == SwitchChangeType.DEACTIVATED
        }

        then: "Flow is UP"
        northbound.getFlowStatus(flow.flowId).status == FlowState.UP

        when: "Activate the src switch"
        lockKeeper.reviveSwitch(swToDeactivate, blockData)
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getSwitch(swToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
            assert northbound.getAllLinks().findAll {
                it.state == IslChangeType.DISCOVERED
            }.size() == topology.islsForActiveSwitches.size() * 2
        }
        isSwDeactivated = false

        then: "System doesn't try to reroute the flow on the switchUp event because flow is already in UP state"
        Wrappers.timedLoop(rerouteDelay + WAIT_OFFSET / 2) {
            assert northbound.getFlowHistory(flow.flowId).findAll { it.action == REROUTE_ACTION }.empty
        }

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        if (isSwDeactivated) {
            lockKeeper.reviveSwitch(swToDeactivate, blockData)
            Wrappers.wait(WAIT_OFFSET) {
                assert northbound.getSwitch(swToDeactivate.dpId).state == SwitchChangeType.ACTIVATED
                assert northbound.getAllLinks().findAll {
                    it.state == IslChangeType.DISCOVERED
                }.size() == topology.islsForActiveSwitches.size() * 2
            }
        }
    }

    @Tidy
    @Tags(VIRTUAL)
    def "Flow is not rerouted when switchUp event appear for a switch which is not related to the flow"() {
        given: "Given a flow in DOWN status on neighboring switches"
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.findAll { it.size() == 2 }.size() == 1
        } ?: assumeTrue("No suiting switches found", false)

        def flowPath = swP.paths.min { it.size() }
        def altPath = swP.paths.findAll { it != flowPath }
        def flow = flowHelperV2.randomFlow(swP)
        flowHelperV2.addFlow(flow)
        assert PathHelper.convert(northbound.getFlowPath(flow.flowId)) == flowPath

        //All alternative paths for both flows are unavailable (bring ports down on the srcSwitch)
        List<PathNode> broughtDownPorts = []
        altPath.unique { it.first() }.each { path ->
            def src = path.first()
            broughtDownPorts.add(src)
            antiflap.portDown(src.switchId, src.portNo)
        }
        Wrappers.wait(antiflapMin + WAIT_OFFSET) {
            assert northbound.getAllLinks().findAll {
                it.state == FAILED
            }.size() == broughtDownPorts.size() * 2
        }

        //move the flow to DOWN status
        def islToBreak = pathHelper.getInvolvedIsls(flowPath).first()
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP

        when: "Generate switchUp event on switch which is not related to the flow"
        def involvedSwitches = pathHelper.getInvolvedSwitches(flowPath)*.dpId
        def switchToManipulate = topology.activeSwitches.find { !(it.dpId in involvedSwitches) }
        def blockData = switchHelper.knockoutSwitch(switchToManipulate, mgmtFlManager)
        def isSwitchActivated = false
        switchHelper.reviveSwitch(switchToManipulate, blockData)
        isSwitchActivated = true

        then: "Flow is not triggered for reroute due to switchUp event because switch is not related to the flow"
        TimeUnit.SECONDS.sleep(rerouteDelay * 2) // it helps to be sure that the auto-reroute operation is completed
        northbound.getFlowHistory(flow.flowId).findAll { it.action == REROUTE_ACTION }.size() == 1

        cleanup: "Restore topology, delete the flow and reset costs"
        flow && flowHelperV2.deleteFlow(flow.flowId)
        islToBreak && antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        !isSwitchActivated && blockData && switchHelper.reviveSwitch(switchToManipulate, blockData)
        broughtDownPorts && broughtDownPorts.each { antiflap.portUp(it.switchId, it.portNo) }
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
    }

    @Tidy
    def "System properly handles multiple flow reroutes if ISL on new path breaks while first reroute is in progress"() {
        given: "Switch pair that have at least 3 paths and 2 paths that have at least 1 common isl"
        List<PathNode> mainPath, backupPath, thirdPath
        List<Isl> mainIsls, backupIsls
        Isl mainPathUniqueIsl, commonIsl
        def swPair = topologyHelper.switchPairs.find { pair ->
            //we are looking for 2 paths that have a common isl. This ISL should not be used in third path
            mainPath = pair.paths.find { path ->
                mainIsls = pathHelper.getInvolvedIsls(path)
                //look for a backup path with a common isl
                backupPath = pair.paths.findAll { it != path }.find { currentBackupPath ->
                    backupIsls = pathHelper.getInvolvedIsls(currentBackupPath)
                    def mainPathUniqueIsls = mainIsls.findAll {
                        !backupIsls.contains(it)
                    }
                    def commonIsls = backupIsls.findAll {
                        it in mainIsls
                    }
                    //given possible mainPath isls to break and available common isls
                    List<Isl> result = [mainPathUniqueIsls, commonIsls].combinations().find { unique, common ->
                        //there should be a safe third path that does not involve any of them
                        thirdPath = pair.paths.findAll { it != path && it != currentBackupPath }.find {
                            def isls = pathHelper.getInvolvedIsls(it)
                            !isls.contains(common) && !isls.contains(unique)
                        }
                    }
                    if(result) {
                        mainPathUniqueIsl = result[0]
                        commonIsl = result[1]
                    }
                    thirdPath
                }
            }
        }
        assert swPair, "Not able to find a switch pair with suitable paths"
        log.debug("main isls: $mainIsls")
        log.debug("backup isls: $backupIsls")

        and: "A flow over these switches that uses one of the desired paths that have common ISL"
        swPair.paths.findAll { it != mainPath }.each { pathHelper.makePathMorePreferable(mainPath, it) }
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)

        and: "A potential 'backup' path that shares common isl has the preferred cost (will be preferred during reroute)"
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        swPair.paths.findAll { it != backupPath }.each { pathHelper.makePathMorePreferable(backupPath, it) }

        when: "An ISL which is unique for current path breaks, leading to a flow reroute"
        antiflap.portDown(mainPathUniqueIsl.srcSwitch.dpId, mainPathUniqueIsl.srcPort)
        Wrappers.wait(3, 0) {
            assert northbound.getLink(mainPathUniqueIsl).state == IslChangeType.FAILED
        }

        and: "Right when reroute starts: an ISL which is common for current path and potential backup path breaks too, \
triggering one more reroute of the current path"
        //add latency to make reroute process longer to allow us break the target path while rules are being installed
        lockKeeper.shapeSwitchesTraffic([swPair.dst], new TrafficControlData(1000))
        //break the second ISL when the first reroute has started and is in progress
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getFlowHistory(flow.flowId).findAll { it.action == REROUTE_ACTION }.size() == 1
        }
        antiflap.portDown(commonIsl.srcSwitch.dpId, commonIsl.srcPort)
        TimeUnit.SECONDS.sleep(rerouteDelay)
        //first reroute should not be finished at this point, otherwise increase the latency to switches
        assert ![REROUTE_SUCCESS, REROUTE_FAIL].contains(
            northbound.getFlowHistory(flow.flowId).find { it.action == REROUTE_ACTION }.histories.last().action)

        then: "System reroutes the flow twice and flow ends up in UP state"
        Wrappers.wait(PATH_INSTALLATION_TIME * 2) {
            def history = northbound.getFlowHistory(flow.flowId)
            def reroutes = history.findAll { it.action == REROUTE_ACTION }
            assert reroutes.size() == 2 //reroute queue, second reroute starts right after first is finished
            reroutes.each { assert it.histories.last().action == REROUTE_SUCCESS }
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }

        and: "New flow path avoids both main and backup paths as well as broken ISLs"
        def actualIsls = pathHelper.getInvolvedIsls(northbound.getFlowPath(flow.flowId))
        !actualIsls.contains(commonIsl)
        !actualIsls.contains(mainPathUniqueIsl)

        and: "Flow is pingable"
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        cleanup:
        swPair && lockKeeper.cleanupTrafficShaperRules(swPair.dst.region)
        flow && flowHelperV2.deleteFlow(flow.flowId)
        withPool {
            [mainPathUniqueIsl, commonIsl].eachParallel { Isl isl ->
                antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
                Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
                    assert northbound.getLink(isl).state == IslChangeType.DISCOVERED
                }
            }
        }
    }

    def singleSwitchFlow() {
        flowHelperV2.singleSwitchFlow(topology.getActiveSwitches().first())
    }

    def noIntermediateSwitchFlow(int minAltPathsCount = 0, boolean getAllPaths = false) {
        def flowWithPaths = getFlowWithPaths(topologyHelper.getAllNeighboringSwitchPairs(), minAltPathsCount)
        return getAllPaths ? flowWithPaths : flowWithPaths[0]
    }

    def intermediateSwitchFlow(int minAltPathsCount = 0, boolean getAllPaths = false) {
        def flowWithPaths = getFlowWithPaths(topologyHelper.getAllNotNeighboringSwitchPairs(), minAltPathsCount)
        return getAllPaths ? flowWithPaths : flowWithPaths[0]
    }

    def getFlowWithPaths(List<SwitchPair> switchPairs, int minAltPathsCount) {
        def switchPair = switchPairs.find { it.paths.size() > minAltPathsCount } ?:
                assumeTrue("No suiting switches found", false)
        return [flowHelperV2.randomFlow(switchPair), switchPair.paths]
    }

    def findSw(SwitchId swId) {
        topology.switches.find { it.dpId == swId }
    }

    def cleanup() {
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        database.resetCosts()
    }
}
