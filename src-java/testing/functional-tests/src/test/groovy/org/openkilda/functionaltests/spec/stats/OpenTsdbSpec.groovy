package org.openkilda.functionaltests.spec.stats

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.testing.Constants.DefaultRule

import groovy.time.TimeCategory
import org.springframework.beans.factory.annotation.Value
import spock.lang.Narrative
import spock.lang.Shared
import spock.lang.Unroll
import spock.util.mop.Use

@Use(TimeCategory)
@Narrative("Verify that basic stats logging happens.")
@Tags([SMOKE_SWITCHES])
class OpenTsdbSpec extends HealthCheckSpecification {

    @Shared
    @Value('${opentsdb.metric.prefix}')
    String metricPrefix

    @Tidy
    @Unroll("Stats are being logged for metric:#metric, tags:#tags")
    @Tags([TOPOLOGY_DEPENDENT, SMOKE])
    def "Basic stats are being logged"(metric, tags) {
        expect: "At least 1 result in the past 5 minutes"
        otsdb.query(5.minutes.ago, metric, tags).dps.size() > 0

        where:
        [metric, tags] << (
                //basic stats for rx/tx, use every unique switch in tags
                [[metricPrefix + "switch.rx-bytes", metricPrefix + "switch.rx-bits", metricPrefix + "switch.rx-packets",
                  metricPrefix + "switch.tx-bytes", metricPrefix + "switch.tx-bits", metricPrefix + "switch.tx-packets"],
                uniqueSwitches.collect { [switchid: it.dpId.toOtsdFormat()] }].combinations()
                //isl latency stats, use every unique switch in src_switch tag
                + [[metricPrefix + "isl.rtt"], uniqueSwitches.collect { [src_switch: it.dpId.toOtsdFormat()] }].combinations()
                //isl latency stats, use every unique switch in dst_switch tag
                + [[metricPrefix + "isl.rtt"], uniqueSwitches.collect { [dst_switch: it.dpId.toOtsdFormat()] }].combinations()
                //stats for default rules. use discovery packet cookie in tags, as is doesn't need any specific actions
                + [[metricPrefix + "switch.flow.system.packets", metricPrefix + "switch.flow.system.bytes", metricPrefix + "switch.flow.system.bits"],
                   [[cookieHex: DefaultRule.VERIFICATION_BROADCAST_RULE.toHexString()]]].combinations())
    }

    @Tidy
    @Tags(HARDWARE)
    @Unroll("Stats are being logged for metric:#metric, tags:#tags")
    def "Basic stats are being logged (10min interval)"() {
        expect: "At least 1 result in the past 10 minutes"
        otsdb.query(10.minutes.ago, metric, [:]).dps.size() > 0
        where:
        [metric, tags] << (
                [[metricPrefix + "switch.flow.system.meter.packets", metricPrefix + "switch.flow.system.meter.bytes",
                  metricPrefix + "switch.flow.system.meter.bits"],
                 [[cookieHex: String.format("0x%X", DefaultRule.VERIFICATION_BROADCAST_RULE.cookie)]]].combinations())
    }

    @Tidy
    @Unroll("GRPC stats are being logged for metric:#metric, tags:#tags")
    @Tags([HARDWARE])
    def "GRPC stats are being logged"(metric, tags) {
        assumeTrue(northbound.getFeatureToggles().collectGrpcStats,
"This test is skipped because 'collectGrpcStats' is disabled")
        expect: "At least 1 result in the past 15 minutes"
        otsdb.query(15.minutes.ago, metricPrefix + metric, tags).dps.size() > 0

        where:
        [metric, tags] << (
                [["switch.packet-in.total-packets", "switch.packet-in.total-packets.dataplane",
                  "switch.packet-in.no-match.packets", "switch.packet-in.apply-action.packets",
                  "switch.packet-in.apply-action.packets", "switch.packet-in.invalid-ttl.packets",
                  "switch.packet-in.action-set.packets", "switch.packet-in.group.packets",
                  "switch.packet-in.packet-out.packets", "switch.packet-out.total-packets.dataplane",
                  "switch.packet-out.total-packets.host", "switch.packet-out.eth0-interface-up"],
                 noviflowSwitches.collect { [switchid: it.switchId.toOtsdFormat()] }].combinations()
        )
    }

    def getUniqueSwitches() {
        topology.activeSwitches.unique { it.ofVersion }
    }

    def getNoviflowSwitches() {
        northbound.activeSwitches.findAll { it.manufacturer == "NoviFlow Inc" }.unique { [it.hardware, it.software] }
    }
}
