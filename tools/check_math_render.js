// 离线自检：直接加载 app 内真正的 render.html 脚本，验证「Markdown 不再吃掉 LaTeX」
// 用假 DOM 运行（不跑 auto-render，只检查 Markdown 解析后公式是否原样保留）
const fs = require('fs');
const path = require('path');
const dir = 'C:/Users/lzega/Desktop/XueTi/app/src/main/assets/render';

const katex = require(path.join(dir, 'katex.min.js'));
const marked = require(path.join(dir, 'marked.min.js'));

// ---- 假 DOM ----
const fakeElement = { innerHTML: '' };
global.document = {
    getElementById: () => fakeElement,
    body: { style: {}, scrollHeight: 123 }
};
global.window = {};
global.renderMathInElement = function () { /* 需要真实 DOM，这里不做 */ };
global.marked = marked;
global.katex = katex;

// ---- 取出 render.html 里的脚本并执行 ----
const html = fs.readFileSync(path.join(dir, 'render.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
eval(script);

function render(text) {
    fakeElement.innerHTML = '';
    window.renderContent(text, '#000000', 15);
    return fakeElement.innerHTML;
}

const caseFromScreenshot = [
    '设 \\(A,B,C\\) 表示三个事件。用集合运算表示：',
    '(1) A发生且B不发生：\\(A\\cap\\bar B=A-B\\)。',
    '(2) 至少有一个发生：\\(A\\cup B\\cup C\\)。',
    '(3) 至少有两个发生：\\(((A\\cap B)\\cup(A\\cap C)\\cup(B\\cap C))\\)。'
].join('\n');

const cases = {
    '截图中的 \\( \\) 行内公式': caseFromScreenshot,
    '$...$ 行内公式': '行内公式 $a^2+b^2=c^2$ 与文字混排。',
    '$$...$$ 独立公式': '独立公式：\n\n$$S = v_0t + \\frac{1}{2}at^2$$',
    '\\[ ... \\] 独立公式': '\\[\\int_0^1 x\\,dx = \\frac{1}{2}\\]',
    '裸环境 pmatrix': '\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}',
    '含下划线与花括号': '下标 $x_{1}$ 与集合 $\\{a,b\\}$',
    'Markdown 结构 + 公式': '## 解答\n\n- 第一步 $a=1$\n- 第二步 $b=2$'
};

let failed = 0;
for (const [name, text] of Object.entries(cases)) {
    const out = render(text);
    const noStrayPlaceholder = !out.includes('\u0001MATH');
    // 关键断言：解析后的 HTML 里公式必须仍带 $ / $$ 定界符（原样交给 KaTeX）
    const hasMath = out.includes('$');
    const ok = hasMath && noStrayPlaceholder;
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`);
    console.log(`      公式保留定界符=${hasMath}  无残留占位符=${noStrayPlaceholder}`);
    console.log(`      输出: ${out.replace(/\n/g, ' ').slice(0, 140)}`);
}

// 关键回归：截图里那种 \(A\cap\bar B=A-B\) 必须变成 $A\cap\bar B=A-B$
const fixed = render('(1) A发生且B不发生：\\(A\\cap\\bar B=A-B\\)。');
const good = fixed.includes('$A\\cap\\bar B=A-B$');
console.log(`\n截图 bug 回归: ${good ? 'PASS（已转为 $...$，KaTeX 可渲染）' : 'FAIL'}`);
if (!good) failed++;

const katexOut = katex.renderToString('A\\cap\\bar B=A-B', { throwOnError: false });
const katexOk = katexOut.includes('katex');
console.log(`KaTeX 直接渲染该公式: ${katexOk ? 'PASS' : 'FAIL'}`);
if (!katexOk) failed++;

console.log(`\n结果: ${failed === 0 ? '全部通过' : failed + ' 项失败'}`);
process.exit(failed === 0 ? 0 : 1);
