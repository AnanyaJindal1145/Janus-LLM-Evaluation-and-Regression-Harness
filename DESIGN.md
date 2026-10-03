# Janus — Design Doc & Build Spec

**Author:** Ananya Jindal · **Status:** v1 (locked) · **Date:** 2026-09-20

Janus = an LLM evaluation & regression harness ("CI-for-prompts"). This doc is a
personal interview reference — real engineering deliberation, no corporate ritual.

---

## PART A — DESIGN DOC

### 1. Problem statement
Teams building on LLMs constantly change prompts, model versions, and inference
params. Each change can silently degrade output quality (a reworded prompt that
improves 90% of cases but breaks JSON on 10%; a model upgrade that improves
reasoning but regresses instruction-following). Unlike code changes, these
regressions are invisible to conventional tests: it compiles, returns 200, and
output *looks* plausible. The failure is semantic, probabilistic, only visible in
aggregate. Common practice is "eyeball a few outputs and ship" — doesn't scale,
not reproducible. Need: a harness that treats prompt/model changes like CI treats
code changes — given a versioned suite + two target versions, run both, score with
deterministic checks + LLM judge, and decide **with statistical confidence** whether
the new version regressed, on which cases, by how much — enough to block a release.

### 2. Goals / non-goals
**Goals:** run versioned suite against configurable target (prompt × model × params)
and persist; composable deterministic scorers + calibrated LLM-as-judge; regression
detection via defensible statistical rule (not raw threshold on a mean); measure judge
trustworthiness vs human labels; cost + latency as first-class scored dimensions;
CI integration (fail build on regression); run cheaply/locally on a student budget.
**Non-goals:** NOT a web dashboard / multi-tenant SaaS (CLI-first, single user);
NOT a prompt-authoring or prompt-optimization tool (measures, doesn't generate);
NOT training/fine-tuning; NOT a live-traffic observability platform (offline suites);
NOT model-hosting.

### 3. Requirements & success metrics
Load suites/targets from versioned YAML; N samples/case for non-determinism;
pluggable scorer pipeline; persist runs; compare two runs → per-case + aggregate
verdict; CI-consumable report. Key metrics: **verdict correctness** (flags injected
bad change; ~0 false positives on no-op re-runs) is #1; **judge–human agreement**
(accuracy + Cohen's κ, target ≥0.7); throughput/wall-clock; **$/run**; reproducibility.

### 4. Approaches considered (decisions in bold)
**4.1 Scoring:** deterministic-only (free/trustworthy but can't judge open text) vs
LLM-judge-only (handles open text but non-deterministic/biased/unverified) vs human
(gold but doesn't scale). **→ Hybrid: deterministic + judge, with a one-time
human-labeled set used to *calibrate/measure* the judge, not grade every run.**
**4.2 Non-determinism:** 1 sample/case (cheapest but a single draw from a stochastic
process isn't a measurement — invalidates the tool) vs **N samples/case, reason about
distributions (N configurable, default 3–5).** N-sampling is *why* the project needs
stats + concurrency.
**4.3 Regression rule:** raw threshold on mean (ignores variance — no right threshold)
vs t-test (assumes normality/adequate N; LLM scores are bounded/skewed/bimodal, small
N — assumptions fail) vs **bootstrap CI on the score delta (no normality assumption,
works with small N + weird distributions, interpretable interval).** The intellectual
core.
**4.4 Persistence:** flat files (simple but querying comparisons/trends is hand-rolled)
vs **SQLite (real SQL, zero-ops, single file, portable — fits a single-user CLI
exactly)** vs Postgres (always-on service, overkill). Postgres = documented
"if multi-user" note.
**4.5 Concurrency:** see §6.1 — Kotlin coroutines + bounded semaphore.
**4.6 Language:** **Kotlin + thin Spring Boot** — over Go (don't want to signal a
systems career I'm not targeting; fluency > shine) and Python (weaker concurrency for
the N-sampling fan-out; want an engineering artifact not a notebook). Leans on Amazon
stack; narrative = "extended my backend work into AI infrastructure."

### 5. Chosen design (overview)
CLI pipeline. `eval run` loads a Suite (versioned YAML Cases) + Target (prompt version
+ model + params), executes each case N× concurrently through a rate-limited retrying
runner, pipes outputs through a Scorer chain (deterministic first, judge for the rest),
persists every score to SQLite tagged with target version + run ID. `eval compare
<runA> <runB>` pulls both from SQLite, applies the bootstrap rule per metric, emits
per-case + aggregate verdict. `eval report` renders CLI table + HTML + JUnit XML. A
GitHub Action runs run+compare on PRs touching prompt files and fails the check on a
flagged regression. Five pluggable abstractions: `Model`, `Scorer`, `Runner`,
`Comparator`, `Reporter`. Uniform `Scorer` interface (deterministic + judge behind one
type) is the clean-layering signal.

### 6. Deep dives
**6.1 Concurrency/latency:** workload = (cases × N) independent I/O-bound API calls.
Sequential is minutes-to-tens-of-minutes. **Kotlin coroutines + explicit
`Semaphore(maxConcurrency)`** — bound is non-optional backpressure (stays under provider
rate limits, protects wallet). Same instinct as Amazon SQS/DLQ work. Wall-clock collapses
to ≈ (total calls ÷ maxConcurrency) × mean-latency; report the speedup.
**6.2 Resilience:** retry 429/transient-5xx/timeout with **exponential backoff + jitter**
(jitter avoids synchronized retry stampede across concurrent workers), capped attempts;
non-429 4xx fails fast, recorded as scored error. One case's failure never aborts the suite.
**6.3 Cost (student budget = design constraint):** judge is the main cost driver
(per open-ended case × N) → judge model is an exposed cost/quality knob; **caching keyed
by (case content, target version, params)** makes unchanged re-runs free (also makes the
20× no-op control nearly free); small default N; Ollama-local subject model for $0 dev;
hard per-run spend cap in config; publish **$/run** as a headline metric.
**6.4 Trusting the judge (calibration):** hand-label 30–50 cases; run judge (temp=0,
structured score+reasoning output so verdicts are auditable); compute accuracy +
Cohen's κ + confusion matrix; iterate rubric; report before/after κ. Below a κ threshold,
don't trust the judge for that suite and say so. Honesty centerpiece.
**6.5 Reproducibility:** suites/targets versioned YAML in git; each run persists target
version, params, model id, timestamp, N; judge at temp=0; subject sampling is the only
intended nondeterminism, explicitly characterized.

### 7. Risks & mitigations
Low-κ judge → calibration gates it; fall back to deterministic-only + say so.
Cost overrun → local model + caching + small N + hard cap.
Scope creep (real killer) → §2 non-goals binding; stop at M7; no dashboard.
Can't defend the stats in interview → build bootstrap by hand, not just library call.

### 8. Resolved design decisions (defensible on a whiteboard)
**8.1 Tolerance band — derive empirically.** Run identical target ~20× against itself,
measure spread of observed delta (95th pct of |delta| across no-op comparisons) = noise
floor. Flag only when the change clears **both** bars: bootstrap CI excludes zero (real)
AND delta exceeds the noise floor (practically real). "I measured my own pipeline's noise
and required regressions to exceed it."
**8.2 Bootstrap at the case level.** N samples for one case are NOT independent (repeated
draws from that case's distribution). Collapse each case's N samples to one per-case score
(mean) first, then resample **cases** with replacement. Sample-level resampling fakes a
larger N → falsely narrow CIs.
**8.3 Pointwise judging** (O(cases), linear cost) over pairwise A-vs-B (more reliable —
sidesteps calibration drift — but O(pairs), multiplies judge cost). Deliberate cost
tradeoff; "I'd switch to pairwise if budget allowed."

---

## PART B — BUILD SPEC

### Tech stack (fixed)
- Language: **Kotlin** · Framework: **Spring Boot (thin — DI/config/CLI only, not a web app)**
- CLI: picocli or Spring Shell — `eval run` / `eval compare` / `eval report` (CI calls the CLI)
- Build: Gradle · Storage: **SQLite** · Stats: **Apache Commons Math** (bootstrap)
- Config: YAML suites + targets, versioned in git · CI: GitHub Actions

**Providers ($0 stack — student budget, based in India, no paid API):** all behind the one `Model` interface.
- **Judge: Google Gemini free tier** (AI Studio key, no card, genuine free tier w/ rate limits). Judge is the one place model quality moves the headline κ number; Gemini Flash is capable enough for bounded rubric grading. temp=0.
- **Subject-under-test: Ollama, local, $0 forever** (Llama 3.2 3B / Qwen 2.5 7B / Phi). We *want* an imperfect subject so the regression detector has real regressions to catch.
- **Second provider (optional, abstraction-proof): Groq free tier** (hosts open models, free key, fast) — demonstrates `Model` isn't hardcoded to one SDK.
- Free-tier **rate limits are a feature, not a bug**: they give M5.2 backoff+jitter and M5.1 bounded semaphore (set maxConcurrency low, 2–4) a genuine operational reason to exist; caching keeps the 20× no-op control mostly cache-hit. "Tuned semaphore + backoff to stay under free-tier limits" beats "paid for unlimited quota" in an interview.
- No Anthropic/OpenAI: both are pay-as-you-go with no standing free tier.

### Architecture
```
   eval CLI  ──▶  ┌──────────────────────────────────────────┐
 (CI calls it)    │  Harness (Kotlin / thin Spring)           │
                  │  1. Load Suite (versioned YAML Cases)     │
                  │  2. Load Target (prompt vN + model+params)│
                  │  3. Runner: N samples/case, coroutines,   │
                  │     Semaphore(maxConcurrency) + retry     │
                  │  4. Scorer chain: deterministic + judge   │
                  │  5. Persist Run + Scores ───────────────┐ │
                  │  6. Comparator: bootstrap CI, two-bar    │ │
                  │  7. Reporter: CLI + HTML + JUnit XML      │ │
                  └───────┬──────────────────┬──────────────┼─┘
                          ▼                  ▼              ▼
                 Anthropic/OpenAI/Ollama   SQLite      GitHub Actions
```
Abstractions: `Model` (one impl/provider) · `Scorer` ((Case,Output)→Score; deterministic
+ judge, one uniform interface) · `Runner` (concurrency+resilience) · `Comparator` (stats)
· `Reporter` (formats).
Conceptual model: `Case` → `Suite` → `Target` (prompt × model × params) → `Run` (Suite ×
Target, N samples/case, persisted Scores) → `Comparison` (two Runs → verdict).

### Milestones (each independently demo-able)
| # | Milestone | Demo |
|---|-----------|------|
| M1 | `Model` interface + run one case | `eval run --suite qa.yaml` prints outputs |
| M2 | Deterministic scorers (exact/regex/contains/JSON-schema/numeric-tol) + SQLite persist | scored pass/fail table; history queryable |
| M3 | LLM-as-judge scorer (rubric, temp=0, structured score+reasoning) | free-text graded w/ visible reasoning |
| **M4** ⭐ | **Judge calibration** — hand-label 30–50, report accuracy + Cohen's κ + confusion matrix, iterate rubric | "agrees 88%, κ=0.76" + matrix |
| **M5** ⭐ | **N-sampling + bootstrap regression detection** — concurrent (semaphore) sampling, retry/backoff+jitter, case-level bootstrap, two-bar verdict | `eval compare v1 v2` → "−4.2% [CI −6.1%,−2.3%], REGRESSION on 7 cases" |
| M6 | Cost/latency first-class scored axes + tradeoff report | "v2: +1.5% quality, +90% cost → flagged" |
| M7 | CI integration (JUnit XML + GH Action on prompt PRs) + HTML report | PR worsening a prompt → check red w/ per-case diffs (GIF it) |

M4 + M5 are the project; rest is scaffolding. Time-crunched → cut M6, never M4/M5.
~5–6 weekends alongside the internship. Stop at M7 — no dashboard, no multi-tenant.

### Metrics to publish (README table, own numbers)
- **Judge quality** ⭐ — accuracy + Cohen's κ (≥0.7) + confusion matrix; κ improvement from rubric iteration (e.g. 0.61→0.76)
- **Regression-detection validity** — catches injected bad change; **0 false regressions across 20 no-op controls**
- **Throughput** — cases/sec + full-suite wall-clock; coroutine+semaphore speedup vs sequential
- **Cost** — $/run; cache-driven re-run reduction
- **A real finding** — run against two real model/prompt versions, report a genuine regression (e.g. JSON-validity 98%→91% on a model upgrade)

### Resume bullet
> **LLM Evaluation & Regression Harness (Janus)** | *Kotlin, Spring Boot, SQLite, GitHub Actions*
> - Built CI-integrated eval framework detecting prompt/model regressions via case-level bootstrap confidence intervals over N-sampled outputs; 0 false positives across 20 no-op control runs
> - Engineered LLM-as-judge with temp=0 structured grading, calibrated against human labels to Cohen's κ=0.76 (up from 0.61 after rubric iteration)
> - Emitted JUnit XML for CI gating; PRs that regress prompt quality fail the build with per-case diffs — caught a real 98%→91% JSON-validity regression on a model upgrade

---

## PART C — IMPLEMENTATION DESIGN CALLS (interview defense, per milestone)

Rationale for choices made *while building* — the "why did you do it this way" answers. Appended as each milestone lands.

### M1 — pipe (Model interface + Suite loader + CLI)
- **No Spring Boot yet.** Spec says Spring stays thin (DI/config only); M1 has nothing to inject. Adding it now would only fight Java 24 compat for zero benefit. Goes in when a milestone needs it. Interview answer: "I added it when a milestone justified it, not on reflex."
- **`Model.complete` is `suspend` from day one** even though M1 calls it sequentially — so the M5 coroutine+semaphore runner drops in with no interface refactor. Main.kt marks exactly where M5 replaces the loop.
- **Offline `EchoModel`** — whole pipeline runs end to end with no API key / no spend ($0-dev principle); real providers implement the same interface later with no pipeline change.
- **`SuiteLoader` fails loud** (`FAIL_ON_UNKNOWN_PROPERTIES`, duplicate-id + empty-suite checks) — a mislabeled field in a test suite must not pass silently.

### M2 — deterministic scorers + SQLite persistence
- **`Scorer.score` is `suspend`** though deterministic scorers are synchronous — so the M3 LLM judge (a network call) implements the SAME interface. "Deterministic + judge as one type" is the layering the doc calls the SDE-2 signal.
- **`Score.value` is continuous [0,1], not a boolean** — even for pass/fail (0.0/1.0). So M5 stats treat every metric identically; no special-casing booleans vs graded judge scores.
- **Schema grain = one row per (run, case, metric)** — exactly the grain `eval compare` queries in M5 (scores for one metric across two run_ids); indexed `(run_id, metric)` now.
- **`Run` is clock-free** — timestamp injected at the CLI boundary (`System.currentTimeMillis()` in Main, not in library code) — so runs are reproducible/testable (matches §6.5).
- **SQLite over Postgres** — single-user CLI persisting history for later comparison is exactly SQLite's sweet spot; "chose the tool that fit the workload, not the biggest hammer."

### M3 — LLM-as-judge scorer
- **Judge is a `Scorer` like the deterministic ones** — same interface, so M5's runner and M2's persistence treat it identically. The uniform layering is now real, not aspirational.
- **Structured `{label, reasoning}`, both persisted.** Label → score via `labelScores` so M5 treats it like any metric; reasoning stored for M4 auditability. Parse is tolerant (strips markdown/prose around the JSON) because real models wrap output — proven by a test.
- **temp=0 on the judge** (from `JudgeConfig`), separate from the subject's params — a judge that flip-flops on re-run can't be calibrated.
- **$0 provider stack behind the interface**: `FakeJudgeModel` (offline demo/tests), `GeminiModel` (real judge, key from env — never hardcoded), `OllamaModel` (real subject). Swapping to the real judge is a one-line YAML change, no code change — the point of the abstraction.
- **`ProviderException.isRetryable`** classifies 429/5xx/timeout now, so M5's backoff wrapper has its signal — free-tier rate limits are the real reason it exists.

### M4 — judge calibration (Cohen's κ + confusion matrix + dev/holdout)
- **Cohen's κ hand-rolled, not a library call** — it's the number you defend on a whiteboard, derivation is short (κ = (Pₒ−Pₑ)/(1−Pₑ)). A unit test bakes in the "raw accuracy lies under class imbalance" argument: judge-says-PASS-every-time scores 0.5 agreement but κ=0.0.
- **Deterministic dev/holdout split via stable FNV-1a hash** — same split every run, no stored RNG seed, reproducible (§6.5); ranked by hash so which cases land in holdout doesn't correlate with id order. This is the overfitting guard: tune rubric on dev, report κ on holdout never touched. Same discipline as a model train/test split — and the honest answer to "how do you know your rubric improvements aren't overfit?"
- **Judge grades the SAME output the human labeled** (`GoldLabel.output`) — otherwise the two verdicts aren't comparable.
- **`UNPARSED` sentinel** — unparseable/unknown judge verdict counts as a disagreement in the matrix rather than crashing the run. A judge that won't emit valid JSON is failing, and κ should reflect that, not hide it.
- **Landis–Koch bands** (slight/fair/moderate/substantial/near-perfect) for a human-readable verdict; target ≥0.7 = "substantial", below which we don't trust the judge for that suite (§6.4 fallback to deterministic-only).
- **Same $0 provider resolution as the registry** — `fake-judge` offline (κ machinery fully testable at $0), `gemini*` on free tier for the real number.

### M5 — N-sampling runner + bootstrap regression detection
- **Bounded `Semaphore(maxConcurrency)` over coroutines** — backpressure that holds the in-flight cap *across all cases*, not per-case (the semaphore is shared, the async fan-out isn't). Caps at the resource that matters (outbound rate / rate limits), not threads.
- **Retry = exponential backoff + full jitter**; jitter de-correlates workers that all 429 at the same instant (prevents the synchronized-retry stampede). Non-429 4xx fails fast. `rng` injected for deterministic tests.
- **Samples kept grouped under their case** (`CaseResult.samples`) — M5.3 collapses per-case first, so the data structure must preserve the case→[samples] grouping; flattening is the bug that silently breaks the stats.
- **Case-level PAIRED bootstrap** — collapse N samples to one per-case mean, then resample *cases* (same index for old+new). Sample-level resampling fakes larger N → falsely narrow CIs; unpaired resampling inflates variance. Deterministic resample index from (seed,iter,pos) hash → identical verdict on re-run (reproducibility + stable CI gate).
- **Two-bar verdict**: REGRESSION only if CI excludes zero (statistically real) AND |delta| > measured noise floor (practically real). A test proves a real-but-tiny −0.05 drop below a 0.10 floor is NO_CHANGE.
- **Noise floor is measured, not guessed** — same target vs itself ~20×, 95th pct of |delta| across no-op pairs. "I measured my pipeline's own noise and required regressions to exceed it."

### M6 — cost/latency as first-class axes
- Latency + tokens carried on every `Sample`, aggregated per-case (`meanLatencyMs`, `totalTokens`); `run` prints wall-clock + total tokens + mean call latency. Cost/latency live alongside quality so a change that improves quality 1% but doubles cost is visible.

### M7 — CI integration + reporting
- **JUnit XML is the gate**: one `<testcase>` per metric, a REGRESSION becomes a `<failure>` → GitHub Actions goes red natively (no custom parsing). `compare` exits 1 on regression so a shell/CI step gates directly.
- **HTML + CLI table** reporters for humans; same `MetricComparison` feeds all three.
- **Credential-free CI**: the workflow runs the gate against the offline echo/fake-judge path (committed fixtures), so no cloud keys live in CI. Live calls only happen locally to refresh fixtures. Better engineering than "CI calls the model", and a strong interview line.
- `.github/workflows/ci.yml` is a local file only — running it requires pushing to GitHub, which is the user's action, not the harness's.

---

## Name
Janus (Roman god of doorways/transitions, two faces looking opposite ways) = the
comparator looks at two versions at once, before and after (version N-1 vs N).
