#include "northstar_telemetry.h"
#include <stdio.h>
#include <string.h>
#include <sys/sysinfo.h>
#include <time.h>

typedef struct { unsigned long long idle; unsigned long long total; } cpu_sample;

static int read_cpu(cpu_sample *s) {
    FILE *fp = fopen("/proc/stat", "r");
    if (!fp) return -1;
    char label[8] = {0};
    unsigned long long user=0,nice=0,system=0,idle=0,iowait=0,irq=0,softirq=0,steal=0;
    int n = fscanf(fp, "%7s %llu %llu %llu %llu %llu %llu %llu %llu",
        label,&user,&nice,&system,&idle,&iowait,&irq,&softirq,&steal);
    fclose(fp);
    if (n < 5 || strcmp(label, "cpu") != 0) return -1;
    s->idle = idle + iowait;
    s->total = user + nice + system + idle + iowait + irq + softirq + steal;
    return 0;
}

static unsigned int read_process_count(void) {
    FILE *fp = fopen("/proc/loadavg", "r");
    if (!fp) return 0;
    double a=0,b=0,c=0; unsigned int running=0,total=0;
    int n = fscanf(fp, "%lf %lf %lf %u/%u", &a,&b,&c,&running,&total);
    fclose(fp);
    return n == 5 ? total : 0;
}

int northstar_collect(northstar_snapshot *out) {
    if (!out) return -1;
    cpu_sample before={0}, after={0};
    if (read_cpu(&before) != 0) return -1;
    struct timespec delay = {.tv_sec=0, .tv_nsec=120000000L};
    nanosleep(&delay, NULL);
    if (read_cpu(&after) != 0) return -1;

    unsigned long long td = after.total - before.total;
    unsigned long long id = after.idle - before.idle;
    out->cpu_percent = td == 0 ? 0.0 : 100.0 * (double)(td-id) / (double)td;

    struct sysinfo info;
    if (sysinfo(&info) != 0) return -1;
    uint64_t unit = info.mem_unit;
    out->memory_total_bytes = (uint64_t)info.totalram * unit;
    out->memory_used_bytes = out->memory_total_bytes - (uint64_t)info.freeram * unit;
    out->load_1m = (double)info.loads[0] / 65536.0;
    out->uptime_seconds = (uint64_t)info.uptime;
    out->process_count = read_process_count();
    return 0;
}
