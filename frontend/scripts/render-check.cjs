/**
 * Headless render check for the storefront.
 *
 * Why this exists: `npm run build` proves the code COMPILES. It says nothing about
 * whether the app MOUNTS. A hook-order violation, a bad import in a lazily-loaded
 * chunk, or a null deref in a provider all build perfectly and then leave the user
 * staring at a blank page with a red console.
 *
 * This drives real Chrome over the DevTools Protocol, loads each route, and reports:
 *   - console errors and uncaught page exceptions
 *   - failed network requests
 *   - whether React actually painted content into #root
 *
 * No puppeteer dependency: it talks to the CDP websocket directly using Node's
 * built-in WebSocket, so there is nothing to install.
 */

const CHROME = process.argv[2]
const PORT = 9333
const TARGET = 'http://localhost:5173'

const { spawn } = require('node:child_process')
const http = require('node:http')

const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  '--no-sandbox',
  '--disable-gpu',
  '--disable-extensions',
  '--no-first-run',
  '--no-default-browser-check',
  '--window-size=1440,900',
  '--user-data-dir=' + require('node:os').tmpdir() + '/cdp-verify-profile',
  'about:blank',
], { stdio: 'ignore' })

/**
 * GET a DevTools HTTP endpoint.
 *
 * The method matters. `/json/new` is a MUTATING endpoint and Chrome rejects GET on it
 * (older builds answered with a plain-text "Using unsafe HTTP verb GET to invoke /json/new"
 * notice, which is what broke the first run - it parsed as JSON and threw). `PUT` is
 * required. The rest are plain GETs.
 */
function getJSON(path, method = 'GET') {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: PORT, path, method }, (res) => {
      let body = ''
      res.on('data', (c) => (body += c))
      res.on('end', () => {
        try { resolve(JSON.parse(body)) } catch (e) { reject(new Error(`non-JSON from ${path}: ${body.slice(0, 120)}`)) }
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

const ROUTES = [
  ['/', 'Home'],
  ['/products', 'Product catalogue'],
  ['/products/19', 'Product detail (3D viewer)'],
  ['/login', 'Login'],
  ['/register', 'Register'],
  ['/cart', 'Cart (anon)'],
  ['/orders', 'Orders (anon -> redirect to login)'],
  ['/this-route-does-not-exist', '404'],
]

async function main() {
  await waitForChrome()

  const results = []

  for (const [route, label] of ROUTES) {
    const target = await getJSON(`/json/new?${encodeURIComponent(TARGET + route)}`, 'PUT')
    const ws = new WebSocket(target.webSocketDebuggerUrl)

    const consoleErrors = []
    const pageErrors = []
    const failedRequests = []
    let painted = 0
    let rootText = ''

    let id = 0
    const pending = new Map()
    const send = (method, params) =>
      new Promise((resolve) => {
        const msgId = ++id
        pending.set(msgId, resolve)
        ws.send(JSON.stringify({ id: msgId, method, params }))
      })

    await new Promise((resolve) => (ws.onopen = resolve))

    ws.onmessage = (event) => {
      const msg = JSON.parse(event.data)
      if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg.result); pending.delete(msg.id); return }

      if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
        consoleErrors.push(
          (msg.params.args || []).map((a) => a.value ?? a.description ?? a.type).join(' ').slice(0, 300)
        )
      }
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params.exceptionDetails
        pageErrors.push((d.exception?.description || d.text || 'unknown').split('\n')[0].slice(0, 300))
      }
      if (msg.method === 'Network.loadingFailed') {
        failedRequests.push(`${msg.params.type} ${msg.params.errorText}`)
      }
    }

    await send('Runtime.enable')
    await send('Network.enable')
    await send('Page.enable')

    // Wait for the SPA to settle: mount, then the initial data fetch.
    await new Promise((r) => setTimeout(r, 4500))

    const evalRes = await send('Runtime.evaluate', {
      expression: `(() => {
        const root = document.getElementById('root');
        const text = (root && root.innerText) || '';
        return JSON.stringify({
          length: text.length,
          sample: text.replace(/\\s+/g, ' ').trim().slice(0, 220),
          hasErrorBoundary: text.includes('Something went wrong') || text.includes('went wrong'),
          path: location.pathname,
        });
      })()`,
      returnByValue: true,
    })

    try {
      const parsed = JSON.parse(evalRes.result.value)
      painted = parsed.length
      rootText = parsed.sample
      results.push({ route, label, painted, rootText, path: parsed.path, consoleErrors, pageErrors, failedRequests, boundary: parsed.hasErrorBoundary })
    } catch {
      results.push({ route, label, painted: 0, rootText: '(evaluate failed)', consoleErrors, pageErrors, failedRequests, boundary: false })
    }

    ws.close()
    await getJSON(`/json/close/${target.id}`).catch(() => {})
  }

  console.log('\n================ RENDER CHECK ================\n')
  let bad = 0
  for (const r of results) {
    const ok = r.painted > 50 && r.pageErrors.length === 0 && r.consoleErrors.length === 0 && !r.boundary
    if (!ok) bad++
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${r.route}  (${r.label})`)
    console.log(`      painted     : ${r.painted} chars  path=${r.path}`)
    console.log(`      text        : ${r.rootText || '(empty)'}`)
    if (r.boundary) console.log('      *** ERROR BOUNDARY TRIPPED ***')
    r.pageErrors.forEach((e) => console.log(`      PAGE ERROR  : ${e}`))
    r.consoleErrors.forEach((e) => console.log(`      CONSOLE ERR : ${e}`))
    r.failedRequests.forEach((e) => console.log(`      NETWORK FAIL: ${e}`))
    console.log('')
  }

  console.log(`=============================================`)
  console.log(`${results.length - bad}/${results.length} routes rendered clean`)
  chrome.kill()
  process.exit(bad === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('check failed:', e.message)
  chrome.kill()
  process.exit(2)
})
