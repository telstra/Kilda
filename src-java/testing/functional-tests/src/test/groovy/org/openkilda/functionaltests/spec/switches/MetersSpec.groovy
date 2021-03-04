package org.openkilda.functionaltests.spec.switches

import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.model.MeterId.MAX_SYSTEM_RULE_METER_ID
import static org.openkilda.model.MeterId.createMeterIdForDefaultRule
import static org.openkilda.model.cookie.Cookie.ARP_POST_INGRESS_COOKIE
import static org.openkilda.model.cookie.Cookie.ARP_POST_INGRESS_ONE_SWITCH_COOKIE
import static org.openkilda.model.cookie.Cookie.ARP_POST_INGRESS_VXLAN_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_ONE_SWITCH_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_VXLAN_COOKIE
import static org.openkilda.testing.Constants.WAIT_OFFSET
import static spock.util.matcher.HamcrestSupport.expect

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.info.meter.MeterEntry
import org.openkilda.messaging.info.rule.FlowEntry
import org.openkilda.messaging.info.rule.SwitchFlowEntries
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.testing.Constants
import org.openkilda.testing.model.topology.TopologyDefinition.Switch

import groovy.transform.Memoized
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.Unroll

import java.math.RoundingMode

@Narrative("""The test suite checks if traffic meters, including default, are set and deleted in a correct way.
Note that many tests are bind to meter implementations of certain hardware manufacturers.""")
class MetersSpec extends HealthCheckSpecification {
    static DISCO_PKT_RATE = 200 // Number of packets per second for the default flows
    static DISCO_PKT_SIZE = 250 // Default size of the discovery packet
    static DISCO_PKT_BURST = 4096 // Default desired packet burst rate for the default flows (ignored by Noviflow)
    static MIN_RATE_KBPS = 64
    static CENTEC_MIN_BURST = 1024 // Driven by the Centec specification
    static CENTEC_MAX_BURST = 32000 // Driven by the Centec specification

    @Value('${burst.coefficient}')
    double burstCoefficient

    @Tidy
    @Unroll
    @Tags([TOPOLOGY_DEPENDENT, SMOKE, SMOKE_SWITCHES])
    def "Able to delete a meter from a #switchType switch"() {
        assumeTrue(switches as boolean, "Unable to find required switches in topology")

        setup: "Select a #switchType switch and retrieve default meters"
        def sw = switches.first()
        def defaultMeters = northbound.getAllMeters(sw.dpId)

        when: "A flow is created and its meter is deleted"
        def flow = flowHelperV2.addFlow(flowHelperV2.singleSwitchFlow(sw))
        def meterToDelete = northbound.getAllMeters(sw.dpId).meterEntries.find {
            !defaultMeters.meterEntries*.meterId.contains(it.meterId)
        }.meterId
        def deleteResult = northbound.deleteMeter(sw.dpId, meterToDelete)

        then: "Delete operation should be successful"
        deleteResult.deleted
        !northbound.getAllMeters(sw.dpId).meterEntries.find { it.meterId == meterToDelete }

        when: "Delete the flow"
        def flowDelete = flowHelperV2.deleteFlow(flow.flowId)

        then: "No excessive meters are installed on the switch"
        Wrappers.wait(WAIT_OFFSET) {
            assert defaultMeters.meterEntries.sort() == northbound.getAllMeters(sw.dpId).meterEntries.sort()
        }

        cleanup:
        !flowDelete && flowHelperV2.deleteFlow(flow.flowId)

        where:
        switchType         | switches
        "Centec"           | getCentecSwitches()
        "Noviflow"         | getNoviflowSwitches()
        "Noviflow(Wb5164)" | getNoviflowWb5164()
        "OVS"              | getVirtualSwitches()
    }

    @Tidy
    @Unroll
    @Tags([TOPOLOGY_DEPENDENT])
    def "Unable to delete a meter with invalid ID=#meterId on a #switchType switch"() {
        assumeTrue(switches as boolean, "Unable to find required switches in topology")

        when: "Try to delete meter with invalid ID"
        northbound.deleteMeter(switches[0].dpId, meterId)

        then: "Got BadRequest because meter ID is invalid"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400

        where:
        meterId | switches              | switchType
        -1      | getNoviflowSwitches() | "Noviflow"
        0       | getNoviflowSwitches() | "Noviflow"
        -1      | getCentecSwitches()   | "Centec"
        0       | getCentecSwitches()   | "Centec"
        -1      | getNoviflowWb5164()   | "Noviflow(Wb5164)"
        0       | getNoviflowWb5164()   | "Noviflow(Wb5164)"
        -1      | getVirtualSwitches()  | "OVS"
        0       | getVirtualSwitches()  | "OVS"
    }

