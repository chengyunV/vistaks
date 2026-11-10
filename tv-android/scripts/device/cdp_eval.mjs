// node cdp_eval.mjs <ws url> <expression file>
import { readFileSync } from 'node:fs'
const [ws, file] = process.argv.slice(2)
const expression = readFileSync(file, 'utf8')
const socket = new WebSocket(ws)
const timer = setTimeout(() => { console.log('timeout'); process.exit(2) }, 60000)
socket.onopen = () => socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }))
socket.onmessage = (event) => {
  const message = JSON.parse(event.data)
  if (message.id !== 1) return
  clearTimeout(timer)
  console.log(JSON.stringify(message.result?.result?.value ?? message.result ?? message.error, null, 1))
  socket.close()
  process.exit(0)
}
socket.onerror = (error) => { console.log('ws error', error.message); process.exit(1) }
