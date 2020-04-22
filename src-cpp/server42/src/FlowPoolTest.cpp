#define BOOST_TEST_MODULE server42 tests
#include <boost/test/unit_test.hpp>

#include "FlowPool.h"
#include "statistics.pb.h"

class BasicAllocator {
public:
    typedef int* value_t;

    static  value_t allocate(int v) {
        return new int(v);
    }

    static void dealocate(value_t v) {
        delete(v);
    }
};


BOOST_AUTO_TEST_CASE( flow_pool_basic_add )
{

    org::openkilda::FlowPool<BasicAllocator> flow;

    pcpp::Packet newPacket(64);

    flow.add_flow("test-01", BasicAllocator::allocate(100));
    flow.add_flow("test-02", BasicAllocator::allocate(1000));
    flow.remove_flow("test-01");

    BOOST_TEST( flow.table.size() == 1 );
}

#define C1M 150
//#define C1M 3

BOOST_AUTO_TEST_CASE( flow_pool_basic_add_1m )
{
    org::openkilda::FlowPool<BasicAllocator> flow;
    for (uint32_t i = 0; i<C1M; ++i) {
        std::stringstream ss;
        ss << "test-"<< i;
        flow.add_flow(ss.str(), BasicAllocator::allocate(i));
    }

    std::cout << flow.table.size();

    BOOST_TEST( flow.table.size() == C1M);

    for (uint32_t i = 0; i<C1M; ++i) {
        std::stringstream ss;
        ss << "test-"<< i;
        flow.remove_flow(ss.str());
        //std::cout << flow.table.size() << "\n" << std::flush;
    }

    BOOST_TEST( flow.table.size() == 0);
}

BOOST_AUTO_TEST_CASE( flow_pool_basic_iteration_1m ) {

    org::openkilda::FlowPool<BasicAllocator> flow;
    for (uint32_t i = 0; i<C1M; ++i) {
        std::stringstream ss;
        ss << "test-"<< i;
        flow.add_flow(ss.str(), BasicAllocator::allocate(i));
    }

    int **start = flow.table.data();
    int **end = flow.table.data() + flow.table.size();

    for (int**pos = start;pos < end;pos += 64) {
        //m_Device->sendPackets(pos, std::min(64L, end - pos));
        std::cout << **pos << "-" << std::min(64L, end - pos) << "\n" << std::flush;

    }
}


BOOST_AUTO_TEST_CASE( pb_test ) {

    const int buff_size = 256;
    uint8_t buff[buff_size];

    auto start_time = std::chrono::time_point_cast<std::chrono::milliseconds>(
            std::chrono::high_resolution_clock::now());

    for (uint32_t i = 0; i < 2000000; i++) {
        org::openkilda::server42::stats::messaging::flowrtt::FlowLatencyPacket flowLatencyPacket;
        flowLatencyPacket.set_flow_id("payload->flow_id");
        flowLatencyPacket.set_t0(i*2);
        flowLatencyPacket.set_t1(i*3);
        flowLatencyPacket.set_packet_id(i*4);
        //std::cout << flowLatencyPacket.ByteSizeLong();
        flowLatencyPacket.SerializePartialToArray(buff, buff_size);
    }

    auto end_time = std::chrono::time_point_cast<std::chrono::milliseconds>(
                        std::chrono::high_resolution_clock::now());
                auto duration = end_time - start_time;
                std::cout << duration.count() << "\n" << std::flush;
}