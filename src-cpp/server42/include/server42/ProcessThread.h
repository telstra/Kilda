#pragma once

#include <dpdk/rte_ring.h>

#include <pcapplusplus/DpdkDevice.h>
#include <pcapplusplus/DpdkDeviceList.h>

class ProcessThread : public pcpp::DpdkWorkerThread
{
private:
    bool m_Stop;
    uint32_t m_CoreId;
    bool not_initilized;
    rte_ring* m_rx_ring;
    uint16_t m_port;

public:
    // c'tor
    ProcessThread(rte_ring *pRing, uint16_t port);

    // d'tor (does nothing)
    ~ProcessThread() { }

    // implement abstract method

    // start running the worker thread
    bool run(uint32_t coreId);

    // ask the worker thread to stop
    void stop();

    // get worker thread core ID
    uint32_t getCoreId() const;

    uint32_t getCoreId();
};
