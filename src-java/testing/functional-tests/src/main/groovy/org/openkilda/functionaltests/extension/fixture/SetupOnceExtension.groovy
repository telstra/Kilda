package org.openkilda.functionaltests.extension.fixture

import static org.openkilda.functionaltests.extension.ExtensionHelper.isFeatureSpecial

import org.openkilda.functionaltests.extension.spring.SpringContextNotifier

import groovy.util.logging.Slf4j
import org.spockframework.runtime.extension.AbstractGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.MethodKind
import org.spockframework.runtime.model.SpecInfo
import org.springframework.beans.factory.config.AutowireCapableBeanFactory

/**
 * Due to JUnit + Spring nature, it is impossible to use Autowired fields in setupSpec (or BeforeClass).
 * Thus, providing new method 'setupOnce' which will run once at the beginning of the spec and have access
 * to Spring context.
 * Target spec should implement SetupOnce interface.
 * @see {@link SetupOnce}
 */
@Slf4j
class SetupOnceExtension extends AbstractGlobalExtension {
    void visitSpec(SpecInfo specInfo) {
        def features = specInfo.features.findAll { !isFeatureSpecial(it) }
        if(features) {
            def setupOnceInterceptor = new SetupOnceInterceptor()
            //if there is a 'setup' method, run 'setupOnce' before 'setup'
            specInfo.fixtureMethods.find { it.kind == MethodKind.SETUP }?.addInterceptor(setupOnceInterceptor)
            //if there is no 'setup', run 'setupOnce' right before the first feature
            features.findAll { !isFeatureSpecial(it) }*.addInterceptor(setupOnceInterceptor)
        }
    }

    class SetupOnceInterceptor implements IMethodInterceptor {
        def setupRan = false
        def setupThrowed = null

        @Override
        void intercept(IMethodInvocation invocation) throws Throwable {
            if (setupThrowed) {
                throw setupThrowed
            }
            if (!setupRan && SpringContextNotifier.context) {
                def spec = invocation.sharedInstance
                SpringContextNotifier.context.getAutowireCapableBeanFactory().autowireBeanProperties(
                        spec, AutowireCapableBeanFactory.AUTOWIRE_BY_NAME, false)
                if (spec instanceof SetupOnce) {
                    log.debug "Running fixture: setupOnce"
                    setupRan = true
                    try {
                        spec.setupOnce()
                    } catch (Throwable t) {
                        setupThrowed = t
                        throw t
                    }
                }
            }
            invocation.proceed()
        }
    }
}
