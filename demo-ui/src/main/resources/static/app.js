// ---------- helpers ----------

function esc(value) {
    const div = document.createElement('div');
    div.textContent = value ?? '';
    return div.innerHTML;
}

async function fetchJson(url, options) {
    const res = await fetch(url, options);
    if (!res.ok) {
        let message = `HTTP ${res.status}`;
        try {
            const body = await res.json();
            if (body.error) message = body.error;
            if (body.detail) message += ` — ${body.detail}`;
        } catch (ignored) { /* non-JSON error body */ }
        if (res.status === 428 && message === `HTTP ${res.status}`) {
            message = 'Credentials not configured — see Settings';
        }
        throw new Error(message);
    }
    if (res.status === 204) return null;
    return res.json();
}

// Options arrive as a single string; BETA delivers them newline-delimited
// (verified the hard way in ZAF #7). Comma is a fallback only.
function parseOptions(options) {
    if (!options) return [];
    const byNewline = options.split('\n').map(s => s.trim()).filter(Boolean);
    if (byNewline.length > 1) return byNewline;
    return options.split(',').map(s => s.trim()).filter(Boolean);
}

function chip(text, extraClass) {
    return `<span class="chip ${extraClass ?? ''}">${esc(text)}</span>`;
}

function statusChip(status) {
    return chip(status ?? '?', 'st-' + String(status ?? '').toLowerCase());
}

// ---------- state ----------

const state = {
    me: null,                 // UserContextDto once credentials work
    requests: [],
    selectedPartner: null,
    formTemplate: null,
    currentCaseToken: null,
    currentCaseEncrypted: false,  // the open case's `encrypted`, prechecks the Add note Encrypt box
    currentWebhookId: null,
    e2ee: {enabled: false},       // GET /api/settings `e2ee`; nothing E2EE renders while enabled is false
};

// ---------- tabs ----------

document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => showView(tab.dataset.view));
});

function showView(name) {
    document.querySelectorAll('.view').forEach(v => v.classList.add('hidden'));
    document.getElementById(`view-${name}`).classList.remove('hidden');
    document.querySelectorAll('.tab').forEach(t =>
        t.classList.toggle('active', t.dataset.view === name));
    if (name === 'dashboard') loadRequests();
    if (name === 'webhooks') loadWebhooks();
    if (name === 'settings') loadReceiverStorageIfShown();
}

// ---------- identity + environments ----------

async function refreshIdentity() {
    const badge = document.getElementById('auth-badge');
    const chip = document.getElementById('env-chip');
    const subtitle = document.getElementById('env-subtitle');
    try {
        const settings = await fetchJson('/api/settings');
        state.settings = settings;
        state.e2ee = settings.e2ee ?? {enabled: false};
        const active = settings.environments.find(e => e.key === settings.activeEnvironment);
        chip.textContent = active?.label ?? settings.activeEnvironment;
        subtitle.textContent = `TSANet Connect ${active?.label ?? ''} — ${active?.apiBaseUrl ?? ''}`;
        renderEnvSettings();
        if (!active?.configured) {
            state.me = null;
            badge.textContent = 'Not configured';
            badge.className = 'badge not-configured';
            return;
        }
        badge.textContent = 'Connecting...';
        badge.className = 'badge';
        const me = await fetchJson('/api/me');
        state.me = me;
        badge.textContent = `${me.companyName ?? 'Unknown company'} — ${me.email ?? me.username ?? ''}`;
        badge.className = 'badge configured';
    } catch (err) {
        state.me = null;
        badge.textContent = `Auth failed: ${err.message}`;
        badge.className = 'badge not-configured';
    }
}

function renderEnvSettings() {
    const host = document.getElementById('env-settings');
    if (!host || !state.settings) return;
    host.innerHTML = '';
    for (const env of state.settings.environments) {
        const isActive = env.key === state.settings.activeEnvironment;
        const card = document.createElement('div');
        card.className = 'env-card' + (isActive ? ' active-env' : '');
        const locked = state.settings.credentialsLocked === true;
        // Locked: who the demo is signed in as, the switch, and no form (tsanetgit/Connect_SDK#152).
        // Unlocked: the markup below is unchanged from before the lock existed.
        card.innerHTML = locked ? `
            <div class="env-head">
                <span class="env-name">${esc(env.label)}</span>
                <span class="env-url">${esc(env.apiBaseUrl)}</span>
                ${env.configured
                    ? chipHtml(`Signed in as ${env.username} (${env.mode === 'oauth' ? 'OAuth' : 'password'})`, 'st-approved')
                    : chipHtml('no credentials', 'st-pending')}
                <span class="spacer"></span>
                ${isActive ? chipHtml('ACTIVE', 'outbound') : ''}
            </div>
            <p class="muted">Credentials are managed by the operator of this demo.</p>
            ${isActive ? '' : `<div class="button-row"><button type="button" class="env-switch-btn" data-env="${esc(env.key)}">Make Active</button></div>`}
            <div class="receiver-storage" data-env="${esc(env.key)}" data-configured="${env.configured}"></div>
            <div class="e2ee-partners" data-env="${esc(env.key)}" data-locked="true"></div>
        ` : `
            <div class="env-head">
                <span class="env-name">${esc(env.label)}</span>
                <span class="env-url">${esc(env.apiBaseUrl)}</span>
                ${env.configured
                    ? chipHtml(`credentials: ${env.username}${env.mode === 'oauth' ? ' (OAuth)' : ''}`, 'st-approved')
                    : chipHtml('no credentials', 'st-pending')}
                <span class="spacer"></span>
                ${isActive ? chipHtml('ACTIVE', 'outbound') : ''}
            </div>
            <form class="env-cred-form" data-env="${esc(env.key)}">
                ${env.oauthAvailable ? `
                <div class="mode-row">
                    <label><input type="radio" name="mode" value="password" ${env.mode !== 'oauth' ? 'checked' : ''}> Password</label>
                    <label><input type="radio" name="mode" value="oauth" ${env.mode === 'oauth' ? 'checked' : ''}> OAuth (client credentials)</label>
                </div>` : ''}
                <div class="form-grid mode-password" ${env.mode === 'oauth' ? 'hidden' : ''}>
                    <label>Username
                        <input type="text" name="username" autocomplete="off">
                    </label>
                    <label>Password
                        <input type="password" name="password" autocomplete="off">
                    </label>
                </div>
                <div class="form-grid mode-oauth" ${env.mode === 'oauth' ? '' : 'hidden'}>
                    <label>Client ID
                        <input type="text" name="clientId" autocomplete="off">
                    </label>
                    <label>Client Secret
                        <input type="password" name="clientSecret" autocomplete="off">
                    </label>
                </div>
                <div class="button-row">
                    <button type="submit">Save Credentials</button>
                    <button type="button" class="secondary env-clear-btn" data-env="${esc(env.key)}">Clear</button>
                    ${isActive ? '' : `<button type="button" class="env-switch-btn" data-env="${esc(env.key)}">Make Active</button>`}
                </div>
            </form>
            <div class="receiver-storage" data-env="${esc(env.key)}" data-configured="${env.configured}"></div>
            <div class="e2ee-partners" data-env="${esc(env.key)}"></div>
        `;
        host.appendChild(card);
        renderE2eePartners(card.querySelector('.e2ee-partners'), env);
    }
    host.querySelectorAll('.env-cred-form').forEach(form =>
        form.addEventListener('submit', onSaveEnvCredentials));
    host.querySelectorAll('.env-cred-form input[name="mode"]').forEach(radio =>
        radio.addEventListener('change', event => {
            const form = event.target.closest('form');
            const oauth = form.mode.value === 'oauth';
            form.querySelector('.mode-password').hidden = oauth;
            form.querySelector('.mode-oauth').hidden = !oauth;
        }));
    host.querySelectorAll('.env-clear-btn').forEach(btn =>
        btn.addEventListener('click', () => onClearEnvCredentials(btn.dataset.env)));
    host.querySelectorAll('.env-switch-btn').forEach(btn =>
        btn.addEventListener('click', () => onSwitchEnvironment(btn.dataset.env)));
    loadReceiverStorageIfShown();
}

