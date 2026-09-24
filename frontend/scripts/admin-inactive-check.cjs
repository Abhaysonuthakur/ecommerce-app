/**
 * Targeted check for the admin "Include deactivated" fix.
 *
 * The full render-check only proves pages mount for an anonymous visitor. This one logs in as
 * an admin and drives the toggle, because the bug it guards against was invisible from the
 * outside: the checkbox looked correct, the request succeeded, and the list quietly contained
 * the wrong rows.
 *
 * What went wrong before: the toggle sent `active: undefined`, which the server reads as
 * `active = true`, so "show inactive" returned the storefront list and hid the withdrawn
 * product it existed to reveal.
 *
 * ---------------------------------------------------------------------------------------
 * ★ WHY THIS SCRIPT PROVISIONS ITS OWN FIXTURE
 *
 * The first version of this check asserted `after.shown >= before.shown`. That assertion is
 * TRUE WHEN THE TOGGLE DOES NOTHING AT ALL - which is precisely the bug it was written to
 * catch. It only failed on a *decrease*, and a no-op toggle never decreases anything. It
 * passed for months on an empty fixture: with no withdrawn product in the database, both
 * readings were 19 and "19 >= 19" is trivially satisfied.
 *
 * A check that cannot fail on its own bug is not a check. So this version:
 *   1. creates a product through the API and deactivates it, so a withdrawn row ALWAYS exists;
 *   2. asserts the count moves by EXACTLY +1, not ">= before";
 *   3. asserts the withdrawn product's name is absent before the toggle and present after -
 *      the count could in principle move for an unrelated reason, the name cannot;
 *   4. deletes the probe in a `finally`, so the fixture never accumulates and a failure does
 *      not leave litter behind.
 *
 * The fixture is created through the same public API an admin uses, so this also exercises
 * create + deactivate + delete end to end.
 * ---------------------------------------------------------------------------------------
 *
 * Usage: node scripts/admin-inactive-check.cjs "C:\\path\\to\\chrome.exe" [apiBase]
 */

const CHROME = process.argv[2]
const API = (process.argv[3] || 'http://localhost:8080').replace(/\/+$/, '')
const PORT = 9334
const BASE = 'http://localhost:5173'

if (!CHROME) {
  console.error('usage: node scripts/admin-inactive-check.cjs "<path to chrome.exe>" [apiBase]')
  process.exit(2)
}

const PROBE_NAME = `ZZWithdrawnProbe${Date.now()}`

const { spawn } = require('node:child_process')
const http = require('node:http')

/** Minimal JSON client for the provisioning calls. Throws on non-2xx so a bad fixture is loud. */
async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let parsed = null
  try { parsed = text ? JSON.parse(text) : null } catch { parsed = text }
  if (!res.ok) {
    throw new Error(`${method} ${path} -> ${res.status} ${text.slice(0, 200)}`)
  }
  return parsed
}

/**
 * Create one withdrawn product and return its id.
 *
 * `active: true` on create then deactivate, rather than `active: false` on create: the
 * deactivate endpoint is the path the admin UI uses, so this covers it. A product created
 * already-inactive would never exercise it.
 */
async function provisionWithdrawnProduct() {
  const login = await api('/api/auth/login', {
    method: 'POST',
    body: { email: 'admin@shop.com', password: 'Admin@123' },
  })
  const token = login.token
  if (!token) throw new Error('login returned no token')

  const categories = await api('/api/categories')
  const categoryId = Array.isArray(categories) && categories.length ? categories[0].id : null
  if (!categoryId) throw new Error('no category available to attach the probe product to')

  const created = await api('/api/admin/products', {
    method: 'POST',
    token,
    body: {
      name: PROBE_NAME,
      description: 'Temporary fixture for the admin inactive-toggle check. Safe to delete.',
      price: 1.0,
      stock: 1,
      imageUrl: '',
      categoryId,
      active: true,
    },
  })

  await api(`/api/admin/products/${created.id}/deactivate`, { method: 'PATCH', token })
  return { token, id: created.id, categoryId }
}

const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  '--no-sandbox', '--disable-gpu', '--disable-extensions',
  '--no-first-run', '--no-default-browser-check',
  '--window-size=1440,900',
  '--user-data-dir=' + require('node:os').tmpdir() + '/cdp-admin-profile',
  'about:blank',
], { stdio: 'ignore' })

function getJSON(path, method = 'GET') {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: PORT, path, method }, (res) => {
      let body = ''
      res.on('data', (c) => (body += c))
      res.on('end', () => {
        try { resolve(JSON.parse(body)) } catch { reject(new Error(`non-JSON: ${body.slice(0, 100)}`)) }
      })
    })
    req.on('error', reject)
    req.end()
  })
}

async function waitForChrome() {
  for (let i = 0; i < 60; i++) {
    try { return await getJSON('/json/version') } catch { await new Promise((r) => setTimeout(r, 250)) }
  }
  throw new Error('Chrome did not expose the debugging port')
}

/**
 * Read the product count and whether the probe name is on screen.
 *
 * The page renders "N products" from `totalElements`, so this reads the total rather than
 * counting cards - with a page size of 12 the card count would saturate and hide a delta.
 */
const READ_STATE = (name) => `(() => {
  const text = document.getElementById('root').innerText;
  const m = text.match(/(\\d+)\\s+products?/i);
  return {
    path: location.pathname,
    shown: m ? Number(m[1]) : null,
    probeVisible: text.includes(${JSON.stringify(name)}),
  };
})()`

