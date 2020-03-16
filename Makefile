# 'make' will build the latest and try to run it.
default: build-latest run-dev

java_version := "1.8"
TASK := functionalTest

check-java-version:
	if  [ `java -version 2>&1 | awk -F '"' '/version/ { print $$2 }' | awk -F'.' '{ print $$1"."$$2 }'` != "$(java_version)" ]; then false; fi

build-base: build-lock-keeper update-props docker/storm/lib
	docker build -t kilda/base-ubuntu:latest docker/base/kilda-base-ubuntu/
	docker build -t kilda/zookeeper:latest docker/zookeeper
	docker build -t kilda/kafka:latest docker/kafka
	docker build -t kilda/hbase:latest docker/hbase
	docker build -t kilda/storm:latest docker/storm
	docker build -t kilda/neo4j:latest docker/neo4j
	docker build -t kilda/opentsdb:latest docker/opentsdb
	docker build -t kilda/logstash:latest docker/logstash
	docker build -t kilda/elasticsearch:latest docker/elasticsearch
	$(MAKE) -C src-python/lab-service find-python-requirements
	docker build -t kilda/base-lab-service:latest docker/base/kilda-base-lab-service/

build-lock-keeper:
	cp src-python/lock-keeper/* docker/lock-keeper/
	docker build -t kilda/lock-keeper:latest docker/lock-keeper/

.PHONY: build-lock-keeper

docker/storm/lib:
	docker/base/hacks/storm.requirements.download.sh

build-latest: build-base compile
	docker-compose build

run-dev:
	docker-compose up

up-test-mode:
	@echo ~~
	@echo ~~ Starting KILDA, and will print the status of Storm Topology deployments
	@echo ~~ Once the topology deployments are done, it should be safe to test
	@echo ~~
	@echo
	cp -n .env.example .env
	OK_TESTS="DISABLE_LOGIN" docker-compose -f docker-compose.yml -f docker-compose.test.yml up -d
	docker-compose logs -f wfm
	$(MAKE) -C tools/elk-dashboards

up-log-mode: up-test-mode
	docker-compose logs -f

# keeping run-test for backwards compatibility (documentation) .. should deprecate
run-test: up-log-mode

.PHONY: clean-docker-files
clean-docker-files:
	if [ -d docker/BUILD ]; then rm -rf docker/BUILD; fi
	if [ -f docker/base/kilda-base-lab-service/merged-requirements.txt ]; then rm docker/base/kilda-base-lab-service/merged-requirements.txt; fi
	if [ -f docker/base-lab-service/log.json ]; then rm docker/base-lab-service/log.json; fi

.PHONY: clean-sources
clean-sources:
	$(MAKE) -C services/src/openkilda-gui clean-java
	$(MAKE) -C src-python/lab-service/lab clean
	cd src-java && ./gradlew clean

compile: update-props check-java-version
	cd src-java && ./gradlew buildAndCopyArtifacts -PdestPath=../docker/BUILD -x test --info --stacktrace $(GRADLE_COMPILE_PARAMS)
	$(MAKE) -C src-python/lab-service/lab test
	$(MAKE) -C src-python/lab-service/lab deploy-wheel
	$(MAKE) -C src-python/lab-service/traffexam deploy-wheel
	$(MAKE) -C services/src/openkilda-gui build

.PHONY: unit
unit: update-props
	cd src-java && ./gradlew test --stacktrace

.PHONY: sonar
sonar: update-props
	cd src-java && ./gradlew test jacocoTestReport sonarqube --stacktrace

.PHONY: clean-test
clean-test:
	docker-compose -f docker-compose.yml -f docker-compose.test.yml down
	docker-compose rm -fv
	docker volume list -q | grep kilda | xargs -r docker volume  rm

.PHONY: clean
clean: clean-sources clean-test clean-docker-files

update-props:
	confd -onetime -confdir ./confd/ -backend file -file ./confd/vars/main.yaml -sync-only

update-props-dryrun:
	confd -onetime -confdir ./confd/ -backend file -file ./confd/vars/main.yaml -sync-only -noop

FUNC_TESTS = src-java/testing/functional-tests

$(FUNC_TESTS)/kilda.properties:
	cp $(FUNC_TESTS)/kilda.properties.example $(FUNC_TESTS)/kilda.properties

$(FUNC_TESTS)/topology.yaml:
	cp $(FUNC_TESTS)/src/test/resources/topology.yaml $(FUNC_TESTS)/topology.yaml

copy-test-props: $(FUNC_TESTS)/kilda.properties $(FUNC_TESTS)/topology.yaml

.PHONY: run-func-tests func-tests test-topology
run-func-tests:
	cd src-java && ./gradlew :functional-tests:$(TASK) --info $(PARAMS)

# EXAMPLES:
#   make func-tests  // run all functional tests
#   make func-tests PARAMS='--tests spec.links.**'  // run all tests from 'spec.links' package
#   make func-tests PARAMS='--tests LinkSpec'  // run all tests from 'LinkSpec' class
#   make func-tests PARAMS='--tests LinkSpec."Able to delete inactive link"'  // run a certain test from 'LinkSpec' class
# test-topology creates a virtual topology ready for testing, ensures that it is properly discovered
test-topology: TASK=runTest
test-topology: PARAMS=--tests HealthCheckSpecification
func-tests test-topology: copy-test-props run-func-tests