// ---------- E2EE partner keys (Settings) ----------
// Per environment: which partner companies the demo can encrypt for and verify from, each by a
// pinned JWKS URL or a pasted public JWK set. Shown only when the demo holds its own keys; with
// E2EE off the card is as it was. Values from the API are set through the DOM, not attributes.

// Locked (tsanet.demo.credentials-locked): the table and the JWKS URL render read-only, with no form and
// no Remove buttons; the server refuses the writes too, and the table comes from the seed variable.
function renderE2eePartners(box, env) {
    if (!box) return;
    if (!state.e2ee?.enabled) {
        box.innerHTML = '';
        return;
    }
    const locked = state.settings?.credentialsLocked === true;
    const jwksUrl = `${location.origin}${state.e2ee.jwksPath}`;
    const rows = (env.e2eePartners ?? []).map(p => `
        <tr>
            <td><code>${esc(p.companyId)}</code></td>
            <td>${p.source === 'url' ? `URL <code>${esc(p.jwksUrl)}</code>` : `pasted JWKS (${esc(p.kids.length)} key${p.kids.length === 1 ? '' : 's'}: <code>${esc(p.kids.join(', '))}</code>)`}</td>
            ${locked ? '' : `<td><button type="button" class="danger e2ee-remove-btn" data-company="${esc(p.companyId).replaceAll('"', '&quot;')}">Remove</button></td>`}
        </tr>`).join('');
    const table = rows
        ? `<table class="e2ee-table"><thead><tr><th>Company</th><th>Keys</th>${locked ? '' : '<th></th>'}</tr></thead><tbody>${rows}</tbody></table>`
        : '<p class="muted">No partner keys yet: Encrypt stays unchecked for every partner.</p>';
    if (locked) {
        box.innerHTML = `<h4>E2EE partner keys</h4>
            <p class="muted">The demo's public keys for a partner to pin: <code class="e2ee-own-url"></code>.
                The partner table is managed by the operator of this demo.</p>
            ${table}`;
        box.querySelector('.e2ee-own-url').textContent = jwksUrl;
        return;
    }
    box.innerHTML = `<h4>E2EE partner keys</h4>
        <p class="muted">The demo's public keys for a partner to pin: <code class="e2ee-own-url"></code>.
            Add each partner company you will exchange encrypted cases with, by its company id.</p>
        ${table}
        <form class="e2ee-partner-form">
            <div class="form-grid">
                <label>Partner company id
                    <input type="text" name="companyId" inputmode="numeric" autocomplete="off" required>
                </label>
                <label>Key source
                    <select name="source">
                        <option value="url">JWKS URL (https)</option>
                        <option value="jwks">Pasted public JWKS</option>
                    </select>
                </label>
            </div>
            <label class="e2ee-src-url">JWKS URL
                <input type="url" name="jwksUrl" placeholder="https://..." autocomplete="off">
            </label>
            <label class="e2ee-src-jwks" hidden>Public JWK set (JSON; a set with a private key is refused)
                <textarea name="jwks" rows="4" spellcheck="false"></textarea>
            </label>
            <div class="button-row">
                <button type="submit">Add / Replace</button>
            </div>
        </form>
        <p class="muted e2ee-result"></p>`;
    box.querySelector('.e2ee-own-url').textContent = jwksUrl;
    const form = box.querySelector('.e2ee-partner-form');
    form.source.addEventListener('change', () => {
        form.querySelector('.e2ee-src-url').hidden = form.source.value !== 'url';
        form.querySelector('.e2ee-src-jwks').hidden = form.source.value !== 'jwks';
    });
    form.addEventListener('submit', event => onSaveE2eePartner(event, box, env.key));
    box.querySelectorAll('.e2ee-remove-btn').forEach(btn =>
        btn.addEventListener('click', () => onRemoveE2eePartner(box, env.key, btn.dataset.company)));
}

async function onSaveE2eePartner(event, box, envKey) {
    event.preventDefault();
    const form = event.target;
    const result = box.querySelector('.e2ee-result');
    const companyId = form.companyId.value.trim();
    const body = form.source.value === 'url' ? {jwksUrl: form.jwksUrl.value.trim()} : {jwks: form.jwks.value};
    result.textContent = 'Saving...';
    try {
        const settings = await fetchJson(
            `/api/settings/${encodeURIComponent(envKey)}/e2ee-partners/${encodeURIComponent(companyId)}`,
            {method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
        state.settings = settings;
        state.e2ee = settings.e2ee ?? {enabled: false};
        renderE2eePartners(box, settings.environments.find(e => e.key === envKey));
        box.querySelector('.e2ee-result').textContent = `Saved the keys for company ${companyId}.`;
    } catch (err) {
        result.textContent = `Failed: ${err.message}`;
    }
}

async function onRemoveE2eePartner(box, envKey, companyId) {
    const result = box.querySelector('.e2ee-result');
    result.textContent = 'Removing...';
    try {
        await fetchJson(`/api/settings/${encodeURIComponent(envKey)}/e2ee-partners/${encodeURIComponent(companyId)}`,
            {method: 'DELETE'});
        const settings = await fetchJson('/api/settings');
        state.settings = settings;
        state.e2ee = settings.e2ee ?? {enabled: false};
        renderE2eePartners(box, settings.environments.find(e => e.key === envKey));
        box.querySelector('.e2ee-result').textContent = `Removed the keys for company ${companyId}.`;
    } catch (err) {
        result.textContent = `Failed: ${err.message}`;
    }
}

/** Whether the active environment's partner table has keys for `companyId`. */
function partnerHasKeys(companyId) {
    if (!state.e2ee?.enabled || companyId == null) return false;
    const active = state.settings?.environments.find(e => e.key === state.settings.activeEnvironment);
    return (active?.e2eePartners ?? []).some(p => p.companyId === String(companyId));
}

// The chip for one record's `e2ee` block: verified, refused at a step (and field), cannot verify
// (encrypted, but the demo had no profile for it), or none for plaintext.
function e2eeChip(e2ee) {
    if (!e2ee || e2ee.state === 'plaintext') return '';
    if (e2ee.state === 'verified') return chip('Verified (E2EE)', 'e2ee-verified');
    if (e2ee.state === 'unverified') return chip('Encrypted, cannot verify', 'e2ee-lock');
    return chip(`Refused at ${e2ee.step}${e2ee.field ? `: ${e2ee.field}` : ''}`, 'e2ee-refused');
}

// The ciphertext as the API returned it, behind a disclosure, for the interop check.
function cipherDisclosure(values) {
    const details = document.createElement('details');
    details.className = 'cipher';
    details.innerHTML = '<summary>Ciphertext as stored</summary>';
    for (const [name, value] of values) {
        if (value == null) continue;
        const pre = document.createElement('pre');
        pre.textContent = `${name}: ${value}`;
        details.appendChild(pre);
    }
    return details;
}

// ---------- V2 receiver storage (Settings) ----------
// Where files sent to the company signed in to each environment land. Values from the API are
// set through the DOM, never inside an attribute: esc() doesn't escape quotes.

const STORAGE_FIELDS = {
    s3: [['bucket', 'Bucket'], ['region', 'Region'], ['roleArn', 'Role ARN']],
    azureBlob: [['container', 'Container'], ['tenantId', 'Tenant ID'], ['storageAccountName', 'Storage account']],
};

const STORAGE_VERIFICATION = {
    PASSED: ['test passed', 'st-approved'],
    FAILED: ['test failed', 'st-rejected'],
    NEVER_TESTED: ['not tested', 'st-pending'],
};

// Each configured card's storage read signs in to that environment, so it runs only while Settings
// is showing: on opening it, and when the cards re-render there. Other tabs cause no traffic.
function loadReceiverStorageIfShown() {
    if (document.getElementById('view-settings')?.classList.contains('hidden')) return;
    document.querySelectorAll('#env-settings .receiver-storage').forEach(box => loadReceiverStorage(box));
}

async function loadReceiverStorage(box, resultText) {
    if (box.dataset.configured !== 'true') {
        box.innerHTML = `<h4>V2 receiver storage</h4>
            <p class="muted">Save credentials for this environment to see where files sent to its company land.</p>`;
        return;
    }
    box.innerHTML = '<h4>V2 receiver storage</h4><p class="muted">Loading...</p>';
    try {
        const status = await fetchJson(`/api/settings/${encodeURIComponent(box.dataset.env)}/receiver-storage`);
        renderReceiverStorage(box, status);
        if (resultText) box.querySelector('.storage-result').textContent = resultText;
    } catch (err) {
        box.innerHTML = '<h4>V2 receiver storage</h4><p class="muted storage-result"></p>';
        box.querySelector('.storage-result').textContent = `Couldn't read it: ${err.message}`;
    }
}

