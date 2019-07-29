package org.openkilda.persistence.tests;

import static java.lang.String.format;
import static java.util.Collections.emptyMap;

import org.openkilda.model.IslStatus;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;

import lombok.Value;
import org.neo4j.ogm.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public class TopologyForPceBuilder {
    private final Session session;
    private final int islandsCount;
    private final int regionsPerIsland;
    private final int switchesPerRegion;

    public TopologyForPceBuilder(Session session,
                                 int islandsCount, int regionsPerIsland, int switchesPerRegion) {
        this.session = session;
        this.islandsCount = islandsCount;
        this.regionsPerIsland = regionsPerIsland;
        this.switchesPerRegion = switchesPerRegion;
    }

    public List<Island> buildCircles() {
        List<Island> islands = new ArrayList<>();

        IntStream.rangeClosed(1, islandsCount).forEach(i -> {
            Island newIsland = new Island();

            IntStream.rangeClosed(1, regionsPerIsland).forEach(r -> {
                Region newRegion = new Region();

                IntStream.rangeClosed(1, switchesPerRegion).forEach(s -> {
                    SwitchId switchId = new SwitchId(format("%02X:%02X:%02X", i, r, s));
                    Switch newSw = buildSwitch(switchId);

                    if (!newRegion.switches.isEmpty()) {
                        Switch prevSw = newRegion.switches.get(newRegion.switches.size() - 1);

                        buildIsl(prevSw, 1000 + s, newSw, 1000 + s);
                        buildIsl(newSw, 2000 + s, prevSw, 2000 + s);
                    }

                    newRegion.switches.add(newSw);
                });

                Switch first = newRegion.switches.get(0);
                Switch last = newRegion.switches.get(newRegion.switches.size() - 1);

                buildIsl(first, 3000, last, 3000);
                buildIsl(last, 3001, first, 3001);

                if (!newIsland.regions.isEmpty()) {
                    Region prevRegion = newIsland.regions.get(newIsland.regions.size() - 1);
                    Switch prevRegionSwitch = prevRegion.switches.get(prevRegion.switches.size() / 2);
                    Switch newRegionSwitch = newRegion.switches.get(0);

                    buildIsl(prevRegionSwitch, 3002, newRegionSwitch, 3002);
                    buildIsl(newRegionSwitch, 3003, prevRegionSwitch, 3003);
                }

                newIsland.regions.add(newRegion);
            });

            Switch firstRegionSwitch = newIsland.regions.get(0).switches.get(0);
            Region lastRegion = newIsland.regions.get(newIsland.regions.size() - 1);
            Switch lastRegionSwitch = lastRegion.switches.get(lastRegion.switches.size() / 2);

            buildIsl(firstRegionSwitch, 3000, lastRegionSwitch, 3000);
            buildIsl(lastRegionSwitch, 3001, firstRegionSwitch, 3001);

            islands.add(newIsland);
        });

        return islands;
    }

    public List<Island> buildStars(int raysPerRegion) {
        List<Island> islands = new ArrayList<>();

        IntStream.rangeClosed(1, islandsCount).forEach(i -> {
            Island newIsland = new Island();

            IntStream.rangeClosed(1, regionsPerIsland).forEach(r -> {
                Region newRegion = new Region();

                SwitchId eyeSwitchId = new SwitchId(format("%02X:%02X:FF:FF", i, r));
                Switch newRegionEyeSw = buildSwitch(eyeSwitchId);
                newRegion.switches.add(newRegionEyeSw);

                int switchesPerRay = switchesPerRegion / raysPerRegion;
                IntStream.rangeClosed(1, raysPerRegion).forEach(y -> {
                    List<Switch> raySwitches = new ArrayList<>();

                    IntStream.rangeClosed(1, switchesPerRay).forEach(s -> {
                        SwitchId switchId = new SwitchId(format("%02X:%02X:%02X:%02X", i, r, y, s));
                        Switch newSw = buildSwitch(switchId);

                        Switch prevSw = raySwitches.isEmpty() ? newRegionEyeSw : raySwitches.get(raySwitches.size() - 1);

                        buildIsl(prevSw, 1000 + s, newSw, 1000 + s);
                        buildIsl(newSw, 2000 + s, prevSw, 2000 + s);

                        raySwitches.add(newSw);
                    });

                    newRegion.switches.addAll(raySwitches);
                });

                if (!newIsland.regions.isEmpty()) {
                    Switch eyeRegionSwitch = newIsland.regions.get(0).switches.get(0);

                    buildIsl(eyeRegionSwitch, 3000, newRegionEyeSw, 3000);
                    buildIsl(newRegionEyeSw, 3001, eyeRegionSwitch, 3001);
                }

                newIsland.regions.add(newRegion);
            });

            islands.add(newIsland);
        });

        return islands;
    }

    public List<Island> buildMeshes() {
        List<Island> islands = new ArrayList<>();

        IntStream.rangeClosed(1, islandsCount).forEach(i -> {
            Island newIsland = new Island();

            IntStream.rangeClosed(1, regionsPerIsland).forEach(r -> {
                Region newRegion = new Region();

                IntStream.rangeClosed(1, switchesPerRegion).forEach(s -> {
                    SwitchId switchId = new SwitchId(format("%02X:%02X:%02X", i, r, s));
                    Switch newSw = buildSwitch(switchId);

                    int index = 100;
                    for (Switch prevSw : newRegion.switches) {
                        buildIsl(prevSw, 1000 + index + s, newSw, 1000 + index + s);
                        buildIsl(newSw, 2000 + index + s, prevSw, 2000 + index + s);
                        index += 100;
                    }

                    newRegion.switches.add(newSw);
                });

                int index = 100;
                for (Region prevRegion : newIsland.regions) {
                    Switch prevRegionSwitch = prevRegion.switches.get(prevRegion.switches.size() / 2);
                    Switch newRegionSwitch = newRegion.switches.get(0);

                    buildIsl(prevRegionSwitch, 3000 + index, newRegionSwitch, 3000 + index);
                    buildIsl(newRegionSwitch, 3001 + index, prevRegionSwitch, 3001 + index);
                }

                newIsland.regions.add(newRegion);
            });

            islands.add(newIsland);
        });

        return islands;
    }

    public List<Island> buildTree(int branchesPerTreeKnot) {
        List<Island> islands = new ArrayList<>();

        IntStream.rangeClosed(1, islandsCount).forEach(i -> {
            Island newIsland = new Island();

            IntStream.rangeClosed(1, regionsPerIsland).forEach(r -> {
                Region newRegion = new Region();

                String eyeSwitchId = format("%02X:%02X", i, r);
                Switch newRegionEyeSwitch = buildSwitch(new SwitchId(eyeSwitchId));
                newRegion.switches.add(newRegionEyeSwitch);

                buildSubTree(newRegion, newRegionEyeSwitch, eyeSwitchId, branchesPerTreeKnot, switchesPerRegion - 1);

                if (!newIsland.regions.isEmpty()) {
                    List<Switch> lastRegionSwitches = newIsland.regions.get(newIsland.regions.size() - 1).switches;
                    Switch lastRegionSwitch = lastRegionSwitches.get(lastRegionSwitches.size() - 1);

                    buildIsl(lastRegionSwitch, 3000, newRegionEyeSwitch, 3000);
                    buildIsl(newRegionEyeSwitch, 3001, lastRegionSwitch, 3001);
                }

                newIsland.regions.add(newRegion);
            });

            islands.add(newIsland);
        });

        return islands;
    }

    private void buildSubTree(Region region, Switch knotSwitch, String knotSwitchId,
                              int branchesPerTreeKnot, int switchesLeft) {
        if (switchesLeft <= 0) {
            return;
        }

        int switchesPerKnot = switchesLeft / branchesPerTreeKnot;

        for (int b = 1; b <= Math.min(switchesPerKnot, branchesPerTreeKnot); b++) {
            String switchId = format("%02X:%s", b, knotSwitchId);
            Switch newSw = buildSwitch(new SwitchId(switchId));
            region.switches.add(newSw);

            buildIsl(knotSwitch, 1000 + b, newSw, 1000 + b);
            buildIsl(newSw, 2000 + b, knotSwitch, 2000 + b);

            if (switchesPerKnot > 1) {
                buildSubTree(region, newSw, switchId, branchesPerTreeKnot, switchesPerKnot - 1);
            }
        }
    }

    private Switch buildSwitch(SwitchId switchId) {
        session.query(format("CREATE (:switch {name:'%s', state:'%s'})-[:has]->(:switch_features "
                        + "{support_bfd:false, support_meters:false, support_vxlan_push_pop:false,"
                        + "support_vxlan_vni_match:false, supported_transit_encapsulation:['TRANSIT_VLAN']})",
                switchId.toString(), SwitchStatus.ACTIVE.name().toLowerCase()), emptyMap());

        return Switch.builder().switchId(switchId).build();
    }

    private void buildIsl(Switch srcSwitch, int srcPort, Switch destSwitch, int destPort) {
        session.query(format("MATCH (src:switch {name:'%s'}), (dest:switch {name:'%s'}) "
                        + "MERGE (src)-[:isl {src_port:%d, dst_port:%d, cost:%d, available_bandwidth:%d,"
                        + "status:'%s'}]->(dest)",
                srcSwitch.getSwitchId().toString(), destSwitch.getSwitchId().toString(),
                srcPort, destPort, 1, 1, IslStatus.ACTIVE.name().toLowerCase()), emptyMap());
    }

    @Value
    public static class Island {
        List<Region> regions = new ArrayList<>();
    }

    @Value
    public static class Region {
        List<Switch> switches = new ArrayList<>();
    }
}