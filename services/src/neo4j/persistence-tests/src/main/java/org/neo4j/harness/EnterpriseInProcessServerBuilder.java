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

package org.neo4j.harness;

import org.neo4j.harness.internal.InProcessServerBuilder;

/**
 * This is for {@code org.neo4j.ogm.testutil.TestServer#newInProcessBuilder} to pass
 * additional configuration parameters and register needed procedures.
 */
public class EnterpriseInProcessServerBuilder extends InProcessServerBuilder {
    public EnterpriseInProcessServerBuilder() {
        withConfig("apoc.import.file.enabled", "true");
    }
}
