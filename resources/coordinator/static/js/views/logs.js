import { api } from '../api.js';
import { badge, card, empty, h } from '../ui.js';
import { openJob } from './jobs.js';

const PAGE_SIZE = 200;

let events = []; // newest first
let hasOlder = false;
let list;
let olderButton;

const when = iso => new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'medium' });

function row(event) {
    return h('li', {
        class: event.jobId ? 'clickable' : '',
        onclick: event.jobId ? () => openJob(event.jobId) : null,
    }, h('span', { class: 'when' }, when(event.occurredAt)), badge(event.status), h('span', { class: 'message' }, event.message));
}

function draw() {
    list.replaceChildren(events.length
        ? h('ul', { class: 'list log-list' }, events.map(row))
        : empty('No logs yet', 'Events appear here as jobs and workers run.'));
    olderButton.hidden = !hasOlder;
}

async function loadNewest() {
    events = await api.get(`/api/events?limit=${PAGE_SIZE}`);
    hasOlder = events.length === PAGE_SIZE;
}

async function loadOlder() {
    olderButton.disabled = true;
    try {
        const page = await api.get(`/api/events?limit=${PAGE_SIZE}&beforeId=${events.at(-1).id}`);
        events = events.concat(page);
        hasOlder = page.length === PAGE_SIZE;
        draw();
    } finally {
        olderButton.disabled = false;
    }
}

export default {
    title: 'Logs',
    async render(root) {
        list = h('div');
        olderButton = h('button', { class: 'button secondary', onclick: loadOlder }, 'Load older logs');
        await loadNewest();
        root.replaceChildren(h('div', { class: 'page-head' }, h('h1', {}, 'Logs')),
            card(null, null, list, h('div', { class: 'row', style: { justifyContent: 'center', marginTop: '12px' } }, olderButton)));
        draw();
    },
    /** Adds new events on top and keeps any older pages already loaded. */
    async refresh() {
        const page = await api.get(`/api/events?limit=${PAGE_SIZE}`);
        const newestKnown = events[0]?.id ?? 0;
        const fresh = page.filter(e => e.id > newestKnown);
        if (!fresh.length) return;
        if (fresh.length === page.length && page.length === PAGE_SIZE) {
            // More than a page arrived (e.g. the tab was hidden), leaving a gap: restart from the newest page.
            events = page;
            hasOlder = true;
        } else {
            events = fresh.concat(events);
        }
        draw();
    },
};