function receiverStorageSummary(config) {
    if (!config) {
        // PROVISIONAL(tsanetgit/Connect-API-Code#170): the platform answers 404 both for none and for a
        // configuration it doesn't show here (today a MongoDB one), so the card can't tell them apart.
        // Once #170's 2026-10-05 ask lands, the card says which.
        return '<p>No storage shown. Either none is registered, so files can\'t be delivered to this '
            + 'company on the direct path yet, or the company has a configuration this page can\'t show '
            + '(today, a MongoDB one). Registering a configuration replaces either.</p>';
    }
    const where = config.method === 's3'
        ? `AWS S3 bucket <code>${esc(config.bucket)}</code> in ${esc(config.region)}, through role <code>${esc(config.roleArn)}</code>`
        : `Azure Blob container <code>${esc(config.container)}</code> in storage account `
            + `<code>${esc(config.storageAccountName)}</code> (tenant <code>${esc(config.tenantId)}</code>)`;
    const prefix = config.prefix ? `, prefix <code>${esc(config.prefix)}</code>` : '';
    const [label, cls] = STORAGE_VERIFICATION[config.verification] ?? [config.verification, 'st-pending'];
    const when = config.lastVerifiedAt ? ` ${esc(new Date(config.lastVerifiedAt).toLocaleString())}` : '';
    const external = config.externalId
        ? `<p>External ID for the role's trust policy (<code>sts:ExternalId</code>): <code>${esc(config.externalId)}</code></p>`
        : '';
    return `<p>${where}${prefix}. ${chipHtml(label, cls)}${when}</p>${external}`;
}

function renderReceiverStorage(box, status) {
    const config = status.config;
    const method = config?.method ?? 's3';
    const testButton = config ? '<button type="button" class="secondary storage-test-btn">Test</button>' : '';
    const inputs = fields => fields.map(([name, label]) =>
        `<label>${esc(label)}<input type="text" name="${name}" autocomplete="off"></label>`).join('');
    const editor = status.editable ? `
        <form class="receiver-storage-form">
            <div class="mode-row">
                <label><input type="radio" name="method" value="s3"> AWS S3</label>
                <label><input type="radio" name="method" value="azureBlob"> Azure Blob</label>
            </div>
            <div class="form-grid storage-s3">${inputs(STORAGE_FIELDS.s3)}</div>
            <div class="form-grid storage-azureBlob">${inputs(STORAGE_FIELDS.azureBlob)}</div>
            <div class="form-grid">${inputs([['prefix', 'Prefix (optional)']])}</div>
            <div class="button-row">
                <button type="submit">Save Storage</button>
                ${testButton}
            </div>
        </form>`
        : `<p class="muted">Registering storage is turned off for this demo (<code>tsanet.demo.receiver-storage-editable</code>).</p>
           ${testButton ? `<div class="button-row">${testButton}</div>` : ''}`;
    box.innerHTML = `<h4>V2 receiver storage</h4>${receiverStorageSummary(config)}${editor}
        <p class="muted storage-result"></p>`;

    const form = box.querySelector('.receiver-storage-form');
    if (form) {
        form.querySelector(`input[name="method"][value="${method}"]`).checked = true;
        for (const [name] of [...STORAGE_FIELDS.s3, ...STORAGE_FIELDS.azureBlob, ['prefix']]) {
            form.elements[name].value = config?.[name] ?? '';
        }
        showStorageFields(form);
        form.querySelectorAll('input[name="method"]').forEach(radio =>
            radio.addEventListener('change', () => showStorageFields(form)));
        form.addEventListener('submit', event => onSaveReceiverStorage(event, box));
    }
    box.querySelector('.storage-test-btn')?.addEventListener('click', () => onTestReceiverStorage(box));
}

