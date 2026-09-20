#include "northstar_telemetry.h"
#include "northstar_spool.hpp"
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/utsname.h>
#include <unistd.h>
#include <chrono>
#include <cstdlib>
#include <ctime>
#include <iomanip>
#include <iostream>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>

static std::string env_or(const char *key,const std::string &fallback){const char *v=std::getenv(key);return v&&*v?v:fallback;}
static int env_int(const char *key,int fallback){try{return std::stoi(env_or(key,std::to_string(fallback)));}catch(...){return fallback;}}
static std::string uuid_v4(){std::random_device rd;unsigned char b[16];for(auto &x:b)x=(unsigned char)rd();b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;std::ostringstream o;o<<std::hex<<std::setfill('0');for(int i=0;i<16;i++){o<<std::setw(2)<<(int)b[i];if(i==3||i==5||i==7||i==9)o<<'-';}return o.str();}
static std::string hex(const unsigned char *data,size_t n){std::ostringstream o;o<<std::hex<<std::setfill('0');for(size_t i=0;i<n;i++)o<<std::setw(2)<<(int)data[i];return o.str();}
static std::string sha256(const std::string &body){unsigned char out[EVP_MAX_MD_SIZE];unsigned int n=0;EVP_MD_CTX *ctx=EVP_MD_CTX_new();if(!ctx)throw std::runtime_error("digest context failed");if(EVP_DigestInit_ex(ctx,EVP_sha256(),nullptr)!=1||EVP_DigestUpdate(ctx,body.data(),body.size())!=1||EVP_DigestFinal_ex(ctx,out,&n)!=1){EVP_MD_CTX_free(ctx);throw std::runtime_error("sha256 failed");}EVP_MD_CTX_free(ctx);return hex(out,n);}
static std::string hmac_sha256(const std::string &key,const std::string &data){unsigned char out[EVP_MAX_MD_SIZE];unsigned int n=0;if(!HMAC(EVP_sha256(),key.data(),(int)key.size(),reinterpret_cast<const unsigned char*>(data.data()),data.size(),out,&n))throw std::runtime_error("hmac failed");return hex(out,n);}
static std::string timestamp_utc(){std::time_t t=std::time(nullptr);std::tm tm{};gmtime_r(&t,&tm);char buf[32];std::strftime(buf,sizeof(buf),"%Y-%m-%dT%H:%M:%SZ",&tm);return buf;}
static std::string signature_headers(const std::string &path,const std::string &body,const std::string &token){std::string ts=timestamp_utc(),nonce=uuid_v4();std::string canonical="POST\n"+path+"\n"+ts+"\n"+nonce+"\n"+sha256(body);std::ostringstream o;o<<"X-NorthStar-Timestamp: "<<ts<<"\r\nX-NorthStar-Nonce: "<<nonce<<"\r\nX-NorthStar-Signature: "<<hmac_sha256(token,canonical)<<"\r\n";return o.str();}
static int http_post(const std::string &host,const std::string &port,const std::string &path,const std::string &body,const std::string &token="",bool sign=false){
 addrinfo hints{};hints.ai_family=AF_UNSPEC;hints.ai_socktype=SOCK_STREAM;addrinfo *res=nullptr;if(getaddrinfo(host.c_str(),port.c_str(),&hints,&res)!=0)throw std::runtime_error("getaddrinfo failed");int fd=-1;for(addrinfo *it=res;it;it=it->ai_next){fd=socket(it->ai_family,it->ai_socktype,it->ai_protocol);if(fd<0)continue;if(connect(fd,it->ai_addr,it->ai_addrlen)==0)break;close(fd);fd=-1;}freeaddrinfo(res);if(fd<0)throw std::runtime_error("connect failed");std::ostringstream q;q<<"POST "<<path<<" HTTP/1.1\r\nHost: "<<host<<"\r\nContent-Type: application/json\r\n";if(!token.empty())q<<"X-NorthStar-Agent-Token: "<<token<<"\r\n";if(sign)q<<signature_headers(path,body,token);q<<"Connection: close\r\nContent-Length: "<<body.size()<<"\r\n\r\n"<<body;std::string wire=q.str();size_t sent=0;while(sent<wire.size()){ssize_t n=send(fd,wire.data()+sent,wire.size()-sent,0);if(n<=0){close(fd);throw std::runtime_error("send failed");}sent+=(size_t)n;}char buf[256]={0};ssize_t n=recv(fd,buf,sizeof(buf)-1,0);close(fd);if(n<=0)throw std::runtime_error("empty response");std::istringstream r(std::string(buf,(size_t)n));std::string version;int status=0;r>>version>>status;return status;
}
static bool post_ok(const std::string &h,const std::string &p,const std::string &path,const std::string &body,const std::string &token="",bool sign=false){try{int s=http_post(h,p,path,body,token,sign);return s>=200&&s<300;}catch(const std::exception &e){std::cerr<<"POST "<<path<<" failed: "<<e.what()<<"\n";return false;}}
static size_t drain_spool(const std::string &path,const std::string &h,const std::string &p,const std::string &endpoint,const std::string &token){auto lines=northstar::read_spool(path);size_t sent=0;while(sent<lines.size()&&post_ok(h,p,endpoint,lines[sent],token,true))++sent;if(sent>0)northstar::replace_spool_atomically(path,lines,sent);return sent;}
static void backoff_sleep(int failures,int base_ms,int max_ms){long long delay=base_ms;for(int i=1;i<failures&&delay<max_ms;i++)delay=std::min<long long>(delay*2,max_ms);std::random_device rd;std::mt19937 gen(rd());std::uniform_int_distribution<int> jitter(0,(int)std::max<long long>(1,delay/4));std::this_thread::sleep_for(std::chrono::milliseconds(std::min<long long>(max_ms,delay+jitter(gen))));}

