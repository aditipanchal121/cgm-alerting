import pandas as pd

from src.loaders.vigil_export import load


def _write_csv(path):
    # Mirrors exportTrainingData.ts's actual column names exactly.
    pd.DataFrame(
        [
            {
                "dateMs": 1_700_000_000_000,
                "timestampIso": "2023-11-14T22:13:20.000Z",
                "sgv": 120,
                "direction": "Flat",
                "gluroo_iob": 2.0,
                "iob_unreliable": 0,
                "external_iob": "",
                "external_iob_age_min": "",
                "minutes_since_last_bolus": 45,
                "last_bolus_units": 3.0,
                "insulin_units_trailing_4h": 3.0,
                "minutes_since_last_carb": 60,
                "last_carb_grams": 30,
                "carbs_g_trailing_4h": 30.0,
            },
            {
                "dateMs": 1_700_000_300_000,
                "timestampIso": "2023-11-14T22:18:20.000Z",
                "sgv": 118,
                "direction": "Flat",
                "gluroo_iob": 1.9,
                "iob_unreliable": 0,
                "external_iob": 3.5,  # fresh external report - should win over gluroo_iob
                "external_iob_age_min": 2,
                "minutes_since_last_bolus": 50,
                "last_bolus_units": 3.0,
                "insulin_units_trailing_4h": 3.0,
                "minutes_since_last_carb": 65,
                "last_carb_grams": 30,
                "carbs_g_trailing_4h": 30.0,
            },
            {
                "dateMs": 1_700_000_600_000,
                "timestampIso": "2023-11-14T22:23:20.000Z",
                "sgv": 116,
                "direction": "Flat",
                "gluroo_iob": 1.8,
                "iob_unreliable": 0,
                "external_iob": 3.4,  # stale external report (>10 min old) - gluroo_iob should win
                "external_iob_age_min": 25,
                "minutes_since_last_bolus": 55,
                "last_bolus_units": 3.0,
                "insulin_units_trailing_4h": 3.0,
                "minutes_since_last_carb": 70,
                "last_carb_grams": 30,
                "carbs_g_trailing_4h": 30.0,
            },
        ]
    ).to_csv(path, index=False)


def test_load_prefers_fresh_external_iob_over_gluroo(tmp_path):
    csv_path = tmp_path / "export.csv"
    _write_csv(csv_path)

    df = load(csv_path, subject_id="brother")

    assert len(df) == 3
    assert (df["subject_id"] == "brother").all()
    assert (df["source"] == "vigil").all()
    assert df["iob_units"].iloc[0] == 2.0  # no external report at all -> gluroo
    assert df["iob_units"].iloc[1] == 3.5  # fresh external report -> external wins
    assert df["iob_units"].iloc[2] == 1.8  # stale external report -> falls back to gluroo
    assert list(df["sgv_mgdl"]) == [120.0, 118.0, 116.0]
