// Thin wrapper around fetch for the QueuePilot REST API.
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

async function request(method, path, body) {
    const headers = {};
    let payload;
    if (body instanceof FormData) {
        payload = body;
    } else if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(body);
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
    post: (path, body) => request('POST', path, body ?? {}),
    put: (path, body) => request('PUT', path, body),
    upload: file => {
        const form = new FormData();
        form.append('file', file);
        return request('POST', '/api/artifacts', form);
    },
};

export const errorText = error => (error instanceof ApiError ? error.details() : String(error));
