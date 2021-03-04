package org.openkilda.functionaltests.extension.spring

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Mark an empty dummy test to run before parameterized feature to ensure context setup. 
 * This is a dummy test which is ran as the first ever test to init Spring context.
 *
 * @see SpringContextNotifier
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface PrepareSpringContextDummy {}