    /**
     * Default meters should be set in PKTPS by default in Kilda, but Centec switches only allow KBPS flag.
     * System should recalculate the PKTPS value to KBPS on Centec switches.
     */
    @Tidy
    @Unroll
    @Tags([HARDWARE, SMOKE_SWITCHES])
    def "Default meters should express bandwidth in kbps re-calculated from pktps on Centec switch(#sw.dpId)"() {
        expect: "Only the default meters should be present on the switch"
        def meters = northbound.getAllMeters(sw.dpId)
        assert meters.meterEntries.size() == 2
        assert meters.meterEntries.each {
            assert it.rate == Math.max((long) (DISCO_PKT_RATE * DISCO_PKT_SIZE * 8 / 1024L), MIN_RATE_KBPS)
        }
        //unable to use #getExpectedBurst. For Centects there's special burst due to KBPS
        assert meters.meterEntries.every { it.burstSize == (long) ((DISCO_PKT_BURST * DISCO_PKT_SIZE * 8) / 1024) }
        assert meters.meterEntries.every(defaultMeters)
        assert meters.meterEntries.every { ["KBPS", "BURST", "STATS"].containsAll(it.flags) }
        assert meters.meterEntries.every { it.flags.size() == 3 }

        where:
        sw << (getCentecSwitches().unique { it.description }
                ?: assumeTrue(false, "Unable to find Centec switches in topology"))
    }

    @Tidy
    @Unroll
    @Tags([HARDWARE, SMOKE_SWITCHES])
    def "Default meters should express bandwidth in pktps on Noviflow switch(#sw.dpId)"() {
        //TODO: Research how to calculate burstSize on OpenVSwitch in this case
        // now burstSize is equal to 4096, rate == 200
        expect: "Only the default meters should be present on the switch"
        def meters = northbound.getAllMeters(sw.dpId)
        meters.meterEntries*.meterId.sort() == sw.defaultMeters.sort()
        meters.meterEntries.each { assert it.burstSize == switchHelper.getExpectedBurst(sw.dpId, it.rate) }
        meters.meterEntries.each { assert ["PKTPS", "BURST", "STATS"].containsAll(it.flags) }
        meters.meterEntries.each { assert it.flags.size() == 3 }

        where:
        sw << (getNoviflowSwitches().unique { it.nbFormat().hardware + it.nbFormat().software }
                ?: assumeTrue(false, "Unable to find Noviflow switch in topology" ))
    }

