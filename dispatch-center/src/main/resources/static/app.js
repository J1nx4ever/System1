window.dispatchIdentity.then(async identity => {
  if (identity.role !== 'DISPATCHER') {
    location.replace(identity.role === 'VEHICLE' ? '/vehicle.html' : '/shovel.html');
    return;
  }

  const $ = id => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const model = {
    identity, simulations: [], runs: [], run: null, simulationId: null,
    snapshot: null, health: null, decisions: [], events: [], productionEvents: [],
    production: null, alerts: [], commands: [], errors: new Map(),
    selected: null, selectedDecision: null, selectedCycle: null, cycleStory: null,
    view: params.get('view') || 'overview', monitorFilter: params.get('filter') || 'all',
    decisionFilter: 'all', socket: null, reconnectDelay: 1000
  };
  const eventOrder = ['ARRIVE_SHOVEL','QUEUE_LOAD','LOADING_START','LOADING_FINISH','ARRIVE_DUMP','QUEUE_DUMP','DUMP_START','DUMP_FINISH'];
  const eventLabels = {
    ARRIVE_SHOVEL:'到达铲装点', QUEUE_LOAD:'进入装载队列', LOADING_START:'开始装载', LOADING_FINISH:'完成装载',
    ARRIVE_DUMP:'到达卸载点', QUEUE_DUMP:'进入卸载队列', DUMP_START:'开始卸载', DUMP_FINISH:'完成卸载'
  };
  let zoom=1, offsetX=0, offsetY=0, geometryKey='';

  $('user-chip').textContent = identity.username + ' · ' + identity.role;
  bindUi();
  try { await loadSimulations(); }
  catch (error) { showPageError('初始化失败', error.message); }

  function bindUi() {
    $('main-nav').querySelectorAll('button').forEach(button => button.onclick=()=>setView(button.dataset.view));
    document.querySelectorAll('[data-jump]').forEach(button => button.onclick=()=>{
      if (button.dataset.filter) {
        if (button.dataset.jump === 'monitor') model.monitorFilter=button.dataset.filter;
        if (button.dataset.jump === 'decision') model.decisionFilter=button.dataset.filter;
      }
      setView(button.dataset.jump);
    });
    $('simulation-select').onchange=()=>selectSimulation($('simulation-select').value);
    $('run-select').onchange=()=>selectRun($('run-select').value);
    $('monitor-filters').querySelectorAll('button').forEach(button=>button.onclick=()=>{model.monitorFilter=button.dataset.filter;renderMonitor();syncFilters();});
    $('decision-filters').querySelectorAll('button').forEach(button=>button.onclick=()=>{model.decisionFilter=button.dataset.filter;renderDecisions();syncFilters();});
    $('close-detail').onclick=()=>{model.selected=null;updateUrl({vehicleId:null});renderMonitor();};
    $('detail-decision').onclick=openSelectedDecisions;
    $('detail-cycle').onclick=openSelectedCycle;
    $('dispatch-submit').onclick=submitManualDispatch;
    $('zoom-in').onclick=()=>setZoom(zoom*1.18);
    $('zoom-out').onclick=()=>setZoom(zoom/1.18);
    $('fit-map').onclick=fitMap;
    $('replay-load').onclick=loadReplay;
    window.addEventListener('resize',()=>{if(model.view==='monitor')fitMap();});
  }

  async function loadSimulations() {
    showLoading('正在读取仿真实例');
    const list=await api('/api/v1/simulations');
    model.simulations=Array.isArray(list)?list:[];
    const requested=params.get('simulationId') || identity.simulationId;
    if (!model.simulations.length) {
      if (!requested) throw new Error('没有可访问的 Simulation，也没有 URL 上下文');
      model.simulations=[{simulationId:requested,online:false,lastReceivedAtMs:0}];
    }
    const chosen=model.simulations.find(x=>x.simulationId===requested)?.simulationId || model.simulations[0].simulationId;
    $('simulation-select').replaceChildren(...model.simulations.map(item=>option(item.simulationId,item.simulationId)));
    $('simulation-select').value=chosen;
    await selectSimulation(chosen);
  }

  async function selectSimulation(simulationId) {
    model.simulationId=simulationId;
    closeSocket();
    showLoading('正在读取 Run 列表');
    model.runs=await api('/api/v1/runs?simulationId='+encodeURIComponent(simulationId));
    $('run-select').replaceChildren(...model.runs.map(run=>option(run.run_id,runLabel(run))));
    if (!model.runs.length) {
      model.run=null; clearRunData(); renderAll(); showPageError('当前没有 Run','只有建立明确运行边界后才能展示运行级证据。');
      return;
    }
    const requested=params.get('runId');
    const active=model.runs.find(run=>run.ended_at_ms==null);
    const chosen=model.runs.find(run=>run.run_id===requested) || active || model.runs[0];
    $('run-select').value=chosen.run_id;
    await selectRun(chosen.run_id);
    connectWebSocket();
  }

  async function selectRun(runId) {
    const run=model.runs.find(item=>item.run_id===runId);
    if (!run) return;
    const restoringInitialRun=model.run==null;
    model.run=run;
    clearRunData();
    updateUrl({runId,simulationId:model.simulationId,decisionId:null,cycleId:null,vehicleId:null,simTime:null,queryWallTime:null});
    setReplayDefault();
    showLoading('正在读取 Run 证据');
    const active=isActive();
    const start=Number(run.started_at_ms), end=run.ended_at_ms==null?Infinity:Number(run.ended_at_ms);
    const tasks=[
      collect('health','/api/v1/health'),
      collect('events','/api/v1/runs/'+enc(runId)+'/events?limit=500'),
      collect('production','/api/v1/runs/'+enc(runId)+'/production/summary'),
      collect('productionEvents','/api/v1/runs/'+enc(runId)+'/production/events?limit=1000'),
      collect('decisions','/api/v1/simulations/'+enc(model.simulationId)+'/decisions?limit=500'),
      collect('alerts','/api/v1/simulations/'+enc(model.simulationId)+'/alerts?limit=100'),
      collect('commands','/api/v1/simulations/'+enc(model.simulationId)+'/manual-dispatch?limit=200')
    ];
    if(active) tasks.push(collect('snapshot','/api/v1/simulations/'+enc(model.simulationId)+'/snapshot'));
    await Promise.all(tasks);
    model.decisions=(model.decisions||[]).filter(item=>item.createdAtMs>=start&&item.createdAtMs<end);
    model.commands=(model.commands||[]).filter(item=>item.requestedAtMs>=start&&item.requestedAtMs<end);
    model.alerts=(model.alerts||[]).filter(item=>item.createdAtMs>=start&&item.createdAtMs<end);
    model.selected=restoringInitialRun?restoreSelected():null;
    model.selectedDecision=restoringInitialRun?params.get('decisionId'):null;
    model.selectedCycle=restoringInitialRun?params.get('cycleId'):null;
    renderAll();
    hidePageState();
    if(model.selectedCycle) loadCycleStory(model.selectedCycle);
  }

  function clearRunData() {
    model.snapshot=null;model.health=null;model.decisions=[];model.events=[];model.productionEvents=[];
    model.production=null;model.alerts=[];model.commands=[];model.errors.clear();model.selected=null;
    model.selectedDecision=null;model.selectedCycle=null;model.cycleStory=null;geometryKey='';
  }

  async function collect(key,url) {
    try { model[key]=await api(url); }
    catch(error) { model.errors.set(key,error.message); model[key]=key==='production'||key==='snapshot'||key==='health'?null:[]; }
  }

  function setView(view) {
    if(!['overview','monitor','decision','cycle','replay'].includes(view))view='overview';
    model.view=view;
    document.querySelectorAll('.view').forEach(node=>node.classList.toggle('active',node.dataset.view===view));
    $('main-nav').querySelectorAll('button').forEach(node=>node.classList.toggle('active',node.dataset.view===view));
    updateUrl({view});
    syncFilters();
    if(view==='monitor'){renderMonitor();requestAnimationFrame(fitMap);}
    if(view==='decision')renderDecisions();
    if(view==='cycle')renderCycles();
  }

  function renderAll() {
    renderTopbar();renderOverview();renderMonitor();renderDecisions();renderCycles();setView(model.view);
    $('footer-status').textContent=model.errors.size?'部分接口失败：'+[...model.errors.keys()].join(' / '):'证据读取完成 · '+formatWall(Date.now());
  }

  function renderTopbar() {
    const active=isActive(), snapshot=active?model.snapshot:null;
    const simTimes=[
      ...(snapshot?.vehicles||[]).map(x=>x.simTimeSeconds),
      ...(snapshot?.shovels||[]).map(x=>x.simTimeSeconds),
      ...(model.productionEvents||[]).map(x=>x.simTimeSeconds)
    ].filter(finite);
    $('sim-time').textContent=simTimes.length?Math.max(...simTimes).toFixed(1)+' s':'未采集';
    setHealth($('mqtt-health'),model.health?.mqttConnected===true,'MQTT '+(model.health?.mqttConnected===true?'已连接':model.health?'未连接':'未知'));
    if(!active)setHealth($('field-health'),null,'历史 Run');
    else setHealth($('field-health'),snapshot?.online===true,snapshot?.online===true?'现场在线':snapshot?'现场失联':'现场未知');
  }

  function renderOverview() {
    const run=model.run;if(!run)return;
    const active=isActive(), snapshot=active?model.snapshot:null, vehicles=snapshot?.vehicles||[], shovels=snapshot?.shovels||[];
    $('run-banner').innerHTML='<div class="run-id"><span>RUN ID</span><strong>'+esc(run.run_id)+'</strong></div>'+
      bannerFact('运行状态','<b class="'+(active?'live':'history')+'">'+(active?'进行中':'历史只读')+'</b>')+
      bannerFact('场景 · 声明值',run.scenario_id||'未声明')+
      bannerFact('开始',formatWall(run.started_at_ms))+
      bannerFact('结束',active?'进行中':formatWall(run.ended_at_ms))+
      bannerFact('Simulation',run.simulation_id);
    const activeAlerts=(model.alerts||[]).filter(x=>x.status!=='CLOSED');
    const failed=(model.decisions||[]).filter(x=>['REJECTED','PUBLISH_FAILED','EXPIRED'].includes(x.ackStatus)).length;
    setText('kpi-online',snapshot?vehicles.filter(x=>!x.stale).length+'/'+vehicles.length:'--');
    setText('kpi-stale',snapshot?vehicles.filter(x=>x.stale).length:'--');
    setText('kpi-cycles',value(model.production?.completeCycles));
    setText('kpi-failed',model.errors.has('decisions')?'--':failed);
    setText('kpi-alerts',model.errors.has('alerts')?'--':activeAlerts.length);
    const quality=model.errors.size?'partial':(!model.production&& !snapshot?'insufficient':'complete');
    setQuality($('overview-quality'),quality,model.errors.size?'PARTIAL':quality.toUpperCase());
    renderOverviewEntities(vehicles,shovels,active);
    renderRecentEvents();
    renderProduction();
    renderAlerts(activeAlerts);
  }

  function renderOverviewEntities(vehicles,shovels,active) {
    const root=$('overview-entities');
    if(!active){root.className='overview-entities empty';root.textContent='历史 Run 不使用当前 Snapshot；请进入历史回放查看已持久化状态。';$('snapshot-time').textContent='历史语境';return;}
    const items=[...vehicles.map(x=>({...x,_type:'vehicle'})),...shovels.map(x=>({...x,_type:'shovel'}))];
    if(!items.length){root.className='overview-entities empty';root.textContent=model.errors.get('snapshot')||'等待真实状态证据';return;}
    root.className='overview-entities';root.replaceChildren();
    const latest=Math.max(...items.map(x=>x.receivedAtMs||0));$('snapshot-time').textContent='最近接收 '+formatWall(latest);
    items.forEach(item=>{
      const node=document.createElement('div');node.className='entity-tile'+(item.stale?' stale':'');
      const vehicle=item._type==='vehicle';
      node.innerHTML='<div class="title"><span>'+(vehicle?'T'+item.vehicleId:esc(item.shovelName||'铲装点 '+item.shovelId))+'</span><span class="state">'+
        (item.stale?'失联':vehicle?esc(item.statechartState||'UNKNOWN'):(item.busy?'作业中':item.available?'可用':'不可用'))+'</span></div>'+
        '<div class="facts"><span>'+(vehicle?(item.stale?'速度 --':num(item.speedMps,1)+' m/s'):'等待 '+(item.stale?'--':item.waitingTrucks))+'</span><span>'+
        (vehicle?(item.loaded?'载重 '+num(item.payloadTons,0)+' t':'空载'):'服务 '+(item.stale?'--':item.trucksInService))+'</span></div>';
      node.onclick=()=>{selectObject(vehicle?'vehicle':'shovel',vehicle?item.vehicleId:item.shovelId);setView('monitor');};root.appendChild(node);
    });
  }

  function renderRecentEvents() {
    const root=$('recent-events'),items=[...(model.events||[])].sort((a,b)=>(b.sim_time_seconds??-1)-(a.sim_time_seconds??-1)||b.wall_time_ms-a.wall_time_ms).slice(0,12);
    if(!items.length){root.className='evidence-feed empty';root.textContent=model.errors.get('events')||'当前 Run 尚无已记录事件';return;}
    root.className='evidence-feed';root.innerHTML=items.map(item=>'<div class="feed-row"><div><b>'+esc(eventName(item.event_type))+'</b><span>'+esc(item.object_type||'')+' '+esc(item.object_id??'')+(item.command_id?' · '+esc(shortId(item.command_id)):'')+'</span></div><time>'+(item.sim_time_seconds==null?formatWall(item.wall_time_ms):num(item.sim_time_seconds,1)+' s')+'</time></div>').join('');
  }

  function renderProduction() {
    const root=$('production-metrics'),p=model.production;
    if(!p){root.className='production-metrics empty';root.textContent=model.errors.get('production')||'当前 Run 尚无生产汇总';return;}
    root.className='production-metrics';
    const metrics=[
      ['观察周期',p.observedCycles,'现行作业阶段'],['完整 / 不完整',value(p.completeCycles)+' / '+value(p.incompleteCycles),'八事件口径'],
      ['可计产周期',p.eligibleCompleteCycles,'源端明确标记'],['倒序周期',p.invalidOrderCycles,'时间证据异常'],
      ['矿石',unit(p.oreTons,'t',1),'卸载完成原始载荷'],['废石',unit(p.wasteTons,'t',1),'卸载完成原始载荷'],
      ['加权品位',p.weightedOreGrade==null?'未采集':num(p.weightedOreGrade*100,2)+'%','按矿石吨数加权'],
      ['到铲至卸完',unit(p.averageArrivalToDumpFinishSeconds,'s',1),'不等于完整运输循环']
    ];
    root.innerHTML=metrics.map(x=>'<div title="'+esc(x[2])+'"><span>'+esc(x[0])+'</span><strong>'+esc(String(x[1]))+'</strong></div>').join('');
  }

  function renderAlerts(items) {
    const root=$('overview-alerts');
    if(!items.length){root.className='alert-list empty';root.textContent=model.errors.get('alerts')||'当前 Run 没有活动告警';return;}
    root.className='alert-list';root.innerHTML=items.slice(0,15).map(x=>'<div class="alert-row"><div><b>'+esc(x.severity)+' · '+esc(x.status)+'</b><small>'+esc(x.objectType)+' '+x.objectId+' · '+esc(x.message)+'</small></div>'+alertButton(x)+'</div>').join('');
    root.querySelectorAll('button').forEach(button=>button.onclick=()=>transitionAlert(button.dataset.id,button.dataset.action));
  }

  function alertButton(x){const next=x.status==='OPEN'?['acknowledge','确认']:x.status==='ACKNOWLEDGED'?['process','处理']:x.status==='PROCESSING'?['close','关闭']:null;return next?'<button data-id="'+esc(x.alertId)+'" data-action="'+next[0]+'">'+next[1]+'</button>':'';}
  async function transitionAlert(id,action){let detail='';if(action==='close'){detail=prompt('请输入处理结果')||'';if(!detail)return;}await api('/api/v1/simulations/'+enc(model.simulationId)+'/alerts/'+enc(id)+'/'+action,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({detail})});await collect('alerts','/api/v1/simulations/'+enc(model.simulationId)+'/alerts?limit=100');renderOverview();}

  function renderMonitor() {
    syncFilters();
    const root=$('object-list'),active=isActive(),snapshot=active?model.snapshot:null;
    if(!active){root.className='object-list empty';root.textContent='历史 Run 不展示当前现场状态，请进入历史回放。';setText('object-count','历史只读');clearMap();renderObjectDetail();return;}
    if(!snapshot){root.className='object-list empty';root.textContent=model.errors.get('snapshot')||'等待现场状态';setText('object-count','--');clearMap();renderObjectDetail();return;}
    const vehicles=(snapshot.vehicles||[]).filter(matchMonitor),shovels=(snapshot.shovels||[]).filter(matchMonitor);
    setText('object-count',(vehicles.length+shovels.length)+' 个对象');
    root.className='object-list';root.replaceChildren();
    appendObjectGroup(root,'车辆',vehicles,'vehicle');appendObjectGroup(root,'铲装点',shovels,'shovel');
    renderGeometry(snapshot.map);renderMarkers(vehicles,shovels);renderObjectDetail();
  }

  function matchMonitor(item) {
    const filter=model.monitorFilter;if(filter==='all')return true;if(filter==='stale')return item.stale===true;if(filter==='online')return !item.stale;
    const state=String(item.statechartState||'').toLowerCase();
    if(filter==='running')return !item.stale&&/(move|travel|route|haul)/.test(state);
    if(filter==='waiting')return !item.stale&&(/(wait|queue|idle)/.test(state)||item.waitingTrucks>0);
    return true;
  }

  function appendObjectGroup(root,label,items,type) {
    if(!items.length)return;const title=document.createElement('div');title.className='object-group';title.textContent=label+' · '+items.length;root.appendChild(title);
    items.forEach(item=>{const id=type==='vehicle'?item.vehicleId:item.shovelId,row=document.createElement('button');row.className='object-row'+(item.stale?' stale':'')+(model.selected?.type===type&&model.selected.id===id?' selected':'');
      const vehicle=type==='vehicle';row.innerHTML='<span class="object-symbol '+(vehicle?(item.loaded?'loaded':''):'shovel')+'"><i>'+(vehicle?'T':id)+'</i></span><span><b>'+(vehicle?'车辆 T'+id:esc(item.shovelName||'铲装点 '+id))+'</b><small>'+(item.stale?'状态失联':vehicle?esc(item.statechartState||'UNKNOWN'):(item.busy?'作业中':item.available?'可用':'不可用'))+'</small></span><span>'+(vehicle?(item.stale?'--':num(item.speedMps,1)+' m/s'):'等待 '+(item.stale?'--':item.waitingTrucks))+'</span>';
      row.onclick=()=>selectObject(type,id);root.appendChild(row);});
  }

  function selectObject(type,id) {model.selected={type,id};updateUrl(type==='vehicle'?{vehicleId:id}:{vehicleId:null});renderMonitor();}
  function restoreSelected(){const id=Number(params.get('vehicleId'));return Number.isInteger(id)?{type:'vehicle',id}:null;}

  function renderObjectDetail() {
    const body=$('detail-body'),empty=$('detail-empty'),item=getSelected();
    body.hidden=!item;empty.hidden=Boolean(item);if(!item){$('detail-title').textContent='对象详情';return;}
    const vehicle=model.selected.type==='vehicle';$('detail-title').textContent=vehicle?'车辆 T'+item.vehicleId:(item.shovelName||'铲装点 '+item.shovelId);
    const status=$('detail-status');status.className='detail-status'+(item.stale?' stale':'');
    status.textContent=item.stale?'状态失联 · 最后接收 '+formatWall(item.receivedAtMs)+' · 数据年龄 '+age(item.receivedAtMs):vehicle?(item.statechartState||'UNKNOWN'):(item.busy?'作业中':item.available?'可用':'不可用');
    const fields=vehicle?[
      ['业务时间',unit(item.simTimeSeconds,'s',1)],['坐标',num(item.x,1)+', '+num(item.y,1)],['速度',item.stale?'--':unit(item.speedMps,'m/s',2)],
      ['载荷',(item.stale?'最后值 · ':'')+unit(item.payloadTons,'t',1)+' · '+(item.loaded?'载重':'空载')],['当前节点',item.currentNode||'未采集'],
      ['目的地',(item.stale?'最后目标 · ':'')+(item.destinationNode||'未采集')],['路段',item.currentSegmentId||'未采集'],['接收时间',formatWall(item.receivedAtMs)]
    ]:[
      ['业务时间',unit(item.simTimeSeconds,'s',1)],['坐标',num(item.x,1)+', '+num(item.y,1)],['等待车辆',item.stale?'--':value(item.waitingTrucks)],
      ['服务车辆',item.stale?'--':value(item.trucksInService)],['剩余量',item.stale?'最后值 · '+unit(item.remainingTons,'t',0):unit(item.remainingTons,'t',0)],
      ['生产类型',item.producesOre?'矿石':'废石'],['接收时间',formatWall(item.receivedAtMs)]
    ];
    $('detail-fields').innerHTML=fields.map(x=>'<dt>'+esc(x[0])+'</dt><dd>'+esc(String(x[1]))+'</dd>').join('');
    $('detail-cycle').disabled=!vehicle||!findCyclesForVehicle(item.vehicleId).length;
    renderManualDispatch(vehicle?item:null);
  }

  function renderManualDispatch(vehicle) {
    const panel=document.querySelector('.manual-dispatch');panel.hidden=!vehicle;if(!vehicle)return;
    const targets=vehicle.loaded?[['Crusher','Crusher 矿石破碎站'],['dumpNode','dumpNode 排土场']]:[['ShovelOreHigh1','ShovelOreHigh1 (HG1)'],['ShovelOreHigh2','ShovelOreHigh2 (HG2)'],['ShovelOreLow','ShovelOreLow (LG)'],['ShovelWaste','ShovelWaste (WASTE)']];
    const mapNodes=new Set((model.snapshot?.map?.nodes||[]).map(x=>x.nodeId));
    const available=targets.filter(x=>x[0]!==vehicle.destinationNode&&(!mapNodes.size||mapNodes.has(x[0])));
    const previous=$('dispatch-target').value;$('dispatch-target').replaceChildren(...available.map(x=>option(x[0],x[1])));if(available.some(x=>x[0]===previous))$('dispatch-target').value=previous;
    const commands=model.commands.filter(x=>x.vehicleId===vehicle.vehicleId).sort((a,b)=>b.requestedAtMs-a.requestedAtMs);
    const active=commands.find(x=>['CREATED','SENT'].includes(x.status));
    const travelling=/^(PrepareRoute|MoveSegment|ArriveSegmentEnd|CheckDestination|TravelToLoadSite|TravelToDumpSite)/.test(vehicle.statechartState||'');
    $('dispatch-submit').disabled=vehicle.stale||!travelling||Boolean(active)||!available.length;
    $('dispatch-status').textContent=vehicle.stale?'车辆失联，禁止下发':!travelling?'车辆非行驶态，不能改派':active?'等待现场结果 · '+shortId(active.commandId):!available.length?'没有可选的兼容目标':'可下发 · 最终以服务端校验为准';
    $('dispatch-history').innerHTML=commands.slice(0,6).map(x=>'<div><span>'+formatWall(x.requestedAtMs)+' · '+esc(x.previousDestinationNode||'未知')+' → '+esc(x.targetNode)+'</span><b class="status-'+String(x.status).toLowerCase()+'">'+esc(x.status)+'</b></div>').join('')||'<div><span>暂无人工改派记录</span></div>';
  }

  async function submitManualDispatch() {
    const vehicle=getSelected();if(!vehicle||model.selected.type!=='vehicle')return;
    const target=$('dispatch-target').value;if(!target)return;
    const message='确认人工改派？\n\n车辆：T'+vehicle.vehicleId+'\n原目标：'+(vehicle.destinationNode||'未知')+'\n新目标：'+target+'\n当前状态：'+(vehicle.statechartState||'UNKNOWN')+'\n\n现场将在当前物理路段终点应用改派，不会瞬移。';
    if(!confirm(message))return;
    $('dispatch-submit').disabled=true;$('dispatch-status').textContent='正在创建并发布 Command…';
    try {
      const command=await api('/api/v1/simulations/'+enc(model.simulationId)+'/manual-dispatch',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({vehicleId:vehicle.vehicleId,targetNode:target})},true);
      model.commands.unshift(command);$('dispatch-status').textContent=command.status==='SENT'?'已发送，等待现场回执 · '+shortId(command.commandId):command.status+' · '+(command.statusMessage||'');
      await collect('decisions','/api/v1/simulations/'+enc(model.simulationId)+'/decisions?limit=500');
    } catch(error){$('dispatch-status').textContent='下发失败 · '+error.message;}
    renderManualDispatch(vehicle);renderDecisions();
  }

  function openSelectedDecisions(){const item=getSelected();if(!item)return;model.decisionFilter='all';const list=model.decisions.filter(x=>model.selected.type==='vehicle'?x.vehicleId===item.vehicleId:x.shovelId===item.shovelId);if(list.length)model.selectedDecision=list.sort((a,b)=>b.createdAtMs-a.createdAtMs)[0].decisionId;setView('decision');}
  function openSelectedCycle(){const item=getSelected();if(!item||model.selected.type!=='vehicle')return;const cycles=findCyclesForVehicle(item.vehicleId);if(cycles.length){model.selectedCycle=cycles[0].cycleId;setView('cycle');loadCycleStory(model.selectedCycle);}}

  function renderGeometry(map) {
    if(!map){clearMap();return;}const key=map.sequence+':'+map.imageWidthPx+':'+map.imageHeightPx;if(key===geometryKey)return;geometryKey=key;
    $('world').hidden=false;$('empty-map').hidden=true;$('world').style.width=map.imageWidthPx+'px';$('world').style.height=map.imageHeightPx+'px';
    $('mine-image').width=map.imageWidthPx;$('mine-image').height=map.imageHeightPx;
    const nodes=new Map((map.nodes||[]).map(x=>[x.nodeId,x])),svg=$('road-layer');svg.setAttribute('width',map.imageWidthPx);svg.setAttribute('height',map.imageHeightPx);svg.setAttribute('viewBox','0 0 '+map.imageWidthPx+' '+map.imageHeightPx);svg.replaceChildren();
    (map.segments||[]).forEach(segment=>{const from=nodes.get(segment.fromNodeId),to=nodes.get(segment.toNodeId);if(!from||!to)return;const line=document.createElementNS('http://www.w3.org/2000/svg','line');attrs(line,{x1:from.x,y1:from.y,x2:to.x,y2:to.y,class:'road'+(segment.closed?' closed':'')});svg.appendChild(line);});
    (map.nodes||[]).forEach(node=>{const circle=document.createElementNS('http://www.w3.org/2000/svg','circle');attrs(circle,{cx:node.x,cy:node.y,r:3,class:'road-node'});svg.appendChild(circle);if(/Crusher|Shovel|dump/i.test(node.nodeId)){const label=document.createElementNS('http://www.w3.org/2000/svg','text');attrs(label,{x:node.x+6,y:node.y-6,class:'node-label'});label.textContent=node.nodeId;svg.appendChild(label);}});
    requestAnimationFrame(fitMap);
  }

  function renderMarkers(vehicles,shovels) {
    const vl=$('vehicle-layer'),sl=$('shovel-layer');vl.replaceChildren();sl.replaceChildren();
    vehicles.forEach(x=>{const node=document.createElement('div');node.className='vehicle-marker'+(x.loaded?' loaded':'')+(x.stale?' stale':'')+(model.selected?.type==='vehicle'&&model.selected.id===x.vehicleId?' selected':'');node.dataset.label='T'+x.vehicleId;node.style.left=x.x+'px';node.style.top=x.y+'px';node.title='T'+x.vehicleId+' · '+(x.stale?'状态失联':num(x.speedMps,1)+' m/s');node.onclick=()=>selectObject('vehicle',x.vehicleId);vl.appendChild(node);});
    shovels.forEach(x=>{const node=document.createElement('div');node.className='shovel-marker'+(x.stale?' stale':'');node.style.left=x.x+'px';node.style.top=x.y+'px';node.innerHTML='<i>'+x.shovelId+'</i>';node.title=x.shovelName||'铲装点 '+x.shovelId;node.onclick=()=>selectObject('shovel',x.shovelId);sl.appendChild(node);});
  }
  function clearMap(){$('world').hidden=true;$('empty-map').hidden=false;$('vehicle-layer').replaceChildren();$('shovel-layer').replaceChildren();}
  function fitMap(){const map=model.snapshot?.map,viewport=$('map-viewport');if(!map||!viewport.clientWidth)return;zoom=Math.min(viewport.clientWidth/map.imageWidthPx,viewport.clientHeight/map.imageHeightPx);offsetX=(viewport.clientWidth-map.imageWidthPx*zoom)/2;offsetY=(viewport.clientHeight-map.imageHeightPx*zoom)/2;applyTransform();}
  function setZoom(next){if(!model.snapshot?.map)return;const viewport=$('map-viewport'),cx=(viewport.clientWidth/2-offsetX)/zoom,cy=(viewport.clientHeight/2-offsetY)/zoom;zoom=Math.max(.2,Math.min(3,next));offsetX=viewport.clientWidth/2-cx*zoom;offsetY=viewport.clientHeight/2-cy*zoom;applyTransform();}
  function applyTransform(){$('world').style.transform='translate('+offsetX+'px,'+offsetY+'px) scale('+zoom+')';}

  function renderDecisions() {
    syncFilters();const root=$('decision-list');let items=[...model.decisions].sort((a,b)=>b.createdAtMs-a.createdAtMs);
    if(model.decisionFilter==='TASK'||model.decisionFilter==='SPEED')items=items.filter(x=>x.decisionType===model.decisionFilter);
    if(model.decisionFilter==='failed')items=items.filter(x=>['REJECTED','PUBLISH_FAILED','EXPIRED'].includes(x.ackStatus));
    setText('decision-count',items.length+' 条');
    if(!items.length){root.className='decision-list empty';root.textContent=model.errors.get('decisions')||'当前筛选没有决策证据';renderDecisionDetail();return;}
    if(!items.some(x=>x.decisionId===model.selectedDecision))model.selectedDecision=items[0].decisionId;
    root.className='decision-list';root.innerHTML=items.map(x=>'<button class="decision-row '+(x.decisionId===model.selectedDecision?'selected':'')+'" data-id="'+esc(x.decisionId)+'"><span class="row-top"><b>'+(x.decisionType==='TASK'?'T'+value(x.vehicleId)+' → '+esc(targetFromDecision(x)||'目标未知'):'T'+value(x.vehicleId)+' · 控制参考')+'</b><em>'+(x.decisionType==='TASK'?'DISPATCH':'CONTROL')+'</em></span><span class="row-meta"><span>'+esc(x.triggerEvent||'触发未记录')+' · '+esc(x.decisionSource||'来源未知')+'</span><span class="ack-'+String(x.ackStatus).toLowerCase()+'">'+esc(x.ackStatus||'UNKNOWN')+'</span></span><span class="row-meta"><span>Command '+esc(shortId(x.commandId))+'</span><time>'+formatWall(x.createdAtMs)+'</time></span></button>').join('');
    root.querySelectorAll('button').forEach(button=>button.onclick=()=>{model.selectedDecision=button.dataset.id;updateUrl({decisionId:model.selectedDecision});renderDecisions();});
    renderDecisionDetail();
  }

  function renderDecisionDetail() {
    const root=$('decision-detail'),item=model.decisions.find(x=>x.decisionId===model.selectedDecision);
    if(!item){root.className='detail-empty';root.textContent='选择一条决策查看原始证据';setQuality($('decision-quality'),'unknown','UNKNOWN');return;}
    const input=parse(item.inputSummaryJson),output=parse(item.modelOutputJson);
    const critical=[item.triggerEvent,item.inputSummaryJson,item.modelOutputJson,item.validationStatus,item.commandId];
    const quality=critical.every(Boolean)?(item.ackStatus?'complete':'partial'):'insufficient';setQuality($('decision-quality'),quality,quality.toUpperCase());
    const error=['REJECTED','PUBLISH_FAILED','EXPIRED'].includes(item.ackStatus);
    const explanation=item.explanation||input?.explanation||input?.causalExplanation;
    root.className='evidence-chain';root.innerHTML=
      chain(1,'决策身份',(item.decisionType==='TASK'?'DISPATCH / 调度决策':'CONTROL / 协同控制')+' · '+(item.decisionSource||'来源未知'),'Decision '+item.decisionId+' · T'+value(item.vehicleId))+
      chain(2,'触发事件',item.triggerEvent||'未采集','决策创建 '+formatWall(item.createdAtMs))+
      chain(3,'网关状态与候选状态',gatewaySummary(input),'输入为网关采集证据，不表述为现场同步快照')+
      chain(4,'决策输入快照',inputSummary(input),explanation?'模型解释：'+explanation:'模型未提供因果解释',false,input)+
      chain(5,'模型 / 操作者输出',outputSummary(output),'原始输出保留在折叠证据中',false,output)+
      chain(6,'后台校验',(item.validationStatus||'UNKNOWN')+' · '+(item.validationDetail||'无详情'),'PASSED 仅代表校验通过，不等于现场执行',item.validationStatus!=='PASSED')+
      chain(7,'Command 发送',item.publishedAtMs?'已发布 · '+formatWall(item.publishedAtMs):'没有发布证据',(item.commandId||'Command 未采集')+' · '+(item.commandTopic||'Topic 未采集'),!item.publishedAtMs)+
      chain(8,'现场 Ack',(item.ackStatus||'PENDING')+' · '+(item.ackDetail||'等待现场结果'),item.ackAtMs?'回执 '+formatWall(item.ackAtMs):'尚无回执',error);
  }

  function chain(step,title,text,note,error=false,raw=null){return '<section class="chain-node'+(error?' error':'')+'" data-step="'+step+'"><h4>'+esc(title)+'</h4><p>'+esc(String(text))+'</p><small>'+esc(String(note))+'</small>'+(raw==null?'':'<details class="raw-evidence"><summary>查看原始 JSON</summary><pre>'+esc(JSON.stringify(raw,null,2))+'</pre></details>')+'</section>';}
  function gatewaySummary(input){const evidence=input?.evidenceSnapshot||input||{};const vehicle=evidence.vehicleStateAtGateway,candidates=evidence.candidateStatesAtGateway;return (vehicle?'车辆状态已采集':'车辆网关状态未采集')+' · '+(Array.isArray(candidates)?candidates.length+' 个候选状态':'候选状态未采集');}
  function inputSummary(input){if(input==null)return'输入证据无法解析';const values=input?.values||input?.evidenceSnapshot?.normalizedObservation;if(Array.isArray(values))return values.length+' 维输入 · '+(values.length===27?'符合当前调度输入维度':'维度与 27 不一致');return compact(input);}
  function outputSummary(output){if(output==null)return'输出证据无法解析';return output.targetNode?'目标 '+output.targetNode:output.action!=null?'动作 '+output.action:compact(output);}
  function targetFromDecision(x){const output=parse(x.modelOutputJson);return output?.targetNode||output?.destinationNode||'';}

  function cycleGroups() {
    const map=new Map();(model.productionEvents||[]).forEach(event=>{if(!map.has(event.cycleId))map.set(event.cycleId,{cycleId:event.cycleId,vehicleId:event.vehicleId,events:[]});map.get(event.cycleId).events.push(event);});
    return [...map.values()].map(group=>{group.events.sort((a,b)=>a.simTimeSeconds-b.simTimeSeconds||eventOrder.indexOf(a.eventType)-eventOrder.indexOf(b.eventType));group.present=new Set(group.events.map(x=>x.eventType));group.complete=eventOrder.every(x=>group.present.has(x));group.ordered=group.events.every((x,i,a)=>i===0||x.simTimeSeconds>=a[i-1].simTimeSeconds);return group;}).sort((a,b)=>(b.events.at(-1)?.simTimeSeconds||0)-(a.events.at(-1)?.simTimeSeconds||0));
  }
  function findCyclesForVehicle(id){return cycleGroups().filter(x=>x.vehicleId===id);}

  function renderCycles() {
    const root=$('cycle-list'),groups=cycleGroups();setText('cycle-count',groups.length+' 个');
    if(!groups.length){root.className='cycle-list empty';root.textContent=model.errors.get('productionEvents')||'当前 Run 尚无生产事件';renderCycleDetail();return;}
    if(!groups.some(x=>x.cycleId===model.selectedCycle))model.selectedCycle=groups[0].cycleId;
    root.className='cycle-list';root.innerHTML=groups.map(x=>'<button class="cycle-row '+(x.cycleId===model.selectedCycle?'selected':'')+'" data-id="'+esc(x.cycleId)+'"><span class="row-top"><b>T'+x.vehicleId+' · '+esc(shortId(x.cycleId))+'</b><em>'+x.events.length+'/8 事件</em></span><span class="progress"><i style="width:'+Math.min(100,x.events.length/8*100)+'%"></i></span><span class="row-meta"><span>'+(x.complete&&x.ordered?'作业阶段完整':'证据不完整')+'</span><span>'+cycleDuration(x)+'</span></span></button>').join('');
    root.querySelectorAll('button').forEach(button=>button.onclick=()=>{model.selectedCycle=button.dataset.id;updateUrl({cycleId:model.selectedCycle});renderCycles();loadCycleStory(model.selectedCycle);});
    renderCycleDetail();
  }

  async function loadCycleStory(cycleId) {
    if(!cycleId||!model.run)return;model.cycleStory=null;renderCycleDetail();
    try{model.cycleStory=await api('/api/v1/runs/'+enc(model.run.run_id)+'/cycles/'+enc(cycleId)+'/story');}
    catch(error){model.errors.set('cycleStory',error.message);}
    renderCycleDetail();
  }

  function renderCycleDetail() {
    const root=$('cycle-detail'),group=cycleGroups().find(x=>x.cycleId===model.selectedCycle);
    if(!group){root.className='detail-empty';root.textContent='选择 Cycle 查看事件完整性';setQuality($('cycle-quality'),'unknown','UNKNOWN');return;}
    setQuality($('cycle-quality'),group.complete&&group.ordered?'partial':'insufficient',group.complete&&group.ordered?'PARTIAL':'INSUFFICIENT');
    const byType=new Map(group.events.map(x=>[x.eventType,x]));
    const timeline=[
      ['UNLOAD_FINISHED_PREVIOUS','上次卸载完成（循环起点）',null,'冻结版完整循环起点未形成关联'],
      ['DISPATCH_TRIGGERED','调度触发',null,model.cycleStory?.taskDecisions?.length?'存在显式 Command 决策引用，但未采集为周期源事件':'未采集为周期源事件'],
      ['HAUL_EMPTY_START','空载运输开始',null,'未采集'],
      ...eventOrder.map(type=>[type,eventLabels[type],byType.get(type),null]),
      ['HAUL_FULL_START','满载运输开始',null,'未采集为独立事件']
    ];
    timeline.sort((a,b)=>timelineRank(a[0])-timelineRank(b[0]));
    root.className='cycle-detail';
    root.innerHTML='<div class="cycle-header">'+
      cycleFact('Cycle',group.cycleId)+cycleFact('车辆','T'+group.vehicleId)+cycleFact('关联',model.cycleStory?.association||'读取中')+cycleFact('Task','未采集 · 不以 Command 冒充')+
      '</div><div class="timeline"><div class="timeline-section">冻结完整循环证据 / 当前作业阶段事件</div>'+
      timeline.map(x=>timelineHtml(x[0],x[1],x[2],x[3])).join('')+
      '<div class="timeline-section">结论：'+(group.complete&&group.ordered?'现行八事件作业阶段完整；冻结版完整运输循环仍证据不足':'作业阶段事件不完整，不能形成周期结论')+'</div></div>';
  }

  function timelineRank(type){return {UNLOAD_FINISHED_PREVIOUS:0,DISPATCH_TRIGGERED:1,HAUL_EMPTY_START:2,ARRIVE_SHOVEL:3,QUEUE_LOAD:4,LOADING_START:5,LOADING_FINISH:6,HAUL_FULL_START:7,ARRIVE_DUMP:8,QUEUE_DUMP:9,DUMP_START:10,DUMP_FINISH:11}[type]??99;}
  function timelineHtml(type,label,event,reason){return '<div class="timeline-item'+(event?'':' missing')+'"><h4>'+esc(label)+'</h4><p>'+(event?'simTime '+num(event.simTimeSeconds,2)+' s · site '+event.siteId+' · Command '+esc(shortId(event.commandId)):('数据不足 · '+esc(reason||'未采集')))+'</p></div>';}
  function cycleFact(label,value){return '<div><span>'+esc(label)+'</span><strong>'+esc(String(value))+'</strong></div>';}
  function cycleDuration(group){if(group.events.length<2)return'--';return num(group.events.at(-1).simTimeSeconds-group.events[0].simTimeSeconds,1)+' s';}

  function setReplayDefault() {
    if(!model.run)return;const end=(model.run.ended_at_ms==null?Date.now():Number(model.run.ended_at_ms))-1;$('replay-time').value=localInput(Math.max(Number(model.run.started_at_ms),end));
  }

  async function loadReplay() {
    if(!model.run)return;const time=new Date($('replay-time').value).getTime();if(!finite(time)){alert('请选择有效墙钟时间');return;}
    $('replay-load').disabled=true;$('replay-load').textContent='正在读取…';
    try {
      const replay=await api('/api/v1/runs/'+enc(model.run.run_id)+'/replay?atWallTimeMs='+time);
      renderReplay(replay);
      updateUrl({queryWallTime:time});
    } catch(error){$('replay-detail').className='replay-detail empty';$('replay-detail').textContent='读取失败 · '+error.message;setQuality($('replay-quality'),'error','ERROR');}
    finally{$('replay-load').disabled=false;$('replay-load').textContent='读取已记录状态';}
  }

  function renderReplay(replay) {
    const vehicles=(replay.vehicle||[]),shovels=(replay.shovel||[]),samples=[...vehicles,...shovels];
    $('replay-wall').textContent=formatWall(replay.atWallTimeMs);
    const sim=samples.map(x=>Number(x.sim_time_seconds)).filter(finite);$('replay-sim').textContent=sim.length?Math.max(...sim).toFixed(1)+' s':'未采集';
    const stage=$('replay-stage');stage.replaceChildren();const canvas=document.createElement('div');canvas.className='replay-canvas';stage.appendChild(canvas);
    const coords=samples.filter(x=>finite(Number(x.x))&&finite(Number(x.y))),xs=coords.map(x=>Number(x.x)),ys=coords.map(x=>Number(x.y));
    const minX=Math.min(...xs,0),maxX=Math.max(...xs,1),minY=Math.min(...ys,0),maxY=Math.max(...ys,1);
    coords.forEach(x=>{const isVehicle=x.vehicle_id!=null,node=document.createElement('div');node.className='replay-point'+(isVehicle?'':' shovel');node.style.left=(8+(Number(x.x)-minX)/Math.max(1,maxX-minX)*84)+'%';node.style.top=(8+(Number(x.y)-minY)/Math.max(1,maxY-minY)*84)+'%';node.textContent=isVehicle?'T'+x.vehicle_id:'S'+x.shovel_id;node.title='接收 '+formatWall(x.received_at_ms);canvas.appendChild(node);});
    const detail=$('replay-detail');detail.className='replay-detail';detail.innerHTML=samples.map(x=>'<div class="replay-sample"><b>'+(x.vehicle_id!=null?'车辆 T'+x.vehicle_id:esc(x.shovel_name||'铲装点 '+x.shovel_id))+'</b><span>simTime '+num(x.sim_time_seconds,1)+' s · 接收 '+formatWall(x.received_at_ms)+' · 样本年龄 '+durationMs(replay.atWallTimeMs-x.received_at_ms)+'</span><span>'+(x.vehicle_id!=null?'坐标 '+num(x.x,1)+', '+num(x.y,1)+' · '+num(x.speed_mps,1)+' m/s · '+esc(x.statechart_state):'坐标 '+num(x.x,1)+', '+num(x.y,1)+' · 等待 '+x.waiting_trucks+' · 服务 '+x.trucks_in_service)+'</span></div>').join('')||'<div class="detail-empty">该时刻没有已持久化状态样本</div>';
    setQuality($('replay-quality'),samples.length?'partial':'insufficient',samples.length?'RECORDED':'INSUFFICIENT');
  }

  function connectWebSocket() {
    closeSocket();const protocol=location.protocol==='https:'?'wss':'ws';
    const socket=new WebSocket(protocol+'://'+location.host+'/ws/state?simulationId='+enc(model.simulationId));model.socket=socket;
    socket.onopen=()=>{model.reconnectDelay=1000;};
    socket.onmessage=event=>{let message;try{message=JSON.parse(event.data);}catch(_){return;}if(message.simulationId!==model.simulationId||!isActive())return;
      if(message.type==='snapshot')model.snapshot=message.data;
      if(message.type==='map'&&model.snapshot)model.snapshot.map=message.data;
      if(message.type==='vehicle'&&model.snapshot)upsert(model.snapshot.vehicles,'vehicleId',message.data);
      if(message.type==='shovel'&&model.snapshot)upsert(model.snapshot.shovels,'shovelId',message.data);
      if(message.type==='manualDispatch'){upsert(model.commands,'commandId',message.data);}
      if(message.type==='decisionTrace'){upsert(model.decisions,'decisionId',message.data);}
      if(message.type==='simulationRestart'&&model.snapshot){model.snapshot.vehicles=[];model.snapshot.shovels=[];}
      if(message.type==='status')applyStatus(message.data);
      renderAll();
    };
    socket.onclose=()=>{if(model.socket!==socket)return;model.socket=null;setTimeout(()=>{if(!model.socket&&model.simulationId)connectWebSocket();},model.reconnectDelay);model.reconnectDelay=Math.min(model.reconnectDelay*2,10000);};
  }
  function closeSocket(){if(model.socket){const old=model.socket;model.socket=null;old.onclose=null;old.close();}}
  function applyStatus(status){if(!model.snapshot)return;(model.snapshot.vehicles||[]).forEach(x=>{if(status.vehicleStale?.[x.vehicleId]!=null)x.stale=status.vehicleStale[x.vehicleId];});(model.snapshot.shovels||[]).forEach(x=>{if(status.shovelStale?.[x.shovelId]!=null)x.stale=status.shovelStale[x.shovelId];});model.snapshot.online=status.online;}

  function getSelected(){if(!model.selected||!model.snapshot)return null;return model.selected.type==='vehicle'?(model.snapshot.vehicles||[]).find(x=>x.vehicleId===model.selected.id):(model.snapshot.shovels||[]).find(x=>x.shovelId===model.selected.id);}
  function syncFilters(){$('monitor-filters').querySelectorAll('button').forEach(x=>x.classList.toggle('active',x.dataset.filter===model.monitorFilter));$('decision-filters').querySelectorAll('button').forEach(x=>x.classList.toggle('active',x.dataset.filter===model.decisionFilter));}
  function isActive(){return model.run?.ended_at_ms==null;}
  function showLoading(text){const node=$('page-state');node.className='page-state show loading';node.innerHTML='<span class="spinner"></span><strong>'+esc(text)+'</strong><small>只读取已记录事实</small>';}
  function showPageError(title,text){const node=$('page-state');node.className='page-state show error';node.innerHTML='<strong>'+esc(title)+'</strong><small>'+esc(text)+'</small>';}
  function hidePageState(){$('page-state').className='page-state';}
  function setHealth(node,ok,text){node.className='health-badge'+(ok===true?' ok':ok===false?' bad':'');node.querySelector('span').textContent=text;}
  function setQuality(node,kind,text){node.className='quality-tag '+kind;node.textContent=text;}
  function bannerFact(label,v){return '<div><span>'+esc(label)+'</span><strong>'+v+'</strong></div>';}
  function option(v,label){const node=document.createElement('option');node.value=v;node.textContent=label;return node;}
  function runLabel(run){return (run.ended_at_ms==null?'● ':'○ ')+(run.scenario_id||'未声明场景')+' · '+shortId(run.run_id);}
  function eventName(v){return eventLabels[v]||String(v||'UNKNOWN').replaceAll('_',' ');}
  function setText(id,text){$(id).textContent=text;}
  function updateUrl(changes){const next=new URLSearchParams(location.search);Object.entries(changes).forEach(([k,v])=>v==null||v===''?next.delete(k):next.set(k,v));history.replaceState(null,'',location.pathname+'?'+next.toString());}
  function attrs(node,map){Object.entries(map).forEach(x=>node.setAttribute(x[0],x[1]));}
  function upsert(list,key,item){const index=list.findIndex(x=>x[key]===item[key]);if(index<0)list.push(item);else list[index]=item;}
  function parse(value){if(value==null)return null;if(typeof value==='object')return value;try{return JSON.parse(value);}catch(_){return value;}}
  function compact(v){const s=typeof v==='string'?v:JSON.stringify(v);return s.length>300?s.slice(0,297)+'…':s;}
  function value(v){return v==null?'--':v;}
  function num(v,d=1){return finite(Number(v))?Number(v).toFixed(d):'--';}
  function unit(v,u,d=1){return v==null?'未采集':num(v,d)+' '+u;}
  function finite(v){return Number.isFinite(v);}
  function shortId(v){if(!v)return'--';const s=String(v);return s.length>12?s.slice(0,8)+'…':s;}
  function enc(v){return encodeURIComponent(v);}
  function formatWall(v){if(v==null||!finite(Number(v)))return'--';return new Date(Number(v)).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});}
  function age(v){return v?durationMs(Date.now()-v):'未知';}
  function durationMs(v){if(!finite(Number(v)))return'未知';const s=Math.max(0,Math.round(v/1000));return s<60?s+' 秒':Math.floor(s/60)+' 分 '+s%60+' 秒';}
  function localInput(ms){const date=new Date(ms-dateOffset(ms));return date.toISOString().slice(0,19);}
  function dateOffset(ms){return new Date(ms).getTimezoneOffset()*60000;}
  function esc(value){return String(value??'').replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));}

  async function api(url,options={},allowAccepted=false) {
    const response=await fetch(url,{cache:'no-store',...options});
    let body=null;try{body=await response.json();}catch(_){}
    if(!response.ok&&!(allowAccepted&&response.status===202))throw new Error(body?.message||body?.error||('HTTP '+response.status));
    return body;
  }
}).catch(error=>console.error(error));
