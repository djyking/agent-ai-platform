"""Isolated phase-five execution acceptance. Requires an explicit private local fixture config.

Creates/terminates only its own Java child processes. Seeds 100k SYNTHETIC completed rows
only in one of the exact dedicated profile databases below. No production identities,
external models or network tools are used. Failed/UNKNOWN commands are never retried.
"""
from __future__ import annotations
import argparse
import copy
import threading
import concurrent.futures
import http.cookiejar
import json
import math
from datetime import datetime
import os
import secrets
import socket
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from process_acceptance import manifest, digest, require, CheckFailure, write_json, under, NoRedirect
from wire_fixture import WireFixture, utc


PROFILES = {
    "phase5": {"project": "phase5-execution", "secondary": "phase5-secondary",
               "database": "harness_phase5_execution", "ports": {"A": 8112, "B": 8113}, "fixturePort": 53967},
    "phase6": {"project": "phase6-execution", "secondary": "phase6-secondary",
               "database": "harness_phase6_execution", "ports": {"A": 8132, "B": 8133}, "fixturePort": 53977},
}


def deployment_template(project=None, application="phase5-probe", subject="operator", fixture_port=None, profile="phase5"):
    """Root controller can generate a private deployment before invoking the normal migrate CLI."""
    require(profile in PROFILES, "FIXED_FIXTURE_PROFILE_REQUIRED")
    selected=PROFILES[profile]
    project=project or selected["project"]
    fixture_port=fixture_port or selected["fixturePort"]
    value=manifest({"project":project,"runTag":"platform-"+profile+"-execution-v1","reviewerSubject":subject},
                   {"applicationId":application,"authOrigin":"http://127.0.0.1:"+str(selected["ports"]["A"])})
    value["tools"][0]["endpoint"]=f"http://127.0.0.1:{fixture_port}/mcp"
    value["models"][0]["endpoint"]=f"http://127.0.0.1:{fixture_port}/chat/completions"
    value["concurrency"]=10
    value["projects"][0].update(maxActiveRuns=30,maxReservedTokens=60000,
        maxActiveRunsPerApplication=30,maxReservedTokensPerApplication=60000,concurrency=10)
    secondary=copy.deepcopy(value["projects"][0]);secondary["id"]=selected["secondary"]
    value["projects"].append(secondary)
    for release in list(value["releases"]):
        second=copy.deepcopy(release);second["projectId"]=selected["secondary"]
        second["releaseId"]=str(uuid.uuid5(uuid.NAMESPACE_URL,selected["secondary"]+"/"+release["agentId"]))
        value["releases"].append(second)
    return value


