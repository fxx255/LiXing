// 把 plot-preview.html 里的内联脚本抽出来，用 stub 顶替 DOM / ECharts / Mermaid，
// 然后实跑一遍核心计算逻辑（求值器、采样、各图数据准备），确保原型不是"看起来对"。
const fs = require('fs');
const html = fs.readFileSync('F:/APP/docs/prototype/plot-preview.html', 'utf8');

const blocks = [...html.matchAll(/<script(?![^>]*\ssrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (!blocks.length) { console.error('没找到内联脚本'); process.exit(1); }
const code = blocks.join('\n');

const stub = `
const document = {
  getElementById: () => ({ innerHTML: '', textContent: '', }),
};
const window = { addEventListener() {} };
const echarts = { init: () => ({ setOption() {}, resize() {} }) };
const mermaid = { initialize() {}, render: async () => ({ svg: '<svg/>' }) };
const renderMathInElement = function () {};
`;

const test = `
let bad = 0;
function check(label, cond, extra) {
  if (!cond) { bad++; console.log('FAIL  ' + label + (extra ? '  ' + extra : '')); }
  else console.log('OK    ' + label + (extra ? '  ' + extra : ''));
}

check('x^2 @3 = 9', compileExpr('x^2')({ x: 3 }) === 9);
check('2x^2+1 @2 = 9 (隐式乘法)', compileExpr('2x^2+1')({ x: 2 }) === 9);
check('-x^2 @2 = -4 (一元负号优先级)', compileExpr('-x^2')({ x: 2 }) === -4);
check('sin(2*pi*5*t) @0.05 = 1', Math.abs(compileExpr('sin(2*pi*5*t)')({ t: 0.05 }) - 1) < 1e-9);

const sp = makeSpectrum(0.25, 26);
check('频谱数据', sp.bars.length === 26 && sp.envelope.length > 100,
      'bars=' + sp.bars.length + ' env=' + sp.envelope.length);
check('频谱首谐波幅度合理', sp.bars[0][1] > 0 && sp.bars[0][1] < 0.5, 'a1=' + sp.bars[0][1].toFixed(4));

const wf = makeWaveform(8, 4, 48);
check('波形数据', wf.data.length === 8 * 48 + 1 && wf.bits.length === 8,
      'pts=' + wf.data.length + ' bits=' + wf.bits.join(''));
check('波形包络与数据同长', wf.envelopeTop.length === wf.data.length);

const eye = makeEye(20, 40, 2, 0.05);
check('眼图轨迹', eye.length > 10 && eye[0].length === 2 * 40 + 1,
      'traces=' + eye.length + ' pts/trace=' + eye[0].length);
const eyeVals = eye.flat().map(p => p[1]);
check('眼图数值有限', eyeVals.every(Number.isFinite));

const cst = makeConstellation(50, 0.075);
check('星座图数据', cst.rx.length === 50 && cst.ideal.length === 4);
check('理想星座点半径 ≈ 1', Math.abs(Math.hypot(cst.ideal[0][0], cst.ideal[0][1]) - 1) < 1e-9);

const s = sampleSeries({ expr: 'x^2' }, -3, 3);
check('采样点数', s.length === SAMPLE_COUNT + 1, 'n=' + s.length);
check('采样端点', Math.abs(s[0][1] - 9) < 1e-6 && Math.abs(s[s.length - 1][1] - 9) < 1e-6,
      'y(-3)=' + s[0][1] + ' y(3)=' + s[s.length - 1][1]);

const s2 = sampleSeries({ expr: '1/x' }, -1, 1);
const nulls = s2.filter(p => p[1] === null).length;
check('发散点断线 (1/x)', nulls > 0, '断点=' + nulls);

let threw = false;
try { sampleSeries({ expr: 'notafunc(x)' }, -1, 1); } catch (e) { threw = true; }
check('非法表达式被拦截', threw);

const opt = RENDERERS.FUNCTION(SPEC.plots[0]);
check('FUNCTION option 生成', opt.series.length === 2 && !!opt.xAxis && !!opt.yAxis);
const opt2 = RENDERERS.EYE(SPEC.plots[3]);
check('EYE option 生成', opt2.series.length > 50);
const opt3 = RENDERERS.CONSTELLATION(SPEC.plots[4]);
check('CONSTELLATION option 生成', opt3.series.length === 2);

check('reply 含公式定界符', SPEC.reply.includes('$$'));
check('spec 可 JSON 序列化', JSON.stringify(SPEC).length > 500);

console.log(bad === 0 ? '\\n全部通过' : '\\n有 ' + bad + ' 项失败');
if (bad > 0) process.exit(1);
`;

try {
  new Function(stub + code + test)();
} catch (e) {
  console.error('执行失败：' + e.message);
  console.error(e.stack);
  process.exit(1);
}
