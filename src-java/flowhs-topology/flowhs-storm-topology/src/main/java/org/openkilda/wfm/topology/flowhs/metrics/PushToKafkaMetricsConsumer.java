/* Copyright 2020 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.wfm.topology.flowhs.metrics;

import org.openkilda.messaging.Utils;
import org.openkilda.messaging.info.Datapoint;

import io.micrometer.core.instrument.config.NamingConvention;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.storm.metric.api.IMetricsConsumer;
import org.apache.storm.task.IErrorReporter;
import org.apache.storm.task.TopologyContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

@Slf4j
public class PushToKafkaMetricsConsumer implements IMetricsConsumer {

    public static final String DEFAULT_METRICS_PREFIX = "metrics";
    public static final String METRICS_PREFIX_CONFIG_NAME = "metrics.prefix";
    public static final String KAFKA_TOPIC_CONFIG_NAME = "metrics.kafka.topic";
    public static final String KAFKA_PRODUCER_CONFIG_NAME = "metrics.kafka.producer.properties";
    private static final Pattern TAG_VALUE_CHARS = Pattern.compile("[^a-zA-Z0-9_]");

    private String stormId;
    private String prefix;
    private String kafkaTopicName;
    private KafkaProducer<String, String> kafkaProducer;

    @Override
    @SuppressWarnings("unchecked")
    public void prepare(Map stormConf, Object registrationArgument, TopologyContext context,
                        IErrorReporter errorReporter) {
        stormId = context.getStormId();
        prefix = (String) stormConf.getOrDefault(METRICS_PREFIX_CONFIG_NAME, DEFAULT_METRICS_PREFIX);
        kafkaTopicName = (String) Objects.requireNonNull(stormConf.get(KAFKA_TOPIC_CONFIG_NAME));
        Properties producerProps = new Properties();
        producerProps.putAll((Map<String, Object>) Objects.requireNonNull(stormConf.get(KAFKA_PRODUCER_CONFIG_NAME)));
        kafkaProducer = new KafkaProducer<>(producerProps);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handleDataPoints(TaskInfo taskInfo, Collection<DataPoint> dataPoints) {
        Map<String, String> tags = buildTags(taskInfo);
        List<Datapoint> resultDatapoints = new ArrayList<>();

        for (DataPoint datapoint : dataPoints) {
            if (datapoint.value instanceof Number) {
                long value = ((Number) datapoint.value).longValue();
                String name = buildMetricName(datapoint.name, "value");
                resultDatapoints.add(buildDatapoint(name, value, taskInfo.timestamp, tags));
            } else if (datapoint.value instanceof String) {
                Long value = convertValueToLong(datapoint.value);
                if (value != null) {
                    String name = buildMetricName(datapoint.name, "value");
                    resultDatapoints.add(buildDatapoint(name, value, taskInfo.timestamp, tags));
                }
            } else if (datapoint.value instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) datapoint.value;
                for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                    Long value = convertValueToLong(entry.getValue());
                    if (value != null) {
                        String name = buildMetricName(datapoint.name, entry.getKey());
                        resultDatapoints.add(buildDatapoint(name, value, taskInfo.timestamp, tags));
                    }
                }
            } else {
                log.warn("Unrecognized metric value {} received. Drop the datapoint.", datapoint.value);
            }
        }

        for (Datapoint datapoint : resultDatapoints) {
            try {
                log.error("Sending the datapoint {}", datapoint);
                String payload = Utils.MAPPER.writeValueAsString(datapoint);
                kafkaProducer.send(new ProducerRecord<>(kafkaTopicName, null, payload));
            } catch (Exception e) {
                log.error("Could not send the datapoint {}", datapoint, e);
            }
        }
    }

    private Map<String, String> buildTags(TaskInfo taskInfo) {
        Map<String, String> tags = new HashMap<>();
        tags.put("stormId", sanitizeTagValue(stormId));
        tags.put("srcComponentId", sanitizeTagValue(taskInfo.srcComponentId));
        tags.put("srcWorkerHost", sanitizeTagValue(taskInfo.srcWorkerHost));
        tags.put("srcTaskId", sanitizeTagValue(String.valueOf(taskInfo.srcTaskId)));
        return tags;
    }

    private String sanitizeTagValue(String value) {
        String conventionValue = NamingConvention.dot.tagValue(value);
        return TAG_VALUE_CHARS.matcher(conventionValue).replaceAll("_");
    }

    private String buildMetricName(String... elements) {
        String result = String.join(".", elements);
        if (prefix != null && !prefix.isEmpty()) {
            return String.join(".", prefix, result);
        } else {
            return result;
        }
    }

    private Long convertValueToLong(Object value) {
        Long result = null;
        if (value instanceof String) {
            try {
                result = ((Double) Double.parseDouble((String) value)).longValue();
            } catch (NumberFormatException e) {
                log.warn("Discarding metric {}", value);
            }
        } else if (value instanceof Number) {
            result = ((Number) value).longValue();
        }
        return result;
    }

    private Datapoint buildDatapoint(String name, long value, long wallTime, Map<String, String> tags) {
        Datapoint datapoint = new Datapoint();
        datapoint.setMetric(name);
        datapoint.setTime(wallTime);
        datapoint.setValue(value);
        datapoint.setTags(tags);
        return datapoint;
    }

    @Override
    public void cleanup() {
        // Nothing
    }
}
