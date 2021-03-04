package org.openkilda.functionaltests.spec.flows

import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.LOW_PRIORITY
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.testing.Constants.STATS_LOGGING_TIMEOUT
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static spock.util.matcher.HamcrestSupport.expect

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.PathNode
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.northbound.dto.v1.flows.PingInput
import org.openkilda.northbound.dto.v1.flows.PingOutput.PingOutputBuilder
import org.openkilda.northbound.dto.v1.flows.UniFlowPingOutput
import org.openkilda.testing.Constants.DefaultRule
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.See
import spock.lang.Unroll

@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/flow-ping")
@Narrative("""
This spec tests all the functionality related to flow pings. 
Flow ping feature sends a 'ping' packet at the one end of the flow, expecting that this packet will 
be delivered at the other end. 'Pings' the flow in both directions(forward and reverse).
""")
class FlowPingSpec extends HealthCheckSpecification {

    @Value('${opentsdb.metric.prefix}')
    String metricPrefix
    @Value('${flow.ping.interval}')
    int pingInterval

    @Tidy
    @Unroll("Able to ping a flow with vlan between switches #srcSwitch.dpId - #dstSwitch.dpId")
    @Tags([TOPOLOGY_DEPENDENT])
    def "Able to ping a flow with vlan"(Switch srcSwitch, Switch dstSwitch) {
        given: "A flow with random vlan"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)

        when: "Ping the flow"
        def beforePingTime = new Date()
        def unicastCounterBefore = northbound.getSwitchRules(srcSwitch.dpId).flowEntries.find {
            it.cookie == DefaultRule.VERIFICATION_UNICAST_RULE.cookie
        }.byteCount
        def response = northbound.pingFlow(flow.flowId, new PingInput())

        then: "Ping is successful"
        response.forward.pingSuccess
        response.reverse.pingSuccess

        and: "No errors"
        !response.error
        !response.forward.error
        !response.reverse.error

        // 0 latency issue https://github.com/telstra/open-kilda/issues/3312
        // and: "Latency is present in response"
        // response.forward.latency
        // response.reverse.latency