function showStorageFields(form) {
    const method = form.method.value;
    form.querySelector('.storage-s3').hidden = method !== 's3';
    form.querySelector('.storage-azureBlob').hidden = method !== 'azureBlob';
}

async function onSaveReceiverStorage(event, box) {
    event.preventDefault();
    const form = event.target;
    const method = form.method.value;
    const body = {method, prefix: form.prefix.value};
    for (const [name] of STORAGE_FIELDS[method]) body[name] = form.elements[name].value.trim();
    const result = box.querySelector('.storage-result');
    result.textContent = 'Saving...';
    try {
        const status = await fetchJson(`/api/settings/${encodeURIComponent(box.dataset.env)}/receiver-storage`, {
            method: 'PUT',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body),
        });
        renderReceiverStorage(box, status);
        box.querySelector('.storage-result').textContent = method === 's3'
            ? 'Saved. Add the external ID to the role\'s trust policy, then Test.'
            : 'Saved. Test it to check TSANet can write there.';
    } catch (err) {
        result.textContent = `Failed: ${err.message}`;
    }
}

async function onTestReceiverStorage(box) {
    const result = box.querySelector('.storage-result');
    result.textContent = 'Testing...';
    try {
        const test = await fetchJson(`/api/settings/${encodeURIComponent(box.dataset.env)}/receiver-storage/test`,
            {method: 'POST'});
        await loadReceiverStorage(box, test.verified
            ? 'Test passed: TSANet can write to this storage.'
            : `Test failed: ${test.detail ?? 'no reason given'}`);
    } catch (err) {
        result.textContent = `Failed: ${err.message}`;
    }
}

function chipHtml(text, cls) {
    return `<span class="chip ${cls}">${esc(text)}</span>`;
}

async function onSaveEnvCredentials(event) {
    event.preventDefault();
    const form = event.target;
    const status = document.getElementById('settings-status');
    status.textContent = 'Saving...';
    try {
        const mode = form.mode && form.mode.value === 'oauth' ? 'oauth' : 'password';
        const body = mode === 'oauth'
            ? {mode, clientId: form.clientId.value, clientSecret: form.clientSecret.value}
            : {mode, username: form.username.value, password: form.password.value};
        await fetchJson(`/api/settings/${encodeURIComponent(form.dataset.env)}/credentials`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body),
        });
        status.textContent = 'Credentials saved.';
        await refreshIdentity();
    } catch (err) {
        status.textContent = `Failed: ${err.message}`;
    }
}

async function onClearEnvCredentials(envKey) {
    const status = document.getElementById('settings-status');
    status.textContent = 'Clearing...';
    try {
        await fetchJson(`/api/settings/${encodeURIComponent(envKey)}/credentials`, {method: 'DELETE'});
        status.textContent = 'Credentials cleared.';
        await refreshIdentity();
    } catch (err) {
        status.textContent = `Failed: ${err.message}`;
    }
}

async function onSwitchEnvironment(envKey) {
    const status = document.getElementById('settings-status');
    status.textContent = `Switching to ${envKey}...`;
    try {
        await fetchJson('/api/settings/environment', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({environment: envKey}),
        });
        status.textContent = `Active environment: ${envKey}.`;
        state.me = null;
        await refreshIdentity();
        await loadRequests();
    } catch (err) {
        status.textContent = `Failed: ${err.message}`;
    }
}

function directionOf(request) {
    if (!state.me?.companyId) return null;
    if (request.submitCompanyId === state.me.companyId) return 'outbound';
    if (request.receiveCompanyId === state.me.companyId) return 'inbound';
    return null;
}

function partnerNameOf(request) {
    const dir = directionOf(request);
    if (dir === 'outbound') return request.receiveCompanyName;
    if (dir === 'inbound') return request.submitCompanyName;
    return `${request.submitCompanyName ?? '?'} → ${request.receiveCompanyName ?? '?'}`;
}

// ---------- dashboard ----------

