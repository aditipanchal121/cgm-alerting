"""ShanghaiT1DM / ShanghaiT2DM loader - STUB, not yet implemented.

Source: open download via Figshare, no DUA -
https://figshare.com/collections/Diabetes_Datasets_ShanghaiT1DM_and_ShanghaiT2DM/6310860
Zhao, Zhu, Wang, Rao, Nature Scientific Data 2023.
T1D: 12 patients; T2D: 100 patients; 3-14 days each; FreeStyle Libre at
**15-minute** sampling - not 5-minute like every other source here (see
schema.NATIVE_SAMPLING_MINUTES). Also includes daily dietary records and
clinical/lab characteristics. Short per-subject duration and the coarser
sampling interval make this best used as an out-of-distribution robustness
check (different sensor, different population, different cadence), not
folded into the primary training mix without resampling - see
preprocessing.resample_to_grid.
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd


def load(data_dir: str | Path, subject_id: str, cohort: str) -> pd.DataFrame:
    """`cohort` is "t1dm" or "t2dm" - the two collections use the same file
    format but should stay labeled separately in `source` (see schema.py)."""
    raise NotImplementedError(
        "Requires the ShanghaiT1DM/T2DM Figshare files - see this module's docstring. "
        "Implement by parsing the per-subject Excel/CSV files into the canonical schema "
        "(see schema.py) once the files are downloaded locally."
    )
