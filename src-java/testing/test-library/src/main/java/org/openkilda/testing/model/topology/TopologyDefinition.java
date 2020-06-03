/* Copyright 2018 Telstra Open Source
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

package org.openkilda.testing.model.topology;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import org.openkilda.model.SwitchId;
import org.openkilda.testing.service.lockkeeper.model.ASwitchFlow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.databind.PropertyNamingStrategy.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.NonFinal;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Defines a topology with switches, links and traffgens.
 * <p/>
 * Topology definition objects are immutable and can't be changed after creation.
 */
@Getter
@RequiredArgsConstructor
@JsonNaming(SnakeCaseStrategy.class)
public class TopologyDefinition {

    @NonNull
    protected List<Switch> switches;
    @NonNull
    protected List<Isl> isls;
    @NonNull
    protected List<TraffGen> traffGens;
    @NonNull
    protected TraffGenConfig traffGenConfig;
    @SuppressWarnings("squid:S1450")

    protected Integer bfdOffset;

    /**
     * Creates TopologyDefinition instance.
     */
    @JsonCreator
    public static TopologyDefinition factory(
            @JsonProperty("switches") List<Switch> switches,
            @JsonProperty("isls") List<Isl> isls,
            @JsonProperty("traff_gens") List<TraffGen> traffGens,
            @JsonProperty("traff_gen_config") TraffGenConfig traffGenConfig) {

        Preconditions.checkArgument(
                switches.size() == switches.stream().map(Switch::getDpId).distinct().count(),
                "Switches must have no duplicates");
        Preconditions.checkArgument(
                isls.size() == isls.stream().distinct().count(), "Isls must have no duplicates");
        Preconditions.checkArgument(
                traffGens.size() == traffGens.stream().map(TraffGen::getName).distinct().count(),
                "TraffGens must have no duplicates");

        return new TopologyDefinition(
                unmodifiableList(switches),
                unmodifiableList(isls),
                unmodifiableList(traffGens),
                traffGenConfig);
    }

    public void setBfdOffset(Integer bfdOffset) {
        this.bfdOffset = bfdOffset;
    }

    /**
     * Get all switches that are marked as active in config.
     */
    @JsonIgnore
    public List<Switch> getActiveSwitches() {
        return switches.stream()
                .filter(Switch::isActive)
                .collect(toList());
    }

    /**
     * Get all switches that are marked as skipped in config.
     */
    @JsonIgnore
    public Set<SwitchId> getSkippedSwitchIds() {
        return switches.stream()
                .filter(sw -> sw.getStatus() == Status.Skip)
                .map(Switch::getDpId)
                .collect(toSet());
    }

    /**
     * Get all ISLs for switches that are marked as active in config.
     */
    @JsonIgnore
    public List<Isl> getIslsForActiveSwitches() {
        return isls.stream()
                .filter(isl -> isl.getDstSwitch() != null
                        && isl.getSrcSwitch().isActive() && isl.getDstSwitch().isActive())
                .collect(toList());
    }

    /**
     * Get list of ISLs that are connected only at one side (no destination switch).
     * The other side is usually an a-switch.
     */
    @JsonIgnore
    public List<Isl> getNotConnectedIsls() {
        return isls.stream()
                .filter(isl -> isl.getSrcSwitch() != null && isl.getSrcSwitch().isActive()
                        && isl.getDstSwitch() == null)
                .collect(toList());
    }

    /**
     * Get list of switch ports excluding the ports which are busy with ISLs.
     */
    @JsonIgnore
    public List<Integer> getAllowedPortsForSwitch(Switch sw) {
        List<Integer> allPorts = new ArrayList<>(sw.getAllPorts());
        allPorts.removeAll(getIslsForActiveSwitches().stream().filter(isl ->
                isl.getSrcSwitch().getDpId().equals(sw.getDpId())).map(Isl::getSrcPort).collect(toList()));
        allPorts.removeAll(getIslsForActiveSwitches().stream().filter(isl ->
                isl.getDstSwitch().getDpId().equals(sw.getDpId())).map(Isl::getDstPort).collect(toList()));
        if (sw.prop != null) {
            allPorts.remove(sw.prop.server42Port);
        }
        return allPorts;
    }

    public Switch find(SwitchId id) {
        return switches.stream().filter(sw -> sw.getDpId().equals(id)).findFirst().get();
    }

    /**
     * Get list of switch ports that have ISLs.
     */
    @JsonIgnore
    public List<Integer> getBusyPortsForSwitch(Switch sw) {
        return getRelatedIsls(sw).stream().map(Isl::getSrcPort).collect(toList());
    }

