import { api } from '../api.js';
import { act } from '../app.js';
import { card, fmtDuration, h, openDrawer, table } from '../ui.js';

let root;

const EXAMPLE = {
    taskType: 'my-task', displayName: 'My task (example)',
    description: 'Describe what the task does. Its code must be a plugin jar in a worker plugins folder.',
    inputSchema: { fields: [{ name: 'limit', type: 'INTEGER', required: true, min: 10, max: 500000000 }] },
    timeoutSeconds: 300, maxRetries: 2, defaultPriority: 5, defaultEstimatedMs: 4000,
};

function detail(task) {
    return h('div', { class: 'stack' },
        h('h1', {}, task.displayName),
        h('div', { class: 'row' }, h('span', { class: 'muted mono' }, task.taskType)),
        task.description ? h('p', { class: 'muted' }, task.description) : null,
        card(null, null, h('dl', { class: 'facts' },
            h('dt', {}, 'Timeout'), h('dd', {}, `${task.timeoutSeconds} s`),
            h('dt', {}, 'Retries'), h('dd', {}, task.maxRetries),
            h('dt', {}, 'Typical duration'), h('dd', {}, fmtDuration(task.defaultEstimatedMs)))),
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
            h('p', { class: 'muted small', style: { margin: 0 } }, 'This registers the description only. The code ships as a plugin jar in a worker’s plugins folder.'),
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
            { label: 'Type', render: t => h('span', { class: 'mono' }, t.taskType) },
            { label: 'Timeout', render: t => `${t.timeoutSeconds} s` },
            { label: 'Retries', render: t => t.maxRetries },
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
