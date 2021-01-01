/* Copyright 2019 Telstra Open Source
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

package org.openkilda.wfm.topology.stats.bolts;

import static java.util.Arrays.asList;
import static org.mockito.Mockito.when;
import static org.openkilda.wfm.topology.stats.MeasurePoint.EGRESS;
import static org.openkilda.wfm.topology.stats.MeasurePoint.INGRESS;
import static org.openkilda.wfm.topology.stats.MeasurePoint.TRANSIT;

import org.openkilda.messaging.info.stats.FlowStatsData;
import org.openkilda.messaging.info.stats.FlowStatsEntry;
import org.openkilda.messaging.info.stats.MeterStatsData;
import org.openkilda.messaging.info.stats.MeterStatsEntry;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.cookie.FlowSegmentCookie;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.wfm.topology.stats.CacheFlowEntry;
import org.openkilda.wfm.topology.stats.MeasurePoint;
import org.openkilda.wfm.topology.stats.MeterCacheKey;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RunWith(MockitoJUnitRunner.class)
public class CacheBoltTest {

    private static final Long FORWARD_PATH_COOKIE = 1L;
    private static final Long PROTECTED_FORWARD_PATH_COOKIE = 2L;
    private static final Long REVERSE_PATH_COOKIE = 3L;
    private static final Long PROTECTED_REVERSE_PATH_COOKIE = 4L;

    private static final Long FORWARD_METER_ID = MeterId.MIN_FLOW_METER_ID + 1L;
    private static final Long PROTECTED_FORWARD_METER_ID = MeterId.MIN_FLOW_METER_ID + 2L;
    private static final Long REVERSE_METER_ID = MeterId.MIN_FLOW_METER_ID + 3L;
    private static final Long PROTECTED_REVERSE_METER_ID = MeterId.MIN_FLOW_METER_ID + 4L;

    private static final SwitchId SRC_SWITCH_ID = new SwitchId(1L);
    private static final SwitchId DST_SWITCH_ID = new SwitchId(2L);
    private static final SwitchId TRANSIT_SWITCH_ID = new SwitchId(3L);

    @Mock
    private PersistenceManager persistenceManager;
    @Mock
    private RepositoryFactory repositoryFactory;
    @Mock
    private FlowRepository flowRepository;

    @Test
    public void cacheBoltInitCookieTest() {
        Flow flow = getFlow();
        when(flowRepository.findAll()).thenReturn(Collections.singletonList(flow));
        when(repositoryFactory.createFlowRepository()).thenReturn(flowRepository);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);

        CacheBolt cacheBolt = new CacheBolt(persistenceManager);
        cacheBolt.init();

        Map<Long, CacheFlowEntry> srcCache = cacheBolt.createCookieToFlowCache(getFlowStatsDataSrcSwitch());

        Assert.assertEquals(3, srcCache.size());
        assertCookieCache(flow, srcCache, FORWARD_PATH_COOKIE, INGRESS);
        assertCookieCache(flow, srcCache, REVERSE_PATH_COOKIE, EGRESS);
        assertCookieCache(flow, srcCache, PROTECTED_REVERSE_PATH_COOKIE, EGRESS);

        Map<Long, CacheFlowEntry> dstCache = cacheBolt.createCookieToFlowCache(getFlowStatsDataDstSwitch());

        Assert.assertEquals(3, dstCache.size());
        assertCookieCache(flow, dstCache, FORWARD_PATH_COOKIE, EGRESS);
        assertCookieCache(flow, dstCache, REVERSE_PATH_COOKIE, INGRESS);
        assertCookieCache(flow, dstCache, PROTECTED_FORWARD_PATH_COOKIE, EGRESS);

        Map<Long, CacheFlowEntry> transitCache = cacheBolt.createCookieToFlowCache(getFlowStatsDataTransitSwitch());

        Assert.assertEquals(4, transitCache.size());
        assertCookieCache(flow, transitCache, REVERSE_PATH_COOKIE, TRANSIT);
        assertCookieCache(flow, transitCache, PROTECTED_REVERSE_PATH_COOKIE, TRANSIT);
        assertCookieCache(flow, transitCache, PROTECTED_REVERSE_PATH_COOKIE, TRANSIT);
        assertCookieCache(flow, transitCache, PROTECTED_REVERSE_PATH_COOKIE, TRANSIT);
    }

    @Test
    public void cacheBoltInitMeterTest() {
        Flow flow = getFlow();
        when(flowRepository.findAll()).thenReturn(Collections.singletonList(flow));
        when(repositoryFactory.createFlowRepository()).thenReturn(flowRepository);
        when(persistenceManager.getRepositoryFactory()).thenReturn(repositoryFactory);

        CacheBolt cacheBolt = new CacheBolt(persistenceManager);
        cacheBolt.init();

        Map<MeterCacheKey, CacheFlowEntry> srcCache = cacheBolt.createSwitchAndMeterToFlowCache(
                getMeterStatsDataSrcSwitch());

        Assert.assertEquals(2, srcCache.size());
        assertMeterCache(flow, SRC_SWITCH_ID, srcCache, FORWARD_METER_ID, FORWARD_PATH_COOKIE);
        assertMeterCache(flow, SRC_SWITCH_ID, srcCache, PROTECTED_FORWARD_METER_ID, PROTECTED_FORWARD_PATH_COOKIE);

        Map<MeterCacheKey, CacheFlowEntry> dstCache = cacheBolt.createSwitchAndMeterToFlowCache(
                getMeterStatsDataDstSwitch());
        Assert.assertEquals(2, dstCache.size());

        assertMeterCache(flow, DST_SWITCH_ID, dstCache, REVERSE_METER_ID, REVERSE_PATH_COOKIE);
        assertMeterCache(flow, DST_SWITCH_ID, dstCache, PROTECTED_REVERSE_METER_ID, PROTECTED_REVERSE_PATH_COOKIE);

        Map<MeterCacheKey, CacheFlowEntry> transitCache = cacheBolt.createSwitchAndMeterToFlowCache(
                getMeterStatsDataDstSwitch());
    }

    private void assertCookieCache(Flow flow, Map<Long, CacheFlowEntry> cookieToFlowCache, Long cookie,
                                   MeasurePoint measurePoint) {
        CacheFlowEntry entry = cookieToFlowCache.get(cookie);
        Assert.assertEquals(flow.getFlowId(), entry.getFlowId());
        Assert.assertEquals(cookie, entry.getCookie());
        Assert.assertEquals(measurePoint, entry.getMeasurePoint());
    }

    private void assertMeterCache(Flow flow, SwitchId switchId, Map<MeterCacheKey, CacheFlowEntry> cache,
                                  Long meterId, Long cookie) {
        CacheFlowEntry entry = cache.get(new MeterCacheKey(switchId, meterId));
        Assert.assertEquals(flow.getFlowId(), entry.getFlowId());
        Assert.assertEquals(cookie, entry.getCookie());
    }

    private Flow getFlow() {
        Switch srcSwitch = Switch.builder().switchId(SRC_SWITCH_ID).build();
        Switch destSwitch = Switch.builder().switchId(DST_SWITCH_ID).build();
        Flow flow = Flow.builder()
                .flowId(uuid())
                .srcSwitch(srcSwitch)
                .destSwitch(destSwitch)
                .build();
        flow.setForwardPath(getPath(flow, srcSwitch, destSwitch, FORWARD_PATH_COOKIE, FORWARD_METER_ID));
        flow.setProtectedForwardPath(getPath(
                flow, srcSwitch, destSwitch, PROTECTED_FORWARD_PATH_COOKIE, PROTECTED_FORWARD_METER_ID));
        flow.setReversePath(getPath(flow, destSwitch, srcSwitch, REVERSE_PATH_COOKIE, REVERSE_METER_ID));
        flow.setProtectedReversePath(getPath(
                flow, destSwitch, srcSwitch, PROTECTED_REVERSE_PATH_COOKIE, PROTECTED_REVERSE_METER_ID));
        return flow;
    }

    private FlowPath getPath(Flow flow, Switch src, Switch dest, long cookie, long meterId) {
        Switch transitSwitch = Switch.builder().switchId(TRANSIT_SWITCH_ID).build();
        PathId pathId = new PathId(uuid());

        List<PathSegment> segments = new ArrayList<>();
        segments.add(PathSegment.builder()
                .pathId(pathId)
                .srcSwitch(src)
                .destSwitch(transitSwitch)
                .build());
        segments.add(PathSegment.builder()
                .pathId(pathId)
                .srcSwitch(transitSwitch)
                .destSwitch(dest)
                .build());

        return FlowPath.builder()
                .pathId(pathId)
                .srcSwitch(src)
                .destSwitch(dest)
                .cookie(new FlowSegmentCookie(cookie))
                .meterId(new MeterId(meterId))
                .segments(segments)
                .build();
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }

    private FlowStatsData getFlowStatsDataSrcSwitch() {
        return new FlowStatsData(SRC_SWITCH_ID, asList(
                new FlowStatsEntry(0, FORWARD_PATH_COOKIE, 0, 0, 0, 0),
                new FlowStatsEntry(0, REVERSE_PATH_COOKIE, 0, 0, 0, 0),
                new FlowStatsEntry(0, PROTECTED_REVERSE_PATH_COOKIE, 0, 0, 0, 0)));
    }

    private FlowStatsData getFlowStatsDataDstSwitch() {
        return new FlowStatsData(DST_SWITCH_ID, asList(
                new FlowStatsEntry(0, FORWARD_PATH_COOKIE, 0, 0, 0, 0),
                new FlowStatsEntry(0, REVERSE_PATH_COOKIE, 0, 0, 0, 0),
                new FlowStatsEntry(0, PROTECTED_FORWARD_PATH_COOKIE, 0, 0, 0, 0)));
    }

    private FlowStatsData getFlowStatsDataTransitSwitch() {
        return new FlowStatsData(TRANSIT_SWITCH_ID, asList(
                new FlowStatsEntry(0, FORWARD_PATH_COOKIE, 0, 0, 0, 0),
                new FlowStatsEntry(0, REVERSE_PATH_COOKIE, 0, 0, 0, 0),
                new FlowStatsEntry(0, PROTECTED_FORWARD_PATH_COOKIE, 0, 0, 0, 0),
                new FlowStatsEntry(0, PROTECTED_REVERSE_PATH_COOKIE, 0, 0, 0, 0)));
    }

    private MeterStatsData getMeterStatsDataSrcSwitch() {
        return new MeterStatsData(SRC_SWITCH_ID, asList(
                new MeterStatsEntry(FORWARD_METER_ID, 0, 0),
                new MeterStatsEntry(PROTECTED_FORWARD_METER_ID, 0, 0)));
    }

    private MeterStatsData getMeterStatsDataDstSwitch() {
        return new MeterStatsData(DST_SWITCH_ID, asList(
                new MeterStatsEntry(REVERSE_METER_ID, 0, 0),
                new MeterStatsEntry(PROTECTED_REVERSE_METER_ID, 0, 0)));
    }
}