    @Tidy
    @Unroll
    @Tags([HARDWARE, SMOKE_SWITCHES])
    def "Default meters should express bandwidth in kbps on Noviflow Wb5164 switch(#sw.dpId)"() {
        expect: "Only the default meters should be present on the switch"
        def meters = northbound.getAllMeters(sw.dpId)
        meters.meterEntries*.meterId.sort() == sw.defaultMeters.sort()
        /* burstSizre doesn't depend on rate on WB switches, it should be calculated by formula
        burstSize * packet_size * 8 / 1024,
        where burstSize - 4096, packet_size: lldp - 300, arp - 100, unicast/multicast - 250 */
        List<Long> arpMeters = [createMeterIdForDefaultRule(ARP_POST_INGRESS_COOKIE).getValue(),
                                createMeterIdForDefaultRule(ARP_POST_INGRESS_VXLAN_COOKIE).getValue(),
                                createMeterIdForDefaultRule(ARP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue()] //22, 23, 24
        List<Long> lldpMeters = [createMeterIdForDefaultRule(LLDP_POST_INGRESS_COOKIE).getValue(),
                                 createMeterIdForDefaultRule(LLDP_POST_INGRESS_VXLAN_COOKIE).getValue(),
                                 createMeterIdForDefaultRule(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue()] //16, 17, 18

        meters.meterEntries.each { meter ->
            if (meter.meterId in arpMeters) {
                verifyBurstSizeOnWb5164(meter.burstSize,
                        Math.max((long) (DISCO_PKT_BURST * 100 * 8 / 1024L), MIN_RATE_KBPS))
            } else if (meter.meterId in lldpMeters) {
                verifyBurstSizeOnWb5164(meter.burstSize,
                        Math.max((long) (DISCO_PKT_BURST * 300 * 8 / 1024L), MIN_RATE_KBPS))
            } else {
                verifyBurstSizeOnWb5164(meter.burstSize,
                        Math.max((long) (DISCO_PKT_BURST * 250 * 8 / 1024L), MIN_RATE_KBPS))
            }
        }
        meters.meterEntries.each { assert ["KBPS", "BURST", "STATS"].containsAll(it.flags) }
        meters.meterEntries.each { assert it.flags.size() == 3 }

        where:
        sw << (getNoviflowWb5164().unique { it.description } ?:
                assumeTrue(false, "Unable to find Noviflow Wb5164 switches in topology"))
    }

    @Tidy
    @Unroll
    @Tags([TOPOLOGY_DEPENDENT])
    @IterationTag(tags = [SMOKE_SWITCHES], iterationNameRegex = /ignore_bandwidth=false/)
    def "Meters are created/deleted when creating/deleting a single-switch flow with ignore_bandwidth=#ignoreBandwidth \
on a #switchType switch"() {
        assumeTrue(switches as boolean, "Unable to find required switches in topology")

        given: "A #switchType switch with OpenFlow 1.3 support"
        def sw = switches.first()

        when: "Get default meters from the switch"
        def defaultMeters = northbound.getAllMeters(sw.dpId)
        assert defaultMeters

        and: "Create a single-switch flow"
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flow.ignoreBandwidth = ignoreBandwidth
        flowHelperV2.addFlow(flow)

        then: "New meters should appear after flow setup"
        def newMeters = northbound.getAllMeters(sw.dpId)
        def newMeterEntries = newMeters.meterEntries.findAll { !defaultMeters.meterEntries.contains(it) }
        newMeterEntries.size() == 2

        and: "All new meters should have KBPS, BURST and STATS flags installed"
        newMeterEntries.every { it.flags.sort().equals(["KBPS", "BURST", "STATS"].sort()) }

        and: "All new meters rate should be equal to flow's rate"
        newMeterEntries*.rate.every { it == flow.maximumBandwidth }

        and: "Switch validation shows no discrepancies in meters"
        def metersValidation = northbound.validateSwitch(sw.dpId).meters
        metersValidation.proper.size() == 2 + sw.defaultMeters.size()
        metersValidation.excess.empty
        metersValidation.missing.empty
        metersValidation.misconfigured.empty

        and: "Flow validation shows no discrepancies in meters"
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }

        when: "Delete the flow"
        def deleteFlow = flowHelperV2.deleteFlow(flow.flowId)

        then: "New meters should disappear from the switch"
        Wrappers.wait(WAIT_OFFSET) {
            def newestMeters = northbound.getAllMeters(sw.dpId)
            newestMeters.meterEntries.containsAll(defaultMeters.meterEntries)
            newestMeters.meterEntries.size() == defaultMeters.meterEntries.size()
        }

        cleanup:
        !deleteFlow && flowHelperV2.deleteFlow(flow.flowId)

        where:
        switchType         | switches              | ignoreBandwidth
        "Centec"           | getCentecSwitches()   | false
        "Centec"           | getCentecSwitches()   | true
        "Noviflow"         | getNoviflowSwitches() | false
        "Noviflow"         | getNoviflowSwitches() | true
        "Noviflow(Wb5164)" | getNoviflowWb5164()   | false
        "Noviflow(Wb5164)" | getNoviflowWb5164()   | true
        "OVS"              | getVirtualSwitches()  | false
        "OVS"              | getVirtualSwitches()  | true
    }

    @Tidy
    @Unroll
    @Tags([TOPOLOGY_DEPENDENT])
    def "Meters are not created when creating a single-switch flow with maximum_bandwidth=0 on a #switchType switch"() {
        assumeTrue(switches as boolean, "Unable to find required switches in topology")

        given: "A #switchType switch with OpenFlow 1.3 support"
        def sw = switches.first()

        when: "Get default meters from the switch"
        def defaultMeters = northbound.getAllMeters(sw.dpId)
        assert defaultMeters

        and: "Create a single-switch flow with maximum_bandwidth=0"
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flow.maximumBandwidth = 0
        flow.ignoreBandwidth = true
        flowHelperV2.addFlow(flow)

        then: "Ony default meters should be present on the switch and new meters should not appear after flow setup"
        def newMeters = northbound.getAllMeters(sw.dpId)
        def newMeterEntries = newMeters.meterEntries.findAll { !defaultMeters.meterEntries.contains(it) }
        newMeterEntries.empty

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        switchType         | switches
        "Centec"           | getCentecSwitches()
        "Noviflow"         | getNoviflowSwitches()
        "Noviflow(Wb5164)" | getNoviflowWb5164()
        "OVS"              | getVirtualSwitches()
    }

    @Tidy
    @Unroll
    @Tags([TOPOLOGY_DEPENDENT])
    def "Source/destination switches have meters only in flow ingress rule and intermediate switches don't have \
meters in flow rules at all (#data.flowType flow)"() {
        assumeTrue(data.switchPair != null, "Unable to find required switch pair in topology")

        when: "Create a flow between given switches"
        def flow = flowHelperV2.randomFlow(data.switchPair)
        flowHelperV2.addFlow(flow)

        then: "The source and destination switches have only one meter in the flow's ingress rule"
        def srcSwFlowMeters = northbound.getAllMeters(flow.source.switchId).meterEntries.findAll(flowMeters)
        def dstSwFlowMeters = northbound.getAllMeters(flow.destination.switchId).meterEntries.findAll(flowMeters)

        srcSwFlowMeters.size() == 1
        dstSwFlowMeters.size() == 1

        def srcSwitchRules = northbound.getSwitchRules(flow.source.switchId).flowEntries.findAll { !Cookie.isDefaultRule(it.cookie) }
        def dstSwitchRules = northbound.getSwitchRules(flow.destination.switchId).flowEntries.findAll { !Cookie.isDefaultRule(it.cookie) }

        if (northbound.getSwitchProperties(flow.source.switchId).multiTable) {
            def srcSwIngressFlowRules = srcSwitchRules.findAll { it.match.inPort == flow.source.portNumber.toString() }
            assert srcSwIngressFlowRules.size() == 2 //shared + simple ingress
            def srcSwIngressSharedRule = srcSwIngressFlowRules.find {
                new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW
            }
            def srcSwIngressNonSharedRule = srcSwIngressFlowRules.find {
                new Cookie(it.cookie).getType() != CookieType.SHARED_OF_FLOW
            }
            assert srcSwIngressSharedRule.match.vlanVid == flow.source.vlanId.toString()
            assert !srcSwIngressSharedRule.instructions.goToMeter
            assert srcSwFlowMeters[0].meterId == srcSwIngressNonSharedRule.instructions.goToMeter
        } else {
            def srcSwFlowIngressRule = filterRules(srcSwitchRules, flow.source.portNumber, flow.source.vlanId, null)[0]
            assert srcSwFlowMeters[0].meterId == srcSwFlowIngressRule.instructions.goToMeter
        }

        if (northbound.getSwitchProperties(flow.destination.switchId).multiTable) {
            def dstSwIngressFlowRules = dstSwitchRules.findAll { it.match.inPort == flow.destination.portNumber.toString() }
            assert dstSwIngressFlowRules.size() == 2 //shared + simple ingress
            def dstSwIngressSharedRule = dstSwIngressFlowRules.find {
                new Cookie(it.cookie).getType() == CookieType.SHARED_OF_FLOW
            }
            def dstSwIngressNonSharedRule = dstSwIngressFlowRules.find {
                new Cookie(it.cookie).getType() != CookieType.SHARED_OF_FLOW
            }
            assert dstSwIngressSharedRule.match.vlanVid == flow.destination.vlanId.toString()
            assert !dstSwIngressSharedRule.instructions.goToMeter
            assert dstSwFlowMeters[0].meterId == dstSwIngressNonSharedRule.instructions.goToMeter
        } else {
            def dstSwFlowIngressRule = filterRules(dstSwitchRules, flow.destination.portNumber, flow.destination.vlanId,
                    null)[0]
            assert dstSwFlowMeters[0].meterId == dstSwFlowIngressRule.instructions.goToMeter
        }

        and: "The source and destination switches have no meters in the flow's egress rule"
        def srcSwFlowEgressRule = filterRules(srcSwitchRules, null, null, flow.source.portNumber)[0]
        def dstSwFlowEgressRule = filterRules(dstSwitchRules, null, null, flow.destination.portNumber)[0]

        !srcSwFlowEgressRule.instructions.goToMeter
        !dstSwFlowEgressRule.instructions.goToMeter

        and: "Intermediate switches don't have meters in flow rules at all"
        pathHelper.getInvolvedSwitches(flow.flowId)[1..-2].findAll { it.ofVersion != "OF_12" }.each { sw ->
            assert northbound.getAllMeters(sw.dpId).meterEntries.findAll(flowMeters).empty
            def flowRules = northbound.getSwitchRules(sw.dpId).flowEntries.findAll { !(it.cookie in sw.defaultCookies) }
            flowRules.each { assert !it.instructions.goToMeter }
        }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [
                [
                        flowType  : "Centec-Centec",
                        switchPair: getTopologyHelper().getAllNotNeighboringSwitchPairs().find {
                            it.src.centec && it.dst.centec && hasOf13Path(it)
                        }
                ],
                [
                        flowType  : "Noviflow-Noviflow",
                        switchPair: getTopologyHelper().getAllNotNeighboringSwitchPairs().find {
                            it.src.noviflow && it.src.ofVersion == "OF_13" &&
                                    it.dst.noviflow && it.dst.ofVersion == "OF_13" && hasOf13Path(it)
                        }
                ],
                //TODO(rtretiak): unlock above iterations by introducing a more clever cost manipulation
                [
                        flowType  : "Centec-Noviflow",
                        switchPair: getTopologyHelper().getAllNotNeighboringSwitchPairs().find {
                            ((it.src.centec && it.dst.noviflow && it.dst.ofVersion == "OF_13") ||
                                    (it.src.noviflow && it.src.ofVersion == "OF_13" && it.dst.centec)) &&
                                    hasOf13Path(it)
                        }
                ],
                [
                        flowType  : "Noviflow_Wb5164-Noviflow_Wb5164",
                        switchPair: getTopologyHelper().getAllNotNeighboringSwitchPairs().find {
                            it.src.wb5164 && it.dst.wb5164 && hasOf13Path(it)
                        }
                ],
                [
                        flowType  : "OVS-OVS",
                        switchPair: getTopologyHelper().getAllNotNeighboringSwitchPairs().find {
                            it.src.virtual && it.dst.virtual && hasOf13Path(it)
                        }
                ]
        ]
    }

    @Tidy
    @Unroll
    @Tags([TOPOLOGY_DEPENDENT, SMOKE_SWITCHES])
    def "Meter burst size is correctly set on #data.switchType switches for #flowRate flow rate"() {
        setup: "A single-switch flow with #flowRate kbps bandwidth is created on OpenFlow 1.3 compatible switch"
        def switches = data.switches
        assumeTrue(switches as boolean, "Unable to find required switches in topology")

        def sw = switches.first()
        def defaultMeters = northbound.getAllMeters(sw.dpId)
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flow.setMaximumBandwidth(100)
        flowHelperV2.addFlow(flow)

        when: "Update flow bandwidth to #flowRate kbps"
        flow.setMaximumBandwidth(flowRate)
        flowHelperV2.updateFlow(flow.flowId, flow)

        then: "New meters should be installed on the switch"
        def newMeters = northbound.getAllMeters(sw.dpId).meterEntries.findAll {
            !defaultMeters.meterEntries.contains(it)
        }
        assert newMeters.size() == 2

        and: "New meters rate should be equal to flow bandwidth"
        newMeters*.rate.each { assert it == flowRate }

        and: "New meters burst size matches the expected value for given switch model"
        newMeters*.burstSize.each { assert it == switchHelper.getExpectedBurst(sw.dpId, flowRate) }

        and: "Switch validation shows no discrepancies in meters"
        def metersValidation = northbound.validateSwitch(sw.dpId).meters
        metersValidation.proper.size() == 2 + sw.defaultMeters.size()
        metersValidation.excess.empty
        metersValidation.missing.empty
        metersValidation.misconfigured.empty

        and: "Flow validation shows no discrepancies in meters"
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        [flowRate, data] << [
                [150, 1000, 1024, 5120, 10240, 2480, 960000],
                [
                        ["switchType": "Noviflow",
                         "switches"  : getNoviflowSwitches()],
                        ["switchType": "OVS",
                         "switches"  : getVirtualSwitches()]
                ]
        ].combinations()

    }

    @Tidy
    @Tags([HARDWARE, TOPOLOGY_DEPENDENT, SMOKE_SWITCHES])
    @Unroll("Flow burst should be correctly set on Centec switches in case of #flowRate kbps flow bandwidth")
    def "Flow burst is correctly set on Centec switches"() {
        setup: "A single-switch flow with #flowRate kbps bandwidth is created on OpenFlow 1.3 compatible Centec switch"
        def switches = getCentecSwitches()
        assumeTrue(switches as boolean, "Unable to find required switches in topology")

        def sw = switches.first()
        def expectedBurstSize = switchHelper.getExpectedBurst(sw.dpId, flowRate)
        def defaultMeters = northbound.getAllMeters(sw.dpId)
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flow.setMaximumBandwidth(100)
        flowHelperV2.addFlow(flow)

        when: "Update flow bandwidth to #flowRate kbps"
        flow.setMaximumBandwidth(flowRate)
        flowHelperV2.updateFlow(flow.flowId, flow)

        then: "Meters with updated rate should be installed on the switch"
        def newMeters = null
        Wrappers.wait(Constants.RULES_DELETION_TIME + Constants.RULES_INSTALLATION_TIME) {
            newMeters = northbound.getAllMeters(sw.dpId).meterEntries.findAll {
                !defaultMeters.meterEntries.contains(it)
            }
            assert newMeters.size() == 2
            assert newMeters*.rate.every { it == flowRate }
        }

        and: "New meters burst size should respect the min/max border value for Centec"
        newMeters*.burstSize.every { it == expectedBurstSize }

        and: "Switch validation shows no discrepancies in meters"
        def metersValidation = northbound.validateSwitch(sw.dpId).meters
        metersValidation.proper.size() == 2 + sw.defaultMeters.size()
        metersValidation.excess.empty
        metersValidation.missing.empty
        metersValidation.misconfigured.empty

        and: "Flow validation shows no discrepancies in meters"
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        flowRate << [
                //flowRate below min
                ((CENTEC_MIN_BURST - 1) / getBurstCoefficient())
                        .toBigDecimal().setScale(0, RoundingMode.CEILING).toLong(),
                1000, //casual middle value
                //flowRate above max
                ((CENTEC_MAX_BURST + 1) / getBurstCoefficient())
                        .toBigDecimal().setScale(0, RoundingMode.CEILING).toLong()
        ]
    }

    @Tidy
    @Unroll
    @Tags([HARDWARE, SMOKE_SWITCHES])
    def "Meter burst size is correctly set on Noviflow Wb5164 switches for #flowRate flow rate"() {
        setup: "A single-switch flow with #flowRate kbps bandwidth is created on OpenFlow 1.3 compatible switch"
        def switches = getNoviflowWb5164()
        assumeTrue(switches as boolean, "Unable to find required switches in topology")

        def sw = switches.first()
        def defaultMeters = northbound.getAllMeters(sw.dpId)
        def flow = flowHelperV2.singleSwitchFlow(sw)
        flow.setMaximumBandwidth(100)
        flowHelperV2.addFlow(flow)

        when: "Update flow bandwidth to #flowRate kbps"
        flow.setMaximumBandwidth(flowRate)
        flowHelperV2.updateFlow(flow.flowId, flow)

        then: "New meters should be installed on the switch"
        def newMeters = northbound.getAllMeters(sw.dpId).meterEntries.findAll {
            !defaultMeters.meterEntries.contains(it)
        }
        assert newMeters.size() == 2

        and: "New meters rate should be equal to flow bandwidth"
        newMeters.each { meter ->
            verifyRateSizeOnWb5164(flowRate.toLong(), meter.rate)
        }

        and: "New meters burst size matches the expected value for given switch model"
        newMeters.each { meter ->
            Long actualBurstSize = meter.burstSize
            Long expectedBurstSize = switchHelper.getExpectedBurst(sw.dpId, flowRate)
            verifyBurstSizeOnWb5164(expectedBurstSize, actualBurstSize)
        }

        and: "Switch validation shows no discrepancies in meters"
        def metersValidation = northbound.validateSwitch(sw.dpId).meters
        metersValidation.proper.size() == 2 + sw.defaultMeters.size()
        metersValidation.excess.empty
        metersValidation.missing.empty
        metersValidation.misconfigured.empty

        and: "Flow validation shows no discrepancies in meters"
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }

        cleanup: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        flowRate << [150, 1000, 1024, 5120, 10240, 2480, 960000]
    }

