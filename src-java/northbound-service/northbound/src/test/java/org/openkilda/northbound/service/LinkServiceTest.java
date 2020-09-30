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

package org.openkilda.northbound.service;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.openkilda.messaging.info.event.IslChangeType;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.messaging.model.LinkPropsMask;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.messaging.model.NetworkEndpointMask;
import org.openkilda.messaging.nbtopology.request.LinkPropsDrop;
import org.openkilda.messaging.nbtopology.request.LinkPropsPut;
import org.openkilda.messaging.nbtopology.request.LinkPropsRequest;
import org.openkilda.messaging.nbtopology.response.BfdPropertiesResponse;
import org.openkilda.messaging.nbtopology.response.LinkPropsData;
import org.openkilda.messaging.nbtopology.response.LinkPropsResponse;
import org.openkilda.model.EffectiveBfdProperties;
import org.openkilda.model.LinkProps;
import org.openkilda.model.SwitchId;
import org.openkilda.northbound.MessageExchanger;
import org.openkilda.northbound.config.KafkaConfig;
import org.openkilda.northbound.converter.LinkMapper;
import org.openkilda.northbound.dto.BatchResults;
import org.openkilda.northbound.dto.v1.links.LinkDto;
import org.openkilda.northbound.dto.v1.links.LinkMaxBandwidthDto;
import org.openkilda.northbound.dto.v1.links.LinkMaxBandwidthRequest;
import org.openkilda.northbound.dto.v1.links.LinkPropsDto;
import org.openkilda.northbound.dto.v1.links.LinkStatus;
import org.openkilda.northbound.dto.v1.links.LinkUnderMaintenanceDto;
import org.openkilda.northbound.dto.v1.links.PathDto;
import org.openkilda.northbound.dto.v2.links.BfdProperties;
import org.openkilda.northbound.dto.v2.links.BfdPropertiesPayload;
import org.openkilda.northbound.error.InconclusiveException;
import org.openkilda.northbound.messaging.MessagingChannel;
import org.openkilda.northbound.service.impl.LinkServiceImpl;
import org.openkilda.northbound.utils.CorrelationIdFactory;
import org.openkilda.northbound.utils.RequestCorrelationId;
import org.openkilda.northbound.utils.TestCorrelationIdFactory;
import org.openkilda.stubs.ManualClock;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@RunWith(SpringRunner.class)
public class LinkServiceTest {
    private int requestIdIndex = 0;

    private static final ManualClock clock = new ManualClock();

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private CorrelationIdFactory idFactory;

    @Autowired
    private LinkService linkService;

    @Autowired
    private MessageExchanger messageExchanger;

    @Autowired
    private LinkMapper linkMapper;

    @Value("${bfd.apply.period.seconds}")
    private Long bfdPropertiesApplyPeriod;

    @Before
    public void reset() {
        Mockito.reset(taskScheduler);
        messageExchanger.resetMockedResponses();

        String lastRequestId = idFactory.produceChained("dummy");
        lastRequestId = lastRequestId.substring(0, lastRequestId.indexOf(':')).trim();
        requestIdIndex = Integer.valueOf(lastRequestId) + 1;
    }

    @Test
    public void shouldGetLinksList() {
        String correlationId = "links-list";
        SwitchId switchId = new SwitchId(1L);

        IslInfoData islInfoData = new IslInfoData(
                new PathNode(switchId, 1, 0), new PathNode(switchId, 2, 1),
                IslChangeType.DISCOVERED, false);

        messageExchanger.mockChunkedResponse(correlationId, Collections.singletonList(islInfoData));
        RequestCorrelationId.create(correlationId);

        List<LinkDto> result =
                linkService.getLinks(islInfoData.getSource().getSwitchId(), islInfoData.getSource().getPortNo(),
                        islInfoData.getDestination().getSwitchId(), islInfoData.getDestination().getPortNo()).join();
        assertFalse("List of link shouldn't be empty", result.isEmpty());

        LinkDto link = result.get(0);
        assertThat(link.getSpeed(), is(0L));
        assertThat(link.getMaxBandwidth(), is(0L));
        assertThat(link.getAvailableBandwidth(), is(0L));
        assertThat(link.getCost(), is(0));
        assertThat(link.isUnderMaintenance(), is(false));
        assertThat(link.getState(), is(LinkStatus.DISCOVERED));

        assertFalse(link.getPath().isEmpty());
        PathDto path = link.getPath().get(0);
        assertThat(path.getSwitchId(), is(switchId.toString()));
        assertThat(path.getPortNo(), is(1));

        path = link.getPath().get(1);
        assertThat(path.getSwitchId(), is(switchId.toString()));
        assertThat(path.getPortNo(), is(2));
    }

