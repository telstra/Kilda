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

ARG base_image=kilda/base-ubuntu
FROM ${base_image}

ADD BUILD/server42-stats/libs/server42-stats.jar /app/
ADD BUILD/server42-control/libs/server42-control.jar /app/
ADD BUILD/server42-control-storm-stub/libs/server42-control-storm-stub.jar /app/
ADD BUILD/server42-control-server-stub/libs/server42-control-server-stub.jar /app/

WORKDIR /app
CMD ["java", "-XX:+PrintFlagsFinal", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap", "-jar", "server42-control.jar"]
