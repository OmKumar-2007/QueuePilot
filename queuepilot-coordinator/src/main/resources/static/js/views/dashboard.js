// The live view: what each worker is doing right now, what runs next, and what just happened.
import { api } from '../api.js';
import { act } from '../app.js';
import { badge, card, empty, fmtTime, h, progress, relative, toast } from '../ui.js';
import { openJob } from './jobs.js';

let busy = false;

const POLICIES = [['FCFS', 'FCFS'], ['PRIORITY_AGING', 'Priority'], ['SJF', 'SJF']];

// Real tasks of different sizes and priorities in two queues. Workers started by the demo script
// use demo pacing, so each takes roughly 15-30 s and the queue is visible.
const DEMO_JOBS = [
    ['Estimate pi (large)', 'monte-carlo-pi', { samples: 300_000_000 }, 2, 'analytics', 30_000],
    ['Sort 4M numbers', 'sorting', { count: 4_000_000 }, 6, 'batch', 9_000],
    ['Estimate pi (urgent)', 'monte-carlo-pi', { samples: 40_000_000 }, 10, 'analytics', 6_000],
    ['Word count', 'text-analysis', { text: 'QueuePilot schedules jobs. Workers execute jobs! Do workers fail? Recovery retries jobs.', topN: 5 }, 4, 'batch', 3_000],
    ['Heap sort 2M', 'sorting', { count: 2_000_000, algorithm: 'HEAP' }, 3, 'batch', 12_000],
    ['Estimate pi (medium)', 'monte-carlo-pi', { samples: 120_000_000 }, 5, 'analytics', 15_000],
    ['Sort 1M numbers', 'sorting', { count: 1_000_000 }, 7, 'batch', 4_000],
    ['Estimate pi (small)', 'monte-carlo-pi', { samples: 20_000_000 }, 1, 'analytics', 4_000],
    ['Merge sort 3M', 'sorting', { count: 3_000_000, algorithm: 'MERGE' }, 5, 'batch', 8_000],
    ['Estimate pi (tiny)', 'monte-carlo-pi', { samples: 10_000_000 }, 8, 'analytics', 2_000],
    ['Sort 6M numbers', 'sorting', { count: 6_000_000 }, 2, 'batch', 20_000],
    ['Estimate pi (batch)', 'monte-carlo-pi', { samples: 80_000_000 }, 6, 'analytics', 10_000],
];

async function runDemo() {
    for (const [, taskType, input, priority, queue, estimatedMs] of DEMO_JOBS) {
        await api.post('/api/jobs', { taskType, input, priority, queue, estimatedMs });
    }
}

const workerAction = (worker, path, message) =>
    act(() => api.post(`/api/admin/workers/${worker.id}/${path}`), message).then(update).catch(() => {});

function slotView(job) {
    if (!job) return h('div', { class: 'slot idle' }, 'Idle');
    return h('div', { class: 'slot', onclick: () => openJob(job.id), style: { cursor: 'pointer' } },
        h('div', { class: 'job-line' }, h('strong', {}, job.label), h('span', { class: 'muted' }, `${job.progressPercent}%`)),
        progress(job.progressPercent));
}

function controlsFor(worker) {
    const paused = worker.status === 'PAUSED';
    return [
        paused
            ? h('button', { class: 'link-button', onclick: () => workerAction(worker, 'resume', `${worker.name} resumed`) }, 'Resume')
            : h('button', { class: 'link-button', onclick: () => workerAction(worker, 'pause', `${worker.name} paused`), title: 'Hold running jobs and take no new ones' }, 'Pause'),
        h('button', { class: 'link-button', onclick: () => workerAction(worker, 'interrupt', `${worker.name}: jobs interrupted, they will be retried`), title: 'Stop the running jobs now; they go back to the queue' }, 'Interrupt'),
        h('button', { class: 'link-button danger', onclick: () => workerAction(worker, 'simulate/freeze', `${worker.name} frozen: no heartbeats for 20 s`), title: 'Stop heartbeats for 20 s: its jobs move to other workers' }, 'Freeze'),
        h('button', {
            class: 'link-button danger', title: 'The worker process exits immediately',
            onclick: () => confirm(`Crash ${worker.name}? Its process will exit; its jobs are recovered by other workers.`)
                && workerAction(worker, 'simulate/kill', `${worker.name} will crash`),
        }, 'Crash'),
    ];
}

// Worker cards are kept between refreshes: only their contents change, and the buttons are only
// rebuilt when the worker's status changes, so clicks are never lost to a re-render.
const cards = new Map();

function updateCard(worker, runningJobs) {
    let entry = cards.get(worker.id);
    if (!entry) {
        const body = h('div', { class: 'stack' });
        const actions = h('div', { class: 'worker-actions' });
        entry = { element: h('section', { class: 'card worker' }, body, actions), body, actions, status: null };
        cards.set(worker.id, entry);
    }
    const jobs = worker.currentJobIds.map(id => runningJobs.get(id)).filter(Boolean);
    const frozen = worker.pendingCommand?.includes('FREEZE');
    entry.element.classList.toggle('paused', worker.status === 'PAUSED');
    entry.body.replaceChildren(
        h('div', { class: 'worker-head' }, h('h2', {}, worker.name), h('span', { class: 'spacer' }), badge(frozen ? 'FROZEN' : worker.status)),
        ...Array.from({ length: worker.concurrencyLimit }, (_, i) => slotView(jobs[i])),
        h('div', { class: 'row small muted' },
            h('span', {}, `${worker.succeededAttempts} done`), h('span', {}, '·'),
            h('span', {}, `heartbeat ${relative(worker.lastHeartbeatAt)}`)));
    if (entry.status !== worker.status) {
        entry.actions.replaceChildren(...controlsFor(worker));
        entry.status = worker.status;
    }
    return entry.element;
}

