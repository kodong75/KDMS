// KDMS 화면 스크립트. 외부 라이브러리 없음. 값은 textContent 로만 넣는다(HTML 로 해석하지 않는다).
'use strict';

const FLOW = ['SCHEMA_DONE', 'LOADING', 'SYNCING', 'CUTOVER', 'VERIFIED', 'DONE'];
const STATUS_KO = {
    PLANNED: '계획', SCHEMA_DONE: '스키마 완료', LOADING: '적재 중', SYNCING: '반영 중', CUTOVER: '전환 중',
    VERIFIED: '검증 통과', DONE: '완료', FAILED: '실패', PENDING: '대기', LOADED: '적재 완료', EXCLUDED: '제외',
    RUNNING: '실행 중', SKIPPED: '건너뜀'
};
const STEP_KO = { drain: '마지막 반영', reload: 'PK 없는 테이블 재적재', post_load: 'UNIQUE·인덱스', verify: '검증', setval: 'IDENTITY·SEQUENCE', fk: 'FK' };

const $ = (id) => document.getElementById(id);
const fmt = (n) => (n === null || n === undefined) ? '-' : Number(n).toLocaleString('ko-KR');
const ko = (s) => STATUS_KO[s] || s || '-';

function el(tag, text, cls) {
    const e = document.createElement(tag);
    if (text !== undefined && text !== null) e.textContent = text;
    if (cls) e.className = cls;
    return e;
}

function time(iso) {
    if (!iso) return '-';
    const d = new Date(iso);
    if (isNaN(d)) return String(iso).replace('T', ' ').substring(0, 19);
    const p = (n) => String(n).padStart(2, '0');
    return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
}

function seconds(ms) {
    return ms === null || ms === undefined ? '-' : (ms / 1000).toFixed(1) + '초';
}

function renderJob(s) {
    const err = $('job-error');
    const v = s.view;
    if (s.error || !v || !v.job) {
        err.hidden = false;
        err.textContent = s.error || '작업이 아직 없다. 명령줄에서 kdms schema 로 대상 테이블을 만든다';
        $('job-status').textContent = s.error ? '조회 실패' : '작업 없음';
        $('job-status').className = 'badge fail';
        return;
    }
    const job = v.job;
    err.hidden = !job.lastError;
    err.textContent = job.lastError || '';
    const badge = $('job-status');
    badge.textContent = ko(job.status);
    badge.className = 'badge ' + (job.status === 'FAILED' ? 'fail' : job.status === 'DONE' ? 'ok' : 'run');

    const at = FLOW.indexOf(job.status);
    document.querySelectorAll('#flow li').forEach((li, i) => {
        li.className = at < 0 ? '' : i < at ? 'done' : i === at ? 'now' : '';
    });

    // 변경분 반영
    const sy = v.sync;
    const running = s.running || [];
    if (sy) {
        const lag = sy.lagSeconds;
        $('lag').textContent = lag === null || lag === undefined ? '-' : Number(lag).toFixed(1) + '초';
        $('sync-counts').textContent = fmt(sy.captured) + ' · ' + fmt(sy.applied) + ' · ' + fmt(sy.pending);
        $('sync-src').textContent = sy.srcMaxAt ? String(sy.srcMaxAt).replace('T', ' ').substring(0, 19) : '-';
        const age = sy.statusAt ? (Date.parse(s.at) - Date.parse(sy.statusAt)) / 1000 : null;
        const on = running.includes('sync');
        $('sync-state').textContent = on ? '실행 중' + (age !== null ? ' · ' + Math.round(age) + '초 전 갱신' : '')
            : '멈춤' + (sy.statusAt ? ' · 마지막 갱신 ' + time(sy.statusAt) : '');
        $('card-sync').classList.toggle('stale', !on);
    } else {
        $('lag').textContent = '-';
        $('sync-counts').textContent = '워터마크 없음';
        $('sync-src').textContent = '-';
        $('sync-state').textContent = running.includes('sync') ? '실행 중(스트리밍 시작 기다림)' : '아직 시작 안 함';
    }

    // 전체 적재
    const tables = v.tables || [];
    const loaded = tables.filter(t => t.status === 'LOADED' || t.status === 'EXCLUDED').length;
    $('load-done').textContent = loaded + ' / ' + tables.length;
    $('load-bar').style.width = (tables.length ? Math.round(loaded * 100 / tables.length) : 0) + '%';
    $('load-rows').textContent = fmt(tables.reduce((a, t) => a + (t.rowsLoaded || 0), 0)) + (running.includes('load') ? ' · 적재 중' : '');

    // 검증
    const vf = v.verify;
    const byTable = {};
    if (vf) {
        const total = vf.checks;
        const bad = vf.mismatches;
        $('verify-result').textContent = total === null ? '진행 중' : (total - bad) + ' / ' + total;
        $('card-verify').classList.toggle('bad', !!bad);
        $('card-verify').classList.toggle('good', total !== null && !bad);
        $('verify-at').textContent = 'run ' + vf.runId + ' · ' + time(vf.finishedAt || vf.startedAt);
        (vf.tables || []).forEach(t => { byTable[t.source] = t; });
    } else {
        $('verify-result').textContent = '-';
        $('verify-at').textContent = '아직 안 함';
    }

    // 전환
    const co = v.cutover;
    const ol = $('cutover-steps');
    ol.replaceChildren();
    if (co) {
        $('cutover-time').textContent = co.status === 'RUNNING' ? '진행 중' : seconds(co.elapsedMs);
        $('card-cutover').classList.toggle('bad', co.status === 'FAILED');
        $('card-cutover').classList.toggle('good', co.status === 'DONE');
        (co.steps || []).forEach(st => {
            const li = el('li', null, st.status.toLowerCase());
            li.append(el('span', STEP_KO[st.step] || st.step), el('span', ko(st.status) + ' ' + seconds(st.elapsedMs), 'muted'));
            if (st.detail && st.status === 'FAILED') li.title = st.detail;
            ol.append(li);
        });
        if (co.lastError) ol.append(el('li', co.lastError, 'failed'));
    } else {
        $('cutover-time').textContent = '-';
        ol.append(el('li', '원천 쓰기를 멈춘 뒤 "전환 시작"', 'muted'));
    }

    // 테이블
    const tb = $('tables');
    tb.replaceChildren();
    tables.forEach(t => {
        const tr = document.createElement('tr');
        tr.append(el('td', t.source + ' → ' + t.target + (t.hasPk ? '' : ' (PK 없음)')));
        const st = el('td', ko(t.status), 'st ' + (t.status || '').toLowerCase());
        if (t.lastError) st.title = t.lastError;
        tr.append(st);
        const pct = t.chunks ? Math.round(t.chunksDone * 100 / t.chunks) : (t.status === 'LOADED' ? 100 : 0);
        const cell = el('td');
        const bar = el('div', null, 'bar small');
        const fill = el('span');
        fill.style.width = pct + '%';
        bar.append(fill);
        cell.append(bar, el('small', t.chunks ? t.chunksDone + '/' + t.chunks + ' 구간' : '', 'muted'));
        tr.append(cell);
        tr.append(el('td', fmt(t.rowsLoaded), 'num'), el('td', fmt(t.changesApplied), 'num'), el('td', fmt(t.pending), 'num'));
        const vt = byTable[t.source];
        tr.append(el('td', vt ? (vt.mismatches ? '불일치 ' + vt.mismatches + (vt.rowDiffs ? ' · 차이 행 ' + vt.rowDiffs : '') : '일치') : '-',
            vt ? (vt.mismatches ? 'st failed' : 'st loaded') : ''));
        tb.append(tr);
    });
    if (!tables.length) {
        const tr = el('tr');
        const td = el('td', '테이블 없음', 'muted');
        td.colSpan = 7;
        tr.append(td);
        tb.append(tr);
    }

    // 기록
    const ev = $('events');
    ev.replaceChildren();
    (v.events || []).forEach(e => {
        const li = el('li', null, e.level.toLowerCase());
        li.append(el('span', time(e.at), 'muted'), el('span', e.stage, 'stage'), el('span', e.message));
        ev.append(li);
    });
}

