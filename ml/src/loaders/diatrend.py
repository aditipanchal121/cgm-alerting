"""DiaTrend loader - STUB, not yet implemented.

Source: PhysioNet, credentialed access (standard PhysioNet DUA + CITI
training certificate) - see https://physionet.org/content/diatrend/
Jaidah, Prioleau et al., "DiaTrend: A dataset from advanced diabetes
technology to enable scientific research," Nature Scientific Data 2023.

Largest of the datasets considered here with real insulin+carb logs
alongside CGM (54 T1D patients, 27,561 patient-days of CGM, mixed
Dexcom/Abbott/Medtronic sensors at ~5 min) - the primary candidate for the
population fine-tuning stage (see ../../README.md's staged roadmap), not
just an eval set.

Format: per-subject files (verify exact structure against the actual
downloaded files once you have PhysioNet access - not yet confirmed here).
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd


def load(data_dir: str | Path, subject_id: str) -> pd.DataFrame:
    raise NotImplementedError(
        "Requires PhysioNet-credentialed DiaTrend files - see this module's docstring. "
        "Implement by parsing the per-subject CGM/bolus/carb records into the canonical "
        "schema (see schema.py) once the files are available locally."
    )
