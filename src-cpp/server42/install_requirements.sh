#!/usr/bin/env bash

sudo apt-get update -y && \
sudo apt-get install -y libpcap-dev gcc g++ libnuma-dev make python3.7 python3.7-dev && \
sudo update-alternatives --install /usr/bin/python python /usr/bin/python3.7 1000 && \
mkdir -p bin && \
mkdir -p tools/cmake && \
cd tools/cmake && \
wget -nc https://github.com/Kitware/CMake/releases/download/v3.14.5/cmake-3.14.5-Linux-x86_64.tar.gz && \
tar -xzvf cmake-3.14.5-Linux-x86_64.tar.gz && \
cd - && \
cd bin && \
ln -sf ../tools/cmake/cmake-3.14.5-Linux-x86_64/bin/cmake .
