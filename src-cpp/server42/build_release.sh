#!/usr/bin/env bash

mkdir -p cmake-build-release
cd cmake-build-release
../bin/cmake -DCMAKE_BUILD_TYPE=Release -G "CodeBlocks - Unix Makefiles" ../
../bin/cmake --build . -- -j4
