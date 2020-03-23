# Changelog

## v1.51.1 (17/03/2020)

### Bug Fixes:
-  [#3269](https://github.com/telstra/open-kilda/pull/3269) Fix the appearance of excess rules when flow update V2. (Issue: [#3266](https://github.com/telstra/open-kilda/issues/3266))
-  [#3271](https://github.com/telstra/open-kilda/pull/3271) Fixed PCE for diverse flow with not enough bandwidth ISL [**storm-topologies**]
-  [#3279](https://github.com/telstra/open-kilda/pull/3279) Fix NPE on response to unclosed FL session (Issue: [#3278](https://github.com/telstra/open-kilda/issues/3278)) [**floodlight**]
-  [#3280](https://github.com/telstra/open-kilda/pull/3280) Added configuration parameter `statistics.interval`. [**storm-topologies**]
-  [#3284](https://github.com/telstra/open-kilda/pull/3284) [Issue 3277] Fix V2 Flow Reroute after multiTable mode switching (Issue: [#3277](https://github.com/telstra/open-kilda/issues/3277))
-  [#3290](https://github.com/telstra/open-kilda/pull/3290) Do not ignore multiTable flags during RequestedFlow mapping [**storm-topologies**]
-  [#3186](https://github.com/telstra/open-kilda/pull/3186) Fixed resource allocation. (Issue: [#3047](https://github.com/telstra/open-kilda/issues/3047)) [**storm-topologies**]
-  [#3001](https://github.com/telstra/open-kilda/pull/3001) Fixed NB default password variable in NB confd template [**configuration**]
-  [#3258](https://github.com/telstra/open-kilda/pull/3258) Fixed switch sync during switch props update (Issue: [#3059](https://github.com/telstra/open-kilda/issues/3059)) [**storm-topologies**]

### Improvements:
-  [#3276](https://github.com/telstra/open-kilda/pull/3276) Clean up compile time warnings for kilda
-  [#3281](https://github.com/telstra/open-kilda/pull/3281) Minor adjustments in ConnectedDevicesSpec [**tests**]
-  [#3282](https://github.com/telstra/open-kilda/pull/3282) improve defaultFlowSpecs [**tests**]
-  [#3283](https://github.com/telstra/open-kilda/pull/3283) Fix call super hash codes and equals
-  [#3292](https://github.com/telstra/open-kilda/pull/3292) Add db indexes for flow history objects (Issue: [#3288](https://github.com/telstra/open-kilda/issues/3288))
-  [#3233](https://github.com/telstra/open-kilda/pull/3233) Remove unused code [**storm-topologies**]
-  [#3117](https://github.com/telstra/open-kilda/pull/3117) move verifyBurstSizeIsCorrect into switchHelper [**tests**]
-  [#3182](https://github.com/telstra/open-kilda/pull/3182) Add make target for creating virtual test topology [**tests**]

### Other changes:
-  [#2946](https://github.com/telstra/open-kilda/pull/2946) Updated migration steps for multi-table switch pipelines
-  [#3165](https://github.com/telstra/open-kilda/pull/3165) Rework unit-tests for flow H&S services [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.51.0...v1.51.1).

### Affected Components:
flow, nbworker, stats, router, fl, flow-hs

### Upgrade notes:
Consider using the following migration scripts to update db:

- [1.23 migration-script](https://github.com/telstra/open-kilda/blob/v1.51.1/services/src/neo4j/migrations/1.23-flow-history-indexes/1-add-flow-history-index.xml)

In case of issues these rollback scripts should be executed:

- [1.23 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.51.1/services/src/neo4j/migrations/1.23-flow-history-indexes/rollback.cql)

---

## v1.51.0 (11/03/2020)

### Features:
-  [#3203](https://github.com/telstra/open-kilda/pull/3203) ARP Part 6: Added shared rule (Issue: [#3118](https://github.com/telstra/open-kilda/issues/3118)) 
-  [#3142](https://github.com/telstra/open-kilda/pull/3142) ARP Part 4: Added ARP support into Floodlight (Issue: [#3118](https://github.com/telstra/open-kilda/issues/3118)) [**floodlight**]
-  [#3143](https://github.com/telstra/open-kilda/pull/3143) Enhancement/topology maintenance isl (Issue: [#3136](https://github.com/telstra/open-kilda/issues/3136)) [**gui**]
-  [#3144](https://github.com/telstra/open-kilda/pull/3144) ARP Part 5: Connected devices topology (Issue: [#3118](https://github.com/telstra/open-kilda/issues/3118)) [**floodlight**]
-  [#3217](https://github.com/telstra/open-kilda/pull/3217) Added ARP support to traff gens [**tests**]
-  [#3226](https://github.com/telstra/open-kilda/pull/3226) Add func tests for ARP connected devices [**tests**]
-  [#3177](https://github.com/telstra/open-kilda/pull/3177) Updated SwitchConnectedDevice db model 
-  [#3119](https://github.com/telstra/open-kilda/pull/3119) ARP Part 1: Add ARP connected devices models (Issue: [#3118](https://github.com/telstra/open-kilda/issues/3118)) [**floodlight**][**northbound**]
-  [#3124](https://github.com/telstra/open-kilda/pull/3124) ARP Part 2: Update Switch Properties (Issue: [#3118](https://github.com/telstra/open-kilda/issues/3118)) [**storm-topologies**]
-  [#3187](https://github.com/telstra/open-kilda/pull/3187) Feature to add and update isl BFD flag (Issues: [#2883](https://github.com/telstra/open-kilda/issues/2883) [#2884](https://github.com/telstra/open-kilda/issues/2884)) [**gui**]
-  [#3188](https://github.com/telstra/open-kilda/pull/3188) Add flow reroute retry design [**docs**]
-  [#3129](https://github.com/telstra/open-kilda/pull/3129) Added LLDP shared rule V2 removing and installation (Issue: [#3056](https://github.com/telstra/open-kilda/issues/3056)) 
-  [#3134](https://github.com/telstra/open-kilda/pull/3134) ARP Part 3: Switch rules (Issue: [#3118](https://github.com/telstra/open-kilda/issues/3118)) [**floodlight**][**storm-topologies**]

### Bug Fixes:
-  [#3272](https://github.com/telstra/open-kilda/pull/3272) Do not install ARP rules on WB switches [**floodlight**]
-  [#3209](https://github.com/telstra/open-kilda/pull/3209) Fix flow endpoints update via APIv2. (Issue: [#3049](https://github.com/telstra/open-kilda/issues/3049)) 
-  [#3016](https://github.com/telstra/open-kilda/pull/3016) Fix error message when switch not found (Issue: [#2906](https://github.com/telstra/open-kilda/issues/2906)) [**storm-topologies**]
-  [#3274](https://github.com/telstra/open-kilda/pull/3274) Added default value for detect connected devices in V2 API [**northbound**]
-  [#3224](https://github.com/telstra/open-kilda/pull/3224) Fixed display of `diverse_with` field in response via APIv2. (Issue: [#2701](https://github.com/telstra/open-kilda/issues/2701)) 
-  [#3243](https://github.com/telstra/open-kilda/pull/3243) Fix incorrect switch validation log message [**storm-topologies**]
-  [#3248](https://github.com/telstra/open-kilda/pull/3248) Fix data points duplicates for switch statistics (Issue: [#2801](https://github.com/telstra/open-kilda/issues/2801)) [**floodlight**]
-  [#3183](https://github.com/telstra/open-kilda/pull/3183) Issue 2885: Fixed getting Flows by Endpoint (Issue: [#2885](https://github.com/telstra/open-kilda/issues/2885)) [**storm-topologies**]
-  [#3259](https://github.com/telstra/open-kilda/pull/3259) Fixed creating/updating flow using the `max_latency` strategy. (Issue: [#3254](https://github.com/telstra/open-kilda/issues/3254)) [**storm-topologies**]

### Improvements:
-  [#3267](https://github.com/telstra/open-kilda/pull/3267) Fix MetersSpec to properly expect default meters in multitable mode [**tests**]
-  [#3141](https://github.com/telstra/open-kilda/pull/3141) LLDP cleanup: Remove unused methods, renamed SwitchLldpInfoData [**floodlight**][**storm-topologies**]
-  [#3082](https://github.com/telstra/open-kilda/pull/3082) Forbid to turn off multiTable property if there are flows with LLDP enabled [**storm-topologies**]
-  [#3041](https://github.com/telstra/open-kilda/pull/3041) Removed constraints and indexes for old flow connected devices model 
-  [#3235](https://github.com/telstra/open-kilda/pull/3235) Make FlowThrottlingBolt stateless for storm. [**storm-topologies**]
-  [#2665](https://github.com/telstra/open-kilda/pull/2665) Use multi-region floodlight for local build. Update lock-keeper to use iptables [**tests**]
-  [#3250](https://github.com/telstra/open-kilda/pull/3250) improve SwitchValidationSpec(wait for meter) [**tests**]
-  [#3251](https://github.com/telstra/open-kilda/pull/3251) Make packet loss test hw-only again [**tests**]


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.50.0...v1.51.0).

### Affected Components:
nb, connected, fl, stats-router, flow-hs, swmanager, reroute, flow, neo4j, nbworker

### Upgrade notes:

Consider using the following migration scripts to update db:

- [1.21 migration-script](https://github.com/telstra/open-kilda/blob/v1.51.0/services/neo4j/migrations/1.21-remove-flow-connected-devices/1-remove-flow-connected-devices-index-and-constraint.xml)
- [1.22 migration-script](https://github.com/telstra/open-kilda/blob/v1.51.0/services/neo4j/migrations/1.22-switch-connected-devices-arp-indexes/1-add-switch-connected-devices-arp-index.xml)

In case of issues these rollback scripts should be executed:

- [1.22 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.51.0/services/neo4j/migrations/1.22-switch-connected-devices-arp-indexes/rollback.cql)
- [1.21 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.51.0/services/neo4j/migrations/1.21-remove-flow-connected-devices/rollback.cql)

---

## v1.50.0 (04/03/2020)

### Features:
-  [#3072](https://github.com/telstra/open-kilda/pull/3072) Add default meters validation (Issues: [#2969](https://github.com/telstra/open-kilda/issues/2969) [#3152](https://github.com/telstra/open-kilda/issues/3152)) [**floodlight**][**storm-topologies**]
-  [#3149](https://github.com/telstra/open-kilda/pull/3149) Added a reroute call when updating the maxLatency flow field. [**storm-topologies**]
-  [#3135](https://github.com/telstra/open-kilda/pull/3135) Added MAX_LATENCY PCE strategy. 

### Bug Fixes:
-  [#3220](https://github.com/telstra/open-kilda/pull/3220) Added a filter in SwitchManagerTopology to check only the paths contained in flow. (Issue: [#3090](https://github.com/telstra/open-kilda/issues/3090)) [**storm-topologies**]

### Improvements:
-  [#3246](https://github.com/telstra/open-kilda/pull/3246) refactor tests according to 1.49 release [**tests**]
-  [#3221](https://github.com/telstra/open-kilda/pull/3221) improve MultitableFlowsSpec (Issue: [#3218](https://github.com/telstra/open-kilda/issues/3218)) [**tests**]
-  [#3092](https://github.com/telstra/open-kilda/pull/3092) Minor tweaks in tests according to default meters validation feature [**tests**]
-  [#3223](https://github.com/telstra/open-kilda/pull/3223) improve protectedPath specs [**tests**]


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.49.0...v1.50.0).

### Affected Components:
router, swmanager, fl, nbworker

---

## v1.49.0 (28/02/2020)

### Features:
-  [#3075](https://github.com/telstra/open-kilda/pull/3075) [Server 42] Design for Round Trip Ping rules [**docs**]
-  [#3121](https://github.com/telstra/open-kilda/pull/3121) Design for ARP connected devices on switch (Issue: [#3118](https://github.com/telstra/open-kilda/issues/3118)) [**docs**]
-  [#2876](https://github.com/telstra/open-kilda/pull/2876) Design for LLDP connected devices on switch (Issue: [#2917](https://github.com/telstra/open-kilda/issues/2917)) [**docs**]

### Bug Fixes:
-  [#3088](https://github.com/telstra/open-kilda/pull/3088) Fix the responses in swagger docs. (Issues: [#2382](https://github.com/telstra/open-kilda/issues/2382) [#2765](https://github.com/telstra/open-kilda/issues/2765)) [**northbound**]
-  [#3039](https://github.com/telstra/open-kilda/pull/3039) Fix MeterVerifyCommand to handle inaccurate meter bandwidth and burst (Issue: [#3027](https://github.com/telstra/open-kilda/issues/3027)) [**floodlight**]
-  [#3174](https://github.com/telstra/open-kilda/pull/3174) Correct handle empty affected isl set in reroute throttling [**storm-topologies**]
-  [#3055](https://github.com/telstra/open-kilda/pull/3055) Fixed issue in flow path stats graph  (Issue: [#3052](https://github.com/telstra/open-kilda/issues/3052)) [**gui**]
-  [#3069](https://github.com/telstra/open-kilda/pull/3069) Fixed flow ping for VXLAN flows [**floodlight**]

### Improvements:
-  [#3208](https://github.com/telstra/open-kilda/pull/3208) improve building procedure for kilda-base-lab-service image 
-  [#3084](https://github.com/telstra/open-kilda/pull/3084) Fixed unit test for multi table [**tests**]
-  [#3153](https://github.com/telstra/open-kilda/pull/3153) Get rid of flow wrappers in Ping topology. [**storm-topologies**]
-  [#3155](https://github.com/telstra/open-kilda/pull/3155) Added a status tag to the flow.latency metric in the Ping topology. [**storm-topologies**]
-  [#3030](https://github.com/telstra/open-kilda/pull/3030) Imrovement for gzip compression in UI and browser (Issue: [#3029](https://github.com/telstra/open-kilda/issues/3029)) [**gui**]
-  [#3160](https://github.com/telstra/open-kilda/pull/3160) Return empty list instead of null on dumpMeters request [**floodlight**]
-  [#3161](https://github.com/telstra/open-kilda/pull/3161) Get rid of the FlowPair wrapper. [**storm-topologies**]
-  [#3163](https://github.com/telstra/open-kilda/pull/3163) Get rid of UnidirectionalFlow wrapper. [**storm-topologies**]
-  [#3169](https://github.com/telstra/open-kilda/pull/3169) Move transit table_id to 5 [**floodlight**]
-  [#3112](https://github.com/telstra/open-kilda/pull/3112) improvement: generate topology with one-way link only [**tests**]
-  [#3191](https://github.com/telstra/open-kilda/pull/3191) Expose OF transaction id into speaker logs [**floodlight**]
-  [#3198](https://github.com/telstra/open-kilda/pull/3198) test improvements [**tests**]

### Other changes:
-  [#3211](https://github.com/telstra/open-kilda/pull/3211) Fix running Storm topology locally (in dev environment) 
-  [#3091](https://github.com/telstra/open-kilda/pull/3091) Now verify cleanup per feature rather than per spec [**tests**]
-  [#3222](https://github.com/telstra/open-kilda/pull/3222) Docker base image for Lab-Api bumped to Ubuntu Eoan 
-  [#3192](https://github.com/telstra/open-kilda/pull/3192) Fix running func-tests and perf-tests in IDEA. 
-  [#3190](https://github.com/telstra/open-kilda/pull/3190) Fix python3 dependency list [**docs**]
-  [#3193](https://github.com/telstra/open-kilda/pull/3193) Fix build.gradle - proper task reference in buildAndCopyArtifacts. 
-  [#3066](https://github.com/telstra/open-kilda/pull/3066) Fixed security vulnerability in kildagui [**gui**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.48.2...v1.49.0).

### Affected Components:
nb, fl, gui, flow, reroute, nbworker, ping

---

## v1.48.1 (24/02/2020)

### Bug Fixes:
-  [#3204](https://github.com/telstra/open-kilda/pull/3204) Downgrade Kafka and Spring dependencies to pre-restructuring versions

### Improvements:
-  [#3213](https://github.com/telstra/open-kilda/pull/3213) Extend detaild of swmanager log messages
-  [#3219](https://github.com/telstra/open-kilda/pull/3219) Expose processed kafka record reference
-  [#3225](https://github.com/telstra/open-kilda/pull/3225) Accept custom Floodlight and Loxigen Git repositoies
-  [#3229](https://github.com/telstra/open-kilda/pull/3229) Lower the log level for unhandled tuples in H&S Flow topology

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.48.0...v1.48.1).

### Affected Components:
nb, swmanager

---

## v1.48.0 (13/02/2020)

### Bug Fixes:
-  [#3181](https://github.com/telstra/open-kilda/pull/3181) Restore RollbacksSpec [**tests**]
-  [#3195](https://github.com/telstra/open-kilda/pull/3195) Fix elasticsearch image build 

### Improvements:
-  [#3178](https://github.com/telstra/open-kilda/pull/3178) reorganize-project-change-docker-context: Change docker build context… 
-  [#3180](https://github.com/telstra/open-kilda/pull/3180) Test/fixes and improvements rebase [**tests**]
-  [#3095](https://github.com/telstra/open-kilda/pull/3095) Reorganize the project (Issue: [#1137](https://github.com/telstra/open-kilda/issues/1137)) 

### Other changes:
-  [#3172](https://github.com/telstra/open-kilda/pull/3172) Add gradle compile params 
-  [#3189](https://github.com/telstra/open-kilda/pull/3189) Copy log config to lab-api container [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.47.2...v1.48.0).

---

## v1.47.2 (05/02/2020)

### Bug Fixes:
-  [#3162](https://github.com/telstra/open-kilda/pull/3162) Add reroute retries when transit rules can't be installed or verified (Issue: [#3128](https://github.com/telstra/open-kilda/issues/3128)) 
-  [#3156](https://github.com/telstra/open-kilda/pull/3156) Dump all switches to return visible switches [**floodlight**]
-  [#3132](https://github.com/telstra/open-kilda/pull/3132) Add test for #3128 (Issue: [#3128](https://github.com/telstra/open-kilda/issues/3128)) [**tests**]
-  [#3167](https://github.com/telstra/open-kilda/pull/3167) Fix reroute request filling in Flow H&S topology. (Issue: [#3128](https://github.com/telstra/open-kilda/issues/3128)) 


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.47.1...v1.47.2).

### Affected Components:
fl, flow-hs

---

## v1.47.1 (03/02/2020)

### Bug Fixes:
-  [#3158](https://github.com/telstra/open-kilda/pull/3158) Skip corrupted flow while doing periodic pings invalidation. [**storm-topologies**]
-  [#3133](https://github.com/telstra/open-kilda/pull/3133) Reroute affected flows on switch up event. (Issue: [#3131](https://github.com/telstra/open-kilda/issues/3131)) [**storm-topologies**]
-  [#3159](https://github.com/telstra/open-kilda/pull/3159) Fixed incorrect log message in case of v2 FlowDelete. [**storm-topologies**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.47.0...v1.47.1).

### Affected Components:
ping, reroute, network

---

## v1.47.0 (29/01/2020)

### Bug Fixes:
-  [#3148](https://github.com/telstra/open-kilda/pull/3148) Periodic pings perf [**northbound**][**storm-topologies**]
-  [#3006](https://github.com/telstra/open-kilda/pull/3006) Fix periodic pings (Issue: [#2873](https://github.com/telstra/open-kilda/issues/2873)) [**storm-topologies**]

### Improvements:
-  [#3138](https://github.com/telstra/open-kilda/pull/3138) Introduce PoP for the switch and take it into account in pce [**pce**] 

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v.1.46.0...v1.47.0).

### Affected Components:
ping, nbworker, flow, nb, flow-hs

---

## v1.46.0 (24/01/2020)

### Features:
-  [#3022](https://github.com/telstra/open-kilda/pull/3022) Server42 Control application initial commit (Issues: [#1137](https://github.com/telstra/open-kilda/issues/1137) [#2998](https://github.com/telstra/open-kilda/issues/2998))
-  [#3024](https://github.com/telstra/open-kilda/pull/3024) Feature: LLDP on switch (Issue: [#2917](https://github.com/telstra/open-kilda/issues/2917)) [**floodlight**][**northbound**][**storm-topologies**]
-  [#2911](https://github.com/telstra/open-kilda/pull/2911) Switch LLDP 4: Added flow rules (Issues: [#2779](https://github.com/telstra/open-kilda/issues/2779) [#2827](https://github.com/telstra/open-kilda/issues/2827) [#2876](https://github.com/telstra/open-kilda/issues/2876) [#2917](https://github.com/telstra/open-kilda/issues/2917) [#2927](https://github.com/telstra/open-kilda/issues/2927)) [**floodlight**][**storm-topologies**]
-  [#2914](https://github.com/telstra/open-kilda/pull/2914) Switch LLDP 3: catching on ISL ports (Issues: [#2917](https://github.com/telstra/open-kilda/issues/2917) [#2918](https://github.com/telstra/open-kilda/issues/2918)) [**floodlight**]
-  [#2916](https://github.com/telstra/open-kilda/pull/2916) Switch LLDP 1: Isl rules (Issue: [#2917](https://github.com/telstra/open-kilda/issues/2917)) [**floodlight**][**northbound**]
-  [#2918](https://github.com/telstra/open-kilda/pull/2918) Switch LLDP 2: switch connected device models (Issues: [#2916](https://github.com/telstra/open-kilda/issues/2916) [#2917](https://github.com/telstra/open-kilda/issues/2917))

### Bug Fixes:
-  [#3014](https://github.com/telstra/open-kilda/pull/3014) Fix NullPointerException [**storm-topologies**]
-  [#2952](https://github.com/telstra/open-kilda/pull/2952) Fix validation of encapsulation_type in v2 FlowUpdate API (Issue: [#2937](https://github.com/telstra/open-kilda/issues/2937)) [**northbound**][**storm-topologies**]
-  [#3025](https://github.com/telstra/open-kilda/pull/3025) Fix removing customer port rule when flow H&S delete. (Issue: [#2971](https://github.com/telstra/open-kilda/issues/2971)) [**floodlight**]
-  [#2937](https://github.com/telstra/open-kilda/pull/2937) Handle invalid encapsulation_type in v2 FlowCreate API (Issues: [#2650](https://github.com/telstra/open-kilda/issues/2650) [#2952](https://github.com/telstra/open-kilda/issues/2952)) [**northbound**][**storm-topologies**]
-  [#3002](https://github.com/telstra/open-kilda/pull/3002) Fix error message when switch not exist (Issue: [#2905](https://github.com/telstra/open-kilda/issues/2905)) [**storm-topologies**]
-  [#3067](https://github.com/telstra/open-kilda/pull/3067) Fix fails of PacketServiceTest [**tests**]
-  [#3068](https://github.com/telstra/open-kilda/pull/3068) Fixed missing @Ignore import [**tests**]

### Improvements:
-  [#2886](https://github.com/telstra/open-kilda/pull/2886) refactor tets with getSwithcFlow to cover #2885 (Issue: [#2885](https://github.com/telstra/open-kilda/issues/2885)) [**tests**]
-  [#3023](https://github.com/telstra/open-kilda/pull/3023) Extend log message for missing rules [**floodlight**]
-  [#3026](https://github.com/telstra/open-kilda/pull/3026) minor improvements in tests [**tests**]
-  [#2986](https://github.com/telstra/open-kilda/pull/2986) Add test for devices interaction with default flow [**tests**]
-  [#3054](https://github.com/telstra/open-kilda/pull/3054) add tests for 3049 issue (Issue: [#3049](https://github.com/telstra/open-kilda/issues/3049)) [**tests**]
-  [#2927](https://github.com/telstra/open-kilda/pull/2927) Add func tests for detecting lldp connected devices per-switch (Issue: [#2914](https://github.com/telstra/open-kilda/issues/2914)) [**tests**]
-  [#3058](https://github.com/telstra/open-kilda/pull/3058) add test "Flow ping can detect a broken path for a vxlan flow on an intermediate switch" (Issue: [#3069](https://github.com/telstra/open-kilda/issues/3069)) [**tests**]
-  [#3060](https://github.com/telstra/open-kilda/pull/3060) ignore tests according to #3059 (Issue: [#3059](https://github.com/telstra/open-kilda/issues/3059)) [**tests**]
-  [#3061](https://github.com/telstra/open-kilda/pull/3061) delete FlowPriorityRerouteSpec [**tests**]
-  [#3063](https://github.com/telstra/open-kilda/pull/3063) improve flowHistorySpec, covers: 3031,3038 (Issues: [#3031](https://github.com/telstra/open-kilda/issues/3031) [#3038](https://github.com/telstra/open-kilda/issues/3038)) [**tests**]

### Other changes:
-  [#2951](https://github.com/telstra/open-kilda/pull/2951) Limit number of tables for ovs switches [**floodlight**]
-  [#3017](https://github.com/telstra/open-kilda/pull/3017) Ignore port history tests due to #3007 [**tests**]
-  [#3018](https://github.com/telstra/open-kilda/pull/3018) Add comment that single-switch flow pings are actually useless [**tests**]
-  [#3042](https://github.com/telstra/open-kilda/pull/3042) Minor fixes in functional tests [**tests**]
-  [#3057](https://github.com/telstra/open-kilda/pull/3057) Add more stats verifications to existing tests [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.45.2...v1.46.0).

### Affected Components:
router, nb, neo4j, flow-hs, nbworker, flow, fl, ping, connected

### Upgrade notes:

Consider using the following migration scripts to update db:

- [1.19 migration-script](https://github.com/telstra/open-kilda/blob/v1.46.0/services/neo4j/migrations/1.19-switch-lldp-property/1-set-switch-lldp-switchproperty-flag.xml)
- [1.20 migration-script](https://github.com/telstra/open-kilda/blob/v1.46.0/services/neo4j/migrations/1.20-switch-connected-devices/1-add-switch-connected-devices-index-and-constraint.xml)

In case of issues these rollback scripts should be executed:

- [1.20 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.46.0/services/neo4j/migrations/1.20-switch-connected-devices/rollback.cql)
- [1.19 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.46.0/services/neo4j/migrations/1.19-switch-lldp-property/rollback.cql)

---

## v1.45.2 (13/01/2020)

### Bug Fixes:
-  [#3107](https://github.com/telstra/open-kilda/pull/3107) Avoid reseting in progress flow status [**storm-topologies**]
-  [#3108](https://github.com/telstra/open-kilda/pull/3108) Fix double network failure handling [**northbound**][**storm-topologies**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.45.1...v1.45.2).

### Affected Components:
nb, reroute, flow-hs

---

## v1.45.1 (30/12/2019)

### Bug Fixes:
-  [#2785](https://github.com/telstra/open-kilda/pull/2785) Reroute topology updates flow status for flows (Issue: [#2781](https://github.com/telstra/open-kilda/issues/2781)) [**storm-topologies**]
-  [#3086](https://github.com/telstra/open-kilda/pull/3086) Decrease parallelism for reply kafka spouts in flr [**storm-topologies**]
-  [#3062](https://github.com/telstra/open-kilda/pull/3062) Fix rollback in flow reroute [**storm-topologies**]


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.45.0...v1.45.1).

### Affected Components:
flow-hs, router, reroute

---

## v1.45.0 (10/12/2019)

### Features:
-  [#3013](https://github.com/telstra/open-kilda/pull/3013) Feature/switch delete [**gui**]
-  [#2910](https://github.com/telstra/open-kilda/pull/2910) Rework PCE (Issue: [#2894](https://github.com/telstra/open-kilda/issues/2894)) [**docs**][**northbound**][**storm-topologies**]

### Bug Fixes:
-  [#3012](https://github.com/telstra/open-kilda/pull/3012) Change goToTable instruction for customer multi table flow (Issue: [#3010](https://github.com/telstra/open-kilda/issues/3010)) [**floodlight**]
-  [#2966](https://github.com/telstra/open-kilda/pull/2966) Fix northbound logging after update log4j to 2.11.0 [**storm-topologies**]
-  [#3020](https://github.com/telstra/open-kilda/pull/3020) Add DB migration for reworked PCE (Issue: [#2894](https://github.com/telstra/open-kilda/issues/2894)) 

### Improvements:
-  [#2985](https://github.com/telstra/open-kilda/pull/2985) Add cleanup verifier to check basic factors of a clean env [**tests**]
-  [#2990](https://github.com/telstra/open-kilda/pull/2990) Refactor IslCostSpec to test 'unstable isl' behavior [**tests**]
-  [#2958](https://github.com/telstra/open-kilda/pull/2958) add test for flows_reroute_using_default_encap_type feature toogle (Issue: [#2955](https://github.com/telstra/open-kilda/issues/2955)) [**tests**]
-  [#2926](https://github.com/telstra/open-kilda/pull/2926) Fix performance degradation in FL Kafka Producer [**floodlight**]
-  [#2992](https://github.com/telstra/open-kilda/pull/2992) Refactor multi-reroute spec to work with bigger amount of flows [**tests**]
-  [#2994](https://github.com/telstra/open-kilda/pull/2994) Update all func tests to use v2 API wherever possible (Issue: [#2921](https://github.com/telstra/open-kilda/issues/2921)) [**tests**]
-  [#2961](https://github.com/telstra/open-kilda/pull/2961) update See annotation for syncSwitch spec [**tests**]
-  [#2999](https://github.com/telstra/open-kilda/pull/2999) Remove outdated/unused config option floodlight.request.timeout 
-  [#2997](https://github.com/telstra/open-kilda/pull/2997) ignore fucn tests according to bugs [**tests**]
-  [#3004](https://github.com/telstra/open-kilda/pull/3004) Revise flow priority reroute test with respect to h&s [**tests**]


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.44.1...v1.45.0).

### Affected Components:
flow-hs, fl, reroute, gui, flow, nbworker, nb

### Upgrade notes:
Consider using the following migration scripts to update db:

- [1.18 migration-script](https://github.com/telstra/open-kilda/blob/v1.45.0/services/neo4j/migrations/1.18-path-computation-strategy/1-path-computation-strategy.xml)

In case of issues these rollback scripts should be executed:

- [1.18 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.45.0/services/neo4j/migrations/1.18-path-computation-strategy/rollback.cql)

---

## v1.44.1 (09/12/2019)

### Bug Fixes:
-  [#3005](https://github.com/telstra/open-kilda/pull/3005) Fix flow segment validation relaxing set field action match [**floodlight**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.44.0...v1.44.1).

### Affected Components:
fl

---

## v1.44.0 (04/12/2019)

### Features:
-  [#2818](https://github.com/telstra/open-kilda/pull/2818) Add Server 42 Design [**api**][**docs**]
-  [#2900](https://github.com/telstra/open-kilda/pull/2900) add tests for multiTable feature [**tests**]

### Bug Fixes:
-  [#2963](https://github.com/telstra/open-kilda/pull/2963) Fix for single switch flow (Issue: [#2947](https://github.com/telstra/open-kilda/issues/2947)) [**storm-topologies**]
-  [#2964](https://github.com/telstra/open-kilda/pull/2964) Fix for parse error in switch props update (Issue: [#2957](https://github.com/telstra/open-kilda/issues/2957)) [**storm-topologies**]
-  [#2991](https://github.com/telstra/open-kilda/pull/2991) Fix flow segment verify issue on OF1.2 switches [**floodlight**]
-  [#2993](https://github.com/telstra/open-kilda/pull/2993) Fix false-negative meter's validation [**floodlight**]

### Improvements:
-  [#2945](https://github.com/telstra/open-kilda/pull/2945) Valdate Switch Props against supported features (Issues: [#2932](https://github.com/telstra/open-kilda/issues/2932) [#2941](https://github.com/telstra/open-kilda/issues/2941)) [**storm-topologies**]
-  [#2826](https://github.com/telstra/open-kilda/pull/2826) Extend local execution time for storm topologies [**storm-topologies**]
-  [#2774](https://github.com/telstra/open-kilda/pull/2774) Extend speaker commands [**floodlight**]
-  [#2967](https://github.com/telstra/open-kilda/pull/2967) Improve error handling and logging in H&S FSMs 
-  [#2835](https://github.com/telstra/open-kilda/pull/2835) Update some configuration for local setup to speed up test execution [**configuration**][**docs**][**tests**]
-  [#2965](https://github.com/telstra/open-kilda/pull/2965) Minor stability tweaks in functional tests [**tests**]
-  [#2973](https://github.com/telstra/open-kilda/pull/2973) Minor stability fixes in functional tests [**tests**]
-  [#2978](https://github.com/telstra/open-kilda/pull/2978) Fix stability of port history test [**tests**]
-  [#2984](https://github.com/telstra/open-kilda/pull/2984) Ignore test that fails due to #2983 [**tests**]

### Other changes:
-  [#2970](https://github.com/telstra/open-kilda/pull/2970) Minor test updates related to V2 migration [**tests**]
-  [#2979](https://github.com/telstra/open-kilda/pull/2979) Ignore port stats test as being unstable [**tests**]
-  [#2929](https://github.com/telstra/open-kilda/pull/2929) Feature/flow diversity and network path (Issues: [#2283](https://github.com/telstra/open-kilda/issues/2283) [#2371](https://github.com/telstra/open-kilda/issues/2371) [#2373](https://github.com/telstra/open-kilda/issues/2373)) [**gui**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.43.1...v1.44.0).

### Affected Components:
flow-hs, fl, swmanager, nbworker

---

## v1.43.1 (03/12/2019)

### Bug Fixes:
-  [#2974](https://github.com/telstra/open-kilda/pull/2974) Handles reroute failures caused by over-provisioning  (Issue: [#2925](https://github.com/telstra/open-kilda/issues/2925)) 

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.43.0...v1.43.1).

### Affected Components:
flow-hs

---

## v1.43.0 (27/11/2019)

### Features:
-  [#2892](https://github.com/telstra/open-kilda/pull/2892) Implement H&S update (Issue: [#2869](https://github.com/telstra/open-kilda/issues/2869)) [**storm-topologies**]
-  [#2869](https://github.com/telstra/open-kilda/pull/2869) Implement H&S delete [**storm-topologies**]

### Bug Fixes:
-  [#2923](https://github.com/telstra/open-kilda/pull/2923) PCE bug fix (Issue: [#2904](https://github.com/telstra/open-kilda/issues/2904)) [**storm-topologies**]
-  [#2931](https://github.com/telstra/open-kilda/pull/2931) Fix stability of SwitchPropertiesSpec [**tests**]
-  [#2936](https://github.com/telstra/open-kilda/pull/2936) Improve switch properties validation (Issue: [#2889](https://github.com/telstra/open-kilda/issues/2889)) [**northbound**][**storm-topologies**]

### Improvements:
-  [#2944](https://github.com/telstra/open-kilda/pull/2944) Allow some tests to be marked as those with perfect cleanup (Issue: [#2943](https://github.com/telstra/open-kilda/issues/2943)) [**tests**]
-  [#2950](https://github.com/telstra/open-kilda/pull/2950) minor improve in flowCrud specs [**tests**]
-  [#2953](https://github.com/telstra/open-kilda/pull/2953) improve swapEndpointSpec [**tests**]
-  [#2959](https://github.com/telstra/open-kilda/pull/2959) Added envs for regions and roles for FL log files in JSON [**configuration**]
-  [#2903](https://github.com/telstra/open-kilda/pull/2903) check that system doesn't ignore encapsulationType when ignoreBandwidth=true [**tests**]
-  [#2909](https://github.com/telstra/open-kilda/pull/2909) FloodlightRouter parallelism tune [**storm-topologies**]
-  [#2920](https://github.com/telstra/open-kilda/pull/2920) Mark v1 specs that have v2 alternative as low priority [**tests**]
-  [#2872](https://github.com/telstra/open-kilda/pull/2872) improve portHistoryspec [**tests**]
-  [#2940](https://github.com/telstra/open-kilda/pull/2940) improve checking of the lastUpdated field [**tests**]
-  [#2942](https://github.com/telstra/open-kilda/pull/2942) Define disruptor configuration for local environment [**configuration**][**storm-topologies**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.42.1...v1.43.0).

### Affected Components:
swmanager, reroute, flow-hs, ping, connected, nbworker, router, nb, flow, network

---

## v1.42.1 (21/11/2019)

### Improvements:
-  [#2939](https://github.com/telstra/open-kilda/pull/2939) Temporary disable changing of `enable_bfd` flag [**northbound**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.42.0...v1.42.1).

### Affected Components:
nb

---

## v1.42.0 (19/11/2019)

### Features:
-  [#2843](https://github.com/telstra/open-kilda/pull/2843) Isl rules for switch [**tests**] 

### Improvements:
-  [#2930](https://github.com/telstra/open-kilda/pull/2930) improve SwitchPropertiesSpec [**tests**]

### Other changes:
-  [#2913](https://github.com/telstra/open-kilda/pull/2913) Adding improvement in topology  screen to icon menu (Issue: [#2912](https://github.com/telstra/open-kilda/issues/2912)) [**gui**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.41.2...v1.42.0).

### Affected Components:
gui, neo4j

### Upgrade notes:

Consider using the following migration scripts to update db:

- [1.17 migration-script.xml](https://github.com/telstra/open-kilda/blob/develop/services/neo4j/migrations/1.17-config-multi-table/1-config-add-multi-table-flag.xml)

In case of issues these rollback scripts should be executed:

- [1.17 rollback.cql](https://github.com/telstra/open-kilda/blob/develop/services/neo4j/migrations/1.17-config-multi-table/rollback.cql)

---

## v1.41.2 (14/11/2019)

### Bug Fixes:
-  [#2919](https://github.com/telstra/open-kilda/pull/2919) Fix flow ping ethernet header [**floodlight**]

### Improvements:
-  [#2915](https://github.com/telstra/open-kilda/pull/2915) Make http async timeout for NB configurable [**northbound**]
-  [#2922](https://github.com/telstra/open-kilda/pull/2922) Fix OOM in Neo4jPersistenceManager caused by ClassGraph [**storm-topologies**]
-  [#2896](https://github.com/telstra/open-kilda/pull/2896) Add stability hotfixes and temporary ignore some tests [**tests**]
-  [#2899](https://github.com/telstra/open-kilda/pull/2899) Fix/security issues lodash [**gui**]
-  [#2902](https://github.com/telstra/open-kilda/pull/2902) Renamed org.openkilda.converter package [**floodlight**]

### Other changes:
-  [#2895](https://github.com/telstra/open-kilda/pull/2895) Update design doc for PCE (Issue: [#2894](https://github.com/telstra/open-kilda/issues/2894)) [**docs**]
-  [#2870](https://github.com/telstra/open-kilda/pull/2870) Remove outdated code [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.41.1...v1.41.2).

### Affected Components:
nb, nbworker, swmanager, fl, flow-hs, network, reroute, flow, gui

---

## v1.41.1 (07/11/2019)

### Bug Fixes:
-  [#2908](https://github.com/telstra/open-kilda/pull/2908) Fix incorrect output port for single-switch-single-port flows [**floodlight**]

### Affected Components:
fl

---

## v1.41.0 (06/11/2019)
 
### Features:
-  [#2243](https://github.com/telstra/open-kilda/pull/2243) Extend flow validation with meter validation. (Issue: [#1249](https://github.com/telstra/open-kilda/issues/1249)) [**floodlight**][**storm-topologies**]
-  [#2845](https://github.com/telstra/open-kilda/pull/2845) add tests for v1/config API [**tests**]

### Bug Fixes:
-  [#2887](https://github.com/telstra/open-kilda/pull/2887) Added noviflow virtual switch checks to FeatureDetectorService [**floodlight**]
-  [#2898](https://github.com/telstra/open-kilda/pull/2898) Fix flow validation for Centec and E switches. [**storm-topologies**]
-  [#2901](https://github.com/telstra/open-kilda/pull/2901) Fix flow validation for Accton switches. [**floodlight**][**storm-topologies**]

### Improvements:
-  [#2880](https://github.com/telstra/open-kilda/pull/2880) improve checks for installed rules in vxlanFlowSpec [**tests**]
-  [#2854](https://github.com/telstra/open-kilda/pull/2854) refactor "System takes isl time_unstable info into account while creating a flow" [**tests**]
-  [#2663](https://github.com/telstra/open-kilda/pull/2663) Log message if ISL has negative cost (Issue: [#2319](https://github.com/telstra/open-kilda/issues/2319)) [**storm-topologies**]
-  [#2891](https://github.com/telstra/open-kilda/pull/2891) Increase PortHistorySpec stability [**tests**]
-  [#2482](https://github.com/telstra/open-kilda/pull/2482) Move flow validation to Nbworker topology. (Issue: [#1442](https://github.com/telstra/open-kilda/issues/1442)) [**floodlight**][**northbound**][**storm-topologies**]
-  [#2680](https://github.com/telstra/open-kilda/pull/2680) Extend network topology dashboard logger (Issue: [#2659](https://github.com/telstra/open-kilda/issues/2659)) [**floodlight**][**storm-topologies**]
-  [#2299](https://github.com/telstra/open-kilda/pull/2299) Make meter modify logic using the H&S approach. (Issue: [#2298](https://github.com/telstra/open-kilda/issues/2298)) [**floodlight**][**storm-topologies**]
-  [#2877](https://github.com/telstra/open-kilda/pull/2877) Fix minor sonar issues [**floodlight**][**storm-topologies**]
-  [#2846](https://github.com/telstra/open-kilda/pull/2846) add test System does not create a flow when bandwidth is not the same on the ISL [**tests**]


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.40.0...v1.41.0).

### Affected Components:
flow, nbworker, nb, network, fl, flow-hs

---

## v1.40.0 (28/10/2019)

### Features:
-  [#2867](https://github.com/telstra/open-kilda/pull/2867) Feature/switch flows (Issue: [#2768](https://github.com/telstra/open-kilda/issues/2768)) [**gui**]

### Bug Fixes:
-  [#2871](https://github.com/telstra/open-kilda/pull/2871) Fixed BFD feature detector [**floodlight**]
-  [#2875](https://github.com/telstra/open-kilda/pull/2875) Fix db migrations versioning 

### Improvements:
-  [#2848](https://github.com/telstra/open-kilda/pull/2848) add narrative annotation into SwitchPropertiesSpec [**tests**]
-  [#2853](https://github.com/telstra/open-kilda/pull/2853) Fix exception handling in northbound worker (Issue: [#2847](https://github.com/telstra/open-kilda/issues/2847)) [**storm-topologies**]
-  [#2863](https://github.com/telstra/open-kilda/pull/2863) minor fix in FlowCrudV2Spec [**tests**]
-  [#2864](https://github.com/telstra/open-kilda/pull/2864) Update jackson lib version [**storm-topologies**][**tests**]
-  [#2866](https://github.com/telstra/open-kilda/pull/2866) minor changes in vxlanFlow v1/v2 specs [**tests**]

### Other changes:
-  [#2699](https://github.com/telstra/open-kilda/pull/2699) Multitable switch isl lcm [**northbound**][**storm-topologies**]
-  [#2862](https://github.com/telstra/open-kilda/pull/2862) Fix major sonar issues [**floodlight**][**northbound**][**storm-topologies**][**tests**]
-  [#2868](https://github.com/telstra/open-kilda/pull/2868) Bumped versions of hbase, kafka, opentsdb, storm and zookeeper [**storm-topologies**]
-  [#2879](https://github.com/telstra/open-kilda/pull/2879) Revert "Bumped versions of hbase, kafka, opentsdb, storm and zookeeper" (Issue: [#2868](https://github.com/telstra/open-kilda/issues/2868)) 

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.39.1...v1.40.0).

### Affected Components:
ping, flow-hs, flow, neo4j, nb, swmanager, nbworker, fl

### Upgrade notes:

Related to [#2699](https://github.com/telstra/open-kilda/pull/2699)

Also please consider using following migration scripts to update db:
- [1.16 migration-script.xml](https://github.com/telstra/open-kilda/blob/v1.40.0/services/neo4j/migrations/1.16-multi-table-flag/1-add-multi-table-flag.xml)

In case of issues these rollback scripts should be executed:
- [1.16 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.40.0/services/neo4j/migrations/1.16-multi-table-flag/rollback.cql)

---

## v1.39.1 (17/10/2019)

### Bug Fixes:
-  [#2850](https://github.com/telstra/open-kilda/pull/2850) Replaced oraclejdk8 to openjdk8 [**tests**]

### Improvements:
-  [#2664](https://github.com/telstra/open-kilda/pull/2664) Create SimpleSwitchRule class for switch rules validation. (Issue: [#1442](https://github.com/telstra/open-kilda/issues/1442)) [**storm-topologies**]
-  [#2849](https://github.com/telstra/open-kilda/pull/2849) Improvement/controller filter default (Issues: [#2787](https://github.com/telstra/open-kilda/issues/2787) [#2803](https://github.com/telstra/open-kilda/issues/2803)) [**gui**]
-  [#2841](https://github.com/telstra/open-kilda/pull/2841) Allow to use traffexam on python-3.5.2 [**tests**]


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.39.0...v1.39.1).

### Affected Components:
gui

---

## v1.39.0 (07/10/2019)

### Features:
-  [#2693](https://github.com/telstra/open-kilda/pull/2693) Added multitable support into floodlight logic [**floodlight**][**storm-topologies**]
-  [#2745](https://github.com/telstra/open-kilda/pull/2745) Add switch connection port info. Fix #2681 (Issue: [#2681](https://github.com/telstra/open-kilda/issues/2681)) [**northbound**][**storm-topologies**]
-  [#2812](https://github.com/telstra/open-kilda/pull/2812) Port history antiflap stats (Issue: [#2718](https://github.com/telstra/open-kilda/issues/2718)) [**storm-topologies**]
-  [#2814](https://github.com/telstra/open-kilda/pull/2814) Add disable port discovery feature (Issue: [#2794](https://github.com/telstra/open-kilda/issues/2794)) [**northbound**][**storm-topologies**]

### Bug Fixes:
-  [#2830](https://github.com/telstra/open-kilda/pull/2830) Fixed different timeFirstSeen and TimeLastSeen for Connected Devices 
-  [#2836](https://github.com/telstra/open-kilda/pull/2836) Delete switch properties when switch is deleted [**storm-topologies**]
-  [#2840](https://github.com/telstra/open-kilda/pull/2840) Fix log message in LinkOperationService. [**storm-topologies**]
-  [#2842](https://github.com/telstra/open-kilda/pull/2842) Fix kafka test config [**tests**]

### Improvements:
-  [#2624](https://github.com/telstra/open-kilda/pull/2624) OF cookie management cleanup [**floodlight**][**storm-topologies**]
-  [#2823](https://github.com/telstra/open-kilda/pull/2823) Add notice for autogenerated files 
-  [#2831](https://github.com/telstra/open-kilda/pull/2831) Tag more low-value tests as LOW_PRIORITY [**tests**]
-  [#2832](https://github.com/telstra/open-kilda/pull/2832) Removed migration 1.13 artifact 
-  [#2833](https://github.com/telstra/open-kilda/pull/2833) improve waiting in MflStatSpec [**tests**]
-  [#2767](https://github.com/telstra/open-kilda/pull/2767) extend statistic test coverage for  different type of flow [**tests**]
-  [#2837](https://github.com/telstra/open-kilda/pull/2837) Moved initialization of connectedDevicesService to setup method [**floodlight**]
-  [#2838](https://github.com/telstra/open-kilda/pull/2838) Add perf test for verifying switch validation with a lot of flows [**tests**]
-  [#2658](https://github.com/telstra/open-kilda/pull/2658) Added helper code to move flow validation. (Issue: [#1442](https://github.com/telstra/open-kilda/issues/1442)) [**floodlight**][**storm-topologies**]
-  [#2796](https://github.com/telstra/open-kilda/pull/2796) add flowHistory test for v2 [**tests**]
-  [#2798](https://github.com/telstra/open-kilda/pull/2798) Extend Endurance test with a 'break isl' event [**tests**]
-  [#2799](https://github.com/telstra/open-kilda/pull/2799) Disable port discovery feature design (Issue: [#2794](https://github.com/telstra/open-kilda/issues/2794)) [**docs**]


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.38.0...v1.39.0).

### Affected Components:
flow, flow-hs, network, router, fl, stats, neo4j, connected, nb, nbworker, swmanager

### Upgrade notes:

Related to [#2814](https://github.com/telstra/open-kilda/pull/2814)

Also please consider using following migration scripts to update db:
- [1.15 migration-script.xml](https://github.com/telstra/open-kilda/blob/v1.39.0/services/neo4j/migrations/1.15-port-properties/1-update-constraints.xml)

In case of issues these rollback scripts should be executed:
- [1.15 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.39.0/services/neo4j/migrations/1.15-port-properties/rollback.cql)

---

## v1.38.0 (30/09/2019)

### Features:
-  [#2693](https://github.com/telstra/open-kilda/pull/2693) Added multitable support into floodlight logic [**floodlight**][**storm-topologies**]
-  [#2745](https://github.com/telstra/open-kilda/pull/2745) Add switch connection port info. Fix #2681 (Issue: [#2681](https://github.com/telstra/open-kilda/issues/2681)) [**northbound**][**storm-topologies**]
-  [#2812](https://github.com/telstra/open-kilda/pull/2812) Port history antiflap stats (Issue: [#2718](https://github.com/telstra/open-kilda/issues/2718)) [**storm-topologies**]

### Bug Fixes:
-  [#2830](https://github.com/telstra/open-kilda/pull/2830) Fixed different timeFirstSeen and TimeLastSeen for Connected Devices

### Improvements:
-  [#2624](https://github.com/telstra/open-kilda/pull/2624) OF cookie management cleanup [**floodlight**][**storm-topologies**]
-  [#2796](https://github.com/telstra/open-kilda/pull/2796) add flowHistory test for v2 [**tests**]
-  [#2831](https://github.com/telstra/open-kilda/pull/2831) Tag more low-value tests as LOW_PRIORITY [**tests**]
-  [#2832](https://github.com/telstra/open-kilda/pull/2832) Removed migration 1.13 artifact
-  [#2767](https://github.com/telstra/open-kilda/pull/2767) extend statistic test coverage for  different type of flow [**tests**]


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.37.0...v1.38.0).

### Affected Components:
swmanager, network, stats, fl, flow-hs, nbworker, flow, nb, connected

---

## v1.37.0 (26/09/2019)

### Features:
-  [#2756](https://github.com/telstra/open-kilda/pull/2756) Added catching of LLDP by Floodlight (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**floodlight**]
-  [#2697](https://github.com/telstra/open-kilda/pull/2697) Added instalation of LLDP rules (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**floodlight**][**storm-topologies**]
-  [#2704](https://github.com/telstra/open-kilda/pull/2704) Added switch validation of LLDP (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**storm-topologies**]
-  [#2643](https://github.com/telstra/open-kilda/pull/2643) Added ability to collect Connected Devices for flow (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**floodlight**][**northbound**][**storm-topologies**]

### Bug Fixes:
-  [#2817](https://github.com/telstra/open-kilda/pull/2817) Fixed null LLDP meter (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**floodlight**]
-  [#2819](https://github.com/telstra/open-kilda/pull/2819) Do not catch LLDP if switch has only 1 OF table (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**floodlight**][**storm-topologies**]
-  [#2820](https://github.com/telstra/open-kilda/pull/2820) Validate LLDP meters with Noviflow burstsize limitations (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**storm-topologies**]
-  [#2825](https://github.com/telstra/open-kilda/pull/2825) Added LLDP cookies and meters to StatsTopology cache (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**storm-topologies**]
-  [#2810](https://github.com/telstra/open-kilda/pull/2810) Fixed handling of unexpected responses in SwitchManagerWorker in Network topology. (Issue: [#2809](https://github.com/telstra/open-kilda/issues/2809)) [**storm-topologies**]

### Improvements:
-  [#2816](https://github.com/telstra/open-kilda/pull/2816) Extend lldp tests with lldp+vxlan tests [**tests**]
-  [#2754](https://github.com/telstra/open-kilda/pull/2754) Add connected devices traffgen support to testing framework [**tests**]
-  [#2824](https://github.com/telstra/open-kilda/pull/2824) fix logic in makePathMorePreferable [**tests**]
-  [#2828](https://github.com/telstra/open-kilda/pull/2828) Update lldp tests to select switches for tests more granularly [**tests**]
-  [#2776](https://github.com/telstra/open-kilda/pull/2776) Add test for 'new switch connects' scenario [**tests**]
-  [#2782](https://github.com/telstra/open-kilda/pull/2782) add test "System doesn't reroute flow to a path with not enough bandwidth available" [**tests**]
-  [#2800](https://github.com/telstra/open-kilda/pull/2800) add waiter to openTsdbSpec [**tests**]
-  [#2808](https://github.com/telstra/open-kilda/pull/2808) Add test to verify single-port flow rules validation/synchronization [**tests**]
-  [#2739](https://github.com/telstra/open-kilda/pull/2739) Func tests for connected devices rules (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**tests**]
-  [#2807](https://github.com/telstra/open-kilda/pull/2807) Added event logging with getting statistics. (Issue: [#2801](https://github.com/telstra/open-kilda/issues/2801)) [**floodlight**][**storm-topologies**]
-  [#2815](https://github.com/telstra/open-kilda/pull/2815) Fixed 1.14 migration 


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.36.1...v1.37.0).

### Affected Components:
stats, nbworker, swmanager, fl, nb, flow, network, neo4j

### Upgrade notes:

Related to [#2643](https://github.com/telstra/open-kilda/pull/2643)

Also please consider using following migration scripts to update db:
- [1.14 migration-script.xml](https://github.com/telstra/open-kilda/blob/v1.37.0/services/neo4j/migrations/1.14-connected-device-indexes/1-add-connected-device-index.xml)

In case of issues these rollback scripts should be executed:
- [1.14 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.37.0/services/neo4j/migrations/1.14-connected-device-indexes/rollback.cql)

## v1.36.1 (19/09/2019)

### Bug Fixes:
-  [#2813](https://github.com/telstra/open-kilda/pull/2813) Hotfix/isl round trip graph issue. [**gui**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.36.0...v1.36.1).

### Affected Components:
GUI

---

## v1.36.0 (18/09/2019)

### Features:
-  [#2760](https://github.com/telstra/open-kilda/pull/2760) Feature to enable search functionality to drop-downs (Issues: [#2648](https://github.com/telstra/open-kilda/issues/2648) [#2737](https://github.com/telstra/open-kilda/issues/2737)) [**gui**]
-  [#2698](https://github.com/telstra/open-kilda/pull/2698) Allocate LLDP resources for flows (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**storm-topologies**]
-  [#2726](https://github.com/telstra/open-kilda/pull/2726) Port history feature [**northbound**][**storm-topologies**]

### Bug Fixes:
-  [#2752](https://github.com/telstra/open-kilda/pull/2752) Add protected paths to flow cache in stats topology. Fix #2749 [**storm-topologies**]
-  [#2790](https://github.com/telstra/open-kilda/pull/2790) Fix the search for N network paths. (Issue: [#2789](https://github.com/telstra/open-kilda/issues/2789)) [**storm-topologies**]
-  [#2546](https://github.com/telstra/open-kilda/pull/2546) Force `WorkerBolt` to be more strict with stored data [**storm-topologies**]
-  [#2804](https://github.com/telstra/open-kilda/pull/2804) Fix for handling bfd response from speaker in network topology [**storm-topologies**]

### Improvements:
-  [#2696](https://github.com/telstra/open-kilda/pull/2696) Enable nested VLAN support into OVS into lab-service [**tests**]
-  [#2764](https://github.com/telstra/open-kilda/pull/2764) add test  system is able to reuse current protected path when can't find new protected path while intentional reroute (Issue: [#2762](https://github.com/telstra/open-kilda/issues/2762)) [**tests**]
-  [#2725](https://github.com/telstra/open-kilda/pull/2725) improve procedure of making path more preferable (Issue: [#2426](https://github.com/telstra/open-kilda/issues/2426)) [**tests**]
-  [#2792](https://github.com/telstra/open-kilda/pull/2792) minor fix in swapEndpointSpec [**tests**]
-  [#2793](https://github.com/telstra/open-kilda/pull/2793) Changed default broadcast address for kilda discovery [**floodlight**]
-  [#2802](https://github.com/telstra/open-kilda/pull/2802) Revert unhandledInput behaviour in worker bolts [**storm-topologies**]

### Other changes:
-  [#2757](https://github.com/telstra/open-kilda/pull/2757) Remove obsolete section [**docs**]
-  [#2586](https://github.com/telstra/open-kilda/pull/2586) Add QinQ support into traffexam [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.35.1...v1.36.0).

### Affected Components:
fl, neo4j, flow-hs, nb, flow, network, nbworker, stats

---

## v1.35.1 (11/09/2019)

### Features:
-  [#2753](https://github.com/telstra/open-kilda/pull/2753) Design for LLDP Connected devices feature (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**docs**]

### Bug Fixes:
-  [#2784](https://github.com/telstra/open-kilda/pull/2784) Fixed JSON deserialization when switch sync error. (Issue: [#2783](https://github.com/telstra/open-kilda/issues/2783)) [**northbound**][**storm-topologies**]

### Improvements:
-  [#2778](https://github.com/telstra/open-kilda/pull/2778) Change logic of verification duplicate isl in PCE
-  [#2775](https://github.com/telstra/open-kilda/pull/2775) add monitoring section in readme file for performance test [**tests**]


For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.35.0...v1.35.1).

### Affected Components:
network, swmanager, nb

---

## v1.35.0 (09/09/2019)

### Features:
-  [#2688](https://github.com/telstra/open-kilda/pull/2688) Added detectConnectedDevices fields to v1 Flow API (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**api**][**northbound**]
-  [#2703](https://github.com/telstra/open-kilda/pull/2703) Allocate LLDP resources (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**storm-topologies**]
-  [#2738](https://github.com/telstra/open-kilda/pull/2738) Add switch rules synchronization when switch activation. (Issue: [#2331](https://github.com/telstra/open-kilda/issues/2331)) [**storm-topologies**]

### Improvements:
-  [#2748](https://github.com/telstra/open-kilda/pull/2748) fix config for FL containers in docker-compose.yml [**configuration**]
-  [#2568](https://github.com/telstra/open-kilda/pull/2568) fix creating topology for performance test [**tests**]
-  [#2763](https://github.com/telstra/open-kilda/pull/2763) change flow description for auto tests [**tests**]
-  [#2766](https://github.com/telstra/open-kilda/pull/2766) Fix removing of unallocated resources on neo4j failure / constraint. [**storm-topologies**]
-  [#2771](https://github.com/telstra/open-kilda/pull/2771) Minor improvements in FlowSyncSpec [**tests**]
-  [#2773](https://github.com/telstra/open-kilda/pull/2773) Minor timeout increase for better test stability [**tests**]
-  [#2652](https://github.com/telstra/open-kilda/pull/2652) Propagate correlation id into completable future callbacks [**floodlight**]
-  [#2777](https://github.com/telstra/open-kilda/pull/2777) Fixed processing an activated switch if the meterEntry is null in the synchronization response. [**storm-topologies**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.34.0...v1.35.0).

### Affected Components:
nb, fl, network, flow, swmanager, flow-hs

### Upgrade notes:
If you have an older version of Kilda installed, then you must migrate the data stored in Neo4j
before you deploy and start this version. You should execute migration scripts before starting of deployment:
 - [1.11 update-constraints-changelog.xml](https://github.com/telstra/open-kilda/blob/v1.35.0/services/neo4j/migrations/1.11-lldp-resources/1-update-constraints-changelog.xml)

In case of any issues you are able to rollback 1.8 changes using:
 - [1.11 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.35.0/services/neo4j/migrations/1.11-lldp-resources/rollback.cql)

---

## v1.34.0 (02/09/2019)

### Features:
-  [#2679](https://github.com/telstra/open-kilda/pull/2679) Expose switch features over rest api [**northbound**][**storm-topologies**]

### Bug Fixes:
-  [#2730](https://github.com/telstra/open-kilda/pull/2730) Fix tag extension for cases when no IterationTags annotation present [**tests**]
-  [#2712](https://github.com/telstra/open-kilda/pull/2712) Fix switch rules synchronization. (Issues: [#2706](https://github.com/telstra/open-kilda/issues/2706) [#2707](https://github.com/telstra/open-kilda/issues/2707)) [**storm-topologies**]
-  [#2727](https://github.com/telstra/open-kilda/pull/2727) Split consumer groups for different regions [**floodlight**]

### Improvements:
-  [#2690](https://github.com/telstra/open-kilda/pull/2690) Switch Validation Refactoring (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) [**storm-topologies**]
-  [#2732](https://github.com/telstra/open-kilda/pull/2732) adjust flowCrud to work with not empty env [**tests**]
-  [#2733](https://github.com/telstra/open-kilda/pull/2733) Increase stats waiting timeout for better stability of test [**tests**]
-  [#2671](https://github.com/telstra/open-kilda/pull/2671) Produce LLDP packets by traffexam (Issue: [#2661](https://github.com/telstra/open-kilda/issues/2661))
-  [#2741](https://github.com/telstra/open-kilda/pull/2741) small improvements for tests [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.33.0...v1.34.0).

### Affected Components:
nbworker, neo4j, fl, swmanager, nb

### Upgrade notes:

Related to [#2679](https://github.com/telstra/open-kilda/pull/2679)

Also please consider using following migration scripts to update db:
- [1.0 migration-script.xml](https://github.com/telstra/open-kilda/blob/v1.34.0/services/neo4j/migrations/1.10-switch-properties/1-rename-switch-features-changelog.xml)

In case of issues these rollback scripts should be executed:
- [1.10 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.34.0/services/neo4j/migrations/1.10-switch-properties/rollback.cql)

---

## v1.33.0 (27/08/2019)

### Features:
-  [#2644](https://github.com/telstra/open-kilda/pull/2644) Add multitable flag for fl commands

### Bug Fixes:
-  [#2721](https://github.com/telstra/open-kilda/pull/2721) Fix CommandBuilder in the SwitchManager topology. [**storm-topologies**]
-  [#2724](https://github.com/telstra/open-kilda/pull/2724) Add retry when neo4j's ClientException is thrown [**storm-topologies**]
-  [#2729](https://github.com/telstra/open-kilda/pull/2729) Force stats topology cache sync to work with H&S requests [**storm-topologies**]

### Improvements:
-  [#2566](https://github.com/telstra/open-kilda/pull/2566) Minor change for which tests are tagged as SMOKE_SWITCHES [**tests**]
-  [#2711](https://github.com/telstra/open-kilda/pull/2711) Add 'purgeTopology' setup step in performance tests [**tests**]
-  [#2713](https://github.com/telstra/open-kilda/pull/2713) Adjust all tests to properly handle antiflap cooldown. [**tests**]
-  [#2653](https://github.com/telstra/open-kilda/pull/2653) extend vxlanFlow tests (APIv1) by checking rules [**tests**]
-  [#2723](https://github.com/telstra/open-kilda/pull/2723) add "See" annotation [**tests**]
-  [#2675](https://github.com/telstra/open-kilda/pull/2675) Get rid from ISL cost manipulation in DB. (Issue: [#2263](https://github.com/telstra/open-kilda/issues/2263)) [**storm-topologies**]
-  [#2687](https://github.com/telstra/open-kilda/pull/2687) refactor flowHelperV2,flowCrud,swapEndpoint files [**tests**]

### Other changes:
-  [#2715](https://github.com/telstra/open-kilda/pull/2715) Fixed RemoveFlow constructor error [**floodlight**]
-  [#2719](https://github.com/telstra/open-kilda/pull/2719) Revert "Take into account bugfix label while generating changelog"
-  [#2720](https://github.com/telstra/open-kilda/pull/2720) Take into account bugfix label while generating changelog
-  [#2722](https://github.com/telstra/open-kilda/pull/2722) Add missing flow reroute fail event in flow dashboard [**storm-topologies**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.32.0...v1.33.0).

### Affected Components:
flow-hs, swmanager, flow, fl, network, stats

---

## v1.32.0 (20/08/2019)

### Features:
-  [#2669](https://github.com/telstra/open-kilda/pull/2669) Added models and repos for Connected Devices (Issue: [#2582](https://github.com/telstra/open-kilda/issues/2582)) 

### Bug Fixes:
-  [#2596](https://github.com/telstra/open-kilda/pull/2596) Added udp dst port match into broadcast default rule. (Issue: [#2595](https://github.com/telstra/open-kilda/issues/2595)) [**floodlight**]
-  [#2695](https://github.com/telstra/open-kilda/pull/2695) Fix flow delete without resources +flow reroute v2 resource deallocation [**storm-topologies**]
-  [#2700](https://github.com/telstra/open-kilda/pull/2700) Update tests to workaround issue #2595 [**tests**]
-  [#2705](https://github.com/telstra/open-kilda/pull/2705) Hot fix wrong error message expected in func tests [**tests**]
-  [#2709](https://github.com/telstra/open-kilda/pull/2709) Hot fix wrong error message expected in func tests [**tests**]
-  [#2710](https://github.com/telstra/open-kilda/pull/2710) Do not match UDP port for broadcast rule on Centec [**floodlight**]

### Improvements:
-  [#2691](https://github.com/telstra/open-kilda/pull/2691) Increase wait after wfm finished for better test stability. [**tests**]
-  [#2668](https://github.com/telstra/open-kilda/pull/2668) Use hs auto-reroutes by default. Update waiters for path allocation [**tests**]
-  [#2673](https://github.com/telstra/open-kilda/pull/2673) Simplify unit test exec process 
-  [#2647](https://github.com/telstra/open-kilda/pull/2647) add tests: vxlan for api v2 [**tests**]
-  [#2682](https://github.com/telstra/open-kilda/pull/2682) Added switch features to switch DB model [**storm-topologies**]
-  [#2686](https://github.com/telstra/open-kilda/pull/2686) Add the default rules to switch rules synchronization. [**floodlight**][**storm-topologies**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.31.0...v1.32.0).

### Upgrade notes:
It is required to synchronize default rules on all switches.

Also please consider using following migration scripts to update db:
- [1.9 migration-script.xml](https://github.com/telstra/open-kilda/blob/v1.32.0/services/neo4j/migrations/1.9-connected-devices/1-update-constraints-changelog.xml)

In case of issues these rollback scripts should be executed:
- [1.9 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.32.0/services/neo4j/migrations/1.9-connected-devices/rollback.cql)

---

## v1.31.0 (14/08/2019)

### Bug Fixes:
-  [#2662](https://github.com/telstra/open-kilda/pull/2662) Fixed incorrect converting meter rate/burstsize from packets to kilobits [**floodlight**]
-  [#2685](https://github.com/telstra/open-kilda/pull/2685) Fix default flow creation in v2 +fixed resetting of FSM in case of retry [**northbound**]

### Improvements:
-  [#2672](https://github.com/telstra/open-kilda/pull/2672) Misc fixes in tests [**tests**]

### Other changes:
-  [#2637](https://github.com/telstra/open-kilda/pull/2637) Enable Vxlan support for v2 api [**floodlight**]
-  [#2683](https://github.com/telstra/open-kilda/pull/2683) Fix Kilda-FlowOperations-Filtered-Table Kibana search 

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.30.1...v1.31.0).

### Affected Components:
flow-hs, nb, fl

---

## v1.30.1 (07/08/2019)

### Bug Fixes:
-  [#2670](https://github.com/telstra/open-kilda/pull/2670) Fix flows in DOWN state without flow paths [**northbound**][**storm-topologies**]
-  [#2677](https://github.com/telstra/open-kilda/pull/2677) Fix error code for flow validation if flow is in DOWN state [**northbound**]
-  [#2678](https://github.com/telstra/open-kilda/pull/2678) Fix failed flow creation/reroute without paths 

### Other changes:
-  [#2674](https://github.com/telstra/open-kilda/pull/2674) Introduce FlowOperations Kibana dashboard [**storm-topologies**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.30.0...v1.30.1).

### Affected Components:
flow-hs, nb

--- 

## v1.30.0 (06/08/2019)

### Features:
-  [#2633](https://github.com/telstra/open-kilda/pull/2633) Adding feature to update isl bandwidth [**gui**]
-  [#2601](https://github.com/telstra/open-kilda/pull/2601) Introduce a custom dashboard logger for flow operations. [**storm-topologies**]

### Bug Fixes:
-  [#2645](https://github.com/telstra/open-kilda/pull/2645) Fix for installing meter for vxlan unicast (Issue: [#2635](https://github.com/telstra/open-kilda/issues/2635)) [**floodlight**]
-  [#2649](https://github.com/telstra/open-kilda/pull/2649) Fix: Ignore default rules when sync rules. [**storm-topologies**]
-  [#2655](https://github.com/telstra/open-kilda/pull/2655) Added go to table instruction to OF flow mapping (Issue: [#2375](https://github.com/telstra/open-kilda/issues/2375)) [**floodlight**]
-  [#2666](https://github.com/telstra/open-kilda/pull/2666) Fix flow create retries 
-  [#2667](https://github.com/telstra/open-kilda/pull/2667) Added condition to skip cost reduction when link goes from state under maintenance and cost less than isl.cost.when.under.maintenance. (Issue: [#2319](https://github.com/telstra/open-kilda/issues/2319)) [**storm-topologies**]
-  [#2670](https://github.com/telstra/open-kilda/pull/2670) Fix flows in DOWN state without flow paths [**northbound**][**storm-topologies**]

### Improvements:
-  [#2626](https://github.com/telstra/open-kilda/pull/2626) test: "System recreates excess meter when flow is created with the same meterId" (Issue: [#2625](https://github.com/telstra/open-kilda/issues/2625)) [**tests**]
-  [#2631](https://github.com/telstra/open-kilda/pull/2631) Get rid of kafka breaker [**tests**]
-  [#2638](https://github.com/telstra/open-kilda/pull/2638) update meterSpec according to wb5164 switch [**tests**]
-  [#2516](https://github.com/telstra/open-kilda/pull/2516) add tests for checking the encapsulation-type field [**tests**]
-  [#2646](https://github.com/telstra/open-kilda/pull/2646) Add WB5164 support to tests as well as various misc fixes [**tests**]
-  [#2654](https://github.com/telstra/open-kilda/pull/2654) Refactor unit tests for install default rules [**floodlight**][**tests**]
-  [#2657](https://github.com/telstra/open-kilda/pull/2657) fix pinned flow spec [**tests**]
-  [#2602](https://github.com/telstra/open-kilda/pull/2602) Add ContentionSpec for v1 and v2 api [**tests**]
-  [#2612](https://github.com/telstra/open-kilda/pull/2612) add tests for pinned/protected/diverse/default flow via APIv2 (Issue: [#2575](https://github.com/telstra/open-kilda/issues/2575)) [**tests**]
-  [#2493](https://github.com/telstra/open-kilda/pull/2493) Repeat db transation on db locks (Issue: [#2391](https://github.com/telstra/open-kilda/issues/2391)) [**storm-topologies**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.29.0...v1.30.0).

### Affected Components:
nb, neo4j, swmanager, network, flow-hs, nbworker, reroute, fl, flow

---

## v1.29.1 (01/08/2019)

### Bug Fixes:
-  [#2656](https://github.com/telstra/open-kilda/pull/2656) Hotfix to add permissions on switch, flow inventory and flow contracts. [**gui**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.29.0...v1.29.1).

### Affected Components:
gui

---

## v1.29.0 (31/07/2019)

### Features:
-  [#2591](https://github.com/telstra/open-kilda/pull/2591) Fix sync rules in case VxLAN encapsulation. [**storm-topologies**]
-  [#2605](https://github.com/telstra/open-kilda/pull/2605) Add to FL getting of default rules such as they should be on the switch. [**floodlight**]
-  [#2611](https://github.com/telstra/open-kilda/pull/2611) Add the default rules validation in the switch validation. [**northbound**][**storm-topologies**]
-  [#2621](https://github.com/telstra/open-kilda/pull/2621) inPort and outPort are added as tags for the flow stats [**floodlight**][**storm-topologies**]

### Bug Fixes:
-  [#2628](https://github.com/telstra/open-kilda/pull/2628) Fix properly picking free port in swap endpoints test 
-  [#2632](https://github.com/telstra/open-kilda/pull/2632) Fix flow create v2 issues +retries enhancement (Issue: [#2575](https://github.com/telstra/open-kilda/issues/2575)) [**floodlight**]
-  [#2640](https://github.com/telstra/open-kilda/pull/2640) Make flow create retries configurable [**configuration**]
-  [#2608](https://github.com/telstra/open-kilda/pull/2608) Limit traffexam bandwidth when examing in parallel [**tests**]
-  [#2609](https://github.com/telstra/open-kilda/pull/2609) Move ExtensionModule file to 'main' to properly resolve in helpers [**tests**]
-  [#2616](https://github.com/telstra/open-kilda/pull/2616) Fixed feature detection for E switches with 500 software (Issue: [#2615](https://github.com/telstra/open-kilda/issues/2615)) [**floodlight**]
-  [#2618](https://github.com/telstra/open-kilda/pull/2618) Fix one of swap endpoints tests [**tests**]

### Improvements:
-  [#2627](https://github.com/telstra/open-kilda/pull/2627) Add workaround to switch validation logic regarding E-switches. (Issue: [#2562](https://github.com/telstra/open-kilda/issues/2562)) [**storm-topologies**]
-  [#2583](https://github.com/telstra/open-kilda/pull/2583) Upd EnduranceSpec, add FlowPinger, add Dice for calling events randomly [**tests**]
-  [#2606](https://github.com/telstra/open-kilda/pull/2606) add new endpoint for managing iptables rules on floodlight (Issue: [#1268](https://github.com/telstra/open-kilda/issues/1268)) [**tests**]
-  [#2545](https://github.com/telstra/open-kilda/pull/2545) Update H&S reroute with encapsulation implementations 
-  [#2617](https://github.com/telstra/open-kilda/pull/2617) add virtualImpl for managing floodlight access [**tests**]
-  [#2623](https://github.com/telstra/open-kilda/pull/2623) Add default rules validation test and update existing validation tests [**tests**]
-  [#2216](https://github.com/telstra/open-kilda/pull/2216) Create FloodlightDashboardLogger for logging OF events [**floodlight**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.28.0...v1.29.0).

### Affected Components:
fl, nb, swmanager, router, stats, flow, flow-hs

---

## v1.28.0 (23/07/2019)

### Features:
-  [#2503](https://github.com/telstra/open-kilda/pull/2503) Fix flow validation in case VxLAN encapsulation. (Issue: [#647](https://github.com/telstra/open-kilda/issues/647)) [**floodlight**][**northbound**]

### Bug Fixes:
-  [#2603](https://github.com/telstra/open-kilda/pull/2603) Fix flow status of degraded flows w/ or w/o h&s flag [**tests**]
-  [#2607](https://github.com/telstra/open-kilda/pull/2607) Disable dumping table stats from OF 1.2 (Issue: [#2600](https://github.com/telstra/open-kilda/issues/2600)) [**floodlight**]

### Improvements:
-  [#2594](https://github.com/telstra/open-kilda/pull/2594) add test for a new vxlan default rule/meter [**tests**]
-  [#2571](https://github.com/telstra/open-kilda/pull/2571) Update SwapEndpointSpec with new tests and minor refactoring [**tests**]
-  [#2543](https://github.com/telstra/open-kilda/pull/2543) H&S reroute - minimize transaction contention and locks (Issue: [#2497](https://github.com/telstra/open-kilda/issues/2497)) [**storm-topologies**]
-  [#2579](https://github.com/telstra/open-kilda/pull/2579) add test 'System doesn't allow to create a one-switch flow on a DEACTIVATED switch' (Issue: [#2576](https://github.com/telstra/open-kilda/issues/2576)) [**tests**]
-  [#2589](https://github.com/telstra/open-kilda/pull/2589) Minor test updates for better stability [**tests**]

### Other changes:
-  [#2525](https://github.com/telstra/open-kilda/pull/2525) Get rid from event(wfm) topology [**storm-topologies**]
-  [#2184](https://github.com/telstra/open-kilda/pull/2184) Design for round trip latency (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**docs**]
-  [#2509](https://github.com/telstra/open-kilda/pull/2509) Configurable ping design (Issue: [#2542](https://github.com/telstra/open-kilda/issues/2542)) 
-  [#2517](https://github.com/telstra/open-kilda/pull/2517) Add logs for port update. [**floodlight**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.27.0...v1.28.0).

### Affected Components:
flow-hs, flow, reroute, neo4j, nb, nbworker, gui, fl, event

### Upgrade notes:
If you have an older version of Kilda installed, then you must migrate the data stored in Neo4j
before you deploy and start this version. You should execute migration scripts before starting of deployment:
 - [1.8 create-index-on-flow-meter-changelog.xml](https://github.com/telstra/open-kilda/blob/v1.28.0/services/neo4j/migrations/1.8-index-on-flow-meter/1-create-index-on-flow-meter-changelog.xml)
 - [1.8 migration-changelog.xml](https://github.com/telstra/open-kilda/blob/v1.28.0/services/neo4j/migrations/1.8-index-on-flow-meter/2-migration-changelog.xml)

In case of any issues you are able to rollback 1.8 changes using:
 - [1.8 rollback-indexes.cql](https://github.com/telstra/open-kilda/blob/v1.28.0/services/neo4j/migrations/1.8-index-on-flow-meter/rollback-indexes.cql)
 - [1.8 rollback-migration.cql](https://github.com/telstra/open-kilda/blob/v1.28.0/services/neo4j/migrations/1.8-index-on-flow-meter/rollback-migration.cql)

---

## v1.27.0 (11/07/2019)

### Features:
-  [#2592](https://github.com/telstra/open-kilda/pull/2592) Add table stats to opentsdb (#2574) (Issue: [#2574](https://github.com/telstra/open-kilda/issues/2574)) [**floodlight**][**storm-topologies**]
-  [#2560](https://github.com/telstra/open-kilda/pull/2560) Do not ignore Inactive ISLs in latency Cache (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**storm-topologies**]
-  [#2539](https://github.com/telstra/open-kilda/pull/2539) add test for checking "get all flows for a switch" [**tests**]

### Improvements:
-  [#2532](https://github.com/telstra/open-kilda/pull/2532) extend ProtectedPathSpec by checking the 'flowStatusDetails' filed [**tests**]
-  [#2567](https://github.com/telstra/open-kilda/pull/2567) Changed update latency intervals (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**configuration**]
-  [#2570](https://github.com/telstra/open-kilda/pull/2570) Add special test that generates topo.yaml based on what is discovered [**tests**]
-  [#2476](https://github.com/telstra/open-kilda/pull/2476) Do not treat all speaker as unavailable on floodlightrouter start (Issue: [#2456](https://github.com/telstra/open-kilda/issues/2456)) [**storm-topologies**]
-  [#2478](https://github.com/telstra/open-kilda/pull/2478) Shared bolt cappable to produce periodic time tuples [**storm-topologies**]
-  [#2553](https://github.com/telstra/open-kilda/pull/2553) Fix for vxlan unicast ping rule [**floodlight**][**northbound**]
-  [#2494](https://github.com/telstra/open-kilda/pull/2494) add tests for creating 2047 and 4094 flows [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.26.0...v1.27.0).

### Affected Components:
isllatency, fl, router, nb, ping, stats

---

## v1.26.0 (04/07/2019)

### Features:
-  [#2438](https://github.com/telstra/open-kilda/pull/2438) Excess rules and meters removing (Issues: [#2189](https://github.com/telstra/open-kilda/issues/2189) [#2215](https://github.com/telstra/open-kilda/issues/2215) [#2289](https://github.com/telstra/open-kilda/issues/2289)) [**api**][**floodlight**][**northbound**][**storm-topologies**]
-  [#2536](https://github.com/telstra/open-kilda/pull/2536) Add API to get all flows for a particular switch or endpoint. (Issue: [#2529](https://github.com/telstra/open-kilda/issues/2529)) [**api**][**northbound**][**storm-topologies**]

### Bug Fixes:
-  [#2569](https://github.com/telstra/open-kilda/pull/2569) Fix flow validation for swap endpoints functionality (for default flows) [**storm-topologies**]
-  [#2572](https://github.com/telstra/open-kilda/pull/2572) Do not use negative round trip latency for OpenTSDB 
-  [#2573](https://github.com/telstra/open-kilda/pull/2573) Fix validation of switches that don't support meters (FLOW HS topology) 
-  [#2577](https://github.com/telstra/open-kilda/pull/2577) Fix fsm flow create 
-  [#2555](https://github.com/telstra/open-kilda/pull/2555) Fix occasionally failing SwapEndpoint test [**tests**]

### Improvements:
-  [#2528](https://github.com/telstra/open-kilda/pull/2528) extend FlowDiversitySpec by checking the "diverse_with" field [**tests**]
-  [#2561](https://github.com/telstra/open-kilda/pull/2561) add extra check to prevent fail on a small env [**tests**]
-  [#2564](https://github.com/telstra/open-kilda/pull/2564) Add test that reproduces #2563 [**tests**]
-  [#2535](https://github.com/telstra/open-kilda/pull/2535) [Network topo] Change logging level for port state events. [**storm-topologies**]
-  [#2506](https://github.com/telstra/open-kilda/pull/2506) Make most traffic examination to run in parallel in both directions [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.25.0...v1.26.0).

### Affected Components:
nb, flow, nbworker, swmanager, fl, network, router, flow-hs

---

## v1.25.0 (01/07/2019)

### Features:
-  [#2508](https://github.com/telstra/open-kilda/pull/2508) PCE takes into account encapsulation type [**storm-topologies**]
-  [#2518](https://github.com/telstra/open-kilda/pull/2518) Add protected path status to API. (Issue: [#2513](https://github.com/telstra/open-kilda/issues/2513)) [**northbound**][**storm-topologies**]
-  [#2521](https://github.com/telstra/open-kilda/pull/2521) Tunnel id match extended [**floodlight**][**storm-topologies**]
-  [#2392](https://github.com/telstra/open-kilda/pull/2392) Add functional tests for flow swap endpoint feature [**tests**]
-  [#2530](https://github.com/telstra/open-kilda/pull/2530) RTL Part 5: change handling of OpenTsdb records (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**storm-topologies**]
-  [#2533](https://github.com/telstra/open-kilda/pull/2533) RTL Part 6: Added One Way latency manipulation (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**floodlight**][**storm-topologies**]
-  [#2473](https://github.com/telstra/open-kilda/pull/2473) Return diverse group flows id in get flow response (Issue: [#2465](https://github.com/telstra/open-kilda/issues/2465)) [**api**][**northbound**][**storm-topologies**]
-  [#2485](https://github.com/telstra/open-kilda/pull/2485) Issue 647 add vxlan methods to fl rebased [**floodlight**][**northbound**][**storm-topologies**]
-  [#2486](https://github.com/telstra/open-kilda/pull/2486) Design for representing protected path status in API [**api**]
-  [#2292](https://github.com/telstra/open-kilda/pull/2292) Feature/580 round trip latency (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**floodlight**][**storm-topologies**]
-  [#2558](https://github.com/telstra/open-kilda/pull/2558) Changed 'isl.latency' to 'isl.rtt' metric in GUI (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**gui**]

### Bug Fixes:
-  [#2498](https://github.com/telstra/open-kilda/pull/2498) Minor fixes for nb correlation id check and logging [**northbound**]
-  [#2504](https://github.com/telstra/open-kilda/pull/2504) Fix TagExtension to properly count execution times for a tag [**tests**]
-  [#2507](https://github.com/telstra/open-kilda/pull/2507) Fix for separate functional test execution [**tests**]
-  [#2510](https://github.com/telstra/open-kilda/pull/2510) Fix NPE in SwManager RouterBolt (Issue: [#2219](https://github.com/telstra/open-kilda/issues/2219)) [**storm-topologies**]
-  [#2520](https://github.com/telstra/open-kilda/pull/2520) Fix log message for validate switch request. [**northbound**]
-  [#2463](https://github.com/telstra/open-kilda/pull/2463) Make FSM independent (network-topology) (Issue: [#2457](https://github.com/telstra/open-kilda/issues/2457)) [**storm-topologies**]
-  [#2541](https://github.com/telstra/open-kilda/pull/2541) Fix correlation id checks for swagger ui (Issue: [#2515](https://github.com/telstra/open-kilda/issues/2515)) [**northbound**]
-  [#2548](https://github.com/telstra/open-kilda/pull/2548) Data migration for supported_transit_encapsulation 
-  [#2549](https://github.com/telstra/open-kilda/pull/2549) Fix for swagger correlation id header name [**northbound**]
-  [#2556](https://github.com/telstra/open-kilda/pull/2556) Fix for round trip latency  (Issues: [#2554](https://github.com/telstra/open-kilda/issues/2554) [#580](https://github.com/telstra/open-kilda/issues/580)) [**gui**]

### Improvements:
-  [#2500](https://github.com/telstra/open-kilda/pull/2500) extend default flow tests [**tests**]
-  [#2446](https://github.com/telstra/open-kilda/pull/2446) add tests to smoke iteration [**tests**]
-  [#2514](https://github.com/telstra/open-kilda/pull/2514) fix logging for the setLinkBfd method [**tests**]
-  [#2519](https://github.com/telstra/open-kilda/pull/2519) add smoke_switches tag, and mark needed tests [**tests**]
-  [#2404](https://github.com/telstra/open-kilda/pull/2404) add possibility to use the `verifyRulesOnProtectedFlow` method when we have 1+ flows (Issue: [#2282](https://github.com/telstra/open-kilda/issues/2282)) [**tests**]
-  [#2346](https://github.com/telstra/open-kilda/pull/2346) Ensure ISL use link props data (Issue: [#2220](https://github.com/telstra/open-kilda/issues/2220)) [**storm-topologies**]
-  [#2540](https://github.com/telstra/open-kilda/pull/2540) fix test "Able to swap main and protected paths manually" [**tests**]

### Other changes:
-  [#2496](https://github.com/telstra/open-kilda/pull/2496) Make 'ovs-meters-enabled' configurable [**configuration**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.24.0...v1.25.0).

### Affected Components:
fl, gui, network, neo4j, flow, nb, isllatency, swmanager

### Upgrade notes:
Use following migration scripts to update db:
- [1.6 migration-script.xml](https://github.com/telstra/open-kilda/blob/v1.25.0/services/neo4j/migrations/1.6-encapsulation-type/1-upper-case-flow-encapsulation-type-changelog.xml)
- [1.7 migration-script.xml](https://github.com/telstra/open-kilda/blob/v1.25.0/services/neo4j/migrations/1.7-supported-transit-encapsulation/1-supported-transit-encapsulation-to-switch-features-changelog.xml)

In case of issues these rollback scripts should be executed:
- [1.6 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.25.0/services/neo4j/migrations/1.6-encapsulation-type/rollback.cql)
- [1.7 rollback.cql](https://github.com/telstra/open-kilda/blob/v1.25.0/services/neo4j/migrations/1.7-supported-transit-encapsulation/rollback.cql)

---

## v1.24.0 (19/06/2019)

### Features:
-  [#2304](https://github.com/telstra/open-kilda/pull/2304) Swap endpoints for flow [**api**][**northbound**][**storm-topologies**]
-  [#2471](https://github.com/telstra/open-kilda/pull/2471) RTL Part 3: Handle RTL packet in Floodlight (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**floodlight**][**storm-topologies**]
-  [#2472](https://github.com/telstra/open-kilda/pull/2472) Storm side vxlan rules [**storm-topologies**]
-  [#2350](https://github.com/telstra/open-kilda/pull/2350) Add an ability to choose encapsulation type in the CRUD flow over REST API. (Issue: [#647](https://github.com/telstra/open-kilda/issues/647)) [**northbound**][**storm-topologies**]
-  [#2417](https://github.com/telstra/open-kilda/pull/2417) Allow both default and VLAN tagged flows for the same port (Issue: [#2411](https://github.com/telstra/open-kilda/issues/2411)) [**floodlight**][**storm-topologies**]

### Bug Fixes:
-  [#2499](https://github.com/telstra/open-kilda/pull/2499) Restored cookie mismatch debug log level [**floodlight**]
-  [#2464](https://github.com/telstra/open-kilda/pull/2464) Fix switch validate and rules sync on meters unsupported switches (Issue: [#2453](https://github.com/telstra/open-kilda/issues/2453)) [**floodlight**][**storm-topologies**]
-  [#2469](https://github.com/telstra/open-kilda/pull/2469) Propagate API_HOST from lab-api into lab-service [**tests**]
-  [#2480](https://github.com/telstra/open-kilda/pull/2480) Fix to protected path tests [**tests**]
-  [#2483](https://github.com/telstra/open-kilda/pull/2483) Fix assumeProfile failing if first feature is profile-dependent [**tests**]
-  [#2484](https://github.com/telstra/open-kilda/pull/2484) Fix: Remove the notification stream going to the HS kafka bolt. [**storm-topologies**]
-  [#2487](https://github.com/telstra/open-kilda/pull/2487) Fix functional test for pinned flow [**tests**]
-  [#2488](https://github.com/telstra/open-kilda/pull/2488) Fix functional test for ISL min port speed feature [**tests**]
-  [#2428](https://github.com/telstra/open-kilda/pull/2428) Fix switch status update logging [**storm-topologies**]
-  [#2512](https://github.com/telstra/open-kilda/pull/2512) Partially revert PR2212 - deduplicate port event notifications (Issue: [#2212](https://github.com/telstra/open-kilda/issues/2212)) [**storm-topologies**]

### Improvements:
-  [#2434](https://github.com/telstra/open-kilda/pull/2434) check that system allows to pass traffic via default and vlan flows when they are on the same port (Issue: [#2433](https://github.com/telstra/open-kilda/issues/2433)) [**tests**]
-  [#2502](https://github.com/telstra/open-kilda/pull/2502) ignore test, functionality is not implemented yet [**tests**]
-  [#2454](https://github.com/telstra/open-kilda/pull/2454) Improve sync rules test to use path with max amount of switches (Issue: [#2453](https://github.com/telstra/open-kilda/issues/2453)) [**tests**]
-  [#2212](https://github.com/telstra/open-kilda/pull/2212) Improvements of Network topology dashboard logger. (Issue: [#1157](https://github.com/telstra/open-kilda/issues/1157)) [**storm-topologies**]
-  [#2477](https://github.com/telstra/open-kilda/pull/2477) Add test that slowly discovers switches one by one [**tests**]
-  [#2489](https://github.com/telstra/open-kilda/pull/2489) Small refactoring of self-loop ISL test [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.23.0...v1.24.0).

### Affected Components:
router, fl, flow, reroute, swmanager, network, nb

---

## v1.23.0 (10/06/2019)

### Features:
-  [#2466](https://github.com/telstra/open-kilda/pull/2466) Add a feature toggle to reroute a flow with default encapsulation type. (Issue: [#647](https://github.com/telstra/open-kilda/issues/647)) [**api**][**storm-topologies**]
-  [#2402](https://github.com/telstra/open-kilda/pull/2402) Add a VxLAN resource pool. (Issue: [#647](https://github.com/telstra/open-kilda/issues/647)) [**neo4j**]
-  [#2406](https://github.com/telstra/open-kilda/pull/2406) Added new Encapslation model class and Extended Encapsulation Enum [**storm-topologies**]

### Bug Fixes:
-  [#2475](https://github.com/telstra/open-kilda/pull/2475) Exclude switch object from switch features equals and hashCode

### Improvements:
-  [#2470](https://github.com/telstra/open-kilda/pull/2470) Add round trip rule into test [**tests**]
-  [#2440](https://github.com/telstra/open-kilda/pull/2440) add test: check that protected flow allows traffic on main/protected paths (Issues: [#2367](https://github.com/telstra/open-kilda/issues/2367) [#2415](https://github.com/telstra/open-kilda/issues/2415)) [**tests**]
-  [#2444](https://github.com/telstra/open-kilda/pull/2444) add possibility to set specific controllet on an OVS [**tests**]
-  [#2253](https://github.com/telstra/open-kilda/pull/2253) Add CommandContext into all tuples. (Issues: [#2199](https://github.com/telstra/open-kilda/issues/2199) [#2239](https://github.com/telstra/open-kilda/issues/2239)) [**storm-topologies**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.22.0...v1.23.0).

### Affected Components:
isllatency, stats, neo4j, flow, portstate, otsdb, swmanager, router, nbworker

### Upgrade notes
If you have an older version of Kilda installed, then you must migrate the data stored in Neo4j 
before you deploy and start this version. You should execute [migration script](https://github.com/telstra/open-kilda/blob/v1.23.0/services/neo4j/migrations/1.5-switch-features/1-create-switch-features-changelog.xml) before starting of deployment.

In case of any issues you are able to rollback these changes using [rollback script](https://github.com/telstra/open-kilda/blob/v1.23.0/services/neo4j/migrations/1.5-switch-features/rollback.cql).

---

## v1.22.0 (06/06/2019)

### Features:
-  [#2322](https://github.com/telstra/open-kilda/pull/2322) Create a config with default encapsulation type in Neo4j. (Issues: [#2427](https://github.com/telstra/open-kilda/issues/2427) [#647](https://github.com/telstra/open-kilda/issues/647)) [**api**][**northbound**][**storm-topologies**]
-  [#2327](https://github.com/telstra/open-kilda/pull/2327) RTL: Part 2. Added round trip latency default rule (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**floodlight**]
-  [#2467](https://github.com/telstra/open-kilda/pull/2467) Add round trip latency rule to install/delete rules API (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**api**][**floodlight**]
-  [#2328](https://github.com/telstra/open-kilda/pull/2328) Pinned flow (Issue: [#2334](https://github.com/telstra/open-kilda/issues/2334)) [**api**][**storm-topologies**]

### Bug Fixes:
-  [#2390](https://github.com/telstra/open-kilda/pull/2390) Fix orphan meter resources [**storm-topologies**]

### Improvements:
-  [#2451](https://github.com/telstra/open-kilda/pull/2451) add tests for a pinned flow [**tests**]
-  [#2427](https://github.com/telstra/open-kilda/pull/2427) Add flow allocate protected path field migration 

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.21.2...v1.22.0).

### Affected Components:
reroute, nbworker, flow, nb, neo4j, fl

### Upgrade notes:

If you have an older version of Kilda installed, then you must migrate the data stored in Neo4j
before you deploy and start this version. You should execute migration scripts before starting of deployment:
 - [1.3 migration-script.xml](https://github.com/telstra/open-kilda/blob/v1.22.0/services/neo4j/migrations/1.3-add-allocate-protected-path-field/1-add-allocate-protected-path-field-changelog.xml)
 - [1.4 migration-script.xml](https://github.com/telstra/open-kilda/blob/v1.22.0/services/neo4j/migrations/1.4-kilda-configuration/1-update-constraints-changelog.xml)

In case of any issues you are able to rollback 1.4 changes using [1.4 rollback-script.cql](https://github.com/telstra/open-kilda/blob/v1.22.0/services/neo4j/migrations/1.4-kilda-configuration/rollback.cql).
For migration 1.3 you must make a database backup to rollback changes.

---

## v1.21.2 (05/06/2019)
### Improvements:
-  [#2397](https://github.com/telstra/open-kilda/pull/2397) Use same transit VLAN for paths in both directions (Issue: [#2386](https://github.com/telstra/open-kilda/issues/2386)) [**storm-topologies**]
-  [#2384](https://github.com/telstra/open-kilda/pull/2384) extend GRPC tests to iterate over all available firmware versions (6.4+) (Issue: [#2251](https://github.com/telstra/open-kilda/issues/2251)) [**tests**]
-  [#2442](https://github.com/telstra/open-kilda/pull/2442) add verification that one transit vlan is created for a flow [**tests**]
-  [#2437](https://github.com/telstra/open-kilda/pull/2437) Add global flag to switch H&S reroutes [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.21.1...v1.21.2).

### Affected Components:
flow

---

## v1.21.1 (04/06/2019)
### Bug Fixes:
-  [#2439](https://github.com/telstra/open-kilda/pull/2439) Fix failsafe transaction interface 
-  [#2283](https://github.com/telstra/open-kilda/pull/2283) Do not reset defaultMaxBanwidth ISL's field on network topology start (Issue: [#2248](https://github.com/telstra/open-kilda/issues/2248)) [**storm-topologies**]
-  [#2412](https://github.com/telstra/open-kilda/pull/2412) Do not fail lab-service if openvswitch service is not running [**tests**]

### Improvements:
-  [#2436](https://github.com/telstra/open-kilda/pull/2436) Make transaction bolt "non-stateful" in terms of storm [**storm-topologies**]
-  [#2423](https://github.com/telstra/open-kilda/pull/2423) Router remove stateful (Issue: [#2234](https://github.com/telstra/open-kilda/issues/2234)) [**storm-topologies**]
-  [#2296](https://github.com/telstra/open-kilda/pull/2296) Extend transation manager with repeat mecahnism (Issue: [#2291](https://github.com/telstra/open-kilda/issues/2291)) 
-  [#2316](https://github.com/telstra/open-kilda/pull/2316) Refactor of PathVerificationService (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**floodlight**]
-  [#2289](https://github.com/telstra/open-kilda/pull/2289) SwitchManager topology refactoring (Issue: [#2215](https://github.com/telstra/open-kilda/issues/2215)) [**northbound**][**storm-topologies**]
-  [#2354](https://github.com/telstra/open-kilda/pull/2354) Detect and handle self looped ISL (Issue: [#2314](https://github.com/telstra/open-kilda/issues/2314)) [**storm-topologies**]
-  [#2424](https://github.com/telstra/open-kilda/pull/2424) fix test: System doesn't reroute main flow path when protected path is broken and new alt path is available (Issue: [#2420](https://github.com/telstra/open-kilda/issues/2420)) [**tests**]
-  [#2396](https://github.com/telstra/open-kilda/pull/2396) add verification that system deletes props for both directions, even … (Issue: [#2388](https://github.com/telstra/open-kilda/issues/2388)) [**tests**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.21.0...v1.21.1).

### Affected Components:
swmanager, fl, nb, neo4j, flow, network, router

---

## v1.21.0 (03/06/2019)
### Features:
-  [#2403](https://github.com/telstra/open-kilda/pull/2403) Adding  feature to toggle the menu sidebar (Issue:  [#2387](https://github.com/telstra/open-kilda/issues/2387)) [**gui**]
-  [#2394](https://github.com/telstra/open-kilda/pull/2394) Adding feature to delete ISL and improvement for list screen cache reset , latency calculation on flow detail screen,changed speed with max_bandwidth on isl detail (Issues:  [#2348](https://github.com/telstra/open-kilda/issues/2348), [#2339](https://github.com/telstra/open-kilda/issues/2339), [#2337](https://github.com/telstra/open-kilda/issues/2337), [#2338](https://github.com/telstra/open-kilda/issues/2338)) [**gui**]
-  [#2395](https://github.com/telstra/open-kilda/pull/2395) Re-implement flow create feature using H&S approach (Issue:  [#1866](https://github.com/telstra/open-kilda/issues/1866)) [**northbound**]
-  [#2360](https://github.com/telstra/open-kilda/pull/2360) Re-implement flow reroute feature using H&S approach (Issue:  [#2017](https://github.com/telstra/open-kilda/issues/2017)) [**northbound**]
-  [#2315](https://github.com/telstra/open-kilda/pull/2315) Add flow create API V2  (Issue:  [#1866](https://github.com/telstra/open-kilda/issues/1866)) [**northbound**][**area/api**] [**northbound**]

### Bug Fixes:
-  [#2364](https://github.com/telstra/open-kilda/pull/2364) Add test for issue #2363 (Issue:  [#2363](https://github.com/telstra/open-kilda/issues/2363)) [**tests**]

### Improvements:
-  [#2425](https://github.com/telstra/open-kilda/pull/2425) Log the root cause of OGM mapping exception in repositories 
-  [#2421](https://github.com/telstra/open-kilda/pull/2421) ignore broken test due to incorrect logic in test [**tests**]
-  [#2414](https://github.com/telstra/open-kilda/pull/2414) Implement "self-executable" fl commands, refactor fl structure (Issue:  [#1866](https://github.com/telstra/open-kilda/issues/1866))
-  [#2409](https://github.com/telstra/open-kilda/pull/2409) rename correlation id for functional and grpc tests [**tests**]
-  [#2408](https://github.com/telstra/open-kilda/pull/2408) add tests for pinned+protected flow [**tests**]
-  [#2405](https://github.com/telstra/open-kilda/pull/2405) Persist python lib versions for traffexam [**tests**]
-  [#2401](https://github.com/telstra/open-kilda/pull/2401) Add logging for ISL disco response in fl kafka producer and fl router
-  [#2399](https://github.com/telstra/open-kilda/pull/2399) Added role and region as custom fields to FL logback config [**area/config**]
-  [#2393](https://github.com/telstra/open-kilda/pull/2393) Create changelog generator script [**area/ops**]
-  [#2368](https://github.com/telstra/open-kilda/pull/2368) System doesn't reroute main flow path when protected path is broken and new alt path is available [**tests**]
-  [#2347](https://github.com/telstra/open-kilda/pull/2347) Add functional tests for BFD feature [**tests**]
-  [#2276](https://github.com/telstra/open-kilda/pull/2276) Make broadcast MAC address for a discovery packet configurable. (Issues:  [#2053](https://github.com/telstra/open-kilda/issues/2053))

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.20.0...v1.21.0).

### Upgrade notes
If you have an older version of Kilda installed, then you must migrate the data stored in Neo4j 
before you deploy and start this version.  You should execute [migration script](https://github.com/telstra/open-kilda/blob/v1.21.0/services/neo4j/migrations/1.2-history-event/1-update-constraints-changelog.xml) before starting of deployment.

In case of any issues you are able to rollback these changes using [rollback script](https://github.com/telstra/open-kilda/blob/v1.21.0/services/neo4j/migrations/1.2-history-event/rollback.cql).

---

## v1.20.0 (27/05/2019)
### Features:
-  [#1954](https://github.com/telstra/open-kilda/pull/1954) Add new api for update max bandwidth (Issues: [#1937](https://github.com/telstra/open-kilda/issues/1937) [#1944](https://github.com/telstra/open-kilda/issues/1944) [#2003](https://github.com/telstra/open-kilda/issues/2003)) [**northbound**][**storm-topologies**]
-  [#2279](https://github.com/telstra/open-kilda/pull/2279) MultiFL. Add FL for statistics. [**floodlight**][**storm-topologies**]

### Bug Fixes:
-  [#2380](https://github.com/telstra/open-kilda/pull/2380) Fix perf test to respect existing topology and properly avoid isl ports [**tests**]
-  [#2385](https://github.com/telstra/open-kilda/pull/2385) Remove unhandledInput from IslStatsBolt [**storm-topologies**]

### Improvements:
-  [#2305](https://github.com/telstra/open-kilda/pull/2305) MultiFL statistics documentation. [**docs**]
-  [#1860](https://github.com/telstra/open-kilda/pull/1860) Design for create flow using H&S approach [**docs**][**storm-topologies**]
-  [#2374](https://github.com/telstra/open-kilda/pull/2374) Minor fixes in meters centec test to keep up with latest code updates [**tests**]
-  [#2312](https://github.com/telstra/open-kilda/pull/2312) Rename IslStatsTopology to IslLatencyTopology and refactor (Issue: [#580](https://github.com/telstra/open-kilda/issues/580)) [**storm-topologies**]
-  [#2347](https://github.com/telstra/open-kilda/pull/2347) Add functional tests for BFD feature [**tests**]
-  [#2379](https://github.com/telstra/open-kilda/pull/2379) fix tests for protected path according to staging env [**tests**]
-  [#2389](https://github.com/telstra/open-kilda/pull/2389) Hotfix for update max bandwith 
-  [#2399](https://github.com/telstra/open-kilda/pull/2399) Added role and region as custom fields to FL logback config [**configuration**][**floodlight**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.19.0...v1.20.0).

---

## v1.19.0 (22/05/2019)
### Features:
-  [#2325](https://github.com/telstra/open-kilda/pull/2325) Use OF groups for discovery process (Issue:  [#580](https://github.com/telstra/open-kilda/issues/580))
-  [#2320](https://github.com/telstra/open-kilda/pull/2320) Adding feature to display meter stats graph (Issue:  [#2025](https://github.com/telstra/open-kilda/issues/2025) [**gui**])
-  [#2155](https://github.com/telstra/open-kilda/pull/2155) Protected paths implementation  (Issue:  [#1232](https://github.com/telstra/open-kilda/issues/1232) [**area/arch**])
-  [#2217](https://github.com/telstra/open-kilda/pull/2217) Accomplish BFD session management (Issue:  [#1487](https://github.com/telstra/open-kilda/issues/1487))

### Bug Fixes:
-  [#2362](https://github.com/telstra/open-kilda/pull/2362) Fix issue in topology settings upload json file. 
-  [#2344](https://github.com/telstra/open-kilda/pull/2344) Fix API PATCH /flows/{flow-id}. (Issue:  [#2343](https://github.com/telstra/open-kilda/issues/2343))

### Improvements:
-  [#2365](https://github.com/telstra/open-kilda/pull/2365) Fix unstable meters test to properly wait for flow update to complete [**tests**]
-  [#2352](https://github.com/telstra/open-kilda/pull/2352) Refactor flow crud test to avoid isl-port endpoints [**tests**]
-  [#2351](https://github.com/telstra/open-kilda/pull/2351) Feature/isl and switch maintenance  (Issues:  [#2294](https://github.com/telstra/open-kilda/issues/2294), [#2295](https://github.com/telstra/open-kilda/issues/2295) [**gui**])
-  [#2345](https://github.com/telstra/open-kilda/pull/2345) Fix KafkaBreaker hardcoded topic name [**tests**]
-  [#2341](https://github.com/telstra/open-kilda/pull/2341) REFACTOR: rename method according to internal convention [**tests**]
-  [#2340](https://github.com/telstra/open-kilda/pull/2340) Add EnduranceSpec with 'simulation' test [**tests**]
-  [#2330](https://github.com/telstra/open-kilda/pull/2330) extend existing 'Unable to create a flow on an isl port' test [**tests**]
-  [#2329](https://github.com/telstra/open-kilda/pull/2329) Use docker memlimits for 16GB setup as default (instead of unlimited) [**area/config**]
-  [#2326](https://github.com/telstra/open-kilda/pull/2326) ADD test: System is able to set min port speed for isl capacity [**tests**]
-  [#2324](https://github.com/telstra/open-kilda/pull/2324) Use virtual/hardware tags instead of assumptions directly in test [**tests**]
-  [#2323](https://github.com/telstra/open-kilda/pull/2323) Extend switch validation spec  [**tests**]
-  [#2317](https://github.com/telstra/open-kilda/pull/2317) Misc improvements in tests (Issue:  [#1865](https://github.com/telstra/open-kilda/issues/1865) [**tests**])
-  [#2284](https://github.com/telstra/open-kilda/pull/2284) extend the 'Able to swap flow path' test [**tests**]
-  [#2269](https://github.com/telstra/open-kilda/pull/2269) ADD: System is able to reroute(intentional) flow with protected according to the priority field [**tests**]
-  [#2264](https://github.com/telstra/open-kilda/pull/2264) ADD test: Able to validate switch rules in case flow is created with protected path [**tests**]
-  [#2262](https://github.com/telstra/open-kilda/pull/2262) ADD test: Able to validate flow with protected path  [**tests**]
-  [#2261](https://github.com/telstra/open-kilda/pull/2261) ADD test: Able to synchronize rules for a flow with protected path [**tests**]
-  [#2241](https://github.com/telstra/open-kilda/pull/2241) Refactoring of VerificationPacket class 
-  [#2213](https://github.com/telstra/open-kilda/pull/2213) Add performance-tests skeleton and example test [**tests**]
-  [#2204](https://github.com/telstra/open-kilda/pull/2204) Stats request to Storm 
-  [#2192](https://github.com/telstra/open-kilda/pull/2192) #1060 Add check for conflicts with ISL ports in FlowValidator.  (Issue:  [#1060](https://github.com/telstra/open-kilda/issues/1060))

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.18.2...v1.19.0).

### Upgrade notes
If you have an older version of Kilda installed, then you must migrate the data stored in Neo4j 
before you deploy and start this version.  You should execute [migration script](https://github.com/telstra/open-kilda/blob/v1.19.0/services/neo4j/migrations/1.1-bfd-session/1-update-constraints-changelog.xml) before starting of deployment.

In case of any issues you are able to rollback these changes using [rollback script](https://github.com/telstra/open-kilda/blob/v1.19.0/services/neo4j/migrations/1.1-bfd-session/rollback.cql).

---

## v1.18.2
### Features:
-  [#2309](https://github.com/telstra/open-kilda/pull/2309) Adding feature to import and export topology screen settings (Issue:  [#2293](https://github.com/telstra/open-kilda/issues/2293) [**gui**])
-  [#2280](https://github.com/telstra/open-kilda/pull/2280) Adding feature of copy to clipboard on list screens (Issue:  [#2275](https://github.com/telstra/open-kilda/issues/2275) [**gui**])
-  [#2271](https://github.com/telstra/open-kilda/pull/2271) Changed oracle jdk to openjdk 
-  [#2252](https://github.com/telstra/open-kilda/pull/2252) Enhancement to change default metric in packet loss graph to packets (Issue:  [#2202](https://github.com/telstra/open-kilda/issues/2202) [**gui**])

### Bug Fixes:
-  [#2250](https://github.com/telstra/open-kilda/pull/2250) Add error response for CRUD Flow when features disabled [**northbound**] (Issue:  [#1920](https://github.com/telstra/open-kilda/issues/1920))
-  [#2256](https://github.com/telstra/open-kilda/pull/2256) Add test for stats max values. Bump spock to 1.3 [**tests**] (Issue:  [#2255](https://github.com/telstra/open-kilda/issues/2255))
-  [#2274](https://github.com/telstra/open-kilda/pull/2274) Restore gitignore coverage

### Improvements:
-  [#2311](https://github.com/telstra/open-kilda/pull/2311) Customise neo4j container 
-  [#2308](https://github.com/telstra/open-kilda/pull/2308) Add test to verify excess rules and meters deletion while sync [**tests**]
-  [#2306](https://github.com/telstra/open-kilda/pull/2306) Expose ElasticSearch ports. 
-  [#2303](https://github.com/telstra/open-kilda/pull/2303) Slightly improve lab service api parallelism [**tests**]
-  [#2300](https://github.com/telstra/open-kilda/pull/2300) Remove AbstractException class 
-  [#2297](https://github.com/telstra/open-kilda/pull/2297) Refactoring of functional tests to use topology helper where possible [**tests**]
-  [#2287](https://github.com/telstra/open-kilda/pull/2287) Rework TagExtension for new tags format. Introduce IterationTags [**tests**]
-  [#2286](https://github.com/telstra/open-kilda/pull/2286) Log discovery data received by speaker 
-  [#2277](https://github.com/telstra/open-kilda/pull/2277) Added argument for FROM in all Dockerfile [**area/cicd**]
-  [#2272](https://github.com/telstra/open-kilda/pull/2272) Improve test name logging in functional-tests module [**tests**]
-  [#2270](https://github.com/telstra/open-kilda/pull/2270) Changed default elasticsearch index name from logstash to kilda 
-  [#2260](https://github.com/telstra/open-kilda/pull/2260) Add fields to SwitchDto from database model. 
-  [#2240](https://github.com/telstra/open-kilda/pull/2240) Minor tests updates [**tests**]
-  [#2238](https://github.com/telstra/open-kilda/pull/2238) Manage to pass kafka-key from storm to speaker 
-  [#2234](https://github.com/telstra/open-kilda/pull/2234) Bolts in fl-router are stateless in terms of storm 
-  [#2233](https://github.com/telstra/open-kilda/pull/2233) Extend functional test for Floodlight and Kafka outage [**tests**]
-  [#2226](https://github.com/telstra/open-kilda/pull/2226) Improve TopologyHelper vastly. Introduce PotentialFlow entity [**tests**]
-  [#2223](https://github.com/telstra/open-kilda/pull/2223) Add test: "Unable to create a flow on an isl port" [**tests**] (Issue:  [#2222](https://github.com/telstra/open-kilda/issues/2222) [**tests**])
-  [#2210](https://github.com/telstra/open-kilda/pull/2210) Improve discovery topology logging 
-  [#2208](https://github.com/telstra/open-kilda/pull/2208) Lower log level for "lost" discovery packets 
-  [#2196](https://github.com/telstra/open-kilda/pull/2196) Fix functional tests for logging [**tests**]
-  [#2188](https://github.com/telstra/open-kilda/pull/2188) Func tests/add tests for updating bw [**tests**]
-  [#2186](https://github.com/telstra/open-kilda/pull/2186) Remove execute method override into `CoordinatedBolt` 
-  [#2167](https://github.com/telstra/open-kilda/pull/2167) ADD: meter validation tests [**tests**] (Issues:  [#2066](https://github.com/telstra/open-kilda/pull/2066), [#2131](https://github.com/telstra/open-kilda/issues/2131) [**tests**])

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.18.1...v1.18.2).

---

## v1.18.1 (03/05/2019)
### Bug Fixes:
-  [#2318](https://github.com/telstra/open-kilda/pull/2318) Fix resource allocation below the low boundaries. (Resource pools may provide a value below the low boundaries if there's a gap in already allocated ones and this gap is below the low boundary)

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.18.0...v1.18.1).

---

## v1.18.0 (02/05/2019)
### Features:
-  [#2230](https://github.com/telstra/open-kilda/pull/2230) ISL and switch details can be open from a flow path on GUI (Issue:  [#1740](https://github.com/telstra/open-kilda/issues/1740))

### Bug Fixes:
-  [#2245](https://github.com/telstra/open-kilda/pull/2245) Add error response for sync meters on umetered flow  (Issue:  [#2043](https://github.com/telstra/open-kilda/issues/2043))

### Improvements:
-  [#2002](https://github.com/telstra/open-kilda/pull/2002) Introduce the data model with flow encapsulation, flow paths and resource pools. (Issue:  [#1519](https://github.com/telstra/open-kilda/issues/1519))
-  [#2103](https://github.com/telstra/open-kilda/pull/2103) Adopt the new data model via a wrapper entity (Issue:  [#2018](https://github.com/telstra/open-kilda/issues/2018))
-  [#2106](https://github.com/telstra/open-kilda/pull/2106) Implement transactional resource pools (Issue:  [#2017](https://github.com/telstra/open-kilda/issues/2017))
-  [#2138](https://github.com/telstra/open-kilda/pull/2138) Migration scripts for the new data model. (Issue:  [#1519](https://github.com/telstra/open-kilda/issues/1519))
-  [#2247](https://github.com/telstra/open-kilda/pull/2247) Add core properties auto create metrics,tagks,tagvs to opentsdb.conf

### Other changes:
-  [#1906](https://github.com/telstra/open-kilda/pull/1906) Protected paths design [**docs**] (Issue:  [#1232](https://github.com/telstra/open-kilda/issues/1232))
-  [#1904](https://github.com/telstra/open-kilda/pull/1904) Design for flow rerouting using H&S approach [**docs**]

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.17.1...v1.18.0).

### Upgrade notes
If you have an older version of Kilda installed, then you must migrate the data stored in Neo4j 
before you deploy and start this version. 

The data migration steps:
- If any of Kilda components is running, shut it down. Neo4j must be up and a backup is created.
- Execute [1-update-constraints-changelog.xml](https://github.com/telstra/open-kilda/blob/v1.18.0/services/neo4j/migrations/1.0-flow-paths-n-resources/1-update-constraints-changelog.xml)
 and [2-migration-changelog.xml](https://github.com/telstra/open-kilda/blob/v1.18.0/services/neo4j/migrations/1.0-flow-paths-n-resources/2-migration-changelog.xml) scripts using Liquigraph tools 
(see [migration README.md](https://github.com/telstra/open-kilda/blob/v1.18.0/services/neo4j/migrations/README.md)).
- Deploy and start all Kilda components of this version.
- After CRUD tests are passed and the system is being operational with no issues for some period, you need to execute the cleanup script 
[3-cleanup-changelog.xml](https://github.com/telstra/open-kilda/blob/v1.18.0/services/neo4j/migrations/1.0-flow-paths-n-resources/3-cleanup-changelog.xml).

In case of any issue you can rollback the migration:
- Execute queries from [rollback-migration.cql](https://github.com/telstra/open-kilda/blob/v1.18.0/services/neo4j/migrations/1.0-flow-paths-n-resources/rollback-migration.cql)
to revert changes made by ```2-migration-changelog.xml```. 
- Execute queries from [rollback-constaints.cql](https://github.com/telstra/open-kilda/blob/v1.18.0/services/neo4j/migrations/1.0-flow-paths-n-resources/rollback-constaints.cql)
to revert changes made by ```1-update-constraints-changelog.xml```. 
 
**IMPORTANT:** The changes made by the cleanup script (3-cleanup-changelog.xml) can't be reverted!  

---

## v1.17.1 (15/04/2019)
### Bug Fixes:
-  [#2259](https://github.com/telstra/open-kilda/pull/2259) Propagate ISL's cost updates to the link-props objects. (Issue:  [#2257](https://github.com/telstra/open-kilda/issues/2257))

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.17.0...v1.17.1).

---

## v1.17.0 (10/04/2019)
### Features:
-  [#2161](https://github.com/telstra/open-kilda/pull/2161) Combine `api` and `northbound` modules, prepare Northbound for API v2 support

### Bug Fixes:
-  [#2236](https://github.com/telstra/open-kilda/pull/2236) Fix the network dump issue in Floodlight router (Router doesn't set proper switch statuses when a region goes back up after being offline).
-  [#2242](https://github.com/telstra/open-kilda/pull/2242) Fix anonymous authentication for Northbound health-check ([#2161](https://github.com/telstra/open-kilda/pull/2161) broke anonymous authentication for `/api/v1/health-check`)

### Improvements:
-  [#2231](https://github.com/telstra/open-kilda/pull/2231) Add logging of stacktrace in DiscoveryBolt exception handling
-  [#2227](https://github.com/telstra/open-kilda/pull/2227) Add storm.yaml template config in confd
-  [#2235](https://github.com/telstra/open-kilda/pull/2235) Enable OVS virtual meters by default [**tests**]
-  [#2179](https://github.com/telstra/open-kilda/pull/2179) Add test: System is able to reroute flow in the correct order based on the priority field [**tests**] (Issue:  [#2144](https://github.com/telstra/open-kilda/issues/2144))

For the complete list of changes, check out [the commit log](https://github.com/telstra/open-kilda/compare/v1.16.2...v1.17.0).
