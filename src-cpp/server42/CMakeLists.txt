cmake_minimum_required(VERSION 3.14.3)
project(server42_mk3)
include(ExternalProject)

set(CMAKE_CXX_STANDARD 17)

# DPDK Build


ExternalProject_Add(dpdk_download
        URL http://fast.dpdk.org/rel/dpdk-19.08.2.tar.xz
        URL_HASH SHA1=e203bbe12aeebb638c1eb320c4abeb423b3e7aec
        PREFIX dpdk
        CONFIGURE_COMMAND ${CMAKE_MAKE_PROGRAM} CONFIG_RTE_LIBRTE_ETHDEV_DEBUG=y CONFIG_RTE_LIBEAL_USE_HPET=y config T=x86_64-native-linuxapp-gcc
        BUILD_COMMAND ${CMAKE_MAKE_PROGRAM} CONFIG_RTE_LIBRTE_ETHDEV_DEBUG=y
        INSTALL_COMMAND mkdir -p ${CMAKE_SOURCE_DIR}/lib ${CMAKE_SOURCE_DIR}/include/dpdk
        COMMAND cp -r -T -L <SOURCE_DIR>/build/include ${CMAKE_SOURCE_DIR}/include/dpdk/
        COMMAND cp -r -T <SOURCE_DIR>/build/lib/ ${CMAKE_SOURCE_DIR}/lib
        BUILD_IN_SOURCE 1
        )

ExternalProject_Get_property(dpdk_download SOURCE_DIR)
set(DPDK_SOURCE_DIR "${SOURCE_DIR}")
message("-${DPDK_SOURCE_DIR}")

set(dpdk_components
        pmd_bond pmd_vmxnet3_uio pmd_virtio pmd_enic pmd_i40e pmd_fm10k pmd_ixgbe net pmd_e1000 pmd_ring pmd_af_packet
        ethdev eal mbuf mempool ring kvargs hash cmdline pci bus_pci bus_vdev mempool_ring
         pmd_bnxt
        )

#set(dpdk_components
#        net ethdev eal mbuf mempool ring kvargs hash cmdline pci bus_pci bus_vdev mempool_ring bus_dpaa mempool_dpaa
#
#              pmd_af_packet pmd_ark pmd_atlantic pmd_avf pmd_avp pmd_axgbe pmd_bbdev_null pmd_bnxt pmd_bond pmd_caam_jr
#                pmd_crypto_scheduler pmd_cxgbe pmd_dpaa pmd_dpaa2 pmd_dpaa2_cmdif pmd_dpaa2_event pmd_dpaa2_qdma pmd_dpaa2_sec
#              pmd_dpaa_event pmd_dpaa_sec pmd_dsw_event pmd_e1000 pmd_ena pmd_enetc pmd_enic pmd_failsafe pmd_fm10k pmd_i40e
#        pmd_ice pmd_ifc pmd_ifpga_rawdev pmd_ixgbe pmd_kni pmd_lio pmd_netvsc pmd_nfp pmd_null pmd_null_crypto
#        pmd_octeontx pmd_octeontx_crypto pmd_octeontx_ssovf pmd_octeontx_zip pmd_opdl_event pmd_qat pmd_qede
#        pmd_ring pmd_sfc_efx pmd_skeleton_event pmd_skeleton_rawdev pmd_softnic pmd_sw_event pmd_tap
#        pmd_thunderx_nicvf pmd_vdev_netvsc pmd_vhost pmd_virtio pmd_virtio_crypto pmd_vmxnet3_uio
#        )


#set(dpdk_components
#        bus_dpaa bus_pci bus_vdev cmdline eal ethdev hash kvargs mbuf mempool mempool_dpaa mempool_ring net pci ring
#        pmd_af_packet pmd_ark pmd_atlantic pmd_avf pmd_avp pmd_axgbe pmd_bbdev_null pmd_bnxt pmd_bond pmd_caam_jr
#        pmd_crypto_scheduler pmd_cxgbe pmd_dpaa pmd_dpaa2 pmd_dpaa2_cmdif pmd_dpaa2_event pmd_dpaa2_qdma pmd_dpaa2_sec
#        pmd_dpaa_event pmd_dpaa_sec pmd_dsw_event pmd_e1000 pmd_ena pmd_enetc pmd_enic pmd_failsafe pmd_fm10k pmd_i40e
#        pmd_ice pmd_ifc pmd_ifpga_rawdev pmd_ixgbe pmd_kni pmd_lio pmd_netvsc pmd_nfp pmd_null pmd_null_crypto
#        pmd_octeontx pmd_octeontx_crypto pmd_octeontx_ssovf pmd_octeontx_zip pmd_opdl_event pmd_qat pmd_qede
#        pmd_ring pmd_sfc_efx pmd_skeleton_event pmd_skeleton_rawdev pmd_softnic pmd_sw_event pmd_tap
#        pmd_thunderx_nicvf pmd_vdev_netvsc pmd_vhost pmd_virtio pmd_virtio_crypto pmd_vmxnet3_uio
#        )

