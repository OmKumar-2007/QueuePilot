import { api } from '../api.js';
import { badge, card, empty, fmtDuration, fmtNumber, fmtTime, h, STATE_COLORS } from '../ui.js';
import { openJob } from './jobs.js';

let root;

function kpi(label, value, sub) {
    return h('div', { class: 'card kpi' }, h('div', { class: 'label' }, label), h('div', { class: 'value' }, value),
        sub ? h('div', { class: 'sub' }, sub) : null);
}

function stateBar(byState, total) {
    const states = Object.keys(STATE_COLORS);
    return h('div', {},
        h('div', { class: 'stacked-bar' }, states.filter(s => byState[s] > 0).map(s =>
            h('span', { style: { width: `${(100 * byState[s]) / total}%`, background: STATE_COLORS[s] }, title: `${s}: ${byState[s]}` }))),
        h('div', { class: 'legend' }, states.map(s => h('span', {}, h('i', { style: { background: STATE_COLORS[s] } }), `${s.toLowerCase()} ${byState[s] || 0}`))));
}

async function draw() {
    const o = await api.get('/api/metrics/overview');
    const s = o.jobsByState;
    const recent = o.recentSucceeded;
    root.replaceChildren(
        h('div', { class: 'grid kpis' },
            kpi('Total jobs', fmtNumber(o.totalJobs)),
            kpi('Queued', fmtNumber(s.QUEUED), `${s.SCHEDULED} scheduled · ${s.BLOCKED} blocked`),
            kpi('Running', fmtNumber(s.RUNNING), `${o.runningAttempts} of ${o.totalSlots} slots`),
            kpi('Succeeded', fmtNumber(s.SUCCEEDED)),
            kpi('Failed', fmtNumber(s.FAILED), `${s.SKIPPED} skipped · ${s.CANCELLED} cancelled`),
            kpi('Active workers', `${o.onlineWorkers}/${o.totalWorkers}`, `${o.totalSlots} slots`),
            kpi('Throughput', `${fmtNumber(o.throughputPerMinute, 1)}/min`, 'succeeded, last 5 min'),
            kpi('Slot utilization', `${fmtNumber(o.slotUtilization * 100)}%`, 'running ÷ online slots')),
        h('div', { class: 'grid two' },
            card('Job states', null, o.totalJobs ? stateBar(s, o.totalJobs) : empty('No jobs yet', 'Submit one from “Submit job”.'),
                recent.jobCount ? h('p', { class: 'muted small' },
                    `Last 5 minutes: ${recent.jobCount} jobs succeeded · average wait ${fmtDuration(recent.avgWaitMs)} · `
                    + `average turnaround ${fmtDuration(recent.avgTurnaroundMs)} · longest wait ${fmtDuration(recent.maxWaitMs)}`) : null),
            card('Deadlines', null, o.deadlineAlerts.length
                ? h('ul', { class: 'timeline' }, o.deadlineAlerts.map(job => h('li', { class: 'clickable', onclick: () => openJob(job.id) },
                    badge(job.deadlineStatus), h('span', {}, `${job.name} — due ${fmtTime(job.deadline)} (${job.state.toLowerCase()})`))))
                : empty('No deadline alerts', 'Jobs within 5 minutes of their deadline appear here.'))),
        card('Recent activity', h('a', { href: '#/jobs' }, 'All jobs →'),
            o.recentEvents.length ? h('ul', { class: 'timeline' }, o.recentEvents.map(e => h('li', {},
                h('time', {}, fmtTime(e.occurredAt)),
                h('div', {}, h('div', { class: 'type' }, e.type.replaceAll('_', ' ')), e.message))))
                : empty('Nothing has happened yet', 'Start a worker and submit a job.')));
}

export default {
    title: 'Overview',
    async render(element) {
        root = element;
        await draw();
    },
    refresh: () => draw(),
};
