package org.openkilda.functionaltests.spec.resilience

import static groovyx.gpars.GParsPool.withPool
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.BaseSpecification
import org.openkilda.functionaltests.extension.rerun.Rerun
import org.openkilda.functionaltests.helpers.FlowHelperV2
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2
import org.openkilda.testing.service.northbound.NorthboundServiceV2

import groovyx.gpars.group.DefaultPGroup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Ignore
import spock.lang.Narrative

@Narrative("""This spec is aimed to test different race conditions and system behavior in a concurrent
 environment (using v2 APIs)""")
class ContentionV2Spec extends BaseSpecification {

    def "Parallel flow creation requests with the same name creates only 1 flow"() {
        when: "Create the same flow in parallel multiple times"
        def flowsAmount = 20
        def group = new DefaultPGroup(flowsAmount)
        def flow = flowHelperV2.randomFlow(topologyHelper.notNeighboringSwitchPair)
        def tasks = (1..flowsAmount).collect {
            group.task { flowHelperV2.addFlow(flow) }
        }
        tasks*.join()

        then: "One flow is created"
        def okTasks = tasks.findAll { !it.isError() }
        okTasks.size() == 1
        okTasks[0].get().flowId == flow.flowId

        and: "Other requests have received a decline"
        def errors = tasks.findAll { it.isError() }.collect { it.getError() as HttpClientErrorException }
        with(errors) {
            size() == flowsAmount - 1
            it.each { assert it.statusCode == HttpStatus.CONFLICT }
        }

        cleanup: "Remove flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Ignore("https://github.com/telstra/open-kilda/issues/3411")
    def "Parallel flow crud requests properly allocate/deallocate bandwidth resources"() {
        when: "Create multiple flows on the same ISLs concurrently"
        def flowsAmount = 20
        def group = new DefaultPGroup(flowsAmount)
        List<FlowRequestV2> flows = []
        flowsAmount.times { flows << flowHelperV2.randomFlow(topologyHelper.notNeighboringSwitchPair, false , flows) }
        def createTasks = flows.collect { flow ->
            group.task { flowHelperV2.addFlow(flow) }
        }
        createTasks*.join()
        assert createTasks.findAll { it.isError() }.empty
        def relatedIsls = pathHelper.getInvolvedIsls(northbound.getFlowPath(flows[0].flowId))
        //all flows use same isls
        flows[1..-1].each { assert pathHelper.getInvolvedIsls(northbound.getFlowPath(it.flowId)) == relatedIsls }

        then: "Available bandwidth on related isls is reduced based on bandwidth of created flows"
        relatedIsls.each { isl ->
            Wrappers.wait(WAIT_OFFSET) {
                with(northbound.getLink(isl)) {
                    availableBandwidth == maxBandwidth - flows.sum { it.maximumBandwidth }
                }
            }
        }

        when: "Simultaneously remove all the flows"
        def deleteTasks = flows.collect { flow ->
            group.task { flowHelperV2.deleteFlow(flow.flowId) }
        }
        deleteTasks*.get()

        then: "Available bandwidth on all related isls is reverted back to normal"
        Wrappers.wait(3) {
            relatedIsls.each {
                verifyAll(northbound.getLink(it)) {
                    availableBandwidth == maxBandwidth
                    maxBandwidth == speed
                }
            }
        }
    }

    @Ignore("https://github.com/telstra/open-kilda/issues/2563")
//    @Rerun(times = 4)
    //Race condition is being tested here, so need multiple runs to ensure stability
    def "Reroute can be simultaneously performed with sync rules requests and not cause any rule discrepancies"() {
        given: "A flow with reroute potential"
        def switches = topologyHelper.getNotNeighboringSwitchPair()
        def flow = flowHelper.randomFlow(switches)
        flowHelper.addFlow(flow)
        def currentPath = pathHelper.convert(northbound.getFlowPath(flow.id))
        def newPath = switches.paths.find { it != currentPath }
        switches.paths.findAll { it != newPath }.each { pathHelper.makePathMorePreferable(newPath, it) }
        def relatedSwitches = (pathHelper.getInvolvedSwitches(currentPath) +
                pathHelper.getInvolvedSwitches(newPath)).unique()

        when: "Flow reroute is simultaneously requested together with sync rules requests for all related switches"
        withPool {
            def rerouteTask = { northboundV2.rerouteFlow(flow.id) }
            rerouteTask.callAsync()
            sleep(100) //experimentally find out that this ensures better overlapping of DB operations
            relatedSwitches.eachParallel { northbound.synchronizeSwitch(it.dpId, true) } //#2563 to fire at this line
        }

        then: "Flow is Up and path has changed"
        Wrappers.wait(WAIT_OFFSET) {
            assert northboundV2.getFlowStatus(flow.id).status == FlowState.UP
            assert pathHelper.convert(northbound.getFlowPath(flow.id)) == newPath
        }

        and: "Related switches have no rule discrepancies"
        Wrappers.wait(WAIT_OFFSET) {
            relatedSwitches.each {
                def validation = northbound.validateSwitch(it.dpId)
                switchHelper.verifyRuleSectionsAreEmpty(validation, ["missing", "excess"])
                switchHelper.verifyMeterSectionsAreEmpty(validation, ["missing", "misconfigured", "excess"])
            }
        }

        and: "Flow is healthy"
        northbound.validateFlow(flow.id).each { direction -> assert direction.asExpected }

        and: "Cleanup: remove flow and reset costs"
        flowHelperV2.deleteFlow(flow.id)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
    }

}
