package org.openkilda.functionaltests.spec.grpc

import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE_SWITCHES
import static org.openkilda.functionaltests.extension.tags.Tag.TOPOLOGY_DEPENDENT
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID
import static org.openkilda.testing.ConstantsGrpc.GRPC_STUB_CONTAINER_NAME

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.healthcheck.HealthCheck
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.DockerHelper
import org.openkilda.messaging.info.event.SwitchChangeType
import org.openkilda.northbound.dto.v1.switches.SwitchDto
import org.openkilda.northbound.dto.v1.switches.SwitchLocationDto
import org.openkilda.testing.service.grpc.GrpcService

import groovy.transform.Memoized
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import spock.lang.See
import spock.lang.Shared

@See("https://github.com/telstra/open-kilda/tree/develop/docs/design/grpc-client")
@Tags([SMOKE_SWITCHES, TOPOLOGY_DEPENDENT])
class GrpcBaseSpecification extends HealthCheckSpecification {
    @Value('${spring.profiles.active}')
    String profile
    @Autowired @Shared
    GrpcService grpc
    @Value('${docker.host}')
    String dockerHost

    @HealthCheck
    def "GRPC is UP and operational"() {
        expect: "GRPC's health check request is successful"
        grpc.getHealthCheck().components["kafka"] == "operational"
    }

    @Memoized
    List<SwitchDto> getNoviflowSwitches() {
        if (profile == "virtual") {
            /* Create fake switch for running test using grpc-stub
            NOTE: The grpc-stub service covers positive test cases only */
            def grpcStubIp = new DockerHelper(dockerHost).getContainerIp(GRPC_STUB_CONTAINER_NAME)
            new SwitchDto(NON_EXISTENT_SWITCH_ID, grpcStubIp,37040, "host", "desc",  SwitchChangeType.ACTIVATED,
                    false, "of_version", "manufacturer", "hardware", "software", "serial_number", "pop",
                    new SwitchLocationDto(48.860611, 2.337633, "street", "city", "country")) as List
        } else {
            northbound.activeSwitches.findAll {
                it.manufacturer.toLowerCase().contains("noviflow") &&
                        compareNoviVersions(it.software, "500.2.7") >= 0
            }.unique { [it.hardware, it.software] }
        }
    }

    /**
     * Compare 2 version strings. Must have equal amount of number groups, e.g. 500.0.0.1 and 49.555.45.0
     * @return '1' if v1 > v2, '-1' if v1 < v2, '0' if equal
     */
    int compareNoviVersions(String v1, String v2) {
        def pattern = /(\d+)/
        def v1Matcher = v1 =~ pattern
        def v2Matcher = v2 =~ pattern
        if (!v1Matcher || !v2Matcher || v1Matcher.size() != v2Matcher.size()) {
            throw new RuntimeException("Unable to compare given version strings: $v1 and $v2")
        }
        def v1Numbers = v1Matcher.collect { it[1].toInteger() }
        def v2Numbers = v2Matcher.collect { it[1].toInteger() }
        for (int i = 0; i < v1Numbers.size(); i++) {
            if (v1Numbers[i] > v2Numbers[i]) {
                return 1
            } else if (v1Numbers[i] < v2Numbers[i]) {
                return -1
            }
        }
        return 0
    }

}
