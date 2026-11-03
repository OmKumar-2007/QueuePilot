import { api } from '../api.js';
import { act } from '../app.js';
import { card, empty, fmtDuration, h, table } from '../ui.js';
import { openJob } from './jobs.js';

let root;

const POLICIES = {
    FCFS: ['First come, first served', 'Oldest job first. Fair and predictable, but a long job delays everything behind it.'],
    PRIORITY_AGING: ['Priority with aging', 'Highest priority first. Waiting jobs slowly gain priority, so none waits forever.'],
    SJF: ['Shortest job first', 'Smallest estimate first. Lowest average wait, but long jobs can wait a long time.'],
};

function settingsForm(settings) {
    const interval = h('input', { type: 'number', min: 1, max: 3600, value: settings.agingIntervalSeconds });
    const step = h('input', { type: 'number', min: 0, max: 10, value: settings.agingStep });
    const quota = h('input', { type: 'number', min: 1, value: settings.maxActiveJobsPerClient });
    return h('details', { class: 'section' }, h('summary', {}, 'Settings'),
        h('div', { class: 'card' },
            h('div', { class: 'form-grid' },
                h('div', { class: 'field' }, h('label', {}, 'Aging interval (seconds)'), interval),
                h('div', { class: 'field' }, h('label', {}, 'Priority gained per interval'), step),
                h('div', { class: 'field' }, h('label', {}, 'Max active jobs per client'), quota)),
            h('div', { class: 'row' }, h('span', { class: 'muted small' }, 'Queues always take turns (round-robin).'), h('span', { class: 'spacer' }),
                h('button', {
                    class: 'button secondary', onclick: () => act(() => api.put('/api/admin/scheduler', {
                        activePolicy: settings.activePolicy, agingIntervalSeconds: Number(interval.value),
                        agingStep: Number(step.value), maxActiveJobsPerClient: Number(quota.value),
                    }), 'Settings saved').then(draw),
                }, 'Save'))));
}

async function draw() {
    const preview = await api.get('/api/scheduler');
    const settings = preview.settings;
    const aging = settings.activePolicy === 'PRIORITY_AGING';
    root.replaceChildren(
        h('div', { class: 'page-head' }, h('h1', {}, 'Scheduler')),
        h('div', { class: 'policy-cards' }, Object.entries(POLICIES).map(([policy, [title, text]]) =>
            h('button', {
                class: `policy-card${settings.activePolicy === policy ? ' active' : ''}`,
                onclick: () => settings.activePolicy !== policy
                    && act(() => api.put('/api/admin/scheduler', { ...settings, activePolicy: policy }), `Policy: ${title}`).then(draw),
            }, h('strong', {}, title), h('span', { class: 'muted small' }, text)))),
        h('div', { class: 'section' }, card(`Up next (${preview.ready.length})`, null, preview.ready.length ? table([
            { label: '#', num: true, render: e => e.rank },
            { label: 'Job', render: e => e.name },
            { label: 'Queue', render: e => e.queue },
            { label: 'Priority', num: true, render: e => (aging ? `${e.priority} → ${e.effectivePriority}` : e.priority) },
            { label: 'Estimate', num: true, render: e => fmtDuration(e.estimatedMs) },
        ], preview.ready, e => openJob(e.jobId)) : empty('Nothing waiting', 'Ready jobs appear here in dispatch order.'))),
        preview.notReady.length ? h('div', { class: 'section' }, card(`Not ready yet (${preview.notReady.length})`, null, table([
            { label: 'Job', render: e => e.name },
            { label: 'Why', render: e => e.reason },
        ], preview.notReady, e => openJob(e.jobId)))) : null,
        settingsForm(settings));
}

export default {
    title: 'Scheduler',
    async render(element) {
        root = element;
        await draw();
    },
    async refresh() {
        // Do not wipe settings while they are being edited.
        if (!root.querySelector('details[open]')) {
            await draw();
        }
    },
};
