// Router, polling loop and admin login. Each view module exports { title, render(root), refresh?() }.
import { admin, api, ApiError } from './api.js';
import { h, openModal, toast } from './ui.js';
import overview from './views/overview.js';
import submit from './views/submit.js';
import jobs from './views/jobs.js';
import workflows from './views/workflows.js';
import tasks from './views/tasks.js';
import scheduler from './views/scheduler.js';
import workers from './views/workers.js';
import benchmarks from './views/benchmarks.js';

const routes = { overview, submit, jobs, workflows, tasks, scheduler, workers, benchmarks };
const REFRESH_MS = 2000;
let current = null;
let timer = null;

async function navigate() {
    const [name, ...rest] = location.hash.replace(/^#\/?/, '').split('/');
    const view = routes[name] || overview;
    const root = document.getElementById('view');
    current = view;
    document.getElementById('page-title').textContent = view.title;
    document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.dataset.route === (routes[name] ? name : 'overview')));
    document.body.classList.remove('nav-open');
    root.replaceChildren(h('div', { class: 'muted' }, 'Loading…'));
    clearInterval(timer);
    try {
        await view.render(root, rest);
    } catch (error) {
        root.replaceChildren(h('div', { class: 'card' }, h('strong', {}, 'Could not load this page. '),
            error instanceof ApiError ? error.details() : String(error)));
    }
    if (view.refresh) {
        timer = setInterval(() => {
            if (current === view && !document.hidden) {
                view.refresh().catch(() => { /* next tick retries; header shows connectivity */ });
            }
        }, REFRESH_MS);
    }
}

async function updateHeader() {
    const health = document.getElementById('health');
    try {
        const [status, preview] = await Promise.all([api.get('/api/health'), api.get('/api/scheduler')]);
        health.className = `health ${status.database ? 'up' : 'down'}`;
        health.lastElementChild.textContent = status.database ? 'Coordinator online' : 'Database unavailable';
        document.getElementById('policy-chip').textContent = `Policy: ${preview.settings.activePolicy.replace('_', ' ')}`;
    } catch {
        health.className = 'health down';
        health.lastElementChild.textContent = 'Coordinator unreachable';
    }
}

function renderAdminButton() {
    const button = document.getElementById('admin-button');
    button.textContent = admin.isLoggedIn() ? 'Admin ✓ (log out)' : 'Admin login';
    button.onclick = () => {
        if (admin.isLoggedIn()) {
            admin.clear();
            renderAdminButton();
            toast('Logged out');
            navigate();
        } else {
            showLogin();
        }
    };
}

export function showLogin() {
    const user = h('input', { value: 'admin', autocomplete: 'username' });
    const password = h('input', { type: 'password', autocomplete: 'current-password' });
    const error = h('p', { class: 'error-list', hidden: true });
    let close;
    const form = h('form', {
        onsubmit: async event => {
            event.preventDefault();
            admin.save(user.value, password.value);
            try {
                await api.adminGet('/api/admin/session');
                close();
                renderAdminButton();
                toast('Logged in as administrator');
                navigate();
            } catch {
                admin.clear();
                error.hidden = false;
                error.textContent = 'Wrong username or password.';
            }
        },
    },
    h('h2', {}, 'Administrator login'),
    h('p', { class: 'muted small' }, 'Needed for task registration, scheduler settings, worker controls and failure simulation. ',
        'Unless QP_ADMIN_PASSWORD is set, the password is in data/admin-password.txt next to the coordinator.'),
    h('div', { class: 'field' }, h('label', {}, 'Username'), user),
    h('div', { class: 'field' }, h('label', {}, 'Password'), password),
    error,
    h('div', { class: 'row' }, h('span', { class: 'spacer' }), h('button', { class: 'button', type: 'submit' }, 'Log in')));
    close = openModal(form);
    password.focus();
}

/** Runs an admin action and reports the outcome; prompts for login when needed. */
export async function adminAction(action, successMessage) {
    try {
        const result = await action();
        if (successMessage) toast(successMessage);
        return result;
    } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
            admin.clear();
            renderAdminButton();
            showLogin();
        } else {
            toast(error instanceof ApiError ? error.details() : String(error), true);
        }
        throw error;
    }
}

document.getElementById('menu-toggle').onclick = () => document.body.classList.toggle('nav-open');
window.addEventListener('hashchange', navigate);
renderAdminButton();
updateHeader();
setInterval(updateHeader, 5000);
navigate();
