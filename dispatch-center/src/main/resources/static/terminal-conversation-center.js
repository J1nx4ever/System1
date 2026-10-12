window.dispatchIdentity.then(identity => {
  if (identity.role !== 'DISPATCHER') return;
  const style = document.createElement('link');
  style.rel = 'stylesheet'; style.href = '/terminal-conversation.css'; document.head.appendChild(style);
  const panel = document.createElement('section');
  panel.className = 'card conversation-panel';
  panel.innerHTML = '<header><div><span class="eyebrow">FIELD CONVERSATION</span><h3>调度 ↔ 现场终端</h3></div><small>MQTT 双向消息 · 当前 Run</small></header><form id="conversation-form" class="conversation-form"><label>对象<select id="conversation-target-type"><option value="VEHICLE">车辆</option><option value="SHOVEL">铲端</option></select></label><label>编号<input id="conversation-target-id" type="number" min="0" required></label><textarea id="conversation-text" maxlength="1000" required placeholder="输入回复或调度消息"></textarea><button id="conversation-send" type="submit">通过 MQTT 发送</button></form><div id="conversation-status" class="conversation-status"></div><div id="conversation-thread" class="conversation-thread">等待消息</div>';
  document.querySelector('.overview-grid').appendChild(panel);
  panel.scrollTop = 0;

  const el = id => document.getElementById(id);
  const form = el('conversation-form'), thread = el('conversation-thread');
  if (!form || !thread) return;
  let replyTo = null, busy = false, lastScope = '';
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const label = m => m.objectType === 'VEHICLE' ? `车辆 T${m.objectId}` : `铲 S${m.objectId}`;
  const status = m => ({PENDING:'等待发布',SENT:'已送达 MQTT Broker，待终端响应',DELIVERED:'终端已响应',RECEIVED:'已收到现场响应',FAILED:'发布失败'})[m.status] || m.status;
  const scope = () => ({sim:el('simulation-select').value,runId:el('run-select').value});
  const notice = text => { el('conversation-status').textContent = text; };
  window.addEventListener('dispatch-reply-terminal', event => {
    const {type,id,message} = event.detail;
    el('conversation-target-type').value = type;
    el('conversation-target-id').value = id;
    el('conversation-text').value = '关于异常“' + message + '”：';
    replyTo = null;
    notice('已定位异常上报对象，请补充处理意见后发送');
    panel.scrollIntoView({behavior:'smooth',block:'center'});
  });
  form.onsubmit = async event => {
    event.preventDefault();
    if (busy) return;
    const {sim,runId} = scope(), text = el('conversation-text').value.trim();
    const id = Number(el('conversation-target-id').value), type = el('conversation-target-type').value;
    if (!sim || !runId || !text || !Number.isInteger(id) || id < 0) { notice('请选择当前 Run、目标对象并输入消息'); return; }
    const runs = await getRuns(sim).catch(() => []);
    const run = runs.find(x => x.run_id === runId);
    if (!run || run.ended_at_ms != null) { notice('仅可向当前运行中的 Run 发送'); return; }
    busy = true; el('conversation-send').disabled = true; notice('正在通过 MQTT 发布…');
    try {
      const response = await fetch(`/api/v1/simulations/${encodeURIComponent(sim)}/terminal-messages`, {
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({objectType:type,objectId:id,body:text,replyToMessageId:replyTo})
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
      el('conversation-text').value = ''; replyTo = null;
      notice(`已发布给 ${label(result)}；等待终端回复。消息 ID：${result.messageId}`);
      await refresh();
    } catch (error) { notice(`发送失败：${error.message}`); await refresh(); }
    finally { busy = false; el('conversation-send').disabled = false; }
  };
  thread.onclick = event => {
    const button = event.target.closest('button[data-reply]');
    if (!button) return;
    const [type,id,messageId] = button.dataset.reply.split('|');
    el('conversation-target-type').value = type;
    el('conversation-target-id').value = id;
    replyTo = messageId;
    notice(`正在回复 ${type === 'VEHICLE' ? '车辆 T' : '铲 S'}${id} 的消息 ${messageId}`);
    el('conversation-text').focus();
  };
  el('conversation-target-type').onchange = () => { replyTo = null; };
  el('conversation-target-id').onchange = () => { replyTo = null; };
  async function getRuns(sim) {
    const response = await fetch(`/api/v1/runs?simulationId=${encodeURIComponent(sim)}`, {cache:'no-store'});
    if (!response.ok) throw new Error(`Run HTTP ${response.status}`);
    return response.json();
  }
  async function refresh() {
    const {sim,runId} = scope();
    if (!sim || !runId) { thread.textContent = '等待选择 Run'; return; }
    const key = `${sim}/${runId}`;
    if (key !== lastScope) { lastScope = key; replyTo = null; }
    try {
      const [runs,response] = await Promise.all([
        getRuns(sim),
        fetch(`/api/v1/simulations/${encodeURIComponent(sim)}/terminal-messages?limit=500&runId=${encodeURIComponent(runId)}`, {cache:'no-store'})
      ]);
      if (!response.ok) throw new Error(`消息 HTTP ${response.status}`);
      const run = runs.find(x => x.run_id === runId);
      if (!run) { thread.textContent = '运行记录不存在'; return; }
      const messages = await response.json();
      if (!messages.length) { thread.textContent = '当前 Run 尚无终端消息'; return; }
      thread.innerHTML = messages.map(m => `<article class="conversation-item ${m.direction === 'INBOUND' ? 'inbound' : 'outbound'} ${m.status === 'FAILED' ? 'failed' : ''}"><div class="meta"><b>${esc(label(m))}</b><span>${m.direction === 'INBOUND' ? '现场回复' : '调度下发'}</span><span>${esc(m.senderName)}</span><time>${new Date(m.createdAtMs).toLocaleString()}</time><span>${esc(status(m))}</span></div><p>${esc(m.body)}</p>${m.statusDetail ? `<small>${esc(m.statusDetail)}</small>` : ''}${m.direction === 'INBOUND' ? `<button type="button" data-reply="${esc(m.objectType)}|${m.objectId}|${esc(m.messageId)}">回复该终端</button>` : ''}</article>`).join('');
    } catch (error) { thread.textContent = `消息加载失败：${error.message}`; }
  }
  refresh(); setInterval(refresh, 3000);
});
