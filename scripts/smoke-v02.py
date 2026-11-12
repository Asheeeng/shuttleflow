#!/usr/bin/env python3
"""V0.2 real HTTP / MySQL dispatch acceptance; uses only Python standard library."""
import argparse,json,http.cookiejar,urllib.request,urllib.error,time
from concurrent.futures import ThreadPoolExecutor
p=argparse.ArgumentParser();p.add_argument('--base',default='http://127.0.0.1:8080/api');p.add_argument('--reset-demo',action='store_true');p.add_argument('--check-timeout',action='store_true',help='Actually wait for the server offer deadline (default two minutes)');args=p.parse_args()
if not args.reset_demo:raise SystemExit('将替换本地演示运营数据，请显式添加 --reset-demo')
n=0
def check(value,message):
 global n
 if not value:raise AssertionError(message)
 n+=1;print(f'PASS {n:02d} {message}',flush=True)
class Client:
 def __init__(self,name):
  self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf=None
  self.csrf=self.call('/auth/csrf')[1]['data'];status,body=self.call('/auth/login',{'username':name,'password':'Demo123456!'})
  check(status==200 and body['data']['username']==name,f'{name} session login')
 def call(self,path,body=None,method=None):
  headers={'Content-Type':'application/json'}
  if self.csrf:headers[self.csrf['headerName']]=self.csrf['token']
  req=urllib.request.Request(args.base+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method or ('GET' if body is None else 'POST'))
  try:
   with self.opener.open(req,timeout=15) as r:return r.status,json.load(r)
  except urllib.error.HTTPError as e:return e.code,json.load(e)
 def get(self,path):status,b=self.call(path);assert status==200,(status,b);return b['data']
