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

package org.openkilda.wfm.share.flow.resources;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.openkilda.model.FlowCookie;
import org.openkilda.persistence.InMemoryGraphBasedTest;
import org.openkilda.persistence.repositories.FlowCookieRepository;

import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class CookiePoolTest extends InMemoryGraphBasedTest {
    private static final long MIN_COOKIE = 5L;
    private static final long MAX_COOKIE = 25L;

    private CookiePool cookiePool;
    private FlowCookieRepository flowCookieRepository;

    @Before
    public void setUp() {
        cookiePool = new CookiePool(persistenceManager, MIN_COOKIE, MAX_COOKIE);
        flowCookieRepository = persistenceManager.getRepositoryFactory().createFlowCookieRepository();
    }

    @Test
    public void cookiePool() {
        Set<Long> cookies = new HashSet<>();
        for (long i = MIN_COOKIE; i <= MAX_COOKIE; i++) {
            cookies.add(cookiePool.allocate(format("flow_%d", i)));
        }
        assertEquals(MAX_COOKIE - MIN_COOKIE + 1, cookies.size());
        cookies.forEach(cookie -> assertTrue(cookie >= MIN_COOKIE && cookie <= MAX_COOKIE));
    }

    @Test(expected = ResourceNotAvailableException.class)
    public void cookiePoolFullTest() {
        for (long i = MIN_COOKIE; i <= MAX_COOKIE + 1; i++) {
            assertTrue(cookiePool.allocate(format("flow_%d", i)) > 0);
        }
    }

    @Test
    public void cookieLldp() {
        // checks that we able to create two cookies for one flow
        String flowId = "flow_1";
        long flowCookie = cookiePool.allocate(flowId);
        long lldpCookie = cookiePool.allocate(flowId);
        assertNotEquals(flowCookie, lldpCookie);
    }

    @Test
    public void deallocateCookiesTest() {
        cookiePool.allocate("flow_1");
        cookiePool.allocate("flow_2");
        long cookie = cookiePool.allocate("flow_3");
        assertEquals(3, flowCookieRepository.findAll().size());

        cookiePool.deallocate(cookie);
        Collection<FlowCookie> flowCookies = flowCookieRepository.findAll();
        assertEquals(2, flowCookies.size());
        assertFalse(flowCookies.stream()
                .map(FlowCookie::getUnmaskedCookie)
                .collect(Collectors.toList())
                .contains(cookie));
    }
}
