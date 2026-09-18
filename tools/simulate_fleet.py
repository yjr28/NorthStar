#!/usr/bin/env python3
import argparse, concurrent.futures, json, random, time, urllib.request, uuid

def post(url, body):
    req=urllib.request.Request(url,data=json.dumps(body).encode(),headers={"Content-Type":"application/json"},method="POST")
    with urllib.request.urlopen(req,timeout=10) as r: return r.status

def host(base,i,samples):
    rng=random.Random(i); hid=str(uuid.uuid4()); ok=0
    if post(base+"/api/v1/hosts",{"id":hid,"hostname":f"sim-host-{i:03d}","agentVersion":"sim-0.1.0","osName":"Linux","architecture":"x86_64"}) in (200,201): ok+=1
    for n in range(samples):
        total=8*1024**3
        status=post(base+f"/api/v1/hosts/{hid}/telemetry",{"cpuPercent":round(rng.uniform(2,95),2),"memoryUsedBytes":int(total*rng.uniform(.25,.85)),"memoryTotalBytes":total,"load1m":round(rng.uniform(.05,4),2),"uptimeSeconds":3600+n*10,"processCount":rng.randint(80,320)})
        ok += status in (200,201)
    return ok

def main():
    p=argparse.ArgumentParser(); p.add_argument("--base-url",default="http://127.0.0.1:8080"); p.add_argument("--hosts",type=int,default=100); p.add_argument("--samples",type=int,default=3); p.add_argument("--workers",type=int,default=32); a=p.parse_args()
    start=time.perf_counter(); success=0
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.workers) as ex:
        for result in ex.map(lambda i:host(a.base_url,i,a.samples),range(a.hosts)): success+=result
    elapsed=time.perf_counter()-start; total=a.hosts*(a.samples+1)
    print(json.dumps({"hosts":a.hosts,"samples_per_host":a.samples,"requests":total,"successful_requests":success,"failed_requests":total-success,"elapsed_seconds":round(elapsed,3),"request_rate":round(total/elapsed,2)},indent=2))
    raise SystemExit(0 if success==total else 1)
if __name__=="__main__": main()