function statItems(o) {
    const s = o.jobsByState;
    const item = (label, value, color) => h('div', { class: 'stat' },
        h('div', { class: 'value' }, value), h('div', { class: 'label' }, h('i', { style: { background: color } }), label));
    return [
        item('Waiting', s.QUEUED + s.BLOCKED, 'var(--queued)'),
        item('Running', s.RUNNING, 'var(--running)'),
        item('Succeeded', s.SUCCEEDED, 'var(--ok)'),
        item('Failed', s.FAILED + s.SKIPPED, 'var(--bad)'),
    ];
}

// Regions refreshed every second; the header controls are created once in build().
let parts;

function sameChildren(container, nodes) {
    return container.children.length === nodes.length && nodes.every((node, i) => container.children[i] === node);
}

async function update() {
    const [overview, workers, running, preview] = await Promise.all([
        api.get('/api/metrics/overview'), api.get('/api/workers'),
        api.get('/api/jobs?state=RUNNING&limit=200'), api.get('/api/scheduler')]);
    const runningJobs = new Map(running.map(j => [j.id, j]));
    const live = workers.filter(w => w.status !== 'OFFLINE');
    const offline = workers.length - live.length;

    parts.settings = preview.settings;
    parts.policyButtons.forEach(b => b.classList.toggle('active', b.dataset.policy === preview.settings.activePolicy));
    parts.stats.replaceChildren(...statItems(overview));
    parts.workerSummary.textContent = `${live.length} running · ${overview.runningAttempts}/${overview.totalSlots} slots busy`
        + (offline ? ` · ${offline} offline` : '');

    const cardNodes = live.map(w => updateCard(w, runningJobs));
    if (!live.length) {
        parts.workers.replaceChildren(card(null, null, empty('No workers', 'Click “+ Add worker”, or run scripts/start-demo.ps1.')));
    } else if (!sameChildren(parts.workers, cardNodes)) {
        parts.workers.replaceChildren(...cardNodes);
    }

    parts.upNext.replaceChildren(preview.ready.length
        ? h('ul', { class: 'list' }, preview.ready.slice(0, 6).map(e => h('li', { onclick: () => openJob(e.jobId), style: { cursor: 'pointer' } },
            h('span', { class: 'rank' }, e.rank), h('span', { class: 'grow' }, e.name),
            h('span', { class: 'muted small' }, `prio ${e.effectivePriority ?? e.priority}`))))
        : empty('Queue is empty', 'Click “Run demo jobs” to fill it.'),
    preview.ready.length > 6 ? h('div', { class: 'muted small' }, `+ ${preview.ready.length - 6} more`) : '');
    parts.activity.replaceChildren(overview.recentEvents.length
        ? h('ul', { class: 'list' }, overview.recentEvents.slice(0, 7).map(e => h('li', {},
            h('span', { class: 'when' }, fmtTime(e.occurredAt)), h('span', { class: 'grow', title: e.message }, e.message))))
        : empty('Nothing yet', ''));
}

function build(root) {
    parts = {
        stats: h('div', { class: 'stats' }),
        workers: h('div', { class: 'workers' }),
        workerSummary: h('span', { class: 'muted small' }),
        upNext: h('div'),
        activity: h('div'),
        settings: null,
    };
    parts.policyButtons = POLICIES.map(([value, label]) => h('button', {
        'data-policy': value,
        onclick: () => parts.settings && parts.settings.activePolicy !== value
            && act(() => api.put('/api/admin/scheduler', { ...parts.settings, activePolicy: value }), `Policy: ${label}`)
                .then(update).catch(() => {}),
    }, label));
    const demoButton = h('button', {
        class: 'button',
        onclick: async () => {
            if (busy) return;
            busy = true;
            demoButton.disabled = true;
            await act(runDemo, `${DEMO_JOBS.length} demo jobs submitted`).catch(() => {});
            busy = false;
            demoButton.disabled = false;
            update();
        },
    }, 'Run demo jobs');
    const addWorker = h('button', {
        class: 'button secondary',
        onclick: () => act(() => api.post('/api/admin/workers/launch?slots=2'), 'Starting a new worker…').then(update).catch(() => {}),
    }, '+ Add worker');
    const clearHistory = h('button', {
        class: 'button danger',
        onclick: () => confirm('Permanently delete all logs and finished jobs, including their results and files? '
            + 'This cannot be undone. Queued and running jobs are not affected.')
            && act(() => api.post('/api/admin/history/clear')).then(cleared => {
                const kept = cleared.jobsKept ? `; kept ${cleared.jobsKept} finished that unfinished jobs depend on` : '';
                toast(`Cleared ${cleared.jobs} jobs and ${cleared.logs} logs${kept}`);
                return update();
            }).catch(() => {}),
    }, 'Clear history');

    cards.clear();
    root.replaceChildren(
        h('div', { class: 'page-head' }, h('h1', {}, 'Live'), h('span', { class: 'spacer' }),
            h('div', { class: 'segmented', title: 'Scheduling policy' }, parts.policyButtons), clearHistory, addWorker, demoButton),
        parts.stats,
        h('div', { class: 'section' }, h('div', { class: 'section-head' }, h('h2', {}, 'Workers'), parts.workerSummary), parts.workers),
        h('div', { class: 'section grid cols-2' },
            card('Up next', null, parts.upNext),
            card('Activity', h('a', { href: '#/logs', class: 'small' }, 'All logs →'), parts.activity)));
}

export default {
    title: 'Live',
    refreshMs: 1000,
    async render(element) {
        build(element);
        await update();
    },
    refresh: () => update(),
};
