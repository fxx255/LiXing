// 校验题 3.3 的解析式与数值
const FC = 5, B = 2, N0 = 1;

// S_Y(f) = (N0/2)·|H(f)|²·|j2πf|²，带内（|f| 在 [fc-B/2, fc+B/2]）
const SY = f => (Math.abs(Math.abs(f) - FC) <= B/2 + 1e-9)
  ? (N0/2) * Math.pow(2*Math.PI*f, 2) : 0;

// 窄带搬移：S_c(f) = S_s(f) = S_Y(f+fc) + S_Y(f-fc)，|f| <= B/2
const SC = f => (Math.abs(f) <= B/2 + 1e-9)
  ? SY(f + FC) + SY(f - FC) : 0;

// 解析式对照：S_c(f) = 4π²N0(fc² + f²)
const SC_analytic = f => 4 * Math.PI * Math.PI * N0 * (FC*FC + f*f);

let bad = 0;
const chk = (label, a, b, tol=1e-6) => {
  const ok = Math.abs(a-b) < tol;
  if (!ok) bad++;
  console.log((ok?'OK  ':'FAIL') + '  ' + label.padEnd(34) + a.toFixed(4) + '  vs  ' + b.toFixed(4));
};

chk('S_Y(4.0) = (N0/2)(2π·4)²', SY(4), 0.5*Math.pow(2*Math.PI*4,2));
chk('S_Y(5.0) = (N0/2)(2π·5)²', SY(5), 0.5*Math.pow(2*Math.PI*5,2));
chk('S_Y(6.0) = (N0/2)(2π·6)²', SY(6), 0.5*Math.pow(2*Math.PI*6,2));
chk('S_Y(3.9) 带外 = 0', SY(3.9), 0);
chk('S_Y(0)   带外 = 0', SY(0), 0);
chk('S_c(0) 搬移', SC(0), SC_analytic(0));
chk('S_c(-1) 搬移', SC(-1), SC_analytic(-1));
chk('S_c(1) 搬移', SC(1), SC_analytic(1));
chk('S_c(1.5) 带外 = 0', SC(1.5), 0);
console.log('\n关键数值：');
console.log('  S_c(0)   = ' + SC(0).toFixed(2) + '  = 100π²');
console.log('  S_c(±1)  = ' + SC(1).toFixed(2) + '  = 104π²  （比中心高 ' + ((SC(1)/SC(0)-1)*100).toFixed(1) + '%）');
console.log('  S_Y(±5)  = ' + SY(5).toFixed(2) + '  = 50π²');
console.log(bad === 0 ? '\n解析式与搬移结果一致' : '\n有 ' + bad + ' 项不一致');
process.exit(bad === 0 ? 0 : 1);
