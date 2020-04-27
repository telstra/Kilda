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

ENV PACKAGE kafka_2.12-2.0.0

WORKDIR /tmp/

RUN    wget -q https://archive.apache.org/dist/kafka/2.0.0/$PACKAGE.tgz \
    && wget -q https://archive.apache.org/dist/kafka/2.0.0/$PACKAGE.tgz.md5 \
    && sed 's/\ //g' $PACKAGE.tgz.md5 > $PACKAGE.tmp.md5 \
    && awk -F ":" '{print $2 " " $1}' $PACKAGE.tmp.md5 > $PACKAGE.tgz.md5 \
    && md5sum -c $PACKAGE.tgz.md5 \
    && tar -xzf $PACKAGE.tgz --directory /opt/ \
    && ln -s /opt/$PACKAGE /opt/kafka \
    && rm -rfv /tmp/*

WORKDIR /opt/kafka/

COPY kafka-conf/server.properties /opt/kafka/config/server.properties
COPY kafka-conf/run_and_configure.sh /opt/kafka/bin/
