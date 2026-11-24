(function () {
  const base = window.BACKEND_URL || window.location.origin;
  const api = base + '/api';
  let csrfToken = '';
  let currentRead = 0;

  async function readSession(guest) {
    const read = ++currentRead;
    const response = await fetch(api + '/auth/session' + (guest ? '?guest=true' : ''), { credentials: 'same-origin', cache: 'no-store' });
    const json = await response.json();
    if (!response.ok || json.code !== 200) throw new Error(json.msg || '无法读取登录状态');
    const data = json.data || {};
    if (read === currentRead) {
      csrfToken = data.csrfToken || '';
      window.SHUTTLE_CSRF = csrfToken;
      window.SHUTTLE_SESSION = data;
    }
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

  async function mountProtected(app, role, requestedPage) {
    try {
      const session = await readSession(false);
      if (!session.authenticated || !session.user || session.user.role !== role && !(role === 'ADMIN' && session.user.role === 'DISPATCHER')) {
        const next = requestedPage || (role === 'ADMIN' ? 'admin.html' : 'driver.html');
        window.location.replace('login.html?role=' + (role === 'ADMIN' ? 'admin' : 'driver') + '&next=' + encodeURIComponent(next));
        return;
      }
      // operation.js also checks the session on load; Vue must use this verified snapshot.
      window.SHUTTLE_SESSION = session;
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