#set(dpdk_components
#        bus_pci bus_vdev cmdline eal ethdev hash kvargs mbuf mempool mempool_ring net pci ring
#        pmd_af_packet pmd_ark pmd_atlantic pmd_avf pmd_avp pmd_axgbe pmd_bbdev_null pmd_bnxt pmd_bond
#        pmd_crypto_scheduler pmd_cxgbe
#        pmd_dpaa_event pmd_dpaa_sec pmd_dsw_event pmd_e1000  pmd_enetc pmd_enic pmd_failsafe pmd_fm10k pmd_i40e
#        pmd_ice  pmd_ifpga_rawdev pmd_ixgbe pmd_kni pmd_lio pmd_netvsc pmd_nfp pmd_null pmd_null_crypto
#        pmd_octeontx pmd_octeontx_crypto pmd_octeontx_ssovf pmd_octeontx_zip pmd_opdl_event pmd_qat pmd_qede
#        pmd_ring pmd_sfc_efx pmd_skeleton_event pmd_skeleton_rawdev pmd_sw_event pmd_tap
#        pmd_thunderx_nicvf pmd_vdev_netvsc pmd_vhost pmd_virtio pmd_virtio_crypto pmd_vmxnet3_uio
#        )

foreach(dpdk_component ${dpdk_components})
    add_library(dpdk_${dpdk_component} STATIC IMPORTED)
    set_property(TARGET dpdk_${dpdk_component} PROPERTY IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/lib/librte_${dpdk_component}.a)
    add_dependencies(dpdk_${dpdk_component} dpdk_download)
    list(APPEND dpdk_libraries "dpdk_${dpdk_component}")
endforeach()

set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)
find_library(numa numa)
find_library(dl dl)
target_link_libraries(dpdk_eal INTERFACE Threads::Threads numa dl)

target_link_libraries(dpdk_mbuf INTERFACE dpdk_eal)

include_directories(include/dpdk)

# PcapPlusPlus Build

ExternalProject_Add(pcapplusplus_download
    URL https://github.com/seladb/PcapPlusPlus/releases/download/v19.12/pcapplusplus-19.12-source-linux.tar.gz
    URL_HASH SHA1=009c51d4389e9d5937ad133b73265ad176eee9c9
    PREFIX pcapplusplus
    DEPENDS dpdk_download
    CONFIGURE_COMMAND cd PcapPlusPlus && ./configure-linux.sh --use-immediate-mode --dpdk --dpdk-home ${DPDK_SOURCE_DIR}
    PATCH_COMMAND patch --binary PcapPlusPlus/Pcap++/src/DpdkDeviceList.cpp < ${CMAKE_SOURCE_DIR}/src/DpdkDeviceList.cpp.patch01
    BUILD_COMMAND cd PcapPlusPlus && ${CMAKE_MAKE_PROGRAM}
    UPDATE_COMMAND ""
    INSTALL_COMMAND mkdir -p ${CMAKE_SOURCE_DIR}/lib ${CMAKE_SOURCE_DIR}/include/pcapplusplus
    COMMAND cp -r -T <SOURCE_DIR>/PcapPlusPlus/Dist/header ${CMAKE_SOURCE_DIR}/include/pcapplusplus
    COMMAND cp <SOURCE_DIR>/PcapPlusPlus/Dist/libPcap++.a   ${CMAKE_SOURCE_DIR}/lib/
    COMMAND cp <SOURCE_DIR>/PcapPlusPlus/Dist/libPacket++.a ${CMAKE_SOURCE_DIR}/lib/
    COMMAND cp <SOURCE_DIR>/PcapPlusPlus/Dist/libCommon++.a ${CMAKE_SOURCE_DIR}/lib/
    BUILD_IN_SOURCE 1
    )


set(pcapplusplus_components Pcap++ Packet++ Common++)

