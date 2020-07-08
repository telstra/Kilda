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

package org.openkilda.floodlight.shared.packet;

import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.floodlightcontroller.packet.BasePacket;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.UDP;
import org.projectfloodlight.openflow.types.TransportPort;

import java.nio.ByteBuffer;

/**
 * This class represents VxLAN header.
 * <p/>
 * VxLAN Header:
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |R|R|R|R|I|R|R|R|            Reserved                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                VxLAN Network Identifier (VNI) |   Reserved    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
@lombok.Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class Vxlan extends BasePacket {
    // The flag set indicates tunnel data is present
    private static final byte FLAGS = 0x8;
    private static final int VXLAN_HEADER_SIZE_IN_BYTES = 8;
    private static final int FIRST_RESERVED_AREA_IN_BYTES = 3;
    private static final int SECOND_RESERVED_AREA_IN_BITS = 8;

    private int vni;

    public static final TransportPort VXLAN_UDP_DST_PORT = TransportPort.of(4789);

    static {
        UDP.decodeMap.put(VXLAN_UDP_DST_PORT, VlanTag.class);
    }

    @Override
    public byte[] serialize() {
        byte[] payloadData = null;
        if (this.payload != null) {
            this.payload.setParent(this);
            payloadData = this.payload.serialize();
        }

        int length = (short) (VXLAN_HEADER_SIZE_IN_BYTES + (payloadData == null ? 0 : payloadData.length));
        byte[] data = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.put(FLAGS);
        bb.put(new byte[FIRST_RESERVED_AREA_IN_BYTES]);
        // 8 bit shift to add reserved area
        bb.putInt(vni << SECOND_RESERVED_AREA_IN_BITS);

        if (payloadData != null) {
            bb.put(payloadData);
        }

        return data;
    }

    @Override
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        // skip 4 bytes: 8 bits flags and 24 bits reserved area
        bb.getInt();
        // 8 bit shift to remove reserved area
        this.vni = bb.getInt() >> SECOND_RESERVED_AREA_IN_BITS;

        if (payload == null) {
            byte[] buf = new byte[bb.remaining()];
            bb.get(buf);
            payload = new net.floodlightcontroller.packet.Data(buf);
        }
        this.payload.setParent(this);
        return this;
    }
}
