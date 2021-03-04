package org.openkilda.functionaltests.spec.flows

import static groovyx.gpars.GParsPool.withPool
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.model.MeterId.createMeterIdForDefaultRule
import static org.openkilda.model.cookie.Cookie.LLDP_INGRESS_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_INPUT_PRE_DROP_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_ONE_SWITCH_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_POST_INGRESS_VXLAN_COOKIE
import static org.openkilda.model.cookie.Cookie.LLDP_TRANSIT_COOKIE
import static org.openkilda.model.cookie.CookieBase.CookieType.ARP_INPUT_CUSTOMER_TYPE
import static org.openkilda.model.cookie.CookieBase.CookieType.LLDP_INPUT_CUSTOMER_TYPE
import static org.openkilda.testing.Constants.WAIT_OFFSET

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.IterationTag
import org.openkilda.functionaltests.extension.tags.IterationTags
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.SwitchHelper
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.functionaltests.helpers.model.SwitchPair
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.payload.flow.DetectConnectedDevicesPayload
import org.openkilda.messaging.payload.flow.FlowPayload
import org.openkilda.messaging.payload.flow.FlowState
import org.openkilda.model.Flow
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.MeterId
import org.openkilda.model.SwitchFeature
import org.openkilda.model.SwitchId
import org.openkilda.model.cookie.Cookie
import org.openkilda.northbound.dto.v1.flows.ConnectedDeviceDto
import org.openkilda.northbound.dto.v1.switches.SwitchPropertiesDto
import org.openkilda.northbound.dto.v2.switches.SwitchConnectedDeviceDto
import org.openkilda.testing.model.topology.TopologyDefinition.Switch
import org.openkilda.testing.service.traffexam.TraffExamService
import org.openkilda.testing.service.traffexam.model.ArpData
import org.openkilda.testing.service.traffexam.model.LldpData
import org.openkilda.testing.tools.ConnectedDevice

import com.github.javafaker.Faker
import groovy.transform.AutoClone
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.See
import spock.lang.Shared
import spock.lang.Unroll

import java.time.Instant
import javax.inject.Provider

@Slf4j
@Narrative("""
Verify ability to detect connected devices per flow endpoint (src/dst). 
Verify allocated Connected Devices resources and installed rules.""")
@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/connected-devices-lldp")
class ConnectedDevicesSpec extends HealthCheckSpecification {

    @Autowired @Shared
    Provider<TraffExamService> traffExamProvider

