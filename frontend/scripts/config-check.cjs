/**
 * Verifies that the values in `frontend/.env` actually reach the running application.
 *
 * This is the check for a specific failure mode: an `.env.example` that documents settings
 * nothing reads. That looks fine in review - the file is there, the variables are named
 * sensibly - and it fails silently, because the app simply keeps using its hardcoded defaults.
 * A reader who sets `VITE_APP_NAME=MyShop` and sees "Aurora" in the header concludes the whole
 * config file is decorative.
 *
 * So this asserts against RENDERED OUTPUT over real HTTP:
 *   - VITE_APP_NAME            -> appears in the header, and in document.title
 *   - VITE_PRODUCTS_PAGE_SIZE  -> the number of product cards on /products
 *
 * Point it at a dev server, which is what a developer runs:
 *     node scripts/config-check.cjs "<chrome-path>" http://localhost:5173 TestMart 4
 *
 * The expected values are passed in rather than read from .env, so the check cannot "pass" by
 * reading the same file the app read. It has to be told what it should see.
 */

const CHROME = process.argv[2]
const TARGET = process.argv[3] || 'http://localhost:5173'
const EXPECT_NAME = process.argv[4] || 'Aurora'
const EXPECT_SIZE = Number.parseInt(process.argv[5] || '12', 10)

const { spawn } = require('node:child_process')
const http = require('node:http')

const PORT = 9335
const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  '--no-sandbox', '--disable-gpu', '--disable-extensions',
  '--no-first-run', '--no-default-browser-check',
  '--window-size=1440,900',
  '--user-data-dir=' + require('node:os').tmpdir() + '/cdp-config-profile',
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

async function main() {
  await waitForChrome()

  // /products is the page that exercises BOTH settings: the header name and the page size.
  const target = await getJSON(`/json/new?${encodeURIComponent(TARGET + '/products')}`, 'PUT')
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

  await new Promise((r) => setTimeout(r, 4500))

  const observed = await evaluate(`(() => {
    const text = document.getElementById('root').innerText;
    const brand = document.querySelector('.brand__name');
    // Product cards carry a link to /products/<id>; counting those counts the rendered page.
    const cards = document.querySelectorAll('a[href^="/products/"]').length;
    return {
      brand: brand ? brand.innerText.trim() : null,
      title: document.title,
      cards,
      // The header appears before the grid, so a missing brand would be a layout failure.
      headerPresent: !!document.querySelector('header'),
    };
  })()`)

  console.log('\n=== config resolution ===')
  console.log(`  expected app name : ${EXPECT_NAME}`)
  console.log(`  observed brand    : ${observed.brand}`)
  console.log(`  observed title    : ${observed.title}`)
  console.log(`  expected page size: ${EXPECT_SIZE}`)
  console.log(`  observed cards    : ${observed.cards}`)
  console.log('')

  let ok = true
  const fail = (msg) => { console.log(`  FAIL: ${msg}`); ok = false }

  if (observed.brand !== EXPECT_NAME) {
    fail(`brand is "${observed.brand}", expected "${EXPECT_NAME}" - VITE_APP_NAME is not being read`)
  } else {
    console.log('  PASS: VITE_APP_NAME reached the header')
  }

  if (!observed.title.startsWith(EXPECT_NAME)) {
    fail(`document.title is "${observed.title}", expected it to start with "${EXPECT_NAME}"`)
  } else {
    console.log('  PASS: VITE_APP_NAME reached document.title')
  }

  // Compare against the product total, so a short final page is not mistaken for a wrong size.
  if (observed.cards === 0) {
    fail('no product cards rendered - cannot verify the page size')
  } else if (observed.cards > EXPECT_SIZE) {
    fail(`rendered ${observed.cards} cards, more than the configured page size ${EXPECT_SIZE}`)
  } else {
    console.log(`  PASS: VITE_PRODUCTS_PAGE_SIZE honoured (${observed.cards} <= ${EXPECT_SIZE})`)
  }

  if (!observed.headerPresent) fail('no <header> rendered')

  if (pageErrors.length) {
    console.log('  PAGE ERRORS: ' + pageErrors.join(' | '))
    ok = false
  }

  console.log(ok ? '\nALL CHECKS PASSED\n' : '\nCHECKS FAILED\n')
  ws.close()
  chrome.kill()
  process.exit(ok ? 0 : 1)
}

main().catch((e) => {
  console.error('check failed:', e.message)
  chrome.kill()
  process.exit(2)
})
