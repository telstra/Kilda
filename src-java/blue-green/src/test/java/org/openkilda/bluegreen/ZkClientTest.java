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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;


public class ZkClientTest {

    @Test
    public void testGetPaths() {
        ZkClient client = Mockito.mock(ZkClient.class, Mockito.CALLS_REAL_METHODS);
        String actual = client.getPaths("path", "to");
        assertEquals("/path/to", actual);
    }

    @Test
    public void testRefreshConnectionOnExpired() throws IOException {
        ZkClient client = Mockito.mock(ZkClient.class);
        ZooKeeper zkMock = Mockito.mock(ZooKeeper.class);
        when(client.getZk()).thenReturn(zkMock);
        when(client.refreshConnection(any())).thenCallRealMethod();
        assertTrue(client.refreshConnection(KeeperState.Expired));
        verify(client, Mockito.times(1)).getZk();
        verify(client, Mockito.times(1)).initWatch();
    }

    @Test
    public void testRefreshConnectionOnDisconnected() throws IOException {
        ZkClient client = Mockito.mock(ZkClient.class);
        ZooKeeper zkMock = Mockito.mock(ZooKeeper.class);
        when(client.getZk()).thenReturn(zkMock);
        when(client.refreshConnection(any())).thenCallRealMethod();
        assertTrue(client.refreshConnection(KeeperState.Disconnected));
        verify(client, Mockito.times(1)).getZk();
        verify(client, Mockito.times(1)).initWatch();
    }

    @Test
    public void testNotRefreshConnectionOnOthers() throws IOException {
        ZkClient client = Mockito.mock(ZkClient.class);
        ZooKeeper zkMock = Mockito.mock(ZooKeeper.class);
        when(client.getZk()).thenReturn(zkMock);
        when(client.refreshConnection(any())).thenCallRealMethod();

        Set<KeeperState> states = new HashSet<>(Arrays.asList(KeeperState.values()));
        states.remove(KeeperState.Disconnected);
        states.remove(KeeperState.Expired);
        for (KeeperState state : states) {
            assertFalse(client.refreshConnection(state));
            verify(client, Mockito.times(0)).getZk();
            verify(client, Mockito.times(0)).initWatch();
        }
    }

    @Test
    public void testValidateNodes() throws KeeperException, InterruptedException {
        ZkClient client = Mockito.mock(ZkClient.class);
        client.serviceName = "service";
        client.id = "id";
        doCallRealMethod().when(client).validateNodes();
        client.validateNodes();
        verify(client, Mockito.times(2)).ensureZNode(Mockito.any());
    }

    @Test
    public void testEnsureZNodeExists() throws KeeperException, InterruptedException {
        ZkClient client = Mockito.mock(ZkClient.class);
        when(client.getPaths(any())).thenCallRealMethod();
        doCallRealMethod().when(client).ensureZNode(any());
        ZooKeeper zkMock = Mockito.mock(ZooKeeper.class);
        when(zkMock.exists(any(), eq(false))).thenReturn(new Stat());
        client.zookeeper = zkMock;
        client.ensureZNode("test");
        verify(zkMock, Mockito.times(0))
                .create(eq("/test"), any(), eq(Ids.OPEN_ACL_UNSAFE), eq(CreateMode.PERSISTENT));
    }

    @Test
    public void testEnsureZNodeCreate() throws KeeperException, InterruptedException {
        ZkClient client = Mockito.mock(ZkClient.class);
        when(client.getPaths(any())).thenCallRealMethod();
        doCallRealMethod().when(client).ensureZNode(any());

        ZooKeeper zkMock = Mockito.mock(ZooKeeper.class);
        when(zkMock.exists(any(), eq(false))).thenReturn(null, new Stat());
        client.zookeeper = zkMock;
        client.ensureZNode("test");
        verify(zkMock, Mockito.times(1))
                .create(eq("/test"), any(), eq(Ids.OPEN_ACL_UNSAFE), eq(CreateMode.PERSISTENT));
    }


    @Test
    public void testEnsureZNodeCreateFails() throws KeeperException, InterruptedException {
        ZkClient client = Mockito.mock(ZkClient.class);
        when(client.getPaths(any())).thenCallRealMethod();
        doCallRealMethod().when(client).ensureZNode(any());

        ZooKeeper zkMock = Mockito.mock(ZooKeeper.class);
        when(zkMock.exists(Mockito.any(), eq(false))).thenReturn((Stat) null, (Stat) null);
        client.zookeeper = zkMock;
        try {
            client.ensureZNode("test");
        } catch (IllegalStateException e) {
            assertEquals("Zk node /test still does not exists", e.getMessage());
        }
        verify(zkMock, Mockito.times(1))
                .create(eq("/test"), Mockito.any(), eq(Ids.OPEN_ACL_UNSAFE), eq(CreateMode.PERSISTENT));
    }

}
