package org.openkilda.functionaltests.helpers

import static groovyx.gpars.GParsPool.withPool
import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.containsInAnyOrder
import static org.openkilda.model.cookie.Cookie.ARP_INGRESS_COOKIE
import static org.openkilda.model.cookie.Cookie.ARP_INPUT_PRE_DROP_COOKIE
import static org.openkilda.model.cookie.Cookie.ARP_POST_INGRESS_COOKIE
import static org.openkilda.model.cookie.Cookie.ARP_POST_INGRESS_ONE_SWITCH_COOKIE
import static org.openkilda.model.cookie.Cookie.ARP_POST_INGRESS_VXLAN_COOKIE
import static org.openkilda.model.cookie.Cookie.ARP_TRANSIT_COOKIE
import static org.openkilda.model.cookie.Cookie.CATCH_BFD_RULE_COOKIE
import static org.openkilda.model.cookie.Cookie.DROP_RULE_COOKIE
import static org.openkilda.model.cookie.Cookie.DROP_VERIFICATION_LOOP_RULE_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_INGRESS_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_INPUT_PRE_DROP_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_ONE_SWITCH_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_VXLAN_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_TRANSIT_COOKIE
import static org.openkilda.model.cookie.Cookie.MULTITABLE_EGRESS_PASS_THROUGH_COOKIE
import static org.openkilda.model.cookie.Cookie.MULTITABLE_INGRESS_DROP_COOKIE
import static org.openkilda.model.cookie.Cookie.MULTITABLE_POST_INGRESS_DROP_COOKIE
import static org.openkilda.model.cookie.Cookie.MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE
import static org.openkilda.model.cookie.Cookie.MULTITABLE_TRANSIT_DROP_COOKIE
import static org.openkilda.model.cookie.Cookie.ROUND_TRIP_LATENCY_RULE_COOKIE
import static org.openkilda.model.cookie.Cookie.SERVER_42_OUTPUT_VLAN_COOKIE
import static org.openkilda.model.cookie.Cookie.SERVER_42_OUTPUT_VXLAN_COOKIE
import static org.openkilda.model.cookie.Cookie.SERVER_42_TURNING_COOKIE
import static org.openkilda.model.cookie.Cookie.VERIFICATION_BROADCAST_RULE_COOKIE
import static org.openkilda.model.cookie.Cookie.VERIFICATION_UNICAST_RULE_COOKIE
import static org.openkilda.model.cookie.Cookie.VERIFICATION_UNICAST_VXLAN_RULE_COOKIE
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.messaging.info.event.IslChangeType
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.model.MeterId
import org.openkilda.model.SwitchFeature
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.model.cookie.CookieBase.CookieType
import org.openkilda.model.cookie.PortColourCookie
import org.openkilda.northbound.dto.v1.switches.MeterInfoDto
import org.openkilda.northbound.dto.v1.switches.SwitchDto
import org.openkilda.northbound.dto.v1.switches.SwitchPropertiesDto
import org.openkilda.northbound.dto.v1.switches.SwitchValidationResult
import org.openkilda.testing.Constants
import org.openkilda.testing.model.topology.TopologyDefinition
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.model.topology.TopologyDefinition.SwitchProperties
import org.openkilda.testing.service.database.Database
import org.openkilda.testing.service.floodlight.model.Floodlight
import org.openkilda.testing.service.floodlight.model.FloodlightConnectMode
import org.openkilda.testing.service.lockkeeper.LockKeeperService
import org.openkilda.testing.service.lockkeeper.model.FloodlightResourceAddress
import org.openkilda.testing.service.northbound.NorthboundService
import org.openkilda.testing.tools.IslUtils

import groovy.transform.Memoized
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.math.RoundingMode

/**
 * Provides helper operations for some Switch-related content.
 * This helper is also injected as a Groovy Extension Module, so methods can be called in two ways:
 * <pre>
 * {@code
 * Switch sw = topology.activeSwitches[0]
 * def descriptionJ = SwitchHelper.getDescription(sw) //Java way
 * def descriptionG = sw.description //Groovysh way
 *}
 * </pre>
 */
