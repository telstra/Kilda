# Kilda performance testing

## Overview
This doc describes the approach and toolset for performance testing of OpenKilda.

## Goals
The goal is to introduce regular (and automated in the future) performance testing which helps to:
- Understand how the system performs under different load conditions.
- Avoid performance degradation.
- Identify bottlenecks and determine the application scalability.
- Develop an appropriate capacity planning process.
  
## What to measure
The following metrics related to application performance:
- Execution time & throughput of operations:
    - Create a flow
    - Reroute a flow
    - Dump flows
    - Get a flow path
    - Validate a flow
    - Dump switches / links
    - Validate a switch
- Execution time of resource-intensive or critical path code:
    - Path & resource allocation 
    - Storm - FL command round trip (send a command - receive an acknowledge)
- Error rates:
    - Unsuccessful reroutes
    - Unsuccessful persistence operations (and retries)
    - Failed flow operations
    - Failed FL commands

## How to measure
- Instrument the system code with different type of meters: timers, counters, gauges, etc.
- Collect and push metrics data to a time series database.
- Visualize the results and/or build a report.

## Environment
The persistence tests are to be run against non-production environments (development, staging):
- The network topology is created in the virtual lab supported by the current testing framework.
- The tests configure the virtual lab with pre-defined network topology and operate with reproducible flow paths.
- The tests define flows and their paths. 

## Persistence tests
The persistence tests are based on the current functional tests, but focus on a specific operation per test. 

## The solution
The described approach requires the following to be implemented: 
- Develop a factory witch creates reproducible network topology and flows in the virtual lab.  
- Ensure the virtual lab gives stable results (minimal deviation in the same test execution). 
- Introduce the application metrics with exporting them to OpenTSDB - [application metrics](application-metrics.md).
- Create a custom set of performance tests (based on functional tests).
