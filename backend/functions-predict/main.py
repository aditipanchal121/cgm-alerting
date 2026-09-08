"""HTTP adapter around predictors.py - the only file in this codebase that
knows it's running as a Cloud Function. No Firestore access: takes
readings/physiology/iobCobHistory as plain JSON, returns projected values.

Locked down via a shared secret (Secret Manager-backed) that pollGlucose
sends as a header - used instead of IAM invoker restriction, since setting
that needs a Cloud Run IAM permission this project's deploying account
doesn't have.
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
    horizon_minutes = body.get("horizonMinutes", 30)
    iob_cob_history = body.get("iobCobHistory", [])

    outputs = []
    for predictor in PREDICTORS:
        projected_value, note = predictor["predict"](readings, physiology, horizon_minutes, iob_cob_history)
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
