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
import functools
import json
import multiprocessing.pool
import uuid
from wsgiref.simple_server import WSGIServer, WSGIRequestHandler

import bottle

from kilda.traffexam import exc
from kilda.traffexam import model

app = bottle.Bottle(autojson=False)
app.install(bottle.JSONPlugin(
        json_dumps=functools.partial(json.dumps, cls=model.JSONEncoder)))
config_key = 'traffexam.{}'.format


@app.route('/address', methpd='GET')
def address_list():
    context = get_context()
    return address_response(context.service.address.list())


@app.route('/address', method='POST')
def address_create():
    context = get_context()

    payload = bottle.request.json
    payload.pop('idnr', None)

    address = extract_payload_fields(payload, 'address')[0]
    vlan_tag = payload.pop('vlan', 0)
    if vlan_tag:
        iface = context.service.vlan.allocate_stack(vlan_tag).iface
    else:
        iface = None

    try:
        entity = model.IpAddress(address, iface=iface, **payload)
    except TypeError as e:
        return bottle.HTTPError(400, str(e))

    try:
        context.service.address.create(entity)
    except exc.ServiceCreateCollisionError as e:
        return bottle.HTTPError(400, str(e))

    bottle.response.status = 201
    return address_response(entity)


@app.route('/address/<idnr>', method='GET')
def address_read(idnr):
    idnr = unpack_idnr(idnr)
    return address_response(_address_lookup(idnr))


@app.route('/address/<idnr>', method='DELETE')
def address_delete(idnr):
    context = get_context()
    idnr = unpack_idnr(idnr)
    try:
        context.service.address.delete(idnr)
    except exc.ServiceLookupError:
        return bottle.HTTPError(404, 'Address does not exist.')
    bottle.response.status = 204


def address_response(payload):
    return format_response(payload, 'address', 'addresses')


@app.route('/address/<idnr>/lldp', method='PUT')
def address_emmit_lldp_packet(idnr):
    address = _address_lookup(unpack_idnr(idnr))
    payload = bottle.request.json
    if payload is None:
        payload = {}

    mac_address, port_id, chassis_id, ttl = extract_payload_fields(
        payload,
        'mac_address', 'port_number', "chassis_id", "time_to_live")
    extra = {}
    for f in ('port_description', 'system_name', 'system_description'):
        try:
            extra[f] = payload[f]
        except KeyError:
            pass

    try:
        push_entry = model.LLDPPush(mac_address, port_id, chassis_id, ttl, **extra)
        get_context().action.lldp_push(address.iface, push_entry)
    except ValueError as e:
        return bottle.HTTPError(400, 'Invalid LLDP payload - {}'.format(e))
    except Exception as e:
        return bottle.HTTPError(500, 'Unexpected error - {}'.format(e))

    return {
        'lldp_push': {
            'sent_packets': 1}}


@app.route('/address/<idnr>/udp', method='PUT')
def address_emmit_udp_packet(idnr):
    address = _address_lookup(unpack_idnr(idnr))
    payload = bottle.request.json
    if payload is None:
        payload = {}

    src_mac_address, dst_mac_address, src_ip, src_port, dst_ip, dst_port, eth_type = extract_payload_fields(
        payload,
        'src_mac_address', 'dst_mac_address', 'src_ip', 'src_port', "dst_ip", "dst_port", "eth_type")

    try:
        push_entry = model.UDPPush(src_mac_address, dst_mac_address, src_ip, src_port, dst_ip, dst_port, eth_type, **{})
        get_context().action.udp_push(address.iface, push_entry)
    except ValueError as e:
        return bottle.HTTPError(400, 'Invalid UDP payload - {}'.format(e))
    except Exception as e:
        return bottle.HTTPError(500, 'Unexpected error - {}'.format(e))

    return {
        'udp_push': {
            'sent_packets': 1}}


def _address_lookup(idnr):
    try:
        address = get_context().service.address.lookup(idnr)
    except exc.ServiceLookupError:
        raise bottle.HTTPError(404, 'Address does not exist.')
    return address


@app.route('/endpoint', method='GET')
def endpoint_list():
    context = get_context()
    return endpoint_response(context.service.endpoint.list())


