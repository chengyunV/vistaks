// node cdp_frame_eval.mjs <ws> <expression file> — evaluate inside every execution context whose
// origin is https://www.youtube.com (the embed), print the results.
import { readFileSync } from 'node:fs'
const [ws, file] = process.argv.slice(2)
const expression = readFileSync(file, 'utf8')
const socket = new WebSocket(ws)
const contexts = []
let id = 0
const pending = new Map()
const send = (method, params = {}) => new Promise((resolve) => { const n = ++id; pending.set(n, resolve); socket.send(JSON.stringify({ id: n, method, params })) })
const timer = setTimeout(() => { console.log('timeout'); process.exit(2) }, 30000)
socket.onmessage = (event) => {
  const message = JSON.parse(event.data)
  if (message.method === 'Runtime.executionContextCreated') contexts.push(message.params.context)
  if (message.id && pending.has(message.id)) { pending.get(message.id)(message); pending.delete(message.id) }
}
socket.onopen = async () => {
  await send('Runtime.enable')
  await new Promise((r) => setTimeout(r, 800))
  const yt = contexts.filter((context) => String(context.origin).includes('youtube.com'))
  for (const context of yt) {
    const result = await send('Runtime.evaluate', { expression, contextId: context.id, returnByValue: true, awaitPromise: true })
    console.log(JSON.stringify({ origin: context.origin, name: context.name, value: result.result?.result?.value ?? result.result?.exceptionDetails?.text }, null, 1))
  }
  if (!yt.length) console.log('no youtube context; origins:', contexts.map((c) => c.origin))
  clearTimeout(timer)
  socket.close()
  process.exit(0)
}
