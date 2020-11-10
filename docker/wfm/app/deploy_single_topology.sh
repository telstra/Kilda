#!/usr/bin/env bash
# Copyright 2020 Telstra Open Source
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#

set -eu ${DEBUG:+-x}

STORM="${STORM:-/opt/storm/bin/storm}"

TOPOLOGY_NAME=${1}
TOPOLOGY_CONFIG=${2}
PREFIX=${3:-""}
SUFFIX=${4:-""}

TOPOLOGY_JAR=$(ls -1 /app/${TOPOLOGY_NAME}-storm-topology/libs/ | grep -v "\-original" | head -1)
COMMA_SEPARATED_DEPENDENCY_LIST=""

for DEPENDENCY_JAR in $(ls -1 /app/${TOPOLOGY_NAME}-storm-topology/dependency-jars/);
do
    COMMA_SEPARATED_DEPENDENCY_LIST="${COMMA_SEPARATED_DEPENDENCY_LIST:+$COMMA_SEPARATED_DEPENDENCY_LIST,}/app/${TOPOLOGY_NAME}-storm-topology/dependency-jars/${DEPENDENCY_JAR}"
done

MAIN_CLASS=$(grep 'Main-Class' /app/${TOPOLOGY_NAME}-storm-topology/build.gradle  | awk -F ':' '{ print $2}' | awk -F "'" '{ print $2 }')

"${STORM}" \
    jar /app/${TOPOLOGY_NAME}-storm-topology/libs/${TOPOLOGY_JAR} \
    ${MAIN_CLASS} \
    --jars \
    "${COMMA_SEPARATED_DEPENDENCY_LIST}" \
    --name ${PREFIX}${TOPOLOGY_NAME}${SUFFIX} \
    ${TOPOLOGY_CONFIG}

