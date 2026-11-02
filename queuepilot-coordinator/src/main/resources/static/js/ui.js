// Small DOM helpers shared by all views. No framework: h() builds elements, the rest formats data.

/** h('div', {class: 'card', onclick: fn}, child1, 'text', ...) */
export function h(tag, props = {}, ...children) {
    const element = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
        if (value === undefined || value === null || value === false) continue;
        if (key.startsWith('on') && typeof value === 'function') {
            element.addEventListener(key.slice(2).toLowerCase(), value);
        } else if (key === 'class') {
            element.className = value;
        } else if (key === 'style' && typeof value === 'object') {
            Object.assign(element.style, value);
        } else if (key === 'html') {
            element.innerHTML = value;
        } else {
            element.setAttribute(key, value === true ? '' : value);
        }
    }
    append(element, children);
    return element;
}

function append(parent, children) {
    for (const child of children.flat(Infinity)) {
        if (child === null || child === undefined || child === false) continue;
        parent.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
}

/** Same as h(), but for SVG elements. */
export function svg(tag, attributes = {}, ...children) {
    const element = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [key, value] of Object.entries(attributes)) {
        element.setAttribute(key, value);
    }
    append(element, children);
    return element;
}

export const badge = state => h('span', { class: `badge ${state}` }, String(state).replaceAll('_', ' '));

export function progress(percent, done = false) {
    const value = Math.max(0, Math.min(100, percent || 0));
    return h('div', { class: `progress${done ? ' done' : ''}`, title: `${value}%` }, h('span', { style: { width: `${value}%` } }));
}

export const shortId = id => (id ? String(id).slice(0, 8) : '—');

export function fmtTime(iso) {
    if (!iso) return '—';
    const date = new Date(iso);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function fmtDateTime(iso) {
    if (!iso) return '—';
    return new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'medium' });
}

export function fmtDuration(ms) {
    if (ms === null || ms === undefined || Number.isNaN(ms)) return '—';
    if (ms < 1000) return `${Math.round(ms)} ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
    const minutes = Math.floor(ms / 60_000);
    return `${minutes}m ${Math.round((ms % 60_000) / 1000)}s`;
}

export function between(startIso, endIso) {
    if (!startIso) return null;
    return (endIso ? new Date(endIso) : new Date()) - new Date(startIso);
}

export function relative(iso) {
    if (!iso) return '—';
    const seconds = Math.round((Date.now() - new Date(iso)) / 1000);
    if (seconds < 2) return 'just now';
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    return `${Math.floor(seconds / 3600)}h ago`;
}

export const fmtNumber = (n, digits = 0) =>
    n === null || n === undefined ? '—' : Number(n).toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits });

export function json(value) {
    return h('pre', { class: 'json' }, JSON.stringify(value, null, 2));
}

export function empty(title, text) {
    return h('div', { class: 'empty' }, h('strong', {}, title), text);
}

export function card(title, actions, ...content) {
    return h('section', { class: 'card' },
        title ? h('div', { class: 'card-head' }, h('h2', {}, title), actions || null) : null,
        ...content);
}

export function table(columns, rows, onRowClick) {
    return h('div', { class: 'table-wrap' },
        h('table', {},
            h('thead', {}, h('tr', {}, columns.map(c => h('th', { class: c.num ? 'num' : '' }, c.label)))),
            h('tbody', {}, rows.map(row => h('tr', {
                class: onRowClick ? 'clickable' : '',
                onclick: onRowClick ? () => onRowClick(row) : null,
            }, columns.map(c => h('td', { class: c.num ? 'num' : '' }, c.render(row))))))));
}

export function toast(message, isError = false) {
    const element = h('div', { class: `toast${isError ? ' error' : ''}` }, message);
    document.getElementById('toasts').append(element);
    setTimeout(() => element.remove(), isError ? 7000 : 3500);
}

function overlay(id, contentId, content) {
    const root = document.getElementById(id);
    const target = document.getElementById(contentId);
    target.replaceChildren(content);
    root.hidden = false;
    const close = () => {
        root.hidden = true;
        target.replaceChildren();
        document.removeEventListener('keydown', onKey);
    };
    const onKey = event => event.key === 'Escape' && close();
    document.addEventListener('keydown', onKey);
    root.querySelectorAll('[data-close]').forEach(el => (el.onclick = close));
    return close;
}

export const openDrawer = content => overlay('drawer', 'drawer-content', content);
export const openModal = content => overlay('modal', 'modal-content', content);
export const drawerIsOpen = () => !document.getElementById('drawer').hidden;

export const STATE_COLORS = {
    SCHEDULED: 'var(--s-scheduled)', BLOCKED: 'var(--s-blocked)', QUEUED: 'var(--s-queued)', RUNNING: 'var(--s-running)',
    SUCCEEDED: 'var(--s-succeeded)', FAILED: 'var(--s-failed)', CANCELLED: 'var(--s-cancelled)', SKIPPED: 'var(--s-skipped)',
};