admin=Client('admin');passenger=Client('passenger')
status,peak=admin.call('/admin/demo/peak',{'confirmation':'INIT PEAK'});check(status==200 and '模拟' in peak['data']['source'],'peak simulation source explicitly labeled')
driver=Client('driver');d2=Client('driver2');d4=Client('driver4')
route=passenger.get('/routes')[0];rid=route['id'];stops=route['stops'];target=stops[2]['id']
board=admin.get('/admin/dispatch-board');alert=next(a for a in board['alerts'] if a['stopId']==target)
check(alert['demand']==30 and alert['allocated']==12 and alert['gap']==18,'two-car alight-first forecast yields C demand30 / supply12 / gap18')
check(alert['level']=='HIGH' and len(alert['vehicles'])==2,'high-peak alert includes per-vehicle explanations')
fleet=admin.get('/admin/fleet');check([v['status'] for v in fleet].count('RUNNING')==2 and [v['status'] for v in fleet].count('STANDBY')==2 and [v['status'] for v in fleet].count('UNAVAILABLE')==1,'daily fleet separates two running / two standby / one unavailable')
suggestion=next(s for s in board['suggestions'] if s['stopId']==target);check([c['vehicleCode'] for c in suggestion['candidates']]==['DEMO-02','DEMO-04'],'candidate ordering uses ready time and same-direction ETA')
check(passenger.call('/admin/dispatch-board')[0]==403,'passenger cannot read dispatch control board')
check(driver.call('/admin/dispatches',{})[0]==403,'driver cannot issue admin orders')
body={'routeId':rid,'targetStopId':target,'vehicleId':suggestion['candidates'][0]['vehicleId']}
with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:admin.call('/admin/dispatches',body),range(2)))
check(sorted(status for status,_ in results)==[200,409],'concurrent admin confirmation creates one coverage reservation')
o=next(x['data'] for status,x in results if status==200);oid=o['id']
check(o['status']=='PENDING' and '缺口18人' in o['reason'],'issued task stores explainable decision and pending state')
check(d2.get('/driver/task')['status']=='STANDBY','pending dispatch does not fabricate running capacity')
check(driver.call(f'/driver/dispatches/{oid}/respond',{'accept':True,'occupancy':0})[0]==404,'driver cannot respond to another driver order')
status,result=d2.call(f'/driver/dispatches/{oid}/respond',{'accept':False,'note':'演示：临时车辆检查'});check(status==200 and result['data']['escalated'],'rejection escalates immediately to admin')
status,result=admin.call(f'/admin/dispatches/{oid}/retry',{});o=result['data'];check(status==200 and o['vehicleCode']=='DEMO-04' and o['previousTaskId']==oid,'admin-confirmed retry chooses next candidate and preserves parent history')
check(admin.call(f'/admin/dispatches/{oid}/retry',{})[0]==409,'same rejected order cannot be retried twice')
check(admin.call('/admin/fleet/'+str(o['vehicleId']),{'status':'UNAVAILABLE','reason':'检修','readyInSeconds':60},'PATCH')[0]==409,'reserved vehicle cannot be changed to unavailable')
check(d4.call(f"/driver/dispatches/{o['id']}/respond",{'accept':True,'occupancy':21})[0]==400,'overcapacity load at acceptance rejected')
status,result=d4.call(f"/driver/dispatches/{o['id']}/respond",{'accept':True,'occupancy':0});check(status==200 and result['data']['status']=='ACCEPTED','driver confirmation activates dispatch')
check(d4.get('/driver/task')['status']=='RUNNING','accepted vehicle is actually running in shared database')
check(d4.call(f"/driver/dispatches/{o['id']}/respond",{'accept':True,'occupancy':0})[0]==409,'acceptance is not repeatable')
check(d4.call('/driver/arrivals',{'stopId':target,'countsConfirmed':True,'boarded':0,'alighted':0})[0]==400,'dispatch still enforces route station sequence')
check(d4.call('/driver/arrivals',{'stopId':stops[1]['id'],'countsConfirmed':True,'boarded':0,'alighted':0})[0]==200,'dispatch driver reports intermediate B station')
check(d4.call('/driver/arrivals',{'stopId':target,'countsConfirmed':True,'boarded':18,'alighted':0})[1]['data']['occupancy']==18,'target arrival applies real occupancy transaction')
completed=next(t for t in admin.get('/admin/dispatch-board')['tasks'] if t['id']==o['id']);check(completed['status']=='COMPLETED' and completed['completedAt'],'target arrival closes dispatch task')
check(admin.get('/admin/overview')['waitingPeople']==32,'dispatch completion never silently marks passenger requests boarded')
check(all(v['occupancy'] is None or 0<=v['occupancy']<=v['capacity'] for v in admin.get('/admin/vehicles')),'all actual vehicle loads stay within rated capacity')
admin.call('/admin/demo/peak',{'confirmation':'INIT PEAK'});fresh=next(s for s in admin.get('/admin/dispatch-board')['suggestions'] if s['stopId']==target)
with ThreadPoolExecutor(max_workers=2) as pool:concurrent=list(pool.map(lambda v:admin.call('/admin/dispatches',{'routeId':rid,'targetStopId':target,'vehicleId':v['vehicleId']}),fresh['candidates']))
check(sorted(status for status,_ in concurrent)==[200,409],'different standby vehicles cannot reserve the same capacity gap twice in MySQL')
if args.check_timeout:
 admin.call('/admin/demo/peak',{'confirmation':'INIT PEAK'});board=admin.get('/admin/dispatch-board');s=next(s for s in board['suggestions'] if s['stopId']==target);_,result=admin.call('/admin/dispatches',{'routeId':rid,'targetStopId':target,'vehicleId':s['candidates'][0]['vehicleId']});timeout_order=result['data'];check(timeout_order['status']=='PENDING','real deadline test starts a pending task')
 print('WAIT real server deadline '+timeout_order['deadlineAt']+'; no database clock manipulation',flush=True)
 limit=time.monotonic()+150
 while time.monotonic()<limit:
  current=next(t for t in admin.get('/admin/dispatch-board')['tasks'] if t['id']==timeout_order['id'])
  if current['status']=='TIMED_OUT':break
  time.sleep(2)
 else:raise AssertionError('server deadline did not expire within 150 seconds')
 check(current['escalated'] and current['status']=='TIMED_OUT','real elapsed deadline expires and escalates task')
 check(d2.get('/driver/task')['status']=='STANDBY','timeout releases reservation without starting vehicle')
 check(d2.call(f"/driver/dispatches/{current['id']}/respond",{'accept':True,'occupancy':0})[0]==409,'late acceptance of timed-out task rejected')
 check(admin.call(f"/admin/dispatches/{current['id']}/resolve",{'note':'接口验收：人工核实，不再顺延'})[1]['data']['resolvedBy'] is not None,'escalated timeout can be resolved with auditable admin identity')
print(f'\n{n} V0.2 real-HTTP assertions passed.',flush=True)
