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


package org.openkilda.server42.control.stormstub.swagger;

import org.openkilda.server42.control.messaging.flowrtt.AddFlow;
import org.openkilda.server42.control.messaging.flowrtt.Message;

import com.fasterxml.classmate.TypeResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2WebMvc;


@EnableSwagger2WebMvc
@Configuration
public class SwaggerApiDocumentationConfig {

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder().title("Server42 storm stub REST CRUD")
                .description(
                        "HTTP control on server42")
                .termsOfServiceUrl("").version("0.0.1-SNAPSHOT").build();
    }

    @Autowired
    private TypeResolver typeResolver;

    @Bean
    public Docket configureControllerPackageAndConvertors() {
        return new Docket(DocumentationType.SWAGGER_2)
                .additionalModels(typeResolver.resolve(AddFlow.class),
                        typeResolver.resolve(Message.class))
                .select()
                .apis(RequestHandlerSelectors.basePackage("org.openkilda.server42.control.stormstub")).build()
                .apiInfo(apiInfo());
    }
}
