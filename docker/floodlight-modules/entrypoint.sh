#!/bin/bash
# Copyright 2017 Telstra Open Source
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
set -e
extra_params=${@:2}

if [ "$1" = 'floodlight' ]; then
  exec java -XX:+PrintFlagsFinal -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap \
    -Dlogback.configurationFile=/app/logback.xml ${extra_params} -cp "/app/floodlight.jar:/app/floodlight-modules.jar:/app/dependency-jars/*" \
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=50505 \
    net.floodlightcontroller.core.Main -cf /app/floodlightkilda.properties
fi

if [ "$1" = 'floodlightStats' ]; then
  exec java -XX:+PrintFlagsFinal -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap \
    -Dlogback.configurationFile=/app/logback.xml ${extra_params} -cp "/app/floodlight.jar:/app/floodlight-modules.jar:/app/dependency-jars/*" \
    net.floodlightcontroller.core.Main -cf /app/floodlightkildaStats.properties
fi

exec "$@"
