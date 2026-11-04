// Sample script for the "Custom JavaScript" task.
// Submit it with input such as {"n": 30}.

function run(input, progress) {
  const n = input.n || 20;
  const sequence = [0, 1];
  for (let i = 2; i < n; i++) {
    sequence.push(sequence[i - 1] + sequence[i - 2]);
    progress((i / n) * 100, `Computed ${i + 1} of ${n} numbers`);
  }
  console.log(`Done: ${n} Fibonacci numbers`);
  return { n, last: sequence[n - 1], sequence: sequence.slice(0, n) };
}
