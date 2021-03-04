package org.openkilda.functionaltests.spec.network

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.messaging.error.MessageError
import org.openkilda.model.FlowEncapsulationType

import org.springframework.web.client.HttpClientErrorException

class PathsSpec extends HealthCheckSpecification {

    @Tags(SMOKE)
    def "Get paths between not neighboring switches"() {
        given: "Two active not neighboring switches"
        def switchPair = topologyHelper.getNotNeighboringSwitchPair()

        and: "Create a flow to reduce available bandwidth on some path between these two switches"
        def flow = flowHelperV2.addFlow(flowHelperV2.randomFlow(switchPair))

        when: "Get paths between switches"
        def paths = northbound.getPaths(switchPair.src.dpId, switchPair.dst.dpId, null, null)

        then: "Paths will be sorted by bandwidth (descending order) and then by latency (ascending order)"
        paths.paths.size() > 0

        for (int i = 1; i < paths.paths.size(); i++) {
            def prevPath = paths.paths[i - 1]
            def curPath = paths.paths[i]

            assert prevPath.bandwidth >= curPath.bandwidth

            if (prevPath.bandwidth == curPath.bandwidth) {
                assert prevPath.latency <= curPath.latency
            }
        }

        and: "Cleanup: delete flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Unable to get paths between one switch"() {
        given: "An active switch"
        def sw = topology.getActiveSwitches()[0]

        when: "Try to get paths between one switch"
        northbound.getPaths(sw.dpId, sw.dpId, null, null)

        then: "Get 400 BadRequest error because request is invalid"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
    }

    @Tidy
    def "Unable to get paths between nonexistent switch"() {
        given: "An active switch"
        def sw = topology.getActiveSwitches()[0]

        when: "Try to get paths between real switch and nonexistent switch"
        northbound.getPaths(sw.dpId, NON_EXISTENT_SWITCH_ID, null, null)

        then: "Get 404 NotFound error"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
    }

    @Tags(LOW_PRIORITY)
    def "Unable to get a path for a 'vxlan' flowEncapsulationType when flow when switches do not support it"() {
        given: "Two active not supported 'vxlan' flowEncapsulationType switches"
        def switchPair = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].any { sw ->
                !northbound.getSwitchProperties(sw.dpId).supportedTransitEncapsulation.contains(
                        FlowEncapsulationType.VXLAN.toString().toLowerCase()
                )
            }
        }
        assumeTrue(switchPair as boolean, "Unable to find required switches in topology")

        when: "Try to get a path for a 'vxlan' flowEncapsulationType between the given switches"
        northbound.getPaths(switchPair.src.dpId, switchPair.dst.dpId, FlowEncapsulationType.VXLAN, null)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 404
        // TODO(andriidovhan) fix errorMessage when the 2587 issue is fixed
        def errorDetails = exc.responseBodyAsString.to(MessageError)
        errorDetails.errorMessage == "Switch $switchPair.src.dpId doesn't have links with enough bandwidth"
    }
}
