// 表达式求值器自检（node evalcheck.js）
// 这份实现是原型里「spec → 采样点」的核心，也是将来移植到 Kotlin 侧的参照：
// 只做白名单函数 + 白名单变量，绝不 eval 字符串。

const MATH_CONST = { pi: Math.PI, e: Math.E };
const MATH_FUNC = {
  sin: Math.sin, cos: Math.cos, tan: Math.tan, asin: Math.asin, acos: Math.acos,
  atan: Math.atan, exp: Math.exp, ln: Math.log, log: Math.log10, log2: Math.log2,
  sqrt: Math.sqrt, abs: Math.abs, floor: Math.floor, ceil: Math.ceil,
  round: Math.round, sign: Math.sign, sinh: Math.sinh, cosh: Math.cosh, tanh: Math.tanh,
};
const PREC = { '+': 1, '-': 1, '*': 2, '/': 2, '^': 3 };
// 一元负号优先级取 2.5：低于 ^(3)、高于 *(2)。
// 这样 -x^2 解析成 -(x^2)，而 -x*y 解析成 (-x)*y，与数学惯例一致。
// 另外前缀一元运算符入栈时不弹出任何东西，2^-1 才能正确解析成 2^(0-1)。
const UNARY_PREC = 2.5;

function tokenize(src) {
  const out = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) { i++; continue; }
    if (/[0-9.]/.test(c)) {
      let j = i;
      while (j < src.length && /[0-9.]/.test(src[j])) j++;
      out.push({ t: 'num', v: parseFloat(src.slice(i, j)) });
      i = j;
      continue;
    }
    if (/[A-Za-z_]/.test(c)) {
      let j = i;
      while (j < src.length && /[A-Za-z_0-9]/.test(src[j])) j++;
      out.push({ t: 'name', v: src.slice(i, j) });
      i = j;
      continue;
    }
    if ('+-*/^(),'.includes(c)) { out.push({ t: 'op', v: c }); i++; continue; }
    throw new Error('非法字符: ' + c);
  }
  return out;
}

function toRpn(tokens) {
  const out = [];
  const ops = [];
  let prev = null;
  for (let k = 0; k < tokens.length; k++) {
    const tk = tokens[k];
    if (tk.t === 'num') { out.push(tk); prev = tk; continue; }
    if (tk.t === 'name') {
      const next = tokens[k + 1];
      if (MATH_FUNC[tk.v] && next && next.t === 'op' && next.v === '(') {
        ops.push({ t: 'func', v: tk.v });
      } else {
        out.push(tk);
      }
      prev = tk;
      continue;
    }
    const v = tk.v;
    if (v === '(') { ops.push(tk); prev = tk; continue; }
    if (v === ')') {
      while (ops.length && ops[ops.length - 1].v !== '(') out.push(ops.pop());
      ops.pop();
      if (ops.length && ops[ops.length - 1].t === 'func') out.push(ops.pop());
      prev = tk;
      continue;
    }
    if (v === ',') {
      while (ops.length && ops[ops.length - 1].v !== '(') out.push(ops.pop());
      prev = tk;
      continue;
    }
    const isUnary = (v === '-' || v === '+') &&
      (prev === null || (prev.t === 'op' && prev.v !== ')'));
    if (isUnary) {
      // 前缀运算符：直接压栈，不弹出任何东西（它的操作数还没解析完）
      ops.push({ t: 'op', v: 'u-' });
      prev = tk;
      continue;
    }
    const p = PREC[v];
    while (ops.length) {
      const top = ops[ops.length - 1];
      if (top.v === '(') break;
      const tp = top.v === 'u-' ? UNARY_PREC : PREC[top.v];
      // ^ 右结合，同级不弹
      const shouldPop = v === '^' ? tp > p : tp >= p;
      if (shouldPop) out.push(ops.pop());
      else break;
    }
    ops.push(tk);
    prev = tk;
  }
  while (ops.length) out.push(ops.pop());
  return out;
}

function evalRpn(rpn, vars) {
  const st = [];
  for (const tk of rpn) {
    if (tk.t === 'num') { st.push(tk.v); continue; }
    if (tk.t === 'name') {
      if (Object.prototype.hasOwnProperty.call(vars, tk.v)) st.push(vars[tk.v]);
      else if (Object.prototype.hasOwnProperty.call(MATH_CONST, tk.v)) st.push(MATH_CONST[tk.v]);
      else throw new Error('未知符号: ' + tk.v);
      continue;
    }
    if (tk.t === 'func') { st.push(MATH_FUNC[tk.v](st.pop())); continue; }
    if (tk.v === 'u-') { st.push(-st.pop()); continue; }
    const b = st.pop();
    const a = st.pop();
    if (tk.v === '+') st.push(a + b);
    else if (tk.v === '-') st.push(a - b);
    else if (tk.v === '*') st.push(a * b);
    else if (tk.v === '/') st.push(a / b);
    else if (tk.v === '^') st.push(Math.pow(a, b));
  }
  if (st.length !== 1) throw new Error('表达式不完整');
  return st[0];
}

/** 把 2x / 2(x+1) / (x+1)(x-1) 这类隐式乘法补成显式 * —— 模型很爱这么写。 */
function insertImplicitMul(tokens) {
  const out = [];
  for (const cur of tokens) {
    const prev = out[out.length - 1];
    if (prev) {
      const prevIsValue =
        prev.t === 'num' ||
        (prev.t === 'name' && !(MATH_FUNC[prev.v] && cur.t === 'op' && cur.v === '(')) ||
        (prev.t === 'op' && prev.v === ')');
      const curStartsValue =
        cur.t === 'num' || cur.t === 'name' || (cur.t === 'op' && cur.v === '(');
      if (prevIsValue && curStartsValue) out.push({ t: 'op', v: '*' });
    }
    out.push(cur);
  }
  return out;
}

function compile(expr) {
  const rpn = toRpn(insertImplicitMul(tokenize(expr)));
  return vars => evalRpn(rpn, vars);
}

const cases = [
  ['x^2', { x: 3 }, 9],
  ['2*x^2 + 1', { x: 2 }, 9],
  ['-x^2', { x: 2 }, -4],
  ['sin(pi/2)', {}, 1],
  ['sin(2*pi*5*t)', { t: 0.05 }, Math.sin(Math.PI * 0.5)],
  ['sqrt(abs(-16))', {}, 4],
  ['1/x', { x: 0 }, Infinity],
  ['2^(-1)', {}, 0.5],
  ['exp(ln(5))', {}, 5],
  ['cos(0)+tan(0)', {}, 1],
  ['(1+2)*3', {}, 9],
  ['ln(e)', {}, 1],
  ['x^2-2x', { x: 3 }, 3],
];

let bad = 0;
for (const [expr, vars, want] of cases) {
  let got;
  try { got = compile(expr)(vars); } catch (e) { got = 'ERR:' + e.message; }
  const ok = (typeof got === 'number' && Math.abs(got - want) < 1e-9) || got === want;
  if (!ok) bad++;
  console.log((ok ? 'OK  ' : 'FAIL') + '  ' + expr.padEnd(16) + ' => ' + got);
}
console.log(bad === 0 ? '\n全部通过' : '\n有 ' + bad + ' 项失败');
process.exit(bad === 0 ? 0 : 1);
