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

package org.openkilda.persistence.ferma;

import com.syncleus.ferma.ReflectionCache;
import com.syncleus.ferma.framefactories.annotation.AbstractAnnotationFrameFactory;
import com.syncleus.ferma.framefactories.annotation.AdjacencyMethodHandler;
import com.syncleus.ferma.framefactories.annotation.InVertexMethodHandler;
import com.syncleus.ferma.framefactories.annotation.IncidenceMethodHandler;
import com.syncleus.ferma.framefactories.annotation.MethodHandler;
import com.syncleus.ferma.framefactories.annotation.OutVertexMethodHandler;

import java.util.HashSet;
import java.util.Set;

public final class AnnotationFrameFactoryWithConverterSupport extends AbstractAnnotationFrameFactory {
    public AnnotationFrameFactoryWithConverterSupport() {
        super(new ReflectionCache(), collectHandlers());
    }

    private static Set<MethodHandler> collectHandlers() {
        final Set<MethodHandler> methodHandlers = new HashSet<>();

        final PropertyMethodHandlerWithConverterSupport propertyHandler
                = new PropertyMethodHandlerWithConverterSupport();
        methodHandlers.add(propertyHandler);

        final InVertexMethodHandler inVertexHandler = new InVertexMethodHandler();
        methodHandlers.add(inVertexHandler);

        final OutVertexMethodHandler outVertexHandler = new OutVertexMethodHandler();
        methodHandlers.add(outVertexHandler);

        final AdjacencyMethodHandler adjacencyHandler = new AdjacencyMethodHandler();
        methodHandlers.add(adjacencyHandler);

        final IncidenceMethodHandler incidenceHandler = new IncidenceMethodHandler();
        methodHandlers.add(incidenceHandler);

        return methodHandlers;
    }
}
