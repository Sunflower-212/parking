<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({ content: { type: String, default: '' } })
// Each message caches its rendered result; only the streaming message is recomputed.
const html = computed(() => {
  const container = document.createElement('div')
  try {
    const fragment = DOMPurify.sanitize(
      marked.parse(props.content, { gfm: true, breaks: true, async: false }),
      {
        RETURN_DOM_FRAGMENT: true,
        ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'del', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
          'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'hr', 'table', 'thead', 'tbody',
          'tr', 'th', 'td', 'a'],
        ALLOWED_ATTR: ['href', 'title', 'start', 'align'],
        ALLOW_DATA_ATTR: false,
        ALLOW_ARIA_ATTR: false,
      },
    )
    // Add only fixed presentation attributes to sanitized nodes, never model-supplied HTML.
    for (const link of fragment.querySelectorAll('a[href]')) {
      try {
        const url = new URL(link.getAttribute('href'), window.location.href)
        if (!['https:', 'http:'].includes(url.protocol)) throw new Error('Unsupported link')
        link.setAttribute('href', url.href)
        link.setAttribute('target', '_blank')
        link.setAttribute('rel', 'noopener noreferrer')
      } catch {
        link.removeAttribute('href')
      }
    }
    for (const block of fragment.querySelectorAll('table, pre')) {
      block.setAttribute('tabindex', '0')
      block.setAttribute('aria-label', block.tagName === 'TABLE' ? '回答表格，可横向滚动' : '代码块，可横向滚动')
    }
    container.append(fragment)
  } catch {
    // A malformed/incomplete answer must remain readable even if parsing fails.
    container.textContent = props.content
  }
  return container.innerHTML
})
</script>

<template>
  <div class="ai-markdown" v-html="html" />
</template>

<style scoped>
.ai-markdown { min-width: 0; max-width: 100%; overflow-wrap: anywhere; line-height: 1.65; }
.ai-markdown :deep(p) { margin: 8px 0; white-space: normal; }
.ai-markdown :deep(h1), .ai-markdown :deep(h2), .ai-markdown :deep(h3),
.ai-markdown :deep(h4), .ai-markdown :deep(h5), .ai-markdown :deep(h6) {
  font-size: 15px; line-height: 1.5; margin: 14px 0 8px; font-weight: 700;
}
.ai-markdown :deep(ul), .ai-markdown :deep(ol) { padding-left: 22px; margin: 8px 0; }
.ai-markdown :deep(li) { margin: 3px 0; }
.ai-markdown :deep(blockquote) { border-left: 3px solid #93b9ff; margin: 10px 0; padding-left: 10px; color: #596579; }
.ai-markdown :deep(code) { font: 12px/1.6 ui-monospace, monospace; background: #e8edf5; border-radius: 4px; padding: 2px 4px; }
.ai-markdown :deep(pre) { max-width: 100%; overflow-x: auto; padding: 10px; border-radius: 8px; background: #e8edf5; white-space: pre; }
.ai-markdown :deep(pre code) { padding: 0; background: none; overflow-wrap: normal; }
.ai-markdown :deep(table) { display: block; width: max-content; max-width: 100%; overflow-x: auto; border-collapse: collapse; margin: 10px 0; }
.ai-markdown :deep(th), .ai-markdown :deep(td) { border: 1px solid #d5dfed; padding: 6px 9px; white-space: nowrap; }
.ai-markdown :deep(th) { background: #eaf2ff; font-weight: 600; }
.ai-markdown :deep(a) { color: #245bc1; text-decoration: underline; }
.ai-markdown :deep(hr) { border: 0; border-top: 1px solid #d5dfed; margin: 12px 0; }
.ai-markdown :deep(:focus-visible) { outline: 2px solid #2563eb; outline-offset: 2px; }
</style>
