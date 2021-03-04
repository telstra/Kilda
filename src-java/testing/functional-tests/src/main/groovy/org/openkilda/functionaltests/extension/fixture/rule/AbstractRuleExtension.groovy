package org.openkilda.functionaltests.extension.fixture.rule


import org.openkilda.functionaltests.extension.spring.SpringContextListener
import org.openkilda.functionaltests.extension.spring.SpringContextNotifier

import groovy.util.logging.Slf4j
import org.spockframework.runtime.extension.AbstractAnnotationDrivenExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.FeatureInfo
import org.spockframework.runtime.model.SpecInfo
import org.springframework.context.ApplicationContext

import java.lang.annotation.Annotation

/**
 * Provides easy interface for adding fixture actions before/after test via spock extension (similar to JUnit rules).
 * If `T` annotation is applied on a spec-level, the annotation effect will be spread on every feature in the spec.
 *
 * @param <T> bound annotation
 */
@Slf4j
abstract class AbstractRuleExtension<T extends Annotation> extends AbstractAnnotationDrivenExtension<T>
        implements SpringContextListener {

    void beforeTest(IMethodInvocation invocation) {}

    /**
     * Will be executed only if test method succeeds. This was made intentional in order to fit our failfast style.
     */
    void afterTest(IMethodInvocation invocation) {}

    //can be later extended with beforeSpec() and afterSpec()

    @Override
    void visitSpecAnnotation(T annotation, SpecInfo spec) {
        for (FeatureInfo feature : spec.getFeatures()) {
            visitFeatureAnnotation(annotation, feature)
        }
    }

    @Override
    void visitFeatureAnnotation(T annotation, FeatureInfo feature) {
        feature.getFeatureMethod().addInterceptor(new IMethodInterceptor() {
            @Override
            void intercept(IMethodInvocation invocation) throws Throwable {
                beforeTest()
                invocation.proceed()
                afterTest()
            }
        })
    }

    @Override
    void visitSpec(SpecInfo spec) {
        SpringContextNotifier.addListener(this)
    }

    @Override
    void notifyContextInitialized(ApplicationContext applicationContext) {
        applicationContext.autowireCapableBeanFactory.autowireBean(this)
    }
}