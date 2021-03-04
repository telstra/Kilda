package org.openkilda.functionaltests.spec.flows

import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.model.cookie.CookieBase.CookieType.SERVICE_OR_FLOW_SEGMENT
import static org.openkilda.testing.Constants.RULES_INSTALLATION_TIME
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static spock.util.matcher.HamcrestSupport.expect

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.PathComputationStrategy
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.northbound.dto.v1.flows.FlowPatchDto
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.northbound.dto.v2.flows.FlowPatchEndpoint
import org.openkilda.northbound.dto.v2.flows.FlowPatchV2
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.testing.model.topology.TopologyDefinition.Isl

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.Shared
import spock.lang.Unroll

@Narrative("""
Covers PATCH /api/v2/flows/:flowId and PATCH /api/v1/flows/:flowId
This API allows to partially update a flow, i.e. update a flow without specifying a full flow payload. 
Depending on changed fields flow will be either updated+rerouted or just have its values changed in database.
""")
class PartialUpdateSpec extends HealthCheckSpecification {
    def amountOfFlowRules = 2

    @Tidy
    @Unroll
    def "Able to partially update flow '#data.field' without reinstalling its rules"() {
        given: "A flow"
        def swPair = topologyHelper.switchPairs.first()
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        def originalCookies = northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
            def cookie = new Cookie(it.cookie)
            !cookie.serviceFlag || cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES
        }*.cookie

        when: "Request a flow partial update for a #data.field field"
        def updateRequest = new FlowPatchV2().tap { it."$data.field" = data.newValue }
        def response = northboundV2.partialUpdate(flow.flowId, updateRequest)

        then: "Update response reflects the changes"
        response."$data.field" == data.newValue

        and: "Changes actually took place"
        Wrappers.wait(WAIT_OFFSET) {
            def flowInfo = northboundV2.getFlow(flow.flowId)
            assert flowInfo.status == FlowState.UP.toString()
            assert flowInfo."$data.field" == data.newValue
        }

