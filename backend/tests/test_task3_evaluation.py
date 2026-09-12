import json

import pytest

from app.agent import Verdict
from app.models import RiskLevel
from evaluations.task3_eval import HERE, evaluate, load_cases, main, summarize


def test_offline_cases_check_actual_evidence_without_model():
    cases = load_cases(HERE / "task3_cases.json")
    rows = evaluate(cases, 3)
    assert len(rows) == len(cases)  # repeats only apply to real-model mode
    assert all(row["evidence_pass"] for row in rows)
    assert all(row["risk_pass"] is None for row in rows)
    assert summarize(rows, False)["classification_accuracy"] is None


def test_evaluation_catches_false_negative_and_wrong_explanations():
    # A deliberately wrong model double tests the scoring code, not prompt quality.
    cases = [c for c in load_cases(HERE / "task3_cases.json") if c.id == "otp_request"]
    rows = evaluate(cases, 2, lambda _: Verdict(risk_level=RiskLevel.low, reason=""))
    summary = summarize(rows, True)
    assert summary["risk_mismatches"] == 2
    assert summary["scam_low_risk_rate"] == 1
    assert summary["empty_reasons"] == 2


def test_errors_are_not_counted_as_correct_model_verdicts():
    cases = load_cases(HERE / "task3_cases.json")[:1]
    def broken(_):
        raise RuntimeError("private request data must not appear")
    rows = evaluate(cases, 1, broken)
    assert rows[0]["error"] == "RuntimeError"
    assert "private" not in json.dumps(rows)
    assert summarize(rows, True)["model_tested"] is False


def test_report_detects_instability():
    cases = load_cases(HERE / "task3_cases.json")[:1]
    values = iter([RiskLevel.low, RiskLevel.medium])
    rows = evaluate(cases, 2, lambda _: Verdict(risk_level=next(values), reason="Review this."))
    assert summarize(rows, True)["unstable_case_ids"] == [cases[0].id]


def test_duplicate_case_ids_are_rejected(tmp_path):
    case = json.loads((HERE / "task3_cases.json").read_text(encoding="utf-8"))[0]
    path = tmp_path / "cases.json"
    path.write_text(json.dumps([case, case]), encoding="utf-8")
    with pytest.raises(ValueError, match="unique"):
        load_cases(path)


def test_offline_cli_never_calls_model_or_changes_service_settings(monkeypatch, tmp_path):
    from app import agent
    from app.config import settings
    monkeypatch.setattr(settings, "brave_search_api_key", "test-config-must-be-restored")
    def forbidden(*args, **kwargs):
        pytest.fail("Offline mode attempted a model call")
    monkeypatch.setattr(agent, "run_pipeline", forbidden)
    output = tmp_path / "report.json"
    assert main(["--output", str(output)]) == 0
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["mode"] == "offline_evidence_only"
    assert report["summary"]["model_tested"] is False
    assert settings.brave_search_api_key == "test-config-must-be-restored"
    assert main(["--output", str(output)]) == 2  # preserve earlier evidence


def test_live_mode_exits_clearly_when_model_is_unconfigured(monkeypatch, tmp_path):
    from app.config import settings
    monkeypatch.setattr(settings, "bedrock_model_id", None)
    output = tmp_path / "report.json"
    assert main(["--live", "--output", str(output)]) == 2
    assert not output.exists()
