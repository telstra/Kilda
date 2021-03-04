package org.openkilda.functionaltests.spec.switches

import static org.junit.jupiter.api.Assumptions.assumeTrue
import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.SwitchHelper
import org.openkilda.messaging.error.MessageError
import org.openkilda.model.FlowEncapsulationType
import org.openkilda.model.SwitchFeature
import org.openkilda.northbound.dto.v1.switches.SwitchPropertiesDto

import groovy.transform.AutoClone
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.Unroll

@Narrative("""Switch properties are created automatically once switch is connected to the controller
and deleted once switch is deleted.
Properties can be read/updated via API '/api/v1/switches/:switch-id/properties'.
Main purpose of that is to understand which feature is supported by a switch(encapsulation type, multi table)""")
class SwitchPropertiesSpec extends HealthCheckSpecification {

    @Tidy
    @Tags([TOPOLOGY_DEPENDENT, SMOKE_SWITCHES])
    def "Able to manipulate with switch properties"() {
        given: "A switch that supports VXLAN"
        def sw = topology.activeSwitches.find { it.features.contains(SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN) }
        assumeTrue(sw as boolean, "Wasn't able to find vxlan-enabled switch")
        def initSwitchProperties = northbound.getSwitchProperties(sw.dpId)
        assert initSwitchProperties.multiTable != null
        assert !initSwitchProperties.supportedTransitEncapsulation.empty

        when: "Update switch properties"
        SwitchPropertiesDto switchProperties = new SwitchPropertiesDto()
        def newMultiTable = !initSwitchProperties.multiTable
        def newTransitEncapsulation = (initSwitchProperties.supportedTransitEncapsulation.size() == 1) ?
                [FlowEncapsulationType.TRANSIT_VLAN.toString().toLowerCase(),
                 FlowEncapsulationType.VXLAN.toString().toLowerCase()].sort() :
                [FlowEncapsulationType.VXLAN.toString().toLowerCase()]
        switchProperties.multiTable = newMultiTable
        switchProperties.supportedTransitEncapsulation = newTransitEncapsulation
        def updateSwPropertiesResponse = SwitchHelper.updateSwitchProperties(sw, switchProperties)

        then: "Correct response is returned"
        updateSwPropertiesResponse.multiTable == newMultiTable
        updateSwPropertiesResponse.supportedTransitEncapsulation.sort() == newTransitEncapsulation

        and: "Switch properties is really updated"
        with(northbound.getSwitchProperties(sw.dpId)) {
            it.multiTable == newMultiTable
            it.supportedTransitEncapsulation.sort() == newTransitEncapsulation
        }

        cleanup: "Restore init switch properties on the switch"
        SwitchHelper.updateSwitchProperties(sw, initSwitchProperties)
    }

    @Tidy
    def "Informative error is returned when trying to get/update switch properties with non-existing id"() {
        when: "Try to get switch properties info for non-existing switch"
        northbound.getSwitchProperties(NON_EXISTENT_SWITCH_ID)

        then: "Human readable error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.NOT_FOUND
        e.responseBodyAsString.to(MessageError).errorMessage ==
                "Switch properties for switch id '$NON_EXISTENT_SWITCH_ID' not found."

        when: "Try to update switch properties info for non-existing switch"
        def switchProperties = new SwitchPropertiesDto()
        switchProperties.multiTable = true
        switchProperties.supportedTransitEncapsulation = [FlowEncapsulationType.VXLAN.toString()]
        northbound.updateSwitchProperties(NON_EXISTENT_SWITCH_ID, switchProperties)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.NOT_FOUND
        exc.responseBodyAsString.to(MessageError).errorMessage ==
                "Switch properties for switch id '$NON_EXISTENT_SWITCH_ID' not found."
    }

    @Tidy
    def "Informative error is returned when trying to update switch properties with incorrect information"() {
        given: "A switch"
        def sw = topology.activeSwitches.first()

        when: "Try to update switch properties info for non-existing switch"
        def switchProperties = new SwitchPropertiesDto()
        switchProperties.supportedTransitEncapsulation = supportedTransitEncapsulation
        northbound.updateSwitchProperties(sw.dpId, switchProperties)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage == errorMessage

        where:
        supportedTransitEncapsulation | errorMessage
        ["test"]                      | "Unable to parse request payload"
        []                            | "Supported transit encapsulations should not be null or empty"
        null                          | "Supported transit encapsulations should not be null or empty"
    }

