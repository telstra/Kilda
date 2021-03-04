package org.openkilda.functionaltests.spec.logging

import static org.openkilda.functionaltests.extension.tags.Tag.SMOKE
import static org.openkilda.testing.Constants.NON_EXISTENT_SWITCH_ID

import org.openkilda.functionaltests.HealthCheckSpecification
import org.openkilda.functionaltests.extension.tags.Tags
import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.messaging.error.MessageError
import org.openkilda.testing.service.elastic.ElasticQueryBuilder
import org.openkilda.testing.service.elastic.ElasticService
import org.openkilda.testing.service.elastic.model.KildaTags

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.client.HttpClientErrorException
import spock.lang.Narrative
import spock.lang.Shared

@Slf4j
@Tags([SMOKE])
@Narrative("This specification ensures that all logging facilities are up and running after Kilda deployment")
class CheckLoggingSpec extends HealthCheckSpecification {

    @Autowired @Shared
    ElasticService elastic

    @Value('${elasticsearch.index}')
    String elasticIndex

    def discoveryMsg = "push discovery package via"
    def switchErrorMsg = "Switch $NON_EXISTENT_SWITCH_ID not found"
    def flowErrorMsg = { "Can not get flow: Flow $it not found" }

    def "Check Floodlight logging"() {
        when: "Retrieve Floodlight logs for last 5 minutes"
        def result = elastic.getLogs(new ElasticQueryBuilder().setTags(KildaTags.FLOODLIGHT)
                .setLevel("INFO").setTimeRange(300).build())

        if (profile == "virtual") { //due to different version of kibana
            assert result?.hits?.total > 0: "No logs could be found for Floodlight"
        } else {
            assert result?.hits?.total.value > 0: "No logs could be found for Floodlight"
        }

        then: "There should be discovery messages"
        result.hits.hits.any { hit -> hit.source.message.toLowerCase().contains(discoveryMsg) }
    }

    def "Check Northbound logging"() {
        when: "A non-existent switch is requested"
        northbound.getSwitch(NON_EXISTENT_SWITCH_ID)

        then: "An error is received (404 code)"
        def switchExc = thrown(HttpClientErrorException)
        switchExc.rawStatusCode == 404
        switchExc.responseBodyAsString.to(MessageError).errorMessage.contains(switchErrorMsg)

        and: "Northbound should log these actions within 30 seconds"
        int timeout = 31
        Wrappers.wait(timeout, 5) {
            def nbLogs = elastic.getLogs(new ElasticQueryBuilder().setIndex(elasticIndex)
                    .setTags(KildaTags.NORTHBOUND).setTimeRange(timeout * 2).setLevel("ERROR")
                    .setField("message", String.format('"%s"', switchErrorMsg)).build())

            assert nbLogs?.hits?.hits?.any { hit -> hit.source.message.contains(switchErrorMsg) }:
                    "Northbound should generate an error message about not being able to find the switch"
        }
    }

    def "Check Storm logging"() {
        when: "A non-existent flow is requested"
        def flowId = "nonexistentFlowId" + System.currentTimeMillis()
        northboundV2.getFlow(flowId)

        then: "An error is received (404 code)"
        def flowExc = thrown(HttpClientErrorException)
        flowExc.rawStatusCode == 404
        flowExc.responseBodyAsString.to(MessageError).errorMessage.contains(flowErrorMsg(flowId))

        and: "Storm should log these actions within 30 seconds"
        int timeout = 31
        Wrappers.wait(timeout, 5) {
            def stormLogs = elastic.getLogs(new ElasticQueryBuilder().setIndex(elasticIndex)
                    .setTags(KildaTags.STORM_WORKER).setTimeRange(timeout * 2).setLevel("ERROR")
                    .setField("message", String.format('"%s"', flowErrorMsg(flowId))).build())

            assert stormLogs?.hits?.hits?.any { hit -> hit.source.message.contains(flowErrorMsg(flowId)) }:
                    "Storm should generate a warning message about not being able to find the flow"
        }
    }
}
