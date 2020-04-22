#include "server42/ReadThread.h"
#include "server42/Payload.h"
#include "statistics.pb.h"
#include <thread>

#include <pcapplusplus/UdpLayer.h>
#include <pcapplusplus/PayloadLayer.h>
#include <netinet/in.h>
#include <zmq.hpp>
#include <rte_branch_prediction.h>

ReadThread::ReadThread(pcpp::DpdkDevice *device, uint32_t queue_id, rte_ring *pRing) :
        m_Device(device), m_Stop(true), m_CoreId(MAX_NUM_OF_CORES + 1),
        m_QueueId(queue_id), not_initilized(true), m_rx_ring(pRing) {

}


bool ReadThread::run(uint32_t coreId) {
    // Register coreId for this worker
    m_CoreId = coreId;
    m_Stop = false;

    // initialize a mbuf packet array of size 32
    pcpp::MBufRawPacket statidMbufArr[32];

    // initialize a mbuf packet array of size 32
    pcpp::MBufRawPacket *mbufArr[32] = {};

    // initialize a mbuf packet array of size 32
    rte_mbuf *rtembufArr[32] = {};

    // we don't want allocate memory in pcpp::DpdkDevice::receivePackets
    for (int i = 0; i < 32; ++i) {
        mbufArr[i] = &statidMbufArr[i];
        mbufArr[i]->setFreeMbuf(false);
    }

    while (!m_Stop) {
        const uint32_t numOfPackets = m_Device->receivePackets(mbufArr, 32, m_QueueId);

        if (unlikely(numOfPackets == 0)) {
            continue;
        }

        for (uint32_t i = 0; i < numOfPackets; ++i) {
            rtembufArr[i] = mbufArr[i]->getMBuf();
        }

        uint32_t enqueued = rte_ring_sp_enqueue_bulk(m_rx_ring, reinterpret_cast<void *const *>(rtembufArr), numOfPackets,
                                            nullptr);

        if (unlikely(enqueued == 0)) {
            for (uint32_t i = 0; i < numOfPackets; ++i) {
                std::cerr << "ring is full packet drop\n";
                mbufArr[i]->setFreeMbuf(true);
                mbufArr[i]->clear();
                mbufArr[i]->setFreeMbuf(false);
            }
        }

    }
    return true;
}

void ReadThread::stop() {
    m_Stop = true;
}

uint32_t ReadThread::getCoreId() const {
    return m_CoreId;
}

