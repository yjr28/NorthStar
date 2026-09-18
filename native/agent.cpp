#include "northstar_telemetry.h"
#include <netdb.h>
#include <sys/socket.h>
#include <sys/utsname.h>
#include <unistd.h>
#include <chrono>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>

static std::string env_or(const char *key, const std::string &fallback) {
    const char *v = std::getenv(key);
    return v && *v ? v : fallback;
}

static std::string uuid_v4() {
    std::random_device rd; unsigned char b[16];
    for (auto &x : b) x = static_cast<unsigned char>(rd());
    b[6] = (b[6] & 0x0F) | 0x40; b[8] = (b[8] & 0x3F) | 0x80;
    std::ostringstream o; o << std::hex << std::setfill('0');
    for (int i=0;i<16;i++) { o << std::setw(2) << static_cast<int>(b[i]); if(i==3||i==5||i==7||i==9)o<<'-'; }
    return o.str();
}

static int http_post(const std::string &host,const std::string &port,const std::string &path,const std::string &body) {
    addrinfo hints{}; hints.ai_family=AF_UNSPEC; hints.ai_socktype=SOCK_STREAM;
    addrinfo *res=nullptr;
    if(getaddrinfo(host.c_str(),port.c_str(),&hints,&res)!=0) throw std::runtime_error("getaddrinfo failed");
    int fd=-1;
    for(addrinfo *it=res;it;it=it->ai_next){
        fd=socket(it->ai_family,it->ai_socktype,it->ai_protocol);
        if(fd<0) continue;
        if(connect(fd,it->ai_addr,it->ai_addrlen)==0) break;
        close(fd); fd=-1;
    }
    freeaddrinfo(res);
    if(fd<0) throw std::runtime_error("connect failed");
    std::ostringstream q;
    q<<"POST "<<path<<" HTTP/1.1\r\nHost: "<<host<<"\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "<<body.size()<<"\r\n\r\n"<<body;
    std::string wire=q.str(); size_t sent=0;
    while(sent<wire.size()){ ssize_t n=send(fd,wire.data()+sent,wire.size()-sent,0); if(n<=0){close(fd);throw std::runtime_error("send failed");} sent+=(size_t)n; }
    char buf[256]={0}; ssize_t n=recv(fd,buf,sizeof(buf)-1,0); close(fd);
    if(n<=0) throw std::runtime_error("empty response");
    std::istringstream r(std::string(buf,(size_t)n)); std::string version; int status=0; r>>version>>status; return status;
}

int main() {
    try {
        std::string api_host=env_or("NORTHSTAR_HOST","127.0.0.1");
        std::string api_port=env_or("NORTHSTAR_PORT","8080");
        std::string host_id=env_or("NORTHSTAR_HOST_ID",uuid_v4());
        int interval=std::stoi(env_or("NORTHSTAR_INTERVAL_SECONDS","10"));
        bool once=env_or("NORTHSTAR_ONCE","0")=="1";

        char hn[256]={0}; gethostname(hn,sizeof(hn)-1);
        utsname uts{}; uname(&uts);

        std::ostringstream registration;
        registration<<"{\"id\":\""<<host_id<<"\",\"hostname\":\""<<hn<<"\",\"agentVersion\":\"0.1.0\",\"osName\":\""<<uts.sysname<<"\",\"architecture\":\""<<uts.machine<<"\"}";
        int rs=http_post(api_host,api_port,"/api/v1/hosts",registration.str());
        if(rs!=200 && rs!=201){ std::cerr<<"registration failed: HTTP "<<rs<<"\n"; return 2; }

        do {
            northstar_snapshot s{};
            if(northstar_collect(&s)!=0){ std::cerr<<"telemetry collection failed\n"; return 3; }
            std::ostringstream body;
            body<<std::fixed<<std::setprecision(2)
                <<"{\"cpuPercent\":"<<s.cpu_percent
                <<",\"memoryUsedBytes\":"<<s.memory_used_bytes
                <<",\"memoryTotalBytes\":"<<s.memory_total_bytes
                <<",\"load1m\":"<<s.load_1m
                <<",\"uptimeSeconds\":"<<s.uptime_seconds
                <<",\"processCount\":"<<s.process_count<<"}";
            int status=http_post(api_host,api_port,"/api/v1/hosts/"+host_id+"/telemetry",body.str());
            if(status<200||status>=300) std::cerr<<"telemetry POST failed: HTTP "<<status<<"\n";
            if(!once) std::this_thread::sleep_for(std::chrono::seconds(interval));
        } while(!once);
        return 0;
    } catch(const std::exception &e) { std::cerr<<"northstar-agent: "<<e.what()<<"\n"; return 1; }
}
