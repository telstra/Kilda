package org.openkilda.persistence.tests.neo4j;

import org.openkilda.persistence.tests.neo4j.Neo4jUserProceduresExtension.Dependencies;

import org.neo4j.harness.internal.HarnessRegisteredProcs;
import org.neo4j.kernel.extension.KernelExtensionFactory;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.kernel.impl.spi.KernelContext;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;

public class Neo4jUserProceduresExtension extends KernelExtensionFactory<Dependencies> {
    public Neo4jUserProceduresExtension() {
        super("user-procs");
    }

    public Lifecycle newInstance(KernelContext context, Dependencies dependencies) {
        return new LifecycleAdapter() {
            public void start() throws Throwable {
                HarnessRegisteredProcs userProcs = new HarnessRegisteredProcs();
                userProcs.addProcedure(apoc.cypher.Cypher.class);
                userProcs.addProcedure(apoc.export.graphml.ExportGraphML.class);
                userProcs.addProcedure(org.neo4j.graphalgo.KShortestPathsProc.class);
                userProcs.applyTo(dependencies.procedures());
            }
        };
    }

    public interface Dependencies {
        Procedures procedures();
    }
}
