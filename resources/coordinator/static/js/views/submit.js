import { api, ApiError, errorText } from '../api.js';
import { h, toast } from '../ui.js';
import { openJob } from './jobs.js';

/** One input control per schema field; read() converts the value back to JSON types. */
function fieldControl(spec) {
    const hint = [spec.description, spec.min != null ? `min ${spec.min}` : null, spec.max != null ? `max ${spec.max}` : null]
        .filter(Boolean).join(' · ');
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
            input = h('input', { placeholder: 'Comma-separated, e.g. 5, 3, 9' });
            read = async () => {
                if (!input.value.trim()) return undefined;
                const items = input.value.split(',').map(s => s.trim()).filter(Boolean);
                return spec.type === 'STRING_LIST' ? items : items.map(Number);
            };
            break;
        case 'FILE':
            input = h('input', { type: 'file' });
            read = async () => (input.files.length ? (await api.upload(input.files[0])).id : undefined);
            break;
        default:
            input = spec.maxLength > 200 ? h('textarea', { rows: 4 }) : h('input', {});
            read = async () => input.value || undefined;
    }
    const element = h('div', { class: 'field' }, h('label', {}, spec.name, spec.required ? ' *' : ''), input,
        hint ? h('small', {}, hint) : null);
    return { spec, element, read };
}

function render(root, tasks) {
    const taskSelect = h('select', {}, tasks.map(t => h('option', { value: t.id }, t.displayName)));
    const description = h('small');
    const inputs = h('div', { class: 'form-grid' });
    let controls = [];

    const priority = h('input', { type: 'number', min: 1, max: 10 });
    const estimate = h('input', { type: 'number', min: 1, placeholder: 'ms' });
    const queue = h('input', { placeholder: 'default' });
    const dependsOn = h('input', { placeholder: 'Job ids, comma-separated' });
    const errors = h('ul', { class: 'errors' });
    const submitButton = h('button', { class: 'button', type: 'submit' }, 'Submit');

    function selectTask() {
        const task = tasks.find(t => t.id === taskSelect.value);
        description.textContent = task?.description || '';
        controls = task ? task.inputSchema.fields.map(fieldControl) : [];
        inputs.replaceChildren(...controls.map(c => c.element));
        priority.placeholder = task ? `${task.defaultPriority} (1 low – 10 high)` : '';
    }
    taskSelect.onchange = selectTask;
    selectTask();

    const form = h('form', {
        class: 'card', style: { maxWidth: '720px' },
        onsubmit: async event => {
            event.preventDefault();
            errors.replaceChildren();
            submitButton.disabled = true;
            try {
                const task = tasks.find(t => t.id === taskSelect.value);
                const input = {};
                for (const control of controls) {
                    const value = await control.read();
                    if (value !== undefined) input[control.spec.name] = value;
                }
                const job = await api.post('/api/jobs', {
                    taskType: task.taskType, input,
                    priority: priority.value ? Number(priority.value) : undefined,
                    estimatedMs: estimate.value ? Number(estimate.value) : undefined,
                    queue: queue.value || undefined,
                    dependsOn: dependsOn.value ? dependsOn.value.split(',').map(s => s.trim()).filter(Boolean) : undefined,
                });
                toast(`Submitted: ${job.label}`);
                openJob(job.id);
            } catch (error) {
                const fieldErrors = error instanceof ApiError ? error.body?.fieldErrors || [] : [];
                errors.replaceChildren(...(fieldErrors.length ? fieldErrors.map(e => h('li', {}, `${e.field}: ${e.message}`))
                    : [h('li', {}, errorText(error))]));
            } finally {
                submitButton.disabled = false;
            }
        },
    },
    h('div', { class: 'field' }, h('label', {}, 'Task'), taskSelect, description),
    inputs,
    h('div', { class: 'field', style: { maxWidth: '220px' } }, h('label', {}, 'Priority'), priority),
    h('details', {}, h('summary', {}, 'More options'),
        h('div', { class: 'form-grid' },
            h('div', { class: 'field' }, h('label', {}, 'Estimated duration'), estimate, h('small', {}, 'Used by Shortest Job First')),
            h('div', { class: 'field' }, h('label', {}, 'Queue'), queue),
            h('div', { class: 'field' }, h('label', {}, 'Depends on'), dependsOn))),
    errors,
    h('div', { class: 'row' }, h('span', { class: 'spacer' }), submitButton));

    root.replaceChildren(h('div', { class: 'page-head' }, h('h1', {}, 'Submit a job')),
        tasks.length ? form : h('div', { class: 'card' }, 'No task types are registered yet.'));
}

export default {
    title: 'Submit',
    async render(root) {
        render(root, await api.get('/api/tasks'));
    },
};