@app.route('/endpoint', method='POST')
def endpoint_create():
    context = get_context()
    payload = bottle.request.json
    payload.pop('idnr', None)

    kind = extract_payload_fields(payload, 'type')[0]
    try:
        klass = model.EndpointKind(kind)
        klass = model.endpoint_klass_map[klass]
    except (TypeError, KeyError):
        return bottle.HTTPError(400, 'Invalid endpoint type {!r}'.format(kind))

    try:
        bind_address = payload.pop('bind_address', None)
        if bind_address:
            bind_address = unpack_idnr(bind_address)
            bind_address = context.service.address.lookup(bind_address)

        if klass is model.ConsumerEndpoint:
            if bind_address is None:
                raise bottle.HTTPError(400, "bind_address field is missing")
            entity = klass(bind_address, **payload)
        elif klass is model.ProducerEndpoint:
            address = extract_payload_fields(
                    payload, 'remote_address')[0]
            address, port = extract_payload_fields(address, 'address', 'port')
            entity = klass(
                    model.EndpointAddress(address, port),
                    bind_address=bind_address, **payload)
        else:
            raise bottle.HTTPError(500, 'Unreachable point have been reached!')
    except exc.ServiceLookupError as e:
        return bottle.HTTPError(400, 'Invalid resource reference: {}'.format(e))
    except TypeError as e:
        return bottle.HTTPError(400, str(e))

    context.service.endpoint.create(entity)

    bottle.response.status = 201
    return endpoint_response(entity)


@app.route('/endpoint/<idnr>', method='GET')
def endpoint_read(idnr):
    context = get_context()
    idnr = unpack_idnr(idnr)
    try:
        entity = context.service.endpoint.lookup(idnr)
    except exc.ServiceLookupError:
        return bottle.HTTPError(404, 'Endpoint does not exist.')
    return endpoint_response(entity)


@app.route('/endpoint/<idnr>', method='DELETE')
def endpoint_delete(idnr):
    context = get_context()
    idnr = unpack_idnr(idnr)
    try:
        context.service.endpoint.delete(idnr)
    except exc.ServiceLookupError:
        return bottle.HTTPError(404, 'Endpoint does not exist.')
    bottle.response.status = 204


@app.route('/endpoint/<idnr>/report', method='GET')
def endpoint_do_report(idnr):
    context = get_context()
    idnr = unpack_idnr(idnr)
    try:
        output = context.service.endpoint.get_report(idnr)
        if output:
            report, error = output
        else:
            report = error = None
        entity = context.service.endpoint.lookup(idnr)
    except exc.ServiceLookupError:
        return bottle.HTTPError(404, 'Endpoint does not exist')

    return {
        'report': report,
        'error': error,
        'status': entity.proc.returncode}


def endpoint_response(payload):
    return format_response(payload, 'endpoint', 'endpoints')


def format_response(payload, single, multiple):
    key = single
    if isinstance(payload, collections.Sequence):
        key = multiple
    return {key: payload}


def init(bind, context):
    app.config[config_key('context')] = context
    app.run(server=MTServer, host=bind.address, port=bind.port, thread_count=5)


def unpack_idnr(idnr):
    try:
        idnr = uuid.UUID(idnr)
    except ValueError:
        raise bottle.HTTPError(400, 'Invalid resource id')
    return idnr


def get_context():
    return bottle.request.app.config[config_key('context')]


def extract_payload_fields(payload, *fields):
    missing = set()
    found = []

    for name in fields:
        try:
            found.append(payload.pop(name))
        except KeyError:
            missing.add(name)

    if missing:
        raise bottle.HTTPError(400, 'Payload is lack of fields: "{}"'.format(
                '", "'.join(sorted(missing))))

    return found


# All that below is taken from https://github.com/RonRothman/mtwsgi
#
# Can make "normal" dependency doe to lack of "packaging" stuff in this repo.
#
# rev: a8f67cfc0d538714a612f78e39c9e1148725ea73
# license: The MIT License(MIT)

class ThreadPoolWSGIServer(WSGIServer):
    '''WSGI-compliant HTTP server.  Dispatches requests to a pool of threads.'''

    def __init__(self, thread_count=None, *args, **kwargs):
        '''If 'thread_count' == None, we'll use multiprocessing.cpu_count() threads.'''
        WSGIServer.__init__(self, *args, **kwargs)
        self.thread_count = thread_count
        self.pool = multiprocessing.pool.ThreadPool(self.thread_count)

    # Inspired by SocketServer.ThreadingMixIn.
    def process_request_thread(self, request, client_address):
        try:
            self.finish_request(request, client_address)
            self.shutdown_request(request)
        except:
            self.handle_error(request, client_address)
            self.shutdown_request(request)

    def process_request(self, request, client_address):
        self.pool.apply_async(self.process_request_thread,
                              args=(request, client_address))


class MTServer(bottle.ServerAdapter):
    def run(self, handler):
        thread_count = self.options.pop('thread_count', None)
        server = make_server(
                self.host, self.port, handler, thread_count, **self.options)
        server.serve_forever()


def make_server(host, port, app, thread_count=None,
                handler_class=WSGIRequestHandler):
    '''Create a new WSGI server listening on `host` and `port` for `app`'''
    httpd = ThreadPoolWSGIServer(thread_count, (host, port), handler_class)
    httpd.set_app(app)
    return httpd
