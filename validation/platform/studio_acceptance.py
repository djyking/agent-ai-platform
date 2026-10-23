"""Real HTTP/MySQL/model Studio acceptance; all inputs are synthetic and read-only.

Full acceptance:
  python validation/platform/studio_acceptance.py --private .work/phase4 --report .work/phase4/acceptance.json
Only a NEW independent application on another model profile:
  python validation/platform/studio_acceptance.py --private .work/phase4 --report .work/phase4/additional.json --additional-only --additional-model PROFILE

There are no automatic mutation retries and no reuse of a failed/UNKNOWN Run.
Evidence is checkpointed during execution. An existing report is archived before a new attempt.
"""
import argparse
import copy
import json
import time
import uuid
import urllib.request
import urllib.error
import http.cookiejar
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--private', required=True)
parser.add_argument('--report', required=True)
parser.add_argument('--origin', default='http://127.0.0.1:8101')
parser.add_argument('--additional-model')
parser.add_argument('--additional-only', action='store_true',
                    help='Only create and test a NEW independent application with --additional-model; skip all primary-model checks.')
args = parser.parse_args()
if args.additional_only and not args.additional_model:
    parser.error('--additional-only requires --additional-model')
private = Path(args.private)
origin = args.origin
report = Path(args.report)
report.parent.mkdir(parents=True, exist_ok=True)
previous_report = None
if report.exists():
    archive = report.with_name(report.stem + '.previous-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8] + report.suffix)
    with archive.open('xb') as target:
        target.write(report.read_bytes())
    previous_report = archive.name
project = '/console/api/projects/studio-dev'
base = project + '/studio'
suffix = uuid.uuid4().hex[:10]
proof = {'status': 'RUNNING', 'mode': 'ADDITIONAL_ONLY' if args.additional_only else 'FULL',
         'attemptId': suffix, 'syntheticInputs': True, 'externalWriteCalls': 0,
         'identity': 'local-test', 'opsAdapterEnabled': False, 'project': 'studio-dev',
         'checks': [], 'runs': [], 'experiments': [], 'commands': [], 'applications': [],
         'httpRequests': 0}
if previous_report:
    proof['previousEvidenceReport'] = previous_report
if args.additional_model:
    proof['additionalModelProfile'] = args.additional_model


def save_report():
    proof['updatedAt'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    temporary = report.with_name(report.name + '.tmp')
    temporary.write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(report)


def check(name):
    proof['checks'].append(name)
    proof['lastCompletedCheck'] = name
    save_report()
    print(name, flush=True)


def record_run(run, task_id=None, experiment_id=None, side=None):
    # Save observed status BEFORE an assertion. Never project UNKNOWN or unfinished work as success.
    if not run.get('id'):
        return
    entry = next((item for item in proof['runs'] if item['id'] == run['id']), None)
    if entry is None:
        entry = {'id': run['id']}
        proof['runs'].append(entry)
    changed = entry.get('status') != run.get('status')
    for field in ['status', 'usage', 'releaseRef', 'reasonCode', 'attention']:
        if field in run:
            entry[field] = copy.deepcopy(run[field])
    if task_id:
        entry['taskId'] = task_id
    if experiment_id:
        entry['experimentId'] = experiment_id
    if side:
        entry['side'] = side
    if changed:
        save_report()


def record_experiment(experiment):
    entry = {'id': experiment['id'], 'applicationId': experiment.get('applicationId'),
             'status': experiment.get('status'), 'passed': experiment.get('passed', False),
             'draftDigest': experiment.get('draftDigest'), 'rows': []}
    for row in experiment.get('rows', []):
        observed = {'sampleId': row.get('sampleId')}
        for side in ['candidate', 'baseline']:
            result = row.get(side)
            if result:
                observed[side] = {key: copy.deepcopy(result[key]) for key in
                                  ['taskId', 'runId', 'status', 'visibility', 'usage', 'passed', 'reason'] if key in result}
                record_run({'id': result.get('runId'), **{key: result[key] for key in ['status', 'usage'] if key in result}},
                           result.get('taskId'), experiment['id'], side)
        entry['rows'].append(observed)
    index = next((i for i, item in enumerate(proof['experiments']) if item['id'] == entry['id']), None)
    if index is None:
        proof['experiments'].append(entry)
        save_report()
    elif proof['experiments'][index] != entry:
        proof['experiments'][index] = entry
        save_report()


class Client:
    def __init__(self, user, password):
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None
        status, body, _ = self.request('POST', '/console/login', dict(username=user, password=password))
        assert status == 200, ('LOGIN_FAILED', status)
        self.csrf = body['csrfToken']

    def request(self, method, path, body=None, etag=None, key=None):
        headers = {'Origin': origin, 'Content-Type': 'application/json'}
        if self.csrf:
            headers['X-CSRF-Token'] = self.csrf
        if etag:
            headers['If-Match'] = etag
        command = None
        if method != 'GET':
            headers['Idempotency-Key'] = key or str(uuid.uuid4())
            if path != '/console/login':
                command = {'method': method, 'path': path, 'key': headers['Idempotency-Key'], 'outcome': 'DISPATCHING'}
                proof['commands'].append(command)
                save_report()
        proof['httpRequests'] += 1
        proof['lastHttp'] = {'method': method, 'path': path, 'status': None}
        request = urllib.request.Request(origin + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            try:
                with self.http.open(request, timeout=120) as response:
                    status, reply_headers = response.status, response.headers
                    proof['lastHttp']['status'] = status
                    if command is not None:
                        command['httpStatus'] = status
                    data = response.read()
                    result = json.loads(data) if 'json' in reply_headers.get('Content-Type', '') else data.decode()
            except urllib.error.HTTPError as error:
                status, reply_headers = error.code, error.headers
                proof['lastHttp']['status'] = status
                if command is not None:
                    command['httpStatus'] = status
                data = error.read()
                try:
                    result = json.loads(data)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    result = {'code': 'NON_JSON_ERROR_RESPONSE'}
            proof['lastHttp']['status'] = status
            if command is not None:
                command.update(httpStatus=status, outcome='ACCEPTED' if 200 <= status < 300 else 'OUTCOME_UNCONFIRMED' if status >= 500 else 'REJECTED')
                if 200 <= status < 300 and path.startswith(base + '/applications/'):
                    app_id = path[len(base + '/applications/'):].split('/')[0]
                    if app_id not in proof['applications']:
                        proof['applications'].append(app_id)
                save_report()
            elif status >= 400:
                save_report()
            return status, result, reply_headers
        except Exception as error:
            if command is not None:
                command.update(outcome='OUTCOME_UNCONFIRMED', errorType=type(error).__name__)
            proof['lastHttp']['errorType'] = type(error).__name__
            save_report()
            raise

    def ok(self, method, path, body=None, etag=None, key=None):
        code, data, headers = self.request(method, path, body, etag, key)
        assert 200 <= code < 300, ('HTTP_REQUEST_FAILED', method, path, code)
        return data, headers


def run_done(task_id, expected='COMPLETED'):
    deadline = time.time() + 150
    while time.time() < deadline:
        task, _ = operator.ok('GET', base + '/tasks/' + task_id)
        run = task['run']
        record_run(run, task_id)
        if run['status'] in ['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED', 'BUDGET_EXCEEDED', 'NEEDS_ATTENTION', 'WAITING_INPUT', 'WAITING_APPROVAL']:
            assert run['status'] == expected, ('UNEXPECTED_RUN_STATUS', run['id'], run['status'], expected)
            return task
        time.sleep(0.8)
    raise AssertionError('Run did not reach expected state; last observed status is retained in runs')


def edit(app, changes):
    fields = ['name', 'templateId', 'instructions', 'modelProfileId', 'knowledgeId', 'toolIds', 'outputFormat', 'requireReview', 'approvers', 'limits', 'samples']
    body = {field: copy.deepcopy(app[field]) for field in fields}
    body.update(changes)
    return body


def change(app, operation, body):
    return operator.ok('POST', base + '/applications/' + app['id'] + '/' + operation, body, '"s' + str(app['revision']) + '"')[0]


def evaluate(app):
    app = change(app, 'evaluate', {})
    experiment_id = app['lastExperiment']['id']
    deadline = time.time() + 180
    while time.time() < deadline:
        experiment, _ = operator.ok('GET', base + '/experiments/' + experiment_id)
        record_experiment(experiment)
        if experiment['status'] != 'RUNNING':
            assert experiment['status'] == 'COMPLETED' and experiment['passed'], ('EVALUATION_NOT_PASSED', experiment_id, experiment['status'])
            return app, experiment
        time.sleep(1)
    raise AssertionError('Evaluation unfinished; observed experiment and Run states are retained')


def run_standard():
    collection='sources-'+suffix
    c,_=operator.ok('PUT',project+'/knowledge/collections/'+collection,dict(name='验收资料 '+suffix,visibility='PROJECT',allowedSubjects=['operator'],disabled=False),'"k0"')
    d,_=operator.ok('PUT',project+'/knowledge/collections/'+collection+'/documents/report',dict(title='Atlas 项目合成资料',text='Atlas 项目的合成验收预算为 4200 元，负责人为虚构角色林工。所有资料均为合成测试数据。',revoked=False),'"k0"')
    templates,_=operator.ok('GET',base+'/templates');caps,_=operator.ok('GET',base+'/capabilities');assert len(templates['items'])==3 and caps['models'];check('independent_identity_templates_capabilities')
    draft=dict(name='资料研究验收 '+suffix,templateId='research',instructions='仅根据授权资料回答。回答必须包含 Atlas 和资料中的预算数值，给出简短来源标号；无资料时明确说无资料。',modelProfileId='deepseek-pro',knowledgeId=collection,toolIds=[],outputFormat='markdown',requireReview=False,approvers=[],samples=[dict(id='budget',name='预算事实',input=dict(question='Atlas 的预算是多少？'),expectedContains=['4200'])])
    appId='research-'+suffix;key=str(uuid.uuid4());app,h=operator.ok('PUT',base+'/applications/'+appId,draft,'"s0"',key)
    replay,_=operator.ok('PUT',base+'/applications/'+appId,draft,'"s0"',key);assert replay==app
    receipt,_=operator.ok('GET',base+'/commands/'+key);assert receipt['response']==app
    assert viewer.request('GET',base+'/commands/'+key)[0]==404
    assert operator.request('PUT',base+'/applications/'+appId,draft,'"s0"')[0]==412;check('cas_and_actor_scoped_command_receipt')
    app=change(app,'preview',dict(input=dict(question='Atlas 的预算是多少？')));task=run_done(app['lastTask']['id']);assert task['run']['output']['visibility']=='AVAILABLE' and '4200' in task['run']['output']['value'];assert task['artifacts'];artifact=task['artifacts'][0]
    data,_=operator.ok('GET',base+'/tasks/'+task['id']+'/artifacts/'+artifact['id']);assert '4200' in data
    code,_,_=viewer.request('POST',project+'/runs',dict(releaseRef=task['run']['releaseRef'],inputs=dict(question='Atlas 的预算是多少？')));assert code in [403,404];assert viewer.request('GET',base+'/tasks/'+task['id'])[0]==404;check('real_preview_knowledge_artifact_private_snapshot')
    app,e=evaluate(app);app=change(app,'publish',dict(reviewConfirmed=True,evaluationId=e['id']));assert app['defaultVersion']==1;check('frozen_samples_gate_first_publication')
    app=change(app,'run',dict(input=dict(question='Atlas 的预算是多少？'),sessionId=task['sessionId']));published=run_done(app['lastTask']['id']);assert published['sessionId']==task['sessionId']
    body=edit(app,dict(modelProfileId='deepseek-flash',instructions='只根据授权资料简短回答，必须给出预算数值，保留 [1] 来源标号。'))
    app,_=operator.ok('PUT',base+'/applications/'+appId,body,'"s'+str(app['revision'])+'"')
    assert operator.request('POST',base+'/applications/'+appId+'/publish',dict(reviewConfirmed=True,evaluationId=e['id']),'"s'+str(app['revision'])+'"')[0]==409
    app,e=evaluate(app);assert all('baseline' in row for row in e['rows']);app=change(app,'publish',dict(reviewConfirmed=True,evaluationId=e['id']));assert app['defaultVersion']==2
    old_release=published['run']['releaseRef'];app=change(app,'default',dict(version=1));assert app['defaultVersion']==1
    same,_=operator.ok('GET',base+'/tasks/'+published['id']);assert same['run']['releaseRef']==old_release;check('live_model_comparison_stale_gate_rollback_stable_existing_run')
    app=change(app,'disable',dict(disabled=True));assert operator.request('POST',project+'/runs',dict(releaseRef=old_release,inputs=dict(question='Atlas')))[0]==409
    app=change(app,'disable',dict(disabled=False));check('disable_enforced_on_generic_run_api')
    structured=dict(name='结构化资料验收 '+suffix,templateId='structured',instructions='你是结构化字段转换器。用户消息中的资料字段就是已提供的资料，company 是公司名称，budget 是预算值（单位元）。请将两个字段逐项列出，不得忽略已给出的字段，不需要其他资料，不使用外部知识。',modelProfileId='deepseek-pro',samples=[dict(id='company',name='表单事实',input=dict(question='请总结',fields=dict(company='Synthetic',budget='6300')),expectedContains=['6300'])])
    sapp,_=operator.ok('PUT',base+'/applications/structured-'+suffix,structured,'"s0"');sapp=change(sapp,'preview',dict(input=dict(question='请总结',fields=dict(company='Synthetic',budget='6300'))));stask=run_done(sapp['lastTask']['id']);assert '6300' in stask['run']['output']['value'];sapp,se=evaluate(sapp);sapp=change(sapp,'publish',dict(reviewConfirmed=True,evaluationId=se['id']));check('second_structured_application_same_studio_and_runtime')
    c,_=operator.ok('PUT',project+'/knowledge/collections/'+collection,dict(name=c['name'],visibility='PROJECT',allowedSubjects=['viewer'],disabled=False),'"k'+str(c['revision'])+'"')
    hidden,_=operator.ok('GET',base+'/tasks/'+task['id']);assert hidden['run']['output']['visibility']=='OMITTED' and not hidden['artifacts'];assert operator.request('GET',base+'/tasks/'+task['id']+'/artifacts/'+artifact['id'])[0]==404;check('current_acl_hides_model_answer_and_artifact')
    # Restore this synthetic collection for interactive product review.
    operator.ok('PUT',project+'/knowledge/collections/'+collection,dict(name=c['name'],visibility='PROJECT',allowedSubjects=['operator'],disabled=False),'"k'+str(c['revision'])+'"')
    proof.update(project='studio-dev',models=['deepseek-v4-pro','deepseek-flash'],knowledgeCollection=collection);save_report()


def run_additional():
    extra=dict(name='跨供应商验收 '+suffix,templateId='structured',instructions='Return exactly the value of the confirmation field in the user-provided fields. Do not add other text.',modelProfileId=args.additional_model,samples=[dict(id='confirmation',name='字段原样返回',input=dict(question='Return the confirmation field',fields=dict(confirmation='OpenAI connector ready')),expectedContains=['OpenAI connector ready'])])
    eapp,_=operator.ok('PUT',base+'/applications/provider-'+suffix,extra,'"s0"');eapp=change(eapp,'preview',dict(input=extra['samples'][0]['input']));etask=run_done(eapp['lastTask']['id']);assert 'OpenAI connector ready' in etask['run']['output']['value']
    eapp,ee=evaluate(eapp);eapp=change(eapp,'publish',dict(reviewConfirmed=True,evaluationId=ee['id']));proof['additionalModelProfile']=args.additional_model;check('second_provider_live_generation_evaluation_publication')


save_report()
try:
    secrets = json.loads((private / 'credentials.private.json').read_text())
    operator = Client('operator', secrets['password'])
    # The additional-only path deliberately never instantiates the baseline viewer or runs baseline checks.
    if not args.additional_only:
        viewer = Client('viewer', secrets['viewerPassword'])
        run_standard()
    if args.additional_model:
        run_additional()
    proof['status'] = 'PASSED'
except Exception as error:
    proof['status'] = 'FAILED'
    # Do not serialize arbitrary response bodies, prompts, passwords or tokens into failure text.
    details = error.args[0] if isinstance(error, AssertionError) and error.args else None
    proof['failure'] = {'type': type(error).__name__, 'message': details if isinstance(details, str) else list(details) if isinstance(details, tuple) else 'Acceptance stopped; inspect recorded HTTP, Run and experiment states'}
    save_report()
    print(json.dumps({'status': 'FAILED', 'checks': len(proof['checks']), 'report': str(report), 'failureType': type(error).__name__}), flush=True)
    raise SystemExit(1)
else:
    save_report()
    print(json.dumps({'status': 'PASSED', 'checks': len(proof['checks']), 'report': str(report)}), flush=True)
