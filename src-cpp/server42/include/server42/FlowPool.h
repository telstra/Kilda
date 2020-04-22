#pragma once

#include <map>
#include <string>
#include <vector>
#include <list>
#include <pcapplusplus/DpdkDevice.h>
#include <boost/shared_ptr.hpp>

namespace org::openkilda {

    struct Location {
        size_t index;
    };

    template<class A>
    class FlowPool {
        typedef typename A::value_t value_t;
        typedef std::map<std::string, Location> locator_t;
        typedef std::vector<value_t> table_t;
        typedef std::vector<std::string> flowid_table_t;
        locator_t locator;
        flowid_table_t flowid_table;
    public:
        table_t table;

        explicit FlowPool() {
        }

        void add_flow(const std::string &flow_id, const value_t &raw_packet) {
            if (locator.find(flow_id) != locator.end()) {
                return;
            }
            locator[flow_id] = Location{table.size()};
            table.push_back(raw_packet);
            flowid_table.push_back(flow_id);
        }

        void remove_flow(const std::string &flow_id) {
            auto location_it = locator.find(flow_id);
            if (location_it == locator.end()) {
                return;
            }

            Location& location = location_it->second;

            value_t packet = table[location.index];

            table[location.index] = table.back();
            table.pop_back();

            locator[flowid_table.back()] = location;
            locator.erase(location_it);

            flowid_table[location.index] = flowid_table.back();
            flowid_table.pop_back();

            // free old mbuff
            A::dealocate(packet);
        }
    };

    class MBufAllocator {
    public:
        typedef pcpp::MBufRawPacket* value_t;

        static  value_t allocate(const pcpp::RawPacket* rawPacket, pcpp::DpdkDevice* device) {
            auto mbuf_raw_packet = new pcpp::MBufRawPacket();
            mbuf_raw_packet->initFromRawPacket(rawPacket, device);
            return mbuf_raw_packet;
        }

        static void dealocate(value_t v) {
            v->setFreeMbuf(true);
            v->clear();
            delete(v);
        }
    };

    typedef FlowPool<MBufAllocator> flow_pool_t;
}