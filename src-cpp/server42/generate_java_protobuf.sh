#!/usr/bin/env bash

if [ ! -f "src-java" ]; then
  echo "ERROR: The script must be run from the project directory"
  exit 1
fi

if [ -z $(docker images -q kilda/server42dpdk-protobuf:latest) ]; then
  cd src-cpp/server42/
  docker build -t kilda/server42dpdk-protobuf:latest . -f Dockerfile.protobuf
  cd -
fi

if [ ! -f "src-java/server42/server42-control-messaging/src/main/java/org/openkilda/server42/control/messaging/flowrtt/Control.java" ]; then
  docker run -it --rm \
  --user $(id -u):$(id -g) \
  -v $(pwd)/src-java/server42/server42-control-messaging/src/main/java:/src-java/server42/server42-control-messaging/src/main/java \
  -v $(pwd)/src-cpp/server42/src:/src-cpp/server42/src \
  kilda/server42dpdk-protobuf:latest \
  protoc --java_out src-java/server42/server42-control-messaging/src/main/java --proto_path src-cpp/server42/src \
  src-cpp/server42/src/control.proto
fi


if [ ! -f "src-java/server42/server42-stats-messaging/src/main/java/org/openkilda/server42/stats/messaging/flowrtt/Statistics.java" ]; then
  docker run -it --rm \
  --user $(id -u):$(id -g) \
  -v $(pwd)/src-java/server42/server42-stats-messaging/src/main/java:/src-java/server42/server42-stats-messaging/src/main/java \
  -v $(pwd)/src-cpp/server42/src:/src-cpp/server42/src \
  kilda/server42dpdk-protobuf:latest \
  protoc --java_out src-java/server42/server42-stats-messaging/src/main/java --proto_path src-cpp/server42/src \
  src-cpp/server42/src/statistics.proto
fi
