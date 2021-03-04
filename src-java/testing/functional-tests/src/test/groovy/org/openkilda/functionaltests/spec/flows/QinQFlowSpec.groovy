package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.IterationTags
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.SwitchHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.command.switches.DeleteRulesAction
import org.openkilda.messaging.error.MessageError
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.northbound.dto.v2.flows.FlowPatchEndpoint
import org.openkilda.northbound.dto.v2.flows.FlowPatchV2
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Unroll

import javax.inject.Provider

class QinQFlowSpec extends HealthCheckSpecification {

    @Autowired @Shared
    Provider<TraffExamService> traffExamProvider

    @Unroll
    @IterationTags([
            @IterationTag(tags=[SMOKE_SWITCHES],
                    iterationNameRegex = /srcVlanId: 10, srcInnerVlanId: 20, dstVlanId: 30, dstInnerVlanId: 0/)
    ])
    def "System allows to manipulate with QinQ flow\
(srcVlanId: #srcVlanId, srcInnerVlanId: #srcInnerVlanId, dstVlanId: #dstVlanId, dstInnerVlanId: #dstInnerVlanId)"() {
        given: "Two switches connected to traffgen and enabled multiTable mode"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue((allTraffGenSwitches.size() > 1), "Unable to find required switches in topology")
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { sw ->
                sw.dpId in allTraffGenSwitches*.dpId && northbound.getSwitchProperties(sw.dpId).multiTable
            } && it.paths.size() > 2
        } ?: assumeTrue(false, "Not able to find enough switches with traffgens and in multi-table mode")

        when: "Create a protected QinQ flow"
        def qinqFlow = flowHelperV2.randomFlow(swP)
        qinqFlow.source.vlanId = srcVlanId
        qinqFlow.source.innerVlanId = srcInnerVlanId
        qinqFlow.destination.vlanId = dstVlanId
        qinqFlow.destination.innerVlanId = dstInnerVlanId
        qinqFlow.allocateProtectedPath = true
        def response = flowHelperV2.addFlow(qinqFlow)

        then: "Response contains correct info about vlanIds"
        /** System doesn't allow to create a flow with innerVlan and without vlan at the same time.
         * for e.g.: when you create a flow with the following params:
         * vlan == 0 and innerVlan != 0,
         * then flow will be created with vlan != 0 and innerVlan == 0
         */
        with(response) {
            it.source.vlanId == (srcVlanId ? srcVlanId : srcInnerVlanId)
            it.source.innerVlanId == (srcVlanId ? srcInnerVlanId : 0)
            it.destination.vlanId == (dstVlanId ? dstVlanId : dstInnerVlanId)
            it.destination.innerVlanId == (dstVlanId ? dstInnerVlanId : 0)
        }