async function loadRequests() {
    const tbody = document.querySelector('#requests-table tbody');
    tbody.innerHTML = '<tr><td colspan="6">Loading...</td></tr>';
    try {
        if (!state.me) await refreshIdentity();
        state.requests = await fetchJson('/api/requests');
        renderRequests();
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="6">Failed to load: ${esc(err.message)}</td></tr>`;
    }
}

function renderRequests() {
    const tbody = document.querySelector('#requests-table tbody');
    const filter = document.getElementById('direction-filter').value;
    const rows = state.requests.filter(r => {
        if (filter === 'all') return true;
        return directionOf(r) === filter;
    });
    if (rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6">No cases found.</td></tr>';
        return;
    }
    tbody.innerHTML = '';
    for (const r of rows) {
        const dir = directionOf(r);
        const tr = document.createElement('tr');
        tr.className = 'clickable';
        tr.innerHTML = `
            <td>${esc(r.id)}</td>
            <td>${dir ? chip(dir, dir) : ''}</td>
            <td>${statusChip(r.status)}</td>
            <td>${state.e2ee?.enabled && r.encrypted === true ? chip('\uD83D\uDD12 Encrypted', 'e2ee-lock') : esc(r.summary)}</td>
            <td>${esc(partnerNameOf(r))}</td>
            <td>${esc(r.updatedAt)}</td>
        `;
        tr.addEventListener('click', () => openCase(r.token));
        tbody.appendChild(tr);
    }
}

document.getElementById('refresh-btn').addEventListener('click', loadRequests);
document.getElementById('direction-filter').addEventListener('change', renderRequests);

// ---------- case detail ----------

document.getElementById('back-to-dashboard').addEventListener('click', () => showView('dashboard'));

async function openCase(token) {
    state.currentCaseToken = token;
    document.querySelectorAll('.view').forEach(v => v.classList.add('hidden'));
    document.getElementById('view-case').classList.remove('hidden');
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.getElementById('case-title').textContent = 'Loading case...';
    document.getElementById('case-summary').innerHTML = '';
    document.getElementById('case-notes').innerHTML = '';
    document.getElementById('case-responses').innerHTML = '';
    document.getElementById('case-actions').innerHTML = '';
    document.getElementById('action-form-host').innerHTML = '';
    document.getElementById('case-action-status').textContent = '';
    document.getElementById('attach-config').textContent = '';
    document.getElementById('attach-form').classList.add('hidden');
    document.getElementById('attach-status').textContent = '';
    try {
        const detail = await fetchJson(`/api/requests/${encodeURIComponent(token)}`);
        renderCase(detail);
    } catch (err) {
        document.getElementById('case-title').textContent = 'Failed to load case';
        document.getElementById('case-summary').innerHTML = `<p class="status">${esc(err.message)}</p>`;
    }
}

function kv(key, value, full) {
    return `<div class="kv ${full ? 'full' : ''}"><div class="k">${esc(key)}</div><div class="v">${value}</div></div>`;
}

function renderCase(detail) {
    const s = detail.status;
    const dir = directionOf(s);
    state.currentCaseEncrypted = s.encrypted === true;
    // The e2ee block exists only while the demo holds keys; without it the page is as before #149.
    const e2ee = detail.e2ee;
    const encrypted = e2ee && e2ee.state !== 'plaintext';
    const summaryText = encrypted ? (e2ee.state === 'verified' ? e2ee.summary : 'Encrypted') : s.summary;
    document.getElementById('case-title').textContent = `Case #${s.id ?? '?'} — ${summaryText ?? ''}`;
    const rows = [
        kv('Status', statusChip(s.status) + (encrypted ? ' ' + e2eeChip(e2ee) : '')),
        kv('Direction', dir ? chip(dir, dir) : '—'),
        kv('Submitted by', esc(s.submitCompanyName)),
        kv('Received by', esc(s.receiveCompanyName)),
        kv('Created', esc(s.createdAt)),
        kv('Updated', esc(s.updatedAt)),
        kv('Case token', `<code>${esc(s.token)}</code>`, true),
    ];
    if (encrypted && e2ee.state === 'verified') {
        // Decrypted values in place of the ciphertext; a refusal shows the chip and no value.
        rows.push(kv('Description', esc(e2ee.description), true));
        // The value and its fieldId are verified; the name is the stored record's unsigned form
        // metadata, so the authenticated fieldId stays beside it. kv escapes the label.
        const names = e2ee.customFieldNames ?? {};
        for (const [fieldId, value] of Object.entries(e2ee.customFields ?? {})) {
            rows.push(kv(names[fieldId] ? `${names[fieldId]} (${fieldId})` : `Custom field ${fieldId}`, esc(value), true));
        }
    } else if (encrypted && e2ee.reason) {
        // Refused or cannot verify: the module's or the demo's reason, which names a step or a cause, never a value.
        rows.push(kv('E2EE', esc(e2ee.reason), true));
    }
    const summaryHost = document.getElementById('case-summary');
    summaryHost.innerHTML = rows.join('');
    if (encrypted && e2ee.state === 'verified') {
        summaryHost.appendChild(cipherDisclosure([
            ['problemSummary', s.summary], ['problemDescription', s.description],
            ...(s.customFields ?? []).map(f => [`customFields[${f.id}] ${f.fieldName ?? ''}`, f.value]),
        ]));
    }

    renderCaseActions(dir);
    renderTimeline('case-notes', detail.notes.map(n => {
        const ne = n.e2ee && n.e2ee.state !== 'plaintext' ? n.e2ee : null;
        return {
            title: ne ? (ne.state === 'verified' ? ne.summary : 'Encrypted note') : n.summary,
            // type label and direction exactly as the API sent them; a note without them shows neither.
            meta: `${n.creatorName ?? n.creatorUsername ?? '?'} (${n.companyName ?? '?'}) · ${n.priority ?? ''}`
                + [n.typeDisplayName ?? n.type, n.direction].filter(Boolean).map(v => ` · ${v}`).join('')
                + ` · ${n.createdAt ?? ''}`,
            body: ne ? (ne.state === 'verified' ? ne.description : (ne.reason ?? '')) : n.description,
            chip: e2eeChip(ne),
            cipher: ne && ne.state === 'verified' ? [['summary', n.summary], ['description', n.description]] : null,
        };
    }), 'No notes yet.');
    renderTimeline('case-responses', detail.responses.map(r => ({
        title: r.type,
        meta: `${r.engineerName ?? ''} ${r.engineerEmail ? '<' + r.engineerEmail + '>' : ''} · ${r.createdAt ?? ''}`,
        body: r.nextSteps ?? r.caseNumber ?? '',
    })), 'No responses yet.');
}

// System-generated notes (e.g. "Case accepted.") arrive with HTML bodies.
// Render them readably WITHOUT trusting the markup: rebuild only an allowlist
// of formatting tags as fresh attribute-free nodes; unknown tags are unwrapped
// to their text. Notes can originate from partner companies — treat as hostile.
const SAFE_BODY_TAGS = new Set(['P', 'STRONG', 'B', 'EM', 'I', 'U', 'BR', 'UL', 'OL', 'LI']);

function safeBodyNodes(html) {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const out = document.createDocumentFragment();
    (function copy(from, to) {
        for (const node of from.childNodes) {
            if (node.nodeType === Node.TEXT_NODE) {
                to.appendChild(document.createTextNode(node.textContent));
            } else if (node.nodeType === Node.ELEMENT_NODE) {
                if (SAFE_BODY_TAGS.has(node.tagName)) {
                    const el = document.createElement(node.tagName); // no attributes copied
                    to.appendChild(el);
                    copy(node, el);
                } else {
                    copy(node, to); // unwrap unknown/unsafe tags, keep their text
                }
            }
        }
    })(doc.body, out);
    return out;
}

function looksLikeHtml(text) {
    return /<\/?[a-z][^>]*>/i.test(text ?? '');
}

function renderTimeline(hostId, items, emptyText) {
    const host = document.getElementById(hostId);
    host.innerHTML = '';
    if (!items.length) {
        host.innerHTML = `<p class="muted">${esc(emptyText)}</p>`;
        return;
    }
    for (const i of items) {
        const item = document.createElement('div');
        item.className = 'timeline-item';
        item.innerHTML = `
            <div class="t-head"><span class="t-title">${esc(i.title)}${i.chip ? ' ' + i.chip : ''}</span><span>${esc(i.meta)}</span></div>
            <div class="t-body"></div>
        `;
        const body = item.querySelector('.t-body');
        if (looksLikeHtml(i.body)) {
            body.classList.add('t-body-rich');
            body.appendChild(safeBodyNodes(i.body));
        } else {
            body.textContent = i.body ?? '';
        }
        if (i.cipher) item.appendChild(cipherDisclosure(i.cipher));
        host.appendChild(item);
    }
}

// Receiver-side actions (approve/reject/request-info) vs submitter-side
// (respond-info/close). The API enforces which are valid for the case's
// current state — the demo shows the relevant set by direction and lets
// upstream validation speak for itself.
function renderCaseActions(dir) {
    const host = document.getElementById('case-actions');
    host.innerHTML = '';
    const actions = [];
    if (dir === 'inbound' || dir === null) {
        actions.push(['Approve', () => showActionForm('approve')]);
        actions.push(['Reject', () => showActionForm('reject')]);
        actions.push(['Request Info', () => showActionForm('request-info')]);
    }
    if (dir === 'outbound' || dir === null) {
        actions.push(['Respond to Info Request', () => showActionForm('respond-info')]);
        actions.push(['Close Case', () => runClose()]);
    }
    actions.push(['Add Note', () => showActionForm('note')]);
    for (const [label, handler] of actions) {
        const btn = document.createElement('button');
        btn.textContent = label;
        if (label === 'Reject') btn.className = 'danger';
        if (label === 'Close Case' || label === 'Add Note') btn.className = 'secondary';
        btn.addEventListener('click', handler);
        host.appendChild(btn);
    }
}

const ACTION_FORMS = {
    'approve': {
        title: 'Approve collaboration request',
        fields: [
            ['caseNumber', 'Your internal case number', 'text'],
            ['engineerName', 'Engineer name', 'text'],
            ['engineerEmail', 'Engineer email', 'email'],
            ['engineerPhone', 'Engineer phone', 'text'],
            ['text', 'Next steps', 'textarea'],
        ],
    },
    'reject': {
        title: 'Reject collaboration request',
        fields: [
            ['engineerName', 'Engineer name', 'text'],
            ['engineerEmail', 'Engineer email', 'email'],
            ['engineerPhone', 'Engineer phone', 'text'],
            ['text', 'Rejection reason', 'textarea'],
        ],
    },
    'request-info': {
        title: 'Request additional information',
        fields: [
            ['engineerName', 'Engineer name', 'text'],
            ['engineerEmail', 'Engineer email', 'email'],
            ['engineerPhone', 'Engineer phone', 'text'],
            ['text', 'Information requested', 'textarea'],
        ],
    },
    'respond-info': {
        title: 'Respond to information request',
        fields: [
            ['text', 'Response', 'textarea'],
        ],
    },
    'note': {
        title: 'Add note',
        fields: [
            ['summary', 'Summary', 'text'],
            ['description', 'Description', 'textarea'],
            ['priority', 'Priority', 'select', ['LOW', 'MEDIUM', 'HIGH']],
            ['type', 'Type', 'select', noteTypeOptions],
        ],
    },
};

// The Add note form's Type options: "No type" first, so it is the default, then the server's
// list in its order. A blank type creates a note with no type; there is no default type.
async function noteTypeOptions() {
    const none = {value: '', label: 'No type'};
    try {
        const types = await fetchJson('/api/note-types');
        return [none, ...types.map(t => ({value: t.code, label: t.displayName}))];
    } catch (err) {
        console.warn('Note types unavailable; offering only "No type".', err);
        return [none];
    }
}

// A select's options are a list, or an async function returning one; each option is a string
// (value and label alike) or {value, label}.
async function showActionForm(action) {
    const spec = ACTION_FORMS[action];
    const host = document.getElementById('action-form-host');
    const fieldsHtml = (await Promise.all(spec.fields.map(async ([name, label, type, options]) => {
        if (type === 'textarea') {
            return `<label>${esc(label)}<textarea name="${name}" rows="3" required></textarea></label>`;
        }
        if (type === 'select') {
            const list = typeof options === 'function' ? await options() : options;
            const opts = list.map(o => typeof o === 'string' ? {value: o, label: o} : o)
                // esc() leaves quotes alone, and a value sits inside a quoted attribute.
                .map(o => `<option value="${esc(o.value).replaceAll('"', '&quot;')}">${esc(o.label)}</option>`).join('');
            return `<label>${esc(label)}<select name="${name}">${opts}</select></label>`;
        }
        return `<label>${esc(label)}<input type="${type}" name="${name}" required></label>`;
    }))).join('');
    // Add note offers Encrypt (E2EE) only while the demo holds keys, prechecked on an encrypted case.
    const encryptRow = action === 'note' && state.e2ee?.enabled
        ? `<label class="check-row"><input type="checkbox" name="encrypt" ${state.currentCaseEncrypted ? 'checked' : ''}> Encrypt (E2EE)
           <span class="muted">${state.currentCaseEncrypted ? 'This case is encrypted; the note is encrypted for the other party.' : 'This case is plaintext.'}</span></label>`
        : '';
    host.innerHTML = `
        <form class="action-form" id="current-action-form">
            <h3>${esc(spec.title)}</h3>
            ${fieldsHtml}
            ${encryptRow}
            <div class="button-row">
                <button type="submit">Submit</button>
                <button type="button" class="secondary" id="cancel-action">Cancel</button>
            </div>
        </form>
    `;
    document.getElementById('cancel-action').addEventListener('click', () => host.innerHTML = '');
    document.getElementById('current-action-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const form = event.target;
        const body = {};
        for (const [name] of spec.fields) body[name] = form[name].value;
        if (form.encrypt) body.encrypt = form.encrypt.checked;
        const status = document.getElementById('case-action-status');
        status.textContent = 'Submitting...';
        try {
            const url = action === 'note'
                ? `/api/requests/${encodeURIComponent(state.currentCaseToken)}/notes`
                : `/api/requests/${encodeURIComponent(state.currentCaseToken)}/${action}`;
            await fetchJson(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body),
            });
            status.textContent = `${spec.title}: done.`;
            host.innerHTML = '';
            await openCase(state.currentCaseToken);
        } catch (err) {
            status.textContent = `Failed: ${err.message}`;
        }
    });
}