    @Test
    public void shouldGetEmptyPropsList() {
        final String correlationId = "empty-link-props";
        messageExchanger.mockChunkedResponse(correlationId, Collections.emptyList());
        RequestCorrelationId.create(correlationId);

        List<LinkPropsDto> result = linkService.getLinkProps(null, 0, null, 0).join();
        assertTrue("List of link props should be empty", result.isEmpty());
    }

    @Test
    public void shouldGetPropsList() {
        final String correlationId = "non-empty-link-props";

        org.openkilda.messaging.model.LinkPropsDto linkProps = new org.openkilda.messaging.model.LinkPropsDto(
                new NetworkEndpoint(new SwitchId("00:00:00:00:00:00:00:01"), 1),
                new NetworkEndpoint(new SwitchId("00:00:00:00:00:00:00:02"), 2),
                Collections.singletonMap("cost", "2"));
        LinkPropsData linkPropsData = new LinkPropsData(linkProps);
        messageExchanger.mockChunkedResponse(correlationId, Collections.singletonList(linkPropsData));
        RequestCorrelationId.create(correlationId);

        List<LinkPropsDto> result = linkService.getLinkProps(null, 0, null, 0).join();
        assertFalse("List of link props shouldn't be empty", result.isEmpty());

        LinkPropsDto dto = result.get(0);
        assertThat(dto.getSrcSwitch(), is(linkPropsData.getLinkProps().getSource().getDatapath().toString()));
        assertThat(dto.getSrcPort(), is(linkPropsData.getLinkProps().getSource().getPortNumber()));
        assertThat(dto.getDstSwitch(), is(linkPropsData.getLinkProps().getDest().getDatapath().toString()));
        assertThat(dto.getDstPort(), is(linkPropsData.getLinkProps().getDest().getPortNumber()));
    }

    @Test
    public void putLinkProps() {
        final String correlationId = getClass().getCanonicalName();

        HashMap<String, String> requestProps = new HashMap<>();
        requestProps.put("test", "value");
        org.openkilda.messaging.model.LinkPropsDto linkProps = new org.openkilda.messaging.model.LinkPropsDto(
                new NetworkEndpoint(new SwitchId("ff:fe:00:00:00:00:00:01"), 8),
                new NetworkEndpoint(new SwitchId("ff:fe:00:00:00:00:00:02"), 9),
                requestProps);
        LinkPropsRequest request = new LinkPropsPut(linkProps);

        LinkPropsResponse payload = new LinkPropsResponse(request, linkProps, null);
        String subCorrelationId = idFactory.produceChained(String.valueOf(requestIdIndex++), correlationId);
        messageExchanger.mockResponse(subCorrelationId, payload);

        LinkPropsDto inputItem = new LinkPropsDto(
                linkProps.getSource().getDatapath().toString(), linkProps.getSource().getPortNumber(),
                linkProps.getDest().getDatapath().toString(), linkProps.getDest().getPortNumber(),
                requestProps);

        RequestCorrelationId.create(correlationId);
        BatchResults result = linkService.setLinkProps(Collections.singletonList(inputItem)).join();

        assertThat(result.getFailures(), is(0));
        assertThat(result.getSuccesses(), is(1));
        assertTrue(result.getMessages().isEmpty());
    }

    @Test
    public void updateMaxBandwidth() {

        final String correlationId = "update-max-bw-corrId";
        Long maxBandwidth = 1000L;
        SwitchId srcSwitch = new SwitchId("ff:fe:00:00:00:00:00:01");
        Integer srcPort = 8;
        SwitchId dstSwitch = new SwitchId("ff:fe:00:00:00:00:00:02");
        Integer dstPort = 9;

        LinkMaxBandwidthRequest inputRequest = new LinkMaxBandwidthRequest();
        inputRequest.setMaxBandwidth(maxBandwidth);

        HashMap<String, String> requestProps = new HashMap<>();
        requestProps.put(LinkProps.MAX_BANDWIDTH_PROP_NAME, String.valueOf(maxBandwidth));

        org.openkilda.messaging.model.LinkPropsDto linkProps = new org.openkilda.messaging.model.LinkPropsDto(
                new NetworkEndpoint(srcSwitch, srcPort), new NetworkEndpoint(dstSwitch, dstPort), requestProps);
        LinkPropsRequest request = new LinkPropsPut(linkProps);

        LinkPropsResponse payload = new LinkPropsResponse(request, linkProps, null);

        messageExchanger.mockResponse(correlationId, payload);
        RequestCorrelationId.create(correlationId);

        LinkMaxBandwidthDto result =
                linkService.updateLinkBandwidth(srcSwitch, srcPort, dstSwitch, dstPort, inputRequest).join();

        assertEquals(srcSwitch.toString(), result.getSrcSwitch());
        assertEquals(dstSwitch.toString(), result.getDstSwitch());
        assertEquals(srcPort, result.getSrcPort());
        assertEquals(dstPort, result.getDstPort());
        assertEquals(maxBandwidth, result.getMaxBandwidth());
    }

