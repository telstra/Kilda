package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.testing.Constants.NON_EXISTENT_FLOW_ID
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID
import static org.openkilda.testing.Constants.PROTECTED_PATH_INSTALLATION_TIME
import static org.openkilda.testing.Constants.RULES_DELETION_TIME
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static org.openkilda.testing.service.floodlight.model.FloodlightConnectMode.RW

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.IterationTags
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.SwitchId
import org.openkilda.northbound.dto.v2.flows.FlowLoopPayload
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.See
import spock.lang.Shared
import spock.lang.Unroll

import javax.inject.Provider

@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/flow-loop")
@Narrative("""Flow loop feature designed for flow path testing. Loop provides additional flow rules on one of the 
terminating switch so any flow traffic is returned to switch-port where it was received. Such flow has 'looped=true'
flag and supports all flow operations. When the loop removed system should restore the original flow rules.
Enabling flowLoop in flow history is registered as the 'update' operation.""")
class FlowLoopSpec extends HealthCheckSpecification {

    @Autowired @Shared
    Provider<TraffExamService> traffExamProvider

    @Tidy
    @Unroll
    @IterationTags([
            @IterationTag(tags = [SMOKE_SWITCHES, TOPOLOGY_DEPENDENT], iterationNameRegex = /protected/),
            @IterationTag(tags = [HARDWARE], iterationNameRegex = /vxlan/)
    ])
    def "Able to create flowLoop for a #data.flowDescription flow"() {
        given: "An active and valid  #data.flowDescription flow"
        def allTraffGenSwIds = topology.activeTraffGens*.switchConnected*.dpId
        assumeTrue((allTraffGenSwIds.size() > 1), "Unable to find switches connected to traffGens")
        def switchPair = data.switchPair(allTraffGenSwIds)
        assumeTrue(switchPair != null, "Unable to find required switch pair in topology")
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.tap(data.flowTap)
        flowHelperV2.addFlow(flow)

        when: "Create flowLoop on the src switch"
        def flowLoopPayloadSrcSw = new FlowLoopPayload(switchPair.src.dpId)
        def createResponse = northboundV2.createFlowLoop(flow.flowId, flowLoopPayloadSrcSw)

        then: "Create flowLoop response contains flowId and src switchId"
        assert createResponse.flowId == flow.flowId
        assert createResponse.switchId == switchPair.src.dpId

        and: "Flow is UP and valid"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP
        }
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "FlowLoop is really created on the src switch"
        northbound.getFlow(flow.flowId).loopSwitchId == switchPair.src.dpId

