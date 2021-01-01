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

package org.openkilda.pce.finder;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.SwitchId;
import org.openkilda.pce.exception.UnroutableFlowException;
import org.openkilda.pce.impl.AvailableNetwork;
import org.openkilda.pce.model.Edge;
import org.openkilda.pce.model.FindPathResult;
import org.openkilda.pce.model.PathWeight;
import org.openkilda.pce.model.WeightFunction;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BestWeightAndShortestPathFinderTest {

    private static final int ALLOWED_DEPTH = 35;
    private static final WeightFunction WEIGHT_FUNCTION = edge -> {
        long total = edge.getCost();
        if (edge.isUnderMaintenance()) {
            total += 10_000;
        }
        if (edge.isUnstable()) {
            total += 10_000;
        }
        total += edge.getDiversityGroupUseCounter() * 1000 + edge.getDestSwitch().getDiversityGroupUseCounter() * 100;
        return new PathWeight(total);
    };

    private static final SwitchId SWITCH_ID_A = new SwitchId("00:00:00:22:3d:5a:04:87");
    private static final SwitchId SWITCH_ID_B = new SwitchId("00:00:70:72:cf:d2:48:6c");
    private static final SwitchId SWITCH_ID_C = new SwitchId("00:00:00:22:3d:6c:00:b8");
    private static final SwitchId SWITCH_ID_D = new SwitchId("00:00:00:22:3d:6b:00:04");
    private static final SwitchId SWITCH_ID_E = new SwitchId("00:00:70:72:cf:d2:47:a6");
    private static final SwitchId SWITCH_ID_F = new SwitchId("00:00:b0:d2:f5:00:5a:b8");

    private static final SwitchId SWITCH_ID_1 = new SwitchId("00:00:00:00:00:00:00:01");
    private static final SwitchId SWITCH_ID_2 = new SwitchId("00:00:00:00:00:00:00:02");
    private static final SwitchId SWITCH_ID_3 = new SwitchId("00:00:00:00:00:00:00:03");
    private static final SwitchId SWITCH_ID_4 = new SwitchId("00:00:00:00:00:00:00:04");
    private static final SwitchId SWITCH_ID_5 = new SwitchId("00:00:00:00:00:00:00:05");

    @Test
    public void shouldChooseExpensiveOverTooDeep() throws UnroutableFlowException {
        AvailableNetwork network = buildLongAndExpensivePathsNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(2);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_4, WEIGHT_FUNCTION).getFoundPath();
        List<Edge> fpath = pairPath.getLeft();
        assertThat(fpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_2, fpath.get(1).getSrcSwitch().getSwitchId());

        List<Edge> rpath = pairPath.getRight();
        assertThat(rpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_2, rpath.get(0).getDestSwitch().getSwitchId());
    }

    @Test
    public void shouldChooseExpensiveOverTooDeepForReverseOrder()
            throws UnroutableFlowException {
        AvailableNetwork network = buildLongAndExpensivePathsNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(2);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_4, SWITCH_ID_1, WEIGHT_FUNCTION).getFoundPath();
        List<Edge> fpath = pairPath.getLeft();
        assertThat(fpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_2, fpath.get(1).getSrcSwitch().getSwitchId());

        List<Edge> rpath = pairPath.getRight();
        assertThat(rpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_2, rpath.get(0).getDestSwitch().getSwitchId());
    }

    @Test
    public void shouldChooseDeeperOverExpensive() throws UnroutableFlowException {
        AvailableNetwork network = buildLongAndExpensivePathsNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(4);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_4, WEIGHT_FUNCTION).getFoundPath();
        List<Edge> fpath = pairPath.getLeft();
        assertThat(fpath, Matchers.hasSize(4));
        assertEquals(SWITCH_ID_5, fpath.get(3).getSrcSwitch().getSwitchId());

        List<Edge> rpath = pairPath.getRight();
        assertThat(rpath, Matchers.hasSize(4));
        assertEquals(SWITCH_ID_5, rpath.get(0).getDestSwitch().getSwitchId());
    }

    @Test
    public void shouldChooseCheaperWithSameDepth() throws UnroutableFlowException {
        AvailableNetwork network = buildLongAndExpensivePathsNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(3);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_5, WEIGHT_FUNCTION).getFoundPath();
        List<Edge> fpath = pairPath.getLeft();
        assertThat(fpath, Matchers.hasSize(3));
        assertEquals(SWITCH_ID_3, fpath.get(2).getSrcSwitch().getSwitchId());

        List<Edge> rpath = pairPath.getRight();
        assertThat(rpath, Matchers.hasSize(3));
        assertEquals(SWITCH_ID_3, rpath.get(0).getDestSwitch().getSwitchId());
    }

    @Test
    public void shouldChooseCheaperOverTooDeepMaxWeightStrategy() throws UnroutableFlowException {
        AvailableNetwork network = buildLongAndExpensivePathsNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(2);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_3, WEIGHT_FUNCTION,
                        Long.MAX_VALUE, Long.MAX_VALUE).getFoundPath();
        List<Edge> fpath = pairPath.getLeft();
        assertThat(fpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_2, fpath.get(1).getSrcSwitch().getSwitchId());

        List<Edge> rpath = pairPath.getRight();
        assertThat(rpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_2, rpath.get(0).getDestSwitch().getSwitchId());
    }

    @Test
    public void shouldChooseCheaperOverTooDeepForReverseOrderMaxWeightStrategy()
            throws UnroutableFlowException {
        AvailableNetwork network = buildLongAndExpensivePathsNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(2);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_3, SWITCH_ID_1, WEIGHT_FUNCTION,
                        Long.MAX_VALUE, Long.MAX_VALUE).getFoundPath();
        List<Edge> fpath = pairPath.getLeft();
        assertThat(fpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_2, fpath.get(1).getSrcSwitch().getSwitchId());

        List<Edge> rpath = pairPath.getRight();
        assertThat(rpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_2, rpath.get(0).getDestSwitch().getSwitchId());
    }

    @Test
    public void shouldChooseDeeperOverCheaperMaxWeightStrategy() throws UnroutableFlowException {
        AvailableNetwork network = buildLongAndExpensivePathsNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(4);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_3, WEIGHT_FUNCTION,
                        Long.MAX_VALUE, Long.MAX_VALUE).getFoundPath();
        List<Edge> fpath = pairPath.getLeft();
        assertThat(fpath, Matchers.hasSize(4));
        assertEquals(SWITCH_ID_5, fpath.get(3).getSrcSwitch().getSwitchId());

        List<Edge> rpath = pairPath.getRight();
        assertThat(rpath, Matchers.hasSize(4));
        assertEquals(SWITCH_ID_5, rpath.get(0).getDestSwitch().getSwitchId());
    }

    @Test
    public void shouldChooseExpensiveWithSameDepthMaxWeightStrategy() throws UnroutableFlowException {
        AvailableNetwork network = buildLongAndExpensivePathsNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(3);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_5, WEIGHT_FUNCTION,
                        Long.MAX_VALUE, Long.MAX_VALUE).getFoundPath();
        List<Edge> fpath = pairPath.getLeft();
        assertThat(fpath, Matchers.hasSize(3));
        assertEquals(SWITCH_ID_4, fpath.get(2).getSrcSwitch().getSwitchId());

        List<Edge> rpath = pairPath.getRight();
        assertThat(rpath, Matchers.hasSize(3));
        assertEquals(SWITCH_ID_4, rpath.get(0).getDestSwitch().getSwitchId());
    }

    private AvailableNetwork buildLongAndExpensivePathsNetwork() {
        /*
         *   Topology:
         *
         *   SW1---SW2~~~SW4
         *          |     |
         *         SW3---SW5
         *
         *   SW2 - SW4 is expensive by cost.
         */
        AvailableNetwork network = new AvailableNetwork();
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_2, 1, 2, 100);
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_4, 3, 4, 10000);
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_3, 5, 6, 100);
        addBidirectionalLink(network, SWITCH_ID_3, SWITCH_ID_5, 7, 8, 100);
        addBidirectionalLink(network, SWITCH_ID_4, SWITCH_ID_5, 9, 10, 100);

        network.reduceByWeight(WEIGHT_FUNCTION);
        return network;
    }

    @Test(expected = UnroutableFlowException.class)
    public void shouldFailWhenPathIsLongerThenAllowedDepth() throws UnroutableFlowException {
        AvailableNetwork network = buildTestNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(1);
        pathFinder.findPathInNetwork(network, SWITCH_ID_D, SWITCH_ID_F, WEIGHT_FUNCTION);
    }

    @Test(expected = UnroutableFlowException.class)
    public void shouldFailWhenPathIsLongerThenAllowedDepthMaxWeightStrategy() throws UnroutableFlowException {
        AvailableNetwork network = buildTestNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(1);
        pathFinder.findPathInNetwork(network, SWITCH_ID_D, SWITCH_ID_F, WEIGHT_FUNCTION,
                Long.MAX_VALUE, Long.MAX_VALUE);
    }

    @Test
    public void shouldReturnTheShortestPath() throws UnroutableFlowException {
        AvailableNetwork network = buildTestNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_E, SWITCH_ID_F, WEIGHT_FUNCTION).getFoundPath();
        List<Edge> fpath = pairPath.getLeft();
        assertThat(fpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_E, fpath.get(0).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_F, fpath.get(1).getDestSwitch().getSwitchId());

        List<Edge> rpath = pairPath.getRight();
        assertThat(rpath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_F, rpath.get(0).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_E, rpath.get(1).getDestSwitch().getSwitchId());
    }

    /**
     * System picks path closest to maxWeight. Omit too cheap path, omit equal to maxWeight path
     */
    @Test
    public void shouldReturnThePathClosestToMaxWeight() throws UnroutableFlowException {
        // a path with maxWeight 201
        FindPathResult pathResult = findThePathClosestToMaxWeight(201L, Long.MAX_VALUE);
        assertFalse(pathResult.isBackUpPathComputationWayUsed());
    }

    /**
     * System picks path closest to maxWeightTier2. Omit too cheap path and maxWeight path,
     * omit equal to backUpMaxWeight path
     */
    @Test
    public void shouldReturnThePathClosestToBackUpMaxWeight() throws UnroutableFlowException {
        // a path with backUpMaxWeight 201
        FindPathResult pathResult = findThePathClosestToMaxWeight(100L, 201L);
        assertTrue(pathResult.isBackUpPathComputationWayUsed());
    }

    private FindPathResult findThePathClosestToMaxWeight(long maxWeight, long backUpMaxWeight)
            throws UnroutableFlowException {
        //given 3 paths that cost: 198, 200, 201
        AvailableNetwork network = buildThreePathsNetwork();
        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        //when: request a path with maxWeight 201
        FindPathResult pathResult = pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_5, WEIGHT_FUNCTION,
                maxWeight, backUpMaxWeight);
        Pair<List<Edge>, List<Edge>> pairPath = pathResult.getFoundPath();

        //then: system picks 200 path
        List<SwitchId> forwardSwitches = getInvolvedSwitches(pairPath.getLeft());
        assertThat(forwardSwitches, equalTo(Arrays.asList(SWITCH_ID_1, SWITCH_ID_3, SWITCH_ID_5)));
        assertThat(getInvolvedSwitches(pairPath.getRight()), equalTo(Lists.reverse(forwardSwitches)));

        return pathResult;
    }

    /**
     * Ensure system picks path closest to maxWeight from the bottom. Omit closest path from the top even if it is
     * closer than the one from the bottom
     */
    @Test
    public void shouldReturnThePathBottomClosestToMaxWeight() throws UnroutableFlowException {
        // a path with maxWeight 200
        FindPathResult pathResult = findThePathBottomClosestToMaxWeight(200L, Long.MAX_VALUE);
        assertFalse(pathResult.isBackUpPathComputationWayUsed());
    }

    /**
     * Ensure system picks path closest to backUpMaxWeight from the bottom. Omit closest path from the top even if it is
     * closer than the one from the bottom
     */
    @Test
    public void shouldReturnThePathBottomClosestToBackUpMaxWeight() throws UnroutableFlowException {
        // a path with backUpMaxWeight 200
        FindPathResult pathResult = findThePathBottomClosestToMaxWeight(100L, 200L);
        assertTrue(pathResult.isBackUpPathComputationWayUsed());
    }

    private FindPathResult findThePathBottomClosestToMaxWeight(long maxWeight, long backUpMaxWeight)
            throws UnroutableFlowException {
        //given 3 paths that cost: 198, 200, 201
        AvailableNetwork network = buildThreePathsNetwork();
        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        //when: request a path with maxWeight 200
        FindPathResult pathResult = pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_5, WEIGHT_FUNCTION,
                maxWeight, backUpMaxWeight);
        Pair<List<Edge>, List<Edge>> pairPath = pathResult.getFoundPath();

        //then: system picks 198 path
        List<SwitchId> forwardSwitches = getInvolvedSwitches(pairPath.getLeft());
        assertThat(forwardSwitches, equalTo(Arrays.asList(SWITCH_ID_1, SWITCH_ID_2, SWITCH_ID_5)));
        assertThat(getInvolvedSwitches(pairPath.getRight()), equalTo(Lists.reverse(forwardSwitches)));

        return pathResult;
    }

    /**
     * Check that we take into account both forward-way and reverse-way links when calculating path using MAX_LATENCY
     * strategy when maxWeight satisfies the path.
     */
    @Test
    public void maxWeightAccountsForBothLinkDirections() throws UnroutableFlowException {
        // a path with maxWeight 103
        FindPathResult pathResult = maxWeightStratAccountsForBothLinkDirections(103L, Long.MAX_VALUE);
        assertFalse(pathResult.isBackUpPathComputationWayUsed());
    }

    /**
     * Check that we take into account both forward-way and reverse-way links when calculating path using MAX_LATENCY
     * strategy when backUpMaxWeight satisfies the path.
     */
    @Test
    public void backUpMaxWeightAccountsForBothLinkDirections() throws UnroutableFlowException {
        // a path with backUpMaxWeight 103
        FindPathResult pathResult = maxWeightStratAccountsForBothLinkDirections(50L, 103L);
        assertTrue(pathResult.isBackUpPathComputationWayUsed());
    }

    private FindPathResult maxWeightStratAccountsForBothLinkDirections(long maxWeight, long backUpMaxWeight)
            throws UnroutableFlowException {
        //given 2 paths with costs: path1 forward 100, path1 reverse 102, path2 forward 101, path2 reverse 100
        AvailableNetwork network = new AvailableNetwork();
        addLink(network, SWITCH_ID_1, SWITCH_ID_2, 1, 1, 100, 0, false, false);
        addLink(network, SWITCH_ID_2, SWITCH_ID_1, 1, 1, 102, 0, false, false);
        addLink(network, SWITCH_ID_1, SWITCH_ID_2, 2, 2, 101, 0, false, false);
        addLink(network, SWITCH_ID_2, SWITCH_ID_1, 2, 2, 100, 0, false, false);
        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        //when: request a path with maxWeight 103
        FindPathResult pathResult = pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_2, WEIGHT_FUNCTION,
                maxWeight, backUpMaxWeight);
        Pair<List<Edge>, List<Edge>> pairPath = pathResult.getFoundPath();
        //then: pick path1, because its reverse cost is 102 which is the closest to 103
        //system skips path2 even though its forward cost is 101, which is closer to 103 than 100 (path1 forward)
        assertThat(pairPath.getLeft().get(0).getSrcPort(), equalTo(1));
        assertThat(pairPath.getRight().get(0).getSrcPort(), equalTo(1));

        return pathResult;
    }

    /**
     * Check that we take into account ONLY forward-way links when calculating path using LATENCY strategy.
     */
    @Test
    public void latencyStratUsesOnlyForwardLinksWeight() throws UnroutableFlowException {
        //given 2 paths with costs: path1 forward 101, path1 reverse 99, path2 forward 100, path2 reverse 102
        AvailableNetwork network = new AvailableNetwork();
        addLink(network, SWITCH_ID_1, SWITCH_ID_2, 1, 1, 101, 0, false, false);
        addLink(network, SWITCH_ID_2, SWITCH_ID_1, 1, 1, 99, 0, false, false);
        addLink(network, SWITCH_ID_1, SWITCH_ID_2, 2, 2, 100, 0, false, false);
        addLink(network, SWITCH_ID_2, SWITCH_ID_1, 2, 2, 102, 0, false, false);
        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        //when: request a best-latency path
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_2, WEIGHT_FUNCTION).getFoundPath();
        //then: pick path2, because its forward cost is 100 which is less than forward 101 of path1
        //system ignores that path1 reverse cost is actually the best of all (99), since it only uses forward
        assertThat(pairPath.getLeft().get(0).getSrcPort(), equalTo(2));
        assertThat(pairPath.getRight().get(0).getSrcPort(), equalTo(2));
    }

    /**
     * Fail to find a path if all available paths cost more or equal to maxWeight.
     */
    @Test(expected = UnroutableFlowException.class)
    public void shouldFailIfNoPathLessThanMaxWeightOrBackUpMaxWeight() throws UnroutableFlowException {
        //given 3 paths that cost: 198, 200, 201
        AvailableNetwork network = buildThreePathsNetwork();
        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        //when: request a path with maxWeight 198
        pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_5, WEIGHT_FUNCTION, 198L, 198L);
        //then: no path found
    }

    @Test(expected = UnroutableFlowException.class)
    public void failToFindASwitch() throws UnroutableFlowException {
        AvailableNetwork network = buildTestNetwork();

        SwitchId srcDpid = new SwitchId("00:00:00:00:00:00:00:ff");

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        pathFinder.findPathInNetwork(network, srcDpid, SWITCH_ID_F, WEIGHT_FUNCTION);
    }

    @Test(expected = UnroutableFlowException.class)
    public void failToFindASwitchMaxWeightStrategy() throws UnroutableFlowException {
        AvailableNetwork network = buildTestNetwork();

        SwitchId srcDpid = new SwitchId("00:00:00:00:00:00:00:ff");

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        pathFinder.findPathInNetwork(network, srcDpid, SWITCH_ID_F, WEIGHT_FUNCTION,
                Long.MAX_VALUE, Long.MAX_VALUE);
    }

    @Test
    public void testForwardAndBackwardPathsEquality() throws UnroutableFlowException {
        AvailableNetwork network = buildEqualCostsNetwork();
        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        Pair<List<Edge>, List<Edge>> paths =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_5, WEIGHT_FUNCTION).getFoundPath();

        List<SwitchId> forwardSwitchPath = getSwitchIdsFlowPath(paths.getLeft());
        List<SwitchId> backwardSwitchPath = Lists.reverse(getSwitchIdsFlowPath(paths.getRight()));
        assertEquals(forwardSwitchPath, backwardSwitchPath);
    }

    @Test
    public void shouldAddIntermediateSwitchWeightOnce() throws UnroutableFlowException {
        AvailableNetwork network = buildTestNetwork();
        // shouldn't affect path if added once
        network.getSwitch(SWITCH_ID_A).increaseDiversityGroupUseCounter();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        Pair<List<Edge>, List<Edge>> paths =
                pathFinder.findPathInNetwork(network, SWITCH_ID_D, SWITCH_ID_F, WEIGHT_FUNCTION).getFoundPath();

        assertEquals(Arrays.asList(SWITCH_ID_D, SWITCH_ID_A, SWITCH_ID_F), getSwitchIdsFlowPath(paths.getLeft()));
    }

    @Test
    public void shouldAddIntermediateSwitchWeightOnceMaxWeightStrategy() throws UnroutableFlowException {
        // a path with maxWeight 201
        FindPathResult pathResult = addIntermediateSwitchWeightOnceMaxWeightStrategy(201L, Long.MAX_VALUE);
        assertFalse(pathResult.isBackUpPathComputationWayUsed());
    }

    @Test
    public void shouldAddIntermediateSwitchWeightOnceMaxWeightStrategyBackUpWay() throws UnroutableFlowException {
        // a path with backUpMaxWeight 201
        FindPathResult pathResult = addIntermediateSwitchWeightOnceMaxWeightStrategy(100L, 201L);
        assertTrue(pathResult.isBackUpPathComputationWayUsed());
    }

    private FindPathResult addIntermediateSwitchWeightOnceMaxWeightStrategy(long maxWeight, long backUpMaxWeight)
            throws UnroutableFlowException {
        //given 3 paths that cost: 198, 200, 201
        AvailableNetwork network = buildThreePathsNetwork();
        //switch on '200' path has a diversity weight increase
        network.getSwitch(SWITCH_ID_3).increaseDiversityGroupUseCounter();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        //when: request a path with maxWeight 201
        FindPathResult pathResult = pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_5, WEIGHT_FUNCTION,
                maxWeight, backUpMaxWeight);
        Pair<List<Edge>, List<Edge>> pairPath = pathResult.getFoundPath();

        //then: system picks 198 path (since 200 path is no longer '200' due to diversity weight rise)
        List<SwitchId> forwardSwitches = getInvolvedSwitches(pairPath.getLeft());
        assertThat(forwardSwitches, equalTo(Arrays.asList(SWITCH_ID_1, SWITCH_ID_2, SWITCH_ID_5)));
        assertThat(getInvolvedSwitches(pairPath.getRight()), equalTo(Lists.reverse(forwardSwitches)));

        return pathResult;
    }

    @Test
    public void shouldFindSymmetricPath() throws UnroutableFlowException {
        AvailableNetwork network = buildLinearNetworkWithPairLinks();
        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(2);

        Pair<List<Edge>, List<Edge>> pathPair =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_3, WEIGHT_FUNCTION).getFoundPath();
        List<Edge> forward = pathPair.getLeft();
        List<Edge> reverse = Lists.reverse(pathPair.getRight());

        List<Boolean> validation = IntStream.range(0, forward.size())
                .mapToObj(i -> Objects.equals(forward.get(i).getSrcPort(), reverse.get(i).getDestPort()))
                .collect(Collectors.toList());
        assertFalse(validation.contains(false));
    }

    private AvailableNetwork buildLinearNetworkWithPairLinks() {
        /*
         * Topology:
         *
         * SW1===SW2===SW3
         *
         * All ISLs have equal cost.
         */
        AvailableNetwork network = new AvailableNetwork();
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_2, 1, 2, 10000);
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_2, 3, 4, 10000);
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_3, 5, 6, 10000);
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_3, 7, 8, 10000);
        return network;
    }

    private AvailableNetwork buildEqualCostsNetwork() {
        /*
         *   Topology:
         *
         *   SW1---SW2---SW4
         *          |     |
         *         SW3---SW5
         *
         *   All ISLs have equal cost.
         */
        AvailableNetwork network = new AvailableNetwork();
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_2, 1, 2, 100);
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_4, 3, 4, 100);
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_3, 5, 6, 100);
        addBidirectionalLink(network, SWITCH_ID_3, SWITCH_ID_5, 7, 8, 100);
        addBidirectionalLink(network, SWITCH_ID_4, SWITCH_ID_5, 9, 10, 100);

        network.reduceByWeight(WEIGHT_FUNCTION);
        return network;
    }

    @Test
    public void testForwardAndBackwardPathsEqualityEvenWhenReverseHasCheaperPath()
            throws UnroutableFlowException {
        // since our ISLs are bidirectional and cost may vary, we need to be sure that cost on reverse ISL won't be
        // taken into account during searching of reverse path.
        AvailableNetwork network = buildNetworkWithCostInReversePathBiggerThanForward();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        Pair<List<Edge>, List<Edge>> paths =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_5, WEIGHT_FUNCTION).getFoundPath();

        List<SwitchId> forwardSwitchPath = getSwitchIdsFlowPath(paths.getLeft());
        List<SwitchId> backwardSwitchPath = Lists.reverse(getSwitchIdsFlowPath(paths.getRight()));
        assertEquals(forwardSwitchPath, backwardSwitchPath);
    }

    @Test
    public void shouldCreatePathThrowMoreExpensiveWayMaxLatencyStrategy() throws UnroutableFlowException {
        FindPathResult pathResult = findPathThrowMoreExpensiveWayMaxLatencyStrategy(10201L, Long.MAX_VALUE);
        assertFalse(pathResult.isBackUpPathComputationWayUsed());
    }

    @Test
    public void shouldCreatePathThrowMoreExpensiveBackUpWayMaxLatencyStrategy() throws UnroutableFlowException {
        FindPathResult pathResult = findPathThrowMoreExpensiveWayMaxLatencyStrategy(100L, 10201L);
        assertTrue(pathResult.isBackUpPathComputationWayUsed());
    }

    private FindPathResult findPathThrowMoreExpensiveWayMaxLatencyStrategy(long maxWeight, long backUpMaxWeight)
            throws UnroutableFlowException {
        // Reverse way is more expansive then forward, so we must choose this path
        // and the sequence of switches must match the forward path.
        AvailableNetwork network = buildNetworkWithCostInReversePathBiggerThanForward();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        FindPathResult pathResult = pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_5, WEIGHT_FUNCTION,
                maxWeight, backUpMaxWeight);
        Pair<List<Edge>, List<Edge>> paths = pathResult.getFoundPath();

        List<Edge> fpath = paths.getLeft();
        assertThat(fpath, Matchers.hasSize(3));
        assertEquals(SWITCH_ID_1, fpath.get(0).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_3, fpath.get(2).getSrcSwitch().getSwitchId());

        List<Edge> rpath = paths.getRight();
        assertThat(rpath, Matchers.hasSize(3));
        assertEquals(SWITCH_ID_5, fpath.get(2).getDestSwitch().getSwitchId());
        assertEquals(SWITCH_ID_3, rpath.get(0).getDestSwitch().getSwitchId());

        return pathResult;
    }

    private AvailableNetwork buildNetworkWithCostInReversePathBiggerThanForward() {
        /*
         *   Topology:
         *
         *   SW1---SW2---SW4
         *          |     |
         *         SW3---SW5
         *
         *   SW3---SW5 isl has lower cost then reverse one.
         */
        AvailableNetwork network = new AvailableNetwork();
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_2, 1, 2, 100);
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_4, 3, 4, 100);
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_3, 5, 68, 100);
        addLink(network, SWITCH_ID_3, SWITCH_ID_5, 7, 8, 10, 100, false, false);
        addLink(network, SWITCH_ID_5, SWITCH_ID_3, 8, 7, 10000, 100, false, false);
        addLink(network, SWITCH_ID_4, SWITCH_ID_5, 9, 10, 100, 100, false, false);
        addLink(network, SWITCH_ID_5, SWITCH_ID_4, 10, 9, 100, 100, false, false);

        network.reduceByWeight(WEIGHT_FUNCTION);
        return network;
    }


    @Test
    public void shouldHandleVeryExpensiveLinks() throws UnroutableFlowException {
        AvailableNetwork network = buildExpensiveNetwork();

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        Pair<List<Edge>, List<Edge>> paths =
                pathFinder.findPathInNetwork(network, SWITCH_ID_1, SWITCH_ID_3, WEIGHT_FUNCTION).getFoundPath();

        List<SwitchId> forwardSwitchPath = getSwitchIdsFlowPath(paths.getLeft());
        List<SwitchId> reverseSwitchPath = Lists.reverse(getSwitchIdsFlowPath(paths.getRight()));
        assertEquals(forwardSwitchPath, reverseSwitchPath);
        assertEquals(forwardSwitchPath, Lists.newArrayList(SWITCH_ID_1, SWITCH_ID_3));
    }

    private AvailableNetwork buildExpensiveNetwork() {
        /*
         *   Triangle topology:
         *
         *   SW1---2 000 000 000---SW2---2 000 000 000---SW3
         *   |                                           |
         *   +---------------------1---------------------+
         */

        AvailableNetwork network = new AvailableNetwork();
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_2, 1, 2, 2000000000); //cost near to MAX_INTEGER
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_3, 3, 4, 2000000000); //cost near to MAX_INTEGER
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_3, 5, 6, 1);

        network.reduceByWeight(WEIGHT_FUNCTION);
        return network;
    }

    private List<SwitchId> getSwitchIdsFlowPath(List<Edge> path) {
        List<SwitchId> switchIds = new ArrayList<>();
        if (!path.isEmpty()) {
            switchIds.add(path.get(0).getSrcSwitch().getSwitchId());
            for (Edge edge : path) {
                switchIds.add(edge.getDestSwitch().getSwitchId());
            }
        }
        return switchIds;
    }

    private void addBidirectionalLink(AvailableNetwork network, SwitchId firstSwitch, SwitchId secondSwitch,
                                      int srcPort, int dstPort, int cost) {
        addLink(network, firstSwitch, secondSwitch, srcPort, dstPort, cost, 1, false, false);
        addLink(network, secondSwitch, firstSwitch, dstPort, srcPort, cost, 1, false, false);
    }

    private AvailableNetwork buildThreePathsNetwork() {
        /*
            2
          /   \
         1--3--5
          \   /
            4
         */
        //Path 1>2>5 = 198cost
        //Path 1>3>5 = 200cost
        //Path 1>4>5 = 201cost
        AvailableNetwork network = new AvailableNetwork();
        //1>2>5
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_2, 1, 1, 100);
        addBidirectionalLink(network, SWITCH_ID_2, SWITCH_ID_5, 2, 1, 98);
        //1>3>5
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_3, 2, 1, 100);
        addBidirectionalLink(network, SWITCH_ID_3, SWITCH_ID_5, 2, 2, 100);
        //1>4>5
        addBidirectionalLink(network, SWITCH_ID_1, SWITCH_ID_4, 3, 1, 100);
        addBidirectionalLink(network, SWITCH_ID_4, SWITCH_ID_5, 2, 3, 101);
        return network;
    }

    private AvailableNetwork buildTestNetwork() {
        /*
         *   Topology:
         *
         *   D---C---F---B---E
         *   |   |   |   |   |
         *   |   +---A---+   |
         *   |      / \      |
         *   +-----+   +-----+
         */
        AvailableNetwork network = new AvailableNetwork();
        addLink(network, SWITCH_ID_A, SWITCH_ID_F,
                7, 60, 0, 3, false, false);
        addLink(network, SWITCH_ID_A, SWITCH_ID_B,
                5, 32, 10, 18, false, false);
        addLink(network, SWITCH_ID_A, SWITCH_ID_D,
                2, 2, 10, 2, false, false);
        addLink(network, SWITCH_ID_A, SWITCH_ID_E,
                6, 16, 10, 15, false, false);
        addLink(network, SWITCH_ID_A, SWITCH_ID_C,
                1, 3, 40, 4, false, false);
        addLink(network, SWITCH_ID_D, SWITCH_ID_C,
                1, 1, 100, 7, false, false);
        addLink(network, SWITCH_ID_D, SWITCH_ID_A,
                2, 2, 10, 1, false, false);
        addLink(network, SWITCH_ID_C, SWITCH_ID_F,
                6, 19, 10, 3, false, false);
        addLink(network, SWITCH_ID_C, SWITCH_ID_D,
                1, 1, 100, 2, false, false);
        addLink(network, SWITCH_ID_C, SWITCH_ID_A,
                3, 1, 100, 2, false, false);
        addLink(network, SWITCH_ID_E, SWITCH_ID_B,
                52, 52, 10, 381, false, false);
        addLink(network, SWITCH_ID_E, SWITCH_ID_A,
                16, 6, 10, 18, false, false);
        addLink(network, SWITCH_ID_B, SWITCH_ID_F,
                48, 49, 10, 97, false, false);
        addLink(network, SWITCH_ID_B, SWITCH_ID_E,
                52, 52, 10, 1021, false, false);
        addLink(network, SWITCH_ID_B, SWITCH_ID_A,
                32, 5, 10, 16, false, false);
        addLink(network, SWITCH_ID_F, SWITCH_ID_B,
                49, 48, 10, 0, false, false);
        addLink(network, SWITCH_ID_F, SWITCH_ID_C,
                19, 6, 10, 3, false, false);
        addLink(network, SWITCH_ID_F, SWITCH_ID_A,
                50, 7, 0, 3, false, false);

        network.reduceByWeight(WEIGHT_FUNCTION);
        return network;
    }

    @Test
    public void shouldBuildPathDependsOnIslConfig() throws UnroutableFlowException {
        // Network without unstable and under maintenance links.
        AvailableNetwork network = buildTestNetworkForVerifyIslConfig(false, false);

        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        Pair<List<Edge>, List<Edge>> pairPath =
                pathFinder.findPathInNetwork(network, SWITCH_ID_A, SWITCH_ID_B, WEIGHT_FUNCTION).getFoundPath();
        List<Edge> forwardPath = pairPath.getLeft();
        assertThat(forwardPath, Matchers.hasSize(1));
        assertEquals(SWITCH_ID_A, forwardPath.get(0).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_B, forwardPath.get(0).getDestSwitch().getSwitchId());

        List<Edge> reversePath = pairPath.getRight();
        assertThat(reversePath, Matchers.hasSize(1));
        assertEquals(SWITCH_ID_B, reversePath.get(0).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_A, reversePath.get(0).getDestSwitch().getSwitchId());

        // Network where shortest path has under maintenance link.
        network = buildTestNetworkForVerifyIslConfig(true, false);

        pairPath = pathFinder.findPathInNetwork(network, SWITCH_ID_A, SWITCH_ID_B, WEIGHT_FUNCTION).getFoundPath();
        forwardPath = pairPath.getLeft();
        assertThat(forwardPath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_A, forwardPath.get(0).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_C, forwardPath.get(0).getDestSwitch().getSwitchId());
        assertEquals(SWITCH_ID_C, forwardPath.get(1).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_B, forwardPath.get(1).getDestSwitch().getSwitchId());

        reversePath = pairPath.getRight();
        assertThat(reversePath, Matchers.hasSize(2));
        assertEquals(SWITCH_ID_B, reversePath.get(0).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_C, reversePath.get(0).getDestSwitch().getSwitchId());
        assertEquals(SWITCH_ID_C, reversePath.get(1).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_A, reversePath.get(1).getDestSwitch().getSwitchId());

        // Network where shortest path has under maintenance link and another short path has unstable link.
        network = buildTestNetworkForVerifyIslConfig(true, true);

        pairPath = pathFinder.findPathInNetwork(network, SWITCH_ID_A, SWITCH_ID_B, WEIGHT_FUNCTION).getFoundPath();
        forwardPath = pairPath.getLeft();
        assertThat(forwardPath, Matchers.hasSize(3));
        assertEquals(SWITCH_ID_A, forwardPath.get(0).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_D, forwardPath.get(0).getDestSwitch().getSwitchId());
        assertEquals(SWITCH_ID_D, forwardPath.get(1).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_E, forwardPath.get(1).getDestSwitch().getSwitchId());
        assertEquals(SWITCH_ID_E, forwardPath.get(2).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_B, forwardPath.get(2).getDestSwitch().getSwitchId());

        reversePath = pairPath.getRight();
        assertThat(reversePath, Matchers.hasSize(3));
        assertEquals(SWITCH_ID_B, reversePath.get(0).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_E, reversePath.get(0).getDestSwitch().getSwitchId());
        assertEquals(SWITCH_ID_E, reversePath.get(1).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_D, reversePath.get(1).getDestSwitch().getSwitchId());
        assertEquals(SWITCH_ID_D, reversePath.get(2).getSrcSwitch().getSwitchId());
        assertEquals(SWITCH_ID_A, reversePath.get(2).getDestSwitch().getSwitchId());
    }

    private AvailableNetwork buildTestNetworkForVerifyIslConfig(boolean shortestPathHasUnderMaintenanceLink,
                                                                boolean shortPathHasUnstableLink) {
        /*
         *   Topology:
         *
         *   A-------B
         *   |\     /|
         *   | +-C-+ |
         *   D-------E
         */
        AvailableNetwork network = new AvailableNetwork();
        addLink(network, SWITCH_ID_A, SWITCH_ID_B,
                1, 1, 0, 0, false, shortestPathHasUnderMaintenanceLink);

        addLink(network, SWITCH_ID_A, SWITCH_ID_C,
                2, 1, 0, 0, false, false);
        addLink(network, SWITCH_ID_C, SWITCH_ID_B,
                2, 2, 0, 0, shortPathHasUnstableLink, false);

        addLink(network, SWITCH_ID_A, SWITCH_ID_D,
                3, 1, 0, 0, false, false);
        addLink(network, SWITCH_ID_D, SWITCH_ID_E,
                1, 1, 0, 0, false, false);
        addLink(network, SWITCH_ID_E, SWITCH_ID_B,
                2, 3, 0, 0, false, false);

        network.reduceByWeight(WEIGHT_FUNCTION);
        return network;
    }

    @Test
    public void shouldFindNPath() throws UnroutableFlowException {
        AvailableNetwork network = buildTestNetworkForTestYensAlgorithm();
        BestWeightAndShortestPathFinder pathFinder = new BestWeightAndShortestPathFinder(ALLOWED_DEPTH);
        List<List<SwitchId>> expectedPaths = new ArrayList<>();

        expectedPaths.add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_D, SWITCH_ID_C, SWITCH_ID_F));
        List<List<Edge>> paths =
                pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 1, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths.add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_D, SWITCH_ID_E, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 2, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths.add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_B, SWITCH_ID_D, SWITCH_ID_C, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 3, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths.add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_D, SWITCH_ID_E, SWITCH_ID_C, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 4, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths.add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_D, SWITCH_ID_B, SWITCH_ID_C, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 5, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths.add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_D, SWITCH_ID_C, SWITCH_ID_E, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 6, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths.add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_B, SWITCH_ID_C, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 7, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths.add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_B, SWITCH_ID_D, SWITCH_ID_E, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 8, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths
                .add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_B, SWITCH_ID_D, SWITCH_ID_C, SWITCH_ID_E, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 9, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths
                .add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_B, SWITCH_ID_D, SWITCH_ID_E, SWITCH_ID_C, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 10, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths
                .add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_D, SWITCH_ID_B, SWITCH_ID_C, SWITCH_ID_E, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 11, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths.add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_B, SWITCH_ID_C, SWITCH_ID_E, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 12, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        expectedPaths
                .add(Lists.newArrayList(SWITCH_ID_A, SWITCH_ID_B, SWITCH_ID_C, SWITCH_ID_D, SWITCH_ID_E, SWITCH_ID_F));
        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 13, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));

        paths = pathFinder.findNPathsBetweenSwitches(network, SWITCH_ID_A, SWITCH_ID_F, 500, WEIGHT_FUNCTION);
        assertEquals(expectedPaths, convertPaths(paths));
    }

    private AvailableNetwork buildTestNetworkForTestYensAlgorithm() {
        /*
         *   Topology:
         *
         *   A--B--C
         *    \ | /|\
         *     \|/ | \
         *      D--E--F
         */
        AvailableNetwork network = new AvailableNetwork();
        addBidirectionalLink(network, SWITCH_ID_A, SWITCH_ID_B, 1, 1, 3);
        addBidirectionalLink(network, SWITCH_ID_A, SWITCH_ID_D, 2, 1, 2);
        addBidirectionalLink(network, SWITCH_ID_B, SWITCH_ID_C, 2, 1, 4);
        addBidirectionalLink(network, SWITCH_ID_B, SWITCH_ID_D, 3, 2, 1);
        addBidirectionalLink(network, SWITCH_ID_C, SWITCH_ID_D, 2, 3, 2);
        addBidirectionalLink(network, SWITCH_ID_C, SWITCH_ID_E, 3, 1, 2);
        addBidirectionalLink(network, SWITCH_ID_C, SWITCH_ID_F, 4, 1, 1);
        addBidirectionalLink(network, SWITCH_ID_D, SWITCH_ID_E, 4, 2, 3);
        addBidirectionalLink(network, SWITCH_ID_E, SWITCH_ID_F, 3, 2, 2);

        network.reduceByWeight(WEIGHT_FUNCTION);
        return network;
    }

    private List<List<SwitchId>> convertPaths(List<List<Edge>> paths) {
        List<List<SwitchId>> convertedPaths = new ArrayList<>();
        for (List<Edge> path : paths) {
            List<SwitchId> convertedPath = getInvolvedSwitches(path);
            convertedPaths.add(convertedPath);
        }
        return convertedPaths;
    }

    private void addLink(AvailableNetwork network, SwitchId srcDpid, SwitchId dstDpid, int srcPort, int dstPort,
                         int cost, int latency, boolean isUnstable, boolean isUnderMaintenance) {
        Edge edge = Edge.builder()
                .srcSwitch(network.getOrAddNode(srcDpid, null))
                .srcPort(srcPort)
                .destSwitch(network.getOrAddNode(dstDpid, null))
                .destPort(dstPort)
                .latency(latency)
                .cost(cost)
                .availableBandwidth(500000)
                .underMaintenance(isUnderMaintenance)
                .unstable(isUnstable)
                .build();
        network.addEdge(edge);
    }

    private List<SwitchId> getInvolvedSwitches(List<Edge> path) {
        List<SwitchId> switches = new ArrayList<>();
        for (Edge edge : path) {
            switches.add(edge.getSrcSwitch().getSwitchId());
        }
        switches.add(path.get(path.size() - 1).getDestSwitch().getSwitchId());
        return switches;
    }

}
