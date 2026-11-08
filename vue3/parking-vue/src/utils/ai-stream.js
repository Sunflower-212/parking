// Fetch supports Authorization and cancellation; native EventSource cannot POST a message body.
export async function readAiStream(response, onEvent) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = '', finished = false
  function consume() {
    let end
    while ((end = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, end)
      buffer = buffer.slice(end + 2)
      const data = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
      if (!data) continue
      const event = JSON.parse(data)
      onEvent(event)
      if (event.type === 'done') finished = true
    }
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      // Keep a trailing CR until the next chunk, so split CRLF boundaries remain intact.
      buffer = buffer.replace(/\r\n/g, '\n')
      if (buffer.length > 512 * 1024) throw new Error('AI返回内容过长，请重新提问')
      consume()
      if (done) break
    }
    if (!finished) throw new Error('AI连接已中断，回答未完成，请重试')
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
