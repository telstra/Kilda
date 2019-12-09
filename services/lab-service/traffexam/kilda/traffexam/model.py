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

import collections
import enum
import ipaddress
import itertools
import json
import socket
import uuid
import weakref

import pyroute2

from kilda.traffexam import exc


class TargetIface(object):
    @classmethod
    def new_from_cli(cls, name):
        try:
            value = cls(name)
        except ValueError as e:
            raise exc.InvalidTargetIfaceError(name, e.args[0])
        return value

    def __init__(self, name):
        self.name = name

        ip = pyroute2.IPRoute()
        idx = ip.link_lookup(ifname=self.name)
        if not idx:
            raise ValueError('network interface not found.'.format(name), name)
        self.index = idx[0]


class BindAddress(object):
    @classmethod
    def new_from_cli(cls, raw):
        try:
            address, port = raw.rsplit(':', 1)
        except ValueError:
            raise exc.InvalidBindAddressError(
                    raw, 'expect address as HOST:PORT pair')

        try:
            value = cls(address, port)
        except ValueError as e:
            raise exc.InvalidBindAddressError(raw, str(e))
        return value

    def __init__(self, address, port):
        self.address = address
        self.port = self.convert_port(port)

    @staticmethod
    def convert_address(address):
        try:
            address = socket.gethostbyname(address)
        except socket.gaierror as e:
            raise ValueError(
                    'Unresolvable host name {!r}: {}'.format(address, e))
        return address

    @staticmethod
    def convert_port(port):
        try:
            port = int(port)
        except ValueError:
            pass
        return port


class Default(object):
    def __init__(self, value, produce=False, override_none=True):
        self._resolve_cache = weakref.WeakKeyDictionary()
        self.value = value
        self.produce = produce
        self.override_none = override_none

    def __get__(self, instance, owner):
        if instance is None:
            return self

        value = self.value
        if self.produce:
            value = value()

        setattr(instance, self._resolve_name(owner), value)
        return value

    def is_filled(self, instance):
        name = self._resolve_name(type(instance))
        data = vars(instance)
        return name in data

    def _resolve_name(self, owner):
        try:
            return self._resolve_cache[owner]
        except KeyError:
            pass

        for name in dir(owner):
            try:
                attr = getattr(owner, name)
            except AttributeError:
                continue
            if attr is not self:
                continue
            break
        else:
            raise RuntimeError(
                '{!r} Unable to resolve bounded name (UNREACHABLE)'.format(
                    self))

        self._resolve_cache[owner] = name
        return name


class Abstract(object):
    pack_exclude = frozenset()

    def __init__(self, **fields):
        cls = type(self)
        extra = set(fields)
        for name in fields:
            try:
                attr = getattr(cls, name)
            except AttributeError:
                continue

            extra.remove(name)

            if isinstance(attr, Default) and attr.override_none and fields[name] is None:
                continue
            setattr(self, name, fields[name])

        if extra:
            raise TypeError('{!r} got unknown arguments: "{}"'.format(
                self, '", "'.join(sorted(extra))))

    def __str__(self):
        return '<{}:{}>'.format(
                type(self).__name__,
                json.dumps(self.pack(), sort_keys=True, cls=JSONEncoder))

    def pack(self):
        payload = vars(self).copy()

        cls = type(self)
        for name in dir(cls):
            if name.startswith('_'):
                continue
            if name in payload:
                continue

            attr = getattr(cls, name)
            if not isinstance(attr, Default):
                continue
            payload[name] = getattr(self, name)

        for name in tuple(payload):
            if not name.startswith('_') and name not in self.pack_exclude:
                continue
            del payload[name]

        return payload


class IdMixin(Abstract):
    idnr = Default(uuid.uuid1, produce=True)


class NetworkIface(Abstract):
    index = Default(None)
    vlan_tag = Default(None)
    pack_exclude = frozenset(('index', ))

    def __init__(self, name, **fields):
        super().__init__(**fields)
        self.name = name

    def get_ipdb_key(self):
        key = self.index
        if key is None:
            key = self.name
        return key


