#!/usr/bin/env bash

mkdir -p cmake-build-debug
cd cmake-build-debug
../bin/cmake -DCMAKE_BUILD_TYPE=Debug -G "CodeBlocks - Unix Makefiles" ../
../bin/cmake --build . -- -j4
