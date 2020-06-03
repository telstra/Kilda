/* Copyright 2018 Telstra Open Source
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

package org.openkilda.testing.config;

import org.openkilda.testing.service.floodlight.FloodlightServiceImpl;
import org.openkilda.testing.service.floodlight.model.Floodlight;
import org.openkilda.testing.service.floodlight.model.FloodlightConnectMode;
import org.openkilda.testing.tools.ExtendedErrorHandler;
import org.openkilda.testing.tools.LoggingRequestInterceptor;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.EnumUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.support.BasicAuthorizationInterceptor;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableRetry
@PropertySource("file:${kilda.config.file:kilda.properties}")
@ComponentScan(basePackages = {"org.openkilda.testing.service", "org.openkilda.testing.tools"})
public class DefaultServiceConfig {

    public DefaultServiceConfig() {
        System.setProperty("http.maxConnections", "1000");
    }

    @Bean(name = "northboundRestTemplate")
    public RestTemplate northboundRestTemplate(
            @Value("${northbound.endpoint}") String endpoint,
            @Value("${northbound.username}") String username,
            @Value("${northbound.password}") String password) {
        RestTemplate restTemplate = buildRestTemplateWithAuth(endpoint, username, password);
        restTemplate.getInterceptors().add(plusEncoderInterceptor());
        return restTemplate;
    }

    @Bean(name = "floodlightRestTemplate")
    public RestTemplate floodlightRestTemplate(
            @Value("#{'${floodlight.endpoints}'.split(',')[0]}") String endpoint,
            @Value("${floodlight.username}") String username,
            @Value("${floodlight.password}") String password) {
        return buildRestTemplateWithAuth(endpoint, username, password);
    }

    @Bean(name = "elasticSearchRestTemplate")
    public RestTemplate elasticSearchRestTemplate(
            @Value("${elasticsearch.endpoint}") String endpoint,
            @Value("${elasticsearch.username}") String username,
            @Value("${elasticsearch.password}") String password) {
        return buildRestTemplateWithAuth(endpoint, username, password);
    }

    @Bean(name = "traffExamRestTemplate")
    public RestTemplate traffExamRestTemplate() {
        return buildLoggingRestTemplate();
    }

    @Bean(name = "lockKeeperRestTemplates")
    public Map<String, RestTemplate> lockKeeperRestTemplates(
            @Value("${lockkeeper.port}") Integer lockKeeperPort,
            @Value("#{'${floodlight.regions}'.split(',')}") List<String> regions,
            @Value("#{'${floodlight.endpoints}'.split(',')}") List<String> flEndpoints) {
        Map<String, RestTemplate> result = new HashMap<>();
        for (int i = 0; i < flEndpoints.size(); i++) {
            String lockKeeperEndpoint = flEndpoints.get(i)
                    .replaceFirst("(.*):\\d+", "$1:" + lockKeeperPort);
            result.put(regions.get(i), buildLoggingRestTemplate(lockKeeperEndpoint));
        }
        return result;
    }

    @Bean
    public List<Floodlight> floodlights(@Value("#{'${floodlight.openflows}'.split(',')}") List<String> openflows,
                                        @Value("#{'${floodlight.endpoints}'.split(',')}") List<String> endpoints,
                                        @Value("#{'${floodlight.containers}'.split(',')}") List<String> containers,
                                        @Value("#{'${floodlight.regions}'.split(',')}") List<String> regions,
                                        @Value("#{'${floodlight.modes}'.split(',')}") List<String> modes) {
        if (openflows.size() != endpoints.size() || openflows.size() != containers.size()
                || openflows.size() != regions.size() || openflows.size() != modes.size()) {
            throw new IllegalArgumentException("Floodlight test properties are illegal. Sizes of floodlight.openflows,"
                    + " floodlight.endpoints, floodlight.containers, floodlight.regions, floodlight.modes should be "
                    + "equal");
        }
        List<Floodlight> floodlights = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            floodlights.add(new Floodlight(openflows.get(i), containers.get(i), regions.get(i),
                    new FloodlightServiceImpl(endpoints.get(i)),
                    EnumUtils.getEnumIgnoreCase(FloodlightConnectMode.class, modes.get(i))));
        }
        return floodlights;
    }

    @Bean(name = "otsdbRestTemplate")
    public RestTemplate otsdbRestTemplate(@Value("${opentsdb.endpoint}") String endpoint) {
        return buildLoggingRestTemplate(endpoint);
    }

    @Bean(name = "labApiRestTemplate")
    public RestTemplate labApiRestTemplate(@Value("${lab-api.endpoint}") String endpoint) {
        return buildLoggingRestTemplate(endpoint);
    }

    @Bean(name = "grpcRestTemplate")
    public RestTemplate grpcRestTemplate(
            @Value("${grpc.endpoint}") String endpoint,
            @Value("${grpc.username}") String username,
            @Value("${grpc.password}") String password) {
        return buildRestTemplateWithAuth(endpoint, username, password);
    }

    /**
     * Build rest template with enabled logging support.
     */
    public static RestTemplate buildLoggingRestTemplate(String endpoint) {
        final RestTemplate restTemplate = buildLoggingRestTemplate();
        restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory(endpoint));
        return restTemplate;
    }

    /**
     * Build rest template with enabled logging support.
     */
    public static RestTemplate buildLoggingRestTemplate() {
        final RestTemplate restTemplate = new RestTemplate(new BufferingClientHttpRequestFactory(
                new HttpComponentsClientHttpRequestFactory()));
        List<ClientHttpRequestInterceptor> interceptors = restTemplate.getInterceptors();
        interceptors.add(new LoggingRequestInterceptor());
        restTemplate.setErrorHandler(new ExtendedErrorHandler());
        return restTemplate;
    }

    /**
     * Build rest template with enabled logging support and basic auth.
     */
    public static RestTemplate buildRestTemplateWithAuth(String endpoint, String username, String password) {
        RestTemplate restTemplate = buildLoggingRestTemplate(endpoint);
        restTemplate.getInterceptors().add(new BasicAuthorizationInterceptor(username, password));
        return restTemplate;
    }

    private ClientHttpRequestInterceptor plusEncoderInterceptor() {
        return new ClientHttpRequestInterceptor() {
            @Override
            public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                                ClientHttpRequestExecution execution) throws IOException {
                return execution.execute(new HttpRequestWrapper(request) {
                    @Override
                    public URI getURI() {
                        URI uri = super.getURI();
                        String escapedQuery = StringUtils.replace(uri.getRawQuery(), "+", "%2B");
                        return UriComponentsBuilder.fromUri(uri)
                                .replaceQuery(escapedQuery)
                                .build(true).toUri();
                    }
                }, body);
            }
        };
    }
}
