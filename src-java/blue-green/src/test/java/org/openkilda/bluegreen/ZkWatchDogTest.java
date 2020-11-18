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

package org.openkilda.bluegreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashSet;

public class ZkWatchDogTest {
    @Test
    public void testValidateNodes() throws KeeperException, InterruptedException {
        ZkWatchDog watchDog = Mockito.mock(ZkWatchDog.class);
        doCallRealMethod().when(watchDog).validateNodes();
        watchDog.validateNodes();
        verify(watchDog, Mockito.times(3)).ensureZNode(any());
    }

    @Test
    public void testCheckSignal() throws KeeperException, InterruptedException {
        ZkWatchDog watchDog = Mockito.mock(ZkWatchDog.class);
        watchDog.signalPath = "/test/path";
        doCallRealMethod().when(watchDog).checkSignal();
        ZooKeeper zkMock = Mockito.mock(ZooKeeper.class);
        watchDog.zookeeper = zkMock;
        watchDog.checkSignal();
        verify(zkMock, Mockito.times(1))
                .getData(eq(watchDog.signalPath), eq(watchDog), eq(watchDog), eq(null));
    }

    @Test
    public void testInitWatch() throws KeeperException, InterruptedException {
        ZkWatchDog watchDog = Mockito.mock(ZkWatchDog.class);
        doCallRealMethod().when(watchDog).initWatch();
        watchDog.initWatch();
        verify(watchDog, Mockito.times(1)).validateNodes();
        verify(watchDog, Mockito.times(1)).checkSignal();
    }


    @Test
    public void testSubscribeUnsubscribe() {
        ZkWatchDog watchDog = Mockito.mock(ZkWatchDog.class);
        watchDog.observers = new HashSet<>();
        LifeCycleObserver observer = Mockito.mock(LifeCycleObserver.class);
        doCallRealMethod().when(watchDog).subscribe(any());
        doCallRealMethod().when(watchDog).unsubscribe(any());
        watchDog.subscribe(observer);
        assertEquals(1, watchDog.observers.size());
        assertTrue(watchDog.observers.contains(observer));
    }

    @Test
    public void testProcessValidNode() throws KeeperException, InterruptedException {
        ZkWatchDog watchDog = Mockito.mock(ZkWatchDog.class);
        watchDog.signalPath = "/test/path";
        doCallRealMethod().when(watchDog).process(any());
        WatchedEvent event = new WatchedEvent(EventType.NodeDataChanged, KeeperState.SyncConnected,
                watchDog.signalPath);
        watchDog.process(event);
        verify(watchDog, Mockito.times(1)).checkSignal();
    }

    @Test
    public void testProcessWrongNode() throws KeeperException, InterruptedException {
        ZkWatchDog watchDog = Mockito.mock(ZkWatchDog.class);
        watchDog.signalPath = "/test/path";
        doCallRealMethod().when(watchDog).process(any());
        WatchedEvent event = new WatchedEvent(EventType.NodeDataChanged, KeeperState.SyncConnected,
                watchDog.signalPath + "data");
        watchDog.process(event);
        verify(watchDog, Mockito.times(0)).checkSignal();
    }

    @Test
    public void testProcessResult() {
        ZkWatchDog watchDog = Mockito.mock(ZkWatchDog.class);
        watchDog.signalPath = "/test/path";
        doCallRealMethod().when(watchDog).processResult(anyInt(), any(),
                any(), any(), any());
        watchDog.processResult(0, "/test/path", null, "START".getBytes(), null);
        verify(watchDog, Mockito.times(1)).notifyObservers();
    }

}