    @Tidy
    @Unroll
    @Tags([TOPOLOGY_DEPENDENT, SMOKE_SWITCHES])
    def "System allows to reset meter values to defaults without reinstalling rules for #data.description flow"() {
        given: "Switches combination (#data.description)"
        assumeTrue(data.switches.size() > 1, "Desired switch combination is not available in current topology")
        def src = data.switches[0]
        def dst = data.switches[1]

        and: "A flow with custom meter rate and burst, that differ from defaults"
        def flow = flowHelperV2.randomFlow(src, dst)
        flow.maximumBandwidth = 1000
        flowHelperV2.addFlow(flow)
        /*at this point meters are set for given flow. Now update flow bandwidth directly via DB, so that existing meter
        rate and burst is no longer correspond to the flow bandwidth*/
        def newBandwidth = 2000
        database.updateFlowBandwidth(flow.flowId, newBandwidth)
        //at this point existing meters do not correspond with the flow
        //now save some original data for further comparison before resetting meters
        Map<SwitchId, SwitchFlowEntries> originalRules = [src.dpId, dst.dpId].collectEntries {
            [(it): northbound.getSwitchRules(it)]
        }
        Map<SwitchId, List<MeterEntry>> originalMeters = [src.dpId, dst.dpId].collectEntries {
            [(it): northbound.getAllMeters(it).meterEntries]
        }

        when: "Ask system to reset meters for the flow"
        def response = northbound.resetMeters(flow.flowId)

        then: "Response contains correct info about new meter values"
        [response.srcMeter, response.dstMeter].each { switchMeterEntries ->
            def originalFlowMeters = originalMeters[switchMeterEntries.switchId].findAll(flowMeters)
            switchMeterEntries.meterEntries.each { meterEntry ->
                if (northbound.getSwitch(switchMeterEntries.switchId).hardware =~ "WB5164") {
                    verifyRateSizeOnWb5164(newBandwidth, meterEntry.rate)
                    Long expectedBurstSize = switchHelper.getExpectedBurst(switchMeterEntries.switchId, newBandwidth)
                    Long actualBurstSize = meterEntry.burstSize
                    verifyBurstSizeOnWb5164(expectedBurstSize, actualBurstSize)
                } else {
                    assert meterEntry.rate == newBandwidth
                    assert meterEntry.burstSize == switchHelper.getExpectedBurst(switchMeterEntries.switchId, newBandwidth)
                }
            }
            assert switchMeterEntries.meterEntries*.meterId.sort() == originalFlowMeters*.meterId.sort()
            assert switchMeterEntries.meterEntries*.flags.sort() == originalFlowMeters*.flags.sort()
        }

        //cannot be checked until https://github.com/telstra/open-kilda/issues/3335
//        and: "Non-default meter rate and burst are actually changed to expected values both on src and dst switch"
//        def srcFlowMeters = northbound.getAllMeters(src.dpId).meterEntries.findAll(flowMeters)
//        def dstFlowMeters = northbound.getAllMeters(dst.dpId).meterEntries.findAll(flowMeters)
//        expect srcFlowMeters, sameBeanAs(response.srcMeter.meterEntries).ignoring("timestamp")
//        expect dstFlowMeters, sameBeanAs(response.dstMeter.meterEntries).ignoring("timestamp")

        and: "Default meters are unchanged"
        [src.dpId, dst.dpId].each { SwitchId swId ->
            assert expect(northbound.getAllMeters(swId).meterEntries.findAll(defaultMeters).sort(),
                    sameBeanAs(originalMeters[swId].findAll(defaultMeters).sort())
                            .ignoring("timestamp"))
        }

        and: "Switch rules are unchanged"
        [src.dpId, dst.dpId].each { SwitchId swId ->
            assert expect(northbound.getSwitchRules(swId), sameBeanAs(originalRules[swId])
                    .ignoring("timestamp")
                    .ignoring("flowEntries.durationNanoSeconds")
                    .ignoring("flowEntries.durationSeconds")
                    .ignoring("flowEntries.byteCount")
                    .ignoring("flowEntries.packetCount"))
        }

        cleanup: "Delete flow"
        flowHelperV2.deleteFlow(flow.flowId)

        where:
        data << [
                [
                        description: "Noviflow-Noviflow",
                        switches   : noviflowSwitches
                ],
                [
                        description: "Centec-Centec",
                        switches   : centecSwitches
                ],
                [
                        description: "Centec-Noviflow",
                        switches   : !centecSwitches.empty && !noviflowSwitches.empty ?
                                [centecSwitches[0], noviflowSwitches[0]] : []
                ],
                [
                        description: "Noviflow_Wb5164-Noviflow_Wb5164",
                        switches   : noviflowWb5164
                ],
                [
                        description: "OVS-OVS",
                        switches   : virtualSwitches
                ]
        ]
    }

