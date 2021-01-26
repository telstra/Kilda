package org.openkilda.functionaltests.spec.grpc

import static org.openkilda.functionaltests.extension.tags.Tag.HARDWARE

import org.openkilda.functionaltests.extension.failfast.Tidy
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.grpc.speaker.model.LogicalPortDto
import org.openkilda.messaging.error.MessageError
import org.openkilda.messaging.model.grpc.LogicalPortType

import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.Unroll

@Narrative("""This test suite checks the CRUD actions on a logical port.
Logical ports are defined by associating a single physical port to them to define
Bidirectional Forwarding Detection(BFD) ports or
by associating a list of physical ports to them to create Link Aggregation Groups (LAG) or
a list of BFD ports to them to create a LAG for fast-failover for BFD sessions.

NOTE: The GRPC implementation supports the LAG type only and it is set by default.""")
class LogicalPortSpec extends GrpcBaseSpecification {

    @Tidy
    @Unroll
    @Tags(HARDWARE) //https://github.com/telstra/open-kilda/issues/3904
    def "Able to create/read/delete logicalport on the #switches.switchId switch"() {
        /**the update action is not working(issue on a Noviflow switch side)*/
        when: "Create logical port"
        def switchPort = northbound.getPorts(switches.switchId).find {
            it.state[0] == "LINK_DOWN" && !it.name.contains("novi_lport")
        }.portNumber
        def switchLogicalPort = 1100 + switchPort
        def request = new LogicalPortDto(LogicalPortType.BFD, [switchPort], switchLogicalPort)
        def responseAfterCreating = grpc.createLogicalPort(switches.address, request)
        assert responseAfterCreating.logicalPortNumber.value == switchLogicalPort

        then: "Able to get the created logical port"
        def responseAfterGetting = grpc.getSwitchLogicalPortConfig(switches.address, switchLogicalPort)
        responseAfterGetting.logicalPortNumber == switchLogicalPort
        responseAfterGetting.name == "novi_lport" + switchLogicalPort.toString()
        responseAfterGetting.portNumbers[0] == switchPort
        responseAfterCreating.type == LogicalPortType.BFD

        and: "The created port is exist in a list of all logical port"
        grpc.getSwitchLogicalPorts(switches.address).contains(responseAfterGetting)

        //        TODO(andriidovhan): add update action
        //        and: "able to edit the created logical port"
        when: "Try to delete the created logical port"
        def responseAfterDeleting = grpc.deleteSwitchLogicalPort(switches.address, switchLogicalPort)

        then: "Logical port is deleted"
        responseAfterDeleting.deleted

        when: "Try to get the deleted logical port"
        grpc.getSwitchLogicalPortConfig(switches.address, switchLogicalPort)

        then: "Human readable error is returned"
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.NOT_FOUND
        exc.responseBodyAsString.to(MessageError).errorMessage == "Provided logical port does not exist."
        Boolean testIsCompleted = true

        cleanup: "Remove created port"
        if (!testIsCompleted) {
            grpc.deleteSwitchLogicalPort(switches.address, switchLogicalPort)
        }

        where:
        switches << getNoviflowSwitches()
    }

    @Tidy
    @Unroll
    @Tags(HARDWARE)
    def "Not able to create logical port with incorrect port number(lPort/sPort): \
#data.logicalPortNumber/#data.portNumber on the #sw.switchId switch"() {
        when:
        "Try to create logical port: #logicalPortNumber/#portNumber"
        def switchPort = northbound.getPorts(sw.switchId).find { it.state[0] == "LINK_DOWN" }.portNumber
        def switchLogicalPort = 1100 + switchPort
        def pNumber = data.portNumber ? data.portNumber : switchPort
        def lPortNumber = data.logicalPortNumber ? data.logicalPortNumber : switchLogicalPort
        grpc.createLogicalPort(sw.address, new LogicalPortDto(LogicalPortType.LAG, [pNumber], lPortNumber))

        then: "Human readable error is returned."
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.BAD_REQUEST
        exc.responseBodyAsString.to(MessageError).errorMessage == data.errorMessage

        where:
        [data, sw] << [
                [
                        [logicalPortNumber: 99,
                         portNumber       : false,
                         errorMessage     : "Valid logicalportno range is 100 to 63487."],
                        [logicalPortNumber: 63488,
                         portNumber       : false,
                         errorMessage     : "Valid logicalportno range is 100 to 63487."],
                        //https://github.com/telstra/open-kilda/issues/3973
//                        [logicalPortNumber: false,
//                         portNumber       : 44444,
//                         errorMessage     : "Invalid portno value."],
                ], noviflowSwitches].combinations()
    }

    @Tidy
    @Unroll
    @Tags(HARDWARE)
    def "Not able to delete non-existent logical port number on the #switches.switchId switch"() {
        when: "Try to delete incorrect logicalPortNumber"
        def validLogicalPorts = 100..63487
        def busyLogicalPorts = grpc.getSwitchLogicalPorts(switches.address)*.logicalPortNumber.sort()
        Integer nonExistentLogicalPort = (validLogicalPorts - busyLogicalPorts).first()
        grpc.deleteSwitchLogicalPort(switches.address, nonExistentLogicalPort)

        then: "Human readable error is returned."
        def exc = thrown(HttpClientErrorException)
        exc.statusCode == HttpStatus.NOT_FOUND
        exc.responseBodyAsString.to(MessageError).errorMessage == "Provided logical port does not exist."

        where:
        switches << getNoviflowSwitches()
    }
}
