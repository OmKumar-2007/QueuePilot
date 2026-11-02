import { admin, api } from '../api.js';
import { adminAction } from '../app.js';
import { badge, card, empty, fmtDuration, fmtTime, h, table } from '../ui.js';
import { openJob } from './jobs.js';

let root;

const DESCRIPTIONS = {
    FCFS: 'Earliest submission first. Fair and predictable, but one long job delays everything behind it (convoy effect).',
    PRIORITY_AGING: 'Highest priority first; every aging interval a waiting job gains priority, so low-priority work cannot starve forever.',
    SJF: 'Smallest estimated duration first. Minimises average waiting time, but long jobs can wait a long time under load.',
};

function settingsCard(settings) {
    const interval = h('input', { type: 'number', min: 1, max: 3600, value: settings.agingIntervalSeconds });
    const step = h('input', { type: 'number', min: 0, max: 10, value: settings.agingStep });
    const quota = h('input', { type: 'number', min: 1, value: settings.maxActiveJobsPerClient });
    const save = policy => adminAction(() => api.adminPut('/api/admin/scheduler', {
        activePolicy: policy, agingIntervalSeconds: Number(interval.value), agingStep: Number(step.value),
        maxActiveJobsPerClient: Number(quota.value),
    }), `Scheduler updated: ${policy}`).then(draw);

    return card('Scheduling policy', admin.isLoggedIn() ? null : h('span', { class: 'muted small' }, 'Log in as administrator to change'),
        h('div', { class: 'policy-options' }, Object.entries(DESCRIPTIONS).map(([policy, text]) =>
            h('button', {
                class: `policy-option${settings.activePolicy === policy ? ' active' : ''}`,
                disabled: !admin.isLoggedIn() && settings.activePolicy !== policy,
                onclick: () => settings.activePolicy !== policy && save(policy),
            }, h('strong', {}, policy.replace('_', ' ')), h('span', { class: 'muted small' }, text)))),
        h('div', { class: 'form-grid', style: { marginTop: '16px' } },
            h('div', { class: 'field' }, h('label', {}, 'Aging interval (s)'), interval),
            h('div', { class: 'field' }, h('label', {}, 'Aging step (+priority per interval)'), step),
            h('div', { class: 'field' }, h('label', {}, 'Max active jobs per client'), quota, h('small', {}, 'Requests above the limit get HTTP 429.'))),
        h('div', { class: 'row' }, h('span', { class: 'muted small' }, 'Queues are always served round-robin, so one busy queue cannot monopolise the workers.'),
            h('span', { class: 'spacer' }),
            h('button', { class: 'button ghost', disabled: !admin.isLoggedIn(), onclick: () => save(settings.activePolicy) }, 'Save settings')));
}

async function draw() {
    const preview = await api.get('/api/scheduler');
    const aging = preview.settings.activePolicy === 'PRIORITY_AGING';
    root.replaceChildren(
        settingsCard(preview.settings),
        card(`Dispatch order (${preview.ready.length} ready)`, h('span', { class: 'muted small' }, `Last queue served: ${preview.lastServedQueue || '—'}`),
            h('p', { class: 'muted small' }, 'The order the next claims would follow, before matching worker capabilities. A worker that cannot run the first job gets the next one it can run.'),
            preview.ready.length ? table([
                { label: 'Rank', num: true, render: e => e.rank },
                { label: 'Job', render: e => e.name },
                { label: 'Task', render: e => h('span', { class: 'mono' }, e.task) },
                { label: 'Queue', render: e => e.queue },
                { label: 'Priority', num: true, render: e => (aging ? `${e.priority} → ${e.effectivePriority}` : e.priority) },
                { label: 'Estimate', num: true, render: e => fmtDuration(e.estimatedMs) },
                { label: 'Submitted', render: e => fmtTime(e.submittedAt) },
            ], preview.ready, e => openJob(e.jobId)) : empty('Nothing waiting', 'All ready jobs have been dispatched.')),
        card(`Not yet runnable (${preview.notReady.length})`, null, preview.notReady.length ? table([
            { label: 'Job', render: e => e.name },
            { label: 'Task', render: e => h('span', { class: 'mono' }, e.task) },
            { label: 'Reason', render: e => e.reason },
        ], preview.notReady, e => openJob(e.jobId)) : empty('None', 'Scheduled, blocked and retry-delayed jobs appear here.')));
}

export default {
    title: 'Scheduler',
    async render(element) {
        root = element;
        await draw();
    },
    async refresh() {
        // Avoid wiping half-typed settings: only redraw when no input has focus.
        if (!root.contains(document.activeElement) || document.activeElement.tagName === 'BUTTON') {
            await draw();
        }
    },
};
