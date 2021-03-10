package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.Assume.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.functionaltests.helpers.Wrappers.wait
import static org.openkilda.messaging.info.event.IslChangeType.DISCOVERED
import static org.openkilda.messaging.info.event.IslChangeType.FAILED
import static org.openkilda.messaging.info.event.IslChangeType.MOVED
import static org.openkilda.testing.Constants.PATH_INSTALLATION_TIME
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
import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.messaging.payload.flow.DetectConnectedDevicesPayload
import org.openkilda.messaging.payload.flow.FlowEndpointPayload
import org.openkilda.messaging.payload.flow.FlowPayload
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.northbound.dto.v2.flows.FlowEndpointV2
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.testing.model.topology.TopologyDefinition.Isl
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.traffexam.FlowNotApplicableException
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.tools.FlowTrafficExamBuilder

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.See
import spock.lang.Shared
import spock.lang.Unroll

import javax.inject.Provider

@Slf4j
@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/hub-and-spoke/crud")
@Narrative(""""Verify CRUD operations and health of basic vlan flows on different types of switches.
More specific cases like partialUpdate/protected/diverse etc. are covered in separate specifications
""")
class FlowCrudV2Spec extends HealthCheckSpecification {

    @Autowired
    Provider<TraffExamService> traffExamProvider

    @Shared
    def getPortViolationError = { String endpoint, int port, SwitchId swId ->
        "The port $port on the switch '$swId' is occupied by an ISL ($endpoint endpoint collision)."
    }

    @Tags([TOPOLOGY_DEPENDENT])
    @IterationTags([
            @IterationTag(tags = [SMOKE], iterationNameRegex = /vlan /),
            @IterationTag(tags = [SMOKE_SWITCHES], iterationNameRegex =
                    /random vlans|no vlans|single-switch flow with vlans/),
            @IterationTag(tags = [LOW_PRIORITY], iterationNameRegex = /and vlan only on/)
    ])
    @Unroll("Valid #data.description has traffic and no rule discrepancies \
(#flow.source.switchId - #flow.destination.switchId)")
    def "Valid flow has no rule discrepancies"() {
        given: "A flow"
        assumeTrue("There should be at least two active traffgens for test execution",
                topology.activeTraffGens.size() >= 2)
        def traffExam = traffExamProvider.get()
        def allLinksInfoBefore = northbound.getAllLinks().collectEntries { [it.id, it.availableBandwidth] }.sort()
        flowHelperV2.addFlow(flow)
        def path = PathHelper.convert(northbound.getFlowPath(flow.flowId))
        def switches = pathHelper.getInvolvedSwitches(path)
        //for single-flow cases need to add switch manually here, since PathHelper.convert will return an empty path
        if (flow.source.switchId == flow.destination.switchId) {
            switches << topology.activeSwitches.find { it.dpId == flow.source.switchId }
        }

        expect: "No rule discrepancies on every switch of the flow"
        wait(WAIT_OFFSET) { //due to instability on jenkins in multiTable mode + server42
            switches.each { verifySwitchRules(it.dpId) }
        }

        and: "No discrepancies when doing flow validation"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        and: "The flow allows traffic (only applicable flows are checked)"
        def beforeTraffic = new Date()
        def trafficApplicable = true
        try {
            def exam = new FlowTrafficExamBuilder(topology, traffExam).buildBidirectionalExam(toFlowPayload(flow), 1000, 3)
            withPool {
                [exam.forward, exam.reverse].eachParallel { direction ->
                    def resources = traffExam.startExam(direction)
                    direction.setResources(resources)
                    assert traffExam.waitExam(direction).hasTraffic()
                }
            }
        } catch (FlowNotApplicableException e) {
            //flow is not applicable for traff exam. That's fine, just inform
            log.warn(e.message)
            trafficApplicable = false
        }

        and: "Flow writes stats"
        if(trafficApplicable) { statsHelper.verifyFlowWritesStats(flow.flowId, beforeTraffic, true) }

        when: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "The flow is not present in NB"
        !northboundV2.getAllFlows().find { it.flowId == flow.flowId }

        and: "ISL bandwidth is restored"
        Wrappers.wait(WAIT_OFFSET) {
            def allLinksInfoAfter = northbound.getAllLinks().collectEntries { [it.id, it.availableBandwidth] }.sort()
            assert allLinksInfoBefore == allLinksInfoAfter
        }

        and: "No rule discrepancies on every switch of the flow"
        switches.each { sw -> Wrappers.wait(WAIT_OFFSET) { verifySwitchRules(sw.dpId) } }

        where:
        /*Some permutations may be missed, since at current implementation we only take 'direct' possible flows
        * without modifying the costs of ISLs.
        * I.e. if potential test case with transit switch between certain pair of unique switches will require change of
        * costs on ISLs (those switches are neighbors, but we want a path with transit switch between them), then
        * we will not test such case
        */
        data << flowsWithoutTransitSwitch + flowsWithTransitSwitch + singleSwitchFlows
        flow = data.flow as FlowRequestV2
    }