foreach(pcapplusplus_component ${pcapplusplus_components})
    add_library(pcapplusplus_${pcapplusplus_component} STATIC IMPORTED)
    set_property(TARGET pcapplusplus_${pcapplusplus_component} PROPERTY IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/lib/lib${pcapplusplus_component}.a)
    add_dependencies(pcapplusplus_${pcapplusplus_component} pcapplusplus_download)
    list(APPEND pcapplusplus_libraries "pcapplusplus_${pcapplusplus_component}")
endforeach()

target_link_libraries(pcapplusplus_Pcap++ INTERFACE ${dpdk_libraries})

# ZeroMQ Build

ExternalProject_Add(zeromq_download
    PREFIX zeromq
    URL https://github.com/zeromq/libzmq/releases/download/v4.3.1/zeromq-4.3.1.tar.gz
    URL_HASH SHA1=6cce22d830eaf95feff7cab00744df13ad7ab7f3
    INSTALL_COMMAND mkdir -p ${CMAKE_SOURCE_DIR}/lib ${CMAKE_SOURCE_DIR}/include/zeromq
    COMMAND cp -r -T -L <SOURCE_DIR>/include ${CMAKE_SOURCE_DIR}/include/zeromq/
    COMMAND cp -r -T <BINARY_DIR>/lib/ ${CMAKE_SOURCE_DIR}/lib/
    )

add_library(zeromq STATIC IMPORTED)
set_property(TARGET zeromq PROPERTY IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/lib/libzmq.a)
add_dependencies(zeromq zeromq_download)

# cppzmq Build

set(PC_LIBZMQ_LIBRARY_DIRS ${CMAKE_SOURCE_DIR}/lib/)

ExternalProject_Add(cppzmq_download
    PREFIX cppzmq
    URL https://github.com/zeromq/cppzmq/archive/v4.3.0.tar.gz
    URL_HASH SHA1=afc87286b15b035dbabcb99ef410143c3a24685c
    DEPENDS zeromq
    CONFIGURE_COMMAND ""
    BUILD_COMMAND ""
    INSTALL_COMMAND mkdir -p ${CMAKE_SOURCE_DIR}/lib ${CMAKE_SOURCE_DIR}/include/zeromq
    COMMAND cp <SOURCE_DIR>/zmq.hpp ${CMAKE_SOURCE_DIR}/include/zeromq/
    COMMAND cp <SOURCE_DIR>/zmq_addon.hpp ${CMAKE_SOURCE_DIR}/include/zeromq/
)

include_directories(include/zeromq)

# protobuf Build

ExternalProject_Add(
    protobuf-external
    PREFIX protobuf
    URL https://github.com/protocolbuffers/protobuf/releases/download/v3.8.0/protobuf-cpp-3.8.0.tar.gz
    URL_HASH SHA1=a2204357cc9b843b963768650aa6713a418f8e5d
    #BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/protobuf
    CMAKE_CACHE_ARGS
    #"-DCMAKE_BUILD_TYPE:STRING=${CMAKE_BUILD_TYPE}"
    "-Dprotobuf_BUILD_TESTS:BOOL=OFF"
    "-Dprotobuf_BUILD_EXAMPLES:BOOL=OFF"
    "-Dprotobuf_WITH_ZLIB:BOOL=OFF"
    "-DCMAKE_CXX_COMPILER:STRING=${CMAKE_CXX_COMPILER}"
    # other project specific parameters
    SOURCE_SUBDIR cmake
    STEP_TARGETS build
    INSTALL_COMMAND mkdir -p ${CMAKE_SOURCE_DIR}/lib ${CMAKE_SOURCE_DIR}/include ${CMAKE_SOURCE_DIR}/bin
    COMMAND cp -r <SOURCE_DIR>/src/google/ ${CMAKE_SOURCE_DIR}/include
    COMMAND cp <BINARY_DIR>/libprotobuf.a ${CMAKE_SOURCE_DIR}/lib/
    COMMAND cp <BINARY_DIR>/protoc ${CMAKE_SOURCE_DIR}/bin/
)

add_library(protobuf STATIC IMPORTED)
set_property(TARGET protobuf PROPERTY IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/lib/libprotobuf.a)

# boost section

ExternalProject_Add(boost_download
    PREFIX boost
    URL https://dl.bintray.com/boostorg/release/1.71.0/source/boost_1_71_0.tar.gz
    URL_HASH SHA256=96b34f7468f26a141f6020efb813f1a2f3dfb9797ecf76a7d7cbd843cc95f5bd
    UPDATE_COMMAND ""
    CONFIGURE_COMMAND ./bootstrap.sh --prefix=${CMAKE_SOURCE_DIR}
    BUILD_COMMAND ./b2 link=static install
    BUILD_IN_SOURCE true
    INSTALL_COMMAND ""
    INSTALL_DIR ${BOOST_ROOT_DIR}
)

