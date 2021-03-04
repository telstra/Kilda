package org.openkilda.performancetests

import org.openkilda.functionaltests.extension.fixture.SetupOnce
import org.openkilda.functionaltests.extension.healthcheck.HealthCheck
import org.openkilda.functionaltests.helpers.FlowHelper
import org.openkilda.functionaltests.helpers.FlowHelperV2
import org.openkilda.functionaltests.helpers.PathHelper
import org.openkilda.functionaltests.helpers.PortAntiflapHelper
import org.openkilda.messaging.model.system.FeatureTogglesDto
import org.openkilda.messaging.model.system.KildaConfigurationDto
import org.openkilda.performancetests.helpers.TopologyHelper
import org.openkilda.testing.service.database.Database
import org.openkilda.testing.service.floodlight.FloodlightsHelper
import org.openkilda.testing.service.labservice.LabService
import org.openkilda.testing.service.lockkeeper.LockKeeperService
import org.openkilda.testing.service.northbound.NorthboundService
import org.openkilda.testing.service.northbound.NorthboundServiceV2
import org.openkilda.testing.tools.IslUtils

import org.spockframework.spring.EnableSharedInjection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.test.context.ContextConfiguration
import spock.lang.Shared
import spock.lang.Specification

@ContextConfiguration(locations = ["classpath:/spring-context.xml"])
@EnableSharedInjection
class BaseSpecification extends Specification {
    @Autowired @Shared
    NorthboundService northbound
    @Autowired @Shared
    NorthboundServiceV2 northboundV2
    @Autowired @Shared
    FloodlightsHelper flHelper
    @Autowired @Shared
    FlowHelperV2 flowHelperV2
    @Autowired @Shared
    LabService labService
    @Autowired @Shared
    LockKeeperService lockKeeper
    @Autowired @Shared
    FlowHelper flowHelper
    @Autowired @Shared
    @Qualifier("performance")
    TopologyHelper topoHelper
    @Autowired @Shared
    Database database
    @Autowired @Shared
    IslUtils islUtils
    @Autowired @Shared
    PortAntiflapHelper antiflap
    @Autowired @Shared
    PathHelper pathHelper

    @Value('${discovery.generic.interval}')
    int discoveryInterval

    @Value('${discovery.timeout}')
    int discoveryTimeout

    @Value('${use.hs}')
    boolean useHs

    @Value('${use.multitable}')
    boolean useMultitable

    @Value('${perf.debug}')
    boolean debug

    def setupSpec() {
        northbound.getAllFlows().each { northbound.deleteFlow(it.id) }
        topoHelper.purgeTopology()
    }

    @HealthCheck
    def "Configure feature toggles"() {
        expect: "Feature toggles are configured"
        def features = FeatureTogglesDto.builder()
                                        .createFlowEnabled(true)
                                        .updateFlowEnabled(true)
                                        .deleteFlowEnabled(true)
                                        .flowsRerouteOnIslDiscoveryEnabled(true)
                                        .useBfdForIslIntegrityCheck(true)
                                        .build()
        northbound.toggleFeature(features)
        northbound.updateKildaConfiguration(KildaConfigurationDto.builder().useMultiTable(useMultitable).build())
    }

    def setup() {
        //setup with empty body in order to trigger a SETUP invocation, which is intercepted in several extensions
        //this can have implementation if required
    }
}
