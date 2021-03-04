package org.openkilda.functionaltests.extension.spring

import static org.openkilda.functionaltests.extension.ExtensionHelper.isFeatureSpecial

import org.openkilda.functionaltests.extension.tags.Tag
import org.openkilda.functionaltests.extension.tags.TagExtension

import groovy.util.logging.Slf4j
import org.spockframework.runtime.extension.AbstractGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.MethodKind
import org.spockframework.runtime.model.SpecInfo
import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware

/**
 * This extension is responsible for handling spring context-related things.
 * 
 * Runs special 'dummy' test before spec to ensure context init for tests with 'where' blocks.
 * Can accept listeners that will be provided with ApplicationContext as soon as it is accessible.
 */
@Slf4j
class SpringContextNotifier implements ApplicationContextAware {
    public static ApplicationContext context;
    private static List<SpringContextListener> listeners = []

    @Override
    void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        log.debug("Setting app spring context for spock extensions")
        context = applicationContext
        listeners.each {
            it.notifyContextInitialized(applicationContext)
        }
    }

    static void addListener(SpringContextListener listener) {
        listeners.add(listener)
        if(context) {
            listener.notifyContextInitialized(context)
        }
    }
}
