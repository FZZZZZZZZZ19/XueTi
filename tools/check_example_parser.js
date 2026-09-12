// 离线自检：验证 ExampleParser 的拆分规则（与 Kotlin 实现同名正则）
const headerRegex = /^\s*(?:#{1,4}\s*)?(?:\*\*)?\s*(?:例题|例|习题|练习|Example)\s*([0-9０-９一二三四五六七八九十]+)?[：:.、\s].*$/i;
const plainHeaderRegex = /^\s*(?:#{1,4}\s*)?(?:\*\*)?\s*(?:例题|Example)\s*([0-9０-９一二三四五六七八九十]+)?\s*(?:\*\*)?\s*$/i;
const questionRegex = /^\s*(?:#{1,6}\s*)?(?:\*\*)?\s*(?:题目|问题|题干)\s*(?:\*\*)?\s*[：:]?\s*$/;
const numberedRegex = /^\s*(?:\d{1,2}|[（(]\d{1,2}[)）])[.、：:)\s]\s*\S.*$/;
const longSolutionRegex = /^\s*(?:#{1,6}\s*)?(?:\*\*)?\s*(?:解答|解析|答案解析|答案|Solution|Answer)\s*(?:\*\*)?\s*[：:]?/;
const shortSolutionRegex = /^\s*(?:\*\*)?\s*(?:解|答)\s*(?:\*\*)?\s*[：:]\s*.*$/;

function splitBy(text, marker) {
    const out = []; let buf = []; let title = null;
    for (const line of text.split('\n')) {
        const hit = marker(line);
        if (hit !== null) {
            if (buf.join('\n').trim()) out.push([title, buf.join('\n').trim()]);
            buf = []; title = hit.title;
            if (hit.keepLine) buf.push(line);
        } else buf.push(line);
    }
    if (buf.join('\n').trim()) out.push([title, buf.join('\n').trim()]);
    return out;
}
const clean = (s) => s.replace(/^#{1,6}\s*/, '').replace(/\*\*/g, '').trim();

function splitQuestionSolution(body) {
    const lines = body.split('\n');
    const idx = lines.findIndex(l => {
        const t = l.trim();
        if (!t) return false;
        return longSolutionRegex.test(t) || shortSolutionRegex.test(t);
    });
    if (idx <= 0) return [body.trim(), ''];
    return [lines.slice(0, idx).join('\n').trim(), lines.slice(idx).join('\n').trim()];
}

function build(pairs) {
    return pairs.filter(p => p[1]).map(([title, body], i) => {
        const heading = title && title !== '题目' ? title : null;
        const [q, s] = splitQuestionSolution(body);
        return { title: heading || `例题 ${i + 1}`, question: q, solution: s };
    });
}

function split(raw) {
    const text = raw.replace(/\r\n/g, '\n').trim();
    let r = splitBy(text, (line) => {
        const t = line.trim();
        if (plainHeaderRegex.test(t)) return { title: clean(t), keepLine: false };
        if (headerRegex.test(t) && t.length <= 60) return { title: clean(t), keepLine: false };
        return null;
    });
    if (r.length >= 2) return build(r);
    r = splitBy(text, (line) => (questionRegex.test(line) ? { title: '题目', keepLine: true } : null));
    if (r.length >= 2) return build(r);
    r = splitBy(text, (line) => (numberedRegex.test(line.trim()) && line.trim().length > 8 ? { title: '题目', keepLine: true } : null));
    if (r.length >= 2) return build(r);
    return [{ title: '', question: text, solution: '' }];
}

const cases = {
    '小标题 + 解答标记': `### 例题 1 求极限
计算 $\\lim_{x\\to 0}\\frac{\\sin x}{x}$

**解答**
由重要极限得结果为 $1$。

### 例题 2 求导数
求 $f(x)=x^2$ 的导数。

**解答**
$f'(x)=2x$。`,
    '题目标记格式': `**题目**
甲乙两数之和为 10，求甲数。

**解答**
设甲数为 x，则乙数为 10-x。

**题目**
一个物体自由下落 2 秒，求位移。

**解答**
$s=\\frac{1}{2}gt^2=19.6$ m。`,
    '编号列表格式': `1. 求 $\\int_0^1 x dx$ 的值。
解：$\\int_0^1 x dx = 1/2$。
2. 求 $\\sqrt{16}$ 的值。
解：答案为 4。`,
    '无标记整段': `这是一道综合题，题干较长且没有任何分题标记，应该整段作为一道题保留下来，不能丢掉内容。`
};

for (const [name, text] of Object.entries(cases)) {
    const items = split(text);
    console.log(`--- ${name}: ${items.length} 道题`);
    items.forEach((it, i) => {
        console.log(`   [${i + 1}] title=${JSON.stringify(it.title)} question=${it.question.length}字 solution=${it.solution.length}字`);
    });
}
