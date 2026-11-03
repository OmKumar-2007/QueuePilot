import { api } from '../api.js';
import { act } from '../app.js';
import { card, fmtDuration, h, openDrawer, table } from '../ui.js';

let root;

const enabledBadge = enabled => h('span', { class: `status ${enabled ? 'ONLINE' : 'DISABLED'}` }, enabled ? 'enabled' : 'disabled');

const EXAMPLE = {
    taskType: 'prime-sieve', version: '1.0.0', displayName: 'Prime sieve (example plugin)',
    description: 'Counts primes up to a limit. Needs the example plugin jar in a worker plugins folder.',
    executorKind: 'JAVA_PLUGIN', requiredCapabilities: ['cpu'],
    inputSchema: { fields: [{ name: 'limit', type: 'INTEGER', required: true, min: 10, max: 500000000 }] },
    outputSchema: { fields: [{ name: 'primeCount', type: 'INTEGER' }] },
    resources: { cpuMillis: 1000, memoryMb: 256 }, timeoutSeconds: 300, maxRetries: 2, defaultPriority: 5, defaultEstimatedMs: 4000,
};

function detail(task) {
    const toggle = h('button', {
        class: 'button secondary',
        onclick: () => act(() => api.post(`/api/admin/tasks/${task.id}/${task.enabled ? 'disable' : 'enable'}`),
            `${task.displayName} ${task.enabled ? 'disabled' : 'enabled'}`)
            .then(async () => { await draw(); openDrawer(detail(await api.get(`/api/tasks/${task.id}`))); }),
    }, task.enabled ? 'Disable' : 'Enable');
    return h('div', { class: 'stack' },
        h('h1', {}, task.displayName),
        h('div', { class: 'row' }, enabledBadge(task.enabled), h('span', { class: 'muted mono' }, task.identifier),
            h('span', { class: 'spacer' }), toggle),
        task.description ? h('p', { class: 'muted' }, task.description) : null,
        card(null, null, h('dl', { class: 'facts' },
            h('dt', {}, 'Needs'), h('dd', {}, task.requiredCapabilities.join(', ') || 'nothing special'),
            h('dt', {}, 'Timeout'), h('dd', {}, `${task.timeoutSeconds} s`),
            h('dt', {}, 'Retries'), h('dd', {}, task.maxRetries),
            h('dt', {}, 'Typical duration'), h('dd', {}, fmtDuration(task.defaultEstimatedMs)),
            h('dt', {}, 'Runs as'), h('dd', {}, task.executorKind === 'CONTAINER' ? `container ${task.containerImage}` : 'Java plugin'))),
        card('Inputs', null, table([
            { label: 'Field', render: f => h('span', { class: 'mono' }, f.name) },
            { label: 'Type', render: f => f.type.toLowerCase() },
            { label: 'Rules', render: f => [f.required && 'required', f.min != null && `≥ ${f.min}`, f.max != null && `≤ ${f.max}`,
                f.values && f.values.join(' | ')].filter(Boolean).join(', ') },
        ], task.inputSchema.fields)));
}

function registerForm() {
    const text = h('textarea', { rows: 14 }, JSON.stringify(EXAMPLE, null, 2));
    return h('details', { class: 'section' }, h('summary', {}, 'Register a new task type'),
        h('div', { class: 'card stack' },
            h('p', { class: 'muted small', style: { margin: 0 } }, 'This registers the description only. The code ships as a plugin jar in a worker’s plugins folder. New versions start disabled.'),
            text,
            h('div', { class: 'row' }, h('span', { class: 'spacer' }), h('button', {
                class: 'button', onclick: () => act(() => api.post('/api/admin/tasks', JSON.parse(text.value)), 'Task registered').then(draw),
            }, 'Register'))));
}

async function draw() {
    const tasks = await api.get('/api/tasks');
    root.replaceChildren(
        h('div', { class: 'page-head' }, h('h1', {}, 'Tasks')),
        h('section', { class: 'card table-card' }, table([
            { label: 'Task', render: t => t.displayName },
            { label: 'Version', render: t => h('span', { class: 'mono' }, t.version) },
            { label: 'Needs', render: t => t.requiredCapabilities.join(', ') },
            { label: 'Status', render: t => enabledBadge(t.enabled) },
        ], tasks, task => openDrawer(detail(task)))),
        registerForm());
}

export default {
    title: 'Tasks',
    async render(element) {
        root = element;
        await draw();
    },
};
