const App = (() => {
    let token = localStorage.getItem('token');
    let pollTimer = null;

    // ── API Helper ──────────────────────────
    async function api(method, path, body) {
        const opts = {
            method,
            headers: { 'Content-Type': 'application/json' }
        };
        if (token) opts.headers['Authorization'] = 'Bearer ' + token;
        if (body) opts.body = JSON.stringify(body);

        const res = await fetch(path, opts);
        if (res.status === 401) {
            logout();
            throw new Error('Unauthorized');
        }
        return res.json();
    }

    // ── Views ───────────────────────────────
    function showLogin() {
        document.getElementById('login-view').style.display = 'flex';
        document.getElementById('dashboard-view').style.display = 'none';
        stopPolling();
    }

    function showDashboard() {
        document.getElementById('login-view').style.display = 'none';
        document.getElementById('dashboard-view').style.display = 'block';
        refreshStatus();
        refreshDevice();
        refreshSettings();
        startPolling();
    }

    // ── Login / Logout ──────────────────────
    async function login(e) {
        e.preventDefault();
        const errEl = document.getElementById('login-error');
        errEl.hidden = true;
        errEl.textContent = '로그인 중...';
        errEl.style.color = '#1976d2';
        errEl.hidden = false;

        const username = document.getElementById('username').value;
        const password = document.getElementById('password').value;

        try {
            const res = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: username, password: password })
            });
            const json = await res.json();
            if (json.success) {
                token = json.data.token;
                localStorage.setItem('token', token);
                showDashboard();
            } else {
                errEl.style.color = '';
                errEl.textContent = json.error;
            }
        } catch (err) {
            errEl.style.color = '';
            errEl.textContent = err.name + ': ' + err.message;
        }
    }

    function logout() {
        if (token) {
            api('POST', '/api/auth/logout').catch(() => {});
        }
        token = null;
        localStorage.removeItem('token');
        showLogin();
    }

    // ── Status ──────────────────────────────
    async function refreshStatus() {
        try {
            const res = await api('GET', '/api/status');
            if (!res.success) return;
            const d = res.data;

            setDot('dot-paper', d.paper === 0 ? 'ok' : 'error');
            setLabel('lbl-paper', d.paper === 0 ? '정상' : '없음');

            setDot('dot-cover', d.cover === 0 ? 'ok' : 'error');
            setLabel('lbl-cover', d.cover === 0 ? '닫힘' : '열림');

            setDot('dot-overheat', d.overheat === 0 ? 'ok' : 'error');
            setLabel('lbl-overheat', d.overheat === 0 ? '정상' : '과열');

            setDot('dot-connection', d.isOpen ? 'ok' : 'warn');
            setLabel('lbl-connection', d.isOpen ? '연결됨' : '미연결');

            document.getElementById('last-update').textContent = new Date().toLocaleTimeString('ko-KR');
        } catch (e) {
            // ignore polling errors
        }
    }

    function setDot(id, cls) {
        const el = document.getElementById(id);
        el.className = 'status-dot ' + cls;
    }

    function setLabel(id, text) {
        document.getElementById(id).textContent = text;
    }

    // ── Device Info ─────────────────────────
    async function refreshDevice() {
        try {
            const res = await api('GET', '/api/device');
            if (!res.success) return;
            const d = res.data;
            document.getElementById('info-model').textContent = d.model;
            document.getElementById('info-android').textContent = 'Android ' + d.android + ' (SDK ' + d.sdkInt + ')';
            document.getElementById('info-ip').textContent = d.ip;
            document.getElementById('info-version').textContent = d.appVersion;
            document.getElementById('info-uptime').textContent = d.uptime;
        } catch (e) {}
    }

    // ── Settings ────────────────────────────
    async function refreshSettings() {
        try {
            const res = await api('GET', '/api/settings');
            if (!res.success) return;
            document.getElementById('setting-url').value = res.data.schoolUrl || '';
        } catch (e) {}
    }

    async function saveSettings() {
        const url = document.getElementById('setting-url').value.trim();
        const resultEl = document.getElementById('settings-result');
        try {
            const res = await api('PUT', '/api/settings', { schoolUrl: url });
            resultEl.textContent = res.success ? '설정이 저장되었습니다' : res.error;
            resultEl.className = 'action-result' + (res.success ? '' : ' fail');
            resultEl.hidden = false;
            setTimeout(() => { resultEl.hidden = true; }, 3000);
        } catch (e) {
            resultEl.textContent = '저장 실패';
            resultEl.className = 'action-result fail';
            resultEl.hidden = false;
        }
    }

    // ── Printer Actions ─────────────────────
    async function printerAction(path, label) {
        const resultEl = document.getElementById('action-result');
        resultEl.textContent = label + ' 처리 중...';
        resultEl.className = 'action-result';
        resultEl.hidden = false;

        try {
            const res = await api('POST', path);
            resultEl.textContent = res.success ? res.data.message : res.error;
            resultEl.className = 'action-result' + (res.success ? '' : ' fail');
        } catch (e) {
            resultEl.textContent = label + ' 실패';
            resultEl.className = 'action-result fail';
        }
        setTimeout(() => { resultEl.hidden = true; }, 4000);
    }

    function testPrint() { printerAction('/api/print/test', '테스트 인쇄'); }
    function feed() { printerAction('/api/print/feed', '용지 이송'); }
    function cut() { printerAction('/api/print/cut', '용지 절단'); }

    // ── Polling ─────────────────────────────
    function startPolling() {
        stopPolling();
        pollTimer = setInterval(refreshStatus, 10000);
    }

    function stopPolling() {
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    }

    // ── Init ────────────────────────────────
    function init() {
        document.getElementById('login-form').addEventListener('submit', login);
        document.getElementById('logout-btn').addEventListener('click', logout);

        if (token) {
            // Validate existing token
            api('GET', '/api/status')
                .then(res => { res.success ? showDashboard() : showLogin(); })
                .catch(() => showLogin());
        } else {
            showLogin();
        }
    }

    document.addEventListener('DOMContentLoaded', init);

    return { testPrint, feed, cut, saveSettings };
})();