int main(){try{
 std::string api_host=env_or("NORTHSTAR_HOST","127.0.0.1"),api_port=env_or("NORTHSTAR_PORT","8080"),host_id=env_or("NORTHSTAR_HOST_ID",uuid_v4()),token=env_or("NORTHSTAR_AGENT_TOKEN","");if(token.size()<24)throw std::runtime_error("NORTHSTAR_AGENT_TOKEN must be at least 24 characters");int interval=env_int("NORTHSTAR_INTERVAL_SECONDS",10),base_ms=env_int("NORTHSTAR_RETRY_BASE_MS",500),max_ms=env_int("NORTHSTAR_RETRY_MAX_MS",30000);bool once=env_or("NORTHSTAR_ONCE","0")=="1";std::string spool=env_or("NORTHSTAR_SPOOL_PATH","/tmp/northstar-telemetry.spool");char hn[256]={0};gethostname(hn,sizeof(hn)-1);utsname uts{};uname(&uts);
 std::ostringstream registration;registration<<"{\"id\":\""<<host_id<<"\",\"hostname\":\""<<hn<<"\",\"agentVersion\":\"0.3.0\",\"osName\":\""<<uts.sysname<<"\",\"architecture\":\""<<uts.machine<<"\",\"agentToken\":\""<<token<<"\"}";int failures=0;while(!post_ok(api_host,api_port,"/api/v1/hosts",registration.str())){if(once)return 2;backoff_sleep(++failures,base_ms,max_ms);}failures=0;std::string endpoint="/api/v1/hosts/"+host_id+"/telemetry";
 do{drain_spool(spool,api_host,api_port,endpoint,token);northstar_snapshot s{};if(northstar_collect(&s)!=0){std::cerr<<"telemetry collection failed\n";if(once)return 3;backoff_sleep(++failures,base_ms,max_ms);continue;}std::ostringstream body;body<<std::fixed<<std::setprecision(2)<<"{\"cpuPercent\":"<<s.cpu_percent<<",\"memoryUsedBytes\":"<<s.memory_used_bytes<<",\"memoryTotalBytes\":"<<s.memory_total_bytes<<",\"load1m\":"<<s.load_1m<<",\"uptimeSeconds\":"<<s.uptime_seconds<<",\"processCount\":"<<s.process_count<<"}";if(post_ok(api_host,api_port,endpoint,body.str(),token,true))failures=0;else{northstar::append_spool(spool,body.str());++failures;}if(!once){if(failures)backoff_sleep(failures,base_ms,max_ms);else std::this_thread::sleep_for(std::chrono::seconds(interval));}}while(!once);return failures?4:0;
}catch(const std::exception &e){std::cerr<<"northstar-agent: "<<e.what()<<"\n";return 1;}}
