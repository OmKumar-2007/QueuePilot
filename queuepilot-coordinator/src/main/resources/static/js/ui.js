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

const LABELS = { PRIORITY_AGING: 'Priority', LEASE_EXPIRED: 'lease expired', TIMED_OUT: 'timed out' };

/** A coloured dot plus a lowercase label, e.g. "● running". */
export const badge = state => h('span', { class: `status ${state}` },
    (LABELS[state] || String(state)).replaceAll('_', ' ').toLowerCase());

export function progress(percent, done = false) {
    const value = Math.max(0, Math.min(100, percent || 0));
    return h('div', { class: `progress${done ? ' done' : ''}`, title: `${value}%` }, h('span', { style: { width: `${value}%` } }));
}

export const shortId = id => (id ? String(id).slice(0, 8) : '—');

export function fmtTime(iso) {
    if (!iso) return '—';
    return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function fmtDateTime(iso) {
    if (!iso) return '—';
    return new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
}

export function fmtDuration(ms) {
    if (ms === null || ms === undefined || Number.isNaN(ms)) return '—';
    if (ms < 1000) return `${Math.round(ms)} ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
    return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
}

export function between(startIso, endIso) {
    if (!startIso) return null;
    return (endIso ? new Date(endIso) : new Date()) - new Date(startIso);
}

export function relative(iso) {
    if (!iso) return '—';
    const seconds = Math.round((Date.now() - new Date(iso)) / 1000);
    if (seconds < 3) return 'now';
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    return `${Math.floor(seconds / 3600)}h ago`;
}

export const json = value => h('pre', { class: 'json' }, JSON.stringify(value, null, 2));

export const empty = (title, text) => h('div', { class: 'empty' }, h('strong', {}, title), text);

export function card(title, actions, ...content) {
    return h('section', { class: 'card' },
        title ? h('div', { class: 'section-head' }, h('h2', {}, title), h('span', { class: 'spacer' }), actions || null) : null,
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
    setTimeout(() => element.remove(), isError ? 6000 : 3000);
}

export function openDrawer(content) {
    const root = document.getElementById('drawer');
    const target = document.getElementById('drawer-content');
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

export const drawerIsOpen = () => !document.getElementById('drawer').hidden;

export const STATE_COLORS = {
    BLOCKED: 'var(--idle)', QUEUED: 'var(--queued)', RUNNING: 'var(--running)',
    SUCCEEDED: 'var(--ok)', FAILED: 'var(--bad)', CANCELLED: 'var(--idle)', SKIPPED: 'var(--idle)',
};
