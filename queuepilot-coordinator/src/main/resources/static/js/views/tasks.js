import { admin, api } from '../api.js';
import { adminAction } from '../app.js';
import { badge, card, fmtDuration, h, json, openDrawer, table } from '../ui.js';

let root;

const EXAMPLE = {
    taskType: 'prime-sieve', version: '1.0.0', displayName: 'Prime sieve (example plugin)',
    description: 'Counts primes up to limit. Install queuepilot-example-plugin jar into a worker plugins folder first.',
    executorKind: 'JAVA_PLUGIN', requiredCapabilities: ['cpu'],
    inputSchema: { fields: [{ name: 'limit', type: 'INTEGER', required: true, min: 10, max: 500000000 }] },
    outputSchema: { fields: [{ name: 'primeCount', type: 'INTEGER' }] },
    resources: { cpuMillis: 1000, memoryMb: 256 }, timeoutSeconds: 300, maxRetries: 2, defaultPriority: 5, defaultEstimatedMs: 4000,
};

function detail(task) {
    const toggle = h('button', {
        class: `button small${task.enabled ? ' danger' : ''}`,
        onclick: () => adminAction(() => api.adminPost(`/api/admin/tasks/${task.id}/${task.enabled ? 'disable' : 'enable'}`),
            `Task ${task.identifier} ${task.enabled ? 'disabled' : 'enabled'}`)
            .then(async () => { await draw(); openDrawer(detail(await api.get(`/api/tasks/${task.id}`))); }),
    }, task.enabled ? 'Disable' : 'Enable');
    return h('div', { class: 'stack' },
        h('div', { class: 'row' }, h('h2', { style: { margin: 0 } }, task.displayName), badge(task.enabled ? 'ONLINE' : 'DISABLED'),
            h('span', { class: 'spacer' }), toggle),
        h('p', { class: 'muted' }, task.description || ''),
        card('Definition', null, h('table', {}, h('tbody', {}, [
            ['Identifier', h('span', { class: 'mono' }, task.identifier)],
            ['Executor', task.executorKind],
            ['Required capabilities', task.requiredCapabilities.join(', ') || 'none'],
            ['Resources', `${task.cpuMillis / 1000} CPU · ${task.memoryMb} MB`],
            ['Timeout', `${task.timeoutSeconds} s`],
            ['Max retries', task.maxRetries],
            ['Default priority', task.defaultPriority],
            ['Default estimate', fmtDuration(task.defaultEstimatedMs)],
            ['Container image', task.containerImage || '—'],
        ].map(([k, v]) => h('tr', {}, h('th', {}, k), h('td', {}, v)))))),
        card('Input schema', null, table([
            { label: 'Field', render: f => h('span', { class: 'mono' }, f.name) },
            { label: 'Type', render: f => f.type },
            { label: 'Required', render: f => (f.required ? 'yes' : 'no') },
            { label: 'Rules', render: f => [f.min != null && `min ${f.min}`, f.max != null && `max ${f.max}`, f.maxLength && `≤ ${f.maxLength} chars`,
                f.maxItems && `≤ ${f.maxItems} items`, f.values && f.values.join(' | ')].filter(Boolean).join(', ') },
        ], task.inputSchema.fields)),
        card('Output schema', null, json(task.outputSchema)));
}

function registerForm() {
    const text = h('textarea', { rows: 14 }, JSON.stringify(EXAMPLE, null, 2));
    return card('Register a task type', badge('ADMIN'),
        h('p', { class: 'muted small' }, 'Registers the manifest (metadata) only. The code ships separately as a plugin jar in a worker\'s plugins folder, or as a container image. New versions start disabled.'),
        text,
        h('div', { class: 'row', style: { marginTop: '10px' } }, h('span', { class: 'spacer' }),
            h('button', {
                class: 'button', disabled: !admin.isLoggedIn(),
                title: admin.isLoggedIn() ? '' : 'Log in as administrator first',
                onclick: () => adminAction(() => api.adminPost('/api/admin/tasks', JSON.parse(text.value)), 'Task registered (disabled)').then(draw),
            }, 'Register')));
}

async function draw() {
    const tasks = await api.get('/api/tasks');
    root.replaceChildren(
        card(`Registered task types (${tasks.length})`, null, table([
            { label: 'Task', render: t => h('div', {}, h('div', {}, t.displayName), h('div', { class: 'muted small mono' }, t.identifier)) },
            { label: 'Executor', render: t => t.executorKind },
            { label: 'Capabilities', render: t => h('div', { class: 'chips' }, t.requiredCapabilities.map(c => h('span', { class: 'chip' }, c))) },
            { label: 'Timeout', num: true, render: t => `${t.timeoutSeconds}s` },
            { label: 'Retries', num: true, render: t => t.maxRetries },
            { label: 'Status', render: t => badge(t.enabled ? 'ONLINE' : 'DISABLED') },
        ], tasks, task => openDrawer(detail(task)))),
        registerForm());
}

export default {
    title: 'Task registry',
    async render(element) {
        root = element;
        await draw();
    },
};