    @Unroll
    @Tags([TOPOLOGY_DEPENDENT])
    @IterationTags([
            @IterationTag(tags = [SMOKE, SMOKE_SWITCHES], iterationNameRegex = /srcLldp=true and dstLldp=true/),
            @IterationTag(tags = [HARDWARE], iterationNameRegex = /VXLAN/)
    ])
    def "Able to create a #flowDescr flow with lldp and arp enabled on #devicesDescr"() {
        assumeTrue(data.encapsulation != FlowEncapsulationType.VXLAN,
"Devices+VXLAN problem https://github.com/telstra/open-kilda/issues/3199")

        given: "A flow with enabled or disabled connected devices"
        def tgService = traffExamProvider.get()
        def flow = getFlowWithConnectedDevices(data)
        flow.encapsulationType = data.encapsulation.toString()

        and: "Switches with turned 'on' multiTable property"
        def initialSrcProps = enableMultiTableIfNeeded(data.srcEnabled, data.switchPair.src.dpId)
        def initialDstProps = enableMultiTableIfNeeded(data.dstEnabled, data.switchPair.dst.dpId)

        when: "Create a flow with connected devices"
        verifyAll(flowHelper.addFlow(flow)) {
            source.detectConnectedDevices.lldp == flow.source.detectConnectedDevices.lldp
            source.detectConnectedDevices.arp == flow.source.detectConnectedDevices.arp
            destination.detectConnectedDevices.lldp == flow.destination.detectConnectedDevices.lldp
            destination.detectConnectedDevices.arp == flow.destination.detectConnectedDevices.arp
        }

        then: "Flow and src/dst switches are valid"
        def createdFlow = database.getFlow(flow.id)
        validateFlowAndSwitches(createdFlow)

        and: "LLDP meters must be installed"
        validateLldpMeters(createdFlow)
        waitForDevicesInputRules(createdFlow)

        when: "Devices send lldp and arp packets on each flow endpoint"
        def srcLldpData = LldpData.buildRandom()
        def dstLldpData = LldpData.buildRandom()
        def srcArpData = ArpData.buildRandom()
        def dstArpData = ArpData.buildRandom()
        withPool {
            [[flow.source, srcLldpData, srcArpData], [flow.destination, dstLldpData, dstArpData]].eachParallel {
                endpoint, lldpData, arpData ->
                    new ConnectedDevice(tgService, topology.getTraffGen(endpoint.datapath), [endpoint.vlanId]).withCloseable {
                        it.sendLldp(lldpData)
                        it.sendArp(arpData)
                    }
            }
        }

        then: "Getting connecting devices shows corresponding devices on each endpoint if enabled"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
                it.source.lldp.size() == (data.srcEnabled ? 1 : 0)
                it.destination.lldp.size() == (data.dstEnabled ? 1 : 0)
                it.source.arp.size() == (data.srcEnabled ? 1 : 0)
                it.destination.arp.size() == (data.dstEnabled ? 1 : 0)
                data.srcEnabled ? verifyEquals(it.source.lldp.first(), srcLldpData) : true
                data.dstEnabled ? verifyEquals(it.destination.lldp.first(), dstLldpData) : true
                data.srcEnabled ? verifyEquals(it.source.arp.first(), srcArpData) : true
                data.dstEnabled ? verifyEquals(it.destination.arp.first(), dstArpData) : true
            }
        }

        and: "Delete the flow"
        flowHelper.deleteFlow(flow.id)

        then: "Delete action removed all rules and meters"
        Wrappers.wait(WAIT_OFFSET) {
            validateSwitchHasNoFlowRulesAndMeters(flow.source.datapath)
            validateSwitchHasNoFlowRulesAndMeters(flow.destination.datapath)
        }

        cleanup: "Restore initial switch properties"
        initialSrcProps && restoreSwitchProperties(data.switchPair.src.dpId, initialSrcProps)
        initialDstProps && restoreSwitchProperties(data.switchPair.dst.dpId, initialDstProps)
        srcLldpData && [data.switchPair.src, data.switchPair.dst].each { database.removeConnectedDevices(it.dpId) }

        where:
        data <<
                [
                        new ConnectedDeviceTestData(protectedFlow: false, oneSwitch: false,
                                srcEnabled: false, dstEnabled: true,
                                encapsulation: FlowEncapsulationType.TRANSIT_VLAN),
                        new ConnectedDeviceTestData(protectedFlow: true, oneSwitch: false,
                                srcEnabled: true, dstEnabled: false,
                                encapsulation: FlowEncapsulationType.TRANSIT_VLAN),
                        new ConnectedDeviceTestData(protectedFlow: false, oneSwitch: false,
                                srcEnabled: false, dstEnabled: true,
                                encapsulation: FlowEncapsulationType.VXLAN),
                        new ConnectedDeviceTestData(protectedFlow: true, oneSwitch: true,
                                srcEnabled: true, dstEnabled: false,
                                encapsulation: FlowEncapsulationType.VXLAN)
                        //now assign each of the above data pcs a relevant switch pair
                ].each { it.switchPair = getUniqueSwitchPairs()[0] } +
                //now add more data, below iterations will be run for all unique switches with TG attached
                [
                        new ConnectedDeviceTestData(protectedFlow: false, oneSwitch: false, srcEnabled: true,
                                dstEnabled: true, encapsulation: FlowEncapsulationType.TRANSIT_VLAN),
                        new ConnectedDeviceTestData(protectedFlow: false, oneSwitch: true, srcEnabled: true,
                                dstEnabled: true, encapsulation: FlowEncapsulationType.TRANSIT_VLAN),
                        new ConnectedDeviceTestData(protectedFlow: true, oneSwitch: false, srcEnabled: true,
                                dstEnabled: true, encapsulation: FlowEncapsulationType.VXLAN)
                        //each of the above datapieces may repeat multiple times depending on amount of available TG switches
                ].collectMany { dataPiece ->
                    getUniqueSwitchPairs().collect {
                        def newDataPiece = dataPiece.clone()
                        newDataPiece.switchPair = it
                        newDataPiece
                    }
                }
        flowDescr = sprintf("%s%s%s", data.encapsulation, data.protectedFlow ? " protected" : "",
                data.oneSwitch ? " oneSwitch" : "")
        devicesDescr = getDescr(data.srcEnabled, data.dstEnabled)
    }

    @AutoClone
    private static class ConnectedDeviceTestData {
        boolean protectedFlow, oneSwitch, srcEnabled, dstEnabled
        FlowEncapsulationType encapsulation
        SwitchPair switchPair
    }

    @Unroll
    def "Able to update flow from srcDevices=#oldSrcEnabled, dstDevices=#oldDstEnabled to \
srcDevices=#newSrcEnabled, dstDevices=#newDstEnabled"() {
        given: "Switches with turned 'on' multiTable property"
        def flow = getFlowWithConnectedDevices(true, false, oldSrcEnabled, oldDstEnabled)
        def initialSrcProps = enableMultiTableIfNeeded(oldSrcEnabled || newSrcEnabled, flow.source.datapath)
        def initialDstProps = enableMultiTableIfNeeded(oldDstEnabled || newDstEnabled, flow.destination.datapath)

        and: "Created flow with enabled or disabled connected devices"
        flowHelper.addFlow(flow)

        when: "Update the flow with connected devices"
        flow.source.detectConnectedDevices = new DetectConnectedDevicesPayload(newSrcEnabled, newSrcEnabled)
        flow.destination.detectConnectedDevices = new DetectConnectedDevicesPayload(newDstEnabled, newDstEnabled)
        flowHelper.updateFlow(flow.id, flow)

        then: "Flow and src/dst switches are valid"
        def updatedFlow = database.getFlow(flow.id)
        validateFlowAndSwitches(updatedFlow)

        and: "LLDP meters must be installed"
        validateLldpMeters(updatedFlow)
        waitForDevicesInputRules(updatedFlow)

        when: "Devices send lldp and arp packets on each flow endpoint"
        def srcLldpData = LldpData.buildRandom()
        def dstLldpData = LldpData.buildRandom()
        def srcArpData = ArpData.buildRandom()
        def dstArpData = ArpData.buildRandom()
        def tgService = traffExamProvider.get()
        withPool {
            [[flow.source, srcLldpData, srcArpData], [flow.destination, dstLldpData, dstArpData]].eachParallel {
                endpoint, lldpData, arpData ->
                    new ConnectedDevice(tgService, topology.getTraffGen(endpoint.datapath), [endpoint.vlanId]).withCloseable {
                        it.sendLldp(lldpData)
                        it.sendArp(arpData)
                    }
            }
        }

        then: "Getting connecting devices shows corresponding devices on each endpoint according to updated status"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
                it.source.lldp.size() == (newSrcEnabled ? 1 : 0)
                it.destination.lldp.size() == (newDstEnabled ? 1 : 0)
                it.source.arp.size() == (newSrcEnabled ? 1 : 0)
                it.destination.arp.size() == (newDstEnabled ? 1 : 0)
                newSrcEnabled ? verifyEquals(it.source.lldp.first(), srcLldpData) : true
                newDstEnabled ? verifyEquals(it.destination.lldp.first(), dstLldpData) : true
                newSrcEnabled ? verifyEquals(it.source.arp.first(), srcArpData) : true
                newDstEnabled ? verifyEquals(it.destination.arp.first(), dstArpData) : true
            }
        }

        cleanup: "Delete the flow"
        flowHelper.deleteFlow(updatedFlow.flowId)

        and: "Restore initial switch properties"
        restoreSwitchProperties(flow.source.datapath, initialSrcProps)
        restoreSwitchProperties(flow.destination.datapath, initialDstProps)
        [flow.source.datapath, flow.destination.datapath].each { database.removeConnectedDevices(it) }

        where:
        [oldSrcEnabled, oldDstEnabled, newSrcEnabled, newDstEnabled] << [
                [false, false, false, false],
                [true, true, true, true],
                [true, true, false, false],
                [false, false, true, true],
                [false, true, true, false],
                [true, true, false, true]
        ]
    }

    /**
     * This is an edge case. Other tests for 'oneSwitch' only test single-switch single-port scenarios
     */
    @Tags([SMOKE_SWITCHES])
    def "Able to detect devices on a single-switch different-port flow"() {
        given: "A flow between different ports on the same switch"
        assumeTrue(topology.activeTraffGens.size() > 0, "Require at least 1 switch with connected traffgen")
        def sw = topology.activeTraffGens*.switchConnected.first()
        def initialProps = enableMultiTableIfNeeded(true, sw.dpId)

        def flow = flowHelper.singleSwitchFlow(sw)
        flow.source.detectConnectedDevices = new DetectConnectedDevicesPayload(true, true)
        flowHelper.addFlow(flow)

        when: "Device connects to src endpoint and send lldp and arp packets"
        def lldpData = LldpData.buildRandom()
        def arpData = ArpData.buildRandom()
        new ConnectedDevice(traffExamProvider.get(), topology.getTraffGen(sw.dpId), [flow.source.vlanId]).withCloseable {
            it.sendLldp(lldpData)
            it.sendArp(arpData)
        }

        then: "LLDP and ARP connected devices are recognized and saved"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
                it.source.lldp.size() == 1
                it.source.arp.size() == 1
                it.destination.lldp.empty
                it.destination.arp.empty
                verifyEquals(it.source.lldp[0], lldpData)
                verifyEquals(it.source.arp[0], arpData)
            }
        }

        when: "Remove the flow"
        flowHelper.deleteFlow(flow.id)

        and: "Try to get connected devices for removed flow"
        northbound.getFlowConnectedDevices(flow.id)

        then: "Error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND

        cleanup: "Restore initial switch properties"
        restoreSwitchProperties(sw.dpId, initialProps)
        database.removeConnectedDevices(sw.dpId)
    }

    @Unroll
    def "Able to swap flow paths with connected devices (srcDevices=#srcEnabled, dstDevices=#dstEnabled)"() {
        given: "Switches with turned 'on' multiTable property"
        def flow = getFlowWithConnectedDevices(true, false, srcEnabled, dstEnabled)
        def initialSrcProps = enableMultiTableIfNeeded(srcEnabled, flow.source.datapath)
        def initialDstProps = enableMultiTableIfNeeded(dstEnabled, flow.destination.datapath)

        and: "Created protected flow with enabled or disabled connected devices"
        flowHelper.addFlow(flow)

        when: "Swap flow paths"
        northbound.swapFlowPath(flow.id)

        then: "Flow and src/dst switches are valid"
        Wrappers.wait(WAIT_OFFSET) { assert northboundV2.getFlowStatus(flow.id).status == FlowState.UP }
        def swappedFlow = database.getFlow(flow.id)
        validateFlowAndSwitches(swappedFlow)

        and: "LLDP meters must be installed"
        validateLldpMeters(swappedFlow)
        waitForDevicesInputRules(swappedFlow)

        when: "Devices send lldp and arp packets on each flow endpoint"
        def srcLldpData = LldpData.buildRandom()
        def dstLldpData = LldpData.buildRandom()
        def srcArpData = ArpData.buildRandom()
        def dstArpData = ArpData.buildRandom()
        def tgService = traffExamProvider.get()
        withPool {
            [[flow.source, srcLldpData, srcArpData], [flow.destination, dstLldpData, dstArpData]].eachParallel {
                endpoint, lldpData, arpData ->
                    new ConnectedDevice(tgService, topology.getTraffGen(endpoint.datapath), [endpoint.vlanId]).withCloseable {
                        it.sendLldp(lldpData)
                        it.sendArp(arpData)
                    }
            }
        }

        then: "Getting connecting devices shows corresponding devices on each endpoint"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
                it.source.lldp.size() == (srcEnabled ? 1 : 0)
                it.destination.lldp.size() == (dstEnabled ? 1 : 0)
                it.source.arp.size() == (srcEnabled ? 1 : 0)
                it.destination.arp.size() == (dstEnabled ? 1 : 0)
                srcEnabled ? verifyEquals(it.source.lldp.first(), srcLldpData) : true
                dstEnabled ? verifyEquals(it.destination.lldp.first(), dstLldpData) : true
                srcEnabled ? verifyEquals(it.source.arp.first(), srcArpData) : true
                dstEnabled ? verifyEquals(it.destination.arp.first(), dstArpData) : true
            }
        }

        cleanup: "Delete the flow"
        flowHelper.deleteFlow(swappedFlow.flowId)
        [flow.source.datapath, flow.destination.datapath].each { database.removeConnectedDevices(it) }

        and: "Restore initial switch properties"
        restoreSwitchProperties(flow.source.datapath, initialSrcProps)
        restoreSwitchProperties(flow.destination.datapath, initialDstProps)

        where:
        [srcEnabled, dstEnabled] << [
                [true, false],
                [true, true]
        ]
    }

    @Tidy
    def "Able to handle 'timeLastSeen' field when receive repeating LLDP packets from the same device"() {
        given: "Switches with turned 'on' multiTable property"
        def flow = getFlowWithConnectedDevices(false, false, true, false)
        def initialSrcProps = enableMultiTableIfNeeded(true, flow.source.datapath)

        and: "Created flow that detects connected devices"
        flowHelper.addFlow(flow)

        and: "A connected device"
        def device = new ConnectedDevice(traffExamProvider.get(), topology.getTraffGen(flow.source.datapath),
                [flow.source.vlanId])

        when: "Device sends lldp packet"
        def lldpData = LldpData.buildRandom()
        device.sendLldp(lldpData)

        then: "Device is registered for the flow, with timeLastSeen and timeFirstSeen values"
        def devices1 = Wrappers.retry(3, 0.5) {
            def devices = northbound.getFlowConnectedDevices(flow.id).source.lldp
            assert devices.size() == 1
            assert Instant.parse(devices[0].timeFirstSeen) == Instant.parse(devices[0].timeLastSeen)
            devices
        } as List<ConnectedDeviceDto>

        when: "Same packet is sent again"
        device.sendLldp(lldpData)

        then: "timeLastSeen is updated, timeFirstSeen remains the same"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            def devices = northbound.getFlowConnectedDevices(flow.id).source.lldp
            assert devices.size() == 1
            assert Instant.parse(devices[0].timeFirstSeen) == Instant.parse(devices1[0].timeFirstSeen)
            assert Instant.parse(devices[0].timeLastSeen) > Instant.parse(devices1[0].timeLastSeen)
        }

        cleanup: "Disconnect the device and remove the flow"
        flow && flowHelper.deleteFlow(flow.id)
        device && device.close()
        database.removeConnectedDevices(flow.source.datapath)

        and: "Restore initial switch properties"
        restoreSwitchProperties(flow.source.datapath, initialSrcProps)
    }

    @Tidy
    def "Able to handle 'timeLastSeen' field when receive repeating ARP packets from the same device"() {
        given: "Switches with turned 'on' multiTable property"
        def flow = getFlowWithConnectedDevices(false, false, true, false)
        def initialSrcProps = enableMultiTableIfNeeded(true, flow.source.datapath)

        and: "Created flow that detects connected devices"
        flowHelper.addFlow(flow)

        and: "A connected device"
        def device = new ConnectedDevice(traffExamProvider.get(), topology.getTraffGen(flow.source.datapath),
                [flow.source.vlanId])

        when: "Device sends ARP packet"
        def arpData = ArpData.buildRandom()
        device.sendArp(arpData)

        then: "Device is registered for the flow, with timeLastSeen and timeFirstSeen values"
        def devices1 = Wrappers.retry(3, 0.5) {
            def devices = northbound.getFlowConnectedDevices(flow.id).source.arp
            assert devices.size() == 1
            assert Instant.parse(devices[0].timeFirstSeen) == Instant.parse(devices[0].timeLastSeen)
            devices
        } as List<ConnectedDeviceDto>

        when: "Same packet is sent again"
        device.sendArp(arpData)

        then: "timeLastSeen is updated, timeFirstSeen remains the same"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            def devices = northbound.getFlowConnectedDevices(flow.id).source.arp
            assert devices.size() == 1
            assert Instant.parse(devices[0].timeFirstSeen) == Instant.parse(devices1[0].timeFirstSeen)
            assert Instant.parse(devices[0].timeLastSeen) > Instant.parse(devices1[0].timeLastSeen)
        }

        cleanup: "Disconnect the device and remove the flow"
        flow && flowHelper.deleteFlow(flow.id)
        device && device.close()
        database.removeConnectedDevices(flow.source.datapath)

        and: "Restore initial switch properties"
        restoreSwitchProperties(flow.source.datapath, initialSrcProps)
    }

    @Tidy
    def "Able to detect different devices on the same port (LLDP)"() {
        given: "Switches with turned 'on' multiTable property"
        def flow = getFlowWithConnectedDevices(false, false, false, true)
        def initialDstProps = enableMultiTableIfNeeded(true, flow.destination.datapath)

        and: "Created flow that detects connected devices"
        flowHelper.addFlow(flow)

        and: "A connected device"
        def device = new ConnectedDevice(traffExamProvider.get(), topology.getTraffGen(flow.destination.datapath),
                [flow.destination.vlanId])

        when: "Two completely different lldp packets are sent"
        def lldpData1 = LldpData.buildRandom()
        def lldpData2 = LldpData.buildRandom()
        device.sendLldp(lldpData1)
        device.sendLldp(lldpData2)

        then: "2 devices are registered for the flow"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            assert northbound.getFlowConnectedDevices(flow.id).destination.lldp.size() == 2
        }

        when: "Same device (same mac address) sends lldp packet with updated port number"
        device.sendLldp(lldpData1.tap {
            it.portNumber = (lldpData1.portNumber.toInteger() + 1).toString()
        })

        then: "Device is recognized as new one and total of 3 devices are registered for the flow"
        def foundDevices = Wrappers.retry(3, 0.5) {
            def devices = northbound.getFlowConnectedDevices(flow.id).destination.lldp
            assert devices.size() == 3
            devices
        } as List<ConnectedDeviceDto>

        when: "Request devices list with 'since' param equal to last registered device"
        def lastDevice = foundDevices.max { Instant.parse(it.timeLastSeen) }
        def filteredDevices = northbound.getFlowConnectedDevices(flow.id, lastDevice.timeLastSeen).destination.lldp

        then: "Only 1 device is returned (the latest registered)"
        filteredDevices.size() == 1
        filteredDevices.first() == lastDevice

        cleanup: "Disconnect the device and remove the flow"
        flow && flowHelper.deleteFlow(flow.id)
        device && device.close()
        database.removeConnectedDevices(flow.destination.datapath)

        and: "Restore initial switch properties"
        restoreSwitchProperties(flow.destination.datapath, initialDstProps)
    }

    @Tidy
    def "Able to detect different devices on the same port (ARP)"() {
        given: "Switches with turned 'on' multiTable property"
        def flow = getFlowWithConnectedDevices(false, false, false, true)
        def initialDstProps = enableMultiTableIfNeeded(true, flow.destination.datapath)

        and: "Created flow that detects connected devices"
        flowHelper.addFlow(flow)

        and: "A connected device"
        def device = new ConnectedDevice(traffExamProvider.get(), topology.getTraffGen(flow.destination.datapath),
                [flow.destination.vlanId])

        when: "Two completely different ARP packets are sent"
        def arpData1 = ArpData.buildRandom()
        def arpData2 = ArpData.buildRandom()
        device.sendArp(arpData1)
        device.sendArp(arpData2)

        then: "2 arp devices are registered for the flow"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            assert northbound.getFlowConnectedDevices(flow.id).destination.arp.size() == 2
        }

        when: "Same device (same IP address) sends ARP packet with updated mac address"
        //note that updating ip adress will also be considered a 'new' device
        device.sendArp(arpData1.tap {
            it.srcMac = new Faker().internet().macAddress("20")
        })

        then: "Device is recognized as new one and total of 3 arp devices are registered for the flow"
        def foundDevices = Wrappers.retry(3, 0.5) {
            def devices = northbound.getFlowConnectedDevices(flow.id).destination.arp
            assert devices.size() == 3
            devices
        } as List<ConnectedDeviceDto>

        when: "Request devices list with 'since' param equal to last registered device"
        def lastDevice = foundDevices.max { Instant.parse(it.timeLastSeen) }
        def filteredDevices = northbound.getFlowConnectedDevices(flow.id, lastDevice.timeLastSeen).destination.arp

        then: "Only 1 device is returned (the latest registered)"
        filteredDevices.size() == 1
        filteredDevices.first() == lastDevice

        cleanup: "Disconnect the device and remove the flow"
        flow && flowHelper.deleteFlow(flow.id)
        device && device.close()
        database.removeConnectedDevices(flow.destination.datapath)

        and: "Restore initial switch properties"
        restoreSwitchProperties(flow.destination.datapath, initialDstProps)
    }

    @Tidy
    def "System properly detects devices if feature is 'off' on switch level and 'on' on flow level"() {
        given: "A switch with devices feature turned off"
        assumeTrue(topology.activeTraffGens.size() > 0, "Require at least 1 switch with connected traffgen")
        def sw = topology.activeTraffGens[0].switchConnected
        def initialProps = enableMultiTableIfNeeded(true, sw.dpId)
        def swProps = northbound.getSwitchProperties(sw.dpId)
        assert !swProps.switchLldp
        assert !swProps.switchArp

        when: "Devices send arp and lldp packets into a free switch port"
        def lldpData = LldpData.buildRandom()
        def arpData = ArpData.buildRandom()
        def deviceVlan = 666
        def tg = topology.getTraffGen(sw.dpId)
        def device = new ConnectedDevice(traffExamProvider.get(), tg, [deviceVlan])
        device.sendLldp(lldpData)
        device.sendArp(arpData)

        then: "No devices are detected for the switch"
        Wrappers.timedLoop(2) {
            assert northboundV2.getConnectedDevices(sw.dpId).ports.empty
            sleep(50)
        }

        when: "Flow is created on a target switch with devices feature 'on'"
        def dst = topology.activeSwitches.find { it.dpId != sw.dpId }
        def flow = flowHelper.randomFlow(sw, dst).tap {
            it.source.detectConnectedDevices = new DetectConnectedDevicesPayload(true, true)
            it.source.vlanId = deviceVlan
        }
        flowHelper.addFlow(flow)

        and: "Device sends an lldp+arp packet into a flow port on that switch (with a correct flow vlan)"
        device.sendLldp(lldpData)
        device.sendArp(arpData)

        then: "LLDP and ARP devices are registered as flow devices"
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
                it.source.lldp.size() == 1
                verifyEquals(it.source.lldp.first(), lldpData)
                it.source.arp.size() == 1
                verifyEquals(it.source.arp.first(), arpData)
            }
        }

        and: "Devices are registered per-switch"
        verifyAll(northboundV2.getConnectedDevices(sw.dpId).ports) {
            it.size() == 1
            it[0].portNumber == tg.switchPort
            it[0].lldp.first().vlan == flow.source.vlanId
            it[0].lldp.first().flowId == flow.id
            verifyEquals(it[0].lldp.first(), lldpData)
            it[0].arp.first().vlan == flow.source.vlanId
            it[0].arp.first().flowId == flow.id
            verifyEquals(it[0].arp.first(), arpData)
        }

        cleanup: "Remove created flow and device"
        flow && flowHelper.deleteFlow(flow.id)
        device && device.close()
        database.removeConnectedDevices(sw.dpId)

        and: "Restore initial switch properties"
        restoreSwitchProperties(sw.dpId, initialProps)
    }

    @Tidy
    def "System properly detects devices if feature is 'on' on switch level and 'off' on flow level"() {
        given: "A switch with devices feature turned on"
        assumeTrue(topology.activeTraffGens.size() > 0, "Require at least 1 switch with connected traffgen")
        def tg = topology.activeTraffGens[0]
        def sw = tg.switchConnected
        def initialProps = northbound.getSwitchProperties(sw.dpId)
        switchHelper.updateSwitchProperties(sw, northbound.getSwitchProperties(sw.dpId).tap {
            it.multiTable = true
            it.switchLldp = true
            it.switchArp = true
        })

        and: "Flow is created on a target switch with devices feature 'off'"
        def dst = topology.activeSwitches.find { it.dpId != sw.dpId }
        def flow = flowHelper.randomFlow(sw, dst).tap {
            it.source.detectConnectedDevices = new DetectConnectedDevicesPayload(false, false)
        }
        flowHelper.addFlow(flow)

        when: "Devices send lldp and arp packets into a flow port"
        def lldpData = LldpData.buildRandom()
        def arpData = ArpData.buildRandom()
        new ConnectedDevice(traffExamProvider.get(), topology.getTraffGen(sw.dpId), [flow.source.vlanId]).withCloseable {
            it.sendLldp(lldpData)
            it.sendArp(arpData)
        }

        then: "ARP and LLDP devices are registered per-switch"
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northboundV2.getConnectedDevices(sw.dpId).ports) {
                it.size() == 1
                it[0].portNumber == tg.switchPort
                it[0].lldp.first().vlan == flow.source.vlanId
                it[0].lldp.first().flowId == flow.id
                verifyEquals(it[0].lldp.first(), lldpData)
                it[0].arp.first().vlan == flow.source.vlanId
                it[0].arp.first().flowId == flow.id
                verifyEquals(it[0].arp.first(), arpData)
            }
        }

        then: "Devices are registered as flow devices"
        verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
            it.source.lldp.size() == 1
            it.source.arp.size() == 1
            verifyEquals(it.source.lldp.first(), lldpData)
            verifyEquals(it.source.arp.first(), arpData)
        }

        cleanup: "Remove created flow and registered devices, revert switch props"
        flowHelper.deleteFlow(flow.id)
        database.removeConnectedDevices(sw.dpId)
        switchHelper.updateSwitchProperties(sw, initialProps)
    }

    @Tags([SMOKE_SWITCHES])
    def "Able to detect devices on free switch port (no flow or isl)"() {
        given: "A switch with devices feature turned on"
        assumeTrue(topology.activeTraffGens.size() > 0, "Require at least 1 switch with connected traffgen")
        def tg = topology.activeTraffGens[0]
        def sw = tg.switchConnected
        def initialProps = northbound.getSwitchProperties(sw.dpId)
        switchHelper.updateSwitchProperties(sw, northbound.getSwitchProperties(sw.dpId).tap {
            it.multiTable = true
            it.switchLldp = true
            it.switchArp = true
        })

        when: "Devices send lldp and arp packets into a free port"
        def lldpData = LldpData.buildRandom()
        def arpData = ArpData.buildRandom()
        def vlan = 123
        new ConnectedDevice(traffExamProvider.get(), tg, [vlan]).withCloseable {
            it.sendLldp(lldpData)
            it.sendArp(arpData)
        }

        then: "Corresponding devices are detected on a switch port"
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northboundV2.getConnectedDevices(sw.dpId).ports.find { it.portNumber == tg.switchPort }) { port ->
                port.portNumber == tg.switchPort
                port.lldp.first().vlan == vlan
                verifyEquals(port.lldp.first(), lldpData)
                port.arp.first().vlan == vlan
                verifyEquals(port.arp.first(), arpData)
            }
        }

        cleanup: "Turn off devices prop, remove connected devices"
        switchHelper.updateSwitchProperties(sw, initialProps)
        database.removeConnectedDevices(sw.dpId)
    }

    @Unroll
    def "Able to distinguish devices between default and non-default single-switch flows (#descr)"() {
        given: "A switch with devices feature turned on"
        assumeTrue(topology.activeTraffGens.size() > 0, "Require at least 1 switch with connected traffgen")
        def tg = topology.activeTraffGens[0]
        def sw = tg.switchConnected
        def initialProps = northbound.getSwitchProperties(sw.dpId)
        switchHelper.updateSwitchProperties(sw, northbound.getSwitchProperties(sw.dpId).tap {
            it.multiTable = true
            it.switchLldp = true
            it.switchArp = true
        })

        and: "A single-sw flow with devices feature 'on'"
        def flow = flowHelper.randomFlow(sw, sw).tap {
            it.source.detectConnectedDevices = new DetectConnectedDevicesPayload(true, true)
        }
        flowHelper.addFlow(flow)

        and: "A single-sw default flow with devices feature 'on'"
        def defaultFlow = flowHelper.randomFlow(sw, sw, true, [flow]).tap {
            it.source.detectConnectedDevices = new DetectConnectedDevicesPayload(true, true)
            it.source.portNumber = flow.source.portNumber
            srcDefault && (it.source.vlanId = 0)
            dstDefault && (it.destination.vlanId = 0)
        }
        flowHelper.addFlow(defaultFlow)

        when: "Devices send lldp and arp packets into a flow port with flow vlan"
        def lldpData = LldpData.buildRandom()
        def arpData = ArpData.buildRandom()
        new ConnectedDevice(traffExamProvider.get(), tg, [flow.source.vlanId]).withCloseable {
            it.sendLldp(lldpData)
            it.sendArp(arpData)
        }

        then: "Corresponding devices are detected on a switch port with reference to corresponding non-default flow"
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northboundV2.getConnectedDevices(sw.dpId).ports) {
                it.size() == 1
                it[0].portNumber == tg.switchPort
                it[0].lldp.first().flowId == flow.id
                it[0].lldp.first().vlan == flow.source.vlanId
                verifyEquals(it[0].lldp.first(), lldpData)
                it[0].arp.first().flowId == flow.id
                it[0].arp.first().vlan == flow.source.vlanId
                verifyEquals(it[0].arp.first(), arpData)
            }
        }

        and: "Devices are also visible as flow devices"
        verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
            it.source.lldp.size() == 1
            verifyEquals(it.source.lldp.first(), lldpData)
            it.source.arp.size() == 1
            verifyEquals(it.source.arp.first(), arpData)
        }

        and: "Devices are NOT visible as a default flow devices"
        def flowDevices = northbound.getFlowConnectedDevices(defaultFlow.id)
        flowDevices.source.lldp.empty
        flowDevices.source.arp.empty

        when: "Other devices send lldp and arp packets into a flow port with vlan different from flow vlan"
        def lldpData2 = LldpData.buildRandom()
        def arpData2 = ArpData.buildRandom()
        def vlan = flow.source.vlanId - 1
        new ConnectedDevice(traffExamProvider.get(), tg, [vlan]).withCloseable {
            it.sendLldp(lldpData2)
            it.sendArp(arpData2)
        }

        then: "Corresponding devices are detected on a switch port with reference to corresponding default flow"
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northboundV2.getConnectedDevices(sw.dpId).ports) {
                it.size() == 1
                it[0].portNumber == tg.switchPort
                it[0].lldp.size() == 2
                it[0].lldp.last().flowId == defaultFlow.id
                it[0].lldp.last().vlan == vlan
                verifyEquals(it[0].lldp.last(), lldpData2)
                it[0].arp.size() == 2
                it[0].arp.last().flowId == defaultFlow.id
                it[0].arp.last().vlan == vlan
                verifyEquals(it[0].arp.last(), arpData2)
            }
        }

        and: "Devices are not visible as a flow devices"
        //it's a previous devices, nothing changed
        verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
            it.source.lldp.size() == 1
            verifyEquals(it.source.lldp.first(), lldpData)
            it.source.arp.size() == 1
            verifyEquals(it.source.arp.first(), arpData)
        }

        and: "Devices are visible as a default flow devices"
        verifyAll(northbound.getFlowConnectedDevices(defaultFlow.id)) {
            it.source.lldp.size() == 1
            verifyEquals(it.source.lldp.first(), lldpData2)
            it.source.arp.size() == 1
            verifyEquals(it.source.arp.first(), arpData2)
        }


        when: "Other devices send lldp and arp packets into a flow port with double vlans different from flow vlan"
        def lldpData3 = LldpData.buildRandom()
        def arpData3 = ArpData.buildRandom()
        def vlans = [vlan - 1, vlan - 2]
        new ConnectedDevice(traffExamProvider.get(), tg, vlans).withCloseable {
            it.sendLldp(lldpData3)
            it.sendArp(arpData3)
        }

        then: "Corresponding devices are detected on a switch port with reference to corresponding default flow"
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northboundV2.getConnectedDevices(sw.dpId).ports) {
                it.size() == 1
                it[0].portNumber == tg.switchPort
                it[0].lldp.size() == 3
                it[0].lldp.last().flowId == defaultFlow.id
                it[0].lldp.last().vlan == vlans[0] //due to issue 3475
                verifyEquals(it[0].lldp.last(), lldpData3)
                it[0].arp.size() == 3
                it[0].arp.last().flowId == defaultFlow.id
                it[0].arp.last().vlan == vlans[0] //due to issue 3475
                verifyEquals(it[0].arp.last(), arpData3)
            }
        }

        and: "Devices are not visible as a flow devices"
        //it's a previous devices, nothing changed
        verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
            it.source.lldp.size() == 1
            it.source.arp.size() == 1
        }

        and: "Devices are visible as a default flow devices"
        verifyAll(northbound.getFlowConnectedDevices(defaultFlow.id)) {
            it.source.lldp.size() == 2
            verifyEquals(it.source.lldp.sort { it.timeFirstSeen }.last(), lldpData3)
            it.source.arp.size() == 2
            verifyEquals(it.source.arp.sort { it.timeFirstSeen }.last(), arpData3)
        }

        cleanup: "Turn off devices prop, remove connected devices, remove flow"
        flowHelper.deleteFlow(flow.id)
        flowHelper.deleteFlow(defaultFlow.id)
        database.removeConnectedDevices(sw.dpId)
        switchHelper.updateSwitchProperties(sw, initialProps)

        where:
        srcDefault | dstDefault
        true       | false
        true       | true

        descr = "default ${getDescr(srcDefault, dstDefault)}"
    }

    @Unroll
    def "System properly detects device vlan in case of #descr"() {
        given: "Switches with turned 'on' multiTable property"
        def flow = getFlowWithConnectedDevices(true, false, true, true).tap {
            srcDefault && (it.source.vlanId = 0)
            dstDefault && (it.destination.vlanId = 0)
        }
        def srcTg = topology.activeTraffGens.find { it.switchConnected.dpId == flow.source.datapath }
        def dstTg = topology.activeTraffGens.find { it.switchConnected.dpId == flow.destination.datapath }
        def initialSrcProps = enableMultiTableIfNeeded(true, flow.source.datapath)
        def initialDstProps = enableMultiTableIfNeeded(true, flow.destination.datapath)

        and: "A flow with enbaled connected devices, #descr"
        flowHelper.addFlow(flow)
        waitForDevicesInputRules(database.getFlow(flow.id))

        when: "Devices send lldp and arp packets on each flow endpoint"
        def srcLldpData = LldpData.buildRandom()
        def dstLldpData = LldpData.buildRandom()
        def srcArpData = ArpData.buildRandom()
        def dstArpData = ArpData.buildRandom()
        def nonDefaultVlan = 777 //use this vlan if flow endpoint is 'default' and should catch any vlan
        def tgService = traffExamProvider.get()
        withPool {
            [[flow.source, srcLldpData, srcArpData], [flow.destination, dstLldpData, dstArpData]].eachParallel {
                endpoint, lldpData, arpData ->
                    new ConnectedDevice(tgService, topology.getTraffGen(endpoint.datapath),
                            [endpoint.vlanId ?: nonDefaultVlan]).withCloseable {
                        it.sendLldp(lldpData)
                        it.sendArp(arpData)
                    }
            }
        }

        then: "Devices are registered for the flow on src and dst"
        Wrappers.wait(WAIT_OFFSET) {
            verifyAll(northbound.getFlowConnectedDevices(flow.id)) {
                it.source.lldp.size() == 1
                it.destination.lldp.size() == 1
                verifyEquals(it.source.lldp.first(), srcLldpData)
                verifyEquals(it.destination.lldp.first(), dstLldpData)
                it.source.arp.size() == 1
                it.destination.arp.size() == 1
                verifyEquals(it.source.arp.first(), srcArpData)
                verifyEquals(it.destination.arp.first(), dstArpData)
            }
        }

        and: "Devices are registered on src switch"
        verifyAll(northboundV2.getConnectedDevices(flow.source.datapath).ports) {
            it.size() == 1
            it[0].portNumber == srcTg.switchPort
            it[0].lldp.size() == 1
            it[0].lldp.first().flowId == flow.id
            it[0].lldp.first().vlan == (flow.source.vlanId ?: nonDefaultVlan)
            verifyEquals(it[0].lldp.first(), srcLldpData)
            it[0].arp.size() == 1
            it[0].arp.first().flowId == flow.id
            it[0].arp.first().vlan == (flow.source.vlanId ?: nonDefaultVlan)
            verifyEquals(it[0].arp.first(), srcArpData)
        }

        and: "Device are registered on dst switch"
        verifyAll(northboundV2.getConnectedDevices(flow.destination.datapath).ports) {
            it.size() == 1
            it[0].portNumber == dstTg.switchPort
            it[0].lldp.size() == 1
            it[0].lldp.first().flowId == flow.id
            it[0].lldp.first().vlan == (flow.destination.vlanId ?: nonDefaultVlan)
            verifyEquals(it[0].lldp.first(), dstLldpData)
            it[0].arp.size() == 1
            it[0].arp.first().flowId == flow.id
            it[0].arp.first().vlan == (flow.destination.vlanId ?: nonDefaultVlan)
            verifyEquals(it[0].arp.first(), dstArpData)
        }

        cleanup: "Delete the flow"
        flowHelper.deleteFlow(flow.id)

        and: "Restore initial switch properties"
        restoreSwitchProperties(flow.source.datapath, initialSrcProps)
        restoreSwitchProperties(flow.destination.datapath, initialDstProps)
        [flow.source.datapath, flow.destination.datapath].each { database.removeConnectedDevices(it) }

        where:
        srcDefault | dstDefault
        false      | true
        true       | true

        descr = "default(no-vlan) flow endpoints on ${getDescr(srcDefault, dstDefault)}"
    }

    @Tidy
    @Unroll
    def "System forbids to turn on '#propertyToTurnOn' on a single-table-mode switch"() {
        when: "Try to change switch props so that connected devices are 'on' but switch is in a single-table mode"
        def sw = topology.activeSwitches.first()
        def initSwitchProperties = northbound.getSwitchProperties(sw.dpId)
        northbound.updateSwitchProperties(sw.dpId, northbound.getSwitchProperties(sw.dpId).tap {
            it.multiTable = false
            it."$propertyToTurnOn" = true
        })

        then: "Bad request error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.BAD_REQUEST
        e.responseBodyAsString.to(MessageError).errorMessage == "Illegal switch properties combination for switch " +
                "$sw.dpId. '$propertyToTurnOn' property can be set to 'true' only if 'multiTable' property is 'true'."

        cleanup:
        !e && switchHelper.updateSwitchProperties(sw, initSwitchProperties)

        where:
        propertyToTurnOn << ["switchLldp", "switchArp"]
    }

    @Tidy
    def "System forbids to turn on 'connected devices per flow' on a single-table-mode switch"() {
        given: "Switch in single-table mode"
        def swPair = topologyHelper.switchPairs.first()
        def sw = swPair.src
        def initProps = northbound.getSwitchProperties(sw.dpId)
        SwitchHelper.updateSwitchProperties(sw, initProps.jacksonCopy().tap {
            it.multiTable = false
        })

        when: "Try to create an lldp-enabled flow using single-table switch as src"
        def flow = flowHelper.randomFlow(swPair).tap {
            it.source.detectConnectedDevices = new DetectConnectedDevicesPayload(true, true)
        }
        northbound.addFlow(flow)

        then: "Bad request error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.BAD_REQUEST
        e.responseBodyAsString.to(MessageError).errorDescription == "Catching of LLDP/ARP packets supported only on " +
                "switches with enabled 'multiTable' switch feature. This feature is disabled on switch $sw.dpId."

        cleanup: "Restore switch props"
        flow && !e && flowHelper.deleteFlow(flow.id)
        SwitchHelper.updateSwitchProperties(sw, initProps)
    }

    @Tidy
    def "System forbids to turn off multi-table mode if switch has an lldp-enabled flow"() {
        given: "Switch in multi-table mode"
        def swPair = topologyHelper.switchPairs.find { it.src.features.contains(SwitchFeature.MULTI_TABLE) }
        def sw = swPair.src
        def initProps = enableMultiTableIfNeeded(true, sw.dpId)

        when: "Create an lldp-enabled flow"
        def flow = flowHelper.randomFlow(swPair).tap {
            it.source.detectConnectedDevices = new DetectConnectedDevicesPayload(true, false)
        }
        flowHelper.addFlow(flow)

        and: "Try disabling multi-table mode on a switch where lldp-per-flow is being enabled"
        northbound.updateSwitchProperties(sw.dpId, initProps.jacksonCopy().tap { it.multiTable = false })

        then: "Error is returned, stating that this operation cannot be performed"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.BAD_REQUEST
        e.responseBodyAsString.to(MessageError).errorDescription == "Failed to update switch properties."
        e.responseBodyAsString.to(MessageError).errorMessage == "Illegal switch properties combination for switch $sw.dpId. " +
                "Detect Connected Devices feature is turn on for following flows [$flow.id]. " +
                "For correct work of this feature switch property 'multiTable' must be set to 'true' " +
                "Please disable detecting of connected devices via LLDP for each flow before set 'multiTable' property to 'false'"

        cleanup: "Restore switch props"
        flow && flowHelper.deleteFlow(flow.id)
        SwitchHelper.updateSwitchProperties(sw, initProps)
    }

    @Unroll
    @Tags([TOPOLOGY_DEPENDENT])
    @IterationTag(tags = [HARDWARE], iterationNameRegex = /VXLAN/)
    def "System detects devices for a qinq(iVlan=#vlanId oVlan=#innerVlanId) flow with lldp and arp enabled on the src switch"() {
        assumeTrue(encapsulationType != FlowEncapsulationType.VXLAN,
"Devices+VXLAN problem https://github.com/telstra/open-kilda/issues/3199")

        given: "Two switches connected to traffgen and they support enabled multiTable mode"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue((allTraffGenSwitches.size() > 1), "Unable to find two active traffgens")
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { sw ->
                sw.dpId in allTraffGenSwitches*.dpId && sw.features.contains(SwitchFeature.MULTI_TABLE)
            }
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A QinQ flow with enabled connected devices"
        def tgService = traffExamProvider.get()
        def flow = flowHelperV2.randomFlow(swP)
        flow.source.vlanId = vlanId
        flow.source.innerVlanId = innerVlanId
        flow.encapsulationType = encapsulationType
        flow.source.detectConnectedDevices.arp = true
        flow.source.detectConnectedDevices.lldp = true

        and: "Switches with turned 'on' multiTable property"
        def initialSrcProps = enableMultiTableIfNeeded(true, swP.src.dpId)
        def initialDstProps = enableMultiTableIfNeeded(false, swP.dst.dpId)

        when: "Create a flow with connected devices"
        flowHelperV2.addFlow(flow)

        then: "Flow and src/dst switches are valid"
        def createdFlow = database.getFlow(flow.flowId)
        validateFlowAndSwitches(createdFlow)

        and: "LLDP meters must be installed"
        validateLldpMeters(createdFlow)
        waitForDevicesInputRules(createdFlow)

        when: "Devices send lldp and arp packets on each flow endpoint"
        def srcLldpData = LldpData.buildRandom()
        def dstLldpData = LldpData.buildRandom()
        def srcArpData = ArpData.buildRandom()
        def dstArpData = ArpData.buildRandom()
        withPool {
            [[flow.source, srcLldpData, srcArpData], [flow.destination, dstLldpData, dstArpData]].eachParallel {
                endpoint, lldpData, arpData ->
                    new ConnectedDevice(tgService, topology.getTraffGen(endpoint.switchId),
                            [endpoint.vlanId, endpoint.innerVlanId]).withCloseable {
                        it.sendLldp(lldpData)
                        it.sendArp(arpData)
                    }
            }
        }

        then: "Getting connecting devices shows corresponding devices on src endpoint"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            verifyAll(northbound.getFlowConnectedDevices(flow.flowId)) {
                it.source.lldp.size() == 1
                it.source.arp.size() == 1
                it.destination.lldp.empty
                it.destination.arp.empty
                verifyEquals(it.source.lldp.first(), srcLldpData)
                verifyEquals(it.source.arp.first(), srcArpData)
            }
        }

        and: "Devices are registered on the src switch only"
        verifyAll(northboundV2.getConnectedDevices(flow.source.switchId).ports) {
            it.size() == 1
            it[0].portNumber == flow.source.portNumber
            it[0].lldp.size() == 1
            it[0].lldp.first().flowId == flow.flowId
            it[0].lldp.first().vlan == (flow.source.vlanId ? flow.source.vlanId : innerVlanId) //due to issue 3475
            verifyEquals(it[0].lldp.first(), srcLldpData)
            it[0].arp.size() == 1
            it[0].arp.first().flowId == flow.flowId
            it[0].arp.first().vlan == (flow.source.vlanId ? flow.source.vlanId : innerVlanId) //due to issue 3475
            verifyEquals(it[0].arp.first(), srcArpData)
        }
        northboundV2.getConnectedDevices(flow.destination.switchId).ports.empty

        and: "Delete the flow"
        flowHelperV2.deleteFlow(flow.flowId)
        def flowIsDeleted = true

        and: "Delete action removed all rules and meters"
        Wrappers.wait(WAIT_OFFSET) {
            validateSwitchHasNoFlowRulesAndMeters(swP.src.dpId)
        }

        cleanup: "Restore initial switch properties"
        !flowIsDeleted && flowHelperV2.deleteFlow(flow.flowId)
        initialSrcProps && restoreSwitchProperties(swP.src.dpId, initialSrcProps)
        initialDstProps && restoreSwitchProperties(swP.dst.dpId, initialDstProps)
        srcLldpData && database.removeConnectedDevices(swP.src.dpId)

        where:
        vlanId | innerVlanId | encapsulationType
        0      | 200         | FlowEncapsulationType.TRANSIT_VLAN
        100    | 200         | FlowEncapsulationType.TRANSIT_VLAN
        0      | 200         | FlowEncapsulationType.VXLAN
        100    | 200         | FlowEncapsulationType.VXLAN
    }

    @Unroll
    def "System doesn't detect devices only if vlan match with outerVlan of qinq flow"() {
        given: "Two switches connected to traffgen and they support enabled multiTable mode"
        def allTraffGenSwitches = topology.activeTraffGens*.switchConnected
        assumeTrue((allTraffGenSwitches.size() > 1), "Unable to find two active traffgens")
        def swP = topologyHelper.getAllNeighboringSwitchPairs().find {
            [it.src, it.dst].every { sw ->
                sw.dpId in allTraffGenSwitches*.dpId && sw.features.contains(SwitchFeature.MULTI_TABLE)
            }
        } ?: assumeTrue(false, "No suiting switches found")

        and: "A QinQ flow with enabled connected devices"
        def tgService = traffExamProvider.get()
        def flow = flowHelperV2.randomFlow(swP)
        def outerVlan = 100
        flow.source.vlanId = outerVlan
        flow.source.innerVlanId = 200
        flow.source.detectConnectedDevices.arp = true
        flow.source.detectConnectedDevices.lldp = true

        and: "Switches with turned 'on' multiTable property"
        def initialSrcProps = enableMultiTableIfNeeded(true, swP.src.dpId)
        def initialDstProps = enableMultiTableIfNeeded(false, swP.dst.dpId)

        flowHelperV2.addFlow(flow)
        waitForDevicesInputRules(database.getFlow(flow.flowId))

        when: "Devices send lldp and arp packets on src flow endpoint and match outerVlan only"
        def srcLldpData = LldpData.buildRandom()
        def dstLldpData = LldpData.buildRandom()
        def srcArpData = ArpData.buildRandom()
        def dstArpData = ArpData.buildRandom()
        withPool {
            [[flow.source, srcLldpData, srcArpData], [flow.destination, dstLldpData, dstArpData]].eachParallel {
                endpoint, lldpData, arpData ->
                    new ConnectedDevice(tgService, topology.getTraffGen(endpoint.switchId), [outerVlan]).withCloseable {
                        it.sendLldp(lldpData)
                        it.sendArp(arpData)
                    }
            }
        }

        then: "Getting connecting devices doesn't show corresponding devices on src endpoint"
        Wrappers.timedLoop(3) {
            //under usual condition system needs some time for devices to appear, that's why timeLoop is used here
            verifyAll(northbound.getFlowConnectedDevices(flow.flowId)) {
                it.source.lldp.empty
                it.source.arp.empty
                it.destination.lldp.empty
                it.destination.arp.empty
            }
        }

        and: "Devices are not registered on the src/dst switches"
        northboundV2.getConnectedDevices(flow.source.switchId).ports.empty
        northboundV2.getConnectedDevices(flow.destination.switchId).ports.empty

        and: "Delete the flow"


        cleanup: "Restore initial switch properties"
        flow && flowHelperV2.deleteFlow(flow.flowId)
        initialSrcProps && restoreSwitchProperties(swP.src.dpId, initialSrcProps)
        initialDstProps && restoreSwitchProperties(swP.dst.dpId, initialDstProps)
    }

    @Unroll
    @Tags([SMOKE_SWITCHES])
    def "Able to detect devices on a qinq single-switch different-port flow"() {
        given: "A flow between different ports on the same switch"
        assumeTrue(topology.activeTraffGens.size() > 0, "Require at least 1 switch with connected traffgen")
        def sw = topology.activeTraffGens*.switchConnected.first()
        def initialProps = enableMultiTableIfNeeded(true, sw.dpId)

        def flow = flowHelperV2.singleSwitchFlow(sw)
        flow.source.detectConnectedDevices.lldp = true
        flow.source.detectConnectedDevices.arp = true
        flow.source.vlanId = vlanId
        flow.source.innerVlanId = innerVlanId
        flow.encapsulationType = encapsulationType
        flowHelperV2.addFlow(flow)

        when: "Device connects to src endpoint and send lldp and arp packets"
        def lldpData = LldpData.buildRandom()
        def arpData = ArpData.buildRandom()
        new ConnectedDevice(traffExamProvider.get(), topology.getTraffGen(sw.dpId),
                [flow.source.vlanId, flow.source.innerVlanId]).withCloseable {
            it.sendLldp(lldpData)
            it.sendArp(arpData)
        }

        then: "LLDP and ARP connected devices are recognized and saved"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            verifyAll(northbound.getFlowConnectedDevices(flow.flowId)) {
                it.source.lldp.size() == 1
                it.source.arp.size() == 1
                it.destination.lldp.empty
                it.destination.arp.empty
                verifyEquals(it.source.lldp[0], lldpData)
                verifyEquals(it.source.arp[0], arpData)
            }
        }

        and: "Devices are registered on the switch"
        verifyAll(northboundV2.getConnectedDevices(flow.source.switchId).ports) {
            it.size() == 1
            it[0].portNumber == flow.source.portNumber
            it[0].lldp.size() == 1
            it[0].lldp.first().flowId == flow.flowId
            it[0].lldp.first().vlan == (flow.source.vlanId ? flow.source.vlanId : innerVlanId) //due to issue 3475
            verifyEquals(it[0].lldp.first(), lldpData)
            it[0].arp.size() == 1
            it[0].arp.first().flowId == flow.flowId
            it[0].arp.first().vlan == (flow.source.vlanId ? flow.source.vlanId : innerVlanId) //due to issue 3475
            verifyEquals(it[0].arp.first(), arpData)
        }

        cleanup: "Restore initial switch properties"
        flow && flowHelperV2.deleteFlow(flow.flowId)
        initialProps && restoreSwitchProperties(sw.dpId, initialProps)
        sw && database.removeConnectedDevices(sw.dpId)

        where:
        vlanId | innerVlanId | encapsulationType
        0      | 200         | FlowEncapsulationType.TRANSIT_VLAN
        100    | 200         | FlowEncapsulationType.TRANSIT_VLAN
        0      | 200         | FlowEncapsulationType.VXLAN
        100    | 200         | FlowEncapsulationType.VXLAN
    }

    @Unroll
    @Tags([SMOKE_SWITCHES])
    def "Able to detect devices when two qinq single-switch different-port flows exist with the same outerVlanId"() {
        given: "Two flows between different ports on the same switch with the same outerVlanId"
        assumeTrue(topology.activeTraffGens.size() > 0, "Require at least 1 switch with connected traffgen")
        def sw = topology.activeTraffGens*.switchConnected.first()
        def initialProps = enableMultiTableIfNeeded(true, sw.dpId)

        def flow1 = flowHelperV2.singleSwitchFlow(sw, true)
        flow1.source.detectConnectedDevices.lldp = true
        flow1.source.detectConnectedDevices.arp = true
        flow1.source.vlanId = commonOuterVlanId
        flow1.source.innerVlanId = innerVlanIdFlow1
        flow1.encapsulationType = encapsulationType
        flowHelperV2.addFlow(flow1)

        def flow2 = flowHelperV2.singleSwitchFlow(sw, true, [flow1])
        flow2.source.detectConnectedDevices.lldp = true
        flow2.source.detectConnectedDevices.arp = true
        flow2.source.vlanId = commonOuterVlanId
        flow2.source.innerVlanId = innerVlanIdFlow2
        flow2.encapsulationType = encapsulationType
        flowHelperV2.addFlow(flow2)

        when: "Device connects to src endpoint and send lldp and arp packets for flow1 only"
        def lldpData = LldpData.buildRandom()
        def arpData = ArpData.buildRandom()
        new ConnectedDevice(traffExamProvider.get(), topology.getTraffGen(sw.dpId),
                [flow1.source.vlanId, flow1.source.innerVlanId]).withCloseable {
            it.sendLldp(lldpData)
            it.sendArp(arpData)
        }

        then: "LLDP and ARP connected devices are recognized for flow1"
        Wrappers.wait(WAIT_OFFSET) { //need some time for devices to appear
            verifyAll(northbound.getFlowConnectedDevices(flow1.flowId)) {
                it.source.lldp.size() == 1
                it.source.arp.size() == 1
                it.destination.lldp.empty
                it.destination.arp.empty
                verifyEquals(it.source.lldp[0], lldpData)
                verifyEquals(it.source.arp[0], arpData)
            }
        }

        and: "LLDP and ARP connected devices are not recognized for flow2"
        verifyAll(northbound.getFlowConnectedDevices(flow2.flowId)) {
            it.source.lldp.empty
            it.source.arp.empty
            it.destination.lldp.empty
            it.destination.arp.empty
        }

        and: "Devices are registered on the switch"
        verifyAll(northboundV2.getConnectedDevices(flow1.source.switchId).ports) {
            it.size() == 1
            it[0].portNumber == flow1.source.portNumber
            it[0].lldp.size() == 1
            it[0].lldp.first().flowId == flow1.flowId
            it[0].lldp.first().vlan == commonOuterVlanId //due to issue 3475
            verifyEquals(it[0].lldp.first(), lldpData)
            it[0].arp.size() == 1
            it[0].arp.first().flowId == flow1.flowId
            it[0].arp.first().vlan == commonOuterVlanId //due to issue 3475
            verifyEquals(it[0].arp.first(), arpData)
        }

        cleanup: "Restore initial switch properties"
        flow1 && flowHelperV2.deleteFlow(flow1.flowId)
        flow2 && flowHelperV2.deleteFlow(flow2.flowId)
        initialProps && restoreSwitchProperties(sw.dpId, initialProps)
        sw && database.removeConnectedDevices(sw.dpId)

        where:
        commonOuterVlanId | innerVlanIdFlow1 | innerVlanIdFlow2 | encapsulationType
        1                 | 2                | 4                | FlowEncapsulationType.TRANSIT_VLAN
        1                 | 3                | 5                | FlowEncapsulationType.VXLAN
    }

    /**
     * Returns a potential flow for creation according to passed params.
     * Note that for 'oneSwitch' it will return a single-port single-switch flow. There is no ability to obtain
     * single-switch different-port flow via this method.
     */
    private FlowPayload getFlowWithConnectedDevices(
            boolean protectedFlow, boolean oneSwitch, boolean srcEnabled, boolean dstEnabled, SwitchPair switchPair) {
        assert !(oneSwitch && protectedFlow), "Cannot create one-switch flow with protected path"
        def flow = null
        if (oneSwitch) {
            flow = flowHelper.singleSwitchSinglePortFlow(switchPair.src)
        } else {
            assert switchPair.src.dpId != switchPair.dst.dpId
            flow = flowHelper.randomFlow(switchPair)
            flow.allocateProtectedPath = protectedFlow
        }
        flow.source.detectConnectedDevices = new DetectConnectedDevicesPayload(srcEnabled, srcEnabled)
        flow.destination.detectConnectedDevices = new DetectConnectedDevicesPayload(dstEnabled, dstEnabled)
        return flow
    }

    private FlowPayload getFlowWithConnectedDevices(
            boolean protectedFlow, boolean oneSwitch, boolean srcEnabled, boolean dstEnabled) {
        def tgSwPair = getUniqueSwitchPairs()?.first()
        assumeTrue(tgSwPair as boolean, "Unable to find a switchPair with traffgens for the requested flow arguments")
        getFlowWithConnectedDevices(protectedFlow, oneSwitch, srcEnabled, dstEnabled, tgSwPair)
    }


    private FlowPayload getFlowWithConnectedDevices(ConnectedDeviceTestData testData) {
        getFlowWithConnectedDevices(testData.protectedFlow, testData.oneSwitch, testData.srcEnabled,
                testData.dstEnabled, testData.switchPair)
    }

    private SwitchPropertiesDto enableMultiTableIfNeeded(boolean needDevices, SwitchId switchId) {
        def initialProps = northbound.getSwitchProperties(switchId)
        if (needDevices && !initialProps.multiTable) {
            def sw = topology.switches.find { it.dpId == switchId }
            switchHelper.updateSwitchProperties(sw, initialProps.jacksonCopy().tap {
                it.multiTable = true
            })
        }
        return initialProps
    }

    private void restoreSwitchProperties(SwitchId switchId, SwitchPropertiesDto initialProperties) {
        Switch sw = topology.switches.find { it.dpId == switchId }
        switchHelper.updateSwitchProperties(sw, initialProperties)
    }

    /**
     * Pick as little as possible amount of switch pairs to cover all unique switch models we have (only connected
     * to traffgens and lldp-enabled).
     */
    @Memoized
    List<SwitchPair> getUniqueSwitchPairs() {
        def tgSwitches = topology.activeTraffGens*.switchConnected
                                 .findAll { it.features.contains(SwitchFeature.MULTI_TABLE) }
        def unpickedTgSwitches = tgSwitches.unique(false) { [it.description, it.nbFormat().hardware].sort() }
        List<SwitchPair> switchPairs = topologyHelper.switchPairs.collectMany { [it, it.reversed] }.findAll {
            it.src in tgSwitches && it.dst in tgSwitches
        }
        assumeTrue(switchPairs.size() > 0, "Unable to find a switchPair with traffgens on both sides")
        def result = []
        while (!unpickedTgSwitches.empty) {
            def pair = switchPairs.sort(false) { switchPair ->
                //prioritize swPairs with unique traffgens on both sides
                [switchPair.src, switchPair.dst].count { Switch sw ->
                    !unpickedTgSwitches.contains(sw)
                }
            }.first()
            //pick first pair and then re-sort considering updated list of unpicked switches
            result << pair
            unpickedTgSwitches = unpickedTgSwitches - pair.src - pair.dst
        }
        return result
    }

    private void validateFlowAndSwitches(Flow flow) {
        northbound.validateFlow(flow.flowId).each { assert it.asExpected }
        [flow.srcSwitch, flow.destSwitch].each {
            def validation = northbound.validateSwitch(it.switchId)
            switchHelper.verifyRuleSectionsAreEmpty(validation, ["missing", "excess", "misconfigured"])
            switchHelper.verifyHexRuleSectionsAreEmpty(validation, ["missingHex", "excessHex", "misconfiguredHex"])
            if (it.ofVersion != "OF_12") {
                switchHelper.verifyMeterSectionsAreEmpty(validation, ["missing", "misconfigured", "excess"])
            }
        }
    }

    private void waitForSrcDevicesInputRules(Flow flow) {
        def ruleTypes = northbound.getSwitchRules(flow.srcSwitch.switchId).flowEntries.collect { new Cookie(it.cookie).type }
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert !flow.detectConnectedDevices.srcArp ^ ruleTypes.contains(ARP_INPUT_CUSTOMER_TYPE)
            assert !flow.detectConnectedDevices.srcLldp ^ ruleTypes.contains(LLDP_INPUT_CUSTOMER_TYPE)
        }
    }

    private void waitForDstDevicesInputRules(Flow flow) {
        def ruleTypes = northbound.getSwitchRules(flow.destSwitch.switchId).flowEntries.collect { new Cookie(it.cookie).type }
        Wrappers.wait(WAIT_OFFSET / 2) {
            assert !flow.detectConnectedDevices.dstArp ^ ruleTypes.contains(ARP_INPUT_CUSTOMER_TYPE)
            assert !flow.detectConnectedDevices.dstLldp ^ ruleTypes.contains(LLDP_INPUT_CUSTOMER_TYPE)
        }
    }

    private void waitForDevicesInputRules(Flow flow) {
        waitForSrcDevicesInputRules(flow)
        waitForDstDevicesInputRules(flow)
    }

    //TODO: This is a candidate to be removed from this spec after default meters validation is released
    private void validateLldpMeters(Flow flow, boolean source) {
        def sw = source ? flow.srcSwitch : flow.destSwitch
        if (sw.ofVersion == "OF_12") {
            return //meters are not supported
        }

        def swProps = northbound.getSwitchProperties(sw.switchId)
        def postIngressMeterCount = swProps.multiTable ? 1 : 0
        def switchLldpMeterCount = swProps.switchLldp ? 1 : 0
        def vxlanMeterCount = swProps.multiTable && sw.features.contains(SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN) ? 1 : 0

        def meters = northbound.getAllMeters(sw.switchId).meterEntries*.meterId

        assert meters.count {
            it == createMeterIdForDefaultRule(LLDP_POST_INGRESS_COOKIE).value
        } == postIngressMeterCount
        assert meters.count {
            it == createMeterIdForDefaultRule(LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).value
        } == postIngressMeterCount

        assert meters.count {
            it == createMeterIdForDefaultRule(LLDP_POST_INGRESS_VXLAN_COOKIE).value
        } == vxlanMeterCount

        assert meters.count {
            it == createMeterIdForDefaultRule(LLDP_INPUT_PRE_DROP_COOKIE).value
        } == switchLldpMeterCount
        assert meters.count { it == createMeterIdForDefaultRule(LLDP_TRANSIT_COOKIE).value } == switchLldpMeterCount
        assert meters.count { it == createMeterIdForDefaultRule(LLDP_INGRESS_COOKIE).value } == switchLldpMeterCount
    }

    private void validateLldpMeters(Flow flow) {
        validateLldpMeters(flow, true)
        validateLldpMeters(flow, false)
    }

    private void validateSwitchHasNoFlowRulesAndMeters(SwitchId switchId) {
        assert northbound.getSwitchRules(switchId).flowEntries.count { !new Cookie(it.cookie).serviceFlag } == 0
        assert northbound.getAllMeters(switchId).meterEntries.count { !MeterId.isMeterIdOfDefaultRule(it.meterId) } == 0
    }

    def verifyEquals(ConnectedDeviceDto device, LldpData lldp) {
        assert device.macAddress == lldp.macAddress
        assert device.chassisId == "Mac Addr: $lldp.chassisId" //for now TG sends it as hardcoded 'mac address' subtype
        assert device.portId == "Locally Assigned: $lldp.portNumber" //subtype also hardcoded for now on traffgen side
        assert device.ttl == lldp.timeToLive
        //other non-mandatory lldp fields are out of scope for now. Most likely they are not properly parsed
        return true
    }

    def verifyEquals(ConnectedDeviceDto device, ArpData arp) {
        assert device.macAddress == arp.srcMac
        assert device.ipAddress == arp.srcIpv4
        return true
    }

    def verifyEquals(SwitchConnectedDeviceDto device, LldpData lldp) {
        assert device.macAddress == lldp.macAddress
        assert device.chassisId == "Mac Addr: $lldp.chassisId" //for now TG sends it as hardcoded 'mac address' subtype
        assert device.portId == "Locally Assigned: $lldp.portNumber" //subtype also hardcoded for now on traffgen side
        assert device.ttl == lldp.timeToLive
        //other non-mandatory lldp fields are out of scope for now. Most likely they are not properly parsed
        return true
    }

    def verifyEquals(SwitchConnectedDeviceDto device, ArpData arp) {
        assert device.macAddress == arp.srcMac
        assert device.ipAddress == arp.srcIpv4
        return true
    }

    private static String getDescr(boolean src, boolean dst) {
        if (src && !dst) {
            "src only"
        } else if (!src && dst) {
            "dst only"
        } else if (src && dst) {
            "src and dst"
        } else {
            "none of the endpoints"
        }
    }
}
