"""Loads exportTrainingData.ts's CSV output (see
backend/functions/src/scripts/exportTrainingData.ts) into the canonical
schema. The only loader in this package that can actually run today - it
reads a file you already have a working pipeline to produce, not a
DUA-gated public dataset.

Known gap: Vigil's export has no instantaneous carbs-on-board estimate (only
trailing-window totals and time-since-last-carb - see exportTrainingData.ts's
buildAlignedRows), unlike the point-in-time iob it does carry. carbs_g below
is populated from the trailing 4h total as the closest available proxy, not
true COB - revisit once/if exportTrainingData.ts is extended to also pull
Gluroo's devicestatus cob field (see nightscout.ts's DeviceStatusPoint).
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd

from ..schema import CANONICAL_COLUMNS, validate_canonical

# Matches EXTERNAL_IOB_FRESHNESS_MS in backend/functions/src/externalIob.ts -
# kept in sync manually since this is a one-off offline script, not something
# worth importing cross-language for.
EXTERNAL_IOB_FRESHNESS_MIN = 10


def load(csv_path: str | Path, subject_id: str) -> pd.DataFrame:
    """`subject_id` is supplied by the caller, not read from the file - the
    export script is per-patient and the CSV itself doesn't carry an id."""
    raw = pd.read_csv(csv_path)

    external_fresh = raw["external_iob_age_min"].notna() & (
        raw["external_iob_age_min"] <= EXTERNAL_IOB_FRESHNESS_MIN
    )
    iob = raw["external_iob"].where(external_fresh, raw["gluroo_iob"])

    df = pd.DataFrame(
        {
            "subject_id": subject_id,
            "timestamp_ms": raw["dateMs"].astype("int64"),
            "sgv_mgdl": raw["sgv"].astype("float64"),
            "iob_units": iob.astype("float64"),
            "carbs_g": raw["carbs_g_trailing_4h"].astype("float64"),
            "source": "vigil",
        }
    )
    df = df.dropna(subset=["sgv_mgdl"]).sort_values("timestamp_ms").reset_index(drop=True)
    df = df.astype({col: dtype for col, dtype in CANONICAL_COLUMNS.items() if col in df.columns})
    validate_canonical(df)
    return df
