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

package org.openkilda.wfm.share.bolt;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.openkilda.wfm.share.utils.ManualClock;

import org.apache.storm.Constants;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.TupleImpl;
import org.apache.storm.tuple.Values;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Objects;

@RunWith(MockitoJUnitRunner.class)
public class MonotonicClockTest {
    @Mock
    private OutputCollector output;

    @Mock
    private TopologyContext topologyContext;

    @Test
    public void ticks() {
        ManualClock baseClock = new ManualClock(Instant.EPOCH, ZoneOffset.UTC);

        when(topologyContext.getThisTaskId()).thenReturn(1);
        when(topologyContext.getComponentId(Mockito.eq((int) Constants.SYSTEM_TASK_ID)))
                .thenReturn(Constants.SYSTEM_COMPONENT_ID);
        when(topologyContext.getComponentOutputFields(Constants.SYSTEM_COMPONENT_ID, Constants.SYSTEM_TICK_STREAM_ID))
                .thenReturn(new Fields());

        MonotonicClock<TickKinds> bolt = new MonotonicClock<>(
                new MonotonicClock.ClockConfig<TickKinds>()
                        .addTickInterval(TickKinds.EACH_2, 2)
                        .addTickInterval(TickKinds.EACH_3, 3),
                1, baseClock);

        bolt.prepare(Collections.emptyMap(), topologyContext, output);

        verifyNoMoreInteractions(output);

        final Tuple tickTuple = makeSystemTickTuple();

        // 0 tick
        bolt.execute(tickTuple);
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(0L, 0L, null), arg.subList(0, 3))));
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(0L, 0L, TickKinds.EACH_2), arg.subList(0, 3))));
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(0L, 0L, TickKinds.EACH_3), arg.subList(0, 3))));
        verify(output).ack(tickTuple);
        verifyNoMoreInteractions(output);
        reset(output);

        // 1 tick
        baseClock.adjust(Duration.ofSeconds(1));
        bolt.execute(tickTuple);
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(1000L, 1L, null), arg.subList(0, 3))));
        verify(output).ack(tickTuple);
        verifyNoMoreInteractions(output);
        reset(output);

        // 2 tick
        baseClock.adjust(Duration.ofSeconds(1));
        bolt.execute(tickTuple);
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(2000L, 2L, null), arg.subList(0, 3))));
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(2000L, 1L, TickKinds.EACH_2), arg.subList(0, 3))));
        verify(output).ack(tickTuple);
        verifyNoMoreInteractions(output);
        reset(output);

        // 3 tick
        baseClock.adjust(Duration.ofSeconds(1));
        bolt.execute(tickTuple);
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(3000L, 3L, null), arg.subList(0, 3))));
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(3000L, 1L, TickKinds.EACH_3), arg.subList(0, 3))));
        verify(output).ack(tickTuple);
        verifyNoMoreInteractions(output);
        reset(output);

        // 4 tick
        baseClock.adjust(Duration.ofSeconds(1));
        bolt.execute(tickTuple);
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(4000L, 4L, null), arg.subList(0, 3))));
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(4000L, 2L, TickKinds.EACH_2), arg.subList(0, 3))));
        verify(output).ack(tickTuple);
        verifyNoMoreInteractions(output);
        reset(output);

        // 5 tick
        baseClock.adjust(Duration.ofSeconds(1));
        bolt.execute(tickTuple);
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(5000L, 5L, null), arg.subList(0, 3))));
        verify(output).ack(tickTuple);
        verifyNoMoreInteractions(output);
        reset(output);

        // 6 tick
        baseClock.adjust(Duration.ofSeconds(1));
        bolt.execute(tickTuple);
        // System.out.println(mockingDetails(output).printInvocations());
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(6000L, 6L, null), arg.subList(0, 3))));
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(6000L, 3L, TickKinds.EACH_2), arg.subList(0, 3))));
        verify(output).emit(Mockito.eq(tickTuple), argThat(
                arg -> Objects.equals(new Values(6000L, 2L, TickKinds.EACH_3), arg.subList(0, 3))));
        verify(output).ack(tickTuple);
        verifyNoMoreInteractions(output);
        reset(output);
    }

    private Tuple makeSystemTickTuple() {
        return new TupleImpl(topologyContext, Collections.emptyList(),
                             (int) Constants.SYSTEM_TASK_ID, Constants.SYSTEM_TICK_STREAM_ID);

    }

    enum TickKinds {
        EACH_2,
        EACH_3
    }
}
