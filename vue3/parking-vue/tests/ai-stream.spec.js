import { expect, it } from 'vitest'
import { readAiStream } from '../src/utils/ai-stream'

function response(text, width = 1) {
  const bytes = new TextEncoder().encode(text)
  return { body: new ReadableStream({ start(controller) { for (let i = 0; i < bytes.length; i += width) controller.enqueue(bytes.slice(i, i + width)); controller.close() } }) }
}
it('parses split UTF-8, CRLF and separate progress, delta and completion events', async () => {
  const events = []
  await readAiStream(response('event: delta\r\ndata: {"type":"delta","text":"车位"}\r\n\r\ndata: {"type":"done","success":true}\r\n\r\n'), e => events.push(e))
  expect(events).toEqual([{ type: 'delta', text: '车位' }, { type: 'done', success: true }])
})
it('does not treat a disconnected partial answer as success', async () => {
  await expect(readAiStream(response('data: {"type":"delta","text":"partial"}\n\n'), () => {})).rejects.toThrow('未完成')
})
it('propagates structured failure without processing later cards', async () => {
  let cards = 0
  await expect(readAiStream(response('data: {"type":"error"}\n\ndata: {"type":"action"}\n\n'), e => { if (e.type === 'error') throw new Error('failed'); cards++ })).rejects.toThrow('failed')
  expect(cards).toBe(0)
})
