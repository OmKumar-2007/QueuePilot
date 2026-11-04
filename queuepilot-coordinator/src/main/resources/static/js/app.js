// Router and polling loop. Each view module exports { title, render(root, args), refresh?() }.
import { api, errorText } from './api.js';
import { h, toast } from './ui.js';
import dashboard from './views/dashboard.js';
import jobs from './views/jobs.js';
import submit from './views/submit.js';
import workflows from './views/workflows.js';
import tasks from './views/tasks.js';
import logs from './views/logs.js';

const routes = { dashboard, workers: dashboard, overview: dashboard, jobs, submit, workflows, tasks, logs };
let current = null;
let timer = null;

async function navigate() {
    const [name, ...rest] = location.hash.replace(/^#\/?/, '').split('/');
    const view = routes[name] || dashboard;
    const route = routes[name] ? (view === dashboard ? 'dashboard' : name) : 'dashboard';
    const root = document.getElementById('view');
    current = view;
    document.title = `${view.title} · QueuePilot`;
    document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.dataset.route === route));
    clearInterval(timer);
    root.replaceChildren(h('div', { class: 'muted' }, 'Loading…'));
    try {
        await view.render(root, rest);
    } catch (error) {
        root.replaceChildren(h('div', { class: 'card' }, h('strong', {}, 'Could not load this page. '), errorText(error)));
    }
    if (view.refresh) {
        timer = setInterval(() => {
            if (current === view && !document.hidden) {
                view.refresh().catch(() => { /* the next tick retries */ });
            }
        }, view.refreshMs || 2000);
    }
}

async function updateHealth() {
    const health = document.getElementById('health');
    try {
        const status = await api.get('/api/health');
        health.className = `health ${status.database ? 'up' : 'down'}`;
        health.title = status.database ? 'Coordinator online' : 'Database unavailable';
    } catch {
        health.className = 'health down';
        health.title = 'Coordinator unreachable';
    }
}

/** index.html sets the initial theme before first paint; this keeps the button in step and saves changes. */
function setupThemeToggle() {
    const button = document.getElementById('theme-toggle');
    const label = () => {
        const text = document.documentElement.dataset.theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode';
        button.title = text;
        button.setAttribute('aria-label', text);
    };
    button.addEventListener('click', () => {
        const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
        document.documentElement.dataset.theme = next;
        try {
            localStorage.setItem('queuepilot-theme', next);
        } catch { /* storage blocked: the choice lasts until the page reloads */ }
        label();
    });
    label();
}

/** Runs an action, shows a confirmation or the error. */
export async function act(action, successMessage) {
    try {
        const result = await action();
        if (successMessage) toast(successMessage);
        return result;
    } catch (error) {
        toast(errorText(error), true);
        throw error;
    }
}

window.addEventListener('hashchange', navigate);
setupThemeToggle();
updateHealth();
setInterval(updateHealth, 5000);
navigate();