@Component
class SwitchHelper {
    static NorthboundService northbound
    static Database database

    //below values are manufacturer-specific and override default Kilda values on firmware level
    static NOVIFLOW_BURST_COEFFICIENT = 1.005 // Driven by the Noviflow specification
    static CENTEC_MIN_BURST = 1024 // Driven by the Centec specification
    static CENTEC_MAX_BURST = 32000 // Driven by the Centec specification

    @Value('${burst.coefficient}')
    double burstCoefficient

    @Value('${discovery.generic.interval}')
    int discoveryInterval

    @Value('${discovery.timeout}')
    int discoveryTimeout

    @Autowired
    TopologyDefinition topology

    @Autowired
    IslUtils islUtils

    @Autowired
    LockKeeperService lockKeeper

    @Autowired
    SwitchHelper(NorthboundService northbound, Database database) {
        this.northbound = northbound
        this.database = database
    }

    static String getDescription(Switch sw) {
        sw.nbFormat().description
    }

    @Memoized
    static SwitchDto nbFormat(Switch sw) {
        northbound.getSwitch(sw.dpId)
    }

    @Memoized
    static Set<SwitchFeature> getFeatures(Switch sw) {
        database.getSwitch(sw.dpId).features
    }

    static List<Long> getDefaultCookies(Switch sw) {
        def swProps = northbound.getSwitchProperties(sw.dpId)
        def multiTableRules = []
        def devicesRules = []
        def server42Rules = []
        if (swProps.multiTable) {
            multiTableRules = [MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE, MULTITABLE_INGRESS_DROP_COOKIE,
                    MULTITABLE_POST_INGRESS_DROP_COOKIE, MULTITABLE_EGRESS_PASS_THROUGH_COOKIE,
                    MULTITABLE_TRANSIT_DROP_COOKIE, LLDP_POST_INGRESS_COOKIE, LLDP_POST_INGRESS_ONE_SWITCH_COOKIE,
                    ARP_POST_INGRESS_COOKIE, ARP_POST_INGRESS_ONE_SWITCH_COOKIE]
            if (sw.features.contains(SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN)
                    && sw.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)) {
                multiTableRules.addAll([LLDP_POST_INGRESS_VXLAN_COOKIE, ARP_POST_INGRESS_VXLAN_COOKIE])
            }
            northbound.getLinks(sw.dpId, null, null, null).each {
                if (sw.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)) {
                    multiTableRules.add(new PortColourCookie(CookieType.MULTI_TABLE_ISL_VXLAN_EGRESS_RULES, it.source.portNo).getValue())
                    multiTableRules.add(new PortColourCookie(CookieType.MULTI_TABLE_ISL_VXLAN_TRANSIT_RULES, it.source.portNo).getValue())
                }
                multiTableRules.add(new PortColourCookie(CookieType.MULTI_TABLE_ISL_VLAN_EGRESS_RULES, it.source.portNo).getValue())
            }
            northbound.getSwitchFlows(sw.dpId).each {
                if (it.source.datapath.equals(sw.dpId)) {
                    multiTableRules.add(new PortColourCookie(CookieType.MULTI_TABLE_INGRESS_RULES, it.source.portNumber).getValue())
                    if (swProps.switchLldp || it.source.detectConnectedDevices.lldp) {
                        devicesRules.add(new PortColourCookie(CookieType.LLDP_INPUT_CUSTOMER_TYPE, it.source.portNumber).getValue())
                    }
                    if (swProps.switchArp || it.source.detectConnectedDevices.arp) {
                        devicesRules.add(new PortColourCookie(CookieType.ARP_INPUT_CUSTOMER_TYPE, it.source.portNumber).getValue())
                    }
                }
                if (it.destination.datapath.equals(sw.dpId)) {
                    multiTableRules.add(new PortColourCookie(CookieType.MULTI_TABLE_INGRESS_RULES, it.destination.portNumber).getValue())
                    if (swProps.switchLldp || it.destination.detectConnectedDevices.lldp) {
                        devicesRules.add(new PortColourCookie(CookieType.LLDP_INPUT_CUSTOMER_TYPE, it.destination.portNumber).getValue())
                    }
                    if (swProps.switchArp || it.destination.detectConnectedDevices.arp) {
                        devicesRules.add(new PortColourCookie(CookieType.ARP_INPUT_CUSTOMER_TYPE, it.destination.portNumber).getValue())
                    }
                }
            }
        }
        if (swProps.switchLldp) {
            devicesRules.addAll([LLDP_INPUT_PRE_DROP_COOKIE, LLDP_TRANSIT_COOKIE, LLDP_INGRESS_COOKIE])
        }
        if (swProps.switchArp) {
            devicesRules.addAll([ARP_INPUT_PRE_DROP_COOKIE, ARP_TRANSIT_COOKIE, ARP_INGRESS_COOKIE])
        }
        if (swProps.server42FlowRtt) {
            server42Rules << SERVER_42_OUTPUT_VLAN_COOKIE
            if (sw.features.contains(SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN)) {
                server42Rules << SERVER_42_OUTPUT_VXLAN_COOKIE
            }
        }
        if (northbound.getFeatureToggles().server42FlowRtt) {
            if (sw.features.contains(SwitchFeature.NOVIFLOW_SWAP_ETH_SRC_ETH_DST)) {
                server42Rules << SERVER_42_TURNING_COOKIE
            }
        }
        if (sw.noviflow && !sw.wb5164) {
            return ([DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                     VERIFICATION_UNICAST_RULE_COOKIE, DROP_VERIFICATION_LOOP_RULE_COOKIE,
                     CATCH_BFD_RULE_COOKIE, ROUND_TRIP_LATENCY_RULE_COOKIE,
                     VERIFICATION_UNICAST_VXLAN_RULE_COOKIE] + multiTableRules + devicesRules + server42Rules)
        } else if((sw.noviflow || sw.nbFormat().manufacturer == "E") && sw.wb5164){
            return ([DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                     VERIFICATION_UNICAST_RULE_COOKIE, DROP_VERIFICATION_LOOP_RULE_COOKIE,
                     CATCH_BFD_RULE_COOKIE] + multiTableRules + devicesRules + server42Rules)
        } else if (sw.ofVersion == "OF_12") {
            return [VERIFICATION_BROADCAST_RULE_COOKIE]
        } else {
            return ([DROP_RULE_COOKIE, VERIFICATION_BROADCAST_RULE_COOKIE,
                     VERIFICATION_UNICAST_RULE_COOKIE, DROP_VERIFICATION_LOOP_RULE_COOKIE]
            + multiTableRules + devicesRules + server42Rules)
        }
    }

    static List<Long> getDefaultMeters(Switch sw) {
        if (sw.ofVersion == "OF_12") {
            return []
        }
        def swProps = northbound.getSwitchProperties(sw.dpId)
        List<MeterId> result = []
        result << MeterId.createMeterIdForDefaultRule(VERIFICATION_BROADCAST_RULE_COOKIE) //2
        result << MeterId.createMeterIdForDefaultRule(VERIFICATION_UNICAST_RULE_COOKIE) //3
        if (sw.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)) {
            result << MeterId.createMeterIdForDefaultRule(VERIFICATION_UNICAST_VXLAN_RULE_COOKIE) //7
        }
        if (swProps.multiTable) {
            result << MeterId.createMeterIdForDefaultRule(LLDP_POST_INGRESS_COOKIE) //16
            result << MeterId.createMeterIdForDefaultRule(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE) //18
            result << MeterId.createMeterIdForDefaultRule(ARP_POST_INGRESS_COOKIE) //22
            result << MeterId.createMeterIdForDefaultRule(ARP_POST_INGRESS_ONE_SWITCH_COOKIE) //24
            if (sw.features.contains(SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN)
                    && sw.features.contains(SwitchFeature.NOVIFLOW_COPY_FIELD)) {
                result << MeterId.createMeterIdForDefaultRule(LLDP_POST_INGRESS_VXLAN_COOKIE) //17
                result << MeterId.createMeterIdForDefaultRule(ARP_POST_INGRESS_VXLAN_COOKIE) //23
            }
        }
        if (swProps.switchLldp) {
            result << MeterId.createMeterIdForDefaultRule(LLDP_INPUT_PRE_DROP_COOKIE) //13
            result << MeterId.createMeterIdForDefaultRule(LLDP_TRANSIT_COOKIE) //14
            result << MeterId.createMeterIdForDefaultRule(LLDP_INGRESS_COOKIE) //15
        }
        if (swProps.switchArp) {
            result << MeterId.createMeterIdForDefaultRule(ARP_INPUT_PRE_DROP_COOKIE) //19
            result << MeterId.createMeterIdForDefaultRule(ARP_TRANSIT_COOKIE) //20
            result << MeterId.createMeterIdForDefaultRule(ARP_INGRESS_COOKIE) //21
        }
        return result*.getValue().sort()
    }

    static boolean isCentec(Switch sw) {
        sw.nbFormat().manufacturer.toLowerCase().contains("centec")
    }

    static boolean isNoviflow(Switch sw) {
        sw.nbFormat().manufacturer.toLowerCase().contains("noviflow")
    }

    static boolean isVirtual(Switch sw) {
        sw.nbFormat().manufacturer.toLowerCase().contains("nicira")
    }

    /**
     * A hardware with 100GB ports. Due to its nature sometimes requires special actions from Kilda
     */
    static boolean isWb5164(Switch sw) {
        sw.nbFormat().hardware =~ "WB5164"
    }

    @Memoized
    static String getDescription(SwitchId sw) {
        northbound.activeSwitches.find { it.switchId == sw }.description
    }

    /**
     * The same as direct northbound call, but additionally waits that default rules and default meters are indeed
     * reinstalled according to config
     */
    static SwitchPropertiesDto updateSwitchProperties(Switch sw, SwitchPropertiesDto switchProperties) {
        def response = northbound.updateSwitchProperties(sw.dpId, switchProperties)
        Wrappers.wait(Constants.RULES_INSTALLATION_TIME) {
            def actualHexCookie = []
            for (long cookie : northbound.getSwitchRules(sw.dpId).flowEntries*.cookie) {
                actualHexCookie.add(new Cookie(cookie).toString())
            }
            def expectedHexCookie = []
            for (long cookie : sw.defaultCookies) {
                expectedHexCookie.add(new Cookie(cookie).toString())
            }
            assertThat sw.toString(), actualHexCookie, containsInAnyOrder(expectedHexCookie.toArray())

            def actualDefaultMetersIds = northbound.getAllMeters(sw.dpId).meterEntries*.meterId.findAll {
                MeterId.isMeterIdOfDefaultRule((long) it)
            }
            assert actualDefaultMetersIds.sort() == sw.defaultMeters.sort()
        }
        return response
    }

    /**
     * This method calculates expected burst for different types of switches. The common burst equals to
     * `rate * burstCoefficient`. There are couple exceptions though:<br>
     * <b>Noviflow</b>: Does not use our common burst coefficient and overrides it with its own coefficient (see static
     * variables at the top of the class).<br>
     * <b>Centec</b>: Follows our burst coefficient policy, except for restrictions for the minimum and maximum burst.
     * In cases when calculated burst is higher or lower of the Centec max/min - the max/min burst value will be used
     * instead.
     *
     * @param sw switchId where burst is being calculated. Needed to get the switch manufacturer and apply special
     * calculations if required
     * @param rate meter rate which is used to calculate burst
     * @return the expected burst value for given switch and rate
     */
    def getExpectedBurst(SwitchId sw, long rate) {
        def descr = getDescription(sw).toLowerCase()
        def hardware = northbound.getSwitch(sw).hardware
        if (descr.contains("noviflow") || hardware =~ "WB5164") {
            return (rate * NOVIFLOW_BURST_COEFFICIENT - 1).setScale(0, RoundingMode.CEILING)
        } else if (descr.contains("centec")) {
            def burst = (rate * burstCoefficient).toBigDecimal().setScale(0, RoundingMode.FLOOR)
            if (burst <= CENTEC_MIN_BURST) {
                return CENTEC_MIN_BURST
            } else if (burst > CENTEC_MIN_BURST && burst <= CENTEC_MAX_BURST) {
                return burst
            } else {
                return CENTEC_MAX_BURST
            }
        } else {
            return (rate * burstCoefficient).round(0)
        }
    }

    /**
     * Verifies that specified meter sections in the validation response are empty.
     * NOTE: will filter out default meters for 'proper' section, so that switch without flow meters, but only with
     * default meters in 'proper' section is considered 'empty'
     */
    static void verifyMeterSectionsAreEmpty(SwitchValidationResult switchValidateInfo,
                                            List<String> sections = ["missing", "misconfigured", "proper", "excess"]) {
        if (switchValidateInfo.meters) {
            sections.each { section ->
                if (section == "proper") {
                    assert switchValidateInfo.meters.proper.findAll { !it.defaultMeter }.empty
                } else {
                    assert switchValidateInfo.meters."$section".empty
                }
            }
        }
    }

    /**
     * Verifies that specified rule sections in the validation response are empty.
     * NOTE: will filter out default rules, except default flow rules(multiTable flow)
     * Default flow rules for the system looks like as a simple default rule.
     * Based on that you have to use extra filter to detect these rules in missing/excess/misconfigured sections.
     */
    static void verifyRuleSectionsAreEmpty(SwitchValidationResult switchValidateInfo,
                                           List<String> sections = ["missing", "proper", "excess", "misconfigured"]) {
        sections.each { String section ->
            if (section == "proper") {
                assert switchValidateInfo.rules.proper.findAll {
                    def cookie = new Cookie(it)
                    !cookie.serviceFlag && cookie.type != CookieType.SHARED_OF_FLOW }.empty
            } else {
                assert switchValidateInfo.rules."$section".findAll {
                    def cookie = new Cookie(it)
                    cookie.type == CookieType.MULTI_TABLE_INGRESS_RULES || !cookie.serviceFlag
                }.empty
            }
        }
    }

    /**
     * Verifies that specified hexRule sections in the validation response are empty.
     * NOTE: will filter out default rules, except default flow rules(multiTable flow)
     * Default flow rules for the system looks like as a simple default rule.
     * Based on that you have to use extra filter to detect these rules in
     * missingHex/excessHex/misconfiguredHex sections.
     */
    static void verifyHexRuleSectionsAreEmpty(SwitchValidationResult switchValidateInfo,
                                           List<String> sections = ["properHex", "excessHex", "missingHex",
                                                                    "misconfiguredHex"]) {
        sections.each { String section ->
            if (section == "properHex") {
                def defaultCookies = switchValidateInfo.rules.proper.findAll {
                    def cookie = new Cookie(it)
                    cookie.serviceFlag || cookie.type == CookieType.SHARED_OF_FLOW
                }

                def defaultHexCookies = []
                defaultCookies.each { defaultHexCookies.add(Long.toHexString(it)) }
                assert switchValidateInfo.rules.properHex.findAll { !(it in defaultHexCookies) }.empty
            } else {
                def defaultCookies = switchValidateInfo.rules."$section".findAll {
                    def cookie = new Cookie(it)
                    cookie.serviceFlag || cookie.type != CookieType.MULTI_TABLE_INGRESS_RULES
                }

                def defaultHexCookies = []
                defaultCookies.each { defaultHexCookies.add(Long.toHexString(it)) }
                assert switchValidateInfo.rules."$section".findAll { !(it in defaultHexCookies) }.empty
            }
        }
    }

    static boolean isDefaultMeter(MeterInfoDto dto) {
        return MeterId.isMeterIdOfDefaultRule(dto.getMeterId())
    }

    /**
     * Verifies that actual and expected burst size are the same.
     */
    static void verifyBurstSizeIsCorrect(Switch sw, Long expected, Long actual) {
        if(sw.isWb5164()) {
            assert Math.abs(expected - actual) <= expected * 0.01
        } else {
            assert Math.abs(expected - actual) <= 1
        }
    }

    static SwitchProperties getDummyServer42Props() {
        return new SwitchProperties(true, 33, "00:00:00:00:00:00", 1)
    }

    /**
     * Waits for certain switch to appear/disappear from switch list in certain floodlights.
     * Useful when knocking out switches
     */
    static void waitForSwitchFlConnection(Switch sw, boolean shouldBePresent, List<Floodlight> floodlights) {
        Wrappers.wait(WAIT_OFFSET) { withPool {
                floodlights.eachParallel {
                    assert it.getFloodlightService().getSwitches()*.switchId.contains(sw.dpId) == shouldBePresent
                }
            } }
    }

    /**
     * Change switch to required table mode if it is not already in it.
     * Return original switch mode which was active before change.
     */
    static boolean changeMultitable(Switch sw, boolean requiredState) {
        def originalProps = northbound.getSwitchProperties(sw.dpId)
        if (originalProps.multiTable != requiredState) {
            northbound.updateSwitchProperties(sw.dpId, originalProps.jacksonCopy().tap {multiTable = requiredState })
        }
        return originalProps.multiTable
    }

    /**
     * Disconnect a switch from controller either removing controller settings inside an OVS switch
     * or blocking access to floodlight via iptables for a hardware switch.
     *
     * @param sw                    switch which is going to be disconnected
     * @param waitForRelatedLinks   make sure that all switch related ISLs are FAILED
     */
    List<FloodlightResourceAddress> knockoutSwitch(Switch sw, FloodlightConnectMode mode, boolean waitForRelatedLinks) {
        def blockData = lockKeeper.knockoutSwitch(sw, mode)
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getSwitch(sw.dpId).state == SwitchChangeType.DEACTIVATED
        }
        if (waitForRelatedLinks) {
            def swIsls = topology.getRelatedIsls(sw)

            Wrappers.wait(discoveryTimeout + WAIT_OFFSET) {
                def allIsls = northbound.getAllLinks()
                swIsls.each { assert islUtils.getIslInfo(allIsls, it).get().state == IslChangeType.FAILED }
            }
        }

        return blockData
    }

    List<FloodlightResourceAddress> knockoutSwitch(Switch sw, FloodlightConnectMode mode) {
        knockoutSwitch(sw, mode, false)
    }

    /**
     * Connect a switch to controller either adding controller settings inside an OVS switch
     * or setting proper iptables to allow access to floodlight for a hardware switch.
     *
     * @param sw                    switch which is going to be connected
     * @param waitForRelatedLinks   make sure that all switch related ISLs are DISCOVERED
     */
    void reviveSwitch(Switch sw, List<FloodlightResourceAddress> flResourceAddress, boolean waitForRelatedLinks) {
        lockKeeper.reviveSwitch(sw, flResourceAddress)
        Wrappers.wait(WAIT_OFFSET) {
            assert northbound.getSwitch(sw.dpId).state == SwitchChangeType.ACTIVATED
        }
        if (waitForRelatedLinks) {
            def swIsls = topology.getRelatedIsls(sw)

            Wrappers.wait(discoveryInterval + WAIT_OFFSET) {
                def allIsls = northbound.getAllLinks()
                swIsls.each {
                    assert islUtils.getIslInfo(allIsls, it).get().state == IslChangeType.DISCOVERED
                    assert islUtils.getIslInfo(allIsls, it.reversed).get().state == IslChangeType.DISCOVERED
                }
            }
        }
    }

    void reviveSwitch(Switch sw, List<FloodlightResourceAddress> flResourceAddress) {
        reviveSwitch(sw, flResourceAddress, false)
    }
}
