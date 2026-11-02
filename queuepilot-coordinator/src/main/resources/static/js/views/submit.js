import { api, ApiError } from '../api.js';
import { card, h, toast } from '../ui.js';
import { openJob } from './jobs.js';

let tasks = [];

/** One input control per schema field; read() converts the value back to JSON types. */
function fieldControl(spec) {
    const hint = [spec.description, spec.min != null ? `min ${spec.min}` : null, spec.max != null ? `max ${spec.max}` : null,
        spec.maxItems != null ? `up to ${spec.maxItems} items` : null].filter(Boolean).join(' · ');
    let input;
    let read;
    switch (spec.type) {
        case 'INTEGER':
        case 'NUMBER':
            input = h('input', { type: 'number', step: spec.type === 'INTEGER' ? '1' : 'any', min: spec.min, max: spec.max });
            read = async () => (input.value === '' ? undefined : Number(input.value));
            break;
        case 'BOOLEAN':
            input = h('select', {}, h('option', { value: '' }, '—'), h('option', { value: 'true' }, 'true'), h('option', { value: 'false' }, 'false'));
            read = async () => (input.value === '' ? undefined : input.value === 'true');
            break;
        case 'ENUM':
            input = h('select', {}, h('option', { value: '' }, spec.required ? 'Choose…' : 'Default'), spec.values.map(v => h('option', { value: v }, v)));
            read = async () => input.value || undefined;
            break;
        case 'STRING_LIST':
        case 'INTEGER_LIST':
        case 'NUMBER_LIST':
            input = h('textarea', { rows: 3, placeholder: 'Comma-separated values, e.g. 5, 3, 9' });
            read = async () => {
                if (!input.value.trim()) return undefined;
                const items = input.value.split(',').map(s => s.trim()).filter(Boolean);
                return spec.type === 'STRING_LIST' ? items : items.map(Number);
            };
            break;
        case 'FILE':
            input = h('input', { type: 'file' });
            read = async clientId => {
                if (!input.files.length) return undefined;
                const artifact = await api.upload(input.files[0], clientId);
                return artifact.id;
            };
            break;
        default:
            input = spec.maxLength > 200 ? h('textarea', { rows: 5 }) : h('input', {});
            read = async () => input.value || undefined;
    }
    const element = h('div', { class: 'field' },
        h('label', {}, spec.name, spec.required ? ' *' : '', h('span', { class: 'muted small' }, ` (${spec.type.toLowerCase().replace('_', ' ')})`)),
        input, hint ? h('small', {}, hint) : null);
    return { spec, element, read };
}