class VLAN(Abstract):
    iface = Default(None)
    parent = Default(None)

    def __init__(self, tag, **fields):
        super().__init__(**fields)
        if isinstance(tag, collections.Sequence) and len(tag) == 1:
            tag = tag[0]
        self.tag = tag

    def set_iface(self, iface):
        self.iface = iface
        return self


class IpAddress(IdMixin, Abstract):
    iface = Default(None)
    prefix = Default(None)

    pack_exclude = frozenset(('iface', ))

    def __init__(self, address, **fields):
        super().__init__(**fields)
        if not self.prefix:
            self.address, self.prefix = self.unpack_cidr(address)
        else:
            self.address = address
        self.network = ipaddress.IPv4Network(
                '{}/{}'.format(self.address, self.prefix), strict=False)
        self._ports = PortQueue(6000, 7000)

    def pack(self):
        payload = super().pack()
        payload.pop('network')
        payload['vlan'] = self.iface.vlan_tag
        return payload

    def alloc_port(self):
        return next(self._ports)

    def free_port(self, port):
        self._ports.free(port)

    @staticmethod
    def unpack_cidr(cidr):
        try:
            addr, prefix = cidr.rsplit('/', 1)
            prefix = int(prefix, 10)
        except ValueError:
            raise ValueError(
                'Invalid address {!r}, expect ipv4/prefix value'.format(cidr))
        return addr, prefix


class _Endpoint(IdMixin, Abstract):
    bind_address = Default(None)
    proc = None

    pack_exclude = frozenset(('proc', ))

    def set_proc(self, proc):
        self.proc = proc

    def pack(self):
        payload = super().pack()

        kind = endpoint_type_map[type(self)]
        payload['type'] = kind.value
        if self.bind_address:
            payload['bind_address'] = self.bind_address.idnr

        return payload


class ConsumerEndpoint(_Endpoint):
    bind_port = Default(None)

    def __init__(self, bind_address, **fields):
        super().__init__(**fields)
        self.bind_address = bind_address


class ProducerEndpoint(_Endpoint):
    bandwidth = Default(1024)
    burst_pkt = Default(0)
    time = Default(10)
    use_udp = Default(False)

    def __init__(self, remote_address, **fields):
        super().__init__(**fields)
        self.remote_address = remote_address

        if not self.time:
            raise ValueError('Invalid time: {!r}'.format(self.time))


class EndpointAddress(Abstract):
    def __init__(self, address, port, **fields):
        super().__init__(**fields)
        self.address = address
        self.port = port


class EndpointKind(enum.Enum):
    producer = 'producer'
    consumer = 'consumer'


endpoint_klass_map = {
    EndpointKind.producer: ProducerEndpoint,
    EndpointKind.consumer: ConsumerEndpoint}
endpoint_type_map = {v: k for k, v in endpoint_klass_map.items()}


class PortQueue(collections.Iterator):
    def __init__(self, lower=1024, upper=65535):
        self.lower = lower
        self.upper = upper
        self.counter = itertools.count(lower)
        self.released = set()

    def __next__(self):
        try:
            item = self.released.pop()
        except KeyError:
            item = next(self.counter)
            if self.upper <= item:
                raise StopIteration
        return item

    def free(self, item):
        if not self.lower <= item < self.upper:
            raise ValueError
        self.released.add(item)


class LLDPPush(Abstract):
    port_description = None
    system_name = None
    system_description = None

    def __init__(self, mac_address, port_number, chassis_id, ttl, **fields):
        super().__init__(**fields)
        self.mac_address = mac_address
        self.port_number = port_number
        self.chassis_id = chassis_id
        self.time_to_live = ttl


class UDPPush(Abstract):
    def __init__(self, src_mac_address, dst_mac_address, src_ip, src_port, dst_ip, dst_port, eth_type, **fields):
        super().__init__(**fields)
        self.src_mac_address = src_mac_address
        self.dst_mac_address = dst_mac_address
        self.src_ip = src_ip
        self.src_port = src_port
        self.dst_ip = dst_ip
        self.dst_port = dst_port
        self.eth_type = eth_type


class JSONEncoder(json.JSONEncoder):
    def default(self, o):
        if isinstance(o, Abstract):
            value = o.pack()
        elif isinstance(o, uuid.UUID):
            value = str(o)
        else:
            value = super().default(o)
        return value