    @Test
    public void dropLinkProps() {
        final String correlationId = getClass().getCanonicalName();

        LinkPropsDto input = new LinkPropsDto("ff:fe:00:00:00:00:00:01", 8,
                "ff:fe:00:00:00:00:00:05", null, null);

        LinkPropsDrop request = new LinkPropsDrop(new LinkPropsMask(
                new NetworkEndpointMask(new SwitchId(input.getSrcSwitch()), input.getSrcPort()),
                new NetworkEndpointMask(new SwitchId(input.getDstSwitch()), input.getDstPort())));

        org.openkilda.messaging.model.LinkPropsDto linkProps = new org.openkilda.messaging.model.LinkPropsDto(
                new NetworkEndpoint(new SwitchId(input.getSrcSwitch()), input.getSrcPort()),
                new NetworkEndpoint(new SwitchId("ff:fe:00:00:00:00:00:02"), 9),
                new HashMap<>());

        LinkPropsResponse payload = new LinkPropsResponse(request, linkProps, null);

        String requestIdBatch = idFactory.produceChained(String.valueOf(requestIdIndex++), correlationId);
        messageExchanger.mockChunkedResponse(requestIdBatch, Collections.singletonList(payload));

        RequestCorrelationId.create(correlationId);
        BatchResults result = linkService.delLinkProps(Collections.singletonList(input)).join();

        assertThat(result.getFailures(), is(0));
        assertThat(result.getSuccesses(), is(1));
        assertTrue(result.getMessages().isEmpty());
    }

    @Test
    public void testLinkUnderMaintenance() {
        String correlationId = "links-list";
        SwitchId switchId = new SwitchId(1L);
        boolean underMaintenance = true;
        boolean evacuate = false;

        IslInfoData islInfoData = new IslInfoData(
                new PathNode(switchId, 1, 0), new PathNode(switchId, 2, 1),
                IslChangeType.DISCOVERED, true);

        messageExchanger.mockChunkedResponse(correlationId, Collections.singletonList(islInfoData));
        RequestCorrelationId.create(correlationId);

        LinkUnderMaintenanceDto linkUnderMaintenanceDto = new LinkUnderMaintenanceDto(
                islInfoData.getSource().getSwitchId().toString(),
                islInfoData.getSource().getPortNo(),
                islInfoData.getDestination().getSwitchId().toString(),
                islInfoData.getDestination().getPortNo(), underMaintenance, evacuate);
        List<LinkDto> result = linkService.updateLinkUnderMaintenance(linkUnderMaintenanceDto).join();
        assertFalse("List of link shouldn't be empty", result.isEmpty());

        LinkDto link = result.get(0);
        assertThat(link.isUnderMaintenance(), is(true));
        assertThat(link.getState(), is(LinkStatus.DISCOVERED));

        assertFalse(link.getPath().isEmpty());
        PathDto path = link.getPath().get(0);
        assertThat(path.getSwitchId(), is(switchId.toString()));
        assertThat(path.getPortNo(), is(1));
    }

    @Test
    public void writeBfdPropertiesHappyPath() throws ExecutionException, InterruptedException {
        String correlationId = "bfd-properties-write-happy-path";
        NetworkEndpoint source = new NetworkEndpoint(new SwitchId(1), 1);
        NetworkEndpoint destination = new NetworkEndpoint(new SwitchId(2), 2);
        BfdProperties goal = new BfdProperties(350, (short) 3);

        IslInfoData islData = new IslInfoData(
                new PathNode(source.getDatapath(), source.getPortNumber(), 0),
                new PathNode(destination.getDatapath(), destination.getPortNumber(), 1),
                IslChangeType.DISCOVERED, false);

        messageExchanger.mockChunkedResponse(
                correlationId, Collections.singletonList(new BfdPropertiesResponse(
                        source, destination, linkMapper.map(goal),
                        new EffectiveBfdProperties(linkMapper.map(BfdProperties.DISABLED), null),
                        new EffectiveBfdProperties(linkMapper.map(BfdProperties.DISABLED), null), islData)));
        RequestCorrelationId.create(correlationId);

        // make write request and schedule read request
        CompletableFuture<BfdPropertiesPayload> future = linkService.writeBfdProperties(source, destination, goal);

        Assert.assertFalse(future.isDone());

        ArgumentCaptor<Runnable> monitorTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(monitorTaskCaptor.capture(), any(Date.class));

        messageExchanger.mockChunkedResponse(
                makeBfdMonitorCorrelationId(correlationId, 0),
                Collections.singletonList(new BfdPropertiesResponse(
                        source, destination, linkMapper.map(goal),
                        new EffectiveBfdProperties(linkMapper.map(goal), null),
                        new EffectiveBfdProperties(linkMapper.map(goal), null),
                        islData)));

        // make read request
        monitorTaskCaptor.getValue().run();

        Assert.assertTrue(future.isDone());
        BfdPropertiesPayload result = future.get();

        Assert.assertEquals(goal, result.getProperties());
        Assert.assertEquals(goal, result.getEffectiveSource().getProperties());
        Assert.assertEquals(goal, result.getEffectiveDestination().getProperties());
    }

