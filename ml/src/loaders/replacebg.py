"""ReplaceBG loader - STUB, not yet implemented.

Source: Jaeb Center for Health Research public data portal (registration
required, not a PhysioNet DUA) - https://public.jaeb.org/dataset/546
226 well-controlled T1D adults, 182 days each - CGM paired with self-
monitored blood glucose from a real-world monitoring study. Large-n but
comparatively thin on structured insulin/carb logging versus DiaTrend/
OhioT1DM - best used for population-scale glucose-dynamics pretraining
rather than covariate-heavy fine-tuning (see ../../README.md).

Format: not yet confirmed - Jaeb's public releases are typically multiple
per-domain text/CSV files (CGM, SMBG, pump settings) joined by a subject id;
verify the actual schema against the downloaded files.
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd


def load(data_dir: str | Path, subject_id: str) -> pd.DataFrame:
    raise NotImplementedError(
        "Requires Jaeb-provided ReplaceBG files - see this module's docstring. "
        "Implement by parsing the CGM record files into the canonical schema "
        "(see schema.py) once the files are available locally."
    )
