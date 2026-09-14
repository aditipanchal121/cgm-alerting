# Data acquisition

Nothing in this directory is committed (see `.gitignore`) - this is real
health data, both your brother's and other people's. Download instructions
below are manual; none of this is automatable, since every public dataset
here gates access behind a DUA/credentialing step by design.

## Vigil's own export (works today)

```
cd ../../backend/functions
npm run export-training-data -- <patientId>
```

Writes a CSV to `backend/functions/training-data-export/` - see that
script's own doc comment for credential setup. Load it with
`ml/src/loaders/vigil_export.load(csv_path, subject_id)`.

## Public datasets (loaders are stubs pending access - see ml/src/loaders/)

| Dataset | How to get it | Loader |
|---|---|---|
| DiaTrend | PhysioNet credentialed access - https://physionet.org/content/diatrend/ (requires a completed CITI training certificate) | `loaders/diatrend.py` |
| OhioT1DM | Email razvan.bunescu@charlotte.edu from a **.edu address**; DUA + ~1 week turnaround - https://webpages.charlotte.edu/rbunescu/data/ohiot1dm/OhioT1DM-dataset.html | `loaders/ohio_t1dm.py` |
| ReplaceBG | Request via Jaeb Center portal - https://public.jaeb.org/dataset/546 | `loaders/replacebg.py` |
| ShanghaiT1DM/T2DM | Open download, no DUA - https://figshare.com/collections/Diabetes_Datasets_ShanghaiT1DM_and_ShanghaiT2DM/6310860 | `loaders/shanghai.py` |

Once a dataset is downloaded, implement its loader's `load()` function
(each stub's docstring describes what's documented about its format) and
add a matching row to `ml/README.md`'s dataset table if the suitability
assessment there needs revising once you've actually seen the files.