    @Tidy
    @Unroll("Able to create a second flow if #data.description")
    def "Able to create multiple flows on certain combinations of switch-port-vlans"() {
        given: "Two potential flows that should not conflict"
        Tuple2<FlowRequestV2, FlowRequestV2> flows = data.getNotConflictingFlows()

        when: "Create the first flow"
        flowHelperV2.addFlow(flows.first)

        and: "Try creating a second flow with #data.description"
        flowHelperV2.addFlow(flows.second)

        then: "Both flows are successfully created"
        northboundV2.getAllFlows()*.flowId.containsAll(flows*.flowId)

        cleanup: "Delete flows"
        flows.each { flowHelperV2.deleteFlow(it.flowId) }

        where:
        data << [
                [
                        description  : "same switch-port but vlans on src and dst are swapped",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.source.vlanId == it.destination.vlanId && it.destination.vlanId--
                            }
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.source.portNumber = flow1.source.portNumber
                                it.source.vlanId = flow1.destination.vlanId
                                it.destination.portNumber = flow1.destination.portNumber
                                it.destination.vlanId = flow1.source.vlanId
                            }
                            return new Tuple2<FlowRequestV2, FlowRequestV2>(flow1, flow2)
                        }
                ],
                [
                        description  : "same switch-port but vlans on src and dst are different",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch)
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.source.portNumber = flow1.source.portNumber
                                it.source.vlanId = flow1.source.vlanId + 1
                                it.destination.portNumber = flow1.destination.portNumber
                                it.destination.vlanId = flow1.destination.vlanId + 1
                            }
                            return new Tuple2<FlowRequestV2, FlowRequestV2>(flow1, flow2)
                        }
                ],
                [
                        description  : "vlan-port of new src = vlan-port of existing dst (+ different src)",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch)
                            //src for new flow will be on different switch not related to existing flow
                            //thus two flows will have same dst but different src
                            def newSrc = getTopology().activeSwitches.find {
                                ![flow1.source.switchId, flow1.destination.switchId].contains(it.dpId) &&
                                        getTopology().getAllowedPortsForSwitch(it).contains(flow1.destination.portNumber)
                            }
                            def flow2 = getFlowHelperV2().randomFlow(newSrc, dstSwitch).tap {
                                it.source.vlanId = flow1.destination.vlanId
                                it.source.portNumber = flow1.destination.portNumber
                                it.destination.vlanId = flow1.destination.vlanId + 1 //ensure no conflict on dst
                            }
                            return new Tuple2<FlowRequestV2, FlowRequestV2>(flow1, flow2)
                        }
                ],
                [
                        description  : "vlan-port of new dst = vlan-port of existing src (but different switches)",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                def srcPort = getTopology().getAllowedPortsForSwitch(srcSwitch)
                                        .intersect(getTopology().getAllowedPortsForSwitch(dstSwitch))[0]
                                it.source.portNumber = srcPort
                            }
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.destination.vlanId = flow1.source.vlanId
                                it.destination.portNumber = flow1.source.portNumber
                            }
                            return new Tuple2<FlowRequestV2, FlowRequestV2>(flow1, flow2)
                        }
                ],
                [
                        description  : "vlan of new dst = vlan of existing src and port of new dst = port of " +
                                "existing dst",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch)
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.destination.vlanId = flow1.source.vlanId
                                it.destination.portNumber = flow1.destination.portNumber
                            }
                            return new Tuple2<FlowRequestV2, FlowRequestV2>(flow1, flow2)
                        }
                ],
                [
                        description  : "default and tagged flows on the same port on dst switch",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch)
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.destination.vlanId = 0
                                it.destination.portNumber = flow1.destination.portNumber
                            }
                            return new Tuple2<FlowPayload, FlowPayload>(flow1, flow2)
                        }
                ],
                [
                        description  : "default and tagged flows on the same port on src switch",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch)
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.source.vlanId = 0
                                it.source.portNumber = flow1.source.portNumber
                            }
                            return new Tuple2<FlowPayload, FlowPayload>(flow1, flow2)
                        }
                ],
                [
                        description  : "tagged and default flows on the same port on dst switch",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.destination.vlanId = 0
                            }
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.destination.portNumber = flow1.destination.portNumber
                            }
                            return new Tuple2<FlowPayload, FlowPayload>(flow1, flow2)
                        }
                ],
                [
                        description  : "tagged and default flows on the same port on src switch",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.source.vlanId = 0
                            }
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.source.portNumber = flow1.source.portNumber
                            }
                            return new Tuple2<FlowPayload, FlowPayload>(flow1, flow2)
                        }
                ],
                [
                        description  : "default and tagged flows on the same ports on src and dst switches",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch)
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.source.vlanId = 0
                                it.source.portNumber = flow1.source.portNumber
                                it.destination.vlanId = 0
                                it.destination.portNumber = flow1.destination.portNumber
                            }
                            return new Tuple2<FlowPayload, FlowPayload>(flow1, flow2)
                        }
                ],
                [
                        description  : "tagged and default flows on the same ports on src and dst switches",
                        getNotConflictingFlows: {
                            def (Switch srcSwitch, Switch dstSwitch) = getTopology().activeSwitches
                            def flow1 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.source.vlanId = 0
                                it.destination.vlanId = 0
                            }
                            def flow2 = getFlowHelperV2().randomFlow(srcSwitch, dstSwitch).tap {
                                it.source.portNumber = flow1.source.portNumber
                                it.destination.portNumber = flow1.destination.portNumber
                            }
                            return new Tuple2<FlowPayload, FlowPayload>(flow1, flow2)
                        }
                ]
        ]
    }

    @Unroll
    @Tags([TOPOLOGY_DEPENDENT, SMOKE_SWITCHES])
    def "Able to create single switch single port flow with different vlan (#flow.source.switchId)"(
            FlowRequestV2 flow) {
        given: "A flow"
        flowHelperV2.addFlow(flow)

        expect: "No rule discrepancies on the switch"
        verifySwitchRules(flow.source.switchId)

        and: "No discrepancies when doing flow validation"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        when: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        then: "The flow is not present in NB"
        !northboundV2.getAllFlows().find { it.flowId == flow.flowId }

        and: "No rule discrepancies on the switch after delete"
        Wrappers.wait(WAIT_OFFSET) { verifySwitchRules(flow.source.switchId) }

        where:
        flow << getSingleSwitchSinglePortFlows()
    }

    @Tidy
    def "Able to validate flow with zero bandwidth"() {
        given: "A flow with zero bandwidth"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flow.maximumBandwidth = 0

        when: "Create a flow with zero bandwidth"
        flowHelperV2.addFlow(flow)

        then: "Validation of flow with zero bandwidth must be succeed"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Unable to create single-switch flow with the same ports and vlans on both sides"() {
        given: "Potential single-switch flow with the same ports and vlans on both sides"
        def flow = flowHelperV2.singleSwitchSinglePortFlow(topology.activeSwitches.first())
        flow.destination.vlanId = flow.source.vlanId

        when: "Try creating such flow"
        flowHelperV2.addFlow(flow)

        then: "Error is returned, stating a readable reason"
        def error = thrown(HttpClientErrorException)
        error.statusCode == HttpStatus.BAD_REQUEST
        def errorDetails = error.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == "It is not allowed to create one-switch flow for the same ports and vlans"

        cleanup:
        !error && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Unroll("Unable to create flow with #data.conflict")
    def "Unable to create flow with conflicting vlans or flow IDs"() {
        given: "A potential flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)

        and: "Another potential flow with #data.conflict"
        def conflictingFlow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        data.makeFlowsConflicting(flow, conflictingFlow)

        when: "Create the first flow"
        flowHelperV2.addFlow(flow)

        and: "Try creating the second flow which conflicts"
        flowHelperV2.addFlow(conflictingFlow)

        then: "Error is returned, stating a readable reason of conflict"
        def error = thrown(HttpClientErrorException)
        error.statusCode == HttpStatus.CONFLICT
        def errorDetails = error.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == data.getErrorDescription(flow, conflictingFlow)

        cleanup: "Delete the dominant flow"
        flowHelperV2.deleteFlow(flow.flowId)
        !error && flowHelperV2.deleteFlow(conflictingFlow.flowId)

        where:
        data << getConflictingData() + [
                conflict            : "the same flow ID",
                makeFlowsConflicting: { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict ->
                    flowToConflict.flowId = dominantFlow.flowId
                },
                getErrorDescription : { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict ->
                    "Flow $dominantFlow.flowId already exists"
                }
        ]
    }

    @Tidy
    def "A flow cannot be created with asymmetric forward and reverse paths"() {
        given: "Two active neighboring switches with two possible flow paths at least and different number of hops"
        List<List<PathNode>> possibleFlowPaths = []
        int pathNodeCount = 2
        def (Switch srcSwitch, Switch dstSwitch) = topology.getIslsForActiveSwitches().find {
            possibleFlowPaths = database.getPaths(it.srcSwitch.dpId, it.dstSwitch.dpId)*.path.sort { it.size() }
            possibleFlowPaths.size() > 1 && possibleFlowPaths.max { it.size() }.size() > pathNodeCount
        }.collect {
            [it.srcSwitch, it.dstSwitch]
        }.flatten() ?: assumeTrue("No suiting active neighboring switches with two possible flow paths at least and " +
                "different number of hops found", false)

        and: "Make all shorter forward paths not preferable. Shorter reverse paths are still preferable"
        possibleFlowPaths.findAll { it.size() == pathNodeCount }.each {
            pathHelper.getInvolvedIsls(it).each { database.updateIslCost(it, Integer.MAX_VALUE) }
        }

        when: "Create a flow"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        then: "The flow is built through one of the long paths"
        def flowPath = northbound.getFlowPath(flow.flowId)
        !(PathHelper.convert(flowPath) in possibleFlowPaths.findAll { it.size() == pathNodeCount })

        and: "The flow has symmetric forward and reverse paths even though there is a more preferable reverse path"
        def forwardIsls = pathHelper.getInvolvedIsls(PathHelper.convert(flowPath))
        def reverseIsls = pathHelper.getInvolvedIsls(PathHelper.convert(flowPath, "reversePath"))
        forwardIsls.collect { it.reversed }.reverse() == reverseIsls

        cleanup: "Delete the flow and reset costs"
        flow && flowHelperV2.deleteFlow(flow.flowId)
        database.resetCosts()
    }

    @Tidy
    @Unroll
    def "Error is returned if there is no available path to #data.isolatedSwitchType switch"() {
        given: "A switch that has no connection to other switches"
        def isolatedSwitch = topologyHelper.notNeighboringSwitchPair.src
        def flow = data.getFlow(isolatedSwitch)
        topology.getBusyPortsForSwitch(isolatedSwitch).each { port ->
            antiflap.portDown(isolatedSwitch.dpId, port)
        }
        //wait until ISLs are actually got failed
        Wrappers.wait(WAIT_OFFSET) {
            def islData = northbound.getAllLinks()
            topology.getRelatedIsls(isolatedSwitch).each {
                assert islUtils.getIslInfo(islData, it).get().state == IslChangeType.FAILED
            }
        }

        when: "Try building a flow using the isolated switch"
        northboundV2.addFlow(flow)

        then: "Error is returned, stating that there is no path found for such flow"
        def error = thrown(HttpClientErrorException)
        error.statusCode == HttpStatus.NOT_FOUND
        def errorDetails = error.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == "Not enough bandwidth or no path found. Failed to find path with " +
                "requested bandwidth=$flow.maximumBandwidth: Switch ${isolatedSwitch.dpId.toString()} doesn't have " +
                "links with enough bandwidth"

        cleanup: "Restore connection to the isolated switch and reset costs"
        !error && flowHelperV2.deleteFlow(flow.flowId)
        topology.getBusyPortsForSwitch(isolatedSwitch).each { port ->
            antiflap.portUp(isolatedSwitch.dpId, port)
        }
        wait(discoveryInterval + WAIT_OFFSET) {
            northbound.getAllLinks().each { assert it.state == DISCOVERED }
        }
        database.resetCosts()

        where:
        data << [
                [
                        isolatedSwitchType: "source",
                        getFlow           : { Switch theSwitch ->
                            getFlowHelperV2().randomFlow(getTopologyHelper().getAllNotNeighboringSwitchPairs()
                                .collectMany { [it, it.reversed] }.find {
                                    it.src == theSwitch
                            })
                        }
                ],
                [
                        isolatedSwitchType: "destination",
                        getFlow           : { Switch theSwitch ->
                            getFlowHelperV2().randomFlow(getTopologyHelper().getAllNotNeighboringSwitchPairs()
                                .collectMany { [it, it.reversed] }.find {
                                    it.dst == theSwitch
                            })
                        }
                ]
        ]
    }

    def "Removing flow while it is still in progress of being set up should not cause rule discrepancies"() {
        given: "A potential flow"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        def paths = database.getPaths(srcSwitch.dpId, dstSwitch.dpId)*.path
        def switches = pathHelper.getInvolvedSwitches(paths.min { pathHelper.getCost(it) })

        when: "Init creation of a new flow"
        northboundV2.addFlow(flow)

        and: "Immediately remove the flow"
        northboundV2.deleteFlow(flow.flowId)

        then: "System returns error as being unable to remove in progress flow"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.BAD_REQUEST

        and: "Flow is not removed"
        northboundV2.getAllFlows()*.flowId.contains(flow.flowId)

        and: "Flow eventually gets into UP state"
        Wrappers.wait(WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }


        and: "All related switches have no discrepancies in rules"
        switches.each {
            def validation = northbound.validateSwitch(it.dpId)
            validation.verifyMeterSectionsAreEmpty(["excess", "misconfigured", "missing"])
            validation.verifyRuleSectionsAreEmpty(["excess", "missing"])
            def swProps = northbound.getSwitchProperties(it.dpId)
            def amountOfMultiTableRules = swProps.multiTable ? 1 : 0
            def amountOfServer42Rules = (swProps.server42FlowRtt && it.dpId in [srcSwitch.dpId,dstSwitch.dpId]) ? 1 : 0
            if (swProps.multiTable && swProps.server42FlowRtt) {
                if ((flow.destination.getSwitchId() == it.dpId && flow.destination.vlanId) || (flow.source.getSwitchId() == it.dpId && flow.source.vlanId))
                    amountOfServer42Rules += 1
            }
            def amountOfFlowRules = 2 + amountOfMultiTableRules + amountOfServer42Rules
            assert validation.rules.proper.findAll { !new Cookie(it).serviceFlag }.size() == amountOfFlowRules
        }

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Unable to create a flow with invalid encapsulation type"() {
        given: "A flow with invalid encapsulation type"
        def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flow.setEncapsulationType("fake")

        when: "Try to create a flow"
        flowHelperV2.addFlow(flow)

        then: "Flow is not created"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.to(MessageError).errorDescription == "Can not parse arguments of the create flow request"

        cleanup:
        !exc && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
	def "Unable to update a flow with invalid encapsulation type"() {
		given: "A flow with invalid encapsulation type"
		def (Switch srcSwitch, Switch dstSwitch) = topology.activeSwitches
		def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
		flowHelperV2.addFlow(flow)

		when: "Try to update the flow (faked encapsulation type)"
		def flowInfo = northboundV2.getFlow(flow.flowId)
		northboundV2.updateFlow(flowInfo.flowId, flowHelperV2.toRequest(flowInfo.tap { it.encapsulationType = "fake" }))

		then: "Flow is not updated"
		def exc = thrown(HttpClientErrorException)
		exc.rawStatusCode == 400
		exc.responseBodyAsString.to(MessageError).errorDescription \
				== "Can not parse arguments of the update flow request"

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)
	}

    @Tidy
    @Unroll
    def "Unable to create a flow on an isl port in case port is occupied on a #data.switchType switch"() {
        given: "An isl"
        Isl isl = topology.islsForActiveSwitches.find { it.aswitch && it.dstSwitch }
        assumeTrue("Unable to find required isl", isl as boolean)

        when: "Try to create a flow using isl port"
        def flow = flowHelperV2.randomFlow(isl.srcSwitch, isl.dstSwitch)
        flow."$data.switchType".portNumber = isl."$data.port"
        flowHelperV2.addFlow(flow)

        then: "Flow is not created"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == data.errorMessage(isl)
        errorDetails.errorDescription == data.errorDescription(isl)

        cleanup:
        !exc && flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [
                [
                        switchType: "source",
                        port      : "srcPort",
                        errorMessage : { Isl violatedIsl ->
                            "Could not create flow"
                        },
                        errorDescription   : { Isl violatedIsl ->
                            getPortViolationError("source", violatedIsl.srcPort, violatedIsl.srcSwitch.dpId)
                        }
                ],
                [
                        switchType: "destination",
                        port      : "dstPort",
                        errorMessage : { Isl violatedIsl ->
                            "Could not create flow"
                        },
                        errorDescription   : { Isl violatedIsl ->
                            getPortViolationError("destination", violatedIsl.dstPort, violatedIsl.dstSwitch.dpId)
                        }
                ]
        ]
    }

    @Tidy
    def "Unable to create a flow on an isl port when ISL status is FAILED"() {
        given: "An inactive isl with failed state"
        Isl isl = topology.islsForActiveSwitches.find { it.aswitch && it.dstSwitch }
        assumeTrue("Unable to find required isl", isl as boolean)
        antiflap.portDown(isl.srcSwitch.dpId, isl.srcPort)
        islUtils.waitForIslStatus([isl, isl.reversed], FAILED)

        when: "Try to create a flow using ISL src port"
        def flow = flowHelperV2.randomFlow(isl.srcSwitch, isl.dstSwitch)
        flow.source.portNumber = isl.srcPort
        flowHelperV2.addFlow(flow)

        then: "Flow is not created"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == getPortViolationError("source", isl.srcPort, isl.srcSwitch.dpId)

        cleanup: "Restore state of the ISL"
        !exc && flow && flowHelperV2.deleteFlow(flow.flowId)
        antiflap.portUp(isl.srcSwitch.dpId, isl.srcPort)
        islUtils.waitForIslStatus([isl, isl.reversed], DISCOVERED)
        database.resetCosts()
    }

    def "Unable to create a flow on an isl port when ISL status is MOVED"() {
        given: "An inactive isl with moved state"
        Isl isl = topology.islsForActiveSwitches.find { it.aswitch && it.dstSwitch }
        assumeTrue("Unable to find required isl", isl as boolean)
        def notConnectedIsls = topology.notConnectedIsls
        assumeTrue("Unable to find non-connected isl", notConnectedIsls.size() > 0)
        def notConnectedIsl = notConnectedIsls.first()
        def newIsl = islUtils.replug(isl, false, notConnectedIsl, true, false)

        islUtils.waitForIslStatus([isl, isl.reversed], MOVED)
        Wrappers.wait(discoveryExhaustedInterval + WAIT_OFFSET) {
            [newIsl, newIsl.reversed].each { assert northbound.getLink(it).state == DISCOVERED }
        }

        when: "Try to create a flow using ISL src port"
        def flow = flowHelperV2.randomFlow(isl.srcSwitch, isl.dstSwitch)
        flow.source.portNumber = isl.srcPort
        flowHelperV2.addFlow(flow)

        then: "Flow is not created"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == getPortViolationError("source", isl.srcPort, isl.srcSwitch.dpId)

        and: "Cleanup: Restore status of the ISL and delete new created ISL"
        islUtils.replug(newIsl, true, isl, false, false)
        islUtils.waitForIslStatus([isl, isl.reversed], DISCOVERED)
        islUtils.waitForIslStatus([newIsl, newIsl.reversed], MOVED)
        northbound.deleteLink(islUtils.toLinkParameters(newIsl))
        Wrappers.wait(WAIT_OFFSET) { assert !islUtils.getIslInfo(newIsl).isPresent() }
        database.resetCosts()
    }

    def "System doesn't allow to create a one-switch flow on a DEACTIVATED switch"() {
        given: "Disconnected switch"
        def sw = topology.getActiveSwitches()[0]
        def blockData = switchHelper.knockoutSwitch(sw, RW)

        when: "Try to create a one-switch flow on a deactivated switch"
        def flow = flowHelperV2.singleSwitchFlow(sw)
        northboundV2.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == "Source switch $sw.dpId and " +
                "Destination switch $sw.dpId are not connected to the controller"

        and: "Cleanup: Connect switch back to the controller"
        switchHelper.reviveSwitch(sw, blockData, true)
    }

    @Tidy
    def "System allows to CRUD protected flow"() {
        given: "Two active not neighboring switches with two diverse paths at least"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.unique(false) { a, b -> a.intersect(b) == [] ? 1 : 0 }.size() >= 2
        } ?: assumeTrue("No suiting switches found", false)

        when: "Create flow with protected path"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.allocateProtectedPath = true
        flowHelperV2.addFlow(flow)

        then: "Flow is created with protected path"
        def flowPathInfo = northbound.getFlowPath(flow.flowId)
        flowPathInfo.protectedPath
        def flowInfo = northboundV2.getFlow(flow.flowId)
        flowInfo.statusDetails

        and: "Rules for main and protected paths are created"
        Wrappers.wait(WAIT_OFFSET) { flowHelper.verifyRulesOnProtectedFlow(flow.flowId) }

        and: "Validation of flow must be successful"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.discrepancies.empty }

        when: "Update flow: disable protected path(allocateProtectedPath=false)"
        def flowInfoFromDb = database.getFlow(flow.flowId)
        def protectedForwardCookie = flowInfoFromDb.protectedForwardPath.cookie.value
        def protectedReverseCookie = flowInfoFromDb.protectedReversePath.cookie.value
        def protectedFlowPath = northbound.getFlowPath(flow.flowId).protectedPath.forwardPath
        northboundV2.updateFlow(flowInfo.flowId, flowHelperV2.toRequest(flowInfo.tap { it.allocateProtectedPath = false }))

        then: "Protected path is disabled"
        !northbound.getFlowPath(flow.flowId).protectedPath
        !northboundV2.getFlow(flow.flowId).statusDetails

        and: "Rules for protected path are deleted"
        Wrappers.wait(WAIT_OFFSET) {
            protectedFlowPath.each { sw ->
                def rules = northbound.getSwitchRules(sw.switchId).flowEntries.findAll {
                    !new Cookie(it.cookie).serviceFlag
                }
                assert rules.every { it != protectedForwardCookie && it != protectedReverseCookie }
            }
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System allows to set/update description/priority/max-latency for a flow"(){
        given: "Two active neighboring switches"
        def switchPair = topologyHelper.getNeighboringSwitchPair()

        and: "Value for each field"
        def initPriority = 100
        def initMaxLatency = 200
        def initMaxLatencyTier2 = 300
        def initDescription = "test description"
        def initPeriodicPing = true

        when: "Create a flow with predefined values"
        def flow = flowHelperV2.randomFlow(switchPair)
        flow.priority = initPriority
        flow.maxLatency = initMaxLatency
        flow.maxLatencyTier2 = initMaxLatencyTier2
        flow.description = initDescription
        flow.periodicPings = initPeriodicPing
        flowHelperV2.addFlow(flow)

        then: "Flow is created with needed values"
        def flowInfo = northboundV2.getFlow(flow.flowId)
        flowInfo.priority == initPriority
        flowInfo.maxLatency == initMaxLatency
        flowInfo.maxLatencyTier2 == initMaxLatencyTier2
        flowInfo.description == initDescription
        flowInfo.periodicPings == initPeriodicPing

        when: "Update predefined values"
        def newPriority = 200
        def newMaxLatency = 300
        def newMaxLatencyTier2 = 400
        def newDescription = "test description updated"
        def newPeriodicPing = false
        flowInfo.priority = newPriority
        flowInfo.maxLatency = newMaxLatency
        flowInfo.maxLatencyTier2 = newMaxLatencyTier2
        flowInfo.description = newDescription
        flowInfo.periodicPings = newPeriodicPing
        northboundV2.updateFlow(flowInfo.flowId, flowHelperV2.toRequest(flowInfo))

        then: "Flow is updated correctly"
        def newFlowInfo = northboundV2.getFlow(flow.flowId)
        newFlowInfo.priority == newPriority
        newFlowInfo.maxLatency == newMaxLatency
        newFlowInfo.maxLatencyTier2 == newMaxLatencyTier2
        newFlowInfo.description == newDescription
        newFlowInfo.periodicPings == newPeriodicPing

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "System doesn't ignore encapsulationType when flow is created with ignoreBandwidth = true"() {
        given: "Two active switches"
        def swPair = topologyHelper.getNeighboringSwitchPair().find {
            [it.src, it.dst].any {
                !northbound.getSwitchProperties(it.dpId).supportedTransitEncapsulation.contains(
                        FlowEncapsulationType.VXLAN.toString().toLowerCase()
                )
            }
        }

        when: "Create a flow with not supported encapsulation type on the switches"
        def flow = flowHelperV2.randomFlow(swPair)
        flow.ignoreBandwidth = true
        flow.maximumBandwidth = 0
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        flowHelperV2.addFlow(flow)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.NOT_FOUND
        //TODO(andriidovhan) fix errorMessage when the 2587 issue is fixed
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Could not create flow"
        errorDetails.errorDescription == "Not enough bandwidth or no path found. " +
                "Failed to find path with requested bandwidth= ignored: Switch $swPair.src.dpId" +
                " doesn't have links with enough bandwidth"

        cleanup:
        !exc && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Flow status accurately represents the actual state of the flow and flow rules"() {
        when: "Create a flow on a long path"
        def swPair = topologyHelper.switchPairs.first()
        def longPath = swPair.paths.max { it.size() }
        swPair.paths.findAll { it != longPath }.each { pathHelper.makePathMorePreferable(longPath, it) }
        def flow = flowHelperV2.randomFlow(swPair)
        northboundV2.addFlow(flow)

        then: "Flow status is changed to UP only when all rules are actually installed"
        northboundV2.getFlowStatus(flow.flowId).status == FlowState.IN_PROGRESS
        Wrappers.wait(PATH_INSTALLATION_TIME) {
            assert northboundV2.getFlowStatus(flow.flowId).status == FlowState.UP
        }
        def flowInfo = database.getFlow(flow.flowId)
        def flowCookies = [flowInfo.forwardPath.cookie.value, flowInfo.reversePath.cookie.value]
        def switches = pathHelper.getInvolvedSwitches(flow.flowId)
        withPool(switches.size()) {
            switches.eachParallel { Switch sw ->
                assert northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.containsAll(flowCookies)
            }
        }

        when: "Delete flow"
        def deleteResponse = northboundV2.deleteFlow(flow.flowId)

        then: "Flow is actually removed from flows dump only after all rules are removed"
        northboundV2.getFlowStatus(flow.flowId).status == FlowState.IN_PROGRESS
        Wrappers.wait(RULES_DELETION_TIME) {
            assert !northboundV2.getFlowStatus(flow.flowId)
        }
        withPool(switches.size()) {
            switches.eachParallel { Switch sw ->
                assert northbound.getSwitchRules(sw.dpId).flowEntries*.cookie.findAll {
                    def cookie = new Cookie(it)
                    cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES || !cookie.serviceFlag
                }.empty
            }
        }
        northboundV2.getAllFlows().empty

        cleanup: 
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        flow && !deleteResponse && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "Able to update a flow endpoint"() {
        given: "Three active switches"
        def allSwitches = topology.activeSwitches
        assumeTrue("Unable to find three active switches", allSwitches.size() >= 3)
        def srcSwitch = allSwitches[0]
        def dstSwitch = allSwitches[1]

        and: "A vlan flow"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch, false)
        flowHelperV2.addFlow(flow)

        when: "Update the flow: port number and vlan id on the src endpoint"
        def flowInfoFromDb1 = database.getFlow(flow.flowId)
        def updatedFlow = flow.jacksonCopy().tap {
            it.source.portNumber = topology.getAllowedPortsForSwitch(topology.activeSwitches.find {
                it.dpId == flow.source.switchId
            }).last()
            it.source.vlanId = flow.destination.vlanId + 1
        }
        flowHelperV2.updateFlow(flow.flowId, updatedFlow)

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.source.portNumber == updatedFlow.source.portNumber
            it.source.vlanId == updatedFlow.source.vlanId
        }

        and: "Flow history shows actual info into stateBefore and stateAfter sections"
        def flowHistory = northbound.getFlowHistory(flow.flowId)
        with(flowHistory.last().dumps.find { it.type == "stateBefore" }){
            it.sourcePort == flow.source.portNumber
            it.sourceVlan == flow.source.vlanId
        }
        with(flowHistory.last().dumps.find { it.type == "stateAfter" }){
            it.sourcePort == updatedFlow.source.portNumber
            it.sourceVlan == updatedFlow.source.vlanId
        }

        and: "Flow rules are recreated"
        def flowInfoFromDb2 = database.getFlow(flow.flowId)
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            def isMultiTableEnabled = northbound.getSwitchProperties(srcSwitch.dpId).multiTable
            with(northbound.getSwitchRules(srcSwitch.dpId).flowEntries.findAll {
                !new Cookie(it.cookie).serviceFlag
            }) { rules ->
                rules.findAll {
                    it.cookie in [flowInfoFromDb2.forwardPath.cookie.value, flowInfoFromDb2.reversePath.cookie.value]
                }.size() == 2
                def ingressRule = rules.find { it.cookie == flowInfoFromDb2.forwardPath.cookie.value }
                ingressRule.match.inPort == updatedFlow.source.portNumber.toString()
                //vlan is matched in shared rule
                isMultiTableEnabled ? !ingressRule.match.vlanVid : (ingressRule.match.vlanVid == updatedFlow.source
                        .vlanId.toString())
            }
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

        when: "Update the flow: switch id on the dst endpoint"
        def newDstSwitch = allSwitches[2]
        flowHelperV2.updateFlow(flow.flowId, flow.tap {
            it.destination.switchId = newDstSwitch.dpId
            it.destination.portNumber = updatedFlow.source.portNumber
        })

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.destination.switchId == newDstSwitch.dpId
        }

        and: "Flow history shows actual info into stateBefore and stateAfter sections"
        def flowHistory2 = northbound.getFlowHistory(flow.flowId)
        with(flowHistory2.last().dumps.find { it.type == "stateBefore" }){
            it.destinationSwitch == dstSwitch.dpId.toString()
        }
        with(flowHistory2.last().dumps.find { it.type == "stateAfter" }){
            it.destinationSwitch == newDstSwitch.dpId.toString()
        }

        and: "Flow rules are removed from the old dst switch"
        def flowInfoFromDb3 = database.getFlow(flow.flowId)
        Wrappers.wait(RULES_DELETION_TIME) {
            with(northbound.getSwitchRules(dstSwitch.dpId).flowEntries.findAll {
                !new Cookie(it.cookie).serviceFlag
            }) { rules ->
                rules.findAll {
                    it.cookie in [flowInfoFromDb2.forwardPath.cookie.value, flowInfoFromDb2.reversePath.cookie.value]
                }.empty
            }
        }

        and: "Flow rules are installed on the new dst switch"
        Wrappers.wait(RULES_INSTALLATION_TIME) {
            with(northbound.getSwitchRules(newDstSwitch.dpId).flowEntries.findAll {
                !new Cookie(it.cookie).serviceFlag
            }) { rules ->
                rules.findAll {
                    it.cookie in [flowInfoFromDb3.forwardPath.cookie.value, flowInfoFromDb3.reversePath.cookie.value]
                }.size() == 2
            }
        }

        and: "Flow is valid and pingable"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        and: "The new and old dst switches pass switch validation"
        Wrappers.wait(RULES_DELETION_TIME) {
            [dstSwitch, newDstSwitch]*.dpId.each { switchId ->
                with(northbound.validateSwitch(switchId)) { validation ->
                    validation.verifyRuleSectionsAreEmpty(["missing", "excess", "misconfigured"])
                    validation.verifyMeterSectionsAreEmpty(["missing", "excess", "misconfigured"])
                }
            }
        }
        def dstSwitchesAreFine = true

        cleanup:
        flow && flowHelperV2.deleteFlow(flow.flowId)
        !srcSwitchIsFine && northbound.synchronizeSwitch(srcSwitch.dpId, true)
        !dstSwitchesAreFine && dstSwitch && newDstSwitch && [dstSwitch, newDstSwitch]*.dpId.each {
            northbound.synchronizeSwitch(it, true)
        }
    }

    @Tidy
    @Tags(LOW_PRIORITY)
    def "System reroutes flow to more preferable path while updating"() {
        given: "Two active not neighboring switches with two possible paths at least"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find {
            it.paths.size() >= 2
        } ?: assumeTrue("No suiting switches found", false)

        and: "A flow"
        def flow = flowHelperV2.randomFlow(switchPair)
        flowHelperV2.addFlow(flow)

        when: "Make the current path less preferable than alternatives"
        def currentPath = pathHelper.convert(northbound.getFlowPath(flow.flowId))
        def alternativePaths = switchPair.paths.findAll { it != currentPath }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentPath) }

        and: "Update the flow"
        def newFlowDescr = flow.description + " updated"
        flowHelperV2.updateFlow(flow.flowId, flow.tap { it.description = newFlowDescr })

        then: "Flow is rerouted"
        def newCurrentPath
        Wrappers.wait(rerouteDelay + WAIT_OFFSET) {
            newCurrentPath = pathHelper.convert(northbound.getFlowPath(flow.flowId))
            assert newCurrentPath != currentPath
        }

        and: "Flow is updated"
        northboundV2.getFlow(flow.flowId).description == newFlowDescr

        and: "All involved switches pass switch validation"
        def involvedSwitchIds = (currentPath*.switchId + newCurrentPath*.switchId).unique()
        withPool {
            involvedSwitchIds.eachParallel { SwitchId swId ->
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
        !involvedSwitchesPassSwValidation && involvedSwitchIds.each { SwitchId swId ->
            northbound.synchronizeSwitch(swId, true)
        }
    }

    @Tidy
    def "System doesn't rebuild path for a flow to more preferable path while updating portNumber/vlanId"() {
        given: "Two active switches connected to traffgens with two possible paths at least"
        def activeTraffGens = topology.activeTraffGens
        def allTraffgenSwitches = activeTraffGens*.switchConnected ?:
                assumeTrue("Should be at least two active traffgens connected to switches", false)
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find { swP ->
            allTraffgenSwitches*.dpId.contains(swP.src.dpId) && allTraffgenSwitches*.dpId.contains(swP.dst.dpId) &&
                    swP.paths.size() >= 2
        } ?: assumeTrue("Unable to find required switches/paths in topology",false)

        and: "A flow"
        def flow = flowHelperV2.randomFlow(switchPair, false)
        flowHelperV2.addFlow(flow)

        when: "Make the current path less preferable than alternatives"
        def currentPath = pathHelper.convert(northbound.getFlowPath(flow.flowId))
        def alternativePaths = switchPair.paths.findAll { it != currentPath }
        alternativePaths.each { pathHelper.makePathMorePreferable(it, currentPath) }

        and: "Update the flow: vlanId on the src endpoint"
        def updatedFlowSrcEndpoint = flow.jacksonCopy().tap {
            it.source.vlanId = flow.destination.vlanId - 1
        }
        flowHelperV2.updateFlow(flow.flowId, updatedFlowSrcEndpoint)

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.source.portNumber == updatedFlowSrcEndpoint.source.portNumber
            it.source.vlanId == updatedFlowSrcEndpoint.source.vlanId
        }

        and: "Flow path is not rebuild"
        Wrappers.timedLoop(rerouteDelay) {
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == currentPath
        }

        when: "Update the flow: vlanId on the dst endpoint"
        def updatedFlowDstEndpoint = flow.jacksonCopy().tap {
            it.destination.vlanId = flow.source.vlanId - 1
        }
        flowHelperV2.updateFlow(flow.flowId, updatedFlowDstEndpoint)

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.destination.portNumber == updatedFlowDstEndpoint.destination.portNumber
            it.destination.vlanId == updatedFlowDstEndpoint.destination.vlanId
        }

        and: "Flow path is not rebuild"
        Wrappers.timedLoop(rerouteDelay) {
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == currentPath
        }

        then: "Update the flow: port number and vlanId on the src/dst endpoints"
        def updatedFlow = flow.jacksonCopy().tap {
            it.source.portNumber = activeTraffGens.find { it.switchConnected.dpId == switchPair.src.dpId}.switchPort
            it.source.vlanId = updatedFlowDstEndpoint.source.vlanId - 1
            it.destination.portNumber = activeTraffGens.find { it.switchConnected.dpId == switchPair.dst.dpId}.switchPort
            it.destination.vlanId = updatedFlowDstEndpoint.destination.vlanId - 1
        }
        flowHelperV2.updateFlow(flow.flowId, updatedFlow)

        then: "Flow is really updated"
        with(northboundV2.getFlow(flow.flowId)) {
            it.source.portNumber == updatedFlow.source.portNumber
            it.source.vlanId == updatedFlow.source.vlanId
            it.destination.portNumber == updatedFlow.destination.portNumber
            it.destination.vlanId == updatedFlow.destination.vlanId
        }

        and: "Flow path is not rebuild"
        Wrappers.timedLoop(rerouteDelay + WAIT_OFFSET / 2) {
            assert pathHelper.convert(northbound.getFlowPath(flow.flowId)) == currentPath
        }

        and: "Flow is valid"
        northbound.validateFlow(flow.flowId).each { direction -> assert direction.asExpected }

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

    @Shared
    def errorDescription = { String operation, FlowRequestV2 flow, String endpoint, FlowRequestV2 conflictingFlow,
                             String conflictingEndpoint ->
        def message = "Requested flow '$conflictingFlow.flowId' " +
                "conflicts with existing flow '$flow.flowId'. " +
                "Details: requested flow '$conflictingFlow.flowId' $conflictingEndpoint: " +
                "switchId=\"${conflictingFlow."$conflictingEndpoint".switchId}\" " +
                "port=${conflictingFlow."$conflictingEndpoint".portNumber}"
        if (0 < conflictingFlow."$conflictingEndpoint".vlanId) {
            message += " vlanId=${conflictingFlow."$conflictingEndpoint".vlanId}";
        }
        message += ", existing flow '$flow.flowId' $endpoint: " +
                "switchId=\"${flow."$endpoint".switchId}\" " +
                "port=${flow."$endpoint".portNumber}"
        if (0 < flow."$endpoint".vlanId) {
            message += " vlanId=${flow."$endpoint".vlanId}"
        }
        return message
    }

    /**
     * Potential flows with more traffgen-available switches will go first. Then the less tg-available switches there is
     * in the pair the lower score that pair will get.
     * During the subsequent 'unique' call the higher scored pairs will have priority over lower scored ones in case
     * if their uniqueness criteria will be equal.
     */
    @Shared
    def traffgensPrioritized = { SwitchPair switchPair ->
        [switchPair.src, switchPair.dst].count { Switch sw ->
            !topology.activeTraffGens.find { it.switchConnected == sw }
        }
    }

    /**
     * Get list of all unique flows without transit switch (neighboring switches), permute by vlan presence.
     * By unique flows it considers combinations of unique src/dst switch descriptions and OF versions.
     */
    def getFlowsWithoutTransitSwitch() {
        def switchPairs = topologyHelper.getAllNeighboringSwitchPairs().sort(traffgensPrioritized)
                .unique { [it.src, it.dst]*.description.sort() }

        return switchPairs.inject([]) { r, switchPair ->
            r << [
                    description: "flow without transit switch and with random vlans",
                    flow       : flowHelperV2.randomFlow(switchPair)
            ]
            r << [
                    description: "flow without transit switch and without vlans",
                    flow       : flowHelperV2.randomFlow(switchPair).tap {
                        it.source.vlanId = 0
                        it.destination.vlanId = 0
                    }
            ]
            r << [
                    description: "flow without transit switch and vlan only on src",
                    flow       : flowHelperV2.randomFlow(switchPair).tap { it.destination.vlanId = 0 }
            ]
            r
        }
    }

    /**
     * Get list of all unique flows with transit switch (not neighboring switches), permute by vlan presence.
     * By unique flows it considers combinations of unique src/dst switch descriptions and OF versions.
     */
    def getFlowsWithTransitSwitch() {
        def switchPairs = topologyHelper.getAllNotNeighboringSwitchPairs().sort(traffgensPrioritized)
                .unique { [it.src, it.dst]*.description.sort() }

        return switchPairs.inject([]) { r, switchPair ->
            r << [
                    description: "flow with transit switch and random vlans",
                    flow       : flowHelperV2.randomFlow(switchPair)
            ]
            r << [
                    description: "flow with transit switch and no vlans",
                    flow       : flowHelperV2.randomFlow(switchPair).tap {
                        it.source.vlanId = 0
                        it.destination.vlanId = 0
                    }
            ]
            r << [
                    description: "flow with transit switch and vlan only on dst",
                    flow       : flowHelperV2.randomFlow(switchPair).tap { it.source.vlanId = 0 }
            ]
            r
        }
    }

    /**
     * Get list of all unique single-switch flows, permute by vlan presence. By unique flows it considers
     * using all unique switch descriptions and OF versions.
     */
    def getSingleSwitchFlows() {
        topology.getActiveSwitches()
                .sort { sw -> topology.activeTraffGens.findAll { it.switchConnected == sw }.size() }.reverse()
                .unique { it.description }
                .inject([]) { r, sw ->
            r << [
                    description: "single-switch flow with vlans",
                    flow       : flowHelperV2.singleSwitchFlow(sw)
            ]
            r << [
                    description: "single-switch flow without vlans",
                    flow       : flowHelperV2.singleSwitchFlow(sw).tap {
                        it.source.vlanId = 0
                        it.destination.vlanId = 0
                    }
            ]
            r << [
                    description: "single-switch flow with vlan only on dst",
                    flow       : flowHelperV2.singleSwitchFlow(sw).tap {
                        it.source.vlanId = 0
                    }
            ]
            r
        }
    }

    boolean isFlowPingable(FlowRequestV2 flow) {
        if (flow.source.switchId == flow.destination.switchId) {
            return false
        } else if (topologyHelper.findSwitch(flow.source.switchId).ofVersion == "OF_12" ||
                topologyHelper.findSwitch(flow.destination.switchId).ofVersion == "OF_12") {
            return false
        } else {
            return true
        }
    }

    /**
     * Get list of all unique single-switch flows. By unique flows it considers
     * using all unique switch descriptions and OF versions.
     */
    def getSingleSwitchSinglePortFlows() {
        topology.getActiveSwitches()
                .unique { it.description }
                .collect { flowHelperV2.singleSwitchSinglePortFlow(it) }
    }

    Switch findSwitch(SwitchId swId) {
        topology.activeSwitches.find { it.dpId == swId }
    }

    FlowPayload toFlowPayload(FlowRequestV2 flow) {
        FlowEndpointV2 source = flow.source
        FlowEndpointV2 destination = flow.destination

        FlowPayload.builder()
                .id(flow.flowId)
                .source(new FlowEndpointPayload(source.switchId, source.portNumber, source.vlanId,
                        new DetectConnectedDevicesPayload(false, false)))
                .destination(new FlowEndpointPayload(destination.switchId, destination.portNumber, destination.vlanId,
                        new DetectConnectedDevicesPayload(false, false)))
                .maximumBandwidth(flow.maximumBandwidth)
                .ignoreBandwidth(flow.ignoreBandwidth)
                .build()
    }

    def getConflictingData() {
        [
                [
                        conflict            : "the same vlans on the same port on src switch",
                        makeFlowsConflicting: { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict ->
                            flowToConflict.source.portNumber = dominantFlow.source.portNumber
                            flowToConflict.source.vlanId = dominantFlow.source.vlanId
                        },
                        getErrorMessage            : { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict,
                                                       String operation = "create" ->
                            "Could not $operation flow"
                        },
                        getErrorDescription        : { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict,
                                                       String operation = "create" ->
                            errorDescription(operation, dominantFlow, "source", flowToConflict, "source")
                        }
                ],
                [
                        conflict            : "the same vlans on the same port on dst switch",
                        makeFlowsConflicting: { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict ->
                            flowToConflict.destination.portNumber = dominantFlow.destination.portNumber
                            flowToConflict.destination.vlanId = dominantFlow.destination.vlanId
                        },
                        getErrorMessage            : { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict,
                                                       String operation = "create" ->
                            "Could not $operation flow"
                        },
                        getErrorDescription        : { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict,
                                                       String operation = "create" ->
                            errorDescription(operation, dominantFlow, "destination", flowToConflict, "destination")
                        }
                ],
                [
                        conflict            : "no vlan, both flows are on the same port on src switch",
                        makeFlowsConflicting: { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict ->
                            flowToConflict.source.portNumber = dominantFlow.source.portNumber
                            flowToConflict.source.vlanId = 0
                            dominantFlow.source.vlanId = 0
                        },
                        getErrorMessage            : { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict,
                                                       String operation = "create" ->
                            "Could not $operation flow"
                        },
                        getErrorDescription        : { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict,
                                                       String operation = "create" ->
                            errorDescription(operation, dominantFlow, "source", flowToConflict, "source")
                        }
                ],
                [
                        conflict            : "no vlan, both flows are on the same port on dst switch",
                        makeFlowsConflicting: { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict ->
                            flowToConflict.destination.portNumber = dominantFlow.destination.portNumber
                            flowToConflict.destination.vlanId = 0
                            dominantFlow.destination.vlanId = 0
                        },
                        getErrorMessage            : { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict,
                                                       String operation = "create" ->
                            "Could not $operation flow"
                        },
                        getErrorDescription        : { FlowRequestV2 dominantFlow, FlowRequestV2 flowToConflict,
                                                       String operation = "create" ->
                            errorDescription(operation, dominantFlow, "destination", flowToConflict, "destination")
                        }
                ]
        ]
    }
}
