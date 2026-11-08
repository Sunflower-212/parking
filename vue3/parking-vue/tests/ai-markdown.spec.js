import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AiMarkdown from '../src/components/AiMarkdown.vue'

it('renders business summaries, tables, lists, quotes and code', () => {
  const wrapper = mount(AiMarkdown, { props: { content: '# 近7天经营\n\n**总体**\n\n- 停车场：1个\n- 营收：1元\n\n| 日期 | 入场 | 营收 |\n| --- | --- | --- |\n| 11-04 | 1 | 1元 |\n\n> 数据仅限管理范围\n\n1. 查询明细\n\n`金额`\n\n```text\n<不是HTML>\n```' } })
  expect(wrapper.get('h1').text()).toBe('近7天经营')
  expect(wrapper.get('strong').text()).toBe('总体')
  expect(wrapper.findAll('ul li')).toHaveLength(2)
  expect(wrapper.get('ol li').text()).toBe('查询明细')
  expect(wrapper.findAll('th')).toHaveLength(3)
  expect(wrapper.get('tbody tr').text()).toContain('1元')
  expect(wrapper.get('blockquote').text()).toContain('管理范围')
  expect(wrapper.get('pre code').text()).toContain('<不是HTML>')
  expect(wrapper.get('table').attributes('tabindex')).toBe('0')
})

it('updates incomplete streaming syntax and handles empty/history content', async () => {
  const wrapper = mount(AiMarkdown, { props: { content: '' } })
  expect(wrapper.text()).toBe('')
  await wrapper.setProps({ content: '**总' })
  expect(wrapper.text()).toContain('总')
  await wrapper.setProps({ content: '**总体**\n\n| 日期 | 营收 |\n| --- | --- |\n| 今天 |' })
  expect(wrapper.get('strong').text()).toBe('总体')
  expect(wrapper.get('table').text()).toContain('今天')
  await wrapper.setProps({ content: '**总体**\n\n| 日期 | 营收 |\n| --- | --- |\n| 今天 | 10元 |\n\n```js\nconst total = 10;' })
  expect(wrapper.get('tbody tr').text()).toContain('10元')
  expect(wrapper.get('pre code').text()).toContain('const total = 10;')
})

it('removes executable markup, remote images, forged controls and dangerous URLs', () => {
  const content = '<script>window.pwned=1</script><style>body{display:none}</style>\n\n<img src="https://example.com/tracker" onerror="alert(1)">\n\n<svg onload="alert(1)"></svg><iframe src="https://example.com"></iframe>\n\n<button class="ai-action" onclick="alert(1)">确认预约</button>\n\n<a href="javascript:alert(1)" onclick="alert(1)" style="color:red" id="ai-chat-window">危险</a>\n\n[数据链接](data:text/html,evil) [文件](file:///etc/passwd) [正常链接](https://example.com/docs)'
  const wrapper = mount(AiMarkdown, { props: { content } })
  expect(wrapper.findAll('script,style,img,svg,iframe,button,form,[onclick],[onerror],[style],[id],.ai-action')).toHaveLength(0)
  const links = wrapper.findAll('a[href]')
  expect(links).toHaveLength(1)
  expect(links[0].attributes('href')).toBe('https://example.com/docs')
  expect(links[0].attributes('target')).toBe('_blank')
  expect(links[0].attributes('rel')).toBe('noopener noreferrer')
})
