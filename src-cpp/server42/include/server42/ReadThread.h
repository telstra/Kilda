#pragma once

#include <pcapplusplus/DpdkDevice.h>
#include <pcapplusplus/DpdkDeviceList.h>
#include <rte_ring.h>

class ReadThread : public pcpp::DpdkWorkerThread
{
private:
    pcpp::DpdkDevice* m_Device;
    bool m_Stop;
    uint32_t m_CoreId;
    uint32_t m_QueueId;
    bool not_initilized;
    rte_ring* m_rx_ring;

public:
    // c'tor
    ReadThread(pcpp::DpdkDevice *device, uint32_t queue_id, rte_ring *pRing);

    // d'tor (does nothing)
    ~ReadThread() { }

    // implement abstract method

    // start running the worker thread
    bool run(uint32_t coreId);

    // ask the worker thread to stop
    void stop();

    // get worker thread core ID
    uint32_t getCoreId() const;
};

