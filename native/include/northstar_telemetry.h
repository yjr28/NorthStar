#ifndef NORTHSTAR_TELEMETRY_H
#define NORTHSTAR_TELEMETRY_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct {
    double cpu_percent;
    uint64_t memory_used_bytes;
    uint64_t memory_total_bytes;
    double load_1m;
    uint64_t uptime_seconds;
    unsigned int process_count;
} northstar_snapshot;
int northstar_collect(northstar_snapshot *out);
#ifdef __cplusplus
}
#endif
#endif
