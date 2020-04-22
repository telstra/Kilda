#include "WriteThread.h"
#include "Payload.h"

#include <chrono>
#include <thread>

#include <stdlib.h>
#include <pcapplusplus/Packet.h>
#include <pcapplusplus/EthLayer.h>
#include <pcapplusplus/VlanLayer.h>
#include <pcapplusplus/IPv4Layer.h>
#include <pcapplusplus/TcpLayer.h>
#include <pcapplusplus/HttpLayer.h>
#include <pcapplusplus/UdpLayer.h>
#include <pcapplusplus/PayloadLayer.h>
#include <pcapplusplus/PcapFileDevice.h>
#include <netinet/in.h>
#include <FlowPool.h>
#include <sstream>
#include <iostream>
#include <rte_cycles.h>


WriteThread::WriteThread(pcpp::DpdkDevice *device, org::openkilda::flow_pool_t& pool, std::mutex& pool_guard) :
        m_Device(device), m_Stop(true), m_CoreId(MAX_NUM_OF_CORES + 1), m_pool(pool), m_pool_guard(pool_guard) {
}


bool WriteThread::run(uint32_t coreId) {
    // Register coreId for this worker
    m_CoreId = coreId;
    m_Stop = false;


    const uint64_t cycles_in_one_second = rte_get_timer_hz();
    const uint64_t cycles_in_500_ms = cycles_in_one_second / 2;
    // endless loop, until asking the thread to stop
    while (!m_Stop) {
        try {
            auto now = std::chrono::time_point_cast<std::chrono::milliseconds>(
                    std::chrono::high_resolution_clock::now());
            auto duration = now.time_since_epoch();
            long millis = std::chrono::duration_cast<std::chrono::milliseconds>(duration).count();
            std::cout
            << "tick " << millis / 1000 << "." << millis % 1000 << " "
            << "pool_size " << m_pool.table.size() << "\n"
            << std::flush;

            uint64_t start_tsc = rte_rdtsc();
            {
                std::lock_guard<std::mutex> guard(m_pool_guard);
                pcpp::MBufRawPacket **start = m_pool.table.data();
                pcpp::MBufRawPacket **end = start + m_pool.table.size();

                const int64_t chunk_size = 32L;
                uint_fast8_t error_count = 0;
                for (pcpp::MBufRawPacket **pos = start; pos < end; pos += chunk_size) {
                    uint16_t send = 0;
                    uint_fast8_t tries = 0;

                    pcpp::MBufRawPacket mbuf_send_buffer[32];
                    pcpp::MBufRawPacket *mbuf_send_buffer_p[32];

                    for (uint_fast8_t i = 0; i < std::min(chunk_size, end - pos); ++i) {
                        mbuf_send_buffer[i].initFromRawPacket(*(pos+i), m_Device);
                        mbuf_send_buffer_p[i] = &mbuf_send_buffer[i];
                        mbuf_send_buffer_p[i]->setFreeMbuf(true);
                    }


                    while (send != std::min(chunk_size, end - pos)) {
                        send = m_Device->sendPackets(mbuf_send_buffer_p, std::min(chunk_size, end - pos), 0, false);
                        if (send != std::min(chunk_size, end - pos)) {
                            if(++tries > 3) {
                                ++error_count;
                                break;
                            }
                        }
                    }
                    if (error_count > 5) {
                        std::cerr << "Errors while send packets, drop send try\n";
                        break;
                    }
                }
            }
            while (true) {
                uint64_t current_tsc = rte_rdtsc();
                if (unlikely(current_tsc - start_tsc >= cycles_in_500_ms)) {
                    break;
                }
            }
        } catch (std::exception &exception) {
            std::cerr << "Error " << exception.what() << "\n";
        } catch (...) {
            std::cerr << "unhandled Error\n";
        };

    }

    std::cerr << "Thread exit\n";

    return true;
}

void WriteThread::stop() {
    m_Stop = true;
}

uint32_t WriteThread::getCoreId() const {
    return m_CoreId;
}
