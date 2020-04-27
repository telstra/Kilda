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

ENV PACKAGE apache-storm-1.2.1

WORKDIR /tmp/

RUN    wget -q https://archive.apache.org/dist/storm/apache-storm-1.2.1/$PACKAGE.tar.gz \
    && wget -q https://archive.apache.org/dist/storm/apache-storm-1.2.1/$PACKAGE.tar.gz.md5 \
    && sed 's/\ //g' $PACKAGE.tar.gz.md5 > $PACKAGE.tmp.md5 \
    && awk -F ":" '{print $2 " " $1}' $PACKAGE.tmp.md5 > $PACKAGE.tar.gz.md5 \
    && md5sum -c $PACKAGE.tar.gz.md5 \
    && tar -xzf $PACKAGE.tar.gz --directory /opt/ \
    && ln -s /opt/$PACKAGE /opt/storm \
    && rm -rfv /tmp/*

WORKDIR /opt/storm/

COPY storm.yaml /opt/storm/conf/storm.yaml
COPY log4j2 /opt/storm/log4j2
COPY lib/*.jar /opt/storm/lib/
