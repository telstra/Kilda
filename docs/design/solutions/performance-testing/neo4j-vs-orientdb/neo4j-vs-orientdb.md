# Kilda performance testing - Neo4j vs OrientDB persistence layer

## Testing environments
- **Neo4j 3.3.9** - single instance.
- **OrientDB 3.0.30** - clustered with 3 nodes. 


- **Storm 1.1.0** - 3 supervisor nodes.
- **Floodlight Modules** - 1 mgmt + 1 stats nodes.
- **Network topology** - a test lab with virtual (OVS) switches.

## Test cases
- **Create flows.** The spec sequentially creates 800 flows on a mesh topology of 100 switches.
- **Dump flows.** The spec sequentially dumps all flows. Execution time of each dump operation is measured.
- **Concurrent reroute flows.** The spec concurrently reroute 300 (100) flows on a mesh topology of 100 switches and 800 flows.

The tests are executed with different parallelism configuration of FlowHsTopology: 2, 10 and 50.

## Test results

### Create flows
|Storm Parallelism / Workers | Neo4j | OrientDB|
|---|---|---|
|2 (1 worker) | 2.5↗ sec/op ([...](neo4j_p2w1_flow_create_f800_s100_mimmax.png)) | 1.6 sec/op ([...](orientdb_p2w1_flow_create_f800_s100_mimmax.png))|
|10 (5 worker) | 2.5↗ sec/op ([...](neo4j_p10w5_flow_create_f800_s100_mimmax.png)) | 1.6 sec/op ([...](orientdb_p10w5_flow_create_f800_s100_mimmax.png))|
|50 (10 worker) | 2.5↗ sec/op ([...](neo4j_p50w10_flow_create_f800_s100_mimmax.png)) | 1.6 sec/op ([...](orientdb_p50w10_flow_create_f800_s100_mimmax.png))|
 
## Dump flows
|Storm Parallelism / Workers | Neo4j | OrientDB|
|---|---|---|
|2 (1 worker) | 0.2 sec/op ([...](neo4j_p2w1_flow_dump_f800_s100_mimmax.png)) | 0.07 sec/op ([...](orientdb_p2w1_flow_dump_f800_s100_mimmax.png))|
|10 (5 worker) | 0.2 sec/op ([...](neo4j_p10w5_flow_dump_f800_s100_mimmax.png)) | 0.07 sec/op ([...](orientdb_p10w5_flow_dump_f800_s100_mimmax.png))|
|50 (10 worker) | 0.2 sec/op ([...](neo4j_p50w10_flow_dump_f800_s100_mimmax.png)) | 0.07 sec/op ([...](orientdb_p50w10_flow_dump_f800_s100_mimmax.png))|

## Concurrent reroute 300 flows
|Storm Parallelism / Workers | Neo4j | OrientDB|
|---|---|---|
|2 (1 worker) | 9 min/all, 25 sec/op ([...](neo4j_p2w1_concurrent_reroute_of_300_flows_f800_s100_mimmax.png)) | 2.25 min/all, 4.5 sec/op ([...](orientdb_p2w1_concurrent_reroute_of_300_flows_f800_s100_mimmax.png))|
|10 (5 worker) | 6 min/all, 7 sec/op ([...](neo4j_p10w5_concurrent_reroute_of_300_flows_f800_s100_mimmax.png)) | 2.2 min/all, 3 sec/op ([...](orientdb_p10w5_concurrent_reroute_of_300_flows_f800_s100_mimmax.png))|
|50 (10 worker) | 3 min/all, 6 sec/op ([...](neo4j_p50w10_concurrent_reroute_of_300_flows_f800_s100_mimmax.png)) | 2.2 min/all, 2.7 sec/op ([...](orientdb_p50w10_concurrent_reroute_of_300_flows_f800_s100_mimmax.png))|

## Concurrent reroute 100 flows
|Storm Parallelism / Workers | Neo4j | OrientDB|
|---|---|---|
|2 (1 worker) | 2 min/all, 15 sec/op ([...](neo4j_p2w1_concurrent_reroute_of_100_flows_f800_s100_mimmax.png)) | 42 sec/all, 4 sec/op ([...](orientdb_p2w1_concurrent_reroute_of_100_flows_f800_s100_mimmax.png))|
|10 (5 worker) | 1.5 min/all, 7 sec/op ([...](neo4j_p10w5_concurrent_reroute_of_100_flows_f800_s100_mimmax.png)) | 50 sec/all, 3 sec/op ([...](orientdb_p10w5_concurrent_reroute_of_100_flows_f800_s100_mimmax.png))|
|50 (10 worker) | 1.1  min/all, 6 sec/op ([...](neo4j_p50w10_concurrent_reroute_of_100_flows_f800_s100_mimmax.png)) | 43 sec/all, 2.7 sec/op ([...](orientdb_p50w10_concurrent_reroute_of_100_flows_f800_s100_mimmax.png))|
