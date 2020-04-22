#pragma once

namespace org::openkilda {

struct Payload {
    int64_t t0;
    int64_t t1;
    char flow_id[32];
    bool direction;
};

}
