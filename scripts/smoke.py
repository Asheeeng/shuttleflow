#!/usr/bin/env python3
"""Real HTTP + real MySQL demo acceptance, no third-party Python packages."""
import argparse,json,sys,urllib.request,urllib.error,http.cookiejar
from concurrent.futures import ThreadPoolExecutor
p=argparse.ArgumentParser();p.add_argument('--base',default='http://127.0.0.1:8080/api');p.add_argument('--reset-demo',action='store_true',help='Explicitly authorize clearing demo operational records');args=p.parse_args()
if not args.reset_demo:sys.exit('此脚本会重置演示业务数据，请显式使用 --reset-demo（仅demo环境）。')
checks=0
def check(condition,label):
 global checks
 if not condition:raise AssertionError(label)
 checks+=1;print(f'PASS {checks:02d} {label}')
class Client:
 def __init__(self):self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf=None
 def call(self,path,body=None,method=None,csrf=True):
  headers={'Content-Type':'application/json'}
  if body is not None and csrf and self.csrf:headers[self.csrf['headerName']]=self.csrf['token']
  req=urllib.request.Request(args.base+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method or ('GET' if body is None else 'POST'))
  try:
   with self.opener.open(req,timeout=15) as res:return res.status,json.load(res)
  except urllib.error.HTTPError as e:return e.code,json.load(e)
 def login(self,role):
  status,body=self.call('/auth/csrf');self.csrf=body['data']
  status,body=self.call('/auth/login',{'username':role,'password':'Demo123456!'})
  check(status==200 and body['data']['username']==role,f'{role} session login')
  return self
admin=Client().login('admin');passenger=Client().login('passenger');driver=Client().login('driver')
check(admin.call('/admin/demo/reset',{'confirmation':'RESET DEMO'})[0]==200,'confirmed demo reset')
check(Client().call('/admin/overview')[0]==401,'anonymous access rejected')
check(passenger.call('/admin/overview')[0]==403,'passenger cannot access admin API')
check(admin.call('/driver/task')[0]==403,'admin cannot impersonate driver')
route=passenger.call('/routes')[1]['data'][0];s=route['stops'];rid=route['id']
journey={'routeId':rid,'originStopId':s[2]['id'],'destinationStopId':s[3]['id'],'partySize':1}
invalid={**journey,'originStopId':s[3]['id'],'destinationStopId':s[2]['id']}
check(passenger.call('/passenger/requests',invalid)[0]==400,'reverse journey rejected')
check(passenger.call('/passenger/requests',journey,csrf=False)[0]==403,'write without CSRF rejected')
status,result=passenger.call('/passenger/requests',journey);request=result['data']
check(status==200 and request['status']=='WAITING','A: persisted WAITING request C to D')
check(passenger.call('/passenger/requests',journey)[0]==409,'A: duplicate request rejected')
demand=admin.call('/admin/demand')[1]['data']
check(next(x for x in demand if x['stopId']==s[2]['id'])['waitingPeople']==1,'A: admin C demand increased')
query=f"/passenger/eta?routeId={rid}&originStopId={s[2]['id']}&destinationStopId={s[3]['id']}"
eta=passenger.call(query)[1]['data'];check(eta['status']=='AVAILABLE' and 390<=eta['remainingSeconds']<=420,'B: A to C ETA uses 7-minute history')
arrival={'stopId':s[1]['id'],'countsConfirmed':True,'boarded':2,'alighted':1}
check(driver.call('/driver/arrivals',{**arrival,'stopId':s[2]['id']})[0]==400,'D: skipped station rejected')
check(driver.call('/driver/arrivals',{**arrival,'alighted':11})[0]==400,'D: excessive alighting rejected')
check(driver.call('/driver/arrivals',{**arrival,'boarded':12})[0]==400,'D: overload rejected')
check(driver.call('/driver/task')[1]['data']['occupancy']==10,'D: rejected updates rolled back')
status,result=driver.call('/driver/arrivals',arrival)
check(status==200 and result['data']['occupancy']==11 and result['data']['lastStopName']=='B站','B: arrival B updates occupancy 10 - 1 + 2 = 11')
eta=passenger.call(query)[1]['data'];check(eta['status']=='AVAILABLE' and 160<=eta['remainingSeconds']<=180,'B: latest B report recalculates ETA to C')
check(passenger.call('/passenger/requests/current')[1]['data']['status']=='WAITING','B: driver does not auto-board passenger')
check(admin.call('/admin/vehicles')[1]['data'][0]['occupancy']==11,'B: admin vehicle data synchronized')
status,result=passenger.call(f"/passenger/requests/{request['id']}/board",{})
check(status==200 and result['data']['status']=='BOARDED','C: passenger confirms boarding')
check(admin.call('/admin/overview')[1]['data']['waitingPeople']==0,'C: active demand decreased to zero')
check(passenger.call('/passenger/requests')[1]['data'][0]['status']=='BOARDED','C: history preserved')
status,result=passenger.call('/passenger/requests',journey);cancel_id=result['data']['id']
check(passenger.call(f'/passenger/requests/{cancel_id}/cancel',{})[1]['data']['status']=='CANCELLED','cancel request ends demand')
clients=[Client().login('passenger') for _ in range(4)]
with ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(lambda c:c.call('/passenger/requests',journey),clients))
check(sorted(status for status,_ in results)==[200,409,409,409],'D: real MySQL concurrent creation permits exactly one active request')
active=passenger.call('/passenger/requests/current')[1]['data'];passenger.call(f"/passenger/requests/{active['id']}/cancel",{})
check(admin.call('/admin/overview')[1]['data']['activeRequests']==0,'final active demand cleared')
check(passenger.call('/auth/logout',{})[0]==200 and passenger.call('/auth/me')[0]==401,'logout revokes session')
print(f'\n{checks} real-HTTP assertions passed. Demo vehicle remains at B with occupancy 11/20.')
