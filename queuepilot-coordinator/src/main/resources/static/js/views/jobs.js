import { api, errorText } from '../api.js';
import {
    badge, between, card, drawerIsOpen, empty, fmtDuration, fmtTime, h, json, openDrawer, progress, shortId, table, toast,
} from '../ui.js';

const FILTERS = {
    All: () => true,
    Active: j => ['BLOCKED', 'QUEUED', 'RUNNING'].includes(j.state),
    Done: j => j.state === 'SUCCEEDED',
    Failed: j => ['FAILED', 'SKIPPED', 'CANCELLED'].includes(j.state),
};
let root;
let filter = 'All';
let openJobId = null;
let workerNames = new Map();

async function loadWorkerNames() {
    workerNames = new Map((await api.get('/api/workers')).map(w => [w.id, w.name]));
}

const columns = [
    { label: 'Job', render: j => h('div', {}, j.label, h('div', { class: 'muted mono' }, shortId(j.id))) },
    { label: 'Task', render: j => j.taskType },
    { label: 'Priority', num: true, render: j => j.priority },
    { label: 'Status', render: j => badge(j.state) },
    { label: 'Progress', render: j => h('div', { style: { minWidth: '110px' } }, progress(j.progressPercent, j.state === 'SUCCEEDED')) },
    { label: 'Worker', render: j => workerNames.get(j.currentWorkerId) || '—' },
    { label: 'Time', num: true, render: j => fmtDuration(between(j.startedAt, j.finishedAt)) },
];

// The header (with the filter tabs) is built once; refreshes only replace the table.
let tabButtons;
let tableArea;

function build() {
    tabButtons = Object.keys(FILTERS).map(name => h('button', { onclick: () => { filter = name; draw(); } }, name));
    tableArea = h('section', { class: 'card table-card' });
    root.replaceChildren(
        h('div', { class: 'page-head' }, h('h1', {}, 'Jobs'), h('span', { class: 'spacer' }), h('div', { class: 'tabs' }, tabButtons),
            h('a', { class: 'button', href: '#/submit' }, 'Submit job')),
        tableArea);
}

async function draw() {
    const [jobs] = await Promise.all([api.get('/api/jobs?limit=300'), loadWorkerNames()]);
    const shown = jobs.filter(FILTERS[filter]);
    tabButtons.forEach(button => {
        const name = Object.keys(FILTERS)[tabButtons.indexOf(button)];
        button.className = name === filter ? 'active' : '';
        button.textContent = `${name} ${jobs.filter(FILTERS[name]).length}`;
    });
    tableArea.replaceChildren(shown.length
        ? table(columns, shown, job => openJob(job.id))
        : empty('No jobs here', 'Submit one, or run the demo from the Live page.'));
}

async function jobDetail(id) {
    const [{ job, attempts, events, artifacts }] = await Promise.all([api.get(`/api/jobs/${id}/history`), loadWorkerNames()]);
    const cancellable = ['BLOCKED', 'QUEUED'].includes(job.state);
    const cancel = h('button', {
        class: 'button danger',
        onclick: async () => {
            try {
                await api.post(`/api/jobs/${id}/cancel`);
                toast('Job cancelled');
                refreshDrawer();
            } catch (error) {
                toast(errorText(error), true);
            }
        },
    }, 'Cancel');

    return h('div', { class: 'stack' },
        h('div', { class: 'row' }, h('h1', {}, job.label)),
        h('div', { class: 'row' }, badge(job.state), h('span', { class: 'muted small' }, `${job.taskType} · priority ${job.priority} · queue ${job.queue}`),
            h('span', { class: 'spacer' }), cancellable ? cancel : null),
        job.state === 'RUNNING' || job.state === 'SUCCEEDED' ? h('div', {},
            progress(job.progressPercent, job.state === 'SUCCEEDED'),
            h('div', { class: 'muted small', style: { marginTop: '4px' } }, `${job.progressPercent}% · ${job.progressMessage || ''}`)) : null,
        job.error && job.state !== 'SUCCEEDED' ? card(null, null, h('div', { class: 'muted' }, job.error)) : null,
        card(null, null, h('dl', { class: 'facts' },
            h('dt', {}, 'Submitted'), h('dd', {}, fmtTime(job.submittedAt)),
            h('dt', {}, 'Started'), h('dd', {}, fmtTime(job.startedAt)),
            h('dt', {}, 'Finished'), h('dd', {}, fmtTime(job.finishedAt)),
            h('dt', {}, 'Run time'), h('dd', {}, fmtDuration(between(job.startedAt, job.finishedAt))),
            h('dt', {}, 'Attempts'), h('dd', {}, `${job.attemptsUsed}`))),
        job.output ? card('Result', null, json(job.output)) : null,
        artifacts.length ? card('Files', null, h('ul', { class: 'list' }, artifacts.map(a => h('li', {},
            h('a', { href: `/api/artifacts/${a.id}/content`, class: 'grow' }, a.originalName))))) : null,
        attempts.length ? card('Attempts', null, h('ul', { class: 'list' }, attempts.map(a => h('li', {},
            h('span', { class: 'rank' }, `#${a.attemptNumber}`),
            h('span', { class: 'grow' }, workerNames.get(a.workerId) || a.workerId, a.error ? h('span', { class: 'muted' }, ` — ${a.error.split(':')[0]}`) : null),
            badge(a.state))))) : null,
        h('details', {}, h('summary', {}, 'Input'), json(job.input)),
        h('details', {}, h('summary', {}, `History (${events.length})`), h('ul', { class: 'list' }, events.map(e =>
            h('li', {}, h('span', { class: 'when' }, fmtTime(e.occurredAt)), h('span', {}, e.message))))));
}

async function refreshDrawer() {
    if (!openJobId || !drawerIsOpen()) {
        openJobId = null;
        return;
    }
    const fresh = await jobDetail(openJobId);
    const content = document.getElementById('drawer-content');
    const panel = content.parentElement;

    // Read the page state only now, so anything the user did during the fetch is kept.
    const openDetails = [...content.querySelectorAll('details')].map(d => d.open);
    fresh.querySelectorAll('details').forEach((d, i) => (d.open = openDetails[i] || false));
    if (content.firstElementChild?.isEqualNode(fresh)) return; // nothing changed: leave scrolling and text selection alone

    const scroll = panel.scrollTop;
    const blocks = [...content.querySelectorAll('pre.json')].map(p => [p.scrollTop, p.scrollLeft]);
    content.replaceChildren(fresh);
    content.querySelectorAll('pre.json').forEach((p, i) => ([p.scrollTop, p.scrollLeft] = blocks[i] || [0, 0]));
    panel.scrollTop = scroll;
}

/** Opens the job detail panel; usable from any view. */
export async function openJob(id) {
    openJobId = id;
    try {
        openDrawer(await jobDetail(id));
    } catch (error) {
        toast(errorText(error), true);
    }
}

setInterval(() => refreshDrawer().catch(() => {}), 1500);

export default {
    title: 'Jobs',
    async render(element, [id]) {
        root = element;
        build();
        await draw();
        if (id) openJob(id);
    },
    refresh: () => draw(),
};
