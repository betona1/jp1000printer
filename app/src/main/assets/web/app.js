const App = (() => {
    let token = localStorage.getItem('token');
    let pollTimer = null;
    const DAY_NAMES = ['월', '화', '수', '목', '금', '토', '일'];

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
        document.getElementById('password-change-modal').style.display = 'none';
        stopPolling();
    }

    function showDashboard() {
        document.getElementById('login-view').style.display = 'none';
        document.getElementById('password-change-modal').style.display = 'none';
        document.getElementById('dashboard-view').style.display = 'block';
        refreshStatus();
        refreshDevice();
        refreshSettings();
        startPolling();
    }

    function showPasswordChangeModal() {
        document.getElementById('login-view').style.display = 'none';
        document.getElementById('dashboard-view').style.display = 'none';
        document.getElementById('password-change-modal').style.display = 'flex';
        document.getElementById('new-password').value = '';
        document.getElementById('confirm-password').value = '';
        document.getElementById('pw-change-error').hidden = true;
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
                if (json.data.requirePasswordChange) {
                    showPasswordChangeModal();
                } else {
                    showDashboard();
                }
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

    // ── Password Change ─────────────────────
    async function changePassword() {
        const newPw = document.getElementById('new-password').value;
        const confirmPw = document.getElementById('confirm-password').value;
        const errEl = document.getElementById('pw-change-error');
        errEl.hidden = true;

        if (newPw.length < 4) {
            errEl.textContent = '4자리 이상 입력해주세요';
            errEl.hidden = false;
            return;
        }
        if (newPw !== confirmPw) {
            errEl.textContent = '비밀번호가 일치하지 않습니다';
            errEl.hidden = false;
            return;
        }

        try {
            const res = await api('POST', '/api/auth/change-password', {
                newPassword: newPw,
                confirmPassword: confirmPw
            });
            if (res.success) {
                showDashboard();
            } else {
                errEl.textContent = res.error;
                errEl.hidden = false;
            }
        } catch (err) {
            errEl.textContent = '변경 실패: ' + err.message;
            errEl.hidden = false;
        }
    }

    // ── Password Change (from settings) ─────
    async function changePasswordFromSettings() {
        const currentPw = document.getElementById('pw-current').value;
        const newPw = document.getElementById('pw-new').value;
        const confirmPw = document.getElementById('pw-confirm').value;
        const resultEl = document.getElementById('pw-settings-result');
        resultEl.hidden = true;

        if (!currentPw) {
            resultEl.textContent = '현재 비밀번호를 입력해주세요';
            resultEl.className = 'action-result fail';
            resultEl.hidden = false;
            return;
        }
        if (newPw.length < 4) {
            resultEl.textContent = '새 비밀번호는 4자리 이상이어야 합니다';
            resultEl.className = 'action-result fail';
            resultEl.hidden = false;
            return;
        }
        if (newPw !== confirmPw) {
            resultEl.textContent = '새 비밀번호가 일치하지 않습니다';
            resultEl.className = 'action-result fail';
            resultEl.hidden = false;
            return;
        }

        try {
            const res = await api('POST', '/api/auth/change-password', {
                currentPassword: currentPw,
                newPassword: newPw,
                confirmPassword: confirmPw
            });
            resultEl.textContent = res.success ? '비밀번호가 변경되었습니다' : res.error;
            resultEl.className = 'action-result' + (res.success ? '' : ' fail');
            resultEl.hidden = false;
            if (res.success) {
                document.getElementById('pw-current').value = '';
                document.getElementById('pw-new').value = '';
                document.getElementById('pw-confirm').value = '';
            }
            setTimeout(() => { resultEl.hidden = true; }, 4000);
        } catch (err) {
            resultEl.textContent = '변경 실패: ' + err.message;
            resultEl.className = 'action-result fail';
            resultEl.hidden = false;
        }
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
            const d = res.data;
            document.getElementById('setting-url').value = d.schoolUrl || '';
            document.getElementById('setting-autostart').checked = !!d.autoStart;
            document.getElementById('setting-powerbtn').checked = !!d.showPowerBtn;
            document.getElementById('setting-schedule-show').checked = !!d.showSchedule;

            // Cut mode
            if (d.cutMode === 'partial') {
                document.getElementById('cut-partial').checked = true;
            } else {
                document.getElementById('cut-full').checked = true;
            }

            // Schedule
            buildScheduleTable(d.schedule || []);
        } catch (e) {}
    }

    async function saveSettings() {
        const resultEl = document.getElementById('settings-result');
        const cutMode = document.querySelector('input[name="cutMode"]:checked').value;

        const payload = {
            schoolUrl: document.getElementById('setting-url').value.trim(),
            autoStart: document.getElementById('setting-autostart').checked,
            showPowerBtn: document.getElementById('setting-powerbtn').checked,
            showSchedule: document.getElementById('setting-schedule-show').checked,
            cutMode: cutMode,
            schedule: getScheduleFromTable()
        };

        try {
            const res = await api('PUT', '/api/settings', payload);
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

    // ── Schedule Table ──────────────────────
    function buildScheduleTable(schedule) {
        const tbody = document.getElementById('schedule-body');
        tbody.innerHTML = '';
        for (let i = 0; i < 7; i++) {
            const s = schedule[i] || { enabled: false, startHour: 9, startMin: 0, endHour: 18, endMin: 0 };
            const tr = document.createElement('tr');
            tr.innerHTML =
                '<td>' + DAY_NAMES[i] + '</td>' +
                '<td><label class="toggle"><input type="checkbox" data-sched="' + i + '-on"' + (s.enabled ? ' checked' : '') + '><span class="slider"></span></label></td>' +
                '<td><input type="time" data-sched="' + i + '-start" value="' + pad(s.startHour) + ':' + pad(s.startMin) + '"></td>' +
                '<td><input type="time" data-sched="' + i + '-end" value="' + pad(s.endHour) + ':' + pad(s.endMin) + '"></td>';
            tbody.appendChild(tr);
        }
    }

    function getScheduleFromTable() {
        const schedule = [];
        for (let i = 0; i < 7; i++) {
            const onEl = document.querySelector('[data-sched="' + i + '-on"]');
            const startEl = document.querySelector('[data-sched="' + i + '-start"]');
            const endEl = document.querySelector('[data-sched="' + i + '-end"]');
            const startParts = (startEl ? startEl.value : '09:00').split(':');
            const endParts = (endEl ? endEl.value : '18:00').split(':');
            schedule.push({
                enabled: onEl ? onEl.checked : false,
                startHour: parseInt(startParts[0]) || 9,
                startMin: parseInt(startParts[1]) || 0,
                endHour: parseInt(endParts[0]) || 18,
                endMin: parseInt(endParts[1]) || 0
            });
        }
        return schedule;
    }

    function pad(n) { return String(n).padStart(2, '0'); }

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

    return { testPrint, feed, cut, saveSettings, changePassword, changePasswordFromSettings };
})();