set(BOOST_ROOT ${CMAKE_SOURCE_DIR})

set(_Boost_STACKTRACE_BASIC_HEADERS     "boost/stacktrace.hpp")
set(_Boost_STACKTRACE_BACKTRACE_HEADERS "boost/stacktrace.hpp")
set(_Boost_STACKTRACE_ADDR2LINE_HEADERS "boost/stacktrace.hpp")
set(_Boost_STACKTRACE_NOOP_HEADERS      "boost/stacktrace.hpp")


find_package(Boost
        1.71.0
        COMPONENTS atomic log test_exec_monitor stacktrace_basic stacktrace_backtrace)

if(NOT ${Boost_FOUND})
    #rerun cmake in initial build
    #will update cmakecache/project files on first build
    #so you may have to reload project after first build
    add_custom_target(RescanBoost ${CMAKE_COMMAND} ${CMAKE_SOURCE_DIR} DEPENDS boost_download)
else()
    #Rescan becomes a dummy target after first build
    #this prevents cmake from rebuilding cache/projects on subsequent builds
    add_custom_target(RescanBoost)
endif()

find_package(Backtrace REQUIRED)


# Project section
include_directories(include)
include_directories(include/server42)


add_custom_command(
        OUTPUT ${CMAKE_SOURCE_DIR}/src/control.pb.cc ${CMAKE_SOURCE_DIR}/src/control.pb.h
        MAIN_DEPENDENCY ${CMAKE_SOURCE_DIR}/include/server42/control.proto
        COMMAND ${CMAKE_SOURCE_DIR}/bin/protoc -I=${CMAKE_SOURCE_DIR}/include/server42/ --cpp_out=${CMAKE_SOURCE_DIR}/src/ --python_out=${CMAKE_SOURCE_DIR}/src/ ${CMAKE_SOURCE_DIR}/include/server42/control.proto
        DEPENDS protobuf
        )

add_custom_command(
        OUTPUT ${CMAKE_SOURCE_DIR}/src/statistics.pb.cc ${CMAKE_SOURCE_DIR}/src/statistics.pb.h
        MAIN_DEPENDENCY ${CMAKE_SOURCE_DIR}/include/server42/statistics.proto
        COMMAND ${CMAKE_SOURCE_DIR}/bin/protoc -I=${CMAKE_SOURCE_DIR}/include/server42/ --cpp_out=${CMAKE_SOURCE_DIR}/src/ --python_out=${CMAKE_SOURCE_DIR}/src/ ${CMAKE_SOURCE_DIR}/include/server42/statistics.proto
        DEPENDS protobuf
)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -Wall -Wextra -Wno-unused-parameter -ggdb -g -msse -msse2 -msse3 -msse4.1")
#set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ggdb -g2 -no-pie")

set(dpdk_libraries_link
        -Wl,--whole-archive ${dpdk_libraries} -Wl,--no-whole-archive)

#set(CMAKE_BUILD_TYPE Debug)

add_executable(server42_mk3 src/server42.cpp src/ReadThread.cpp src/WriteThread.cpp src/ProcessThread.cpp src/control.pb.cc src/statistics.pb.cc)
target_link_libraries(server42_mk3 ${dpdk_libraries_link} ${pcapplusplus_libraries} zeromq protobuf Boost::filesystem ${Backtrace_LIBRARIES})
add_dependencies(server42_mk3 ${dpdk_libraries} ${pcapplusplus_libraries} zeromq cppzmq_download protobuf-external boost_download)
set_target_properties (server42_mk3 PROPERTIES COMPILE_FLAGS ${CMAKE_CXX_FLAGS})

add_executable(server42_mk3_test src/FlowPoolTest.cpp src/ReadThread.cpp src/WriteThread.cpp src/control.pb.cc src/statistics.pb.cc)
target_link_libraries(server42_mk3_test ${dpdk_libraries_link} ${pcapplusplus_libraries} zeromq protobuf Boost::test_exec_monitor ${Backtrace_LIBRARIES})
add_dependencies(server42_mk3_test ${dpdk_libraries} ${pcapplusplus_libraries} zeromq cppzmq_download protobuf-external boost_download)
set_target_properties (server42_mk3_test PROPERTIES COMPILE_FLAGS ${CMAKE_CXX_FLAGS})