        and: "Unicast rule packet count is increased and logged to otsdb"
        def statsData = null
        Wrappers.wait(STATS_LOGGING_TIMEOUT, 2) {
            statsData = otsdb.query(beforePingTime, metricPrefix + "switch.flow.system.bytes",
                    [switchid : srcSwitch.dpId.toOtsdFormat(),
                     cookieHex: DefaultRule.VERIFICATION_UNICAST_RULE.toHexString()]).dps
            assert statsData && !statsData.empty
        }
        statsData.values().last().toLong() > unicastCounterBefore

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        [srcSwitch, dstSwitch] << ofSwitchCombinations
    }

    @Tidy
    @Unroll("Able to ping a flow with no vlan between switches #srcSwitch.dpId - #dstSwitch.dpId")
    @Tags([TOPOLOGY_DEPENDENT])
    def "Able to ping a flow with no vlan"(Switch srcSwitch, Switch dstSwitch) {
        given: "A flow with no vlan"
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flow.source.vlanId = 0
        flow.destination.vlanId = 0
        flowHelperV2.addFlow(flow)

        when: "Ping the flow"
        def response = northbound.pingFlow(flow.flowId, new PingInput())

        then: "Ping is successful"
        response.forward.pingSuccess
        response.reverse.pingSuccess

        and: "No errors"
        !response.error
        !response.forward.error
        !response.reverse.error

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        [srcSwitch, dstSwitch] << ofSwitchCombinations
    }

    @Tidy
    @Unroll("Flow ping can detect a broken #description")
    @IterationTag(tags = [SMOKE], iterationNameRegex = /forward path/)
    def "Flow ping can detect a broken path for a vlan flow"() {
        given: "A flow with at least 1 a-switch link"
        def switches = topology.activeSwitches.findAll { !it.centec && it.ofVersion != "OF_12" }
        List<List<PathNode>> allPaths = []
        List<PathNode> aswitchPath
        //select src and dst switches that have an a-switch path
        def (Switch srcSwitch, Switch dstSwitch) = [switches, switches].combinations()
                .findAll { src, dst -> src != dst }.find { Switch src, Switch dst ->
            allPaths = database.getPaths(src.dpId, dst.dpId)*.path
            aswitchPath = allPaths.find { pathHelper.getInvolvedIsls(it).find { it.aswitch } }
            aswitchPath
        } ?: assumeTrue(false, "Wasn't able to find suitable switch pair")
        //make a-switch path the most preferable
        allPaths.findAll { it != aswitchPath }.each { pathHelper.makePathMorePreferable(aswitchPath, it) }
        //build a flow
        def flow = flowHelperV2.randomFlow(srcSwitch, dstSwitch)
        flowHelperV2.addFlow(flow)
        expectedPingResult.flowId = flow.flowId
        assert aswitchPath == pathHelper.convert(northbound.getFlowPath(flow.flowId))

        when: "Break the flow by removing rules from a-switch"
        def islToBreak = pathHelper.getInvolvedIsls(aswitchPath).find { it.aswitch }
        def rulesToRemove = []
        data.breakForward && rulesToRemove << islToBreak.aswitch
        data.breakReverse && rulesToRemove << islToBreak.aswitch.reversed
        lockKeeper.removeFlows(rulesToRemove)

        and: "Ping the flow"
        def response = northbound.pingFlow(flow.flowId, data.pingInput)

        then: "Ping response properly shows that certain direction is unpingable"
        expect response, sameBeanAs(expectedPingResult)
                .ignoring("forward.latency").ignoring("reverse.latency")

        cleanup: "Restore rules, costs and remove the flow"
        flow && flowHelperV2.deleteFlow(flow.flowId)
        rulesToRemove && lockKeeper.addFlows(rulesToRemove)
        northbound.deleteLinkProps(northbound.getAllLinkProps())
        Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
            assert islUtils.getIslInfo(islToBreak).get().state == IslChangeType.DISCOVERED
        }
        database.resetCosts()

        where:
        data << [
                [
                        breakForward: true,
                        breakReverse: false,
                        pingInput   : new PingInput()
                ],
                [
                        breakForward: false,
                        breakReverse: true,
                        pingInput   : new PingInput()
                ],
                [
                        breakForward: true,
                        breakReverse: true,
                        pingInput   : new PingInput()
                ],
                [
                        breakForward: true,
                        breakReverse: false,
                        pingInput   : new PingInput((getDiscoveryInterval() + 1) * 1000)
                ],
                [
                        breakForward: false,
                        breakReverse: true,
                        pingInput   : new PingInput((getDiscoveryInterval() + 1) * 1000)
                ],
                [
                        breakForward: true,
                        breakReverse: true,
                        pingInput   : new PingInput((getDiscoveryInterval() + 1) * 1000)
                ]
        ]
        description = "${data.breakForward ? "forward" : ""}${data.breakForward && data.breakReverse ? " and " : ""}" +
                "${data.breakReverse ? "reverse" : ""} path with ${data.pingInput.timeoutMillis}ms timeout"

        expectedPingResult = new PingOutputBuilder().forward(
                new UniFlowPingOutput(
                        pingSuccess: !data.breakForward,
                        error: data.breakForward ? "No ping for reasonable time" : null)).reverse(
                new UniFlowPingOutput(
                        pingSuccess: !data.breakReverse,
                        error: data.breakReverse ? "No ping for reasonable time" : null))
                .error(null)
                .build()
    }

    @Tidy
    def "Unable to ping a single-switch flow"() {
        given: "A single-switch flow"
        def sw = topology.activeSwitches.find { !it.centec && it.ofVersion != "OF_12" }
        assert sw
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flowHelperV2.addFlow(flow)

        when: "Ping the flow"
        def response = northbound.pingFlow(flow.flowId, new PingInput())

        then: "Error received"
        !response.forward
        !response.reverse
        response.error == "Flow ${flow.flowId} should not be one switch flow"

        cleanup: "Remove the flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Verify error if try to ping with wrong flowId"() {
        when: "Send ping request with non-existing flowId"
        def wrongFlowId = "nonexistent"
        def response = northbound.pingFlow(wrongFlowId, new PingInput())

        then: "Receive error response"
        verifyAll(response) {
            flowId == wrongFlowId
            !forward
            !reverse
            error == "Flow $wrongFlowId does not exist"
        }
    }

    @Tidy
    @Tags(HARDWARE)
    def "Flow ping can detect a broken path for a vxlan flow on an intermediate switch"() {
        given: "A vxlan flow with intermediate switch(es)"
        def switchPair = topologyHelper.getAllNotNeighboringSwitchPairs().find { swP ->
            swP.paths.findAll { path ->
                pathHelper.getInvolvedSwitches(path).every {
                    northbound.getSwitchProperties(it.dpId).supportedTransitEncapsulation.contains(
                            FlowEncapsulationType.VXLAN.toString().toLowerCase()
                    )
                }
            }.size() >= 1
        } ?: assumeTrue(false, "Unable to find required switches in topology")

        def flow = flowHelperV2.randomFlow(switchPair)
        flow.encapsulationType = FlowEncapsulationType.VXLAN
        flowHelperV2.addFlow(flow)
        //make sure that flow is pingable
        with(northbound.pingFlow(flow.flowId, new PingInput())) {
            it.forward.pingSuccess
            it.reverse.pingSuccess
        }

        when: "Break the flow by removing flow rules from the intermediate switch"
        def intermediateSwId = pathHelper.getInvolvedSwitches(flow.flowId)[1].dpId
        def rulesToDelete = northbound.getSwitchRules(intermediateSwId).flowEntries.findAll {
            !new Cookie(it.cookie).serviceFlag
        }*.cookie
        rulesToDelete.each { cookie ->
            northbound.deleteSwitchRules(intermediateSwId, cookie)
        }

        and: "Ping the flow"
        def response = northbound.pingFlow(flow.flowId, new PingInput())

        then: "Ping shows that path is broken"
        !response.forward.pingSuccess
        !response.reverse.pingSuccess

        cleanup: "Delete the flow"
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    def "Able to turn on periodic pings on a flow"() {
        when: "Create a flow with periodic pings turned on"
        def endpointSwitches = topologyHelper.notNeighboringSwitchPair
        def flow = flowHelperV2.randomFlow(endpointSwitches).tap {
            it.periodicPings = true
        }
        flowHelperV2.addFlow(flow)

        then: "Packet counter on catch ping rules grows due to pings happening"
        def srcSwitchPacketCount = getPacketCountOfVlanPingRule(endpointSwitches.src.dpId)
        def dstSwitchPacketCount = getPacketCountOfVlanPingRule(endpointSwitches.dst.dpId)

        Wrappers.wait(pingInterval + WAIT_OFFSET / 2) {
            def srcPacketCountNow = getPacketCountOfVlanPingRule(endpointSwitches.src.dpId)
            def dstPacketCountNow = getPacketCountOfVlanPingRule(endpointSwitches.dst.dpId)

            srcPacketCountNow > srcSwitchPacketCount && dstPacketCountNow > dstSwitchPacketCount
        }

        cleanup: "Remove flow"
        flow && flowHelperV2.deleteFlow(flow.flowId)
    }

    @Tidy
    @Tags([LOW_PRIORITY])
    def "Unable to create a single-switch flow with periodic pings"() {
        when: "Try to create a single-switch flow with periodic pings"
        def flow = flowHelperV2.singleSwitchFlow(topology.activeSwitches.first()).tap {
            it.periodicPings = true
        }
        flowHelperV2.addFlow(flow)

        then: "Error is returned in response"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.BAD_REQUEST
        with(e.responseBodyAsString.to(MessageError)) {
            it.errorMessage == "Could not create flow"
            it.errorDescription == "Couldn't turn on periodic pings for one-switch flow"
        }

        cleanup:
        !e && flowHelperV2.deleteFlow(flow.flowId)
    }

    def getPacketCountOfVlanPingRule(SwitchId switchId) {
        return northbound.getSwitchRules(switchId).flowEntries
                .findAll{it.cookie == Cookie.VERIFICATION_UNICAST_RULE_COOKIE}[0].packetCount
    }

    /**
     * Returns all switch combinations with unique description, excluding single-switch combinations,
     * combinations with Centec switches and OF_12 switches
     */
    def getOfSwitchCombinations() {
        def switches = topology.activeSwitches.findAll {
            it.ofVersion != "OF_12" && !it.centec
        }
        [switches, switches].combinations().findAll { src, dst ->
            src != dst
        }.unique { [it*.description.sort(), it*.ofVersion.sort()] }
    }
}
