"""HTTP adapter around predictors.py - the only file in this codebase that
knows it's running as a Cloud Function. No Firestore access at all: this
takes readings/physiology/treatments as plain JSON and returns projected
values, nothing more.

Locked down via a shared secret (Secret Manager-backed, via SecretParam)
that pollGlucose sends as a header - not reachable without it. IAM invoker
restriction would be the more idiomatic GCP-native mechanism, but setting
it at deploy time needs a Cloud Run IAM permission this project's deploying
account doesn't have; this achieves the same "only pollGlucose can call
this" property using a permission (Secret Manager access) the project
already has granted (see backend/README.md's one-time setup).
"""

import hmac
import json

from firebase_functions import https_fn
from firebase_functions.params import SecretParam

from predictors import PREDICTORS

PREDICT_FUNCTION_SECRET = SecretParam("PREDICT_FUNCTION_SECRET")


@https_fn.on_request(region="us-central1", secrets=[PREDICT_FUNCTION_SECRET])
def predict_experimental(req: https_fn.Request) -> https_fn.Response:
    if not hmac.compare_digest(req.headers.get("X-Predict-Secret", ""), PREDICT_FUNCTION_SECRET.value):
        return https_fn.Response("Forbidden", status=403)
    if req.method != "POST":
        return https_fn.Response("Method not allowed", status=405)

    body = req.get_json(silent=True) or {}
    readings = body.get("readings", [])
    physiology = body.get("physiology", {})
    treatments = body.get("treatments", [])
    horizon_minutes = body.get("horizonMinutes", 30)

    outputs = []
    for predictor in PREDICTORS:
        projected_value, note = predictor["predict"](readings, physiology, treatments, horizon_minutes)
        outputs.append(
            {
                "key": predictor["key"],
                "name": predictor["name"],
                "description": predictor["description"],
                "sourceUrl": predictor["sourceUrl"],
                "projectedValue": projected_value,
                "note": note,
            }
        )

    return https_fn.Response(json.dumps({"outputs": outputs}), status=200, content_type="application/json")
