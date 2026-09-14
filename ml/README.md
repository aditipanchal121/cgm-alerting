# Vigil ML: fine-tuning a glucose forecasting model

Plan + framework for fine-tuning a time-series foundation model (Amazon
Chronos) on CGM data, first from public datasets, then personalized to your
brother's own data once enough has accumulated. **Not started yet** - see
"Status" below for exactly what does and doesn't run today. No Firebase
access anywhere in this directory; it only reads local files (an
already-exported CSV, or downloaded public-dataset files).

## Why Chronos, and why fine-tune rather than use it zero-shot

The closest published prior work to this exact task -
[Evaluating Time-Series Foundation Models and Multimodal Dietary Context for
CGM Forecasting (arXiv:2609.11872)](https://arxiv.org/html/2609.11872) -
benchmarked Chronos-Bolt, Chronos-2, and TimesFM 2.5 zero-shot against
classical/task-specific baselines (ARIMA, LSTM, PatchTST) on public CGM
data, and found **zero-shot foundation models did not consistently beat a
well-tuned baseline**. But **fine-tuning Chronos-Bolt gave a 6.5-18.4% RMSE
reduction** over zero-shot, on both T1D and non-diabetes cohorts, and the
gains held up on out-of-distribution test subjects. That result is the
whole rationale for this being a fine-tuning project, not a "just call the
pretrained model" one - and it's why baselines.py (below) exists to keep
zero-shot/fine-tuned Chronos honest against what's already running in
production.

**Model**: [amazon/chronos-bolt-small](https://huggingface.co/amazon/chronos-bolt-small)
as the starting checkpoint - non-autoregressive (single forward pass per
forecast, unlike original Chronos-T5's token-by-token generation), Apache-2.0,
CPU-fast, and the specific variant the closest prior-work paper fine-tuned.
`amazon-science/chronos-forecasting`'s fine-tuning path
(`scripts/training/train.py`) is well documented and supports pushing
checkpoints straight to the HF Hub.

**Alternatives considered, not chosen for v1:**
- **Chronos-2** (Oct 2025) natively supports covariates (IOB/carbs as real
  input channels, via group attention over per-variable patches) rather than
  Chronos-Bolt's univariate-only design - the more architecturally correct
  choice long-term, but new enough (weeks old at time of writing) that
  starting with the better-established Bolt path first, then migrating once
  Chronos-2's fine-tuning tooling has more real-world mileage, is the lower-risk
  sequencing.
- **Moirai** ([Salesforce, arXiv:2402.02592](https://arxiv.org/pdf/2402.02592))
  is the most credible non-Chronos alternative specifically because it's the
  only one of these architected from the ground up for arbitrary covariates
  (not bolted on). Worth a head-to-head benchmark in evaluate.py once there's
  a working Chronos pipeline to compare it against - not a v1 blocker.
- **TimesFM 3** (Google) added native covariate support and LoRA fine-tuning
  recently too - a reasonable second opinion, same reasoning as Moirai.
- **PatchTST** ([arXiv:2211.14730](https://arxiv.org/abs/2211.14730)) isn't
  a pretrained foundation model - it's the architecture the literature uses
  as the strong *from-scratch supervised* baseline. Worth implementing in
  baselines.py eventually, alongside Vigil's existing rule-based predictors,
  so "does a foundation model actually buy anything over a much smaller
  purpose-built model" is an answered question, not an assumption.

## Covariates: IOB and carbs

Chronos-Bolt is **univariate** - no native way to feed it insulin-on-board
or carbs as extra channels. Vigil's own rule-based predictors (see
`backend/functions-predict/predictors.py`) exist specifically because
glucose trajectory depends heavily on IOB/COB, so this matters. Three paths,
roughly in the order this project should try them:

1. **Ship glucose-only first.** The cited paper's core fine-tuning result
   (6.5-18.4% RMSE gain) was on CGM alone - a real result to reproduce
   before adding complexity.
2. **[ChronosX](https://arxiv.org/abs/2503.12107)** bolts covariate-injection
   modules onto a frozen pretrained Chronos without retraining the backbone -
   a middle ground that keeps Bolt's speed.
3. **Migrate to Chronos-2** once its covariate path has matured - the
   architecturally "right" long-term answer, per its own reported gains
   being concentrated specifically in covariate-informed tasks.

## Datasets

| Dataset | Access | Sampling | Insulin/carb logs | Role |
|---|---|---|---|---|
| [DiaTrend](https://physionet.org/content/diatrend/) | PhysioNet credentialed | ~5 min | Yes - pump basal/bolus + carbs | Primary population fine-tune source (54 T1D subjects, largest well-annotated set here) |
| [ReplaceBG](https://public.jaeb.org/dataset/546) | Jaeb portal request | 5 min | Partial (CGM/SMBG-focused) | Population-scale glucose-dynamics statistics |
| [OhioT1DM](https://webpages.charlotte.edu/rbunescu/data/ohiot1dm/OhioT1DM-dataset.html) | DUA via email (.edu only) | 5 min | Yes - bolus/meal/exercise/sleep | **Held out, eval only** - the literature-standard benchmark (annual BGLP Challenge uses it), so numbers reported against it are externally comparable |
| [ShanghaiT1DM/T2DM](https://figshare.com/collections/Diabetes_Datasets_ShanghaiT1DM_and_ShanghaiT2DM/6310860) | Open download | **15 min** (different from the rest) | Yes - daily diet + labs | OOD robustness check - different sensor (Libre), different sampling rate, don't fold into training without resampling |
| Vigil's own export | Already works (`exportTrainingData.ts`) | 5 min | Yes - real bolus/carb history + two IOB sources | **The actual deployment target** - stage 2 personalization |

Deliberately **not** using the BIG IDEAs Lab dataset as a training source -
its subjects are non-diabetic/pre-diabetic and not on insulin, so it can't
inform IOB-aware behavior; it's a candidate for a general glucose-dynamics
sanity check only, not scoped into this plan's v1.

See `data/README.md` for exact access instructions per dataset - all of
them require a manual step (DUA, portal request, or just a download) that
can't be automated from here.

## Staged roadmap

1. **Population fine-tune**: `amazon/chronos-bolt-small` → fine-tuned on
   DiaTrend + ReplaceBG (see `config/datasets.yaml`'s weighting). Evaluate
   against OhioT1DM (held out) for a literature-comparable number, and
   against ShanghaiT1DM/T2DM for OOD robustness.
2. **Personalization fine-tune**: continue fine-tuning the stage-1
   checkpoint on your brother's own exported data. Population-then-personal
   fine-tuning consistently outperforms either alone in this literature,
   given how much inter-patient variability there is in glucose dynamics -
   this is the step that's actually gated on "enough of his own data," not
   stage 1.
3. **Production integration** (see below) - not scoped into this framework
   yet, deliberately.

**How much of his data is "enough" for stage 2?** No hard number to give
honestly yet - this is a question the framework itself should answer once
there's a working stage-1 checkpoint, via a data-volume ablation (fine-tune
on increasing subsets of his exported history, plot RMSE/Clarke-A+B against
subset size, see where it plateaus) rather than a guess made in advance.
For a starting order of magnitude: OhioT1DM's own per-subject research
window is 8 continuous weeks, which the field treats as enough to
meaningfully characterize one person's glucose dynamics - a reasonable
initial target to accumulate toward, not a validated threshold for this
specific pipeline.

## Evaluation

Three numbers, not just RMSE, matching how this literature actually reports
results (see `src/evaluate.py`):

- **RMSE** (mg/dL) - the standard headline number, but bounded below by CGM
  sensor noise itself (Dexcom G6's own MARD is ~9% overall, worse in the
  first 12-24h after insertion) - a fine-tuned model's RMSE gain smaller
  than the sensor's own noise floor isn't a meaningful signal.
- **MARD** (%) - directly comparable to the sensor accuracy spec sheet.
- **Clarke Error Grid zones A-E**, with **A+B > 90%** as the conventional
  clinical-acceptability bar this literature holds itself to (Zone A =
  clinically accurate, Zone B = benign disagreement that wouldn't cause a
  treatment error). This is the number to lead with in anything
  safety-adjacent, not RMSE alone.

Every evaluation run compares against **Vigil's existing rule-based
predictors** (`src/baselines.py` imports `predictors.py` directly, unmodified,
from `backend/functions-predict/`) - the same standard the cited paper held
zero-shot Chronos to. A fine-tuned model that doesn't beat `multiBolus` (the
current best rule-based predictor - see its own docstring) on your brother's
actual data isn't worth deploying, however good its public-benchmark numbers
look.

## Domain pitfalls this plan accounts for

- **CGM lags true blood glucose**, and not just by the ~5-6 min interstitial
  equilibration delay - fibrous encapsulation around the sensor is the
  *dominant* lag source in practice, pushing real-world lag past 15-20+ min
  during rapid change. A "15-min-ahead" CGM forecast is a smaller true lead
  time than it looks.
- **Compression lows and sensor warm-up** (first 12-24h post-insertion) are
  known artifacts, not real physiology - `preprocessing.py`'s gap-handling
  doesn't currently detect these specifically; flagging and excluding them
  is a real gap in this framework worth closing before trusting any
  personalization-stage numbers, not before.
- **Personalization genuinely helps here** - this isn't a generic ML nicety,
  it's a specific, repeated finding in the glucose-forecasting literature,
  which is the actual justification for stage 2 existing at all rather than
  shipping a population model directly.

## What's implemented vs. stubbed (status)

Runs today, with a real test suite (`pytest tests/` - 19 tests passing,
including one that imports and runs the actual production
`predictors.py` against synthetic data):
- `src/schema.py` - canonical row format + validation
- `src/loaders/vigil_export.py` - loads exportTrainingData.ts's CSV
- `src/preprocessing.py` - grid resampling (with correct short-gap-only
  interpolation - verified directly against pandas' own `interpolate(limit=)`,
  which does *not* do what its docstring implies for a long gap), run
  splitting, windowing, Chronos Arrow export
- `src/baselines.py` - runs Vigil's real rule-based predictors against
  offline windows
- `src/evaluate.py` - RMSE, MARD, Clarke Error Grid (zone boundaries
  verified against a citable reference implementation, not written from
  memory)

Structurally complete, not yet runnable end to end:
- `src/loaders/diatrend.py`, `ohio_t1dm.py`, `replacebg.py`, `shanghai.py` -
  real function signatures and documented expected formats, `NotImplementedError`
  until the underlying DUA-gated files exist to test against
- `src/fine_tune.py` - generates a real Chronos training config and prints
  the exact command; requires `--run` to actually invoke anything, and even
  then needs a separate clone of `amazon-science/chronos-forecasting` with
  its own (much heavier, torch-based) environment - not this package's
  `requirements.txt`

Not started at all, on purpose (per project scope right now):
- Any actual fine-tuning run
- Production integration (below)

## Production integration (future, not implemented)

`predictors.py`'s existing plugin shape (`predict(readings, physiology,
horizon_minutes, iob_cob_history) -> (value, note)`) runs inline, synchronously,
inside a 256Mi Cloud Function on every 5-minute poll - fine for the current
rule-based math, but a real model (even Bolt-small) doesn't belong there:
model-loading and inference latency/memory don't fit a lightweight
synchronous request handler. The eventual integration point is a **separate
inference service** (Cloud Run with more memory, or a Vertex AI endpoint),
called asynchronously, writing into a new Firestore collection alongside the
existing `livePredictions`/`predictionAccuracy` so it's A/B-tracked against
the current predictors using infrastructure that already exists. None of
this is built yet - intentionally out of scope until there's a fine-tuned
checkpoint worth serving.

## Setup

```
cd ml
python -m venv venv
./venv/Scripts/python -m pip install -r requirements.txt   # lightweight deps only, see requirements.txt
./venv/Scripts/python -m pytest tests/ -v
```

## Key sources

- [Chronos: Learning the Language of Time Series (TMLR 2024)](https://www.stat.berkeley.edu/~mmahoney/pubs/2619_Chronos_Learning_the_Lang.pdf) / [amazon-science/chronos-forecasting](https://github.com/amazon-science/chronos-forecasting)
- [Chronos-Bolt](https://huggingface.co/amazon/chronos-bolt-base) / [Chronos-2 (arXiv:2510.15821)](https://arxiv.org/pdf/2510.15821) / [ChronosX (arXiv:2503.12107)](https://arxiv.org/abs/2503.12107)
- [Evaluating Time-Series Foundation Models and Multimodal Dietary Context for CGM Forecasting (arXiv:2609.11872)](https://arxiv.org/html/2609.11872) - closest prior work to this exact project
- [CGM-LSM (arXiv:2412.09727)](https://arxiv.org/abs/2412.09727), [GlucoFM (arXiv:2605.30865)](https://arxiv.org/html/2605.30865v1), [GlucoFM-Bench (arXiv:2606.06881)](https://arxiv.org/abs/2606.06881)
- [Moirai/Uni2TS (arXiv:2402.02592)](https://arxiv.org/pdf/2402.02592), [TimesFM](https://github.com/google-research/timesfm), [PatchTST (arXiv:2211.14730)](https://arxiv.org/abs/2211.14730)
- [Clarke error grid reference implementation](https://github.com/suetAndTie/ClarkeErrorGrid) (Clarke WL et al., Diabetes Care 1987)
- [DiaTrend (Nature Sci Data 2023)](https://www.nature.com/articles/s41597-023-02469-5), [OhioT1DM](https://webpages.charlotte.edu/rbunescu/data/ohiot1dm/OhioT1DM-dataset.html), [ShanghaiT1DM/T2DM](https://www.nature.com/articles/s41597-023-01940-7), [ReplaceBG](https://public.jaeb.org/dataset/546)
- [Fibrotic encapsulation as the dominant CGM lag source (Diabetes 2019)](https://diabetesjournals.org/diabetes/article/68/10/1892/35372/), [LoopDocs' IOB decay model](https://loopkit.github.io/loopdocs/operation/algorithm/prediction/)
