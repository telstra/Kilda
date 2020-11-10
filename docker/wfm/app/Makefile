# WFM topology management

TOPOLOGY := network flowhs stats isllatency opentsdb portstate nbworker ping reroute swmanager floodlightrouter connecteddevices server42-control

TOPOLOGY_JAR := target/WorkflowManager-1.0-SNAPSHOT-jar-with-dependencies.jar
TOPOLOGY_CONFIG := topology.properties
TOPOLOGY_GREEN_CONFIG := topology.green.properties
TOPOLOGY_BLUE_CONFIG := topology.blue.properties

STORM := /opt/storm/bin/storm

status_location := topology-state

deploy_targets := $(addprefix deploy-,$(TOPOLOGY))
deploy_blue_targets := $(addprefix deploy-,$(addsuffix -blue,$(TOPOLOGY)))
deploy_green_targets := $(addprefix deploy-,$(addsuffix -green,$(TOPOLOGY)))

kill_targets := $(addprefix kill-,$(TOPOLOGY))
kill_blue_targets := $(addprefix kill-,$(addsuffix -blue,$(TOPOLOGY)))
kill_green_targets := $(addprefix kill-,$(addsuffix -green,$(TOPOLOGY)))

deploy_topology_name = $(patsubst %-green,%,$(patsubst %-blue,%,$(patsubst deploy-%,%,$@)))
kill_topology_name = $(patsubst %-green,%,$(patsubst %-blue,%,$(patsubst kill-%,%,$@)))
deployment_mode = $(lastword $(subst -, _,$(filter %-green %-blue, $@)))

deploy-all: alive-list $(addsuffix .alive,$(addprefix $(status_location)/$(prefix),$(TOPOLOGY)))
deploy-all-blue: alive-list $(addsuffix -blue.alive,$(addprefix $(status_location)/$(prefix),$(TOPOLOGY)))
deploy-all-green: alive-list $(addsuffix -green.alive,$(addprefix $(status_location)/$(prefix),$(TOPOLOGY)))
kill-all: $(kill_targets)
kill-all-blue: $(kill_blue_targets)
kill-all-green: $(kill_green_targets)


$(status_location)/$(prefix)%.alive:
	$(MAKE) deploy-$(patsubst $(status_location)/$(prefix)%.alive,%,$@)
	touch $@

$(deploy_targets):
	./deploy_single_topology.sh "$(deploy_topology_name)" "${TOPOLOGY_CONFIG}" "$(prefix)" "$(deployment_mode)"

$(deploy_blue_targets):
	./deploy_single_topology.sh "$(deploy_topology_name)" "${TOPOLOGY_BLUE_CONFIG}" "$(prefix)" "$(deployment_mode)"

$(deploy_green_targets):
	./deploy_single_topology.sh "$(deploy_topology_name)" "${TOPOLOGY_GREEN_CONFIG}" "$(prefix)" "$(deployment_mode)"

$(kill_targets) $(kill_blue_targets) $(kill_green_targets): alive-list
	if [ -e $(status_location)/$(prefix)$(kill_topology_name)$(deployment_mode).alive ]; \
	then ./kill-topology.sh $(prefix)$(kill_topology_name)$(deployment_mode); fi

alive-list:
	mkdir -p $(status_location)
	rm -f $(status_location)/*.alive
	for t in $$($(STORM) list | sed -n '/^-----/,$$ {s: \+.*$$::; p}' | tail -n+2); do touch $(status_location)/$$t.alive; done

debug-logging-%: alive-list $(status_location)/$(prefix)%.alive
	./enable-debug-logging.sh $*

.PHONY: deploy-all kill-all alive-list
.PHONY: $(deploy_targets) $(deploy_blue_targets) $(deploy_green_targets) $(kill_targets) $(kill_blue_targets) $(kill_green_targets)