async function runClose() {
    const status = document.getElementById('case-action-status');
    status.textContent = 'Closing...';
    try {
        await fetchJson(`/api/requests/${encodeURIComponent(state.currentCaseToken)}/close`, {method: 'POST'});
        status.textContent = 'Case closed.';
        await openCase(state.currentCaseToken);
    } catch (err) {
        status.textContent = `Failed: ${err.message}`;
    }
}

// ---------- attachments ----------

document.getElementById('load-attach-config').addEventListener('click', async () => {
    const host = document.getElementById('attach-config');
    host.textContent = 'Loading attachment config...';
    try {
        const config = await fetchJson(
            `/api/requests/${encodeURIComponent(state.currentCaseToken)}/attachments/config`);
        host.innerHTML = `<pre>${esc(JSON.stringify(config, null, 2))}</pre>`;
        document.getElementById('attach-form').classList.remove('hidden');
    } catch (err) {
        host.textContent = `Failed: ${err.message}`;
    }
});

document.getElementById('attach-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.target;
    const status = document.getElementById('attach-status');
    const data = new FormData();
    data.append('description', form.description.value);
    for (const file of form.files.files) data.append('files', file);
    status.textContent = 'Forwarding...';
    try {
        const results = await fetchJson(
            `/api/requests/${encodeURIComponent(state.currentCaseToken)}/attachments`,
            {method: 'POST', body: data});
        status.textContent = `Forwarded ${results.length} attachment(s).`;
        form.reset();
    } catch (err) {
        status.textContent = `Failed: ${err.message}`;
    }
});

