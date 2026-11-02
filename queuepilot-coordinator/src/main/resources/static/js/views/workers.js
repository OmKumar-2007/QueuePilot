import { admin, api } from '../api.js';
import { adminAction } from '../app.js';
import { badge, card, empty, h, progress, relative, shortId } from '../ui.js';
import { openJob } from './jobs.js';

let root;
let showOffline = false;

function actions(worker) {
    if (!admin.isLoggedIn()) return null;
    const post = (path, message) => adminAction(() => api.adminPost(`/api/admin/workers/${worker.id}/${path}`), message).then(draw);
    const buttons = [];
    if (worker.status === 'ONLINE') buttons.push(h('button', { class: 'button ghost small', onclick: () => post('drain', 'Draining: no new work') }, 'Drain'));
    if (worker.status === 'DRAINING') buttons.push(h('button', { class: 'button ghost small', onclick: () => post('resume', 'Resumed') }, 'Resume'));
    if (worker.status === 'ONLINE' || worker.status === 'DRAINING') buttons.push(h('button', { class: 'button ghost small', onclick: () => post('disable', 'Disabled') }, 'Disable'));
    if (worker.status === 'DISABLED') buttons.push(h('button', { class: 'button ghost small', onclick: () => post('enable', 'Enabled') }, 'Enable'));
    if (worker.status !== 'OFFLINE') {
        buttons.push(h('button', {
            class: 'button danger small', title: 'The worker process exits abruptly on its next heartbeat',
            onclick: () => confirm(`Kill worker ${worker.name}? Its running jobs will be recovered by lease expiry.`)
                && post('simulate/kill', 'Kill requested; watch the recovery in Jobs'),
        }, 'Simulate crash'));
        buttons.push(h('button', {
            class: 'button danger small', title: 'Heartbeats stop but the worker keeps computing; its late results will be rejected',
            onclick: () => post('simulate/freeze', 'Freeze requested'),
        }, 'Freeze heartbeats'));
    }
    return h('div', { class: 'row', style: { marginTop: '12px' } }, buttons);
}

function workerCard(w) {
    return h('section', { class: 'card worker-card' },
        h('div', { class: 'card-head' },
            h('div', {}, h('h2', {}, w.name), h('div', { class: 'muted small mono' }, w.id)),
            badge(w.status)),
        h('div', { class: 'row small' }, h('span', {}, `${w.runningAttempts} / ${w.concurrencyLimit} slots busy`)),
        progress(100 * w.runningAttempts / w.concurrencyLimit),
        h('dl', { class: 'meta' },
            h('dt', {}, 'Last heartbeat'), h('dd', {}, relative(w.lastHeartbeatAt)),
            h('dt', {}, 'Attempts'), h('dd', {}, `${w.succeededAttempts} succeeded · ${w.failedAttempts} failed/lost`),
            h('dt', {}, 'Running'), h('dd', {}, w.currentJobIds.length
                ? w.currentJobIds.map(id => h('a', { href: '#', class: 'mono', onclick: e => { e.preventDefault(); openJob(id); } }, shortId(id), ' '))
                : 'idle'),
            h('dt', {}, 'Capabilities'), h('dd', {}, h('div', { class: 'chips' }, w.capabilities.map(c => h('span', { class: 'chip' }, c)))),
            h('dt', {}, 'Tasks'), h('dd', {}, h('div', { class: 'chips' }, w.supportedTasks.map(t => h('span', { class: 'chip mono' }, t))))),
        w.pendingCommand ? h('div', { class: 'small muted' }, `Pending command: ${w.pendingCommand}`) : null,
        actions(w));
}

async function draw() {
    const all = await api.get('/api/workers');
    const visible = all.filter(w => showOffline || w.status !== 'OFFLINE');
    const toggle = h('label', { class: 'row small', style: { fontWeight: 400 } },
        h('input', { type: 'checkbox', style: { width: 'auto' }, checked: showOffline, onchange: e => { showOffline = e.target.checked; draw(); } }),
        `Show offline (${all.length - all.filter(w => w.status !== 'OFFLINE').length})`);
    root.replaceChildren(
        h('div', { class: 'row', style: { marginBottom: '12px' } }, h('span', { class: 'muted' }, `${visible.length} worker process(es)`), h('span', { class: 'spacer' }), toggle),
        visible.length ? h('div', { class: 'grid two' }, visible.map(workerCard))
            : card(null, null, empty('No workers online', 'Start one from the project folder:'),
                h('pre', { class: 'json' }, 'java -jar queuepilot-worker/target/queuepilot-worker-1.0.0.jar --name worker-1 --slots 2')));
}

export default {
    title: 'Workers',
    async render(element) {
        root = element;
        await draw();
    },
    refresh: () => draw(),
};
