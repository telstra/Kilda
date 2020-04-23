#include <vector>
#include <unistd.h>
#include <sstream>
#include <iostream>
#include <stdio.h>
#include <stdlib.h>
#include <thread>
#include <time.h>
#include <mutex>


#include <zeromq/zmq.hpp>
#include <pcapplusplus/SystemUtils.h>
#include <pcapplusplus/DpdkDeviceList.h>
#include <pcapplusplus/TablePrinter.h>

#include <boost/stacktrace.hpp>
#include <boost/filesystem.hpp>
#include <pcapplusplus/EthLayer.h>
#include <pcapplusplus/VlanLayer.h>
#include <pcapplusplus/UdpLayer.h>
#include <pcapplusplus/IPv4Layer.h>
#include <Payload.h>
#include <netinet/in.h>
#include <pcapplusplus/PayloadLayer.h>
#include <rte_memory.h>
#include <rte_ring.h>
#include <ProcessThread.h>
#include <rte_debug.h>
//#include <rte_ethdev.h>

#include "server42/ReadThread.h"
#include "server42/WriteThread.h"
#include "control.pb.h"

/*
 The size of the mbuf pool size dictates how many packets can be handled by the application at the same time. For example: if
 pool size is 1023 it means that no more than 1023 packets can be handled or stored in application memory at every point in time
*/
#define MBUF_POOL_SIZE 16*1024-1
//#define MBUF_POOL_SIZE 2097152-1
#define DEVICE_ID_1 0

#define COLLECT_STATS_EVERY_SEC 15

void segv_handler(int signum) {
    ::signal(signum, SIG_DFL);
    boost::stacktrace::safe_dump_to("./backtrace.dump");
    ::raise(SIGABRT);
}

// Keep running flag
bool keepRunning = true;

void onApplicationInterrupted(void *cookie) {
    keepRunning = false;
    printf("\nShutting down...\n");
}


void printStats(pcpp::DpdkDevice *rxDevice) {
    pcpp::DpdkDevice::DpdkDeviceStats stats;
    rxDevice->getStatistics(stats);

    //rxDevice->

    std::vector<std::string> columnNames;
    columnNames.push_back(" ");
    columnNames.push_back("Total Packets");
    columnNames.push_back("Packets/sec");
    columnNames.push_back("Bytes");
    columnNames.push_back("Bits/sec");

    std::vector<int> columnLengths;
    columnLengths.push_back(10);
    columnLengths.push_back(15);
    columnLengths.push_back(15);
    columnLengths.push_back(15);
    columnLengths.push_back(15);

    pcpp::TablePrinter printer(columnNames, columnLengths);

    std::stringstream totalRx;
    totalRx << "rx" << "|" << stats.aggregatedRxStats.packets << "|" << stats.aggregatedRxStats.packetsPerSec << "|"
            << stats.aggregatedRxStats.bytes << "|" << stats.aggregatedRxStats.bytesPerSec * 8;
    printer.printRow(totalRx.str(), '|');

    std::stringstream totalTx;
    totalTx << "tx" << "|" << stats.aggregatedTxStats.packets << "|" << stats.aggregatedTxStats.packetsPerSec << "|"
            << stats.aggregatedTxStats.bytes << "|" << stats.aggregatedTxStats.bytesPerSec * 8;
    printer.printRow(totalTx.str(), '|');
    printer.closeTable();

    columnNames.clear();
    columnNames.push_back(" ");
    columnNames.push_back("Packets Dropeed By HW");
    columnNames.push_back("Erroneous Packets");
    columnNames.push_back("MbufAlocFailed");

    std::vector<int> columnLengths2;
    columnLengths2.push_back(10);
    columnLengths2.push_back(15);
    columnLengths2.push_back(15);
    columnLengths2.push_back(15);

    pcpp::TablePrinter printer2(columnNames, columnLengths2);
    std::stringstream errorRx;
    errorRx << "rx" << "|" << stats.rxPacketsDropeedByHW << "|" << stats.rxErroneousPackets << "|"
            << stats.rxMbufAlocFailed;
    printer2.printRow(errorRx.str(), '|');
}

