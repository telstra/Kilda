/* Copyright 2017 Telstra Open Source
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

package org.openkilda.messaging;

import org.openkilda.model.OutputVlanType;
import org.openkilda.model.SwitchId;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Utils for flow commands.
 */
public final class Utils {
    // TODO(zero_down_time) remove when Zero Down Time feature will be implemented
    public static final String COMMON_COMPONENT_NAME = "common_component";
    // TODO(zero_down_time) remove when Zero Down Time feature will be implemented
    public static final String COMMON_COMPONENT_RUN_ID = "common_run_id";

    /**
     * Common object mapper.
     */
    public static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * The request timestamp attribute.
     */
    public static final String TIMESTAMP = "timestamp";
    /**
     * The transaction ID property name.
     */
    public static final String TRANSACTION_ID = "transaction_id";
    /**
     * The correlation ID header name.
     */
    public static final String CORRELATION_ID = "correlation_id";
    /**
     * The Extra auth header name.
     */
    public static final String EXTRA_AUTH = "EXTRA_AUTH";
    /**
     * The destination property.
     */
    public static final String DESTINATION = "destination";

    /**
     * The region of message origination.
     */
    public static final String REGION = "region";

    public static final String ROUTE = "route";
    /**
     * The payload property.
     */
    public static final String PAYLOAD = "payload";
    /**
     * The payload property.
     */
    public static final String FLOW_ID = "flowid";
    /**
     * The payload property.
     */
    public static final String FLOW_PATH = "flowpath";
    /**
     * The default correlation ID value.
     */
    public static final String DEFAULT_CORRELATION_ID = "admin-request";
    /**
     * The default correlation ID value.
     */
    public static final String SYSTEM_CORRELATION_ID = "system-request";
    /**
     * The health check operational status.
     */
    public static final String HEALTH_CHECK_OPERATIONAL_STATUS = "operational";
    /**
     * The health check non operational status.
     */
    public static final String HEALTH_CHECK_NON_OPERATIONAL_STATUS = "non-operational";
    /**
     * Kafka message header to specify message version.
     */
    public static final String MESSAGE_VERSION_HEADER = "kafka.message.version.header";
    /**
     * Property name for Kafka consumer to specify component name for consumer interceptor.
     */
    public static final String CONSUMER_COMPONENT_NAME_PROPERTY = "kafka.consumer.messaging.component.name.property";
    /**
     * Property name for Kafka consumer to specify run ID for consumer interceptor.
     */
    public static final String CONSUMER_RUN_ID_PROPERTY = "kafka.consumer.messaging.run.id.property";
    /**
     * Property name for Kafka consumer to specify zookeeper connection string.
     */
    public static final String CONSUMER_ZOOKEEPER_CONNECTION_STRING_PROPERTY =
            "kafka.consumer.messaging.zookeeper.connecting.string.property";
    /**
     * Property name for Kafka producer to specify component name for producer interceptor.
     */
    public static final String PRODUCER_COMPONENT_NAME_PROPERTY = "kafka.producer.messaging.component.name.property";
    /**
     * Property name for Kafka producer to specify run ID for producer interceptor.
     */
    public static final String PRODUCER_RUN_ID_PROPERTY = "kafka.producer.messaging.run.id.property";
    /**
     * Property name for Kafka producer to specify zookeeper connection string.
     */
    public static final String PRODUCER_ZOOKEEPER_CONNECTION_STRING_PROPERTY =
            "kafka.producer.messaging.zookeeper.connecting.string.property";
    /**
     * VLAN TAG Ether type value.
     */
    public static final int ETH_TYPE = 0x8100;
    /**
     * OpenFlow controller port number.
     */
    public static final int OF_CONTROLLER_PORT = 0xFFFFFFFD;
    /**
     * Minimum allowable VLAN ID value.
     */
    private static final int MIN_VLAN_ID = 0;
    /**
     * Maximum allowable VLAN ID value.
     */
    private static final int MAX_VLAN_ID = 4095;

    /**
     * Minimum allowable VXLAN VNI value.
     */
    private static final int MIN_VXLAN_ID = 0;

    /**
     * Maximum allowable VXLAN VNI value.
     */
    private static final int MAX_VXLAN_ID = 16777214;



    /**
     * A private constructor.
     */
    private Utils() {
        throw new UnsupportedOperationException();
    }

    /**
     * Checks if specified vlan id is in allowable range.
     *
     * @param vlanId vlan id
     * @return true if vlan id is valid
     */
    public static boolean validateVlanRange(final Integer vlanId) {
        return (vlanId >= MIN_VLAN_ID) && (vlanId <= MAX_VLAN_ID);
    }

    public static boolean validateVxlanRange(final Integer vni) {
        return (vni >= MIN_VXLAN_ID) && (vni <= MAX_VXLAN_ID);
    }

    /**
     * Validates output vlan operation type value by output vlan tag.
     *
     * @param outputVlanId   output vlan id
     * @param outputVlanType output vlan operation type
     * @return true if output vlan operation type is valid
     */
    public static boolean validateOutputVlanType(final Integer outputVlanId, final OutputVlanType outputVlanType) {
        return (outputVlanId != null && outputVlanId != 0)
                ? (OutputVlanType.PUSH.equals(outputVlanType) || OutputVlanType.REPLACE.equals(outputVlanType))
                : (OutputVlanType.POP.equals(outputVlanType) || OutputVlanType.NONE.equals(outputVlanType));
    }

    /**
     * Validates output vlan operation type value by input vlan tag.
     *
     * @param inputVlanId    input vlan id
     * @param outputVlanType output vlan operation type
     * @return true if output vlan operation type is valid
     */
    public static boolean validateInputVlanType(final Integer inputVlanId, final OutputVlanType outputVlanType) {
        return (inputVlanId != null && inputVlanId != 0)
                ? (OutputVlanType.POP.equals(outputVlanType) || OutputVlanType.REPLACE.equals(outputVlanType))
                : (OutputVlanType.PUSH.equals(outputVlanType) || OutputVlanType.NONE.equals(outputVlanType));
    }

    /**
     * Return true if switch id is valid.
     *
     * @param switchId switch id.
     * @return true if switch id is valid.
     */
    public static boolean validateSwitchId(SwitchId switchId) {
        // TODO: check valid switch id
        return switchId != null;
    }

    /**
     * Returns value from map by key, throws exception otherwise.
     *
     * @param map map with keys and values
     * @param key key
     * @param clazz value will be cast to this class
     * @return value cast to the clazz
     */
    public static <T> T getValue(Map<String, ?> map, String key, Class<T> clazz) {
        if (map.containsKey(key)) {
            return clazz.cast(map.get(key));
        } else {
            throw new IllegalArgumentException(String.format("Missed property %s in map %s", key, map));
        }
    }
}

