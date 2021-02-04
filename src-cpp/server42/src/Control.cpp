#include "Control.h"

#include <boost/log/trivial.hpp>

#include "control.pb.h"

#include "PacketGenerator.h"

namespace org::openkilda {

    using CommandPacket = server42::control::messaging::flowrtt::CommandPacket;
    using CommandPacketResponse = server42::control::messaging::flowrtt::CommandPacketResponse;
    using Command = server42::control::messaging::flowrtt::CommandPacket_Type;
    using AddFlow = server42::control::messaging::flowrtt::AddFlow;
    using RemoveFlow = server42::control::messaging::flowrtt::RemoveFlow;
    using Error = server42::control::messaging::flowrtt::Error;
    using Flow = server42::control::messaging::flowrtt::Flow;
    using ListFlowsFilter = server42::control::messaging::flowrtt::ListFlowsFilter;
    using ClearFlowsFilter = server42::control::messaging::flowrtt::ClearFlowsFilter;

    buffer_t trivial_response_from(const CommandPacket &command_packet) {
        CommandPacketResponse response;
        response.set_communication_id(command_packet.communication_id());
        std::vector<boost::uint8_t> result(response.ByteSizeLong());
        response.SerializeToArray(result.data(), result.size());
        return result;
    }

    buffer_t error_response_from(boost::int64_t communication_id, const std::string &what) {
        CommandPacketResponse response;
        response.set_communication_id(communication_id);
        Error error;
        error.set_what(what);
        response.add_error()->PackFrom(error);
        std::vector<boost::uint8_t> result(response.ByteSizeLong());
        response.SerializeToArray(result.data(), result.size());
        return result;
    }

    void add_flow(AddFlow &addFlow, flow_pool_t &flow_pool, pcpp::DpdkDevice *device) {

        FlowCreateArgument arg = {
                .flow_pool = flow_pool,
                .device = device,
                .dst_mac = addFlow.flow().dst_mac(),
                .tunnel_id = addFlow.flow().tunnel_id(),
                .inner_tunnel_id = addFlow.flow().inner_tunnel_id(),
                .transit_tunnel_id = addFlow.flow().transit_tunnel_id(),
                .udp_src_port = addFlow.flow().udp_src_port(),
                .flow_id = addFlow.flow().flow_id(),
                .direction = addFlow.flow().direction(),
                .hash = addFlow.flow().hash_code()
        };

        generate_and_add_packet_for_flow(arg);
    }


    void remove_flow(org::openkilda::server42::control::messaging::flowrtt::RemoveFlow &remove_flow,
                     org::openkilda::flow_pool_t &flow_pool) {
        flow_pool.remove_flow(make_flow_endpoint(remove_flow.flow().flow_id(), remove_flow.flow().direction()));
    }


    buffer_t clear_flows(const CommandPacket &command_packet, flow_pool_t &pool, std::mutex &flow_pool_guard) {
        CommandPacketResponse response;
        response.set_communication_id(command_packet.communication_id());

        if (command_packet.command_size() == 1) {
            ClearFlowsFilter filter;
            const google::protobuf::Any &any = command_packet.command(0);
            any.UnpackTo(&filter);
            auto flow_list = pool.get_metadata_db()->get_flow_from_switch(filter.dst_mac());
            if (flow_list.size() > 0) {
                std::lock_guard<std::mutex> guard(flow_pool_guard);
                for (auto f : flow_list) {
                    pool.remove_flow(f);
                }
            }
        } else {
            std::lock_guard<std::mutex> guard(flow_pool_guard);
            pool.clear();
        }
        return trivial_response_from(command_packet);
    }

    buffer_t get_list_flows(const CommandPacket &command_packet, flow_pool_t &pool) {
        CommandPacketResponse response;
        response.set_communication_id(command_packet.communication_id());

        if (command_packet.command_size() == 1) {
            ListFlowsFilter filter;
            const google::protobuf::Any &any = command_packet.command(0);
            any.UnpackTo(&filter);
            auto flow_list = pool.get_metadata_db()->get_flow_from_switch(filter.dst_mac());
            for (auto f : flow_list) {
                Flow flow;
                flow.set_flow_id(std::get<int(flow_endpoint_members::flow_id)>(f));
                flow.set_direction(std::get<int(flow_endpoint_members::direction)>(f));
                response.add_response()->PackFrom(flow);
            }
        } else {
            for (const flow_pool_t::flow_endpoint_t &flow_endpoint : pool.get_flowid_table()) {
                Flow flow;
                flow.set_flow_id(std::get<int(flow_endpoint_members::flow_id)>(flow_endpoint));
                flow.set_direction(std::get<int(flow_endpoint_members::direction)>(flow_endpoint));
                response.add_response()->PackFrom(flow);
            }
        }

        std::vector<boost::uint8_t> result(response.ByteSizeLong());
        response.SerializeToArray(result.data(), result.size());
        return result;
    }

    buffer_t handle_command_request(const void *data, int size, SharedContext &ctx) {
        CommandPacket command_packet;
        if (!command_packet.ParseFromArray(data, size)) {
            BOOST_LOG_TRIVIAL(error) << "Failed to parse CommandPacket";
            return error_response_from(0, "Failed to parse CommandPacket");
        } else {
            switch (command_packet.type()) {
                case Command::CommandPacket_Type_ADD_FLOW:
                case Command::CommandPacket_Type_REMOVE_FLOW: {
                    std::lock_guard<std::mutex> guard(ctx.flow_pool_guard);
                    for (int i = 0; i < command_packet.command_size(); ++i) {
                        const google::protobuf::Any &any = command_packet.command(i);
                        if (command_packet.type() == Command::CommandPacket_Type_ADD_FLOW) {
                            AddFlow addFlow;
                            any.UnpackTo(&addFlow);
                            add_flow(addFlow, ctx.flow_pool, ctx.primary_device);
                        } else {
                            RemoveFlow removeFlow;
                            any.UnpackTo(&removeFlow);
                            remove_flow(removeFlow, ctx.flow_pool);
                        }
                    }
                    return trivial_response_from(command_packet);
                }
                case Command::CommandPacket_Type_CLEAR_FLOWS:
                    return clear_flows(command_packet, ctx.flow_pool, ctx.flow_pool_guard);
                case Command::CommandPacket_Type_LIST_FLOWS:
                    return get_list_flows(command_packet, ctx.flow_pool);
                case Command::CommandPacket_Type_PUSH_SETTINGS:
                    // TODO: implement PUSH_SETTINGS
                    return trivial_response_from(command_packet);
            }
        }
    }


}