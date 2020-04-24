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

package org.openkilda.persistence.ferma.frames;

import static java.lang.String.format;

import org.openkilda.model.Switch.SwitchData;
import org.openkilda.model.SwitchFeature;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.ferma.frames.converters.Convert;
import org.openkilda.persistence.ferma.frames.converters.SwitchFeatureConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchIdConverter;
import org.openkilda.persistence.ferma.frames.converters.SwitchStatusConverter;

import com.syncleus.ferma.FramedGraph;
import com.syncleus.ferma.annotations.Property;
import lombok.NonNull;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public abstract class SwitchFrame extends KildaBaseVertexFrame implements SwitchData {
    public static final String FRAME_LABEL = "switch";
    public static final String SWITCH_ID_PROPERTY = "name";
    public static final String STATUS_PROPERTY = "state";
    public static final String ADDRESS_PROPERTY = "address";
    public static final String PORT_PROPERTY = "port";

    private Set<SwitchFeature> features;

    @Override
    @Property(SWITCH_ID_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract SwitchId getSwitchId();

    @Override
    @Property(SWITCH_ID_PROPERTY)
    @Convert(SwitchIdConverter.class)
    public abstract void setSwitchId(@NonNull SwitchId switchId);

    @Override
    @Property(STATUS_PROPERTY)
    @Convert(SwitchStatusConverter.class)
    public abstract SwitchStatus getStatus();

    @Override
    @Property(STATUS_PROPERTY)
    @Convert(SwitchStatusConverter.class)
    public abstract void setStatus(SwitchStatus status);

    @Override
    public InetSocketAddress getSocketAddress() {
        int port = Optional.ofNullable(getProperty(PORT_PROPERTY)).map(l -> ((Long) l).intValue()).orElse(0);
        return Optional.ofNullable((String) getProperty(ADDRESS_PROPERTY))
                .map(address -> convert(address, port))
                .orElse(null);
    }

    private InetSocketAddress convert(String address, int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName(address), port);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(format("Switch address '%s' is invalid", address), e);
        }
    }

    @Override
    public void setSocketAddress(InetSocketAddress socketAddress) {
        setProperty(ADDRESS_PROPERTY, socketAddress != null && socketAddress.getAddress() != null
                ? socketAddress.getAddress().getHostAddress() : null);
        setProperty(PORT_PROPERTY, socketAddress != null
                ? (long) socketAddress.getPort() : null);
    }

    @Override
    @Property("hostname")
    public abstract String getHostname();

    @Override
    @Property("hostname")
    public abstract void setHostname(String hostname);

    @Override
    @Property("controller")
    public abstract String getController();

    @Override
    @Property("controller")
    public abstract void setController(String controller);

    @Override
    @Property("description")
    public abstract String getDescription();

    @Override
    @Property("description")
    public abstract void setDescription(String description);

    @Override
    @Property("of_version")
    public abstract String getOfVersion();

    @Override
    @Property("of_version")
    public abstract void setOfVersion(String ofVersion);

    @Override
    @Property("of_description_manufacturer")
    public abstract String getOfDescriptionManufacturer();

    @Override
    @Property("of_description_manufacturer")
    public abstract void setOfDescriptionManufacturer(String ofDescriptionManufacturer);

    @Override
    @Property("of_description_hardware")
    public abstract String getOfDescriptionHardware();

    @Override
    @Property("of_description_hardware")
    public abstract void setOfDescriptionHardware(String ofDescriptionHardware);

    @Override
    @Property("of_description_software")
    public abstract String getOfDescriptionSoftware();

    @Override
    @Property("of_description_software")
    public abstract void setOfDescriptionSoftware(String ofDescriptionSoftware);

    @Override
    @Property("of_description_serial_number")
    public abstract String getOfDescriptionSerialNumber();

    @Override
    @Property("of_description_serial_number")
    public abstract void setOfDescriptionSerialNumber(String ofDescriptionSerialNumber);

    @Override
    @Property("of_description_datapath")
    public abstract String getOfDescriptionDatapath();

    @Override
    @Property("of_description_datapath")
    public abstract void setOfDescriptionDatapath(String ofDescriptionDatapath);

    @Override
    @Property("under_maintenance")
    public abstract boolean isUnderMaintenance();

    @Override
    @Property("under_maintenance")
    public abstract void setUnderMaintenance(boolean underMaintenance);

    @Override
    @Property("pop")
    public abstract String getPop();

    @Override
    @Property("pop")
    public abstract void setPop(String pop);

    @Override
    public Set<SwitchFeature> getFeatures() {
        if (features == null) {
            features = new HashSet<>();
            getElement().properties("features").forEachRemaining(property -> {
                if (property.isPresent()) {
                    Object propertyValue = property.value();
                    if (propertyValue instanceof Collection) {
                        ((Collection<String>) propertyValue).forEach(entry ->
                                features.add(SwitchFeatureConverter.INSTANCE.toEntityAttribute(entry)));
                    } else {
                        features.add(SwitchFeatureConverter.INSTANCE.toEntityAttribute((String) propertyValue));
                    }
                }
            });
        }
        return features;
    }

    @Override
    public void setFeatures(Set<SwitchFeature> features) {
        this.features = features;

        getElement().properties("features").forEachRemaining(property -> property.remove());

        features.forEach(value ->
                getElement().property(VertexProperty.Cardinality.set, "features",
                        SwitchFeatureConverter.INSTANCE.toGraphProperty(value)));
    }

    public static Optional<SwitchFrame> load(FramedGraph graph, SwitchId switchId) {
        return Optional.ofNullable(graph.traverse(input -> input.V()
                .hasLabel(FRAME_LABEL)
                .has(SWITCH_ID_PROPERTY, SwitchIdConverter.INSTANCE.toGraphProperty(switchId)))
                .nextOrDefaultExplicit(SwitchFrame.class, null));
    }
}