function renderTasks(list) {
    const box = $('tasks');
    box.replaceChildren();
    list.slice().reverse().forEach(t => {
        const d = el('details', null, 'task');
        d.open = t.exitCode === null || t.exitCode === undefined || t.exitCode !== 0;
        const state = t.exitCode === null || t.exitCode === undefined ? (t.stopping ? '멈추는 중' : '실행 중') : '끝(종료 코드 ' + t.exitCode + ')';
        d.append(el('summary', 'kdms ' + t.kind + ' · ' + state + ' · ' + time(t.startedAt)));
        const pre = el('pre', t.lines.join('\n'));
        d.append(pre);
        box.append(d);
        pre.scrollTop = pre.scrollHeight;
    });
    return list.some(t => t.exitCode === null || t.exitCode === undefined);
}

async function getJson(url) {
    const r = await fetch(url, { cache: 'no-store' });
    return r.json();
}

let busy = false;

async function pollJob() {
    try {
        renderJob(await getJson('api/job'));
    } catch (e) {
        $('job-error').hidden = false;
        $('job-error').textContent = '화면 서버에 닿지 않는다: ' + e;
    }
    setTimeout(pollJob, 3000);
}

async function pollTasks() {
    try {
        busy = renderTasks(await getJson('api/tasks'));
    } catch (e) {
        busy = false;
    }
    setTimeout(pollTasks, busy ? 1500 : 5000);
}

async function post(url) {
    const token = document.querySelector('meta[name="kdms-token"]').content;
    const r = await fetch(url, { method: 'POST', headers: { 'X-KDMS-Token': token } });
    if (!r.ok) {
        const body = await r.json().catch(() => ({}));
        alert(body.error || ('요청 실패 ' + r.status));
    }
    busy = true;
}

document.addEventListener('DOMContentLoaded', () => {
    const refresh = $('refresh');
    if (refresh) {
        refresh.addEventListener('click', () => {
            refresh.disabled = true;
            refresh.textContent = '확인 중…';
            window.location.reload();
        });
    }
    const actions = document.querySelector('meta[name="kdms-actions"]').content === 'true';
    document.querySelectorAll('[data-run], [data-stop]').forEach(b => {
        b.disabled = !actions;
        b.addEventListener('click', () => {
            if (b.dataset.confirm && !confirm(b.dataset.confirm)) return;
            post(b.dataset.run ? 'api/tasks/' + b.dataset.run : 'api/tasks/' + b.dataset.stop + '/stop');
        });
    });
    $('actions-off').hidden = actions;
    pollJob();
    pollTasks();
});
