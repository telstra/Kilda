package org.openkilda.functionaltests.extension.env

import static org.junit.jupiter.api.Assumptions.assumeTrue

import org.openkilda.functionaltests.extension.spring.ContextAwareGlobalExtension
import org.openkilda.functionaltests.extension.tags.Tag
import org.openkilda.functionaltests.extension.tags.TagExtension

import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.SpecInfo
import org.springframework.beans.factory.annotation.Value

/**
 * Ensure that virtual test is not getting executed on 'hardware' env and vice-versa.
 * This is a fallback mechanism and originally testing scope should be leveraged using tags expression.
 *
 * @see TagExtension
 */
class AssumeProfileExtension extends ContextAwareGlobalExtension {

    @Value('${spring.profiles.active}')
    String profile

    @Override
    void visitSpec(SpecInfo spec) {
        //check before every feature, in case if 'where' block may return no data
        spec.allFeatures*.addInterceptor(new IMethodInterceptor() {
            @Override
            void intercept(IMethodInvocation invocation) throws Throwable {
                def tags = TagExtension.collectAllTags(invocation.feature)
                checkTags(tags)
                invocation.proceed()
            }
        })
        //check before every iteration
        spec.allFeatures*.getFeatureMethod()*.addInterceptor(new IMethodInterceptor() {
            @Override
            void intercept(IMethodInvocation invocation) throws Throwable {
                def tags = TagExtension.collectAllTags(invocation.iteration)
                checkTags(tags)
                invocation.proceed()
            }
        })
    }

    private void checkTags(Set<Tag> tags) {
        if (tags.contains(Tag.VIRTUAL) || tags.contains(Tag.HARDWARE)) { //if test is profile-dependent
            assumeTrue(tags*.toString().contains(profile.toUpperCase()),
"This test cannot be executed for current active profile: $profile")
        }
    }
}