    @Tidy
    @Unroll
    def "Error is returned when trying to #data.desc"() {
        given: "A switch"
        def sw = topology.activeSwitches.first()

        when: "Try to update switch properties with incorrect server 42 properties combination"
        def switchProperties = new SwitchPropertiesDto()
        switchProperties.supportedTransitEncapsulation = [FlowEncapsulationType.TRANSIT_VLAN.toString()]
        switchProperties.multiTable = data.multiTable
        switchProperties.server42FlowRtt = data.server42FlowRtt
        switchProperties.server42Port = data.server42Port
        switchProperties.server42Vlan = data.server42Vlan
        switchProperties.server42MacAddress = data.server42MacAddress
        northbound.updateSwitchProperties(sw.dpId, switchProperties)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage == String.format(data.error, sw.dpId)

        where:
        data << [
                new PropertiesData(desc: "enable server42_flow_rtt property without server42_port property",
                        multiTable: true, server42FlowRtt: true, server42Port: null, server42MacAddress: "42:42:42:42:42:42",
                        server42Vlan: 15,
                        error: "Illegal switch properties combination for switch %s. To enable property " +
                                "'server42_flow_rtt' you need to specify valid property 'server42_port'"),

                new PropertiesData(desc: "enable server42_flow_rtt property without server42_mac_address property",
                        multiTable: true, server42FlowRtt: true, server42Port: 42, server42MacAddress: null,
                        server42Vlan: 15,
                        error: "Illegal switch properties combination for switch %s. To enable property " +
                                "'server42_flow_rtt' you need to specify valid property 'server42_mac_address'"),

                new PropertiesData(desc: "enable server42_flow_rtt property without server42_vlan property",
                        multiTable: true, server42FlowRtt: true, server42Port: 42, server42MacAddress: "42:42:42:42:42:42",
                        server42Vlan: null,
                        error: "Illegal switch properties combination for switch %s. To enable property " +
                                "'server42_flow_rtt' you need to specify valid property 'server42_vlan'"),

                new PropertiesData(desc: "set invalid server42_port property",
                        multiTable: true, server42FlowRtt: true, server42Port: -1, server42MacAddress: null,
                        server42Vlan: 15,
                        error: "Property 'server42_port' for switch %s has invalid value '-1'. Port must be positive"),

                new PropertiesData(desc: "set invalid server42mac_address property",
                        multiTable: false, server42FlowRtt: false, server42Port: null, server42MacAddress: "INVALID",
                        server42Vlan: 15,
                        error: "Property 'server42_mac_address' for switch %s has invalid value 'INVALID'."),

                new PropertiesData(desc: "set invalid server42_vlan property",
                        multiTable: false, server42FlowRtt: false, server42Port: null, server42MacAddress: null,
                        server42Vlan: -1,
                        error: "Property 'server42_vlan' for switch %s has invalid value '-1'. Vlan must be in range [0, 4095]"),
        ]
    }

    @AutoClone
    private static class PropertiesData {
        boolean multiTable, server42FlowRtt
        Integer server42Port, server42Vlan
        String server42MacAddress, desc, error
    }

    @Tidy
    def "Unable to turn on switchLldp property without turning on multiTable property"() {
        given: "A switch"
        def sw = topology.activeSwitches.first()
        def initSwitchProperties = northbound.getSwitchProperties(sw.dpId)

        when: "Try to update set switchLldp property to True and multiTable property to False"
        def switchProperties = new SwitchPropertiesDto()
        switchProperties.supportedTransitEncapsulation = [FlowEncapsulationType.TRANSIT_VLAN.toString()]
        switchProperties.multiTable = false
        switchProperties.switchLldp = true
        northbound.updateSwitchProperties(sw.dpId, switchProperties)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage ==
                "Illegal switch properties combination for switch $sw.dpId. 'switchLldp' property " +
                "can be set to 'true' only if 'multiTable' property is 'true'."

        cleanup:
        !exc && switchHelper.updateSwitchProperties(sw, initSwitchProperties)
    }

    @Tidy
    def "Unable to turn on switchArp property without turning on multiTable property"() {
        given: "A switch"
        def sw = topology.activeSwitches.first()
        def initSwitchProperties = northbound.getSwitchProperties(sw.dpId)

        when: "Try to update set switchArp property to True and multiTable property to False"
        def switchProperties = new SwitchPropertiesDto()
        switchProperties.supportedTransitEncapsulation = [FlowEncapsulationType.TRANSIT_VLAN.toString()]
        switchProperties.multiTable = false
        switchProperties.switchArp = true
        northbound.updateSwitchProperties(sw.dpId, switchProperties)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage ==
                "Illegal switch properties combination for switch $sw.dpId. 'switchArp' property " +
                "can be set to 'true' only if 'multiTable' property is 'true'."

        cleanup:
        !exc && switchHelper.updateSwitchProperties(sw, initSwitchProperties)
    }

    @Tidy
    @Tags([TOPOLOGY_DEPENDENT, SMOKE_SWITCHES])
    def "System forbids to turn on VXLAN encap type on switch that does not support it"() {
        given: "Switch that does not support VXLAN feature"
        def sw = topology.activeSwitches.find { !it.features.contains(SwitchFeature.NOVIFLOW_PUSH_POP_VXLAN) }
        assumeTrue(sw as boolean, "There is no non-vxlan switch in the topology")

        when: "Try to turn on VXLAN encap type on that switch"
        def initProps = northbound.getSwitchProperties(sw.dpId)
        northbound.updateSwitchProperties(sw.dpId, initProps.jacksonCopy().tap {
            it.supportedTransitEncapsulation = [FlowEncapsulationType.VXLAN.toString()]
        })

        then: "Error is returned"
        def e = thrown(HttpClientErrorException)
        e.statusCode == HttpStatus.BAD_REQUEST
        e.responseBodyAsString.to(MessageError).errorDescription ==
                "Switch $sw.dpId doesn't support requested feature NOVIFLOW_PUSH_POP_VXLAN"

        cleanup:
        !e && SwitchHelper.updateSwitchProperties(sw, initProps)
    }
}
