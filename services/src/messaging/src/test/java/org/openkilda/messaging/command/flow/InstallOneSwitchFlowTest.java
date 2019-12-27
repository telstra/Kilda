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

package org.openkilda.messaging.command.flow;

import static org.openkilda.messaging.command.Constants.bandwidth;
import static org.openkilda.messaging.command.Constants.flowName;
import static org.openkilda.messaging.command.Constants.inputPort;
import static org.openkilda.messaging.command.Constants.inputVlanId;
import static org.openkilda.messaging.command.Constants.meterId;
import static org.openkilda.messaging.command.Constants.outputPort;
import static org.openkilda.messaging.command.Constants.outputVlanId;
import static org.openkilda.messaging.command.Constants.outputVlanType;
import static org.openkilda.messaging.command.Constants.switchId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.openkilda.model.Metadata;
import org.openkilda.model.OutputVlanType;

import org.junit.Test;

import java.util.HashSet;
import java.util.UUID;

public class InstallOneSwitchFlowTest {
    private InstallOneSwitchFlow flow = new InstallOneSwitchFlow(UUID.randomUUID(), flowName, 0L, switchId,
            inputPort, outputPort, inputVlanId, outputVlanId, outputVlanType, bandwidth, meterId, true,
            false, false, new HashSet<>(), Metadata.builder().build());

    @Test
    public void toStringTest() {
        String flowString = flow.toString();
        assertNotNull(flowString);
        assertFalse(flowString.isEmpty());
    }

    @Test
    public void getOutputVlanType() {
        assertEquals(outputVlanType, flow.getOutputVlanType());
    }

    @Test
    public void getBandwidth() {
        assertEquals(bandwidth, flow.getBandwidth().longValue());
    }

    @Test
    public void getMeterId() {
        assertEquals(meterId, flow.getMeterId().longValue());
    }

    @Test
    public void getInputVlanId() {
        assertEquals(inputVlanId, flow.getInputVlanId().intValue());
    }

    @Test
    public void getOutputVlanId() {
        assertEquals(outputVlanId, flow.getOutputVlanId().intValue());
    }

    @Test
    public void setBandwidth() {
        flow.setBandwidth(bandwidth);
        assertEquals(bandwidth, flow.getBandwidth().longValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setNullBandwidth() {
        flow.setBandwidth(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setNegativeBandwidth() {
        flow.setBandwidth(-1L);
    }

    @Test
    public void setMeterId() {
        flow.setMeterId(meterId);
        assertEquals(meterId, flow.getMeterId().longValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setNegativeMeterId() {
        flow.setMeterId(-1L);
    }

    @Test
    public void setInputVlanId() {
        flow.setInputVlanId(inputVlanId);
        assertEquals(inputVlanId, flow.getInputVlanId().intValue());
    }

    @Test
    public void setNullInputVlanId() {
        flow.setInputVlanId(null);
        assertEquals(0, flow.getInputVlanId().intValue());
    }

    @Test
    public void setZeroInputVlanId() {
        flow.setInputVlanId(0);
        assertEquals(0, flow.getInputVlanId().intValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setNegativeInputVlanId() {
        flow.setInputVlanId(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setTooBigInputVlanId() {
        flow.setInputVlanId(4096);
    }

    @Test
    public void setOutputVlanId() {
        flow.setOutputVlanId(outputVlanId);
        assertEquals(outputVlanId, flow.getOutputVlanId().intValue());
    }

    @Test
    public void setNullOutputVlanId() {
        flow.setOutputVlanId(null);
        assertEquals(0, flow.getOutputVlanId().intValue());
    }

    @Test
    public void setZeroOutputVlanId() {
        flow.setOutputVlanId(0);
        assertEquals(0, flow.getOutputVlanId().intValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setNegativeOutputVlanId() {
        flow.setOutputVlanId(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setTooBigOutputVlanId() {
        flow.setOutputVlanId(4096);
    }

    @Test
    public void setOutputVlanType() {
        flow.setOutputVlanId(outputVlanId);
        flow.setOutputVlanType(outputVlanType);
        assertEquals(outputVlanType, flow.getOutputVlanType());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setNullOutputVlanType() {
        flow.setOutputVlanType(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setInvalidOutputVlanType() {
        flow.setOutputVlanType(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setIncorrectNoneOutputVlanType() {
        flow.setOutputVlanId(outputVlanId);
        flow.setOutputVlanType(OutputVlanType.NONE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setIncorrectPopOutputVlanType() {
        flow.setOutputVlanId(outputVlanId);
        flow.setOutputVlanType(OutputVlanType.POP);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setIncorrectPushOutputVlanType() {
        flow.setOutputVlanId(0);
        flow.setOutputVlanType(OutputVlanType.PUSH);
    }

    @Test(expected = IllegalArgumentException.class)
    public void setIncorrectReplaceOutputVlanType() {
        flow.setOutputVlanId(null);
        flow.setOutputVlanType(OutputVlanType.REPLACE);
    }
}