    @Test
    public void writeBfdPropertiesApplyFail() throws InterruptedException {
        String correlationId = "bfd-properties-write-apply-fail";
        NetworkEndpoint source = new NetworkEndpoint(new SwitchId(1), 1);
        NetworkEndpoint destination = new NetworkEndpoint(new SwitchId(2), 2);
        BfdProperties goal = new BfdProperties(350, (short) 3);

        IslInfoData islData = new IslInfoData(
                new PathNode(source.getDatapath(), source.getPortNumber(), 0),
                new PathNode(destination.getDatapath(), destination.getPortNumber(), 1),
                IslChangeType.DISCOVERED, false);

        messageExchanger.mockChunkedResponse(
                correlationId, Collections.singletonList(new BfdPropertiesResponse(
                        source, destination, linkMapper.map(goal),
                        new EffectiveBfdProperties(linkMapper.map(BfdProperties.DISABLED), null),
                        new EffectiveBfdProperties(linkMapper.map(BfdProperties.DISABLED), null), islData)));
        RequestCorrelationId.create(correlationId);

        // make write request and schedule read request
        CompletableFuture<BfdPropertiesPayload> future = linkService.writeBfdProperties(source, destination, goal);

        Assert.assertFalse(future.isDone());

        ArgumentCaptor<Runnable> monitorTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(monitorTaskCaptor.capture(), any(Date.class));
        Mockito.reset(taskScheduler);

        clock.adjust(Duration.ofSeconds(1));
        messageExchanger.mockChunkedResponse(
                makeBfdMonitorCorrelationId(correlationId, 0),
                Collections.singletonList(new BfdPropertiesResponse(
                        source, destination, linkMapper.map(goal),
                        new EffectiveBfdProperties(linkMapper.map(goal), null),
                        new EffectiveBfdProperties(linkMapper.map(BfdProperties.DISABLED), null), islData)));

        // make read request and schedule one more read
        monitorTaskCaptor.getValue().run();
        verify(taskScheduler).schedule(monitorTaskCaptor.capture(), any(Date.class));
        Assert.assertFalse(future.isDone());

        clock.adjust(Duration.ofSeconds(bfdPropertiesApplyPeriod));

        messageExchanger.mockChunkedResponse(
                makeBfdMonitorCorrelationId(correlationId, 1),
                Collections.singletonList(new BfdPropertiesResponse(
                        source, destination, linkMapper.map(goal),
                        new EffectiveBfdProperties(linkMapper.map(goal), null),
                        new EffectiveBfdProperties(linkMapper.map(BfdProperties.DISABLED), null), islData)));

        // make read request and fail due to timeout
        monitorTaskCaptor.getValue().run();

        Assert.assertTrue(future.isDone());
        try {
            future.get();
            Assert.fail("ExecutionException exception expected");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Assert.assertTrue(cause instanceof InconclusiveException);
        }
    }

    private String makeBfdMonitorCorrelationId(String base, int number) {
        String value = RequestCorrelationId.chain(base, "monitor");
        return RequestCorrelationId.chain(value, String.valueOf(number));
    }

    @TestConfiguration
    @Import(KafkaConfig.class)
    @ComponentScan({
            "org.openkilda.northbound.converter",
            "org.openkilda.northbound.utils"})
    @PropertySource({"classpath:northbound.properties"})
    static class Config {
        @Bean
        public Clock clock() {
            return clock;
        }

        @Bean
        public CorrelationIdFactory idFactory() {
            return new TestCorrelationIdFactory();
        }

        @Bean
        public MessagingChannel messagingChannel() {
            return new MessageExchanger();
        }

        @Bean
        public RestTemplate restTemplate() {
            return mock(RestTemplate.class);
        }

        @Bean
        public LinkService linkService() {
            return new LinkServiceImpl();
        }

        @Bean
        public TaskScheduler taskScheduler() {
            return mock(TaskScheduler.class);
        }
    }
}
