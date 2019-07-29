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

package org.openkilda.persistence.ferma.repositories.frames;

import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.ferma.model.Switch;

import com.syncleus.ferma.AbstractElementFrame;
import com.syncleus.ferma.AbstractVertexFrame;
import com.syncleus.ferma.DelegatingFramedGraph;
import com.syncleus.ferma.FramedGraph;
import com.syncleus.ferma.annotations.Property;
import lombok.NonNull;
import org.apache.tinkerpop.gremlin.neo4j.structure.Neo4jVertex;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.time.Instant;
import java.util.Optional;

public class SwitchFrame extends AbstractVertexFrame implements Switch {
    public static final String FRAME_LABEL = "switch";

    public static final String SWITCH_ID_PROPERTY = "name";
    public static final String STATUS_PROPERTY = "state";

    private Vertex cachedElement;

    @Override
    public Vertex getElement() {
        // A workaround for the issue with neo4j-gremlin and Ferma integration.
        if (cachedElement == null) {
            try {
                java.lang.reflect.Field field = AbstractElementFrame.class.getDeclaredField("element");
                field.setAccessible(true);
                Object value = field.get(this);
                field.setAccessible(false);
                if (value instanceof Neo4jVertex) {
                    cachedElement = (Vertex) value;
                }
            } catch (NoSuchFieldException | IllegalAccessException ex) {
                // just ignore
            }

            if (cachedElement == null) {
                cachedElement = super.getElement();
            }
        }

        return cachedElement;
    }

    @Override
    public SwitchId getSwitchId() {
        return new SwitchId(getProperty(SWITCH_ID_PROPERTY));
    }

    @Property(SWITCH_ID_PROPERTY)
    @Override
    public void setSwitchId(@NonNull SwitchId switchId) {
        setProperty(SWITCH_ID_PROPERTY, switchId.toString());
    }

    @Override
    public SwitchStatus getStatus() {
        String value = getProperty(STATUS_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return SwitchStatus.valueOf(value.toUpperCase());
    }

    @Override
    public void setStatus(SwitchStatus status) {
        setProperty(STATUS_PROPERTY, status == null ? null : status.name().toLowerCase());
    }

    @Override
    public String getAddress() {
        return getProperty("address");
    }

    @Override
    public void setAddress(String address) {
        setProperty("address", address);
    }

    @Override
    public String getHostname() {
        return getProperty("hostname");
    }

    @Override
    public void setHostname(String hostname) {
        setProperty("hostname", hostname);
    }

    @Override
    public String getController() {
        return getProperty("controller");
    }

    @Override
    public void setController(String controller) {
        setProperty("controller", controller);
    }

    @Override
    public String getDescription() {
        return getProperty("description");
    }

    @Override
    public void setDescription(String description) {
        setProperty("description", description);
    }

    @Property("of_version")
    @Override
    public String getOfVersion() {
        return getProperty("of_version");
    }

    @Override
    public void setOfVersion(String ofVersion) {
        setProperty("of_version", ofVersion);
    }

    @Override
    public String getOfDescriptionManufacturer() {
        return getProperty("of_description_manufacturer");
    }

    @Override
    public void setOfDescriptionManufacturer(String ofDescriptionManufacturer) {
        setProperty("of_description_manufacturer", ofDescriptionManufacturer);
    }

    @Override
    public String getOfDescriptionHardware() {
        return getProperty("of_description_hardware");
    }

    @Override
    public void setOfDescriptionHardware(String ofDescriptionHardware) {
        setProperty("of_description_hardware", ofDescriptionHardware);
    }

    @Override
    public String getOfDescriptionSoftware() {
        return getProperty("of_description_software");
    }

    @Override
    public void setOfDescriptionSoftware(String ofDescriptionSoftware) {
        setProperty("of_description_software", ofDescriptionSoftware);
    }

    @Override
    public String getOfDescriptionSerialNumber() {
        return getProperty("of_description_serial_number");
    }

    @Override
    public void setOfDescriptionSerialNumber(String ofDescriptionSerialNumber) {
        setProperty("of_description_serial_number", ofDescriptionSerialNumber);
    }

    @Override
    public String getOfDescriptionDatapath() {
        return getProperty("of_description_datapath");
    }

    @Override
    public void setOfDescriptionDatapath(String ofDescriptionDatapath) {
        setProperty("of_description_datapath", ofDescriptionDatapath);
    }

    @Override
    public boolean isUnderMaintenance() {
        return Optional.ofNullable((Boolean) getProperty("under_maintenance")).orElse(false);
    }

    @Override
    public void setUnderMaintenance(boolean underMaintenance) {
        setProperty("under_maintenance", underMaintenance);
    }

    @Override
    public Instant getTimeCreate() {
        String value = getProperty("time_create");
        return value == null ? null : Instant.parse(value);
    }

    @Override
    public void setTimeCreate(Instant timeCreate) {
        setProperty("time_create", timeCreate == null ? null : timeCreate.toString());
    }

    @Override
    public Instant getTimeModify() {
        String value = getProperty("time_modify");
        return value == null ? null : Instant.parse(value);
    }

    @Override
    public void setTimeModify(Instant timeModify) {
        setProperty("time_modify", timeModify == null ? null : timeModify.toString());
    }

    public void updateWith(Switch sw) {
        setStatus(sw.getStatus());
        setAddress(sw.getAddress());
        setHostname(sw.getHostname());
        setController(sw.getController());
        setDescription(sw.getDescription());
        setOfVersion(sw.getOfVersion());
        setOfDescriptionManufacturer(sw.getOfDescriptionManufacturer());
        setOfDescriptionHardware(sw.getOfDescriptionHardware());
        setOfDescriptionSoftware(sw.getOfDescriptionSoftware());
        setOfDescriptionSerialNumber(sw.getOfDescriptionSerialNumber());
        setOfDescriptionDatapath(sw.getOfDescriptionDatapath());
        setUnderMaintenance(sw.isUnderMaintenance());
        setTimeModify(sw.getTimeModify());
    }

    public void delete() {
        remove();
    }

    public static SwitchFrame addNew(FramedGraph graph, Switch newSwitch) {
        // A workaround for improper implementation of the untyped mode in OrientTransactionFactoryImpl.
        Vertex element = ((DelegatingFramedGraph) graph).getBaseGraph().addVertex(T.label, FRAME_LABEL);
        SwitchFrame frame = graph.frameElementExplicit(element, SwitchFrame.class);
        frame.setSwitchId(newSwitch.getSwitchId());
        frame.setTimeCreate(newSwitch.getTimeCreate());
        frame.updateWith(newSwitch);
        return frame;
    }

    public static SwitchFrame load(FramedGraph graph, SwitchId switchId) {
        return graph.traverse(input -> input.V().hasLabel(FRAME_LABEL).has(SWITCH_ID_PROPERTY, switchId.toString()))
                .nextOrDefaultExplicit(SwitchFrame.class, null);
    }

    public static void delete(FramedGraph graph, Switch sw) {
        if (sw instanceof SwitchFrame) {
            ((SwitchFrame) sw).delete();
        } else {
            SwitchFrame switchFrame = load(graph, sw.getSwitchId());
            if (switchFrame != null) {
                switchFrame.delete();
            }
        }
    }
}