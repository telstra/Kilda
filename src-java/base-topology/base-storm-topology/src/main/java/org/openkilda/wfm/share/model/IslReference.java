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

package org.openkilda.wfm.share.model;

import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.model.Isl;

import lombok.EqualsAndHashCode;
import lombok.Value;

@Value
@EqualsAndHashCode
public class IslReference {
    private final Endpoint source;
    private final Endpoint dest;

    /**
     * Make {@link IslReference} from {@link IslInfoData}.
     */
    public static IslReference of(IslInfoData speakerData) {
        Endpoint source = new Endpoint(speakerData.getSource());
        Endpoint dest = new Endpoint(speakerData.getDestination());
        return new IslReference(source, dest);
    }

    /**
     * Make {@link IslReference} from {@link Isl}.
     */
    public static IslReference of(Isl daoData) {
        Endpoint source = Endpoint.of(daoData.getSrcSwitchId(), daoData.getSrcPort());
        Endpoint dest = Endpoint.of(daoData.getDestSwitchId(), daoData.getDestPort());
        return new IslReference(source, dest);
    }

    public static IslReference of(Endpoint endpoint) {
        return new IslReference(endpoint, null);
    }

    public IslReference(Endpoint source, Endpoint dest) {
        if (source == null && dest == null) {
            throw new IllegalArgumentException("At least one of ISL endpoints (source or dest) must be defined");
        }

        if (source == null) {
            this.source = dest;
            this.dest = null;
        } else if (dest == null) {
            this.source = source;
            this.dest = null;
        } else if (source.getDatapath().compareTo(dest.getDatapath()) < 0) {
            this.source = source;
            this.dest = dest;
        } else {
            this.source = dest;
            this.dest = source;
        }
    }

    /**
     * Return "opposite" endpoint by provided endpoint.
     *
     * <p>Provided endpoint must be part of {@link IslReference}, or {@link IllegalArgumentException} will be thrown.
     */
    public Endpoint getOpposite(Endpoint endpoint) {
        Endpoint opposite;
        if (source.equals(endpoint)) {
            opposite = dest;
        } else if (dest.equals(endpoint)) {
            opposite = source;
        } else {
            throw new IllegalArgumentException(String.format("Endpoint %s is not part of %s", endpoint, this));
        }
        return opposite;
    }

    public boolean isIncomplete() {
        return source == null || dest == null;
    }

    public boolean isSelfLoop() {
        return source.getDatapath().equals(dest.getDatapath());
    }

    @Override
    public String toString() {
        return String.format("%s <===> %s", source, dest);
    }
}