class Client:
    def __init__(self,origin,username,password,project=None):
        self.origin=origin
        self.http=urllib.request.build_opener(NoRedirect(),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf=None
        login={"username":username,"password":password}
        if project is not None:login["projectId"]=project
        status,body,_=self.request("POST","/console/login",login)
        require(status==200,"LOCAL_LOGIN_FAILED")
        self.csrf=body["csrfToken"]
    def request(self,method,path,body=None,etag=None,key=None):
        headers={"Origin":self.origin,"Content-Type":"application/json"}
        if self.csrf:headers["X-CSRF-Token"]=self.csrf
        if etag:headers["If-Match"]=etag
        if method!="GET":headers["Idempotency-Key"]=key or uuid.uuid4().hex
        req=urllib.request.Request(self.origin+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
        try:response=self.http.open(req,timeout=15)
        except urllib.error.HTTPError as error:response=error
        with response:
            raw=response.read();status=response.status;received={k.lower():v for k,v in response.headers.items()}
        return status,json.loads(raw) if raw else {},received


class Runner:
    def __init__(self,path,report,soak_seconds=0,checks=None):
        self.c=json.loads(Path(path).read_text(encoding="utf-8-sig"))
        require(0<=soak_seconds<=604800,"INVALID_SOAK_DURATION")
        self.soak_seconds=soak_seconds
        self.selected_checks=checks
        c=self.c
        self.profile=c.get("fixtureProfile","phase5")
        require(self.profile in PROFILES,"FIXED_FIXTURE_PROFILE_REQUIRED")
        self.profile_config=PROFILES[self.profile]
        self.secondary_project=self.profile_config["secondary"]
        self.history_pattern="^platform-"+self.profile+"-[a-f0-9]{8}-history-[0-9]+$"
        self.work=under(c["workDir"],Path(c["workspace"])/".work")
        self.work.mkdir(parents=True,exist_ok=True)
        require(c["project"]==self.profile_config["project"],"DEDICATED_PROJECT_REQUIRED")
        db=c["mysql"]
        require(db["database"]==self.profile_config["database"] and db["host"]=="127.0.0.1","DEDICATED_LOOPBACK_DB_REQUIRED")
        require(c["ports"]==self.profile_config["ports"] and c["fixturePort"]==self.profile_config["fixturePort"],"DEDICATED_PORTS_REQUIRED")
        self.env={**os.environ,**json.loads(under(c["environmentFile"],Path(c["workspace"])/".work").read_text(encoding="utf-8-sig"))}
        self.env={key:str(value) for key,value in self.env.items()}
        require(self.env.get("HARNESS_IDENTITY_PROVIDER")=="local-test","LOCAL_TEST_IDENTITY_REQUIRED")
        jdbc=self.env.get("HARNESS_JDBC_URL","")
        require(jdbc.startswith("jdbc:mysql://127.0.0.1:") and
                jdbc.split("?",1)[0].rsplit("/",1)[-1]==db["database"],"DATABASE_ENVIRONMENT_MISMATCH")
        migration_jdbc=self.env.get("HARNESS_MIGRATION_JDBC_URL",jdbc)
        require(migration_jdbc==jdbc,"MIGRATION_DATABASE_ENVIRONMENT_MISMATCH")
        self.path=Path(report)
        require(not self.path.exists(),"REPORT_ALREADY_EXISTS")
        self.report={"status":"RUNNING","fixtureProfile":self.profile,"startedAt":utc(),"checks":[],"processes":[],"jarSha256":digest(Path(c["jar"]).read_bytes()),
            "boundaries":{"syntheticFixture":True,"externalModelCalls":0,"externalWriteCalls":0,"historicalRowsAreSynthetic":True,"linuxObservation":False}}
        self.processes={};self.clients={};self.secondary_clients={};self.logs=[]
        self.fixture=WireFixture(c["fixturePort"],self.work,{"shared":self.env[c.get("fixtureTokenEnv","ACCEPTANCE_FIXTURE_TOKEN")]},secrets.token_urlsafe(32),model_delay=1.5)
        self.refs=c.get("releaseRefs",{})
        self.secondary_refs=c.get("secondaryReleaseRefs",{})
        self.route="/console/api/projects/"+c["project"]+"/runs"
        self.tag="platform-"+self.profile+"-"+uuid.uuid4().hex[:8]
        self.save()
    def save(self):write_json(self.path,self.report)
    def check(self,name,action):
        row={"name":name,"status":"RUNNING","startedAt":utc()};self.report["checks"].append(row);self.save()
        try:row.update(status="PASSED",evidence=action());print(name+": PASSED",flush=True)
        except Exception as exc:row.update(status="FAILED",failureCode=str(exc) if isinstance(exc,CheckFailure) else type(exc).__name__);raise
        finally:row["finishedAt"]=utc();self.save()
    def command(self,*args):
        result=subprocess.run([self.c["java"],"-jar",self.c["jar"],*args],env=self.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
            creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0),timeout=90)
        if result.returncode:(self.work/("cli-"+uuid.uuid4().hex+".private.log")).write_bytes(result.stdout+result.stderr)
        require(result.returncode==0,"JAVA_CLI_FAILED")
        return result.stdout.decode("utf-8",errors="replace")
    def sql(self,query,timeout=180):
        db=self.c["mysql"]
        result=subprocess.run([db["client"],"--protocol=TCP","--host="+db["host"],"--port="+str(db["port"]),"--user="+db["user"],
            "--database="+db["database"],"--batch","--raw","--skip-column-names"],input=query.encode(),env={**os.environ,"MYSQL_PWD":self.env[db.get("passwordEnv","HARNESS_JDBC_PASSWORD")]},
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0),timeout=timeout)
        if result.returncode:(self.work/("sql-"+uuid.uuid4().hex+".private.log")).write_bytes(result.stderr)
        require(result.returncode==0,"MYSQL_QUERY_FAILED")
        return result.stdout.decode("utf-8").strip()
    def start(self,label):
        port=self.c["ports"][label]
        with socket.socket() as probe:require(probe.connect_ex(("127.0.0.1",port))!=0,"PORT_IN_USE")
        log=(self.work/(label+"-"+uuid.uuid4().hex[:8]+".private.log")).open("wb");self.logs.append(log)
        runtime_env={key:value for key,value in self.env.items() if not key.startswith("HARNESS_MIGRATION_") and key!="HARNESS_EXPECTED_GOVERNANCE_FINGERPRINT"}
        process=subprocess.Popen([self.c["java"],"-jar",self.c["jar"]],env={**runtime_env,"HARNESS_PORT":str(port)},stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
        self.processes[label]=process;self.report["processes"].append({"label":label,"pid":process.pid,"startedAt":utc()});self.save()
        end=time.monotonic()+90
        while time.monotonic()<end:
            require(process.poll() is None,"WORKER_STARTUP_FAILED")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/health",timeout=2) as response:
                    if response.status==200:break
            except (OSError,urllib.error.URLError):pass
            time.sleep(.3)
        else:raise CheckFailure("HEALTH_TIMEOUT")
        self.clients[label]=Client(f"http://127.0.0.1:{port}",self.c["username"],self.c["password"],self.c["project"])
        self.secondary_clients[label]=Client(f"http://127.0.0.1:{port}",self.c["username"],self.c["password"],self.secondary_project)
    def stop(self,label):
        process=self.processes.get(label)
        if process and process.poll() is None:
            process.kill();process.wait(timeout=10)
            for row in self.report["processes"]:
                if row["pid"]==process.pid:row.update(stoppedAt=utc(),termination="owned child abrupt kill")
            self.save()
    def create(self,kind,label,worker="A",secondary=False):
        key=self.tag+"-"+label
        route="/console/api/projects/"+self.secondary_project+"/runs" if secondary else self.route
        refs=self.secondary_refs if secondary else self.refs
        client=self.secondary_clients[worker] if secondary else self.clients[worker]
        status,result,_=client.request("POST",route,{"releaseRef":refs[kind],"inputs":{"caseKey":key},"clientReference":key})
        require(status==202,"CREATE_HTTP_"+str(status))
        return result["run"]["id"],key
    def get(self,run,worker="A"):
        status,body,headers=self.clients[worker].request("GET",self.route+"/"+run)
        require(status==200,"RUN_HTTP_"+str(status));return body,headers["etag"]
    def wait(self,run,states,worker="A",seconds=40):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            body,etag=self.get(run,worker)
            if body["status"] in states:return body,etag
            require(body["status"] not in {"FAILED","EXPIRED","CANCELLED","BUDGET_EXCEEDED","NEEDS_ATTENTION"},"UNEXPECTED_"+body["status"])
            time.sleep(.1)
        raise CheckFailure("RUN_STATE_TIMEOUT")
    def control(self,run,operation,worker="A"):
        _,etag=self.get(run,worker)
        status,body,_=self.clients[worker].request("POST",self.route+"/"+run+"/"+operation,{},etag)
        require(status==202,"CONTROL_HTTP_"+str(status));return body
    def approve(self,run,worker="A"):
        status,approval,headers=self.clients[worker].request("GET",self.route+"/"+run+"/approval")
        require(status==200 and approval["reviewComplete"],"APPROVAL_UNAVAILABLE")
        status,_,_=self.clients[worker].request("POST",self.route+"/"+run+"/approvals/"+approval["id"]+"/decision",
            {"digest":approval["digest"],"decision":"APPROVE","reason":"Reviewed local synthetic probe"},headers["etag"])
        require(status==202,"APPROVAL_REJECTED")
    def concurrency(self):
        self.fixture.max_active=0
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            rows=list(pool.map(lambda n:self.create("read","capacity-"+str(n),"A" if n%2 else "B"),range(12)))
        for run,key in rows:
            self.wait(run,{"COMPLETED"})
            require(len(self.fixture.rows("dispatch",key))==1,"DUPLICATE_DISPATCH")
        require(0<self.fixture.max_active<=self.c.get("expectedToolConcurrency",2),"SHARED_TOOL_LIMIT_BROKEN")
        workers=int(self.sql("SELECT COUNT(*) FROM gov_worker WHERE state='RUNNING' AND heartbeat_at>UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000-5000"))
        require(workers==2,"TWO_WORKERS_NOT_OBSERVED")
        return {"runs":len(rows),"twoRecentWorkerHeartbeats":workers,"observedMaxToolCalls":self.fixture.max_active,"dispatches":len(rows),"sampleRun":rows[0][0]}
    def model_concurrency_and_fairness(self):
        self.fixture.max_active_models=0
        stop=threading.Event();observed=[]
        def sample():
            while not stop.wait(.05):
                observed.append(int(self.sql("SELECT COUNT(*) FROM platform_slots WHERE pool_id='global' AND owner_id IS NOT NULL AND lease_until>UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000")))
        monitor=threading.Thread(target=sample,daemon=True);monitor.start()
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
                primary=list(pool.map(lambda n:self.create("model-then-human","model-load-"+str(n),"A" if n%2 else "B"),range(10)))
            secondary,key=self.create("model-then-human","model-other-project","B",secondary=True)
            secondary_route="/console/api/projects/"+self.secondary_project+"/runs/"+secondary
            start=time.monotonic()
            while time.monotonic()-start<20:
                status,body,_=self.secondary_clients["B"].request("GET",secondary_route)
                require(status==200,"SECOND_PROJECT_READ_FAILED")
                if body["status"]=="WAITING_INPUT":break
                require(body["status"] in {"QUEUED","RUNNING"},"SECOND_PROJECT_FAILED")
                time.sleep(.1)
            else:raise CheckFailure("SECOND_PROJECT_STARVED")
            progressed_ms=(time.monotonic()-start)*1000
            for run,_ in primary:self.wait(run,{"WAITING_INPUT"})
            require(1<self.fixture.max_active_models<=5,"SHARED_MODEL_CONCURRENCY_BROKEN")
            require(observed and max(observed)<=10,"GLOBAL_EXECUTION_LIMIT_BROKEN")
            for run,_ in primary:self.control(run,"cancel");self.wait(run,{"CANCELLED"})
            status,_,headers=self.secondary_clients["B"].request("GET",secondary_route)
            require(status==200,"SECOND_PROJECT_FINAL_READ_FAILED")
            status,_,_=self.secondary_clients["B"].request("POST",secondary_route+"/cancel",{},headers["etag"])
            require(status==202,"SECOND_PROJECT_CANCEL_FAILED")
            return {"simultaneousSubmittedPrimaryRuns":10,"secondaryRunId":secondary,"secondaryProgressMillis":round(progressed_ms,3),"observedMaxModelCalls":self.fixture.max_active_models,"configuredModelLimit":5,"observedMaxExecutionSlots":max(observed),"configuredExecutionLimit":10}
        finally:stop.set();monitor.join(timeout=10)
    def killed_worker(self):
        self.stop("B")
        run,key=self.create("hold-read","killed-owner")
        end=time.monotonic()+15
        while not self.fixture.rows("dispatch",key) and time.monotonic()<end:time.sleep(.05)
        require(len(self.fixture.rows("dispatch",key))==1,"CALL_DID_NOT_START")
        self.stop("A");self.fixture.release(key);self.start("B")
        state,_=self.wait(run,{"NEEDS_ATTENTION"},"B",seconds=110)
        require(len(self.fixture.rows("dispatch",key))==1,"UNKNOWN_REPLAYED")
        # Core recovery and the governance heartbeat are independent transactions. The
        # expired DISPATCHED permit still consumes capacity until the next heartbeat.
        # Require continuous quarantine, then the explicit UNKNOWN projection.
        end=time.monotonic()+5
        while True:
            states=self.sql("SELECT state FROM gov_permit WHERE run_id='"+run+"'").splitlines()
            require(len(states)==1 and states[0] in {"DISPATCHED","UNKNOWN"},"UNKNOWN_PERMIT_CAPACITY_RELEASED")
            if states==["UNKNOWN"]:break
            require(time.monotonic()<end,"UNKNOWN_PERMIT_NOT_RETAINED")
            time.sleep(.1)
        require(len(self.fixture.rows("dispatch",key))==1,"UNKNOWN_REPLAYED_AFTER_HEARTBEAT")
        self.start("A")
        return {"runId":run,"status":state["status"],"dispatches":1,"unknownPermitRetained":True,"replacementWorker":True}
    def token_settlement(self):
        run,_=self.create("model-then-human","tokens")
        state,_=self.wait(run,{"WAITING_INPUT"})
        require(state["usage"]["chargedTokens"]==22,"PROVIDER_USAGE_NOT_COMMITTED")
        before=int(self.sql("SELECT SUM(reserved_tokens) FROM gov_reservation WHERE run_id='"+run+"' AND settled=FALSE"))
        require(before>0,"CALENDAR_RESERVATION_MISSING")
        self.control(run,"cancel");self.wait(run,{"CANCELLED"})
        end=time.monotonic()+5
        while time.monotonic()<end:
            pending=int(self.sql("SELECT COUNT(*) FROM gov_reservation WHERE run_id='"+run+"' AND settled=FALSE"))
            if pending==0:break
            time.sleep(.2)
        require(pending==0,"TERMINAL_BUDGET_NOT_SETTLED")
        return {"runId":run,"chargedTokens":22,"waitingReservedTokens":before,"terminalReservationsSettled":True}
    def history_scale(self):
        count=int(self.c.get("historicalRows",100000));require(count==100000,"EXPECTED_100K_HISTORY")
        reuse=self.c.get("reuseSyntheticHistory",False)
        require(isinstance(reuse,bool),"REUSE_HISTORY_FLAG_MUST_BE_BOOLEAN")
        require(self.c["mysql"]["database"]==self.profile_config["database"],"DEDICATED_HISTORY_DATABASE_REQUIRED")
        seed_seconds=0
        if not reuse:
            existing=int(self.sql("SELECT COUNT(*) FROM platform_runs WHERE client_reference REGEXP '"+self.history_pattern+"'"))
            require(existing==0,"EXISTING_SYNTHETIC_HISTORY_REQUIRES_EXPLICIT_REUSE")
            run,_=self.create("read","history-seed");self.wait(run,{"COMPLETED"})
            prefix=self.tag+"-history-"
            # Distinct historical rows are synthetic fixtures; no claim that 100k remote tasks executed.
            digits="(SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)"
            cte=f"WITH numbers AS (SELECT a.d+10*b.d+100*c.d+1000*d.d+10000*e.d n FROM {digits} a CROSS JOIN {digits} b CROSS JOIN {digits} c CROSS JOIN {digits} d CROSS JOIN {digits} e), hashes AS (SELECT n,MD5(CONCAT('{prefix}',n)) h FROM numbers), phase5_seed AS (SELECT n,CONCAT(SUBSTRING(h,1,8),'-',SUBSTRING(h,9,4),'-',SUBSTRING(h,13,4),'-',SUBSTRING(h,17,4),'-',SUBSTRING(h,21,12)) id FROM hashes)"
            query=f"""START TRANSACTION;
INSERT INTO harness_runs(run_id,creation_scope,creation_digest,revision,fence,lease_until,run_status,next_attempt_at,created_at,snapshot_json,event_sequence)
{cte} SELECT s.id,SHA2(CONCAT('{prefix}',s.n),256),r.creation_digest,r.revision,0,NULL,'COMPLETED',NULL,r.created_at,
JSON_SET(r.snapshot_json,'$.id',s.id,'$.creationKey',CONCAT('{prefix}',s.n),'$.fence',0,'$.leaseUntil',NULL),r.event_sequence FROM phase5_seed s CROSS JOIN harness_runs r WHERE r.run_id='{run}';
INSERT INTO platform_runs(run_id,project_id,application_id,subject_id,delegation_id,release_json,limits_json,client_reference,created_at,reserved_tokens)
{cte} SELECT s.id,p.project_id,p.application_id,p.subject_id,p.delegation_id,p.release_json,p.limits_json,CONCAT('{prefix}',s.n),p.created_at,p.reserved_tokens FROM phase5_seed s CROSS JOIN platform_runs p WHERE p.run_id='{run}';
INSERT INTO harness_run_index(run_id,run_status,charged_tokens,model_calls,tool_calls,updated_at,terminal_at,deadline_at,expirable_at,platform_project_id,platform_application_id,platform_subject_id,platform_created_at,platform_reserved_tokens)
{cte} SELECT s.id,i.run_status,i.charged_tokens,i.model_calls,i.tool_calls,i.updated_at,i.terminal_at,i.deadline_at,NULL,i.platform_project_id,i.platform_application_id,i.platform_subject_id,i.platform_created_at,i.platform_reserved_tokens FROM phase5_seed s CROSS JOIN harness_run_index i WHERE i.run_id='{run}';
COMMIT;
SELECT COUNT(*) FROM platform_runs WHERE project_id='{self.c["project"]}' AND client_reference REGEXP '^{prefix}[0-9]+$' AND run_id<>'{run}';"""
            seed_started=time.monotonic()
            require(int(self.sql(query,timeout=900))==count,"SYNTHETIC_HISTORY_COUNT_MISMATCH")
            seed_seconds=time.monotonic()-seed_started
        validation_started=time.monotonic()
        history_validation=self.validate_synthetic_history(count)
        validation_seconds=time.monotonic()-validation_started
        plans=self.sql("EXPLAIN SELECT run_id FROM harness_run_index WHERE expirable_at<=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 ORDER BY expirable_at,run_id LIMIT 100; EXPLAIN SELECT p.application_id,p.reserved_tokens,i.run_status FROM harness_run_index i JOIN platform_runs p ON p.run_id=i.run_id WHERE p.project_id='"+self.c["project"]+"' AND i.run_status IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_INPUT','NEEDS_ATTENTION');")
        require("harness_wait_due_idx" in plans,"WAIT_DEADLINE_INDEX_UNUSED")
        sustained=self.mixed_api_load("sustained",200,10)
        burst=self.mixed_api_load("burst",100,20)
        idle_queue=self.idle_queue_latency()
        return {"syntheticTerminalRows":count,"seedSource":"reused_existing_fixture" if reuse else "new_fixture",
            "seedDurationSeconds":round(seed_seconds,3),"historyValidationDurationSeconds":round(validation_seconds,3),"historyValidation":history_validation,
            "readWriteRatio":{"getPercent":90,"postPercent":10},"sustained":sustained,"burst":burst,
            "idleQueue":idle_queue,"queryPlan":plans.splitlines()}
    def validate_synthetic_history(self,expected):
        require(self.c["mysql"]["database"]==self.profile_config["database"] and self.c["mysql"]["host"]=="127.0.0.1","DEDICATED_HISTORY_DATABASE_REQUIRED")
        pattern=self.history_pattern
        platform_count=int(self.sql("SELECT COUNT(*) FROM platform_runs WHERE client_reference REGEXP '"+pattern+"'"))
        require(platform_count==expected,"SYNTHETIC_PLATFORM_HISTORY_COUNT_MISMATCH")
        # Validate both directions by selecting fixture core rows independently of platform ownership.
        # This is an explicit acceptance-fixture integrity scan, not a production admission query.
        query=f"""WITH fixture_core AS (
SELECT run_id,run_status,lease_until,JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.creationKey')) fixture_key
FROM harness_runs WHERE JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.creationKey')) REGEXP '{pattern}')
SELECT JSON_OBJECT('coreRows',COUNT(*),'coreTerminalRows',SUM(r.run_status='COMPLETED' AND r.lease_until IS NULL),
'platformRows',COUNT(p.run_id),'indexRows',COUNT(i.run_id),
'joinedTerminalRows',SUM(p.project_id='{self.c["project"]}' AND BINARY p.client_reference=BINARY r.fixture_key AND i.run_status='COMPLETED' AND i.terminal_at IS NOT NULL),
'ownershipProjectionMatches',SUM(BINARY i.platform_project_id=BINARY p.project_id AND BINARY i.platform_application_id=BINARY p.application_id AND BINARY i.platform_subject_id=BINARY p.subject_id AND i.platform_created_at=p.created_at AND i.platform_reserved_tokens=p.reserved_tokens),
'distinctReferences',COUNT(DISTINCT r.fixture_key))
FROM fixture_core r LEFT JOIN platform_runs p ON p.run_id=r.run_id LEFT JOIN harness_run_index i ON i.run_id=r.run_id;"""
        result=json.loads(self.sql(query,timeout=300))
        require(all(result.get(key)==expected for key in ("coreRows","coreTerminalRows","platformRows","indexRows","joinedTerminalRows","ownershipProjectionMatches","distinctReferences")),"SYNTHETIC_HISTORY_INTEGRITY_MISMATCH")
        return {"referenceRegex":pattern,"platformReferenceRows":platform_count,**result,"readOnlyValidation":True}
    @staticmethod
    def p95(values):
        ordered=sorted(values)
        return ordered[max(0,math.ceil(len(ordered)*.95)-1)] if ordered else 0
    def mixed_api_load(self,label,total,rps):
        require(total%10==0,"FIXED_RATIO_REQUIRES_MULTIPLE_OF_TEN")
        start=time.monotonic();samples=[]
        def measured(n):
            method="POST" if n%10==0 else "GET"
            worker="A" if (n//10 if method=="POST" else n)%2 else "B"
            case_key=self.tag+"-load-"+label+"-"+str(n)
            before=time.monotonic()
            try:
                if method=="POST":
                    status,body,_=self.clients[worker].request("POST",self.route,
                        {"releaseRef":self.refs["read"],"inputs":{"caseKey":case_key},"clientReference":case_key},key=case_key)
                else:status,body,_=self.clients[worker].request("GET",self.route+"?limit=20")
            except (OSError,urllib.error.URLError,TimeoutError):
                status,body=0,{}
            return {"method":method,"status":status,"millis":(time.monotonic()-before)*1000,
                "runId":body.get("run",{}).get("id") if method=="POST" else None,"caseKey":case_key}
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
            futures=[]
            for n in range(total):
                delay=start+n/rps-time.monotonic()
                if delay>0:time.sleep(delay)
                futures.append(pool.submit(measured,n))
            samples=[future.result() for future in futures]
        elapsed=time.monotonic()-start
        reads=[s for s in samples if s["method"]=="GET"]
        writes=[s for s in samples if s["method"]=="POST"]
        evidence={"requests":total,"offeredRequestsPerSecond":rps,"durationSeconds":round(elapsed,3),
            "getRequests":len(reads),"postRequests":len(writes),"readWriteRatio":"90:10",
            "errors":sum(s["status"]!=(202 if s["method"]=="POST" else 200) for s in samples),
            "p95Millis":round(self.p95([s["millis"] for s in samples]),3),
            "getP95Millis":round(self.p95([s["millis"] for s in reads]),3),
            "postP95Millis":round(self.p95([s["millis"] for s in writes]),3),
            "maxMillis":round(max(s["millis"] for s in samples),3),"postCommands":[{"idempotencyKey":s["caseKey"],"runId":s["runId"],"status":s["status"],"millis":round(s["millis"],3)} for s in writes],"completedPostRuns":[],"duplicateDispatches":0}
        # Record even a failed measured phase before any assertion or following mutation.
        self.report.setdefault("mixedLoadEvidence",{})[label]=evidence;self.save()
        require(evidence["errors"]==0,"MIXED_"+label.upper()+"_HTTP_FAILURE")
        require(max(evidence["p95Millis"],evidence["getP95Millis"],evidence["postP95Millis"])<=500,"MIXED_"+label.upper()+"_API_P95_ABOVE_500MS")
        require(len({s["runId"] for s in writes})==len(writes),"DISTINCT_POSTS_REUSED_RUN")
        for row in writes:
            require(row["runId"] is not None,"POST_RUN_ID_MISSING")
            self.wait(row["runId"],{"COMPLETED"})
            dispatches=len(self.fixture.rows("dispatch",row["caseKey"]))
            evidence["duplicateDispatches"]+=max(0,dispatches-1)
            require(dispatches==1,"LOAD_POST_DISPATCH_COUNT_MISMATCH")
            evidence["completedPostRuns"].append({"runId":row["runId"],"dispatchCount":dispatches})
        self.save()
        return evidence
    def idle_queue_latency(self):
        require(int(self.sql("SELECT COUNT(*) FROM platform_runs p JOIN harness_runs r ON r.run_id=p.run_id WHERE p.project_id='"+self.c["project"]+"' AND r.run_status IN ('QUEUED','RUNNING')"))==0,"QUEUE_NOT_IDLE_BEFORE_PROBE")
        samples=[]
        for n in range(20):
            run,key=self.create("read","idle-queue-"+str(n),"A" if n%2 else "B")
            completed,_=self.wait(run,{"COMPLETED"})
            uuid.UUID(run)
            first_claim=self.sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.at')) FROM harness_run_events WHERE run_id='"+run+"' AND JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.type'))='LEASE_CLAIMED' ORDER BY event_sequence LIMIT 1")
            require(bool(first_claim),"DURABLE_FIRST_CLAIM_MISSING")
            created=completed["createdAt"]
            delay=(datetime.fromisoformat(first_claim.replace("Z","+00:00"))-datetime.fromisoformat(created.replace("Z","+00:00"))).total_seconds()*1000
            require(delay>=0,"QUEUE_CLOCK_SOURCES_DISAGREE")
            require(len(self.fixture.rows("dispatch",key))==1,"IDLE_PROBE_DUPLICATE_DISPATCH")
            samples.append({"runId":run,"createdAt":created,"firstClaimAt":first_claim,"queueMillis":round(delay,3)})
        value=self.p95([s["queueMillis"] for s in samples])
        evidence={"sampleCount":20,"source":"HTTP Run.createdAt and MySQL durable LEASE_CLAIMED.at; public events intentionally omit lease details",
            "definition":"first lease claim minus Run creation; excludes tool/model generation and completion time",
            "p95Millis":round(value,3),"maxMillis":max(s["queueMillis"] for s in samples),"targetP95Millis":2000,"samples":samples}
        self.report["idleQueueEvidence"]=evidence;self.save()
        require(value<=2000,"IDLE_QUEUE_P95_ABOVE_2_SECONDS")
        return evidence
    def sustained_observation(self):
        # This is an actual timed probe. Requested duration is never reported as elapsed evidence.
        start=time.monotonic();durations=[];statuses=[];next_at=start;refresh_at=start+600
        deadline=start+self.soak_seconds
        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
            pending=[]
            def probe(n):
                before=time.monotonic()
                try:status,_,_=self.clients["A" if n%2 else "B"].request("GET",self.route+"?limit=20")
                except (OSError,urllib.error.URLError,TimeoutError):status=0
                return (time.monotonic()-before)*1000,status
            n=0
            while time.monotonic()<deadline:
                if time.monotonic()>=refresh_at:
                    for label in ("A","B"):
                        self.clients[label]=Client(f"http://127.0.0.1:{self.c['ports'][label]}",self.c["username"],self.c["password"],self.c["project"])
                    refresh_at=time.monotonic()+600
                pending.append(pool.submit(probe,n));n+=1;next_at+=.1
                # Bound outstanding memory while retaining exact latency samples for the current run.
                if len(pending)>=100:
                    for future in pending:
                        duration,status=future.result();durations.append(duration);statuses.append(status)
                    pending=[]
                    self.report["soakProgress"]={"requestedSeconds":self.soak_seconds,"elapsedSeconds":round(time.monotonic()-start,3),"requests":len(statuses),"errors":sum(code!=200 for code in statuses)};self.save()
                pause=next_at-time.monotonic()
                if pause>0:time.sleep(min(pause,max(0,deadline-time.monotonic())))
            for future in pending:
                duration,status=future.result();durations.append(duration);statuses.append(status)
        elapsed=time.monotonic()-start
        p95=sorted(durations)[max(0,int(len(durations)*.95)-1)] if durations else 0
        evidence={"requestedSeconds":self.soak_seconds,"elapsedSeconds":round(elapsed,3),"requests":len(statuses),"observedRequestsPerSecond":round(len(statuses)/elapsed,3),"errors":sum(code!=200 for code in statuses),"p95Millis":round(p95,3),"completed24Hours":elapsed>=86400 and self.soak_seconds>=86400}
        self.report["soakEvidence"]=evidence;self.save()
        require(elapsed>=self.soak_seconds and evidence["errors"]==0 and p95<=500,"SOAK_SERVICE_TARGET_FAILED")
        return evidence
    def unknown_write(self):
        run,key=self.create("unknown-write","lost-write-response");self.wait(run,{"WAITING_APPROVAL"});self.approve(run)
        state,_=self.wait(run,{"NEEDS_ATTENTION"})
        require(len(self.fixture.rows("effect",key))==1 and len(self.fixture.rows("dispatch",key))==1,"WRITE_EFFECT_REPLAYED")
        time.sleep(2)
        require(len(self.fixture.rows("dispatch",key))==1,"UNKNOWN_WRITE_REPLAYED")
        return {"runId":run,"status":state["status"],"syntheticExternalEffects":1,"dispatches":1,"unknownRetained":True}
    def run(self):
        try:
            self.fixture.start()
            if not self.refs:
                output=self.command("releases",self.env["HARNESS_CONFIG"])
                rows=json.loads(output[output.index("["):])
                self.refs={row["releaseRef"]["agentId"].removeprefix("acceptance-"):row["releaseRef"] for row in rows if row["projectId"]==self.c["project"]}
                self.secondary_refs={row["releaseRef"]["agentId"].removeprefix("acceptance-"):row["releaseRef"] for row in rows if row["projectId"]==self.secondary_project}
            already_migrated=self.c.get("schemaAlreadyMigrated",False)
            require(isinstance(already_migrated,bool),"INVALID_MIGRATION_PRECONDITION")
            if already_migrated:
                # Reusing the isolated fault fixture may intentionally retain UNKNOWN
                # calls. Do not bypass offline migration guards to rerun a no-op DDL.
                require(self.env.get("HARNESS_SCHEMA_MODE")=="validate","REUSED_SCHEMA_MUST_VALIDATE")
                self.report["migration"]="previously applied; both JVMs perform read-only schema validation"
            else:
                self.command("migrate")
                self.report["migration"]="explicit offline CLI applied"
            self.start("A");self.start("B")
            from phase5_operations_acceptance import run_checks
            actions={
                "two_workers_shared_concurrency":self.concurrency,
                "ten_tasks_five_models_two_projects":self.model_concurrency_and_fairness,
                "calendar_reservation_and_real_fixture_usage":self.token_settlement,
                "100k_history_and_10rps_api":self.history_scale,
                "sustained_local_observation":self.sustained_observation,
                "abrupt_worker_loss_unknown_without_replay":self.killed_worker,
                "approved_write_lost_response_no_duplicate":self.unknown_write,
                "mysql_operations_retention_metrics":lambda:run_checks(self),
            }
            selected=self.selected_checks or [name for name in actions if self.soak_seconds or name!="sustained_local_observation"]
            require(len(selected)==len(set(selected)) and all(name in actions for name in selected),"INVALID_CHECK_SELECTION")
            require("sustained_local_observation" not in selected or self.soak_seconds>0,"OBSERVATION_DURATION_REQUIRED")
            self.report["selectedChecks"]=selected
            self.report["scope"]="selected checks only" if self.selected_checks else "all execution checks"
            for name in selected:self.check(name,actions[name])
            self.report["status"]="PASSED"
        except Exception as exc:
            self.report.update(status="FAILED",failureCode=str(exc) if isinstance(exc,CheckFailure) else type(exc).__name__);raise
        finally:
            for label in list(self.processes):self.stop(label)
            self.fixture.close()
            for log in self.logs:log.close()
            self.report["finishedAt"]=utc();self.save()


if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--config",required=True);parser.add_argument("--report",required=True);parser.add_argument("--soak-seconds",type=int,default=0)
    parser.add_argument("--checks",nargs="+",help="Explicit subset; report remains scoped to selected checks")
    args=parser.parse_args();Runner(args.config,args.report,args.soak_seconds,args.checks).run()
