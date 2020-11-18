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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;

public class ZkWriterTest {
    @Test
    public void testValidateNodes() throws KeeperException, InterruptedException {
        ZkWriter writer = Mockito.mock(ZkWriter.class);
        doCallRealMethod().when(writer).validateNodes();
        writer.validateNodes();
        verify(writer, Mockito.times(3)).ensureZNode(any());
    }

    @Test
    public void testProcess() throws KeeperException, InterruptedException, IOException {
        ZkWriter writer = Mockito.mock(ZkWriter.class);
        doCallRealMethod().when(writer).process(any());
        writer.process(new WatchedEvent(EventType.NodeDataChanged, KeeperState.SyncConnected, "/test"));
        verify(writer, Mockito.times(1)).refreshConnection(KeeperState.SyncConnected);
    }
}
