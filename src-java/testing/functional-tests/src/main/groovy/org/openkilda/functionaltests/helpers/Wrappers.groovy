package org.openkilda.functionaltests.helpers

import groovy.transform.InheritConstructors
import groovy.util.logging.Slf4j

@Slf4j
class Wrappers {

    /**
     * Retries some operation for a given number of times. Retry is considered failed if any throwable is thrown.
     *
     * @param times number of retries
     * @param retryInterval pause between retries (in seconds)
     * @param handler exception handler (receives exception on each failure)
     * @param body operation to wrap around
     * @return result of the body execution
     * @throws Throwable if closure fails to execute in given amount of tries throw last caught throwable.
     */
    def static retry(int times = 5, double retryInterval = 2, Closure handler = { e -> log.debug("retry failed", e) },
                     Closure body) {
        int retries = 0

        Throwable thrown = null
        while (retries++ < times) {
            try {
                return body.call()
            } catch (Throwable t) {
                thrown = t
                handler.call(t)
                sleep(retryInterval * 1000 as long)
            }
        }
        throw thrown
    }

    /**
     * Executes given closure in a loop for certain amount of time.
     * If time is less or equal to 0, the closure won't be executed even once.
     * Loop may take a bit longer than 'timeout' by maximum of closure execution time, since check is performed
     * before the closure call.
     *
     * @param timeout For how long to execute the given closure in a loop, seconds.
     * @param closure code to repeat in a loop
     */
    static void timedLoop(double timeout, Closure closure) {
        long endTime = System.currentTimeMillis() + (long)(timeout * 1000)
        while (System.currentTimeMillis() < endTime) {
            closure.call()
        }
    }

    /**
     * Wait for closure to return a result.
     *
     * @param timeout time to wait in seconds
     * @param retryInterval pause between retries (in seconds)
     * @param closure : A closure that returns following:
     *          - Groovy truth or null if check is successful
     *          - False or throw if check is unsuccessful
     * @return True if wait was successful, throws WaitTimeoutException otherwise
     */
    static boolean wait(double timeout, double retryInterval = 0.5, Closure closure) {
        long endTime = System.currentTimeMillis() + (long) (timeout * 1000)
        long sleepTime = (long) (retryInterval * 1000)
        Throwable thrown = null
        while (System.currentTimeMillis() < endTime) {
            try {
                def result = closure.call()
                if (result || result == null) {
                    return true
                } else {
                    thrown = null
                    sleep(sleepTime)
                }
            } catch (Throwable t) {
                thrown = t
                sleep(sleepTime)
            }
        }
        throw new WaitTimeoutException("Condition was not satisfied within $timeout seconds", thrown)
    }

    /**
     * Debug only. Logs how much time certain code block took to execute
     *
     * @param name some name of closure/code block that is being executed
     * @param closure code block to benchmark
     * @return result of the given closure
     */
    static def benchmark(name, closure) {
        def start = System.currentTimeMillis()
        def result
        try {
            result = closure.call()
        } finally {
            def now = System.currentTimeMillis()
            log.debug("$name took " + (now - start) + "ms")
        }
        return result
    }

    /**
     * Execute some code silently. No exceptions will be thrown. Any exception will be only logged.
     * Useful for cleanup blocks where you're not allowed to throw.
     *
     * @param closure
     */
    static void silent(closure) {
        try {
            closure.call()
        } catch (Throwable t) {
            log.error("", t)
        }
    }

    @InheritConstructors
    static class WaitTimeoutException extends RuntimeException {
        Throwable originalError
        
        WaitTimeoutException(String message, Throwable error) {
            super(error ? message + "\nFailed with exception:\n\n$error" : message)
            originalError = error
        }
    }
}