void add_flow(org::openkilda::server42::control::messaging::flowrtt::AddFlow &addFlow,
              org::openkilda::flow_pool_t &flow_pool,
              pcpp::DpdkDevice *device) {

    pcpp::Packet newPacket(64);

    pcpp::MacAddress dst(addFlow.flow().dst_mac());
    pcpp::MacAddress src(device->getMacAddress());
    pcpp::EthLayer newEthernetLayer(src, dst);
    newPacket.addLayer(&newEthernetLayer);

    if (org::openkilda::server42::control::messaging::flowrtt::Flow_EncapsulationType_VXLAN ==
        addFlow.flow().transit_encapsulation_type()) {
        printf("VXLAN as transit is not supported, skip add_flow'\n");
        return;
    }

    if (org::openkilda::server42::control::messaging::flowrtt::Flow_EncapsulationType_VXLAN ==
        addFlow.flow().encapsulation_type()) {
        printf("VXLAN is not supported, skip add_flow'\n");
        return;
    }

    pcpp::VlanLayer newVlanLayer(addFlow.flow().transit_tunnel_id(), false, 1, PCPP_ETHERTYPE_IP);
    if (addFlow.flow().transit_tunnel_id()) {
        newPacket.addLayer(&newVlanLayer);
    }

    pcpp::VlanLayer newVlanLayer2(addFlow.flow().tunnel_id(), false, 1, PCPP_ETHERTYPE_IP);
    if (addFlow.flow().tunnel_id()) {
        newPacket.addLayer(&newVlanLayer2);
    }

    pcpp::IPv4Layer newIPLayer(pcpp::IPv4Address(std::string("192.168.0.1/24")),
                               pcpp::IPv4Address(std::string("192.168.1.1")));

    newIPLayer.getIPv4Header()->timeToLive = 128;
    newPacket.addLayer(&newIPLayer);

    pcpp::UdpLayer newUdpLayer(addFlow.flow().udp_src_port(), 58168);
    newPacket.addLayer(&newUdpLayer);

    org::openkilda::Payload payload {};

    using time_stamp = std::chrono::time_point<std::chrono::high_resolution_clock,
            std::chrono::seconds>;

    time_stamp ts = std::chrono::time_point_cast<std::chrono::seconds>(std::chrono::high_resolution_clock::now());

    payload.t0 = htonl(ts.time_since_epoch().count());
    payload.t1 = htonl(ts.time_since_epoch().count() + rand() % 1000);

    size_t length = addFlow.flow().flow_id().copy(payload.flow_id, sizeof(payload.flow_id) - 1);
    payload.flow_id[length] = '\0';

    payload.direction = addFlow.flow().direction();

    pcpp::PayloadLayer payloadLayer(reinterpret_cast<uint8_t *>(&payload), sizeof(payload), false);
    newPacket.addLayer(&payloadLayer);
    newPacket.computeCalculateFields();

    flow_pool.add_flow(addFlow.flow().flow_id(),
                       org::openkilda::MBufAllocator::allocate(newPacket.getRawPacket(),
                                                               device));
}


void remove_flow(org::openkilda::server42::control::messaging::flowrtt::RemoveFlow &remove_flow,
                 org::openkilda::flow_pool_t &flow_pool) {
    flow_pool.remove_flow(remove_flow.flow().flow_id());
}

