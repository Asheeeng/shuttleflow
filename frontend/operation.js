(() => {
  'use strict';
  const root = document.querySelector('[data-operation]');
  if (!root) return;
  const role = root.dataset.operation;
  const full = role === 'workspace';
  const labels = {
    normal:'正常运营', class_peak:'下课高峰', surge:'突发客流', multi_vehicle:'多车竞争',
    capacity_shortage:'车辆不足', driver_refusal:'司机拒单顺延', all_day:'全天运营', custom:'自定义',
    fixed:'固定班次', threshold:'简单阈值', smart:'智能调度',
    idle:'待运行', preparing:'正在创建仿真车队', running:'运行中', paused:'已暂停', stopping:'清理中',
    completed:'已完成', stopped:'已停止', error:'运行失败', ready:'待发车', dwell:'停站', moving:'行驶中',
    reserve:'待命', manual:'人工驾驶', fault:'故障', withdrawn:'退出运营', interrupted:'未完成行程',
    created:'创建', assigned:'派车', accepted:'接单', executing:'执行', rejected:'拒单',
    reassigned:'改派', escalated:'升级', yellow:'黄色', orange:'橙色', red:'红色'
  };
  const esc = value => String(value ?? '—').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[ch]);
  const option = (value, name) => `<option value="${esc(value)}">${esc(name)}</option>`;
  const options = keys => keys.map(key => option(key, labels[key] || key)).join('');
  const stateOf = key => labels[key] || key || '—';
  const direction = key => key === 'back' ? '返程' : '去程';
  const formatSeconds = value => value == null ? '—' : `${Math.floor(value / 60)} 分 ${Math.round(value % 60)} 秒`;
  const $ = selector => root.querySelector(selector);
  const empty = text => `<p class="op-empty">${esc(text)}</p>`;
  const tone = phase => {
    if (['running','moving','dwell','executing'].includes(phase)) return 'live';
    if (['completed','assigned','accepted','reassigned'].includes(phase)) return 'good';
    if (['paused','rejected','reserve'].includes(phase)) return 'hold';
    if (['error','fault','withdrawn','stopped','escalated'].includes(phase)) return 'bad';
    return ['preparing','stopping'].includes(phase) ? 'busy' : '';
  };
  const chip = phase => `<span class="op-status-chip op-tone-${tone(phase)}">${esc(stateOf(phase))}</span>`;
  const loadLevel = ratio => ratio >= .9 ? 'high' : ratio >= .7 ? 'mid' : 'low';
  const button = (label, attr, style='') => `<button class="btn ${style}" type="button" ${attr}>${esc(label)}</button>`;
  function feedback(text='', kind='') {
    const node = $('[data-feedback]');
    node.className = 'op-feedback' + (kind ? ' is-' + kind : '');
    node.textContent = text;
  }
  const panel = (title, content, style='') => `<section class="op-panel ${style}"><h3>${title}</h3>${content}</section>`;
  let snapshot, activeSnapshot, archivedSnapshot, routeKey, carId, tripId, historyRun, socket, timer, requesting, refreshAgain;
  const tabs = {overview:'运营总览', config:'仿真配置', vehicles:'车辆运行', dispatch:'智能调度', comparison:'策略对比', advanced:'高级实验'};
  let tab = 'overview';
  let replay = null;
  let mounted = false;
  let loading = false;
  const defaultFleet = {lineId:1, direction:'go', count:3, capacity:13, initialStation:0, initialLoad:0, headway:150, travel:0, dwell:18, manual:0};
  const presetFleet = {
    normal:[1,'go',1,18], class_peak:[2,'go',2,34], surge:[3,'go',2,24],
    multi_vehicle:[1,'back',2,31], capacity_shortage:[3,'back',0,26], driver_refusal:[2,'back',2,18]
  };
  const field = (label, name, value, type='number') => `<label>${label}<input name="${name}" type="${type}" value="${esc(value)}" required></label>`;
  const select = (label, name, entries) => `<label>${label}<select name="${name}">${entries}</select></label>`;

  async function api(path, payload) {
    const response = await fetch(path, payload ? {method:'POST', headers:window.ShuttleAuth.headers({'Content-Type':'application/json'}),body:JSON.stringify(payload)} : {});
    const result = await response.json();
    if (!response.ok || result.code !== 200) throw new Error(result.msg || '仿真接口不可用');
    return result.data;
  }

  function addFleet(data = defaultFleet) {
    const row = document.createElement('div');
    row.className = 'op-fleet';
    row.innerHTML = `<div class="op-panel-header"><strong>线路与车辆设置</strong>${button('移除车组','data-remove','btn-danger')}</div>
      <div class="op-input-grid">${select('线路','lineId',[1,2,3].map(i => option(i,i+'号线')).join(''))}
      ${select('方向','direction',option('go','去程')+option('back','返程'))}
      ${select('初始站','initialStation',option(0,'该方向首站'))}
      ${field('车辆数','count',data.count)}${field('座位','capacity',data.capacity)}
      ${field('初始载客','initialLoad',data.initialLoad)}${field('人工车辆数','manual',data.manual)}
      ${field('发车间隔（秒）','headway',data.headway)}
      ${field('站间秒数（0 = 线路配置）','travel',data.travel)}
      ${field('停站（秒）','dwell',data.dwell)}</div>`;
    for (const key of ['lineId','direction']) row.querySelector(`[name="${key}"]`).value = data[key];
    let choices = 0;
    const stops = async () => {
      const request = ++choices;
      const line = row.querySelector('[name=lineId]').value;
      const dir = row.querySelector('[name=direction]').value;
      const picker = row.querySelector('[name=initialStation]');
      const prior = picker.options.length > 1 ? picker.value : data.initialStation;
      try {
        const result = await api(`/api/line/${line}/stations?direction=${dir}`);
        if (request !== choices) return;
        picker.innerHTML = option(0,'该方向首站') + result.slice(0,-1).map(stop => option(stop.stationId,stop.stationName)).join('');
        picker.value = Array.from(picker.options).some(x => x.value === String(prior)) ? prior : '0';
      } catch (error) { feedback(error.message,'error'); }
    };
    row.querySelector('[name=lineId]').onchange = stops;
    row.querySelector('[name=direction]').onchange = stops;
    row.querySelector('[data-remove]').onclick = () => row.remove();
    $('[data-fleet]').append(row);
    stops();
  }

  function applyConfig(config) {
    for (const [key, value] of Object.entries(config)) {
      const input = $(`[data-config-fields] [name="${key}"]`);
      if (input) input.value = key === 'start' ? value.slice(0,16) : value;
    }
    $('[data-fleet]').replaceChildren();
    for (const fleet of config.fleet || [defaultFleet]) addFleet({...defaultFleet,...fleet});
    $('[name=reject]').checked = Boolean(config.reject);
  }

  function preset(key) {
    const base = {scenario:key, start:'2026-11-18T07:50', minutes:100, speed:120, seed:20261118, passengers:100,
                  wait:40, policy:'smart', flow:'uniform', pattern:'mixed', fleet:[defaultFleet]};
    if (presetFleet[key]) {
      const [lineId,dir,count,passengers] = presetFleet[key];
      Object.assign(base,{passengers, reject:key==='driver_refusal',fleet:[{...defaultFleet,lineId,direction:dir,count,initialLoad:key==='multi_vehicle'?9:0}]});
    }
    if (key === 'class_peak') base.flow='peak';
    if (key === 'surge') base.flow='burst';
    if (key === 'all_day') Object.assign(base,{start:'2026-11-18T07:00',minutes:900,speed:600,passengers:300,flow:'peak',fleet:[1,2,3].map(lineId=>({...defaultFleet,lineId,count:2}))});
    applyConfig(base);
  }

  function configOfForm() {
    const form = $('[data-config-form]');
    if (!form.reportValidity()) throw new Error('请填写完整配置');
    const values = Object.fromEntries(new FormData(form));
    for (const key of ['minutes','speed','passengers','seed','wait']) values[key] = Number(values[key]);
    values.reject = $('[name=reject]').checked;
    values.fleet = [...root.querySelectorAll('.op-fleet')].map(row => Object.fromEntries(
      [...row.querySelectorAll('[name]')].map(input => [input.name,input.name === 'direction' ? input.value : Number(input.value)])));
    return values;
  }

  function configMarkup() {
    return `<form class="op-form" data-config-form>
      <div class="op-config-grid" data-config-fields>
        <section class="op-panel op-config-card"><h3>场景与时钟</h3><div class="op-input-grid">
          ${select('场景','scenario',options([...Object.keys(presetFleet),'all_day','custom']))}
          ${field('模拟开始时间','start','2026-11-18T07:50','datetime-local')}${field('运营分钟','minutes',100)}
          ${field('运行倍率','speed',120)}${field('随机种子','seed',20261118)}</div></section>
        <section class="op-panel op-config-card"><h3>客流与调度</h3><div class="op-input-grid">
          ${field('乘客数','passengers',100)}${field('最大候车分钟','wait',40)}
          ${select('客流','flow',option('uniform','均匀')+option('random','随机')+option('peak','上下课高峰')+option('burst','突发'))}
          ${select('策略','policy',options(['fixed','threshold','smart']))}
          ${select('运营模式','pattern',option('mixed','自动与人工混合')+option('auto','全自动'))}
          <label class="op-check"><input name="reject" type="checkbox">首单模拟拒绝</label></div></section>
      </div>
      <section class="op-panel op-settings"><div class="op-panel-header"><h3>车辆编组</h3>${button('添加车组','data-add')}</div>
        <div data-fleet class="op-fleet-list"></div></section>
    </form>`;
  }

  function setTab(next, focus=false) {
    tab=next;
    root.querySelectorAll('[data-tab]').forEach(item=>{
      const selected=item.dataset.tab===tab;
      item.setAttribute('aria-selected',String(selected));
      item.tabIndex=selected?0:-1;
      const section=$('#op-panel-'+item.dataset.tab);
      if(section) section.hidden=!selected;
      if(selected&&focus)item.focus();
    });
    const advanced=document.getElementById('op-panel-advanced');
    if(advanced) {
      advanced.dataset.managed='';
      advanced.hidden=tab!=='advanced';
      if(!advanced.hidden)advanced.open=true;
    }
    if(tab!=='vehicles'&&replay)replay.playing=false;
    if(tab==='vehicles'&&historyRun&&archivedSnapshot?.runId!==historyRun)refresh();
    render();
  }

  function setup() {
    const title=full?'运营仿真控制台':role==='admin'?'运营仿真大屏':role==='driver'?'仿真行程与上下客预测':'仿真车辆位置与到站时间';
    root.innerHTML = `<header class="op-head">
      <div class="op-bar"><div class="op-title"><span class="sf-mark" aria-hidden="true">SF</span><div><h2>${title}</h2>
      <p class="op-note">车辆位置按线路进度推算</p></div></div><span class="op-badge">本机隔离仿真</span></div>
      <div class="op-status-row"><div class="op-status" role="status" data-status></div>
      ${role==='admin'?'<a class="btn btn-primary op-action" href="simulation.html">打开运营仿真控制台</a>':''}
      ${full?`<div class="op-actions">${Object.entries({start:'开始',pause:'暂停',resume:'继续',stop:'停止',reset:'重置视图',save:'保存配置'}).map(([key,label])=>
        button(label,`data-command="${key}"`,({start:'btn-primary',resume:'btn-success',stop:'btn-danger',reset:'btn-danger'})[key])).join('')}</div>`:''}</div>
      <div class="op-feedback" role="status" data-feedback></div><div class="op-feedback is-error" role="alert" data-run-error></div>
      </header>
      ${full?`<nav class="op-tabs" role="tablist" aria-label="运营仿真工作台">${Object.entries(tabs).map(([key,label])=>
      `<button class="btn" type="button" role="tab" data-tab="${key}" id="op-tab-${key}" aria-controls="op-panel-${key}" aria-selected="${key===tab}" tabindex="${key===tab?0:-1}">${label}</button>`).join('')}</nav>
      <section class="op-tab-panel" id="op-panel-config" role="tabpanel" aria-labelledby="op-tab-config" tabindex="0" hidden>${configMarkup()}</section>
      <section class="op-tab-panel" id="op-panel-vehicles" role="tabpanel" aria-labelledby="op-tab-vehicles" tabindex="0" hidden>
        <div class="op-panel op-fields"><label>历史运行<select data-history><option value="">当前运行</option></select></label>
        ${button('导出历史原始记录','data-export')}</div><div class="op-tab-panel" data-board="vehicles"></div></section>
      ${['overview','dispatch','comparison'].map(key=>`<section class="op-tab-panel" id="op-panel-${key}" data-board="${key}" role="tabpanel" aria-labelledby="op-tab-${key}" tabindex="0"${key===tab?'':' hidden'}></section>`).join('')}
      <dialog data-account-dialog><h3>临时仿真司机账号</h3><p data-credentials></p><form method="dialog"><button class="btn">关闭</button></form></dialog>`
      :'<div class="op-tab-panel" data-board="overview"></div>'}`;
    if (!full) return;
    root.querySelectorAll('[data-tab]').forEach(item=>{
      item.onclick=()=>setTab(item.dataset.tab);
      item.onkeydown=event=>{
        const keys=Object.keys(tabs), index=keys.indexOf(item.dataset.tab);
        const target={ArrowLeft:(index+keys.length-1)%keys.length,ArrowRight:(index+1)%keys.length,Home:0,End:keys.length-1}[event.key];
        if(target===undefined)return;
        event.preventDefault();
        setTab(keys[target],true);
      };
    });
    $('[data-account-dialog]').onclose = () => {$('[data-credentials]').textContent='';};
    preset('custom');
    $('[name=scenario]').onchange = event => preset(event.target.value);
    $('[data-add]').onclick = () => {
      if (root.querySelectorAll('.op-fleet').length < 6) addFleet();
    };
    root.querySelectorAll('[data-command]').forEach(button => button.onclick = async () => {
      try {
        feedback();
        const action = button.dataset.command;
        const payload = {action};
        if (action === 'start' || action === 'save') payload.config = configOfForm();
        await api('/api/admin/operation/control',payload);
        if (action === 'start' || action === 'reset') {
          historyRun = ''; routeKey = carId = tripId = null; replay = null;
        }
        if (action === 'save') feedback('配置已保存','success');
        await refresh();
      } catch(error) { feedback(error.message,'error'); }
    });
    $('[data-history]').onchange = async event => {
      historyRun = event.target.value;
      routeKey = carId = tripId = null; replay = null;
      await refresh();
    };
    $('[data-export]').onclick = () => {
      if (!historyRun || snapshot?.runId !== historyRun) return;
      const url = URL.createObjectURL(new Blob([JSON.stringify(snapshot,null,2)],{type:'application/json'}));
      const a=document.createElement('a'); a.href=url; a.download=`shuttleflow-${historyRun}.json`; a.click();
      setTimeout(()=>URL.revokeObjectURL(url),500);
    };
  }

  function clock(value) {
    if (value == null) return '—';
    const base = snapshot.config?.start || snapshot.start;
    if (!base) return formatSeconds(value);
    const time = new Date(base + '+08:00');
    return new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).format(time.getTime()+value*1000);
  }

  function topology(route,cars,sec,trip) {
    const stops=route.stops, gap=135, left=64, width=Math.max(500,left*2+gap*(stops.length-1));
    const x=index=>left+index*gap;
    const stationLabels=stops.map((stop,i)=>{
      const waiting=snapshot.queues?.[`${route.lineId}:${route.direction}:${stop.stationId}`] ?? 0;
      const distance=i<stops.length-1?`<text class="op-svg-meta" x="${x(i)+gap/2}" y="84" text-anchor="middle" font-size="11">${esc(stop.distanceM)} m</text>`:'';
      return `${distance}<rect class="op-svg-stop ${i===0||i===stops.length-1?'terminal':''}" x="${x(i)-7}" y="103" width="14" height="14" rx="2"/>
        <text class="op-svg-text" x="${x(i)}" y="${i%2?162:140}" text-anchor="middle" font-size="12" font-weight="750">${esc(stop.shortName||stop.stationName)}</text>
        <text class="op-svg-meta" x="${x(i)}" y="${i%2?180:158}" text-anchor="middle" font-size="11">${waiting} 人候车</text>`;
    }).join('');
    if (trip && sec != null) {
      let i=trip.stops.findLastIndex(s=>s.arrived!==null&&s.arrived<=sec);
      if(i>=0) {
        const current=trip.stops[i], next=trip.stops[i+1];
        const progress=current.departed!=null&&next?.arrived!=null&&next.arrived>current.departed
          ? Math.max(0,Math.min(1,(sec-current.departed)/(next.arrived-current.departed))) : 0;
        cars=[{plate:trip.plate,index:i,progress,phase:'replay'}];
      } else cars=[];
    }
    const marks=cars.map((car,i)=>{
      const cx=x(car.index)+car.progress*gap, y=19+(i%3)*21;
      return `<g><title>${esc(car.plate)} ${esc(stateOf(car.phase))}</title>
      <line class="op-svg-guide" x1="${cx}" x2="${cx}" y1="${y+8}" y2="103"/>
      <rect class="op-car ${esc(car.phase)}" x="${cx-46}" y="${y-9}" width="92" height="19" rx="2"/>
      <path class="op-svg-line" d="M${cx-40},${y-4}h12v7h-12z m2,8h1 m7,0h1" fill="none" stroke-width="1.2"/>
      <text class="op-svg-text" x="${cx+6}" y="${y+4}" text-anchor="middle" font-size="10" font-weight="800">${esc(car.plate)} →</text></g>`;
    }).join('');
    return `<div class="op-scroll" data-map-scroll tabindex="0" role="region" aria-label="可横向滚动的线路图">
      <svg role="img" aria-label="线路拓扑与仿真车辆位置" viewBox="0 0 ${width} 194" style="width:max(100%, ${stops.length*82}px)">
      <path class="op-svg-line" d="M${left},110 H${x(stops.length-1)}" stroke-width="4"/>${stationLabels}${marks}</svg></div>`;
  }

  function plot(trip,car) {
    const width=Math.max(560,trip.stops.length*70), height=150, x=i=>38+i*(width-68)/Math.max(trip.stops.length-1,1);
    const y=value=>122-94*value/trip.capacity, real=[], estimate=[];
    trip.stops.forEach((stop,i)=>{if(stop.load!==null&&stop.departed!==null)real.push([x(i),y(stop.load)]);});
    if(car?.tripId===trip.id) {
      estimate.push([x(car.index),y(car.load)]);
      for(const p of car.predictions) {
        const i=trip.stops.findIndex(s=>s.stationId===p.stationId);
        if(i>=0)estimate.push([x(i),y(p.load)]);
      }
    }
    return panel('全程载客变化', `<p class="op-note">实线为离站实报，蓝色虚线为预计载客，红色虚线为核载上限。</p>
      <div class="op-scroll" tabindex="0" role="region" aria-label="可横向滚动的载客图">
      <svg width="${width}" height="${height}" role="img" aria-label="实际与预测载客量曲线">
      <line class="op-capacity-line" x1="36" y1="28" x2="${width-25}" y2="28"/>
      <text class="op-svg-meta" x="36" y="19" font-size="11">容量上限 ${trip.capacity}</text>
      <polyline class="op-actual-line" points="${real.map(p=>p.join(',')).join(' ')}"/>
      ${real.map(p=>`<circle class="op-actual" cx="${p[0]}" cy="${p[1]}" r="3"/>`).join('')}
      <polyline class="op-forecast-line" points="${estimate.map(p=>p.join(',')).join(' ')}"/>
      ${trip.stops.map((stop,i)=>`<text class="op-svg-meta" x="${x(i)}" y="143" text-anchor="middle" font-size="10">${i+1}</text>`).join('')}</svg></div>`, 'op-chart');
  }

  function table(headers, rows, extra='', noData='暂无记录') {
    if(!rows.length)return empty(noData);
    return `<div class="op-scroll" tabindex="0" role="region" aria-label="可横向滚动的数据表"><table ${extra}>
      <thead><tr>${headers.map(v=>`<th scope="col">${esc(v)}</th>`).join('')}</tr></thead>
      <tbody>${rows.map(row=>`<tr>${row.map(cell=>`<td>${cell}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
  }

  function metricsMarkup(metrics) {
    if(!metrics)return '';
    const names={checkins:'签到人数',boarded:'已上车',waiting:'未完成候车',cancelled:'取消候车',alighted:'已下车',averageWait:'平均候车'};
    return `<div class="sf-kpi-grid op-metrics">${Object.entries(names).map(([key,name])=>
      `<article class="sf-kpi-card"><span>${name}</span><strong data-metric="${key}">${esc(metrics[key])}
      ${metrics[key]==null?'':`<i>${key==='averageWait'?' 秒':' 人'}</i>`}</strong></article>`).join('')}</div>`;
  }

  function updateHeader() {
    const phase=snapshot.phase;
    const status=`${chip(phase)}${snapshot.second!=null?` <span class="op-clock">模拟时间 ${clock(snapshot.second)}</span>`:''}`;
    if($('[data-status]').innerHTML!==status)$('[data-status]').innerHTML=status;
    $('[data-run-error]').textContent=snapshot.error||'';
    if (!full)return;
    const activePhase=activeSnapshot.phase;
    if(!['running','paused'].includes(activePhase)&&$('[data-account-dialog]').open) {
      $('[data-account-dialog]').close();
      $('[data-credentials]').textContent='';
    }
    const list=activeSnapshot.history||[];
    const picker=$('[data-history]');
    const entries=option('','当前运行')+list.map(run=>option(run.runId,`${run.config.start.slice(0,16).replace('T',' ')} · ${stateOf(run.config.scenario)} · ${stateOf(run.config.policy)} · ${run.runId}`)).join('');
    if(picker.innerHTML!==entries&&document.activeElement!==picker)picker.innerHTML=entries;
    if(picker.value!==(historyRun||''))picker.value=historyRun||'';
    $('[data-export]').disabled=!historyRun||snapshot.runId!==historyRun;
    const active=['preparing','running','paused','stopping'].includes(activePhase);
    root.querySelectorAll('[data-command]').forEach(item=>{
      const action=item.dataset.command;
      item.disabled=(action==='start'||action==='reset')?active:action==='pause'?activePhase!=='running':
                    action==='resume'?activePhase!=='paused':action==='stop'?!active||activePhase==='stopping':false;
    });
  }

  function dispatchMarkup() {
    const alerts=(snapshot.alerts||[]).slice(-30).reverse();
    const changes=(snapshot.taskHistory||[]).slice(-60).reverse();
    return panel('预警记录',table(['等级','触发时间','原因'],alerts.map(alert=>[
      `<span class="op-severity ${esc(alert.level)}">${esc(stateOf(alert.level))}</span>`,
      esc(alert.createdAt?.replace('T',' ')),esc(alert.message)]),'class="op-timeline"','当前运行暂无预警。'),'op-dispatch')+
      panel('调度任务生命周期',table(['任务','阶段','车辆','时间','起因与派车理由'],changes.map(step=>[
        esc(step.taskId),chip(step.action),esc(step.vehicleId),esc(step.time.replace('T',' ')),esc(step.reason)
      ]),'class="op-timeline"','当前运行暂无调度事件。'),'op-dispatch');
  }

  function inspectMarkup(car,route) {
    if(!car)return '';
    const commands={fault:'车辆故障',withdraw:'退出运营',restore:'重新投入',...(car.manual?{credentials:'查看测试司机账号'}:{})};
    const controls=full&&tab==='vehicles'&&['running','paused'].includes(activeSnapshot.phase)&&!historyRun
      ? `<div class="op-actions">${Object.entries(commands).map(([key,label])=>button(label,`data-fleet-command="${key}"`,
        key==='fault'||key==='withdraw'?'btn-danger':key==='restore'?'btn-success':'')).join('')}</div>`:'';
    return panel(`车辆 ${esc(car.plate)}`, `${chip(car.phase)}<dl>
      <dt>核载与当前载客</dt><dd>${car.capacity} 座 · ${car.load} 人 · 满载率 ${Math.round(100*car.load/car.capacity)}%</dd>
      <dt>当前 / 下一站</dt><dd>${esc(route.stops[car.index]?.stationName)} / ${esc(route.stops[car.index+1]?.stationName)}</dd>
      <dt>区间进度 / 剩余距离</dt><dd>${Math.round(car.progress*100)}% · ${car.remainingDistance==null?'—':Math.round(car.remainingDistance)+' m'}</dd>
      <dt>下一站剩余时间 / 预计终点</dt><dd>${formatSeconds(car.nextEta)} · ${clock(car.terminalEta)}</dd></dl>${controls}`, 'op-inspector');
  }

  function tripMarkup(trip,car,route,routeTrips) {
    if(!trip)return panel('逐站运行记录',empty('所选线路暂无行程。可以切换线路，或从历史运行中选择已有记录。'));
    return panel('逐站运行记录',table(['站点','计划到站','实际到站','实际离站','下客','上客','离站载客','离站剩余候车','累计距离'],
      trip.stops.map(stop=>[esc(stop.name),clock(stop.planned),clock(stop.arrived),clock(stop.departed),stop.alighted,stop.boarded,
        stop.departed===null?'—':esc(stop.load),esc(stop.waiting),stop.distance+' m'])))+
      plot(trip,car)+panel('区间载客热力图',`<p class="op-note">离站实报 · 绿色 &lt;70% · 黄色 70–89% · 红色 ≥90%</p>
      ${table(['行程与车辆',...route.stops.slice(0,-1).map((_,i)=>`${i+1}→${i+2}`)],
      routeTrips.slice(-20).map(item=>[esc(item.id+' · '+item.plate),...item.stops.slice(0,-1).map(stop=>
        stop.departed===null||stop.load===null?'—':`<span class="op-load ${loadLevel(stop.load/item.capacity)}">${stop.load}/${item.capacity}</span>`)]))}`);
  }

  function render() {
    if(!activeSnapshot)return;
    snapshot=full&&tab==='vehicles'&&historyRun&&archivedSnapshot?.runId===historyRun?archivedSnapshot:activeSnapshot;
    updateHeader();
    const board=$(`[data-board="${tab}"]`);
    if(!board)return;
    if(tab==='dispatch') { updateBoard(board, dispatchMarkup()); return; }
    if(tab==='comparison') { updateBoard(board, comparisonMarkup(activeSnapshot.history||[])); return; }
    const cars=snapshot.vehicles||[], routes=snapshot.routes||{}, trips=Object.values(snapshot.trips||{});
    const isVehicles=full&&tab==='vehicles';
    const metrics=metricsMarkup(snapshot.metrics);
    if (!Object.keys(routes).length) {
      const message=snapshot.phase==='preparing'?'正在初始化车队与线路数据…':'还没有运营记录。配置场景并开始运行后，线路和车辆会显示在这里。';
      updateBoard(board, metrics+panel(isVehicles?'车辆运行':'线路运营',empty(message)));
      return;
    }
    if(role==='driver'&&cars[0]) routeKey=`${cars[0].lineId}:${cars[0].direction}`;
    if(!routes[routeKey]) routeKey=Object.keys(routes)[0];
    const route=routes[routeKey];
    const shown=cars.filter(c=>c.lineId===route.lineId&&c.direction===route.direction);
    const car=shown.find(c=>c.id===carId)||shown[0];
    const routeTrips=trips.filter(t=>t.lineId===route.lineId&&t.direction===route.direction);
    const trip=routeTrips.find(t=>t.id===tripId)||routeTrips.find(t=>t.id===car?.tripId)||routeTrips.at(-1);
    const carRows=shown.map(c=>[esc(c.plate),chip(c.phase),esc(direction(c.direction)),
      `<span class="op-load ${loadLevel(c.load/c.capacity)}">${esc(c.load+'/'+c.capacity)} · ${c.load/c.capacity>=.9?'拥挤':c.load/c.capacity>=.7?'较满':'宽松'}</span>`,
      esc(formatSeconds(c.nextEta))]);
    const carList=table(['车辆','状态','方向','载客','下一站预计'],carRows,'data-vehicles','当前方向暂无车辆。');
    const inspector=role!=='passenger'?inspectMarkup(car,route):'';
    const prediction=role==='driver'&&car?panel('本站及后续站点 · 预测',
      table(['站点','预计下客','预计上客','预计剩余座位'],(car.predictions||[]).map(p=>[esc(p.name),p.alight,p.board,p.seats]))):'';
    const filters=`<div class="op-fields">
      ${select('线路方向','op-route',Object.entries(routes).map(([k,v])=>option(k,v.name+' · '+direction(v.direction))).join(''))}
      ${shown.length&&role!=='passenger'?select('车辆','op-car',shown.map(c=>option(c.id,c.plate)).join('')):''}
      ${isVehicles&&routeTrips.length?select('行程','op-trip',routeTrips.slice().reverse().map(t=>option(t.id,t.plate+' · '+t.id+' · '+stateOf(t.status))).join('')):''}
      ${isVehicles&&trip&&historyRun?button('回放行程','data-replay','btn-primary')+'<label>回放进度<input data-replay-seek type="range"></label>':''}
      ${isVehicles&&trip&&historyRun?'<span class="op-clock" data-replay-time></span>':''}</div>`;
    const map=panel('线路拓扑与站间进度',filters+topology(route,shown,isVehicles?replay?.second:null,trip)+carList,'op-map');
    updateBoard(board, (isVehicles?'':metrics)+`<div class="op-view ${inspector?'':'op-view-single'}">${map}${inspector}</div>`+
      prediction+(isVehicles?tripMarkup(trip,car,route,routeTrips):''));
    const find = selector => board.querySelector(selector);
    const redraw = () => { board.userUpdate=true; try {render();} finally {board.userUpdate=false;} };
    const routeInput=find('[name=op-route]');
    if(!routeInput)return;
    routeInput.value=routeKey;
    routeInput.onchange=e=>{routeKey=e.target.value;carId=tripId=null;replay=null;redraw();};
    if(find('[name=op-car]')) {
      find('[name=op-car]').value=car.id;
      find('[name=op-car]').onchange=e=>{carId=Number(e.target.value);tripId=null;replay=null;redraw();};
    }
    if(find('[name=op-trip]')) {
      find('[name=op-trip]').value=trip?.id||'';
      find('[name=op-trip]').onchange=e=>{tripId=e.target.value;replay=null;redraw();};
    }
    if(find('[data-replay]')) bindReplay(trip, redraw);
    board.querySelectorAll('[data-fleet-command]').forEach(item=>item.onclick=async()=>{
      try {
        const action=item.dataset.fleetCommand;
        const data=await api('/api/admin/operation/control',{action,vehicleId:car.id});
        if(action==='credentials') {
          $('[data-credentials]').textContent=`手机号 ${data.phone} · 密码 ${data.password}`;
          $('[data-account-dialog]').showModal();
        } else await refresh();
      } catch(error) { feedback(error.message,'error'); }
    });
  }

  function updateBoard(board,markup) {
    if(board.renderedMarkup===markup)return;
    const before=Array.from(board.querySelectorAll('.op-scroll'), node=>node.scrollLeft);
    const focused=document.activeElement;
    const name=board.contains(focused)?focused.getAttribute('name'):null;
    if(board.contains(focused)&&(focused.matches('input[type=range], select')||focused.closest('.op-scroll'))
          && !board.userUpdate) return;
    board.renderedMarkup=markup;
    board.innerHTML=markup;
    board.querySelectorAll('.op-scroll').forEach((node,i)=>{node.scrollLeft=before[i]||0;});
    if(name)board.querySelector(`[name="${name}"]`)?.focus({preventScroll:true});
  }

  function comparisonMarkup(history) {
    if(!history.length)return panel('同配置策略对比 · 真实业务结果',empty('完成一次运行后，这里会显示对应策略的结果。'));
    const key=entry=>JSON.stringify({...entry.config,policy:null});
    const group=history.filter(entry=>key(entry)===key(history[0]));
    const unique=[...new Map(group.reverse().map(entry=>[entry.config.policy,entry])).values()];
    return panel('同配置策略对比 · 真实业务结果', `<p class="op-note">保持车队、客流与种子一致，分别运行三个策略。此处显示最近配置的完成记录。</p>
      ${table(['策略','运行','签到','上车','剩余候车','取消','平均候车（秒）'],unique.map(entry=>
        [esc(stateOf(entry.config.policy)),esc(entry.runId),...['checkins','boarded','waiting','cancelled','averageWait'].map(k=>esc(entry.metrics[k]))]))}`, 'op-dispatch');
  }

  function bindReplay(trip, redraw) {
    const times=trip.stops.flatMap(s=>[s.arrived,s.departed]).filter(s=>s!==null);
    const slider=$('[data-replay-seek]');
    if(!times.length){slider.disabled=true;$('[data-replay]').disabled=true;return;}
    slider.min=Math.min(...times); slider.max=Math.max(...times); slider.value=replay?.second??slider.min;
    $('[data-replay-time]').textContent='回放时间 '+clock(Number(slider.value));
    slider.oninput=()=>{replay={second:Number(slider.value),playing:false};
      const target=$('[data-board=vehicles] [data-map-scroll]');
      const route=snapshot.routes[routeKey];
      const scroll=target.scrollLeft;
      target.outerHTML=topology(route,[],replay.second,trip);
      $('[data-board=vehicles] [data-map-scroll]').scrollLeft=scroll;
      $('[data-replay-time]').textContent='回放时间 '+clock(replay.second);
      $('[data-replay]').textContent='回放行程';
    };
    $('[data-replay]').textContent=replay?.playing?'暂停回放':'回放行程';
    $('[data-replay]').onclick=()=>{
      const start=replay?.second??Number(slider.min);
      replay={second:start>=Number(slider.max)?Number(slider.min):start,playing:!replay?.playing};
      redraw();
    };
  }

  async function refresh() {
    if(requesting) { refreshAgain=true; return; }
    requesting=true;
    try {
      activeSnapshot=await api('/api/operation/view');
      if(!activeSnapshot.enabled) { root.hidden=true; return; }
      if(!mounted) {
        setup(); mounted=true; root.hidden=false;
        document.querySelector("#app > .header")?.after(root);
        if(full&&activeSnapshot.savedConfig) applyConfig(activeSnapshot.savedConfig);
        if(full)setTab(tab);
      }
      if(full&&tab==='vehicles'&&historyRun&&archivedSnapshot?.runId!==historyRun)
        archivedSnapshot=await api('/api/operation/view?run='+encodeURIComponent(historyRun));
      if(!replay?.playing)render();
    } catch(error) {
      if(mounted) $('[data-run-error]').textContent=error.message;
      else if(loading) root.hidden=true;
    } finally {
      requesting=false;
      if(refreshAgain){refreshAgain=false;setTimeout(refresh,100);}
    }
  }

  async function open() {
    try {
      const session=await window.ShuttleAuth.readSession(false);
      if(!session.authenticated)return;
      if(full) {
        loading=true;
        root.innerHTML=panel('运营仿真控制台','<p class="op-empty" role="status">正在加载运营数据…</p>');
        root.hidden=false;
      }
      await refresh();
      loading=false;
      if(root.hidden)return;
      const connect=()=>{
        const url=new URL('/ws',location.href);url.protocol=location.protocol==='https:'?'wss:':'ws:';
        socket=new WebSocket(url);
        socket.onmessage=event=>{
          const type=JSON.parse(event.data).type;
          if(type==='simulation_change'&&!(tab==='vehicles'&&historyRun))refresh();
        };
        socket.onclose=()=>{timer=setTimeout(connect,2500);};
      };
      connect();
      const poll=setInterval(()=>{if(!(tab==='vehicles'&&historyRun))refresh();},3500);
      const playback=setInterval(()=>{
        if(!replay?.playing)return;
        const slider=$('[data-replay-seek]');
        if(!slider)return;
        replay.second=Math.min(Number(slider.max),replay.second+20);
        if(replay.second>=Number(slider.max))replay.playing=false;
        render();
      },200);
      window.addEventListener('pagehide',()=>{
        clearInterval(poll);clearInterval(playback);clearTimeout(timer);
        if(socket){socket.onclose=null;socket.close();}
      },{once:true});
    } catch (_) { /* Production and unsigned pages leave this local-only panel hidden. */ }
  }
  if(role==='passenger') setTimeout(open,500); else open();
})();
