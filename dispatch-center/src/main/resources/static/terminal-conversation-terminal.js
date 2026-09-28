window.dispatchIdentity.then(identity => {
  if (!['VEHICLE','SHOVEL'].includes(identity.role)) return;
  const style = document.createElement('link');
  style.rel = 'stylesheet'; style.href = '/terminal-conversation.css'; document.head.appendChild(style);
  const panel = document.createElement('section');
  panel.className = 'panel full conversation-panel';
  panel.innerHTML = '<h2>调度中心消息 <small>MQTT CONVERSATION</small></h2><p class="conversation-hint">收到调度消息后点击“回复”。消息通过 MQTT 返回调度中心。</p><div id="conversation-thread" class="conversation-thread">等待调度消息</div><form id="conversation-form" class="conversation-form"><textarea id="conversation-text" maxlength="1000" required placeholder="输入回复内容"></textarea><button id="conversation-send" type="submit">通过 MQTT 回复</button></form><div id="conversation-status" class="conversation-status"></div>';
  document.querySelector('main').appendChild(panel);
  panel.scrollTop = 0;

  const sim = identity.simulationId, terminalId = identity.subjectId;
  const el = id => document.getElementById(id), thread = el('conversation-thread');
  if (!thread) return;
  let selected = null, busy = false;
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const notice = text => { el('conversation-status').textContent = text; };
  thread.onclick = event => {
    const button = event.target.closest('button[data-reply]');
    if (!button) return;
    selected = button.dataset.reply;
    notice(`回复调度消息 ${selected}`);
    el('conversation-text').focus();
  };
  el('conversation-form').onsubmit = async event => {
    event.preventDefault();
    if (busy) return;
    const text = el('conversation-text').value.trim();
    if (!selected || !text) { notice('请选择一条调度消息并输入回复'); return; }
    busy = true; el('conversation-send').disabled = true; notice('正在通过 MQTT 回复…');
    try {
      const response = await fetch(`/api/v1/simulations/${encodeURIComponent(sim)}/terminal-messages/${encodeURIComponent(selected)}/reply`, {
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({body:text})
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
      el('conversation-text').value = '';
      notice(`已通过 MQTT 回复，消息 ID：${result.messageId}`);
      await refresh();
    } catch (error) { notice(`回复失败：${error.message}`); }
    finally { busy = false; el('conversation-send').disabled = false; }
  };
  async function refresh() {
    try {
      const response = await fetch(`/api/v1/simulations/${encodeURIComponent(sim)}/terminal-messages?limit=100`, {cache:'no-store'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const messages = await response.json();
      if (!messages.length) { thread.textContent = '暂无调度消息'; return; }
      thread.innerHTML = messages.map(m => `<article class="conversation-item ${m.direction === 'INBOUND' ? 'inbound' : 'outbound'} ${m.status === 'FAILED' ? 'failed' : ''}"><div class="meta"><b>${m.direction === 'OUTBOUND' ? '调度中心' : '本端'}</b><time>${new Date(m.createdAtMs).toLocaleString()}</time><span>${esc(m.status)}</span></div><p>${esc(m.body)}</p>${m.direction === 'OUTBOUND' ? `<button type="button" data-reply="${esc(m.messageId)}">回复</button>` : ''}</article>`).join('');
      if (!selected) {
        const latest = [...messages].reverse().find(m => m.direction === 'OUTBOUND');
        if (latest) { selected = latest.messageId; notice(`可回复最新调度消息 ${selected}`); }
      }
    } catch (error) { thread.textContent = `消息加载失败：${error.message}`; }
  }
  refresh(); setInterval(refresh, 3000);
});
