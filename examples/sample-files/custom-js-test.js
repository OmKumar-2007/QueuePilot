// Self-checking test script for the "Custom JavaScript" task.
//
// How to use: Submit page -> Task: Custom JavaScript -> script: upload this file ->
// input: one of the JSON values below -> Submit. The job panel then shows the outcome.
//
//   (leave empty)        Job SUCCEEDED, result.verdict = "PASS", all checks passed
//   {"name": "Alice"}    Same, and result.greeting = "Hello, Alice!" (input reached the script)
//   {"seconds": 20}      Same, but runs for 20 s so live progress can be watched (limit: 120 s)
//   {"fail": true}       Job FAILED with SCRIPT_ERROR and "Intentional test failure" (errors are reported)
//   {not json            Job FAILED with BAD_INPUT, and the script never runs

const crypto = require('crypto');

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function countPrimes(limit) {
  const composite = new Array(limit + 1).fill(false);
  let count = 0;
  for (let i = 2; i <= limit; i++) {
    if (composite[i]) continue;
    count++;
    for (let j = i * i; j <= limit; j += i) composite[j] = true;
  }
  return count;
}

function fibonacci(n) {
  let [a, b] = [0, 1];
  for (let i = 0; i < n; i++) [a, b] = [b, a + b];
  return a;
}

// Each check compares a computed value with a known correct answer.
const CHECKS = [
  ['primes below 10000', () => countPrimes(10000), 1229],
  ['fibonacci(50)', () => fibonacci(50), 12586269025],
  ['sorting', () => [5, 3, 9, 1].sort((a, b) => a - b).join(','), '1,3,5,9'],
  ['sha256 via a Node module', () => crypto.createHash('sha256').update('abc').digest('hex').slice(0, 16), 'ba7816bf8f01cfea'],
  ['JSON round trip', () => JSON.parse(JSON.stringify({ a: [1, 2] })).a[1], 2],
];

async function run(input, progress) {
  console.log(`Started on Node ${process.version} with input ${JSON.stringify(input)}`);
  if (input.fail) {
    throw new Error('Intentional test failure');
  }

  const stepMs = ((input.seconds || 2) * 1000) / CHECKS.length;
  const checks = [];
  for (const [index, [name, compute, expected]] of CHECKS.entries()) {
    await sleep(stepMs);
    const actual = compute();
    const passed = actual === expected;
    checks.push({ name, passed, actual, expected });
    console.log(`${passed ? 'PASS' : 'FAIL'}: ${name}`);
    progress(((index + 1) / CHECKS.length) * 100, `Checked ${name}`);
  }

  const passed = checks.filter(check => check.passed).length;
  return {
    verdict: passed === checks.length ? 'PASS' : 'FAIL',
    passed: `${passed}/${checks.length}`,
    greeting: `Hello, ${input.name || 'QueuePilot'}!`,
    nodeVersion: process.version,
    checks,
  };
}
