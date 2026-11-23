// Runs one JavaScript expression inside the Bittu app's page on the phone (test builds only).
// Usage: node cdp.mjs "<expression>"   (needs: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>)
const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = pages.find((p) => p.url.includes('bittu.localhost')) || pages[0];
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: `(async () => { ${process.argv[2]} })()`, awaitPromise: true, returnByValue: true } }));
ws.onmessage = (m) => { const r = JSON.parse(m.data); if (r.id !== 1) return; console.log(r.result.exceptionDetails ? 'ERROR ' + JSON.stringify(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text) : JSON.stringify(r.result.result.value)); ws.close(); };
