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

ARG base_image=kilda/storm:latest
FROM ${base_image}

ENV DEBIAN_FRONTEND noninteractive

RUN pip install kafka
RUN echo "PATH=$PATH:/opt/storm/bin" >> ~/.bashrc

WORKDIR /app
ADD wfm/app /app
#ADD src/main/resources/topology.properties /app/

# Main config
COPY BUILD/base-storm-topology/resources/topology.properties /app/
COPY BUILD/base-storm-topology/resources/topology.green.properties /app/
COPY BUILD/base-storm-topology/resources/topology.blue.properties /app/



#connecteddevices
COPY BUILD/connecteddevices-storm-topology/libs/*               /app/connecteddevices-storm-topology/libs/
COPY BUILD/connecteddevices-storm-topology/build.gradle         /app/connecteddevices-storm-topology/



#floodlightrouter
COPY BUILD/floodlightrouter-storm-topology/libs/*               /app/floodlightrouter-storm-topology/libs/
COPY BUILD/floodlightrouter-storm-topology/build.gradle         /app/floodlightrouter-storm-topology/



#flowhs
COPY BUILD/flowhs-storm-topology/libs/*                         /app/flowhs-storm-topology/libs/
COPY BUILD/flowhs-storm-topology/build.gradle                   /app/flowhs-storm-topology/



#isllatency
COPY BUILD/isllatency-storm-topology/libs/*                     /app/isllatency-storm-topology/libs/
COPY BUILD/isllatency-storm-topology/build.gradle               /app/isllatency-storm-topology/



#nbworker
COPY BUILD/nbworker-storm-topology/libs/*                       /app/nbworker-storm-topology/libs/
COPY BUILD/nbworker-storm-topology/build.gradle                 /app/nbworker-storm-topology/



#network
COPY BUILD/network-storm-topology/libs/*                        /app/network-storm-topology/libs/
COPY BUILD/network-storm-topology/build.gradle                  /app/network-storm-topology/



#opentsdb
COPY BUILD/opentsdb-storm-topology/libs/*                       /app/opentsdb-storm-topology/libs/
COPY BUILD/opentsdb-storm-topology/build.gradle                 /app/opentsdb-storm-topology/



#ping
COPY BUILD/ping-storm-topology/libs/*                           /app/ping-storm-topology/libs/
COPY BUILD//ping-storm-topology/build.gradle                    /app/ping-storm-topology/



#portstate
COPY BUILD/portstate-storm-topology/libs/*                      /app/portstate-storm-topology/libs/
COPY BUILD/portstate-storm-topology/build.gradle                /app/portstate-storm-topology/



#reroute
COPY BUILD/reroute-storm-topology/libs/*                        /app/reroute-storm-topology/libs/
COPY BUILD/reroute-storm-topology/build.gradle                  /app/reroute-storm-topology/



#stats
COPY BUILD/stats-storm-topology/libs/*                          /app/stats-storm-topology/libs/
COPY BUILD/stats-storm-topology/build.gradle                    /app/stats-storm-topology/



#swmanager
COPY BUILD/swmanager-storm-topology/libs/*                      /app/swmanager-storm-topology/libs/
COPY BUILD/swmanager-storm-topology/build.gradle                /app/swmanager-storm-topology/


#server42 control
COPY BUILD/server42-control-storm-topology/libs/*                      /app/server42-control-storm-topology/libs/
COPY BUILD/server42-control-storm-topology/build.gradle                /app/server42-control-storm-topology/


CMD /app/entry-point.sh

RUN TZ=Australia/Melbourne date >> /container_baked_on.txt
