package org.openkilda.functionaltests.extension

import groovy.util.logging.Slf4j
import org.opentest4j.TestAbortedException
import org.spockframework.runtime.extension.AbstractGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.SpecInfo
import spock.lang.Ignore

@Slf4j
class SkippedTestsLogger extends AbstractGlobalExtension {
    @Override
    void visitSpec(SpecInfo spec) {
        spec.allFixtureMethods*.addInterceptor(new AssumptionInterceptor())
        spec.allFeatures*.getFeatureMethod()*.addInterceptor(new AssumptionInterceptor())

        //process Ignore annotations
        def specIgnore = spec.getAnnotation(Ignore)
        if(specIgnore) {
            log.warn("Ignored spec: ${spec.name}\nReason: ${specIgnore.value()}")
        }
        spec.features*.featureMethod.each { feature ->
            def featureIgnore = feature.getAnnotation(Ignore)
            if(featureIgnore) {
                log.warn("Ignored test: ${feature.feature.spec.name}#${feature.name}\n" +
                        "Reason: ${featureIgnore.value()}")
            }
        }
    }

    class AssumptionInterceptor implements IMethodInterceptor {
        @Override
        void intercept(IMethodInvocation invocation) throws Throwable {
            try {
                invocation.proceed()
            } catch (TestAbortedException t) {
                log.warn("Aborted test: ${invocation.feature.spec.name}#${invocation.iteration.name}\n" +
                        "Reason: ${t.message}")
                throw t
            }
        }
    }
}
