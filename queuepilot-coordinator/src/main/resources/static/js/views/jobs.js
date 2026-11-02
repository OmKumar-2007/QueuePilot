import { api, ApiError } from '../api.js';
import {
    badge, between, card, drawerIsOpen, empty, fmtDateTime, fmtDuration, fmtTime, h, json, openDrawer, progress,
    shortId, table, toast,
} from '../ui.js';

const STATES = ['', 'SCHEDULED', 'BLOCKED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'];
let root;
let filter = '';
let openJobId = null;

const columns = [
    { label: 'Job', render: j => h('div', {}, h('div', {}, j.name), h('div', { class: 'muted small mono' }, shortId(j.id))) },
    { label: 'Task', render: j => h('span', { class: 'mono' }, `${j.taskType}@${j.taskVersion}`) },
    { label: 'Queue', render: j => j.queue },
    { label: 'State', render: j => badge(j.state) },
    { label: 'Prio', num: true, render: j => j.priority },
    { label: 'Progress', render: j => h('div', { title: j.progressMessage }, progress(j.progressPercent, j.state === 'SUCCEEDED')) },
    { label: 'Attempts', num: true, render: j => `${j.attemptsUsed}/${j.maxRetries + 1}` },
    { label: 'Worker', render: j => j.currentWorkerId || '—' },
    { label: 'Submitted', render: j => fmtTime(j.submittedAt) },
    { label: 'Runtime', num: true, render: j => fmtDuration(between(j.startedAt, j.finishedAt)) },
    { label: 'Deadline', render: j => (j.deadlineStatus === 'NONE' ? '—' : badge(j.deadlineStatus)) },
];

async function draw() {
    const query = filter ? `?state=${filter}&limit=300` : '?limit=300';
    const jobs = await api.get(`/api/jobs${query}`);
    const select = h('select', { style: { width: 'auto' }, onchange: e => { filter = e.target.value; draw(); } },
        STATES.map(s => h('option', { value: s, selected: s === filter }, s || 'All states')));
    root.replaceChildren(card(`Jobs (${jobs.length})`, h('div', { class: 'row' }, select, h('a', { class: 'button small', href: '#/submit' }, 'Submit job')),
        jobs.length ? table(columns, jobs, job => openJob(job.id)) : empty('No jobs', filter ? 'No job is in this state.' : 'Submit a job to get started.')));
}

async function jobDetail(id) {
    const { job, attempts, events, artifacts } = await api.get(`/api/jobs/${id}/history`);
    const cancellable = ['SCHEDULED', 'BLOCKED', 'QUEUED'].includes(job.state);
    const cancel = h('button', {
        class: 'button danger small',
        onclick: async () => {
            try {
                await api.post(`/api/jobs/${id}/cancel`);
                toast('Job cancelled');
                refreshDrawer();
            } catch (error) {
                toast(error instanceof ApiError ? error.details() : String(error), true);
            }
        },
    }, 'Cancel job');

    const facts = [
        ['Job id', h('span', { class: 'mono' }, job.id)],
        ['Task', h('span', { class: 'mono' }, `${job.taskType}@${job.taskVersion}`)],
        ['Queue / client', `${job.queue} / ${job.clientId}`],
        ['Priority', job.priority],
        ['Estimated', fmtDuration(job.estimatedMs)],
        ['Submitted', fmtDateTime(job.submittedAt)],
        ['Start at', fmtDateTime(job.startAt)],
        ['Ready at', fmtDateTime(job.readyAt)],
        ['Started', fmtDateTime(job.startedAt)],
        ['Finished', fmtDateTime(job.finishedAt)],
        ['Deadline', job.deadline ? h('span', {}, fmtDateTime(job.deadline), ' ', badge(job.deadlineStatus)) : '—'],
        ['Depends on', job.dependsOn.length ? job.dependsOn.map(d => h('a', { class: 'mono', href: '#', onclick: e => { e.preventDefault(); openJob(d); } }, shortId(d), ' ')) : '—'],
    ];

    return h('div', { class: 'stack' },
        h('div', { class: 'row' }, h('h2', { style: { margin: 0 } }, job.name), badge(job.state), h('span', { class: 'spacer' }), cancellable ? cancel : null),
        h('div', {}, progress(job.progressPercent, job.state === 'SUCCEEDED'), h('div', { class: 'muted small' }, `${job.progressPercent}% · ${job.progressMessage || ''}`)),
        job.errorCode ? h('div', { class: 'card', style: { borderColor: 'var(--s-failed)' } }, h('strong', {}, job.errorCode), ' — ', job.errorMessage) : null,
        card('Details', null, h('div', { class: 'table-wrap' }, h('table', {}, h('tbody', {}, facts.map(([k, v]) => h('tr', {}, h('th', {}, k), h('td', {}, v))))))),
        card('Input', null, json(job.input)),
        job.output ? card('Output', null, json(job.output)) : null,
        artifacts.length ? card('Files', null, h('ul', {}, artifacts.map(a => h('li', {},
            h('a', { href: `/api/artifacts/${a.id}/content` }, a.originalName), ` · ${a.role.toLowerCase()} · ${a.sizeBytes.toLocaleString()} bytes`)))) : null,
        card(`Attempts (${attempts.length})`, null, attempts.length ? table([
            { label: '#', render: a => a.attemptNumber },
            { label: 'Worker', render: a => a.workerId },
            { label: 'State', render: a => badge(a.state) },
            { label: 'Started', render: a => fmtTime(a.startedAt) },
            { label: 'Duration', num: true, render: a => fmtDuration(between(a.startedAt, a.finishedAt)) },
            { label: 'Error', render: a => (a.errorCode ? `${a.errorCode}: ${a.errorMessage || ''}` : '') },
        ], attempts) : empty('Not started yet', 'An attempt is created when a worker claims the job.')),
        card('History', null, h('ul', { class: 'timeline' }, events.map(e => h('li', {}, h('time', {}, fmtTime(e.occurredAt)),
            h('div', {}, h('div', { class: 'type' }, e.type.replaceAll('_', ' ')), e.message))))));
}

async function refreshDrawer() {
    if (!openJobId || !drawerIsOpen()) {
        openJobId = null;
        return;
    }
    const content = document.getElementById('drawer-content');
    const scroll = content.parentElement.scrollTop;
    content.replaceChildren(await jobDetail(openJobId));
    content.parentElement.scrollTop = scroll;
}

/** Opens the job detail drawer; usable from any view. */
export async function openJob(id) {
    openJobId = id;
    try {
        openDrawer(await jobDetail(id));
    } catch (error) {
        toast(error instanceof ApiError ? error.details() : String(error), true);
    }
}

setInterval(() => refreshDrawer().catch(() => {}), 2000);

export default {
    title: 'Jobs',
    async render(element, [id]) {
        root = element;
        await draw();
        if (id) openJob(id);
    },
    refresh: () => draw(),
};