        and: "Flow is really created with requested vlanIds"
        with(northbound.getFlow(qinqFlow.flowId)) {
            it.source.vlanId == (srcVlanId ? srcVlanId : srcInnerVlanId)
            it.source.innerVlanId == (srcVlanId ? srcInnerVlanId : 0)
            it.destination.vlanId == (dstVlanId ? dstVlanId : dstInnerVlanId)
            it.destination.innerVlanId == (dstVlanId ? dstInnerVlanId : 0)
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(qinqFlow.flowId).each { assert it.asExpected }
        verifyAll(northbound.pingFlow(qinqFlow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "The flow allows traffic"
        def traffExam = traffExamProvider.get()
        def examQinQFlow = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(qinqFlow), 1000, 5)
        withPool {
            [examQinQFlow.forward, examQinQFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "Involved switches pass switch validation"
        def involvedSwitchesFlow1 = pathHelper.getInvolvedSwitches(
                pathHelper.convert(northbound.getFlowPath(qinqFlow.flowId))
        )
        involvedSwitchesFlow1.each {
            with(northbound.validateSwitch(it.dpId)) { validation ->
                validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                validation.verifyHexRuleSectionsAreEmpty(["missingHex", "excessHex", "misconfiguredHex"])
                validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
            }
        }

        when: "Create a vlan flow on the same port as QinQ flow"
        def vlanFlow = flowHelper.randomFlow(swP).tap {
            it.source.portNumber = qinqFlow.source.portNumber
            it.source.vlanId = qinqFlow.source.vlanId + 1
            it.destination.portNumber = qinqFlow.destination.portNumber
            it.destination.vlanId = qinqFlow.destination.vlanId + 1
        }
        flowHelper.addFlow(vlanFlow)

        then: "Both existing flows are valid"
        [qinqFlow.flowId, vlanFlow.id].each {
            northbound.validateFlow(it).each { assert it.asExpected }
        }

        and: "Involved switches pass switch validation"
        def involvedSwitchesFlow2 = pathHelper.getInvolvedSwitches(pathHelper.convert(northbound.getFlowPath(vlanFlow.id)))
        def involvedSwitchesforBothFlows = (involvedSwitchesFlow1 + involvedSwitchesFlow2).unique { it.dpId }
        involvedSwitchesforBothFlows.each {
            with(northbound.validateSwitch(it.dpId)) { validation ->
                validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
            }
        }

        and: "Both flows are pingable"
        [qinqFlow.flowId, vlanFlow.id].each {
            verifyAll(northbound.pingFlow(it, new PingInput())) {
                it.forward.pingSuccess
                it.reverse.pingSuccess
            }
        }

        then: "Both flows allow traffic"
        def examSimpleFlow = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(vlanFlow, 1000, 5)
        withPool {
            [examQinQFlow.forward, examQinQFlow.reverse, examSimpleFlow.forward, examSimpleFlow.reverse]
                    .eachParallel { direction ->
                        def resources = traffExam.startExam(direction)
                        direction.setResources(resources)
                        assert traffExam.waitExam(direction).hasTraffic()
                    }
        }

        when: "Update the QinQ flow(outer/inner vlans)"
        def updateResponse = flowHelperV2.updateFlow(qinqFlow.flowId, qinqFlow.tap {
            qinqFlow.source.vlanId = vlanFlow.source.vlanId
            qinqFlow.source.innerVlanId = vlanFlow.destination.vlanId
            qinqFlow.destination.vlanId = vlanFlow.destination.vlanId
            qinqFlow.destination.innerVlanId = vlanFlow.source.vlanId
        })

        then: "Update response contains correct info about innerVlanIds"
        with(updateResponse) {
            it.source.vlanId == vlanFlow.source.vlanId
            it.source.innerVlanId == vlanFlow.destination.vlanId
            it.destination.vlanId == vlanFlow.destination.vlanId
            it.destination.innerVlanId == vlanFlow.source.vlanId
        }

        and: "Flow is really updated"
        with(northbound.getFlow(qinqFlow.flowId)) {
            it.source.vlanId == vlanFlow.source.vlanId
            it.source.innerVlanId == vlanFlow.destination.vlanId
            it.destination.vlanId == vlanFlow.destination.vlanId
            it.destination.innerVlanId == vlanFlow.source.vlanId
        }

        and: "Flow history shows actual info into stateBefore and stateAfter sections"
        def flowHistory = northbound.getFlowHistory(qinqFlow.flowId)
        with(flowHistory.last().dumps.find { it.type == "stateBefore" }){
            it.sourceVlan == (srcVlanId ? srcVlanId : srcInnerVlanId)
            it.sourceInnerVlan == (srcVlanId ? srcInnerVlanId : 0)
            it.destinationVlan == (dstVlanId ? dstVlanId : dstInnerVlanId)
            it.destinationInnerVlan ==  (dstVlanId ? dstInnerVlanId : 0)
        }
        with(flowHistory.last().dumps.find { it.type == "stateAfter" }){
            it.sourceVlan == vlanFlow.source.vlanId
            it.sourceInnerVlan == vlanFlow.destination.vlanId
            it.destinationVlan == vlanFlow.destination.vlanId
            it.destinationInnerVlan == vlanFlow.source.vlanId
        }

        then: "Both existing flows are still valid and pingable"
        [qinqFlow.flowId, vlanFlow.id].each {
            northbound.validateFlow(it).each { assert it.asExpected }
        }

        [qinqFlow.flowId, vlanFlow.id].each {
            verifyAll(northbound.pingFlow(it, new PingInput())) {
                it.forward.pingSuccess
                it.reverse.pingSuccess
            }
        }

        when: "Delete the flows"
        [qinqFlow.flowId, vlanFlow.id].each { flowHelperV2.deleteFlow(it) }

        then: "Flows rules are deleted"
        involvedSwitchesforBothFlows.each { sw ->
            Wrappers.wait(RULES_INSTALLATION_TIME, 1) {
                assert northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.sort() == sw.defaultCookies.sort()
            }
        }

        and: "Shared rule of flow is deleted"
        [swP.src.dpId, swP.dst.dpId].each { swId ->
            assert northbound.getSwitchRules(swId).flowEntries.findAll {
                new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW
            }.empty
        }

        where:
        srcVlanId | srcInnerVlanId | dstVlanId | dstInnerVlanId
        10        | 20             | 30        | 40
        10        | 0              | 0         | 40
        10        | 20             | 0         | 0
    }

    @Unroll
    def "System allows to create a single switch QinQ flow\
(srcVlanId: #srcVlanId, srcInnerVlanId: #srcInnerVlanId, dstVlanId: #dstVlanId, dstInnerVlanId: #dstInnerVlanId)"() {
        given: "A switch with enabled multiTable mode"
        def sw = topology.activeSwitches.find { northbound.getSwitchProperties(it.dpId).multiTable } ?:
                assumeTrue(false, "Not able to find enough switches in multi-table mode")

        when: "Create a single switch QinQ flow"
        def qinqFlow = flowHelperV2.singleSwitchFlow(sw)
        qinqFlow.source.vlanId = srcVlanId
        qinqFlow.source.innerVlanId = srcInnerVlanId
        qinqFlow.destination.vlanId = dstVlanId
        qinqFlow.destination.innerVlanId = dstInnerVlanId
        def response = flowHelperV2.addFlow(qinqFlow)

        then: "Response contains correct info about vlanIds"
        with(response) {
            it.source.vlanId == (srcVlanId ? srcVlanId : srcInnerVlanId)
            it.source.innerVlanId == (srcVlanId ? srcInnerVlanId : 0)
            it.destination.vlanId == (dstVlanId ? dstVlanId : dstInnerVlanId)
            it.destination.innerVlanId == (dstVlanId ? dstInnerVlanId : 0)
        }

        and: "Flow is really created with requested vlanIds"
        with(northbound.getFlow(qinqFlow.flowId)) {
            it.source.vlanId == (srcVlanId ? srcVlanId : srcInnerVlanId)
            it.source.innerVlanId == (srcVlanId ? srcInnerVlanId : 0)
            it.destination.vlanId == (dstVlanId ? dstVlanId : dstInnerVlanId)
            it.destination.innerVlanId == (dstVlanId ? dstInnerVlanId : 0)
        }

        and: "Flow is valid"
        northbound.validateFlow(qinqFlow.flowId).each { assert it.asExpected }

        and: "Unable to ping a one-switch qinq flow"
        verifyAll(northbound.pingFlow(qinqFlow.flowId, new PingInput())) {
            !it.forward
            !it.reverse
            it.error == "Flow ${qinqFlow.flowId} should not be one switch flow"
        }

        and: "Involved switches pass switch validation"
        with(pathHelper.getInvolvedSwitches(pathHelper.convert(northbound.getFlowPath(qinqFlow.flowId)))) {
            def validationInfo = northbound.validateSwitch(it.dpId)
            validationInfo.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
            validationInfo.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(qinqFlow.flowId)

        then: "Flow rules are deleted"
        Wrappers.wait(RULES_INSTALLATION_TIME, 1) {
            assert northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.sort() == sw.defaultCookies.sort()
        }
        northbound.getSwitchRules(sw.dpId).flowEntries.findAll {
            new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW
        }.empty

        where:
        srcVlanId | srcInnerVlanId | dstVlanId | dstInnerVlanId
        10        | 20             | 30        | 40
        10        | 0              | 0         | 40
        10        | 20             | 0         | 0
    }

    @Tidy
    @Tags(TOPOLOGY_DEPENDENT)
    def "System doesn't allow to create a QinQ flow when a switch supports multi table mode but it is disabled"() {
        given: "A switch pair with disabled multi table mode at least on the one switch"
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].any { northbound.getSwitchProperties(it.dpId).multiTable }
        } ?: assumeTrue(false, "Not able to find enough switches in multi-table mode")
        def initSrcSwProps = northbound.getSwitchProperties(swP.src.dpId)
        SwitchHelper.updateSwitchProperties(swP.src, initSrcSwProps.jacksonCopy().tap {
            it.multiTable = false
        })

        when: "Try to create a QinQ flow when at least on switch doesn't support multi table mode"
        def flow = flowHelperV2.randomFlow(swP)
        flow.source.innerVlanId = 4093
        flow.destination.innerVlanId = 3904
        northboundV2.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage == "Could not create flow"

        cleanup: "Revert system to original state"
        northbound.updateSwitchProperties(swP.src.dpId, initSrcSwProps)
        Wrappers.wait(RULES_INSTALLATION_TIME, 1) {
            assert northbound.getSwitchRules(swP.src.dpId).flowEntries*.cookie.sort() == swP.src.defaultCookies.sort()
        }
        SwitchHelper.updateSwitchProperties(swP.src, initSrcSwProps)
    }

    @Tidy
    @Unroll
    def "System doesn't allow to create a QinQ flow with incorrect innerVlanIds\
(src:#srcInnerVlanId, dst:#dstInnerVlanId)"() {
        given: "A switch pair with enabled multi table mode"
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { northbound.getSwitchProperties(it.dpId).multiTable }
        } ?: assumeTrue(false, "Not able to find enough switches in multi-table mode")

        when: "Try to create a QinQ flow with incorrect innerVlanId"
        def flow = flowHelperV2.randomFlow(swP)
        flow.source.innerVlanId = srcInnerVlanId
        flow.destination.innerVlanId = dstInnerVlanId
        northboundV2.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage == "Invalid request payload"

        cleanup:
        !exc && flowHelper.deleteFlow(flow.flowId)

        where:
        srcInnerVlanId | dstInnerVlanId
        4096           | 10
        10             | -1
    }

    def "System allow to create/update/delete a QinQ flow via APIv1"() {
        given: "Two switches with enabled multi table mode"
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { northbound.getSwitchProperties(it.dpId).multiTable }
        } ?: assumeTrue(false, "Not able to find enough switches in multi-table mode")

        when: "Create a QinQ flow"
        def flow = flowHelper.randomFlow(swP)
        flow.source.innerVlanId = 234
        flow.destination.innerVlanId = 432
        flowHelper.addFlow(flow)

        then: "Flow is really created with requested innerVlanId"
        with(northbound.getFlow(flow.id)) {
            it.source.innerVlanId == flow.source.innerVlanId
            it.destination.innerVlanId == flow.destination.innerVlanId
        }

        when: "Update the flow(innerVlan/vlanId) via partialUpdate"
        def newDstVlanId = flow.destination.vlanId + 1
        def newDstInnerVlanId = flow.destination.innerVlanId + 1
        def updateRequest = new FlowPatchV2(
                destination: new FlowPatchEndpoint(innerVlanId: newDstInnerVlanId, vlanId: newDstVlanId)
        )
        def response = flowHelperV2.partialUpdate(flow.id, updateRequest)

        then: "Partial update response reflects the changes"
        response.destination.vlanId == newDstVlanId
        response.destination.innerVlanId == newDstInnerVlanId

        and: "Flow is really updated with requested innerVlanId/vlanId"
        with(northbound.getFlow(flow.id)) {
            it.destination.vlanId == newDstVlanId
            it.destination.innerVlanId == newDstInnerVlanId
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.id).each { assert it.asExpected }
        verifyAll(northbound.pingFlow(flow.id, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        when: "Delete the flow via APIv1"
        northbound.deleteFlow(flow.id)
        def flowIsDeleted = true

        then: "Flows rules are deleted"
        [swP.src, swP.dst].each { sw ->
            Wrappers.wait(RULES_INSTALLATION_TIME, 1) {
                assert northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.sort() == sw.defaultCookies.sort()
            }
        }

        and: "Shared rule of flow is deleted"
        [swP.src.dpId, swP.dst.dpId].each { swId ->
            assert northbound.getSwitchRules(swId).flowEntries.findAll {
                new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW
            }.empty
        }

        cleanup:
        !flowIsDeleted && flowHelperV2.deleteFlow(flow.id)
    }

    @Tidy
    def "System allows to create QinQ flow and vlan flow with the same vlan on the same port"() {
        given: "Two switches with enabled multi table mode"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue((allTraffGenSwitches.size() > 1), "Unable to find required switches in topology")
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { sw ->
                sw.dpId in allTraffGenSwitches*.dpId && northbound.getSwitchProperties(sw.dpId).multiTable
            }
        } ?: assumeTrue(false, "Not able to find enough switches with traffgens and in multi-table mode")

        when: "Create a QinQ flow"
        def flowWithQinQ = flowHelperV2.randomFlow(swP)
        flowWithQinQ.source.innerVlanId = 234
        flowWithQinQ.destination.innerVlanId = 432
        flowHelperV2.addFlow(flowWithQinQ)

        and: "Create a flow without QinQ"
        def flowWithoutQinQ = flowHelperV2.randomFlow(swP)
        flowWithoutQinQ.source.vlanId = 0
        flowWithoutQinQ.source.innerVlanId = flowWithQinQ.source.vlanId
        flowHelperV2.addFlow(flowWithoutQinQ)

        then: "Both flows allow traffic"
        def traffExam = traffExamProvider.get()
        def examFlowWithtQinQ = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flowWithQinQ), 1000, 5)
        def examFlowWithoutQinQ = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flowWithoutQinQ), 1000, 5)
        withPool {
            [examFlowWithtQinQ.forward, examFlowWithtQinQ.reverse,
             examFlowWithoutQinQ.forward, examFlowWithoutQinQ.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        cleanup:
        flowWithQinQ && flowHelperV2.deleteFlow(flowWithQinQ.flowId)
        flowWithoutQinQ && flowHelperV2.deleteFlow(flowWithoutQinQ.flowId)
    }

    @Unroll
    def "System detects conflict QinQ flows(oVlan: #conflictVlan, iVlan: #conflictInnerVlanId)"() {
        given: "Two switches with enabled multi table mode"
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { northbound.getSwitchProperties(it.dpId).multiTable }
        } ?: assumeTrue(false, "Not able to find enough switches in multi-table mode")

        when: "Create a first flow"
        def flow = flowHelperV2.randomFlow(swP)
        flow.source.vlanId = vlan
        flow.source.innerVlanId = innerVlan
        flowHelperV2.addFlow(flow)

        and: "Try to create a flow which conflicts(vlan) with first flow"
        def conflictFlow = flowHelperV2.randomFlow(swP)
        conflictFlow.source.vlanId = conflictVlan
        conflictFlow.source.innerVlanId = conflictInnerVlanId
        conflictFlow.source.portNumber = flow.source.portNumber
        northboundV2.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.CONFLICT
        exc.responseBodyAsString.to(MessageError).errorMessage == "Could not create flow"

        and: "Revert system to original state"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        vlan | innerVlan | conflictVlan | conflictInnerVlanId
        10   | 100       | 10           | 100
        10   | 0         | 0            | 10
    }

    def "System allows to create more than one QinQ flow on the same port and with the same vlan"() {
        given: "Two switches connected to traffgen and enabled multiTable mode"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue((allTraffGenSwitches.size() > 1), "Unable to find required switches in topology")
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { sw ->
                sw.dpId in allTraffGenSwitches*.dpId && northbound.getSwitchProperties(sw.dpId).multiTable
            }
        } ?: assumeTrue(false, "Not able to find enough switches with traffgens and in multi-table mode")

        when: "Create a first QinQ flow"
        def flow1 = flowHelperV2.randomFlow(swP)
        flow1.source.innerVlanId = 300
        flow1.destination.innerVlanId = 400
        flowHelperV2.addFlow(flow1)

        and: "Create a second QinQ flow"
        def flow2 = flowHelperV2.randomFlow(swP)
        flow2.source.vlanId = flow1.source.vlanId
        flow2.source.innerVlanId = flow1.destination.innerVlanId
        flow2.destination.vlanId = flow1.destination.vlanId
        flow2.destination.innerVlanId = flow1.source.innerVlanId
        flowHelperV2.addFlow(flow2)


        then: "Both flow are valid and pingable"
        [flow1.flowId, flow2.flowId].each { flowId ->
            northbound.validateFlow(flowId).each { assert it.asExpected }
            verifyAll(northbound.pingFlow(flowId, new PingInput())) {
                it.forward.pingSuccess
                it.reverse.pingSuccess
            }
        }

        and: "Flows allow traffic"
        def traffExam = traffExamProvider.get()
        def exam1 = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flow1), 1000, 5)
        def exam2 = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flow2), 1000, 5)
        withPool {
            [exam1.forward, exam1.reverse, exam2.forward, exam2.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        when: "Delete the second flow"
        flowHelperV2.deleteFlow(flow2.flowId)

        then: "The first flow is still valid and pingable"
        northbound.validateFlow(flow1.flowId).each { assert it.asExpected }
        verifyAll(northbound.pingFlow(flow1.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "The first flow still allows traffic"
        withPool {
            [exam1.forward, exam1.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "Cleanup: Delete the first flow"
        flowHelperV2.deleteFlow(flow1.flowId)
    }

    @Unroll
    def "System allows to create a single-switch-port QinQ flow\
(srcVlanId: #srcVlanId, srcInnerVlanId: #srcInnerVlanId, dstVlanId: #dstVlanId, dstInnerVlanId: #dstInnerVlanId)"() {
        given: "A switch with enabled multiTable mode"
        def sw = topology.activeSwitches.find { northbound.getSwitchProperties(it.dpId).multiTable } ?:
                assumeTrue(false, "Not able to find enough switches in multi-table mode")

        when: "Create a single switch QinQ flow"
        def qinqFlow = flowHelperV2.singleSwitchSinglePortFlow(sw)
        qinqFlow.source.vlanId = srcVlanId
        qinqFlow.source.innerVlanId = srcInnerVlanId
        qinqFlow.destination.vlanId = dstVlanId
        qinqFlow.destination.innerVlanId = dstInnerVlanId
        def response = flowHelperV2.addFlow(qinqFlow)

        then: "Response contains correct info about vlanIds"
        with(response) {
            it.source.vlanId == (srcVlanId ? srcVlanId : srcInnerVlanId)
            it.source.innerVlanId == (srcVlanId ? srcInnerVlanId : 0)
            it.destination.vlanId == (dstVlanId ? dstVlanId : dstInnerVlanId)
            it.destination.innerVlanId == (dstVlanId ? dstInnerVlanId : 0)
        }

        and: "Flow is really created with requested vlanIds"
        with(northbound.getFlow(qinqFlow.flowId)) {
            it.source.vlanId == (srcVlanId ? srcVlanId : srcInnerVlanId)
            it.source.innerVlanId == (srcVlanId ? srcInnerVlanId : 0)
            it.destination.vlanId == (dstVlanId ? dstVlanId : dstInnerVlanId)
            it.destination.innerVlanId == (dstVlanId ? dstInnerVlanId : 0)
        }

        and: "Flow is valid"
        northbound.validateFlow(qinqFlow.flowId).each { assert it.asExpected }

        and: "Involved switches pass switch validation"
        with(pathHelper.getInvolvedSwitches(pathHelper.convert(northbound.getFlowPath(qinqFlow.flowId)))) {
            def validationInfo = northbound.validateSwitch(it.dpId)
            validationInfo.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
            validationInfo.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }

        when: "Delete the flow"
        flowHelperV2.deleteFlow(qinqFlow.flowId)

        then: "Flow rules are deleted"
        Wrappers.wait(RULES_INSTALLATION_TIME, 1) {
            assert northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.sort() == sw.defaultCookies.sort()
        }

        where:
        srcVlanId | srcInnerVlanId | dstVlanId | dstInnerVlanId
        10        | 20             | 30        | 40
        10        | 0              | 0         | 40
        10        | 20             | 0         | 0
    }

    @Unroll
    @Tags(HARDWARE) //not tested
    @IterationTags([
            @IterationTag(tags=[SMOKE_SWITCHES],
                    iterationNameRegex = /srcVlanId: 10, srcInnerVlanId: 20, dstVlanId: 30, dstInnerVlanId: 0/)
    ])
    def "System allows to manipulate with QinQ vxlan flow\
(srcVlanId: #srcVlanId, srcInnerVlanId: #srcInnerVlanId, dstVlanId: #dstVlanId, dstInnerVlanId: #dstInnerVlanId)"() {
        given: "Two switches connected to traffgen and enabled multiTable mode"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue((allTraffGenSwitches.size() > 1), "Unable to find required switches in topology")
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { sw ->
                sw.dpId in allTraffGenSwitches*.dpId && northbound.getSwitchProperties(sw.dpId).multiTable &&
                        northbound.getSwitchProperties(sw.dpId).supportedTransitEncapsulation
                                .contains(FlowEncapsulationType.VXLAN.toString().toLowerCase())
            } && it.paths.size() > 2
        } ?: assumeTrue(false, "Not able to find enough switches with traffgens and in multi-table mode")

        when: "Create a protected QinQ vxlan flow"
        def qinqFlow = flowHelperV2.randomFlow(swP)
        qinqFlow.encapsulationType = FlowEncapsulationType.VXLAN
        qinqFlow.source.vlanId = srcVlanId
        qinqFlow.source.innerVlanId = srcInnerVlanId
        qinqFlow.destination.vlanId = dstVlanId
        qinqFlow.destination.innerVlanId = dstInnerVlanId
        qinqFlow.allocateProtectedPath = true
        def response = flowHelperV2.addFlow(qinqFlow)

        then: "Response contains correct info about vlanIds"
        /** System doesn't allow to create a flow with innerVlan and without vlan at the same time.
         * for e.g.: when you create a flow with the following params:
         * vlan == 0 and innerVlan != 0,
         * then flow will be created with vlan != 0 and innerVlan == 0
         */
        with(response) {
            it.source.vlanId == (srcVlanId ? srcVlanId : srcInnerVlanId)
            it.source.innerVlanId == (srcVlanId ? srcInnerVlanId : 0)
            it.destination.vlanId == (dstVlanId ? dstVlanId : dstInnerVlanId)
            it.destination.innerVlanId == (dstVlanId ? dstInnerVlanId : 0)
        }

        and: "Flow is really created with requested vlanIds"
        with(northbound.getFlow(qinqFlow.flowId)) {
            it.source.vlanId == (srcVlanId ? srcVlanId : srcInnerVlanId)
            it.source.innerVlanId == (srcVlanId ? srcInnerVlanId : 0)
            it.destination.vlanId == (dstVlanId ? dstVlanId : dstInnerVlanId)
            it.destination.innerVlanId == (dstVlanId ? dstInnerVlanId : 0)
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(qinqFlow.flowId).each { assert it.asExpected }
        verifyAll(northbound.pingFlow(qinqFlow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "The flow allows traffic"
        def traffExam = traffExamProvider.get()
        def examQinQFlow = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(qinqFlow), 1000, 5)
        withPool {
            [examQinQFlow.forward, examQinQFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "Involved switches pass switch validation"
        def involvedSwitchesFlow1 = pathHelper.getInvolvedSwitches(
                pathHelper.convert(northbound.getFlowPath(qinqFlow.flowId))
        )
        involvedSwitchesFlow1.each {
            with(northbound.validateSwitch(it.dpId)) { validation ->
                validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
            }
        }

        when: "Create a vlan flow on the same port as QinQ flow"
        def vlanFlow = flowHelper.randomFlow(swP).tap {
            it.source.portNumber = qinqFlow.source.portNumber
            it.source.vlanId = qinqFlow.source.vlanId + 1
            it.destination.portNumber = qinqFlow.destination.portNumber
            it.destination.vlanId = qinqFlow.destination.vlanId + 1
        }
        flowHelperV2.addFlow(vlanFlow)

        then: "Both existing flows are valid"
        [qinqFlow.flowId, vlanFlow.id].each {
            northbound.validateFlow(it).each { assert it.asExpected }
        }

        and: "Involved switches pass switch validation"
        def involvedSwitchesFlow2 = pathHelper.getInvolvedSwitches(pathHelper.convert(northbound.getFlowPath(vlanFlow.id)))
        def involvedSwitchesforBothFlows = (involvedSwitchesFlow1 + involvedSwitchesFlow2).unique { it.dpId }
        involvedSwitchesforBothFlows.each {
            with(northbound.validateSwitch(it.dpId)) { validation ->
                validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
            }
        }

        and: "Both flows are pingable"
        [qinqFlow.flowId, vlanFlow.id].each {
            verifyAll(northbound.pingFlow(it, new PingInput())) {
                it.forward.pingSuccess
                it.reverse.pingSuccess
            }
        }

        then: "Both flows allow traffic"
        def examSimpleFlow = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(vlanFlow, 1000, 5)
        withPool {
            [examQinQFlow.forward, examQinQFlow.reverse, examSimpleFlow.forward, examSimpleFlow.reverse]
                    .eachParallel { direction ->
                        def resources = traffExam.startExam(direction)
                        direction.setResources(resources)
                        assert traffExam.waitExam(direction).hasTraffic()
                    }
        }

        when: "Update the QinQ flow(outer/inner vlans)"
        def updateResponse = flowHelperV2.updateFlow(qinqFlow.flowId, qinqFlow.tap {
            qinqFlow.source.vlanId = vlanFlow.source.vlanId
            qinqFlow.source.innerVlanId = vlanFlow.destination.vlanId
            qinqFlow.destination.vlanId = vlanFlow.destination.vlanId
            qinqFlow.destination.innerVlanId = vlanFlow.source.vlanId
        })

        then: "Update response contains correct info about innerVlanIds"
        with(updateResponse) {
            it.source.vlanId == vlanFlow.source.vlanId
            it.source.innerVlanId == vlanFlow.destination.vlanId
            it.destination.vlanId == vlanFlow.destination.vlanId
            it.destination.innerVlanId == vlanFlow.source.vlanId
        }

        and: "Flow is really updated"
        with(northbound.getFlow(qinqFlow.flowId)) {
            it.source.vlanId == vlanFlow.source.vlanId
            it.source.innerVlanId == vlanFlow.destination.vlanId
            it.destination.vlanId == vlanFlow.destination.vlanId
            it.destination.innerVlanId == vlanFlow.source.vlanId
        }

        then: "Both existing flows are still valid and pingable"
        [qinqFlow.flowId, vlanFlow.id].each {
            northbound.validateFlow(it).each { assert it.asExpected }
        }

        [qinqFlow.flowId, vlanFlow.id].each {
            verifyAll(northbound.pingFlow(it, new PingInput())) {
                it.forward.pingSuccess
                it.reverse.pingSuccess
            }
        }

        when: "Delete the flows"
        [qinqFlow.flowId, vlanFlow.id].each { flowHelperV2.deleteFlow(it) }

        then: "Flows rules are deleted"
        involvedSwitchesforBothFlows.each { sw ->
            Wrappers.wait(RULES_INSTALLATION_TIME, 1) {
                assert northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.sort() == sw.defaultCookies.sort()
            }
        }

        and: "Shared rule of flow is deleted"
        [swP.src.dpId, swP.dst.dpId].each { swId ->
            assert northbound.getSwitchRules(swId).flowEntries.findAll {
                new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW
            }.empty
        }

        where:
        srcVlanId | srcInnerVlanId | dstVlanId | dstInnerVlanId
        10        | 20             | 30        | 40
        10        | 0              | 0         | 40
        10        | 20             | 0         | 0
    }

    @Tidy
    def "System is able to synchronize switch(flow rules)"() {
        given: "Two switches connected to traffgen and enabled multiTable mode"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue((allTraffGenSwitches.size() > 1), "Unable to find required switches in topology")
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { sw ->
                sw.dpId in allTraffGenSwitches*.dpId && northbound.getSwitchProperties(sw.dpId).multiTable
            }
        } ?: assumeTrue(false, "Not able to find enough switches with traffgens and in multi-table mode")

        and: "A QinQ flow on the given switches"
        def flow = flowHelperV2.randomFlow(swP)
        flow.maximumBandwidth = 100
        flow.source.innerVlanId = 600
        flow.destination.innerVlanId = 700
        flowHelperV2.addFlow(flow)

        when: "Delete all flow rules(ingress/egress/shared) on the src switch"
        northbound.deleteSwitchRules(swP.src.dpId, DeleteRulesAction.DROP_ALL_ADD_DEFAULTS)

        then: "System detects missing rules on the src switch"
        def amountOfServer42Rules = northbound.getSwitchProperties(swP.src.dpId).server42FlowRtt ? 1 : 0
        with(northbound.validateSwitch(swP.src.dpId).rules) {
            it.excess.empty
            it.excessHex.empty
            it.missing.size() == 3 + amountOfServer42Rules //ingress, egress, shared, server42
            it.missingHex.size() == 3 + amountOfServer42Rules
        }

        when: "Synchronize the src switch"
        northbound.synchronizeSwitch(swP.src.dpId, false)

        then: "Missing rules are reinstalled"
        def validateSwResponse = northbound.validateSwitch(swP.src.dpId)
        switchHelper.verifyRuleSectionsAreEmpty(validateSwResponse, ["missing", "excess", "misconfigured"])
        switchHelper.verifyHexRuleSectionsAreEmpty(validateSwResponse, ["missingHex", "excessHex", "misconfiguredHex"])

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }

        and: "The flow allows traffic"
        def traffExam = traffExamProvider.get()
        def examFlow = new FlowTrafficExamBuilder(topology, traffExam)
                .buildBidirectionalExam(flowHelperV2.toV1(flow), 100, 5)
        withPool {
            [examFlow.forward, examFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Ignore("https://github.com/telstra/open-kilda/issues/3858")
    def "System doesn't rebuild flow path to more preferable path while updating innerVlanId"() {
        given: "Two active switches connected to traffgens with two possible paths at least"
        def allTraffgenSwitchIds = topology.activeTraffGens*.switchConnected*.dpId ?:
                assumeTrue(false, "Should be at least two active traffgens connected to switches")
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { sw ->
                sw.dpId in allTraffgenSwitchIds && northbound.getSwitchProperties(sw.dpId).multiTable
            } && it.paths.size() > 2
        } ?: assumeTrue(false, "Not able to find enough switches with traffgens and in multi-table mode")

        and: "A flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.source.innerVlanId = flow.source.vlanId
        flow.destination.innerVlanId = flow.destination.vlanId
        flowHelperV2.addFlow(flow)

        when: "Make the current path less preferable than alternatives"
        def currentPath = pathHelper.convert(northbound.getFlowPath(flow.flowId))
        def alternativePaths = switchPair.paths.findAll { it != currentPath }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentPath) }

        and: "Update the flow: port number and vlanId on the src/dst endpoints"
        def updatedFlow = flow.jacksonCopy().tap {
            it.source.innerVlanId = flow.destination.vlanId
            it.destination.innerVlanId = flow.source.vlanId
        }
        flowHelperV2.updateFlow(flow.flowId, updatedFlow)

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.source.innerVlanId == updatedFlow.source.innerVlanId
            it.destination.innerVlanId == updatedFlow.destination.innerVlanId
        }

        and: "Flow is not rerouted"
        Wrappers.timedLoop(rerouteDelay + WAIT_OFFSET / 2) {
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == currentPath
        }

        and: "System allows traffic on the flow"
        def traffExam = traffExamProvider.get()
        def examFlow = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(
                flowHelperV2.toV1(updatedFlow), 100, 5
        )
        withPool {
            [examFlow.forward, examFlow.reverse].eachParallel { direction ->
                def resources = traffExam.startExam(direction)
                direction.setResources(resources)
                assert traffExam.waitExam(direction).hasTraffic()
            }
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "All involved switches pass switch validation"
        withPool {
            currentPath*.switchId.eachParallel { SwitchId swId ->
                with(northbound.validateSwitch(swId)) { validation ->
                    validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                    validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
                }
            }
        }
        def involvedSwitchesPassSwValidation = true

        cleanup: "Revert system to original state"
        flow && flowHelperV2.deleteFlow(flow.flowId)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        !involvedSwitchesPassSwValidation && currentPath*.switchId.each { SwitchId swId ->
            northbound.synchronizeSwitch(swId, true)
        }
    }
}
