(function () {
  const base = window.BACKEND_URL || window.location.origin;
  const api = base + '/api';
  let csrfToken = '';

  async function readSession(guest) {
    const response = await fetch(api + '/auth/session' + (guest ? '?guest=true' : ''), { credentials: 'same-origin' });
    const json = await response.json();
    if (!response.ok || json.code !== 200) throw new Error(json.msg || '无法读取登录状态');
    const data = json.data || {};
    csrfToken = data.csrfToken || '';
    window.SHUTTLE_CSRF = csrfToken;
    window.SHUTTLE_SESSION = data;
    return data;
  }

  function headers(extra) {
    const result = Object.assign({}, extra || {});
    if (csrfToken) result['X-Shuttle-CSRF'] = csrfToken;
    return result;
  }

  async function passengerSession() {
    let session = await readSession(true);
    if (!session.user || session.user.role !== 'PASSENGER') {
      await fetch(api + '/auth/logout', { method: 'POST', credentials: 'same-origin', headers: headers() });
      session = await readSession(true);
    }
    return session;
  }

  async function mountProtected(app, role) {
    try {
      const session = await readSession(false);
      if (!session.authenticated || !session.user || session.user.role !== role) {
        const next = role === 'ADMIN' ? 'admin.html' : 'driver.html';
        window.location.replace('login.html?role=' + (role === 'ADMIN' ? 'admin' : 'driver') + '&next=' + encodeURIComponent(next));
        return;
      }
      app.mount('#app');
    } catch (error) {
      window.location.replace('login.html?role=' + (role === 'ADMIN' ? 'admin' : 'driver'));
    }
  }

  async function mountPassenger(app) {
    try {
      let session = await readSession(false);
      if (session.authenticated && session.user && session.user.role === 'PASSENGER') {
        app.mount('#app');
        return;
      }
      const guestRequested = new URLSearchParams(window.location.search).get('guest') === '1';
      if (!session.authenticated && guestRequested) {
        session = await readSession(true);
        if (session.user && session.user.guest) { app.mount('#app'); return; }
      }
      window.location.replace('login.html?role=passenger&next=passenger.html');
    } catch (error) {
      window.location.replace('login.html?role=passenger&next=passenger.html');
    }
  }

  async function logout() {
    try { await fetch(api + '/auth/logout', { method: 'POST', credentials: 'same-origin', headers: headers() }); }
    finally { csrfToken = ''; window.location.replace('index.html'); }
  }

  window.ShuttleAuth = { readSession, passengerSession, headers, mountProtected, mountPassenger, logout };
})();
