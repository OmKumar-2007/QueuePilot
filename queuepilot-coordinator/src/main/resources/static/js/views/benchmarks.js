import { api } from '../api.js';
import { badge, card, empty, fmtDateTime, fmtDuration, fmtNumber, h, table } from '../ui.js';

let root;

/** Horizontal bars; lower is better for waits, so the best value is highlighted. */
function bars(rows, key, format, lowerIsBetter = true) {
    const max = Math.max(...rows.map(r => r[key]), 1);
    const best = lowerIsBetter ? Math.min(...rows.map(r => r[key])) : Math.max(...rows.map(r => r[key]));
    return h('div', { class: 'bars' }, rows.map(r => h('div', { class: 'bar-row' },
        h('span', {}, r.policy.replace('_', ' ')),
        h('div', { class: 'bar-track' }, h('div', {
            class: 'bar-fill',
            style: { width: `${(100 * r[key]) / max}%`, background: r[key] === best ? 'var(--s-succeeded)' : 'var(--accent)' },
        })),
        h('span', { class: 'num' }, format(r[key])))));
}

function simulated(report) {
    return report.summary.scenarios.map(s => card(`${s.scenario.replaceAll('_', ' ')}`, null,
        h('p', { class: 'muted small' }, s.description),
        h('div', { class: 'grid two' },
            h('div', {}, h('h3', {}, 'Average waiting time'), bars(s.results, 'avgWaitMs', fmtDuration)),
            h('div', {}, h('h3', {}, 'Longest wait (starvation indicator)'), bars(s.results, 'maxWaitMs', fmtDuration))),
        h('div', { style: { marginTop: '14px' } }, table([
            { label: 'Policy', render: r => r.displayName },
            { label: 'Avg wait', num: true, render: r => fmtDuration(r.avgWaitMs) },
            { label: 'Avg turnaround', num: true, render: r => fmtDuration(r.avgTurnaroundMs) },
            { label: 'p95 wait', num: true, render: r => fmtDuration(r.p95WaitMs) },
            { label: 'Max wait', num: true, render: r => fmtDuration(r.maxWaitMs) },
            { label: 'Makespan', num: true, render: r => fmtDuration(r.makespanMs) },
            { label: 'Throughput', num: true, render: r => `${fmtNumber(r.throughputPerSec, 3)}/s` },
            { label: 'Utilization', num: true, render: r => `${fmtNumber(r.utilization * 100, 1)}%` },
        ], s.results))));
}

function measured(report) {
    return [card('Measured groups', null, table([
        { label: 'Workers', num: true, render: g => `${g.workers} (${g.totalSlots} slots)` },
        { label: 'Task', render: g => h('div', {}, g.task, h('div', { class: 'muted small mono' }, JSON.stringify(g.input))) },
        { label: 'Runs', num: true, render: g => `${g.repetitions} × ${g.jobsPerRun}` },
        { label: 'Exec median', num: true, render: g => fmtDuration(g.executionMedianMs.median) },
        { label: 'Throughput (median)', num: true, render: g => `${fmtNumber(g.throughputPerSec.median, 2)}/s` },
        { label: 'p95 throughput', num: true, render: g => `${fmtNumber(g.throughputPerSec.p95, 2)}/s` },
        { label: 'Utilization', num: true, render: g => `${fmtNumber(g.utilization.median * 100, 0)}%` },
        { label: 'Success', num: true, render: g => `${fmtNumber(g.successRate * 100, 0)}%` },
        { label: 'Retries', num: true, render: g => g.retries },
        { label: 'CPU load', num: true, render: g => `${fmtNumber(g.systemCpuLoad.mean * 100, 0)}%` },
    ], report.summary.groups))];
}

function reportView(report) {
    const env = report.environment || {};
    return [
        card(report.name, badge(report.kind),
            h('p', { class: 'muted' }, report.summary.label),
            h('p', { class: 'small muted' }, `Recorded ${fmtDateTime(report.createdAt)} · ${env.os || '?'} · ${env.availableProcessors || '?'} CPUs · `
                + `Java ${env.javaVersion || '?'} · commit ${env.gitCommit || '?'}`),
            h('details', {}, h('summary', { class: 'small' }, 'Configuration'), h('pre', { class: 'json' }, JSON.stringify(report.config, null, 2)))),
        ...(report.kind === 'SIMULATED' ? simulated(report) : measured(report)),
    ];
}

let selected = null;

async function draw() {
    const reports = await api.get('/api/benchmarks');
    if (!reports.length) {
        root.replaceChildren(card('Benchmarks', null,
            empty('No benchmark reports imported yet', 'Benchmarks run outside the platform, from the separate queuepilot-benchmarks module:'),
            h('pre', { class: 'json' },
                'java -jar queuepilot-benchmarks/target/queuepilot-benchmarks-1.0.0.jar simulate --publish\n'
                + 'java -jar queuepilot-benchmarks/target/queuepilot-benchmarks-1.0.0.jar execute \\\n'
                + '     --worker-jar queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar --worker-counts 1,2,4 --publish')));
        return;
    }
    selected = reports.find(r => r.id === selected?.id) || reports[0];
    const picker = h('select', { style: { width: 'auto' }, onchange: e => { selected = reports.find(r => r.id === e.target.value); draw(); } },
        reports.map(r => h('option', { value: r.id, selected: r.id === selected.id }, `${r.kind} · ${r.name} · ${fmtDateTime(r.createdAt)}`)));
    root.replaceChildren(
        h('div', { class: 'page-head' }, h('h1', {}, 'Benchmarks'), h('span', { class: 'spacer' }), picker),
        ...reportView(selected));
}

export default {
    title: 'Benchmarks',
    async render(element) {
        root = element;
        await draw();
    },
};