function render(root) {
    const enabled = tasks.filter(t => t.enabled);
    const taskSelect = h('select', {}, enabled.map(t => h('option', { value: t.id }, `${t.displayName} — ${t.identifier}`)));
    const inputsBox = h('div', { class: 'form-grid' });
    const description = h('p', { class: 'muted small' });
    let controls = [];

    const name = h('input', { placeholder: 'Defaults to the task name' });
    const priority = h('input', { type: 'number', min: 1, max: 10 });
    const estimate = h('input', { type: 'number', min: 1, placeholder: 'ms (used by SJF)' });
    const queue = h('input', { placeholder: 'default' });
    const clientId = h('input', { value: 'dashboard' });
    const startAt = h('input', { type: 'datetime-local' });
    const deadline = h('input', { type: 'datetime-local' });
    const dependsOn = h('input', { placeholder: 'Job ids, comma-separated' });
    const errors = h('ul', { class: 'error-list' });
    const submitButton = h('button', { class: 'button', type: 'submit' }, 'Submit job');

    function selectTask() {
        const task = enabled.find(t => t.id === taskSelect.value);
        description.textContent = task ? `${task.description || ''} Requires: ${task.requiredCapabilities.join(', ') || 'nothing'} · timeout ${task.timeoutSeconds}s · ${task.maxRetries} retries` : '';
        controls = task ? task.inputSchema.fields.map(fieldControl) : [];
        inputsBox.replaceChildren(...controls.map(c => c.element));
        priority.placeholder = task ? `default ${task.defaultPriority}` : '';
        estimate.placeholder = task ? `default ${task.defaultEstimatedMs} ms` : '';
    }
    taskSelect.onchange = selectTask;
    selectTask();

    const toIso = value => (value ? new Date(value).toISOString() : undefined);

    const form = h('form', {
        onsubmit: async event => {
            event.preventDefault();
            errors.replaceChildren();
            submitButton.disabled = true;
            try {
                const task = enabled.find(t => t.id === taskSelect.value);
                const input = {};
                for (const control of controls) {
                    const value = await control.read(clientId.value);
                    if (value !== undefined) input[control.spec.name] = value;
                }
                const body = {
                    name: name.value || undefined, taskType: task.taskType, taskVersion: task.version, input,
                    priority: priority.value ? Number(priority.value) : undefined,
                    estimatedMs: estimate.value ? Number(estimate.value) : undefined,
                    queue: queue.value || undefined, startAt: toIso(startAt.value), deadline: toIso(deadline.value),
                    dependsOn: dependsOn.value ? dependsOn.value.split(',').map(s => s.trim()).filter(Boolean) : undefined,
                };
                const job = await api.post('/api/jobs', body, { clientId: clientId.value });
                toast(`Job ${job.name} accepted (${job.state})`);
                openJob(job.id);
            } catch (error) {
                const fieldErrors = error instanceof ApiError ? error.body?.fieldErrors || [] : [];
                errors.replaceChildren(...(fieldErrors.length ? fieldErrors.map(e => h('li', {}, `${e.field}: ${e.message}`))
                    : [h('li', {}, error instanceof ApiError ? error.details() : String(error))]));
            } finally {
                submitButton.disabled = false;
            }
        },
    },
    h('div', { class: 'field' }, h('label', {}, 'Task type'), taskSelect, description),
    h('h3', {}, 'Task input'), inputsBox,
    h('h3', {}, 'Scheduling'),
    h('div', { class: 'form-grid' },
        h('div', { class: 'field' }, h('label', {}, 'Name'), name),
        h('div', { class: 'field' }, h('label', {}, 'Priority (1 low – 10 high)'), priority),
        h('div', { class: 'field' }, h('label', {}, 'Estimated duration'), estimate),
        h('div', { class: 'field' }, h('label', {}, 'Queue'), queue),
        h('div', { class: 'field' }, h('label', {}, 'Client id'), clientId, h('small', {}, 'Used for per-client limits; not a login.')),
        h('div', { class: 'field' }, h('label', {}, 'Start at'), startAt),
        h('div', { class: 'field' }, h('label', {}, 'Deadline'), deadline),
        h('div', { class: 'field' }, h('label', {}, 'Depends on'), dependsOn)),
    errors,
    h('div', { class: 'row' }, h('span', { class: 'spacer' }), submitButton));

    root.replaceChildren(
        card('Submit a job', null, enabled.length ? form : h('p', {}, 'No enabled task types. Register or enable one in the Task registry.')),
        card('Demo batch', null,
            h('p', { class: 'muted' }, 'Submits eight real jobs of different sizes and priorities across two queues, useful for comparing policies live. Run times come from the work itself, not timers.'),
            h('button', { class: 'button ghost', onclick: submitDemoBatch }, 'Submit demo batch')));
}

async function submitDemoBatch() {
    const jobs = [
        ['Pi — large', 'monte-carlo-pi', { samples: 400_000_000 }, 2, 'analytics', 20_000],
        ['Pi — small', 'monte-carlo-pi', { samples: 20_000_000 }, 5, 'analytics', 1_000],
        ['Sort 5M (merge)', 'sorting', { count: 5_000_000, algorithm: 'MERGE' }, 6, 'batch', 4_000],
        ['Sort 3M (heap)', 'sorting', { count: 3_000_000, algorithm: 'HEAP' }, 3, 'batch', 6_000],
        ['Pi — urgent', 'monte-carlo-pi', { samples: 60_000_000 }, 10, 'analytics', 3_000],
        ['Text sample', 'text-analysis', { text: 'QueuePilot schedules jobs. Workers execute jobs! Do workers fail? Recovery retries jobs.', topN: 5 }, 4, 'batch', 500],
        ['Sort 1M', 'sorting', { count: 1_000_000 }, 5, 'batch', 1_500],
        ['Pi — medium', 'monte-carlo-pi', { samples: 150_000_000 }, 1, 'analytics', 8_000],
    ];
    try {
        for (const [name, taskType, input, priority, queue, estimatedMs] of jobs) {
            await api.post('/api/jobs', { name, taskType, input, priority, queue, estimatedMs }, { clientId: 'demo' });
        }
        toast('8 demo jobs submitted');
        location.hash = '#/jobs';
    } catch (error) {
        toast(error instanceof ApiError ? error.details() : String(error), true);
    }
}

export default {
    title: 'Submit job',
    async render(root) {
        tasks = await api.get('/api/tasks');
        render(root);
    },
};
