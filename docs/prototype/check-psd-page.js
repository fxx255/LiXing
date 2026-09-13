// 抽出 psd-example.html 的内联脚本，stub 掉浏览器 API 后实跑，校验三张图的数据。
const fs = require('fs');
const html = fs.readFileSync('F:/APP/docs/prototype/psd-example.html', 'utf8');
const blocks = [...html.matchAll(/<script(?![^>]*\ssrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (!blocks.length) { console.error('没找到内联脚本'); process.exit(1); }

const stub = `
const document = {
  getElementById: () => ({ setOption() {}, innerHTML: '', textContent: '' }),
  body: {},
};
const echarts = { init: () => ({ setOption() {}, resize() {} }) };
const renderMathInElement = function () {};
`;

const test = `
let bad = 0;
function check(l, c, e) {
  if (!c) { bad++; console.log('FAIL  ' + l + (e ? '  ' + e : '')); }
  else console.log('OK    ' + l + (e ? '  ' + e : ''));
}

check('H(f) 手写点数', hPoints.length === 10, 'n=' + hPoints.length);
check('H(f) 带内为 1', hPoints[2][1] === 1 && hPoints[3][1] === 1);
check('H(f) 带外为 0', hPoints[0][1] === 0 && hPoints[9][1] === 0);
check('H(f) 双带位置正确', hPoints[2][0] === -6 && hPoints[3][0] === -4 && hPoints[6][0] === 4 && hPoints[7][0] === 6);

check('S_Y(5) = (N0/2)(2π·5)²', Math.abs(SY(5) - 0.5 * Math.pow(2 * Math.PI * 5, 2)) < 1e-9, SY(5).toFixed(2));
check('S_Y 带外为 0', SY(0) === 0 && SY(3.9) === 0 && SY(7) === 0);
check('S_Y 采样点充足', syPoints.length > 800, 'n=' + syPoints.length);
check('S_Y 峰值在带边缘', Math.max(...syPoints.map(p => p[1])) > 700,
      'max=' + Math.max(...syPoints.map(p => p[1])).toFixed(1));

check('S_c 点数', scPoints.length > 150, 'n=' + scPoints.length);
check('S_c(0) = 100π²', Math.abs(SC(0) - 100 * Math.PI * Math.PI) < 1e-6, SC(0).toFixed(2));
check('S_c(±1) = 104π²', Math.abs(SC(1) - 104 * Math.PI * Math.PI) < 1e-6, SC(1).toFixed(2));
check('S_c 带外为 0', SC(1.5) === 0);

const scMax = Math.max(...scScaled.map(p => p[1]));
const scMin = Math.min(...scScaled.map(p => p[1]));
check('缩放后落在 y 轴范围内', scMax < 1.25 && scMin >= 0,
      'min=' + scMin.toFixed(3) + ' max=' + scMax.toFixed(3));
check('带内确实上翘（非严格矩形）', scMax > scMin * 1.03,
      '上翘 ' + ((scMax / scMin - 1) * 100).toFixed(1) + '%');

console.log(bad === 0 ? '\\n全部通过' : '\\n有 ' + bad + ' 项失败');
if (bad > 0) process.exit(1);
`;

try {
  new Function(stub + blocks.join('\n') + test)();
} catch (e) {
  console.error('执行失败：' + e.message);
  process.exit(1);
}
