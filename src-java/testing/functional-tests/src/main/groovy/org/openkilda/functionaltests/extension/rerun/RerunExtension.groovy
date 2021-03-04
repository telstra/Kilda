package org.openkilda.functionaltests.extension.rerun

import org.spockframework.runtime.extension.AbstractAnnotationDrivenExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.FeatureInfo
import spock.lang.Specification

/**
 * Forces certain test to be rerun for certain amount of times. Test will be considered successful only if all
 * iterations succeed. The execution will be stopped if any 'rerun' fails and the test will be considered as failed. 
 * If annotation is applied to a parametrized test (with 'where'), then all permutations will be run
 * first, then the whole permutations set will be rerun the given amount of times.<br/>
 * Useful if the test tries to catch a certain race condition and usually needs multiple 'tries' to make it happen.<br/>
 * Can also be useful to debug certain test and verify its stability.
 *
 * @see Rerun
 */
class RerunExtension extends AbstractAnnotationDrivenExtension<Rerun> {

    @Override
    void visitFeatureAnnotation(Rerun annotation, FeatureInfo feature) {
        if(annotation.times() < 0) {
            throw new IllegalArgumentException("Rerun annotation can only have positive values")
        }
        feature.addInterceptor(new IMethodInterceptor() {
            @Override
            void intercept(IMethodInvocation featureInvocation) throws Throwable {
                featureInvocation.feature.name += "[0]"
                featureInvocation.proceed()
                annotation.times().times {
                    featureInvocation.feature.name = featureInvocation.feature.name.replaceAll(~/\[\d+]$/,"[${it + 1}]")
                    featureInvocation.proceed()
                }
            }
        })
    }
}