async function main() {
  const failures = []
  const check = (label, ok, detail) => {
    console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? `  (${detail})` : ''}`)
    if (!ok) failures.push(label)
  }

  let fixture = null
  try {
    // ---- Fixture: a withdrawn product must exist, or the toggle proves nothing ----
    console.log('--- fixture ---')
    fixture = await provisionWithdrawnProduct()
    console.log(`  created + deactivated product id=${fixture.id} name=${PROBE_NAME}`)

    // ---- Browser ----
    await waitForChrome()
    const target = await getJSON(`/json/new?${encodeURIComponent(BASE + '/login')}`, 'PUT')
    const ws = new WebSocket(target.webSocketDebuggerUrl)

    let id = 0
    const pending = new Map()
    const send = (method, params) =>
      new Promise((resolve) => {
        const msgId = ++id
        pending.set(msgId, resolve)
        ws.send(JSON.stringify({ id: msgId, method, params }))
      })

    const pageErrors = []
    await new Promise((r) => (ws.onopen = r))
    ws.onmessage = (e) => {
      const m = JSON.parse(e.data)
      if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); return }
      if (m.method === 'Runtime.exceptionThrown') {
        pageErrors.push((m.params.exceptionDetails.exception?.description || '').split('\n')[0])
      }
    }
    await send('Runtime.enable')
    await send('Page.enable')

    const evaluate = async (expression) => {
      const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'evaluate failed')
      return r.result.value
    }

    // Log in as the admin by driving the real form. Going through the UI rather than injecting
    // a token means this also covers the login page and the token storage path.
    await new Promise((r) => setTimeout(r, 3000))
    const loginResult = await evaluate(`(async () => {
      const inputs = [...document.querySelectorAll('input')];
      const email = inputs.find(i => i.type === 'email');
      const pass  = inputs.find(i => i.type === 'password');
      if (!email || !pass) return { error: 'form not found' };
      const setVal = (el, v) => {
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(el, v);
        el.dispatchEvent(new Event('input', { bubbles: true }));
      };
      setVal(email, 'admin@shop.com');
      setVal(pass, 'Admin@123');
      email.closest('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      await new Promise(r => setTimeout(r, 2500));
      return { path: location.pathname, hasToken: !!localStorage.getItem('ecommerce.token') };
    })()`)
    console.log('--- browser ---')
    console.log('  login ->', JSON.stringify(loginResult))

    /*
     * Login lands on "/" by design (the redirect target is the page the guard interrupted, or
     * the home page). So navigate to the admin console explicitly rather than assuming the
     * login form forwards there - an SPA has no full page loads, so history.pushState is how
     * the router is told to move.
     */
    await evaluate(`(() => {
      history.pushState({}, '', '/admin/products');
      window.dispatchEvent(new PopStateEvent('popstate'));
      return location.pathname;
    })()`)
    await new Promise((r) => setTimeout(r, 4000))

    const before = await evaluate(READ_STATE(PROBE_NAME))
    console.log('  toggle off ->', JSON.stringify(before))

    const after = await evaluate(`(async () => {
      const boxes = [...document.querySelectorAll('input[type=checkbox]')];
      // The label reads "Include deactivated" - matched on that, not on "inactive".
      // Guessing the label text is how this check failed the first two times, so if it ever
      // breaks again, dump the labels rather than guessing a third time.
      let toggle = null;
      for (const b of boxes) {
        const wrap = b.closest('label') || b.parentElement;
        if (wrap && /deactivat/i.test(wrap.innerText || '')) { toggle = b; break; }
      }
      if (!toggle) {
        return { error: 'toggle not found', labels: boxes.map(b => (b.closest('label') || b.parentElement)?.innerText?.slice(0, 40)) };
      }
      toggle.click();
      await new Promise(r => setTimeout(r, 3500));
      const text = document.getElementById('root').innerText;
      const m = text.match(/(\\d+)\\s+products?/i);
      return { shown: m ? Number(m[1]) : null, checked: toggle.checked, probeVisible: text.includes(${JSON.stringify(PROBE_NAME)}) };
    })()`)
    console.log('  toggle ON  ->', JSON.stringify(after))

    console.log('--- assertions ---')
    check('the login form produced a token', loginResult.hasToken === true)
    check('the toggle was found and clicked', !after.error && after.checked === true)
    check('the count was readable in both states',
      typeof before.shown === 'number' && typeof after.shown === 'number',
      `${before.shown} -> ${after.shown}`)

    if (typeof before.shown === 'number' && typeof after.shown === 'number') {
      // The decisive assertion. `>=` would pass on a no-op toggle, which is the bug.
      check('turning the toggle on adds EXACTLY the one withdrawn product',
        after.shown === before.shown + 1,
        `${before.shown} -> ${after.shown}, expected ${before.shown + 1}`)
    }
    check('the withdrawn product is hidden while the toggle is off', before.probeVisible === false)
    check('the withdrawn product is revealed once the toggle is on', after.probeVisible === true)
    check('no uncaught page errors', pageErrors.length === 0, pageErrors.join(' | '))

    ws.close()
  } finally {
    // Always clean up, even when an assertion above threw - a fixture left behind would
    // silently change what the next run measures.
    if (fixture) {
      try {
        await api(`/api/admin/products/${fixture.id}`, { method: 'DELETE', token: fixture.token })
        console.log(`--- cleanup ---\n  deleted probe product id=${fixture.id}`)
      } catch (e) {
        console.log(`--- cleanup ---\n  WARNING: could not delete probe ${fixture.id}: ${e.message}`)
        console.log('  (deactivate it by hand before the next run)')
      }
    }
    chrome.kill()
  }

  console.log('')
  if (failures.length) {
    console.log(`${failures.length} FAILED: ${failures.join('; ')}`)
    process.exit(1)
  }
  console.log('ALL PASSED')
}

main().catch((e) => {
  console.error('check failed:', e.message)
  chrome.kill()
  process.exit(2)
})