// ---------- attachments: direct delivery (V2) ----------

function formatBytes(n) {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`;
    if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MiB`;
    return `${(n / 1024 / 1024 / 1024).toFixed(2)} GiB`;
}

function renderUploadState(status, s) {
    if (s.phase === 'starting') {
        status.textContent = `Requesting a grant for ${s.fileName}...`;
        return;
    }
    if (s.phase === 'uploading') {
        status.textContent = `${s.mode}: ${s.partsDone}/${s.partsTotal} parts, `
            + `${formatBytes(s.bytesSent)} of ${formatBytes(s.bytesTotal)}`;
        return;
    }
    if (s.phase === 'failed') {
        status.textContent = `Failed: ${s.error}`;
        return;
    }
    const o = s.outcome || {};
    status.textContent = `Platform outcome: grant ${o.grantId} ${String(o.status || '').toLowerCase()}`
        + ` (${formatBytes(o.expectedSizeBytes || 0)}, ${s.mode})`;
}

document.getElementById('attach-v2-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.target;
    const status = document.getElementById('attach-v2-status');
    const data = new FormData();
    data.append('file', form.file.files[0]);
    status.textContent = 'Uploading to the demo...';
    try {
        const {uploadId} = await fetchJson(
            `/api/requests/${encodeURIComponent(state.currentCaseToken)}/attachments/v2`,
            {method: 'POST', body: data});
        form.reset();
        const poll = async () => {
            const s = await fetchJson(`/api/uploads/${encodeURIComponent(uploadId)}`);
            renderUploadState(status, s);
            if (s.phase !== 'done' && s.phase !== 'failed') setTimeout(poll, 500);
        };
        await poll();
    } catch (err) {
        status.textContent = `Failed: ${err.message}`;
    }
});

// ---------- new collaboration ----------

document.getElementById('partner-search-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const host = document.getElementById('partner-results');
    host.innerHTML = '<p class="muted">Searching...</p>';
    try {
        const term = document.getElementById('partner-search-input').value;
        const partners = await fetchJson(`/api/partners?q=${encodeURIComponent(term)}`);
        if (!partners.length) {
            host.innerHTML = '<p class="muted">No partners matched.</p>';
            return;
        }
        host.innerHTML = '';
        for (const p of partners) {
            const card = document.createElement('div');
            card.className = 'partner-card';
            card.innerHTML = `
                <div>
                    <div class="p-name">${esc(p.companyName ?? p.label)}</div>
                    <div class="p-dept">${esc(p.departmentName ?? '')} ${p.documentId ? `· form #${esc(p.documentId)}` : ''}</div>
                </div>
            `;
            const btn = document.createElement('button');
            btn.textContent = 'Select';
            btn.addEventListener('click', () => selectPartner(p));
            card.appendChild(btn);
            host.appendChild(card);
        }
    } catch (err) {
        host.innerHTML = `<p class="status">Failed: ${esc(err.message)}</p>`;
    }
});

async function selectPartner(partner) {
    state.selectedPartner = partner;
    const panel = document.getElementById('form-panel');
    const label = document.getElementById('selected-partner');
    const customHost = document.getElementById('custom-fields');
    panel.classList.remove('hidden');
    label.textContent = `Loading process form for ${partner.companyName ?? partner.label}...`;
    customHost.innerHTML = '';
    try {
        const params = new URLSearchParams();
        if (partner.documentId != null) params.set('documentId', partner.documentId);
        else if (partner.departmentId != null) params.set('departmentId', partner.departmentId);
        else params.set('companyId', partner.companyId);
        const template = await fetchJson(`/api/partners/form?${params}`);
        state.formTemplate = template;
        label.textContent =
            `${partner.companyName ?? partner.label}` +
            (partner.departmentName ? ` / ${partner.departmentName}` : '') +
            ` — ${template.fields?.length ?? 0} partner-defined field(s)`;
        renderCustomFields(template);
        renderEncryptRow(template.receiverCompanyId ?? partner.companyId);
        panel.scrollIntoView({behavior: 'smooth'});
    } catch (err) {
        label.textContent = `Failed to load form: ${err.message}`;
        state.formTemplate = null;
        renderEncryptRow(null);
    }
}

// Shown only while the demo holds keys; prechecked when the partner's company has a key entry in
// the active environment, and the hint says which. Checked while the demo holds no keys is a 403 from the server.
function renderEncryptRow(partnerCompanyId) {
    const row = document.getElementById('encrypt-row');
    const hint = document.getElementById('encrypt-hint');
    const box = row.querySelector('input[name="encrypt"]');
    if (!state.e2ee?.enabled || partnerCompanyId == null) {
        row.classList.add('hidden');
        box.checked = false;
        return;
    }
    const hasKeys = partnerHasKeys(partnerCompanyId);
    box.checked = hasKeys;
    hint.textContent = hasKeys
        ? `Company ${partnerCompanyId} has a key entry in ${state.settings.activeEnvironment.toUpperCase()}; the summary, description and custom-field values are encrypted for it.`
        : `Company ${partnerCompanyId} has no key entry in ${state.settings.activeEnvironment.toUpperCase()}: add one under Settings before encrypting.`;
    row.classList.remove('hidden');
}

function renderCustomFields(template) {
    const host = document.getElementById('custom-fields');
    const fields = [...(template.fields ?? [])]
        .sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0));
    if (!fields.length) {
        host.innerHTML = '<p class="muted">This partner has no custom fields on their process form.</p>';
        return;
    }
    let currentSection = null;
    let html = '';
    for (const f of fields) {
        if (f.section && f.section !== currentSection) {
            currentSection = f.section;
            // Section arrives as an enum-ish string (e.g. COMMON_CUSTOMER_SECTION)
            const pretty = currentSection.replace(/_/g, ' ').toLowerCase()
                .replace(/\b\w/g, c => c.toUpperCase());
            html += `<h3>${esc(pretty)}</h3>`;
        }
        const req = f.required ? 'required' : '';
        const name = `custom-${f.fieldId}`;
        const type = String(f.type ?? '').toLowerCase();
        const options = parseOptions(f.options);
        if (options.length && (type.includes('select') || type.includes('drop') || type.includes('option') || type.includes('list'))) {
            const opts = ['<option value="">-- select --</option>',
                ...options.map(o => `<option>${esc(o)}</option>`)].join('');
            html += `<label>${esc(f.label)}${f.required ? ' *' : ''}<select name="${name}" ${req}>${opts}</select></label>`;
        } else if (type.includes('textarea') || type.includes('multi')) {
            html += `<label>${esc(f.label)}${f.required ? ' *' : ''}<textarea name="${name}" rows="3" ${req}></textarea></label>`;
        } else if (type.includes('date')) {
            html += `<label>${esc(f.label)}${f.required ? ' *' : ''}<input type="date" name="${name}" ${req}></label>`;
        } else if (type.includes('number') || type.includes('int')) {
            html += `<label>${esc(f.label)}${f.required ? ' *' : ''}<input type="number" name="${name}" ${req}></label>`;
        } else {
            html += `<label>${esc(f.label)}${f.required ? ' *' : ''}<input type="text" name="${name}" ${req}></label>`;
        }
    }
    host.innerHTML = html;
}

document.getElementById('collab-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.target;
    const status = document.getElementById('collab-status');
    if (!state.formTemplate) {
        status.textContent = 'Select a partner first.';
        return;
    }
    const customFieldValues = {};
    for (const f of state.formTemplate.fields ?? []) {
        const input = form[`custom-${f.fieldId}`];
        if (input && input.value !== '') customFieldValues[f.fieldId] = input.value;
    }
    status.textContent = 'Submitting collaboration request...';
    try {
        const created = await fetchJson('/api/requests', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                formTemplate: state.formTemplate,
                // The company to encrypt for: a template fetched by document names no receiver.
                receiverCompanyId: state.formTemplate.receiverCompanyId ?? state.selectedPartner?.companyId ?? null,
                caseNumber: form.caseNumber.value,
                summary: form.summary.value,
                description: form.description.value,
                customFieldValues,
                encrypt: state.e2ee?.enabled ? form.encrypt.checked : false,
            }),
        });
        status.innerHTML = `Created case #${esc(created.id)} (${statusChip(created.status)}). `;
        const link = document.createElement('a');
        link.href = '#';
        link.textContent = 'Open case →';
        link.addEventListener('click', (e) => {
            e.preventDefault();
            openCase(created.token);
        });
        status.appendChild(link);
        form.reset();
        // reset() clears the Encrypt box too; the partner is still selected, so its precheck comes back.
        renderEncryptRow(state.formTemplate.receiverCompanyId ?? state.selectedPartner?.companyId ?? null);
    } catch (err) {
        status.textContent = `Failed: ${err.message}`;
    }
});

