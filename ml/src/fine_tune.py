"""Generates a Chronos fine-tuning config and prints (never runs, unless
--run is passed explicitly) the training command - see
amazon-science/chronos-forecasting's scripts/training/train.py.

Not runnable end to end yet: needs (1) a clone of amazon-science/
chronos-forecasting with `pip install --editable ".[training]"` from that
clone (a separate GPU-capable environment, not this package's own
requirements.txt - see ../README.md), and (2) Arrow training files from
preprocessing.write_chronos_arrow, which itself needs real data this
project doesn't have yet (see ../data/README.md). This module is the
scaffold to fill in once both exist - deliberately not wired to actually
train anything today.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import yaml

# amazon/chronos-bolt-small: fast enough for CPU fine-tuning/inference (see
# ../README.md's model-selection rationale), a reasonable population-stage
# starting point before considering base/large.
DEFAULT_MODEL_ID = "amazon/chronos-bolt-small"


def build_config(arrow_paths: list[str | Path], output_dir: str | Path, probabilities: list[float] | None = None) -> dict:
    """One entry per Arrow file in training_data_paths, mirroring chronos-
    forecasting's own config schema. `probabilities` lets a smaller,
    higher-quality source (e.g. Vigil's own personalization data, once
    there's enough of it) be oversampled relative to a larger population
    corpus - equal weighting is the default until there's a reason not to."""
    paths = [str(p) for p in arrow_paths]
    if probabilities is None:
        probabilities = [1.0 / len(paths)] * len(paths)
    if len(probabilities) != len(paths):
        raise ValueError("probabilities must have one entry per arrow_paths entry")

    return {
        "training_data_paths": paths,
        "probability": probabilities,
        "output_dir": str(output_dir),
        # Values below are starting points cited from the paper closest to
        # this exact task (arXiv:2609.11872, fine-tuning Chronos-Bolt on CGM
        # data) - not yet tuned against Vigil's own data.
        "context_length": 512,
        "prediction_length": 64,
        "min_past": 60,
        "max_steps": 1000,
        "learning_rate": 1e-3,
        "per_device_train_batch_size": 32,
    }


def write_config(config: dict, path: str | Path) -> None:
    Path(path).write_text(yaml.dump(config, sort_keys=False))


def build_train_command(config_path: str | Path, model_id: str = DEFAULT_MODEL_ID, chronos_repo: str | Path = "../chronos-forecasting") -> list[str]:
    return [
        sys.executable,
        str(Path(chronos_repo) / "scripts" / "training" / "train.py"),
        "--config",
        str(config_path),
        "--model-id",
        model_id,
        "--no-random-init",
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arrow", nargs="+", required=True, help="Arrow file(s) from preprocessing.write_chronos_arrow")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--model-id", default=DEFAULT_MODEL_ID)
    parser.add_argument("--chronos-repo", default="../chronos-forecasting")
    parser.add_argument("--config-out", default="fine_tune_config.generated.yaml")
    parser.add_argument("--run", action="store_true", help="Actually invoke training, not just print the command")
    args = parser.parse_args()

    config = build_config(args.arrow, args.output_dir)
    write_config(config, args.config_out)
    command = build_train_command(args.config_out, args.model_id, args.chronos_repo)

    print(f"Wrote config to {args.config_out}")
    print("Training command:\n  " + " ".join(command))
    if args.run:
        subprocess.run(command, check=True)
    else:
        print("(--run not passed - not executing. This project isn't starting training runs yet - see ../README.md.)")


if __name__ == "__main__":
    main()