    @Tidy
    def "Try to reset meters for unmetered flow"() {
        given: "A flow with the 'bandwidth: 0' and 'ignoreBandwidth: true' fields"
        def availableSwitches = topology.activeSwitches
        def src = availableSwitches[0]
        def dst = availableSwitches[1]

        def flow = flowHelperV2.randomFlow(src, dst)
        flow.ignoreBandwidth = true
        flow.maximumBandwidth = 0
        flowHelperV2.addFlow(flow)

        when: "Resetting meter burst and rate to default"
        northbound.resetMeters(flow.flowId)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.rawStatusCode == 400
        exc.responseBodyAsString.to(MessageError).errorMessage == "Can't update meter: Flow '$flow.flowId' is unmetered"

        cleanup: "Delete the created flow"
        flowHelperV2.deleteFlow(flow.flowId)
    }

    @Memoized
    String getSwitchDescription(SwitchId sw) {
        northbound.activeSwitches.find { it.switchId == sw }.description
    }

    @Memoized
    List<Switch> getNoviflowSwitches() {
        topology.activeSwitches.findAll { it.noviflow && it.ofVersion == "OF_13" && !it.wb5164 }
    }

    @Memoized
    List<Switch> getCentecSwitches() {
        topology.getActiveSwitches().findAll { it.centec }
    }