        and: "Flow rules have not been reinstalled"
        northbound.getSwitchRules(swPair.src.dpId).flowEntries*.cookie.containsAll(originalCookies)

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [
                [
                        field   : "maxLatency",
                        newValue: 12345
                ],
                [
                        field   : "maxLatencyTier2",
                        newValue: 23456
                ],
                [
                        field   : "priority",
                        newValue: 654
                ],
                [
                        field   : "periodicPings",
                        newValue: true
                ],
                [
                        field   : "targetPathComputationStrategy",
                        newValue: PathComputationStrategy.LATENCY.toString().toLowerCase()
                ],
                [
                        field   : "pinned",
                        newValue: true
                ],
                //https://github.com/telstra/open-kilda/issues/3896
//                [
//                        field   : "pathComputationStrategy",
//                        newValue: PathComputationStrategy.LATENCY.toString().toLowerCase()
//                ],
                [
                        field   : "description",
                        newValue: "updated"
                ],
                //https://github.com/telstra/open-kilda/issues/3896
//                [
//                        field   : "ignoreBandwidth",
//                        newValue: true
//                ]
        ]
    }

    @Tidy
    @Unroll
    @Tags([LOW_PRIORITY])
    def "Able to partially update flow #data.field without reinstalling its rules(v1)"() {
        given: "A flow"
        def swPair = topologyHelper.switchPairs.first()
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        def originalCookies = northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
            def cookie = new Cookie(it.cookie)
            !cookie.serviceFlag || cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES
        }*.cookie

        when: "Request a flow partial update for a #data.field field"
        def updateRequest = new FlowPatchDto().tap { it."$data.field" = data.newValue }
        def response = northbound.partialUpdate(flow.flowId, updateRequest)

        then: "Update response reflects the changes"
        response."$data.field" == data.newValue

        and: "Changes actually took place"
        northboundV2.getFlow(flow.flowId)."$data.field" == data.newValue

        and: "Flow rules have not been reinstalled"
        northbound.getSwitchRules(swPair.src.dpId).flowEntries*.cookie.containsAll(originalCookies)

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [
                [
                        field   : "maxLatency",
                        newValue: 12345
                ],
                [
                        field   : "priority",
                        newValue: 654
                ],
                [
                        field   : "periodicPings",
                        newValue: true
                ],
                [
                        field   : "targetPathComputationStrategy",
                        newValue: PathComputationStrategy.LATENCY.toString().toLowerCase()
                ]
        ]
    }

    @Tidy
    @Unroll
    def "Able to partially update flow #data.field which causes a reroute"() {
        given: "A flow"
        def swPair = topologyHelper.switchPairs.first()
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        def originalCookies = northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll { !new Cookie(it.cookie).serviceFlag }

        when: "Request a flow partial update for a #data.field field"
        def newValue = data.getNewValue(flow."$data.field")
        def updateRequest = new FlowPatchV2().tap { it."$data.field" = newValue }
        def response = flowHelperV2.partialUpdate(flow.flowId, updateRequest)

        then: "Update response reflects the changes"
        response."$data.field" == newValue

        and: "Changes actually took place"
        northboundV2.getFlow(flow.flowId)."$data.field" == newValue

        and: "Flow rules have been reinstalled"
        //system doesn't reinstall shared rule on reroute action
        def newCookies = northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll { !new Cookie(it.cookie).serviceFlag }
        newCookies.find { new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW } ==
                originalCookies.find { new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW }
        !newCookies.findAll { new Cookie(it.cookie).getType() != CookieType.SHARED_OF_FLOW  }
                .any { it in originalCookies.findAll { new Cookie(it.cookie).getType() != CookieType.SHARED_OF_FLOW } }

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [
                [
                        field   : "maximumBandwidth",
                        getNewValue: { it + 100 }
                ],
                [
                        field   : "allocateProtectedPath",
                        getNewValue: { !it }
                ]
        ]
    }

    @Tidy
    def "Able to turn on diversity feature using partial update"() {
        given: "Two active neighboring switches with two not overlapping paths at least"
        def switchPair = topologyHelper.switchPairs.find {
            it.paths.collect { pathHelper.getInvolvedIsls(it) }.unique { a, b -> a.intersect(b) ? 0 : 1 }.size() >= 2
        } ?: assumeTrue(false, "Can't find a switch pair with 2 not overlapping paths")

        when: "Create 2 not diverse flows going through these switches"
        def flow1 = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow1)
        def flow1Path = PathHelper.convert(northbound.getFlowPath(flow1.flowId))
        def flow2 = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow2)
        def flow2Path = PathHelper.convert(northbound.getFlowPath(flow1.flowId))

        then: "Both flows use the same path"
        flow1Path == flow2Path

        when: "Update second flow to become diverse with the first flow (partial update)"
        flowHelperV2.partialUpdate(flow2.flowId, new FlowPatchV2().tap { diverseFlowId = flow1.flowId })

        then: "Flows use diverse paths"
        pathHelper.getInvolvedIsls(flow1.flowId).intersect(pathHelper.getInvolvedIsls(flow2.flowId)).empty

        cleanup:
        [flow1, flow2].each { flowHelperV2.deleteFlow(it.flowId) }
    }

    @Tidy
    def "Able to do partial update on a single-switch flow"() {
        given: "A single-switch flow"
        def swPair = topologyHelper.singleSwitchPair
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        def originalCookies = northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
            def cookie = new Cookie(it.cookie)
            !cookie.serviceFlag || cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES
        }*.cookie

        when: "Request a flow partial update for a 'priority' field"
        def newPriority = 777
        def updateRequest = new FlowPatchV2().tap { it.priority = newPriority }
        def response = northboundV2.partialUpdate(flow.flowId, updateRequest)

        then: "Update response reflects the changes"
        response.priority == newPriority

        and: "Changes actually took place"
        northboundV2.getFlow(flow.flowId).priority == newPriority

        and: "Flow rules have not been reinstalled"
        northbound.getSwitchRules(swPair.src.dpId).flowEntries*.cookie.containsAll(originalCookies)

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Able to update a flow port and vlan using partial update"() {
        given: "Three active switches"
        def allSwitches = topology.activeSwitches
        assumeTrue(allSwitches.size() >= 3, "Unable to find three active switches")
        def srcSwitch = allSwitches[0]
        def dstSwitch = allSwitches[1]

        and: "A vlan flow"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch, false)
        flowHelperV2.addFlow(flow)

        when: "Update the flow: port number and vlan id on the src endpoint"
        def newPortNumber = topology.getAllowedPortsForSwitch(topology.activeSwitches.find {
            it.dpId == flow.source.switchId
        }).last()
        def newVlanId = flow.destination.vlanId + 1
        flowHelperV2.partialUpdate(flow.flowId, new FlowPatchV2().tap {
            source = new FlowPatchEndpoint().tap {
                portNumber = newPortNumber
                vlanId = newVlanId
            }
        })

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.source.portNumber == newPortNumber
            it.source.vlanId == newVlanId
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "The src switch passes switch validation"
        with(northbound.validateSwitch(srcSwitch.dpId)) { validation ->
            validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
            validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }
        def srcSwitchIsFine = true

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        !srcSwitchIsFine && northbound.synchronizeSwitch(srcSwitch.dpId, true)
    }

    @Tidy
    def "Able to update a flow endpoint using partial update"() {
        given: "Three active switches"
        def allSwitches = topology.activeSwitches
        assumeTrue(allSwitches.size() >= 3, "Unable to find three active switches")
        def srcSwitch = allSwitches[0]
        def dstSwitch = allSwitches[1]
        def newDstSwitch = allSwitches[2]

        and: "A vlan flow"
        //pick a port that is free both on current dst switch and on future updated dst switch
        def port = topology.getAllowedPortsForSwitch(dstSwitch)
                .intersect(topology.getAllowedPortsForSwitch(newDstSwitch)).first()
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch, false).tap {
            it.destination.portNumber = port
        }
        flowHelperV2.addFlow(flow)

        when: "Update the flow: switch id on the dst endpoint"
        flowHelperV2.partialUpdate(flow.flowId, new FlowPatchV2().tap {
            destination = new FlowPatchEndpoint().tap {
                switchId = newDstSwitch.dpId
            }
        })

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.destination.switchId == newDstSwitch.dpId
        }

        and: "Flow rules are installed on the new dst switch"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            assert northbound.getSwitchRules(newDstSwitch.dpId).flowEntries.findAll { def cookie = new Cookie(it.cookie)
                !cookie.serviceFlag && cookie.type == SERVICE_OR_FLOW_SEGMENT
            }.size() == amountOfFlowRules
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        //issue with excess rules: https://github.com/telstra/open-kilda/issues/4055
//        and: "The new and old dst switches pass switch validation"
//        Wrappers.wait(RULES_DELETION_TIME) {
//            [dstSwitch, newDstSwitch]*.dpId.each { switchId ->
//                with(northbound.validateSwitch(switchId)) { validation ->
//                    validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
//                    validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
//                }
//            }
//        }
//        def dstSwitchesAreFine = true
        def dstSwitchesAreFine = false

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        !dstSwitchesAreFine && dstSwitch && newDstSwitch && [dstSwitch, newDstSwitch]*.dpId.each {
            northbound.synchronizeSwitch(it, true)
        }
    }

    @Tidy
    @Tags([LOW_PRIORITY])
    def "Partial update with empty body does not actually update flow in any way(v1)"() {
        given: "A flow"
        def swPair = topologyHelper.switchPairs.first()
        def flow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(flow)
        def originalCookies = northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
            def cookie = new Cookie(it.cookie)
            !cookie.serviceFlag || cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES
        }*.cookie

        when: "Request a flow partial update without specifying any fields"
        def flowBeforeUpdate = northboundV2.getFlow(flow.flowId)
        northbound.partialUpdate(flow.flowId, new FlowPatchDto())

        then: "Flow is left intact"
        expect northboundV2.getFlow(flow.flowId), sameBeanAs(flowBeforeUpdate)
                .ignoring("lastUpdated")
                .ignoring("diverseWith")

        and: "Flow rules have not been reinstalled"
        northbound.getSwitchRules(swPair.src.dpId).flowEntries*.cookie.containsAll(originalCookies)

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    def "Partial update with empty body does not actually update flow in any way"() {
        given: "A flow"
        def swPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            it.paths.collect { pathHelper.getInvolvedIsls(it) }.unique { a, b -> a.intersect(b) ? 0 : 1 }.size() > 1
        } ?: assumeTrue(false, "Need at least 2 non-overlapping paths for diverse flow")
        def helperFlow = flowHelperV2.randomFlow(swPair)
        flowHelperV2.addFlow(helperFlow)
        def flow = flowHelperV2.randomFlow(swPair).tap {
            pinned = true
            periodicPings = true
            diverseFlowId = helperFlow.flowId
        }
        flowHelperV2.addFlow(flow)
        def originalCookies = northbound.getSwitchRules(swPair.src.dpId).flowEntries.findAll {
            def cookie = new Cookie(it.cookie)
            !cookie.serviceFlag || cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES
        }*.cookie

        when: "Request a flow partial update without specifying any fields"
        def flowBeforeUpdate = northboundV2.getFlow(flow.flowId)
        northboundV2.partialUpdate(flow.flowId, new FlowPatchV2())

        then: "Flow is left intact"
        expect northboundV2.getFlow(flow.flowId), sameBeanAs(flowBeforeUpdate)
                .ignoring("lastUpdated")

        and: "Flow rules have not been reinstalled"
        northbound.getSwitchRules(swPair.src.dpId).flowEntries*.cookie.containsAll(originalCookies)

        cleanup: "Remove the flow"
        [flow, helperFlow].each { flowHelperV2.deleteFlow(it.flowId) }
    }

    @Tidy
    @Unroll
    def "Unable to partial update a flow in case new port is an isl port on a #data.switchType switch"() {
        given: "An isl"
        Isl isl = topology.islsForActiveSwitches.find { it.aswitch && it.dstSwitch }
        assumeTrue(isl as boolean, "Unable to find required isl")

        and: "A flow"
        def flow = flowHelperV2.randomFlow(isl.srcSwitch, isl.dstSwitch)
        flowHelperV2.addFlow(flow)

        when: "Try to edit port to isl port"
        northboundV2.partialUpdate(flow.flowId, new FlowPatchV2().tap {
            it."$data.switchType" = new FlowPatchEndpoint().tap { it.portNumber = isl."$data.port" }
        })

        then: "Error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        def error = exc.responseBodyAsString.to(MessageError)
        error.errorMessage == "Could not update flow"
        error.errorDescription == data.message(isl)

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [
                [
                        switchType: "source",
                        port      : "srcPort",
                        message   : { Isl violatedIsl ->
                            getPortViolationError("source", violatedIsl.srcPort, violatedIsl.srcSwitch.dpId)
                        }
                ],
                [
                        switchType: "destination",
                        port      : "dstPort",
                        message   : { Isl violatedIsl ->
                            getPortViolationError("destination", violatedIsl.dstPort, violatedIsl.dstSwitch.dpId)
                        }
                ]
        ]
    }

    @Tidy
    @Unroll("Unable to partial update flow (#data.conflict)")
    def "Unable to partial update flow when there are conflicting vlans"() {
        given: "Two potential flows"
        def swPair = topologyHelper.switchPairs.first()
        def flow1 = flowHelperV2.randomFlow(swPair, false)
        def flow2 = flowHelperV2.randomFlow(swPair, false, [flow1])
        FlowPatchV2 patch = data.getPatch(flow1)

        when: "Create two flows"
        flowHelperV2.addFlow(flow1)
        flowHelperV2.addFlow(flow2)

        and: "Try updating the second flow which should conflict with the first one (partial update)"
        northboundV2.partialUpdate(flow2.flowId, patch)

        then: "Error is returned, stating a readable reason of conflict"
        def error = thrown(HttpClientErrorException)
        error.statusCode == HttpStatus.CONFLICT
        with(error.responseBodyAsString.to(MessageError)) {
            errorMessage == "Could not update flow"
            errorDescription == data.getError(flow1, flow2, patch)
        }

        cleanup:
        [flow1, flow2].each { flowHelperV2.deleteFlow(it.flowId) }

        where:
        data <<[
                [
                        conflict: "the same vlans on the same port on src switch",
                        getPatch: { FlowRequestV2 dominantFlow ->
                            new FlowPatchV2().tap { source = new FlowPatchEndpoint().tap {
                                    portNumber = dominantFlow.source.portNumber
                                    vlanId = dominantFlow.source.vlanId
                                }
                            }
                        },
                        getError: { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict, FlowPatchV2 patchDto ->
                            errorDescription(dominantFlow, "source", flowToConflict, "source", patchDto)
                        }
                ],
                [
                        conflict: "the same vlans on the same port on dst switch",
                        getPatch: { FlowRequestV2 dominantFlow ->
                            new FlowPatchV2().tap { destination = new FlowPatchEndpoint().tap {
                                    portNumber = dominantFlow.destination.portNumber
                                    vlanId = dominantFlow.destination.vlanId
                                }
                            }
                        },
                        getError: { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict, FlowPatchV2 patchDto ->
                            errorDescription(dominantFlow, "destination", flowToConflict, "destination", patchDto)
                        }
                ],
                [
                        conflict: "no vlan, both flows are on the same port on src switch",
                        getPatch: { FlowRequestV2 dominantFlow ->
                            dominantFlow.source.vlanId = 0
                            new FlowPatchV2().tap { source = new FlowPatchEndpoint().tap {
                                    portNumber = dominantFlow.source.portNumber
                                    vlanId = dominantFlow.source.vlanId
                                }
                            }
                        },
                        getError: { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict, FlowPatchV2 patchDto ->
                            errorDescription(dominantFlow, "source", flowToConflict, "source", patchDto)
                        }
                ],
                [
                        conflict: "no vlan, both flows are on the same port on dst switch",
                        getPatch: { FlowRequestV2 dominantFlow ->
                            dominantFlow.destination.vlanId = 0
                            new FlowPatchV2().tap { destination = new FlowPatchEndpoint().tap {
                                    portNumber = dominantFlow.destination.portNumber
                                    vlanId = dominantFlow.destination.vlanId
                                }
                            }
                        },
                        getError: { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict, FlowPatchV2 patchDto ->
                            errorDescription(dominantFlow, "destination", flowToConflict, "destination", patchDto)
                        }
                ]
        ]
    }

    @Tidy
    @Tags(HARDWARE)
    def "Able to update flow encapsulationType using partial update"() {
        given: "A flow with a 'transit_vlan' encapsulation"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { swP ->
            [swP.src, swP.dst].every { sw ->
                northbound.getSwitchProperties(sw.dpId).supportedTransitEncapsulation
                        .contains(FlowEncapsulationType.VXLAN.toString().toLowerCase())
            }
        }
        assumeTrue(switchPair as boolean, "Unable to find required switches in topology")

        def flow = flowHelperV2.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.TRANSIT_VLAN
        flowHelperV2.addFlow(flow)

        def originalCookies = northbound.getSwitchRules(switchPair.src.dpId).flowEntries.findAll {
            def cookie = new Cookie(it.cookie)
            !cookie.serviceFlag && cookie.type == SERVICE_OR_FLOW_SEGMENT
        }

        when: "Request a flow partial update for an encapsulationType field(vxlan)"
        def newEncapsulationTypeValue = FlowEncapsulationType.VXLAN.toString().toLowerCase()
        def updateRequest = new FlowPatchV2().tap { it.encapsulationType = newEncapsulationTypeValue }
        def response = flowHelperV2.partialUpdate(flow.flowId, updateRequest)

        then: "Update response reflects the changes"
        response.encapsulationType == newEncapsulationTypeValue

        and: "Changes actually took place"
        northboundV2.getFlow(flow.flowId).encapsulationType == newEncapsulationTypeValue

        and: "Flow rules have been reinstalled"
        !northbound.getSwitchRules(switchPair.src.dpId).flowEntries.findAll { def cookie = new Cookie(it.cookie)
            !cookie.serviceFlag && cookie.type == SERVICE_OR_FLOW_SEGMENT
        }.any { it in originalCookies }

        cleanup: "Remove the flow"
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Able to update a flow port and vlan for a single-switch flow using partial update"() {
        given: "An active single-switch flow (different ports)"
        def sw = topology.activeSwitches.first()
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flowHelperV2.addFlow(flow)

        when: "Update the flow: port number and vlanId on the src endpoint"
        def flowInfoFromDb = database.getFlow(flow.flowId)
        def ingressCookie = flowInfoFromDb.forwardPath.cookie.value
        def egressCookie = flowInfoFromDb.reversePath.cookie.value
        def newPortNumber = (topology.getAllowedPortsForSwitch(topology.activeSwitches.find {
            it.dpId == flow.source.switchId
        }) - flow.source.portNumber - flow.destination.portNumber).last()
        def newVlanId = flow.source.vlanId - 1
        flowHelperV2.partialUpdate(flow.flowId, new FlowPatchV2().tap {
            source = new FlowPatchEndpoint().tap {
                portNumber = newPortNumber
                vlanId = newVlanId
            }
        })

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.source.portNumber == newPortNumber
            it.source.vlanId == newVlanId
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "The ingress/egress rules are really updated"
        Wrappers.wait(RULES_INSTALLATION_TIME + WAIT_OFFSET) {
            def swRules = northbound.getSwitchRules(flow.source.switchId).flowEntries
            with(swRules.find { it.cookie == ingressCookie }) {
                it.match.inPort == newPortNumber.toString()
                it.instructions.applyActions.flowOutput == flow.destination.portNumber.toString()
                it.instructions.applyActions.setFieldActions*.fieldValue.contains(flow.destination.vlanId.toString())
            }
            with(swRules.find { it.cookie == egressCookie }) {
                it.match.inPort == flow.destination.portNumber.toString()
                it.instructions.applyActions.flowOutput == newPortNumber.toString()
                it.instructions.applyActions.setFieldActions*.fieldValue.contains(newVlanId.toString())
            }
        }

        and: "The switch passes switch validation"
        with(northbound.validateSwitch(flow.source.switchId)) { validation ->
            validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
            validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }
        def switchIsFine = true

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        !switchIsFine && northbound.synchronizeSwitch(flow.source.switchId, true)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Able to update a flow port and vlan for a single-switch single-port flow using partial update"() {
        given: "An active single-switch single-port flow"
        def sw = topology.activeSwitches.first()
        def flow = flowHelperV2.singleSwitchSinglePortFlow(sw)
        flowHelperV2.addFlow(flow)

        when: "Update the flow: new port number on src+dst and new vlanId on the src endpoint"
        def flowInfoFromDb = database.getFlow(flow.flowId)
        def ingressCookie = flowInfoFromDb.forwardPath.cookie.value
        def egressCookie = flowInfoFromDb.reversePath.cookie.value
        def newPortNumber = (topology.getAllowedPortsForSwitch(topology.activeSwitches.find {
            it.dpId == flow.source.switchId
        }) - flow.source.portNumber).last()
        def newVlanId = flow.source.vlanId - 1
        flowHelperV2.partialUpdate(flow.flowId, new FlowPatchV2().tap {
            source = new FlowPatchEndpoint().tap {
                portNumber = newPortNumber
                vlanId = newVlanId
            }
            destination = new FlowPatchEndpoint().tap {
                portNumber = newPortNumber
            }
        })

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.source.portNumber == newPortNumber
            it.destination.portNumber == newPortNumber
            it.source.vlanId == newVlanId
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "The ingress/egress rules are really updated"
        Wrappers.wait(RULES_INSTALLATION_TIME + WAIT_OFFSET) {
            def swRules = northbound.getSwitchRules(flow.source.switchId).flowEntries
            with(swRules.find { it.cookie == ingressCookie }) {
                it.match.inPort == newPortNumber.toString()
                it.instructions.applyActions.flowOutput == "in_port"
                it.instructions.applyActions.setFieldActions*.fieldValue.contains(flow.destination.vlanId.toString())
            }
            with(swRules.find { it.cookie == egressCookie }) {
                it.match.inPort == newPortNumber.toString()
                it.instructions.applyActions.flowOutput == "in_port"
                it.instructions.applyActions.setFieldActions*.fieldValue.contains(newVlanId.toString())
            }
        }

        and: "The switch passes switch validation"
        with(northbound.validateSwitch(flow.source.switchId)) { validation ->
            validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
            validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
        }
        def switchIsFine = true

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        !switchIsFine && northbound.synchronizeSwitch(flow.source.switchId, true)
    }

    @Shared
    def getPortViolationError = { String endpoint, int port, SwitchId swId ->
        "The port $port on the switch '$swId' is occupied by an ISL ($endpoint endpoint collision)."
    }

    @Shared
    def errorDescription = { FlowRequestV2 flow, String endpoint, FlowRequestV2 conflictingFlow,
            String conflictingEndpoint, FlowPatchV2 patch ->
        def requestedFlow = jacksonMerge(conflictingFlow, patch)
        "Requested flow '$conflictingFlow.flowId' " +
                "conflicts with existing flow '$flow.flowId'. " +
                "Details: requested flow '$requestedFlow.flowId' $conflictingEndpoint: " +
                "switchId=\"${requestedFlow."$conflictingEndpoint".switchId}\" " +
                "port=${requestedFlow."$conflictingEndpoint".portNumber}" +
                "${requestedFlow."$conflictingEndpoint".vlanId ? " vlanId=" + requestedFlow."$conflictingEndpoint".vlanId : ""}, " +
                "existing flow '$flow.flowId' $endpoint: " +
                "switchId=\"${flow."$endpoint".switchId}\" " +
                "port=${flow."$endpoint".portNumber}" +
                "${flow."$endpoint".vlanId ? " vlanId=" + flow."$endpoint".vlanId : ""}"
    }

    /**
    * Merge obj2 into obj1 using Jackson serialisation.
    * @return new object as a result of merge
     */
    def jacksonMerge(Object obj1, Object obj2) {
        def mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        def map1 = mapper.convertValue(obj1, Map)
        def map2 = mapper.convertValue(obj2, Map)
        return mapper.convertValue(merge(map1, map2), obj1.class)
    }

    Map merge(Map... maps) {
        Map result
        if (maps.length == 0) {
            result = [:]
        } else if (maps.length == 1) {
            result = maps[0]
        } else {
            result = [:]
            maps.each { map ->
                map.each { k, v ->
                    result[k] = result[k] instanceof Map ? merge(result[k], v) : v
                }
            }
        }

        result
    }
}
