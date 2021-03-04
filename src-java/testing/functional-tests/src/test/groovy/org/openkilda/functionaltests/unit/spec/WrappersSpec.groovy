package org.openkilda.functionaltests.unit.spec

import org.openkilda.functionaltests.helpers.Wrappers
import org.openkilda.functionaltests.helpers.Wrappers.WaitTimeoutException

import spock.lang.Specification
import spock.lang.Unroll

@Unroll
class WrappersSpec extends Specification {

    def timeout = 0.01

    def "Wrappers.wait throws exception if condition fails"() {
        when: "Wait is fed with a false condition"
        Wrappers.wait(timeout, condition)

        then: "WaitTimeoutException is thrown"
        thrown(WaitTimeoutException)

        where:
        condition << [{ false }, { assert false }, { throw new Exception() }, { [] }]
    }

    def "Wrappers.wait returns true when condition passes"() {
        expect: "Wait returns true on OK conditions"
        Wrappers.wait(timeout, condition)

        where:
        condition << [{ true }, /*{ assert true },*/ { }, { new Object() }]
    }
}