    @Memoized
    List<Switch> getNoviflowWb5164() {
        topology.getActiveSwitches().findAll { it.wb5164 }
    }

    @Memoized
    List<Switch> getVirtualSwitches() {
        topology.getActiveSwitches().findAll { it.virtual }
    }

    List<FlowEntry> filterRules(List<FlowEntry> rules, inPort, inVlan, outPort) {
        if (inPort) {
            rules = rules.findAll { it.match.inPort == inPort.toString() }
        }
        if (inVlan) {
            rules = rules.findAll { it.match.vlanVid == inVlan.toString() }
        }
        if (outPort) {
            rules = rules.findAll { it.instructions?.applyActions?.flowOutput == outPort.toString() }
        }

        return rules
    }

    def defaultMeters = { it.meterId <= MAX_SYSTEM_RULE_METER_ID }

    def flowMeters = { it.meterId > MAX_SYSTEM_RULE_METER_ID }

    boolean hasOf13Path(SwitchPair pair) {
        def possibleDefaultPaths = pair.paths.findAll {
            it.size() == pair.paths.min { it.size() }.size()
        }
        !possibleDefaultPaths.find { path ->
            path[1..-2].every { it.switchId.description.contains("OF_12") }
        }
    }

    void verifyBurstSizeOnWb5164(Long expected, Long actual) {
        //...ValidationServiceImpl.E_SWITCH_METER_RATE_EQUALS_DELTA_COEFFICIENT = 0.01
        assert Math.abs(expected - actual) <= expected * 0.01
    }

    void verifyRateSizeOnWb5164(Long expectedRate, Long actualRate) {
        //...ValidationServiceImpl.E_SWITCH_METER_BURST_SIZE_EQUALS_DELTA_COEFFICIENT = 0.01
        assert Math.abs(expectedRate - actualRate) <= expectedRate * 0.01
    }
}