// ---------- webhooks ----------

async function loadWebhooks() {
    const tbody = document.querySelector('#webhooks-table tbody');
    tbody.innerHTML = '<tr><td colspan="5">Loading...</td></tr>';
    document.getElementById('webhook-deliveries').classList.add('hidden');
    try {
        const subs = await fetchJson('/api/webhooks');
        if (!subs.length) {
            tbody.innerHTML = '<tr><td colspan="5">No subscriptions.</td></tr>';
            return;
        }
        tbody.innerHTML = '';
        for (const s of subs) {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${esc(s.id)}</td>
                <td>${esc(s.callbackUrl)}</td>
                <td>${esc(s.eventTypes)}</td>
                <td>${s.active ? chip('active', 'st-approved') : chip('inactive', 'st-closed')}</td>
            `;
            const td = document.createElement('td');
            const view = document.createElement('button');
            view.textContent = 'Deliveries';
            view.className = 'secondary';
            view.addEventListener('click', () => loadDeliveries(s.id));
            const del = document.createElement('button');
            del.textContent = 'Delete';
            del.className = 'danger';
            del.style.marginLeft = '0.5rem';
            del.addEventListener('click', async () => {
                if (!confirm(`Delete webhook subscription #${s.id}?`)) return;
                try {
                    await fetchJson(`/api/webhooks/${s.id}`, {method: 'DELETE'});
                    await loadWebhooks();
                } catch (err) {
                    alert(`Failed: ${err.message}`);
                }
            });
            td.append(view, del);
            tr.appendChild(td);
            tbody.appendChild(tr);
        }
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="5">Failed to load: ${esc(err.message)}</td></tr>`;
    }
}

async function loadDeliveries(subscriptionId) {
    const wrap = document.getElementById('webhook-deliveries');
    const tbody = document.querySelector('#deliveries-table tbody');
    wrap.classList.remove('hidden');
    document.getElementById('deliveries-sub').textContent = `— subscription #${subscriptionId}`;
    tbody.innerHTML = '<tr><td colspan="5">Loading...</td></tr>';
    try {
        const page = await fetchJson(`/api/webhooks/${subscriptionId}/deliveries?page=0&size=20`);
        const items = page.content ?? page.items ?? [];
        if (!items.length) {
            tbody.innerHTML = '<tr><td colspan="5">No deliveries recorded.</td></tr>';
            return;
        }
        tbody.innerHTML = items.map(d => `
            <tr>
                <td>${esc(d.eventType)}</td>
                <td>${esc(d.httpStatus)}</td>
                <td>${esc(d.attemptNumber)}</td>
                <td>${d.success ? chip('yes', 'st-approved') : chip('no', 'st-rejected')}</td>
                <td>${esc(d.createdAt)}</td>
            </tr>
        `).join('');
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="5">Failed: ${esc(err.message)}</td></tr>`;
    }
}

document.getElementById('refresh-webhooks').addEventListener('click', loadWebhooks);

document.getElementById('webhook-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = event.target;
    const status = document.getElementById('webhook-status');
    status.textContent = 'Creating...';
    try {
        const eventTypes = form.eventTypes.value.split(',').map(s => s.trim()).filter(Boolean);
        await fetchJson('/api/webhooks', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({callbackUrl: form.callbackUrl.value, eventTypes}),
        });
        status.textContent = 'Subscription created.';
        form.reset();
        await loadWebhooks();
    } catch (err) {
        status.textContent = `Failed: ${err.message}`;
    }
});

// ---------- init ----------

refreshIdentity().then(loadRequests);