    /**
     * Get list of ISLs that are related to a certain switch.
     * Returns only outgoing from the switch ISLs. The caller will have to mirror them in order to get full list of
     * actual ISLs.
     */
    @JsonIgnore
    public List<Isl> getRelatedIsls(Switch sw) {
        List<Isl> isls = getIslsForActiveSwitches().stream().filter(isl ->
                isl.getSrcSwitch().getDpId().equals(sw.getDpId()) || isl.getDstSwitch().getDpId().equals(sw.getDpId()))
                .collect(toList());
        for (Isl isl : isls) {
            if (isl.getDstSwitch().getDpId().equals(sw.getDpId())) {
                isls.set(isls.indexOf(isl), isl.getReversed());
            }
        }
        return isls;
    }

    /**
     * Get all switches that are marked as active in config with server42 enabled.
     */
    @JsonIgnore
    public List<Switch> getActiveServer42Switches() {
        return switches.stream()
                .filter(Switch::isActive)
                .filter(s -> s.prop != null)
                .collect(toList());
    }

    @Value
    @NonFinal
    @JsonNaming(SnakeCaseStrategy.class)
    @JsonIdentityInfo(property = "name", generator = ObjectIdGenerators.PropertyGenerator.class)
    public static class Switch {

        private static int DEFAULT_MAX_PORT = 100;

        private String name;
        @NonNull
        private SwitchId dpId;
        @NonNull
        private String ofVersion;
        @NonNull
        private Status status;
        @NonNull
        private List<String> regions;
        @NonNull
        private List<OutPort> outPorts;
        private Integer maxPort;

        @NonFinal
        private String controller;

        private SwitchProperties prop;

        /**
         * Create a Switch instance.
         */
        @JsonCreator
        public static Switch factory(
                @JsonProperty("name") String name,
                @JsonProperty("dp_id") SwitchId dpId,
                @JsonProperty("of_version") String ofVersion,
                @JsonProperty("status") Status status,
                @JsonProperty("regions") List<String> regions,
                @JsonProperty("out_ports") List<OutPort> outPorts,
                @JsonProperty("max_port") Integer maxPort,
                @JsonProperty("controller") String controller,
                @JsonProperty("prop") SwitchProperties properties) {
            if (outPorts == null) {
                outPorts = emptyList();
            }
            if (maxPort == null) {
                maxPort = DEFAULT_MAX_PORT;
            }

            return new Switch(name, dpId, ofVersion, status, regions, outPorts, maxPort, controller, properties);
        }

        @JsonIgnore
        public boolean isActive() {
            return status == Status.Active;
        }

        /**
         * Get list of all available ports on this switch.
         */
        @JsonIgnore
        public List<Integer> getAllPorts() {
            return IntStream.rangeClosed(1, maxPort).boxed().collect(toList());
        }

        public void setController(String controller) {
            this.controller = controller;
        }
    }

    @Value
    @NonFinal
    @JsonNaming(SnakeCaseStrategy.class)
    public static class OutPort {

        private int port;
        @NonNull
        @JsonSerialize(using = ToStringSerializer.class)
        private RangeSet<Integer> vlanRange;

        @JsonCreator
        public static OutPort factory(
                @JsonProperty("port") int port,
                @JsonProperty("vlan_range") String vlanRange) {

            return new OutPort(port, parseVlanRange(vlanRange));
        }

        private static RangeSet<Integer> parseVlanRange(String vlanRangeAsStr) {
            vlanRangeAsStr = StringUtils.strip(StringUtils.strip(vlanRangeAsStr, "["), "]");
            String[] splitRanges = vlanRangeAsStr.split(",");
            if (splitRanges.length == 0) {
                throw new IllegalArgumentException("Vlan range must be non-empty.");
            }

            ImmutableRangeSet.Builder<Integer> resultVlanRange = ImmutableRangeSet.builder();
            for (String range : splitRanges) {
                String[] boundaries = range.split("\\.\\.");
                if (boundaries.length == 0 || boundaries.length > 2) {
                    throw new IllegalArgumentException("Range " + range + " is not valid.");
                }

                int lowerBound = Integer.parseInt(boundaries[0].trim());
                if (boundaries.length == 2) {
                    int upperBound = Integer.parseInt(boundaries[1].trim());
                    resultVlanRange.add(Range.closed(lowerBound, upperBound));
                } else {
                    resultVlanRange.add(Range.closed(lowerBound, lowerBound));
                }
            }

            return resultVlanRange.build();
        }
    }

    @Value
    @NonFinal
    @JsonNaming(SnakeCaseStrategy.class)
    public static class SwitchProperties {

        @JsonProperty("server42_flow_rtt")
        private Boolean server42FlowRtt;

        @JsonProperty("server42_port")
        private Integer server42Port;

