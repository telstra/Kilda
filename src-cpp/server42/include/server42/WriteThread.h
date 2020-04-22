#pragma once

#include <mutex>
#include <pcapplusplus/DpdkDevice.h>
#include <pcapplusplus/DpdkDeviceList.h>
#include "FlowPool.h"

class WriteThread : public pcpp::DpdkWorkerThread
{
private:
    pcpp::DpdkDevice* m_Device;
    bool m_Stop;
    uint32_t m_CoreId;
    org::openkilda::flow_pool_t& m_pool;
    std::mutex& m_pool_guard;
public:
    // c'tor
    WriteThread(pcpp::DpdkDevice* device, org::openkilda::flow_pool_t&, std::mutex& );

    // d'tor (does nothing)
    ~WriteThread() { }

    // implement abstract method

    // start running the worker thread
    bool run(uint32_t coreId);

    // ask the worker thread to stop
    void stop();

    // get worker thread core ID
    uint32_t getCoreId() const;
};
