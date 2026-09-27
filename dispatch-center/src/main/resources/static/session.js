(() => {
  const next = location.pathname + location.search;
  window.dispatchIdentity = fetch('/api/v1/auth/me', { cache: 'no-store' }).then(async response => {
    if (!response.ok) { location.replace(`/login.html?next=${encodeURIComponent(next)}`); throw new Error('authentication required'); }
    const user = await response.json();
    const badge = document.createElement('button'); badge.className = 'identity-badge'; badge.textContent = `${user.username} · ${user.role}`; badge.title = '退出登录';
    badge.onclick = async () => { await fetch('/api/v1/auth/logout', { method: 'POST' }); location.replace('/login.html'); };
    document.body.appendChild(badge); return user;
  });
})();
