package org.openkilda.functionaltests.extension.spring

import groovy.util.logging.Slf4j
import org.spockframework.runtime.extension.AbstractGlobalExtension
import org.spockframework.runtime.model.SpecInfo
import org.springframework.context.ApplicationContext

/**
 * Allows to postpone 'visitSpec' execution until Spring context is actually initialized. Not expected to work if
 * you are adding setupSpec interceptors.
 */
@Slf4j
abstract class ContextAwareGlobalExtension extends AbstractGlobalExtension implements SpringContextListener {
    { SpringContextNotifier.addListener(this) }

    ApplicationContext context
    List<Closure> delayedActions = []

    @Override
    void visitSpec(SpecInfo spec) {
        if(!context) {
            log.debug("Register delayed visitSpec action for $spec.name")
            delayedActions << { delayedVisitSpec(spec) }
        } else {
            delayedVisitSpec(spec)
        }
    }

    void delayedVisitSpec(SpecInfo spec) { }

    @Override
    void notifyContextInitialized(ApplicationContext applicationContext) {
        applicationContext.autowireCapableBeanFactory.autowireBean(this)
        context = applicationContext
        if(delayedActions) {
            log.debug("Execute ${delayedActions.size()} delayed visitSpec actions")
            delayedActions.each { it.call() }
        }
    }
}
