# Copyright 2017 Telstra Open Source
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#

import functools
import pathlib
import operator

import nsenter
from scapy.all import IP, UDP, Ether, sendp
from scapy.contrib import lldp

from kilda.traffexam import context as context_module


class Abstract(context_module.ContextConsumer):
    def __call__(self, *args, **kwargs):
        raise NotImplementedError


class LLDPPush(Abstract):
    ns_fs_location = pathlib.Path('/var/run/netns')
    lldp_bcast_mac = '01:80:c2:00:00:0e'

    def __call__(self, iface, push_entry):
        pkt = self._encode(push_entry)

        ns_name = self.context.make_network_namespace_name()
        ns_fd = self.ns_fs_location / ns_name
        with nsenter.Namespace(str(ns_fd), 'net'):
            sendp(pkt, iface=iface.name, verbose=False, promisc=False)

    def _encode(self, entry):
        entries = (
            lldp.LLDPDUChassisID(
                subtype=lldp.LLDPDUChassisID.SUBTYPE_MAC_ADDRESS,
                id=entry.chassis_id),
            lldp.LLDPDUPortID(
                subtype=lldp.LLDPDUPortID.SUBTYPE_LOCALLY_ASSIGNED,
                id=entry.port_number),
            lldp.LLDPDUTimeToLive(ttl=entry.time_to_live))
        if entry.port_description:
            entries = entries + (lldp.LLDPDUPortDescription(description=entry.port_description),)
        if entry.system_name:
            entries = entries + (lldp.LLDPDUSystemName(system_name=entry.system_name),)
        if entry.system_description:
            entries = entries + (lldp.LLDPDUSystemDescription(description=entry.system_description),)

        entries = entries + (lldp.LLDPDUEndOfLLDPDU(),)
        payload = functools.reduce(operator.truediv, entries)

        return Ether(src=entry.mac_address, dst=self.lldp_bcast_mac) / payload


class UDPPush(Abstract):
    ns_fs_location = pathlib.Path('/var/run/netns')

    def __call__(self, iface, push_entry):
        data = "TEST"
        ether = Ether(src=push_entry.src_mac_address, dst=push_entry.dst_mac_address, type=push_entry.eth_type)
        ip = IP(src=push_entry.src_ip, dst=push_entry.dst_ip)
        udp = UDP(sport=push_entry.src_port, dport=push_entry.dst_port)
        pkt = ether / ip / udp / data

        ns_name = self.context.make_network_namespace_name()
        ns_fd = self.ns_fs_location / ns_name
        with nsenter.Namespace(str(ns_fd), 'net'):
            sendp(pkt, iface=iface.name, verbose=False, promisc=False)


class Adapter(object):
    def __init__(self, context):
        self.lldp_push = LLDPPush(context)
        self.udp_push = UDPPush(context)