        and: "FlowLoop rules are created on the src switch"
        def flowLoopRules // [forward, reverse]
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            flowLoopRules = getFlowLoopRules(switchPair.src.dpId)
            assert flowLoopRules.size() == 2
            assert flowLoopRules*.packetCount.every { it == 0 }
        }

        and: "FlowLoop rules are not created on the dst switch"
        getFlowLoopRules(switchPair.dst.dpId).empty

        and: "The src switch is valid"
        northbound.validateSwitch(switchPair.src.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])

        when: "Send traffic via flow in the forward direction"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flow), 1000, 5)
        exam.forward.setResources(traffExam.startExam(exam.forward))

        then: "Flow doesn't allow traffic, because it is grubbed by flowLoop rules"
        !traffExam.waitExam(exam.forward).hasTraffic()
        //rtretiak: sometimes we receive an additional packet after exam is finished. wait for it for better stability
        sleep(1000)

        and: "Counter only on the forward flowLoop rule is increased"
        def flowInfo = database.getFlow(flow.flowId)
        def forwardCookie = flowInfo.forwardPath.cookie.value
        def reverseCookie = flowInfo.reversePath.cookie.value
        def rulesOnSrcSw = northbound.getSwitchRules(switchPair.src.dpId).flowEntries
        def forwardLoopPacketCount = rulesOnSrcSw.find { it.cookie in flowLoopRules[0].cookie }.packetCount
        forwardLoopPacketCount > 0
        //reverseLoopPacketCount localEnv = 0, stageEnv = 8
        def reverseLoopPacketCount = rulesOnSrcSw.find { it.cookie in flowLoopRules[1].cookie }.packetCount
        forwardLoopPacketCount > reverseLoopPacketCount

        and: "Counter on the simple(ingress/egress) flow rules is not increased on the src switch"
        rulesOnSrcSw.findAll { it.cookie in [forwardCookie, reverseCookie] }*.packetCount.every { it == 0 }

        and: "Counter on the simple(ingress/egress) flow rules on the dst switch is the same as reverseLoopPacketCount"
        northbound.getSwitchRules(switchPair.dst.dpId).flowEntries.findAll {
            it.cookie in [forwardCookie, reverseCookie]
        }*.packetCount.every { it == reverseLoopPacketCount }

        when: "Send traffic via flow in the reverse direction"
        exam.reverse.setResources(traffExam.startExam(exam.reverse))

        then: "Flow doesn't allow traffic, because it is grubbed by flowLoop rules"
        !traffExam.waitExam(exam.reverse).hasTraffic()
        //rtretiak: sometimes we receive an additional packet after exam is finished. wait for it for better stability
        sleep(1000)

        and: "Counter only on the reverse flowLoop rule is increased"
        def rulesOnSrcSw_2 = northbound.getSwitchRules(switchPair.src.dpId).flowEntries
        def reverseLoopPacketCount_2 = rulesOnSrcSw_2.find { it.cookie in flowLoopRules[1].cookie }.packetCount
        reverseLoopPacketCount_2 > reverseLoopPacketCount
        rulesOnSrcSw_2.find { it.cookie in flowLoopRules[0].cookie }.packetCount == forwardLoopPacketCount

        and: "Counter on the simple(ingress/egress) flow rules is not increased on the src switch"
        rulesOnSrcSw_2.findAll { it.cookie in [forwardCookie, reverseCookie] }*.packetCount.every { it == 0 }

        and: "Counter on the simple(ingress/egress) flow rules is increased on the dst switch"
        with(northbound.getSwitchRules(switchPair.dst.dpId).flowEntries) {
            it.findAll { it.cookie in [forwardCookie, reverseCookie] }*.packetCount.each {
                assert it == reverseLoopPacketCount_2
            }
        }

        when: "Delete flowLoop"
        def deleteResponse = northboundV2.deleteFlowLoop(flow.flowId)

        then: "The delete flowLoop response contains the flowId"
        deleteResponse.flowId == flow.flowId

        and: "FlowLoop is really deleted"
        !northboundV2.getFlow(flow.flowId).loopSwitchId

        and: "Flow is UP and valid"
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP
        }
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "FlowLoop rules are deleted from the src switch"
        Wrappers.wait(RULES_DELETION_TIME) {
            assert getFlowLoopRules(switchPair.src.dpId).empty
        }

        and: "The src switch is valid"
        northbound.validateSwitch(switchPair.src.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        def switchIsValid = true

        and: "Flow allows traffic"
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        cleanup: "Delete the flow"
        flow && flowHelperV2.deleteFlow(flow.flowId)
        !switchIsValid && [switchPair.src.dpId, switchPair.dst.dpId].each {
            northbound.synchronizeSwitch(it, true)
        }

        where:
        data << [[
                         flowDescription: "pinned",
                         switchPair     : { List<SwitchId> switchIds ->
                             getSwPairConnectedToTraffGenForSimpleFlow(switchIds)
                         },
                         flowTap        : { FlowRequestV2 fl -> fl.pinned = true }
                 ],
                 [
                         flowDescription: "default",
                         switchPair     : { List<SwitchId> switchIds ->
                             getSwPairConnectedToTraffGenForSimpleFlow(switchIds)
                         },
                         flowTap        : { FlowRequestV2 fl ->
                             fl.source.vlanId = 0
                             fl.destination.vlanId = 0
                         }
                 ],
                 [
                         flowDescription: "protected",
                         switchPair     : { List<SwitchId> switchIds ->
                             getSwPairConnectedToTraffGenForProtectedFlow(switchIds)
                         },
                         flowTap        : { FlowRequestV2 fl -> fl.allocateProtectedPath = true }
                 ],
                 //https://github.com/telstra/open-kilda/issues/4072
//                 [
//                         flowDescription: "vxlan",
//                         switchPair     : { List<SwitchId> switchIds ->
//                             getSwPairConnectedToTraffGenForVxlanFlow(switchIds)
//                         },
//                         flowTap        : { FlowRequestV2 fl -> fl.encapsulationType = FlowEncapsulationType.VXLAN }
//                 ],
                 [
                         flowDescription: "qinq",
                         switchPair     : { List<SwitchId> switchIds ->
                             getSwPairConnectedToTraffGenForQinQ(switchIds)
                         },
                         flowTap        : { FlowRequestV2 fl ->
                             fl.source.vlanId = 10
                             fl.source.innerVlanId = 100
                             fl.destination.vlanId = 20
                             fl.destination.innerVlanId = 200
                         }
                 ]
        ]
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Able to delete a flow with created flowLoop on it"() {
        given: "A active multi switch flow"
        def switchPair = topologyHelper.switchPairs.first()
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)

        when: "Create flowLoop on the dst switch"
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.dst.dpId))

        then: "FlowLoop is created on the dst switch"
        def flowLoopOnSwitch = northboundV2.getFlowLoop(flow.flowId, switchPair.dst.dpId)
        flowLoopOnSwitch.size() == 1
        with(flowLoopOnSwitch[0]) {
            it.flowId == flow.flowId
            it.switchId == switchPair.dst.dpId
        }

        and: "FlowLoop rules are created"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert getFlowLoopRules(switchPair.dst.dpId).size() == 2
        }

        and: "Flow history contains info about flowLoop"
        Wrappers.wait(WAIT_OFFSET / 2) {
            def flowHistory = northbound.getFlowHistory(flow.flowId).last()
            assert !flowHistory.dumps.find { it.type == "stateBefore" }.loopSwitchId
            assert flowHistory.dumps.find { it.type == "stateAfter" }.loopSwitchId == switchPair.dst.dpId
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        def flowIsDeleted = true

        then: "FlowLoop rules are deleted from the dst switch"
        Wrappers.wait(RULES_DELETION_TIME) {
            assert getFlowLoopRules(switchPair.dst.dpId).empty
        }

        and: "The dst switch is valid"
        northbound.validateSwitch(switchPair.src.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        def testIsCompleted = true

        cleanup: "Delete the flow"
        flow && !flowIsDeleted && flowHelperV2.deleteFlow(flow.flowId)
        !testIsCompleted && [switchPair.src.dpId, switchPair.dst.dpId].each {
            northbound.synchronizeSwitch(it, true)
        }
    }

    @Tidy
    def "System is able to reroute a flow when flowLoop is created on it"() {
        given: "A multi switch flow with one alternative path at least"
        def allTraffGenSwIds = topology.activeTraffGens*.switchConnected*.dpId
        assumeTrue((allTraffGenSwIds.size() > 1), "Unable to find switches connected to traffGens")
        // pick swPair for protected flow, we can fail any ISL on a flow path and be sure that an alternative path is available
        def switchPair = getSwPairConnectedToTraffGenForProtectedFlow(allTraffGenSwIds)
        assumeTrue(switchPair != null, "Unable to find required switch pair in topology")
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def flowPath = PathHelper.convert(northbound.getFlowPath(flow.flowId))

        and: "FlowLoop is created on the dst switch"
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.dst.dpId))
        def flowLoopOnSwitch = northboundV2.getFlowLoop(flow.flowId)
        assert flowLoopOnSwitch.size() == 1
        with(flowLoopOnSwitch[0]) {
            it.flowId == flow.flowId
            it.switchId == switchPair.dst.dpId
        }

        when: "Fail a flow ISL (bring switch port down)"
        def islToFail = pathHelper.getInvolvedIsls(flowPath).last()
        antiflap.portDown(islToFail.srcSwitch.dpId, islToFail.srcPort)

        then: "The flow was rerouted"
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert PathHelper.convert(northbound.getFlowPath(flow.flowId)) != flowPath
        }

        and: "Flow is UP and valid"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP }
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "FlowLoop is still present on the dst switch"
        northboundV2.getFlow(flow.flowId).loopSwitchId == switchPair.dst.dpId

        and: "FlowLoop rules ar still present on the dst switch"
        getFlowLoopRules(switchPair.dst.dpId).size() == 2

        and: "The src switch is valid"
        northbound.validateSwitch(switchPair.src.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])

        and: "Flow doesn't allow traffic, because it is grubbed by flowLoop rules"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flow), 1000, 5)

        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert !traffExam.waitExam(direction).hasTraffic()
            }
        }
        getFlowLoopRules(switchPair.src.dpId)*.packetCount.every { it > 0 }

        cleanup: "Revive the ISL back (bring switch port up) and delete the flow"
        flow && flowHelperV2.deleteFlow(flow.flowId)
        islToFail && antiflap.portUp(islToFail.srcSwitch.dpId, islToFail.srcPort)
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert northbound.getActiveLinks().size() == topology.islsForActiveSwitches.size() * 2
        }
        database.resetCosts()
    }

    @Tidy
    def "System is able to detect and sync missing flowLoop rules"() {
        given: "An active flow with created flowLoop on the src switch"
        def switchPair = topologyHelper.getNeighboringSwitchPair()
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.src.dpId))
        def flowLoopRules
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            flowLoopRules = getFlowLoopRules(switchPair.src.dpId)*.cookie
            assert flowLoopRules.size() == 2
        }

        when: "Delete flowLoop rules"
        flowLoopRules.each { northbound.deleteSwitchRules(switchPair.src.dpId, it) }

        then: "System detects missing flowLoop rules"
        Wrappers.wait(RULES_DELETION_TIME) {
            assert northbound.validateSwitch(switchPair.src.dpId).rules.missing.sort() == flowLoopRules.sort()
        }

        when: "Sync the src switch"
        def syncResponse = northbound.synchronizeSwitch(switchPair.src.dpId, true)

        then: "Sync response contains flowLoop rules into the installed section"
        syncResponse.rules.installed.sort() == flowLoopRules.sort()

        then: "FlowLoop rules are synced"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert getFlowLoopRules(switchPair.src.dpId).size() == 2
        }
        northbound.validateSwitch(switchPair.src.dpId).rules.missing.empty
        def testIsCompleted = true

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        !testIsCompleted && northbound.synchronizeSwitch(switchPair.src.dpId, true)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Systems allows to get all flowLoops that goes through a switch"() {
        given: "Two active switches"
        def switchPair = topologyHelper.switchPairs.find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        }
        assumeTrue(switchPair != null, "Unable to find required switch pair in topology")

        and: "Three multi switch flows"
        def flow1 = flowHelperV2.randomFlow(switchPair)
        flow1.allocateProtectedPath = true
        flowHelperV2.addFlow(flow1)

        def flow2 = flowHelperV2.randomFlow(switchPair, false, [flow1])
        flowHelperV2.addFlow(flow2)

        def flow3 = flowHelperV2.randomFlow(switchPair, false, [flow1, flow2])
        flowHelperV2.addFlow(flow3)

        when: "Get all flowLoops from the src switch"
        then: "There is no flowLoop because it is not created yet"
        northboundV2.getFlowLoop(switchPair.src.dpId).empty

        when: "Create flowLoop for flow1 and flow2 on the src switch"
        northboundV2.createFlowLoop(flow1.flowId, new FlowLoopPayload(switchPair.src.dpId))
        northboundV2.createFlowLoop(flow2.flowId, new FlowLoopPayload(switchPair.src.dpId))

        and: "Create flowLoop for the flow3 on the dst switch"
        northboundV2.createFlowLoop(flow3.flowId, new FlowLoopPayload(switchPair.dst.dpId))

        and: "Get all flowLoops from the src switch"
        def flowLoopSrcSw = northboundV2.getFlowLoop(switchPair.src.dpId)

        then: "The created flowLoops for flow1/flow2 are returned in the response list from the src switch"
        flowLoopSrcSw.size() == 2
        flowLoopSrcSw*.flowId.sort() == [flow1, flow2]*.flowId.sort()
        flowLoopSrcSw*.switchId.unique() == [switchPair.src.dpId]

        when: "Get all flowLoops from the dst switch"
        def flowLoopDstSw = northboundV2.getFlowLoop(switchPair.dst.dpId)

        then: "Only flow3 is in the response list from the dst switch"
        flowLoopDstSw.size() == 1
        flowLoopDstSw[0].flowId == flow3.flowId
        flowLoopDstSw[0].switchId == switchPair.dst.dpId

        cleanup: "Delete the flows"
        [flow1, flow2, flow3].each { flowHelperV2.deleteFlow(it.flowId) }
    }

    @Tidy
    def "System is able to autoSwapPath for a protected flow when flowLoop is created on it"() {
        given: "Two active switches with three diverse paths at least"
        def allTraffGenSwIds = topology.activeTraffGens*.switchConnected*.dpId
        assumeTrue((allTraffGenSwIds.size() > 1), "Unable to find switches connected to traffGens")
        def switchPair = topologyHelper.getSwitchPairs().find {
            [it.dst, it.src].every { it.dpId in allTraffGenSwIds } && it.paths.unique(false) {
                a, b -> a.intersect(b) == [] ? 1 : 0
            }.size() >= 3
        } ?: assumeTrue(false, "No suiting switches found")


        and: "A protected unmetered flow with flowLoop on the src switch"
        def flow = flowHelperV2.randomFlow(switchPair).tap {
            maximumBandwidth = 0
            ignoreBandwidth = true
            allocateProtectedPath = true
        }
        flowHelperV2.addFlow(flow)
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.src.dpId))

        when: "Break ISL on the main path (bring port down) to init auto swap"
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        def currentPath = pathHelper.convert(flowPathInfo)
        def currentProtectedPath = pathHelper.convert(flowPathInfo.protectedPath)
        def islToBreak = pathHelper.getInvolvedIsls(currentPath)[0]
        antiflap.portDown(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
        def portIsDown = true

        then: "Flow is switched to protected path"
        Wrappers.wait(PROTECTED_PATH_INSTALLATION_TIME) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
            assert northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == currentProtectedPath
        }

        and: "FlowLoop is still present on the src switch"
        northbound.getFlow(flow.flowId).loopSwitchId == switchPair.src.dpId

        and: "FlowLoop rules are still exist on the src switch"
        and: "The src switch is valid"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            def flowLoopRules = getFlowLoopRules(switchPair.src.dpId)
            assert flowLoopRules.size() == 2
            assert flowLoopRules*.packetCount.every { it == 0 }
            northbound.validateSwitch(switchPair.src.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }

        when: "Send traffic via flow"
        def traffExam = traffExamProvider.get()
        def exam = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flow), 1000, 5)

        then: "Flow doesn't allow traffic, because it is grubbed by flowLoop rules"
        withPool {
            [exam.forward, exam.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert !traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "Counter on the flowLoop rules are increased"
        getFlowLoopRules(switchPair.src.dpId)*.packetCount.every { it > 0 }

        cleanup: "Revert system to original state"
        flow && flowHelperV2.deleteFlow(flow.flowId)
        if (portIsDown) {
            antiflap.portUp(islToBreak.srcSwitch.dpId, islToBreak.srcPort)
            Wrappers.wait(WAIT_OFFSET + discoveryInterval) {
                assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED
            }
        }
        database.resetCosts()
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Able to create flowLoop for a singleSwitch flow"() {
        given: "An active singleSwitch flow"
        def sw = topology.activeSwitches.first()
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flowHelperV2.addFlow(flow)

        when: "Create flowLoop on the sw switch"
        def createResponse = northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(sw.dpId))

        then: "Create flowLoop response contains flowId and src switchId"
        assert createResponse.flowId == flow.flowId
        assert createResponse.switchId == sw.dpId

        and: "Flow is UP and valid"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP }
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "FlowLoop is really created on the switch"
        def flowLoopOnSwitch = northboundV2.getFlowLoop(flow.flowId)
        flowLoopOnSwitch.size() == 1
        with(flowLoopOnSwitch[0]) {
            it.flowId == flow.flowId
            it.switchId == sw.dpId
        }

        and: "FlowLoop rules are created"
        and: "The switch is valid"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert getFlowLoopRules(sw.dpId).size() == 2
            northbound.validateSwitch(sw.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }


        when: "Delete flowLoop"
        northboundV2.deleteFlowLoop(flow.flowId)

        then: "FlowLoop is deleted"
        !northboundV2.getFlow(flow.flowId).loopSwitchId

        and: "FlowLoop rules are deleted from the switch"
        Wrappers.wait(RULES_DELETION_TIME) {
            assert getFlowLoopRules(sw.dpId).empty
        }

        and: "Flow is UP and valid"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP }
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "The switch is valid"
        northbound.validateSwitch(sw.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        def testIsCompleted = true

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        !testIsCompleted && northbound.synchronizeSwitch(sw.dpId, true)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Able to create flowLoop for a singleSwitchSinglePort flow"() {
        given: "An active singleSwitchSinglePort flow"
        def sw = topology.activeSwitches.first()
        def flow = flowHelperV2.singleSwitchSinglePortFlow(sw)
        flowHelperV2.addFlow(flow)

        when: "Create flowLoop on the sw switch"
        def createResponse = northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(sw.dpId))

        then: "Create flowLoop response contains flowId and src switchId"
        assert createResponse.flowId == flow.flowId
        assert createResponse.switchId == sw.dpId

        and: "Flow is UP and valid"
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP }
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "FlowLoop is really created on the switch"
        northbound.getFlow(flow.flowId).loopSwitchId == sw.dpId

        and: "FlowLoop rules are created"
        and: "The switch is valid"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert getFlowLoopRules(sw.dpId).size() == 2
            northbound.validateSwitch(sw.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }

        when: "Delete the flow with created flowLoop"
        flowHelperV2.deleteFlow(flow.flowId)
        def flowIsDeleted = true

        then: "FlowLoop rules are deleted from the switch"
        getFlowLoopRules(sw.dpId).empty

        and: "The switch is valid"
        northbound.validateSwitch(sw.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        def testIsCompleted = true

        cleanup: "Delete the flow"
        !flowIsDeleted && flowHelperV2.deleteFlow(flow.flowId)
        !testIsCompleted && northbound.synchronizeSwitch(sw.dpId, true)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Attempt to create the exact same flowLoop twice just reinstalls the rules"() {
        given: "An active multi switch flow with created flowLoop on the src switch"
        def switchPair = topologyHelper.getNeighboringSwitchPair()
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.src.dpId))
        Wrappers.wait(WAIT_OFFSET) { assert northbound.getFlowStatus(flow.flowId).status == FlowState.UP }

        when: "Try to create flowLoop on the src switch again"
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.src.dpId))

        then: "FlowLoop is still present for the src switch"
        northboundV2.getFlow(flow.flowId).loopSwitchId == switchPair.src.dpId

        and: "No extra rules are created on the src/dst switches"
        getFlowLoopRules(switchPair.src.dpId).size() == 2
        getFlowLoopRules(switchPair.dst.dpId).empty

        and: "The src/dst switches are valid"
        [switchPair.src, switchPair.dst].each {
            northbound.validateSwitch(it.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }

        when: "Delete the flow with created flowLoop"
        northboundV2.deleteFlow(flow.flowId)
        def flowIsDeleted = true

        then: "FlowLoop rules are deleted"
        Wrappers.wait(RULES_DELETION_TIME) {
            assert getFlowLoopRules(switchPair.src.dpId).empty
        }
        def testIsCompleted = true

        cleanup:
        !flowIsDeleted && flowHelperV2.deleteFlow(flow.flowId)
        if (!testIsCompleted) {
            [switchPair.src, switchPair.dst].each { northbound.synchronizeSwitch(it.dpId, true) }
        }
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Unable to create flowLoop when a switch is deactivated"() {
        given: "An active flow"
        def switchPair = topologyHelper.getNeighboringSwitchPair()
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)

        and: "Deactivated the src switch"
        def blockData = switchHelper.knockoutSwitch(switchPair.src, RW)

        when: "Try to create flowLoop on th src switch(deactivated)"
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.src.dpId))

        then: "Human readable error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.BAD_REQUEST
        with(e.responseBodyAsString.to(MessageError)) {
            errorMessage == "Could not update flow"
            errorDescription == "Source switch $switchPair.src.dpId is not connected to the controller"
        }

        and: "FlowLoop is not created"
        !northbound.getFlow(flow.flowId).loopSwitchId

        when: "Try to create flowLoop on th dst switch(activated)"
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.dst.dpId))

        then: "Human readable error is returned" //system can't update the flow when it is down
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        with(exc.responseBodyAsString.to(MessageError)) {
            errorMessage == "Could not update flow"
            errorDescription == "Source switch $switchPair.src.dpId is not connected to the controller"
        }

        then: "FlowLoop is not created"
        !northbound.getFlow(flow.flowId).loopSwitchId

        and: "FlowLoop rules are not created on the dst switch"
        getFlowLoopRules(switchPair.dst.dpId).empty

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        switchHelper.reviveSwitch(switchPair.src, blockData, true)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Unable to create flowLoop on the src switch when it is already created on the dst switch"() {
        given: "An active flow with created flowLoop on the src switch"
        def switchPair = topologyHelper.getNeighboringSwitchPair()
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.src.dpId))

        when: "Try to create flowLoop on the dst switch"
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(switchPair.dst.dpId))

        then: "FlowLoop is not created on the dst switch"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.UNPROCESSABLE_ENTITY
        with(exc.responseBodyAsString.to(MessageError)) {
            errorMessage == "Can't create flow loop on '$flow.flowId'"
            errorDescription == "Flow is already looped on switch '$switchPair.src.dpId'"
        }

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Unable to create flowLoop on a transit switch"() {
        given: "An active multi switch flow with transit switch"
        def switchPair = topologyHelper.getNotNeighboringSwitchPair()
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)
        def transitSwId = PathHelper.convert(northbound.getFlowPath(flow.flowId))[1].switchId

        when: "Try to create flowLoop on the transit switch"
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(transitSwId))

        then: "Human readable error is returned" //flowLoop is no allowed on a transit switch
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        with(exc.responseBodyAsString.to(MessageError)) {
            errorMessage == "Could not update flow"
            errorDescription == "Loop switch is not terminating in flow path"
        }

        then: "FlowLoop is not created"
        !northbound.getFlow(flow.flowId).loopSwitchId

        and: "FlowLoop rules are not created on the transit switch"
        getFlowLoopRules(transitSwId).empty

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Unable to create flowLoop for a non existent flow"() {
        when: "Try to create flowLoop on the transit switch"
        def sw = topology.activeSwitches.first()
        northboundV2.createFlowLoop(NON_EXISTENT_FLOW_ID, new FlowLoopPayload(sw.dpId))

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.NOT_FOUND
        with(exc.responseBodyAsString.to(MessageError)) {
            errorMessage == "Flow not found"
            errorDescription == "Flow '$NON_EXISTENT_FLOW_ID' not found."
        }

        and: "FlowLoop rules are not created on the switch"
        getFlowLoopRules(sw.dpId).empty

        and: "The switch is valid"
        northbound.validateSwitch(sw.dpId).verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
        def switchIsValid = true

        cleanup:
        !switchIsValid && northbound.synchronizeSwitch(sw.dpId, true)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Unable to create flowLoop on a non existent switch"() {
        given: "An active multi switch flow"
        def swP = topologyHelper.getNeighboringSwitchPair()
        def flow = flowHelperV2.randomFlow(swP)
        flowHelperV2.addFlow(flow)

        when: "Try to create flowLoop on a non existent switch"
        northboundV2.createFlowLoop(flow.flowId, new FlowLoopPayload(NON_EXISTENT_SWITCH_ID))

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        with(exc.responseBodyAsString.to(MessageError)) {
            errorMessage == "Could not update flow"
            errorDescription == "Loop switch is not terminating in flow path"
        }

        and: "FlowLoop rules are not created for the flow"
        !northboundV2.getFlow(flow.flowId).loopSwitchId

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    def "getSwPairConnectedToTraffGenForSimpleFlow"(List<SwitchId> switchIds) {
        getTopologyHelper().getSwitchPairs().collectMany { [it, it.reversed] }.find { swP ->
            [swP.dst, swP.src].every { it.dpId in switchIds }
        }
    }

    def "getSwPairConnectedToTraffGenForProtectedFlow"(List<SwitchId> switchIds) {
        getTopologyHelper().getSwitchPairs().find {
            [it.dst, it.src].every { it.dpId in switchIds } && it.paths.unique(false) {
                a, b -> a.intersect(b) == [] ? 1 : 0
            }.size() >= 2
        }
    }

    def "getSwPairConnectedToTraffGenForVxlanFlow"(List<SwitchId> switchIds) {
        getTopologyHelper().getSwitchPairs().find {
            [it.dst, it.src].every {
                it.dpId in switchIds &&
                        getNorthbound().getSwitchProperties(it.dpId).supportedTransitEncapsulation
                                .contains(FlowEncapsulationType.VXLAN.toString().toLowerCase())
            }
        }
    }

    def "getSwPairConnectedToTraffGenForQinQ"(List<SwitchId> switchIds) {
        return getTopologyHelper().getSwitchPairs().find { swP ->
            [swP.dst, swP.src].every { it.dpId in switchIds } && swP.paths.findAll { path ->
                pathHelper.getInvolvedSwitches(path).every { getNorthbound().getSwitchProperties(it.dpId).multiTable }
            }
        }
    }

    def getFlowLoopRules(SwitchId switchId) {
        def forwardLoopRule
        def reverseLoopRule
        northbound.getSwitchRules(switchId).flowEntries.findAll {
            def hexCookie = Long.toHexString(it.cookie)
            if (hexCookie.startsWith("40080000")) {
                forwardLoopRule = it
            } else if (hexCookie.startsWith("20080000")) {
                reverseLoopRule = it
            }
        }
        return forwardLoopRule && reverseLoopRule ? [forwardLoopRule, reverseLoopRule] : []
    }
}
