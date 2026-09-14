"""OhioT1DM loader - STUB, not yet implemented.

Source: UC Cincinnati / Razvan Bunescu (now UNC Charlotte). Requires a Data
Use Agreement - email razvan.bunescu@charlotte.edu from a .edu address; see
https://webpages.charlotte.edu/rbunescu/data/ohiot1dm/OhioT1DM-dataset.html
Ships as an encrypted archive, password sent separately, ~1 week turnaround.

Format (per the dataset's published documentation - verify against the
actual files once you have access, this hasn't been tested against them):
per-subject XML files, one per ~8-week recording, with `<glucose_level>`
events every 5 min and separate `<bolus>`/`<meal>` event streams (plus
basal, exercise, sleep, and fitness-band channels this loader ignores).
Two release cohorts (2018: 6 subjects, 2020: 6 subjects), used together as
the community-standard benchmark/eval set (see the annual BGLP Challenge) -
this is the dataset to hold out for literature-comparable numbers, not to
fold into the training mix.
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd


def load(xml_dir: str | Path, subject_id: str) -> pd.DataFrame:
    raise NotImplementedError(
        "Requires OhioT1DM XML files under a Data Use Agreement - see this module's docstring. "
        "Implement by parsing <glucose_level>/<bolus>/<meal> events per subject XML into the "
        "canonical schema (see schema.py) once the files are available locally."
    )