        @JsonProperty("server42_mac_address")
        private String server42MacAddress;

        @JsonProperty("server42_vlan")
        private Integer server42Vlan;


        public SwitchProperties(@JsonProperty("server42_flow_rtt") Boolean server42FlowRtt,
                                @JsonProperty("server42_port") Integer server42Port,
                                @JsonProperty("server42_mac_address") String server42MacAddress,
                                @JsonProperty("server42_vlan") Integer server42Vlan) {
            this.server42FlowRtt = server42FlowRtt;
            this.server42Port = server42Port;
            this.server42MacAddress = server42MacAddress;
            this.server42Vlan = server42Vlan;
        }
    }

    @Value
    @NonFinal
    @JsonNaming(SnakeCaseStrategy.class)
    public static class Isl {

        @NonNull
        private Switch srcSwitch;
        private int srcPort;
        private Switch dstSwitch;
        private int dstPort;
        private long maxBandwidth;
        private ASwitchFlow aswitch;

        @JsonCreator
        public static Isl factory(
                @JsonProperty("src_switch") Switch srcSwitch,
                @JsonProperty("src_port") int srcPort,
                @JsonProperty("dst_switch") Switch dstSwitch,
                @JsonProperty("dst_port") int dstPort,
                @JsonProperty("max_bandwidth") long maxBandwidth,
                @JsonProperty("a_switch") ASwitchFlow aswitch) {
            return new Isl(srcSwitch, srcPort, dstSwitch, dstPort, maxBandwidth, aswitch);
        }

        @Override
        public String toString() {
            return String.format("%s-%s -> %s-%s", srcSwitch.dpId.toString(), srcPort,
                    dstSwitch != null ? dstSwitch.dpId.toString() : "null", dstSwitch != null ? dstPort : "null");
        }

        /**
         * Returns the 'reverse' version of this ISL.
         */
        @JsonIgnore
        public Isl getReversed() {
            if (this.getDstSwitch() == null) {
                return this; //don't reverse not connected ISL
            }
            ASwitchFlow reversedAsw = null;
            if (this.getAswitch() != null) {
                reversedAsw = this.getAswitch().getReversed();
            }
            return Isl.factory(this.getDstSwitch(), this.getDstPort(), this.getSrcSwitch(),
                    this.getSrcPort(), this.getMaxBandwidth(), reversedAsw);
        }
    }

    @Value
    @NonFinal
    @JsonNaming(SnakeCaseStrategy.class)
    public static class TraffGen {

        @NonNull
        private String name;
        @NonNull
        private String controlEndpoint;
        @NonNull
        private String ifaceName;
        @NonNull
        private Switch switchConnected;
        private int switchPort;
        @NonNull
        private Status status;

        @JsonCreator
        public static TraffGen factory(
                @JsonProperty("name") String name,
                @JsonProperty("iface") String ifaceName,
                @JsonProperty("control_endpoint") String controlEndpoint,
                @JsonProperty("switch") Switch switchConnected,
                @JsonProperty("switch_port") int switchPort,
                @JsonProperty("status") Status status) {
            return new TraffGen(name, controlEndpoint, ifaceName, switchConnected, switchPort, status);
        }

        public boolean isActive() {
            return status == Status.Active;
        }
    }

    /**
     * Get all traffgens that are marked as 'active' in config.
     */
    @JsonIgnore
    public List<TraffGen> getActiveTraffGens() {
        return traffGens.stream()
                .filter(TraffGen::isActive)
                .filter(traffGen -> traffGen.getSwitchConnected().isActive())
                .collect(toList());
    }

    /**
     * Get switch for certain traffgen.
     */
    @JsonIgnore
    public TraffGen getTraffGen(SwitchId swId) {
        return traffGens.stream()
                .filter(traffGen -> traffGen.getSwitchConnected().getDpId().equals(swId))
                .findFirst().orElseThrow(() -> new RuntimeException("Switch has no traffgen"));
    }

    @Value
    @NonFinal
    @JsonNaming(SnakeCaseStrategy.class)
    public static class TraffGenConfig {

        @NonNull
        private String addressPoolBase;
        private int addressPoolPrefixLen;

        public static TraffGenConfig defaultConfig() {
            return new TraffGenConfig("172.16.80.0", 20);
        }

        @JsonCreator
        public static TraffGenConfig factory(
                @JsonProperty("address_pool_base") String addressPoolBase,
                @JsonProperty("address_pool_prefix_len") int addressPoolPrefixLen) {
            return new TraffGenConfig(addressPoolBase, addressPoolPrefixLen);
        }
    }

    public enum Status {
        Active,
        Inactive,
        Skip
    }
}