int main(int argc, char *argv[]) {
    GOOGLE_PROTOBUF_VERIFY_VERSION;

    ::signal(SIGSEGV, &segv_handler);
    ::signal(SIGABRT, &segv_handler);

    if (boost::filesystem::exists("./backtrace.dump")) {
        // there is a backtrace
        std::ifstream ifs("./backtrace.dump");

        boost::stacktrace::stacktrace st = boost::stacktrace::stacktrace::from_dump(ifs);
        std::cout << "Previous run crashed:\n" << st << std::endl;

        // cleaning up
        ifs.close();
        boost::filesystem::remove("./backtrace.dump");
    }

    // Register the on app close event handler
    pcpp::ApplicationEventHandler::getInstance().onApplicationInterrupted(onApplicationInterrupted, NULL);

    // Initialize DPDK
    pcpp::CoreMask coreMaskToUse = pcpp::getCoreMaskForAllMachineCores();
    pcpp::DpdkDeviceList::initDpdk(coreMaskToUse, MBUF_POOL_SIZE);

    // Find DPDK devices
    pcpp::DpdkDevice *device1 = pcpp::DpdkDeviceList::getInstance().getDeviceByPort(DEVICE_ID_1);
    if (device1 == NULL) {
        printf("Cannot find device1 with port '%d'\n", DEVICE_ID_1);
        return 1;
    }

    pcpp::DpdkDevice::DpdkDeviceConfiguration config = pcpp::DpdkDevice::DpdkDeviceConfiguration(
            /*receiveDescriptorsNumber*/ 2048,
            /*transmitDescriptorsNumber*/ 2048,
            /*flushTxBufferTimeout*/ 100,
            /*rssHashFunction*/ 0);

    // Open DPDK devices
    if (!device1->openMultiQueues(1, 1, config)) {
        printf("Couldn't open device1 #%d, PMD '%s'\n", device1->getDeviceId(), device1->getPMDName().c_str());
        return 1;
    }

    std::mutex flow_pool_guard;
    org::openkilda::flow_pool_t flow_pool;

    rte_ring *rx_ring = rte_ring_create(
            "rx-ring",
            4194304 / 2 / 2 / 2 / 2,
            SOCKET_ID_ANY,
            RING_F_SP_ENQ);

    if (rx_ring == NULL) {
        rte_panic("Cannot create ring\n");
    }

    // Create worker threads
    std::vector<pcpp::DpdkWorkerThread *> workers;
    workers.push_back(new WriteThread(device1, flow_pool, flow_pool_guard));
    workers.push_back(new ReadThread(device1, 0, rx_ring));
    workers.push_back(new ProcessThread(rx_ring, 5556));
//    workers.push_back(new ProcessThread(rx_ring, 5557));
//    workers.push_back(new ProcessThread(rx_ring, 5558));
//    workers.push_back(new ProcessThread(rx_ring, 5559));
//    workers.push_back(new ProcessThread(rx_ring, 5560));


    // Create core mask - use core 1 and 2 for the two threads
    int workersCoreMask = 0;

    for (int i = 1; i <= workers.size(); i++) {
        workersCoreMask = workersCoreMask | (1 << i);
    }

    // Start capture in async mode
    if (!pcpp::DpdkDeviceList::getInstance().startDpdkWorkerThreads(workersCoreMask, workers)) {
        printf("Couldn't start worker threads");
        return 1;
    }

    uint64_t counter = 0;
    int statsCounter = 1;


    //  Prepare our context and publisher
    zmq::context_t context(1);
    zmq::socket_t socket(context, ZMQ_REP);
    socket.bind("tcp://*:5555");

    // Keep running while flag is on
    while (keepRunning) {
        // Sleep for 1 second
        std::this_thread::sleep_for(std::chrono::seconds(1));

        try {
            zmq::message_t request;
            if (socket.recv(&request, ZMQ_DONTWAIT)) {
                org::openkilda::server42::control::messaging::flowrtt::CommandPacket command_packet;
                if (!command_packet.ParseFromArray(request.data(), request.size())) {
                    printf("Failed to parse CommandPacket.\n");
                } else {
                    switch (command_packet.type()) {
                        case org::openkilda::server42::control::messaging::flowrtt::CommandPacket_Type_ADD_FLOW: {
                            std::lock_guard<std::mutex> guard(flow_pool_guard);
                            printf("CommandPacket_Type_ADD_FLOW. size= %d\n", command_packet.command_size());
                            printf("flow before = %ld\n", flow_pool.table.size());
                            for (uint32_t i = 0; i < command_packet.command_size(); ++i) {
                                const google::protobuf::Any &any = command_packet.command(i);
                                org::openkilda::server42::control::messaging::flowrtt::AddFlow addFlow;
                                any.UnpackTo(&addFlow);
                                if (command_packet.command_size() < 5) {
                                    printf("CommandPacket_Type_ADD_FLOW. %s %ld\n",
                                           addFlow.flow().flow_id().c_str(),
                                           addFlow.flow().tunnel_id());
                                }
                                add_flow(addFlow, flow_pool, device1);
                            }
                            org::openkilda::server42::control::messaging::flowrtt::CommandPacketResponse response;
                            response.set_communication_id(command_packet.communication_id());
                            zmq::message_t message(response.ByteSizeLong());
                            response.SerializeToArray(message.data(), message.size());
                            socket.send(message);
                            printf("CommandPacketResponse. %s\n", response.DebugString().c_str());
                            printf("flow after = %ld\n", flow_pool.table.size());
                        }
                            break;
                        case org::openkilda::server42::control::messaging::flowrtt::CommandPacket_Type_REMOVE_FLOW: {

                            google::protobuf::Any any = command_packet.command().at(0);
                            org::openkilda::server42::control::messaging::flowrtt::RemoveFlow removeFlow;
                            any.UnpackTo(&removeFlow);
                            printf("CommandPacket_Type_REMOVE_FLOW. %s\n",
                                   removeFlow.flow().flow_id().c_str());

                            {
                                std::lock_guard<std::mutex> guard(flow_pool_guard);
                                remove_flow(removeFlow, flow_pool);
                            }

                            org::openkilda::server42::control::messaging::flowrtt::CommandPacketResponse response;
                            response.set_communication_id(command_packet.communication_id());
                            zmq::message_t message(response.ByteSizeLong());
                            response.SerializeToArray(message.data(), message.size());
                            socket.send(message);
                            printf("CommandPacketResponse. %s\n", response.DebugString().c_str());
                        }
                            break;
                        case org::openkilda::server42::control::messaging::flowrtt::CommandPacket_Type_CLEAR_FLOWS:
                            break;
                        case org::openkilda::server42::control::messaging::flowrtt::CommandPacket_Type_LIST_FLOWS:
                            break;
                        case org::openkilda::server42::control::messaging::flowrtt::CommandPacket_Type_PUSH_SETTINGS:
                            break;
                    }
                }
            }
        } catch (zmq::error_t &exception) {
            std::cerr
                    << "ZMQ Error " << exception.what() << "\n";
        } catch (std::exception &exception) {
            std::cerr << "Error " << exception.what() << "\n";

        }

        // Print stats every COLLECT_STATS_EVERY_SEC seconds
//        if (counter % COLLECT_STATS_EVERY_SEC == 0) {
//            // Clear screen and move to top left
//            const char clr[] = {27, '[', '2', 'J', '\0'};
//            const char topLeft[] = {27, '[', '1', ';', '1', 'H', '\0'};
//            printf("%s%s", clr, topLeft);
//
//            printf("device1 #%d, PMD '%s' MAC:'%s' \n", device1->getDeviceId(),
//                   device1->getPMDName().c_str(),
//                   device1->getMacAddress().toString().c_str());
//            printf("Mbuf: free %d in use %d \n", device1->getAmountOfFreeMbufs(),
//                   device1->getAmountOfMbufsInUse());
//
//            printf("\n\nStats #%d\n", statsCounter++);
//            printf("==========\n\n");
//
//            // Print stats of traffic going from Device1 to Device2
//            printf("\nDevice1 stats:\n\n");
//            printStats(device1);
//            printf("==========\n");
//            printf("ring: count %d, free count %d\n", rte_ring_count(rx_ring), rte_ring_free_count(rx_ring));
//            printf("==========\n\n");
//        }
//        counter++;
    }

    rte_ring_free(rx_ring);

    // Stop worker threads
    pcpp::DpdkDeviceList::getInstance().stopDpdkWorkerThreads();

    // Exit app with normal exit code
    return 0;
}
