// Thin wrapper around fetch for the QueuePilot REST API.
const ADMIN_KEY = 'queuepilot.admin';

export class ApiError extends Error {
    constructor(status, body) {
        super(body?.message || `Request failed (HTTP ${status})`);
        this.status = status;
        this.body = body;
    }

    /** Human-readable message including field errors. */
    details() {
        const fields = (this.body?.fieldErrors || []).map(e => `${e.field}: ${e.message}`);
        return fields.length ? `${this.message} — ${fields.join('; ')}` : this.message;
    }
}

function storage() {
    try {
        return window.sessionStorage;
    } catch {
        return null;
    }
}

export const admin = {
    credentials() {
        return storage()?.getItem(ADMIN_KEY) || null;
    },
    isLoggedIn() {
        return !!this.credentials();
    },
    save(user, password) {
        storage()?.setItem(ADMIN_KEY, btoa(`${user}:${password}`));
    },
    clear() {
        storage()?.removeItem(ADMIN_KEY);
    },
};

async function request(method, path, body, { asAdmin = false, clientId } = {}) {
    const headers = {};
    let payload;
    if (body instanceof FormData) {
        payload = body;
    } else if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(body);
    }
    if (asAdmin) {
        const credentials = admin.credentials();
        if (!credentials) {
            throw new ApiError(401, { message: 'Administrator login required' });
        }
        headers.Authorization = `Basic ${credentials}`;
    }
    if (clientId) {
        headers['X-Client-Id'] = clientId;
    }
    const response = await fetch(path, { method, headers, body: payload });
    const text = await response.text();
    let data = null;
    if (text) {
        try {
            data = JSON.parse(text);
        } catch {
            data = { message: text };
        }
    }
    if (!response.ok) {
        throw new ApiError(response.status, data);
    }
    return data;
}

export const api = {
    get: path => request('GET', path),
    post: (path, body, options) => request('POST', path, body ?? {}, options),
    upload: (file, clientId) => {
        const form = new FormData();
        form.append('file', file);
        return request('POST', '/api/artifacts', form, { clientId });
    },
    adminGet: path => request('GET', path, undefined, { asAdmin: true }),
    adminPost: (path, body) => request('POST', path, body ?? {}, { asAdmin: true }),
    adminPut: (path, body) => request('PUT', path, body, { asAdmin: true }),
};
