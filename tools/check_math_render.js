// 离线自检：验证内置 KaTeX / marked 能正确渲染公式与 Markdown（不需要浏览器 DOM）
const path = require('path');
const dir = 'C:/Users/lzega/Desktop/XueTi/app/src/main/assets/render';

const katex = require(path.join(dir, 'katex.min.js'));
const marked = require(path.join(dir, 'marked.min.js'));

const markdown = [
    '## 例题',
    '',
    '行内公式 $a^2 + b^2 = c^2$ 应该被渲染。',
    '',
    '独立公式：',
    '',
    '$$S = v_0 t + \\frac{1}{2} a t^2$$',
    '',
    '矩阵：',
    '',
    '$$\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}$$'
].join('\n');

let html = marked.parse(markdown);
console.log('marked 输出长度:', html.length, '| 含 <h2>:', html.includes('<h2'));

const samples = ['a^2 + b^2 = c^2', 'S = v_0 t + \\frac{1}{2} a t^2', '\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}'];
for (const s of samples) {
    const out = katex.renderToString(s, { throwOnError: false, displayMode: true });
    console.log('katex ok:', JSON.stringify(s.slice(0, 34)), '->', out.length, 'chars, 含 katex class:', out.includes('katex'));
}

// 验证 render.html 里的「裸环境」预处理正则
const src = '\\begin{aligned} x &= 1 \\\\ y &= 2 \\end{aligned}';
const wrapped = src.replace(
    /\\begin\{(equation\*?|align\*?|aligned|gather\*?|array|cases|pmatrix|bmatrix|vmatrix|Vmatrix|matrix|split)\}([\s\S]*?)\\end\{\1\}/g,
    (m) => '$$' + m + '$$'
);
console.log('裸环境自动包裹:', wrapped.startsWith('$$') && wrapped.endsWith('$$'));
const out2 = katex.renderToString(wrapped.slice(2, -2), { throwOnError: false, displayMode: true });
console.log('包裹后可渲染:', out2.includes('katex'));
