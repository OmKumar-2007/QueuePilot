import { api, ApiError } from '../api.js';
import { badge, card, empty, fmtDateTime, h, progress, STATE_COLORS, svg, table, toast } from '../ui.js';
import { openJob } from './jobs.js';

let graphArea;
let selectedId = null;

// A media-style pipeline built from the available tasks (validate -> analyse -> parallel work -> combine -> verify).
const TEMPLATE = {
    name: 'Report pipeline',
    queue: 'pipelines',
    steps: [
        { key: 'ingest', taskType: 'sorting', input: { count: 2000000 }, dependsOn: [] },
        { key: 'validate', taskType: 'text-analysis', input: { text: 'Readings ingested. Values sorted. Ready for analysis.' }, dependsOn: ['ingest'] },
        { key: 'simulate_a', taskType: 'monte-carlo-pi', input: { samples: 80000000, seed: 1 }, dependsOn: ['validate'] },
        { key: 'simulate_b', taskType: 'monte-carlo-pi', input: { samples: 80000000, seed: 2 }, dependsOn: ['validate'] },
        { key: 'simulate_c', taskType: 'monte-carlo-pi', input: { samples: 80000000, seed: 3 }, dependsOn: ['validate'] },
        { key: 'combine', taskType: 'sorting', input: { count: 500000, algorithm: 'HEAP' }, dependsOn: ['simulate_a', 'simulate_b', 'simulate_c'] },
        { key: 'verify', taskType: 'text-analysis', input: { text: 'All simulations combined. Package verified.' }, dependsOn: ['combine'] },
    ],
};

/** Layered layout: a node's column is the length of the longest path leading to it. */
function dagView(workflow) {
    const depth = {};
    const incoming = {};
    workflow.nodes.forEach(n => (incoming[n.jobId] = []));
    workflow.edges.forEach(e => incoming[e.to].push(e.from));
    const depthOf = id => (depth[id] ??= incoming[id].length ? 1 + Math.max(...incoming[id].map(depthOf)) : 0);
    workflow.nodes.forEach(n => depthOf(n.jobId));

    const columns = {};
    workflow.nodes.forEach(n => (columns[depth[n.jobId]] ??= []).push(n));
    const W = 170, H = 54, GX = 70, GY = 22;
    const position = {};
    Object.entries(columns).forEach(([col, nodes]) => nodes.forEach((n, row) => {
        position[n.jobId] = { x: 16 + col * (W + GX), y: 16 + row * (H + GY) };
    }));
    const width = 32 + Object.keys(columns).length * (W + GX) - GX;
    const height = 32 + Math.max(...Object.values(columns).map(c => c.length)) * (H + GY) - GY;

    const edges = workflow.edges.map(e => {
        const a = position[e.from], b = position[e.to];
        const x1 = a.x + W, y1 = a.y + H / 2, x2 = b.x, y2 = b.y + H / 2, mid = (x1 + x2) / 2;
        return svg('path', { class: 'edge', d: `M${x1},${y1} C${mid},${y1} ${mid},${y2} ${x2 - 6},${y2}`, 'marker-end': 'url(#arrow)' });
    });
    const nodes = workflow.nodes.map(n => {
        const p = position[n.jobId];
        const color = STATE_COLORS[n.state];
        const group = svg('g', { class: 'node', transform: `translate(${p.x},${p.y})`, style: 'cursor:pointer' },
            svg('rect', { width: W, height: H, rx: 10, stroke: color }),
            svg('rect', { width: Math.max(0, (W - 2) * n.progressPercent / 100), height: 4, x: 1, y: H - 5, rx: 2, fill: color }),
            svg('text', { x: 12, y: 21, 'font-weight': '600' }, n.step.slice(0, 22)),
            svg('text', { x: 12, y: 39, fill: color }, `${n.state.toLowerCase()} · ${n.progressPercent}%`));
        group.addEventListener('click', () => openJob(n.jobId));
        return group;
    });
    return h('div', { class: 'dag' }, svg('svg', { width, height, viewBox: `0 0 ${width} ${height}` },
        svg('defs', {}, svg('marker', { id: 'arrow', viewBox: '0 0 10 10', refX: 8, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto-start-reverse' },
            svg('path', { d: 'M0,0 L10,5 L0,10 z', fill: 'var(--muted)' }))),
        ...edges, ...nodes));
}

function creator() {
    const text = h('textarea', { rows: 16 }, JSON.stringify(TEMPLATE, null, 2));
    const errors = h('ul', { class: 'errors' });
    return h('details', { class: 'section' }, h('summary', {}, 'Submit a workflow'), h('div', { class: 'card stack' },
        h('p', { class: 'muted small', style: { margin: 0 } }, 'Steps reference each other by key. Independent steps run in parallel; if a step fails, everything after it is skipped; cycles are rejected.'),
        text, errors,
        h('div', { class: 'row' }, h('span', { class: 'spacer' }),
            h('button', {
                class: 'button', onclick: async () => {
                    errors.replaceChildren();
                    try {
                        const workflow = await api.post('/api/workflows', JSON.parse(text.value), 'dashboard');
                        selectedId = workflow.id;
                        toast(`Workflow ${workflow.name} submitted`);
                        draw();
                    } catch (error) {
                        const list = error instanceof ApiError ? (error.body?.fieldErrors || []).map(e => `${e.field}: ${e.message}`) : [];
                        errors.replaceChildren(...(list.length ? list : [error instanceof ApiError ? error.details() : String(error)]).map(m => h('li', {}, m)));
                    }
                },
            }, 'Submit workflow'))));
}

async function draw() {
    const workflows = await api.get('/api/workflows');
    if (!selectedId && workflows.length) selectedId = workflows[0].id;
    const selected = workflows.find(w => w.id === selectedId);
    graphArea.replaceChildren(
        h('div', { class: 'page-head' }, h('h1', {}, 'Workflows')),
        selected ? card(selected.name, h('div', { class: 'row' }, badge(selected.state), h('span', { class: 'muted small' }, `${selected.percentComplete}% done`)),
            progress(selected.percentComplete, selected.state === 'SUCCEEDED'),
            h('div', { style: { marginTop: '14px' } }, dagView(selected)),
            h('p', { class: 'muted small' }, 'Click a step to see its job.')) : '',
        h('div', { class: 'section' }, card('All workflows', null, workflows.length ? table([
            { label: 'Name', render: w => w.name },
            { label: 'State', render: w => badge(w.state) },
            { label: 'Steps', num: true, render: w => w.nodes.length },
            { label: 'Progress', render: w => progress(w.percentComplete, w.state === 'SUCCEEDED') },
            { label: 'Created', render: w => fmtDateTime(w.createdAt) },
        ], workflows, w => { selectedId = w.id; draw(); }) : empty('No workflows yet', 'Submit one below.'))));
}

export default {
    title: 'Workflows',
    async render(element) {
        // The editor is created once so polling never wipes what the user is typing.
        graphArea = h('div');
        element.replaceChildren(graphArea, creator());
        await draw();
    },
    refresh: () => draw(),
};
