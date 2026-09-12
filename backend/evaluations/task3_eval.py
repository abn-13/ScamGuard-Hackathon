"""Run local evidence checks, or opt in to real Bedrock prompt evaluation.

Default: no model calls, database, alerts, or external services. Offline results
are explicitly NOT scam-classification accuracy. See TASK_3_LOCAL_TESTING_ZH.md.
"""

import argparse
from contextlib import ExitStack, redirect_stdout
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import subprocess
import time
from unittest.mock import patch

from pydantic import BaseModel, ConfigDict, Field
from typing import Literal

from app import agent
from app.config import settings
from app.schemas import IncomingMessage
from app.tools.domain_check import assess_sender_domain
from app.tools.email_auth import assess_email_authentication
from app.tools.reply_to_check import assess_reply_to_alignment

HERE = Path(__file__).resolve().parent


class Case(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(min_length=1)
    category: Literal["legitimate", "scam", "ambiguous"]
    provenance: Literal["synthetic", "de-identified-real"]
    expected_risk: list[Literal["low", "medium", "high"]] = Field(min_length=1)
    review: str = Field(min_length=1)
    message: IncomingMessage
    expected_evidence: dict[str, str]


def load_cases(path: Path) -> list[Case]:
    cases = [Case.model_validate(row) for row in json.loads(path.read_text(encoding="utf-8"))]
    if not cases or len({c.id for c in cases}) != len(cases):
        raise ValueError("Cases must be nonempty and IDs unique.")
    for case in cases:
        keys = {"domain", "authentication", "reply_to"} if case.message.source.value == "email" else set()
        if set(case.expected_evidence) != keys:
            raise ValueError(f"{case.id}: expected_evidence must have exactly {sorted(keys)}")
    return cases


def evidence(message: IncomingMessage) -> dict:
    if message.source.value != "email":
        return {}
    return {
        "domain": assess_sender_domain(message.sender)["verdict"],
        "authentication": assess_email_authentication(
            message.authentication_results, message.sender
        )["status"],
        "reply_to": assess_reply_to_alignment(message.sender, message.reply_to)["status"],
    }


def evaluate(cases: list[Case], repeat: int, runner=None) -> list[dict]:
    rows = []
    for case in cases:
        for index in range(repeat if runner else 1):
            actual_evidence = evidence(case.message)
            row = {
                "id": case.id, "category": case.category, "provenance": case.provenance,
                "run": index + 1, "expected_risk": case.expected_risk,
                "expected_evidence": case.expected_evidence, "actual_evidence": actual_evidence,
                "evidence_pass": actual_evidence == case.expected_evidence,
                "risk_pass": None, "review_checklist": case.review,
                "human_reason_review": "pending" if runner else "not_applicable",
            }
            if runner:
                start = time.monotonic()
                try:
                    # Avoid Strands streaming message content to the terminal.
                    with redirect_stdout(io.StringIO()):
                        verdict = runner(case.message)
                    row.update(
                        actual_risk=verdict.risk_level.value, reason=verdict.reason,
                        risk_pass=verdict.risk_level.value in case.expected_risk,
                        reason_nonempty=bool(verdict.reason.strip()),
                    )
                except Exception as exc:
                    # Error type/code are enough for triage; avoid credential/request dumps.
                    code = getattr(exc, "response", {}).get("Error", {}).get("Code")
                    row.update(error=code or type(exc).__name__, risk_pass=False)
                row["elapsed_seconds"] = round(time.monotonic() - start, 2)
            rows.append(row)
            status = row.get("error") or row.get("actual_risk") or str(actual_evidence)
            print(f"{case.id} [{index + 1}]: {status}", flush=True)
            # Configuration/permission failures apply to every case; do not repeat them.
            if row.get("error") in {
                "AccessDeniedException", "ExpiredTokenException", "UnrecognizedClientException",
                "NoCredentialsError", "TokenRetrievalError", "EndpointConnectionError",
            }:
                return rows
    return rows


def summarize(rows: list[dict], live: bool) -> dict:
    summary = {"runs": len(rows), "evidence_failures": sum(not r["evidence_pass"] for r in rows)}
    if not live:
        return {**summary, "model_tested": False, "classification_accuracy": None}
    completed = [r for r in rows if "actual_risk" in r]
    normal = [r for r in completed if r["category"] == "legitimate"]
    scam = [r for r in completed if r["category"] == "scam"]
    unstable = sorted({
        r["id"] for r in completed
        if len({x["actual_risk"] for x in completed if x["id"] == r["id"]}) > 1
    })
    return {
        **summary, "model_tested": bool(completed), "errors": len(rows) - len(completed),
        "risk_mismatches": sum(not r["risk_pass"] for r in completed),
        "empty_reasons": sum(not r["reason_nonempty"] for r in completed),
        "expected_risk_match_rate": sum(r["risk_pass"] for r in completed) / len(completed) if completed else None,
        "legitimate_flag_rate": sum(r["actual_risk"] != "low" for r in normal) / len(normal) if normal else None,
        "scam_low_risk_rate": sum(r["actual_risk"] == "low" for r in scam) / len(scam) if scam else None,
        "unstable_case_ids": unstable,
        "reason_quality": "requires human review; nonempty text is not a quality score",
    }


def git_value(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=HERE, capture_output=True, text=True, check=False
    ).stdout.strip()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="Call real AWS Bedrock; incurs model usage.")
    parser.add_argument("--baseline", action="store_true", help="Use the saved pre-change prompt for comparison.")
    parser.add_argument("--external-services", action="store_true", help="In live mode, allow configured URL/search/domain services.")
    parser.add_argument("--cases", type=Path, default=HERE / "task3_cases.json")
    parser.add_argument("--case", help="Run one exact case ID.")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--model-id", help="Override BEDROCK_MODEL_ID for this process.")
    parser.add_argument("--output", type=Path, help="New JSON report path; existing files are never overwritten.")
    args = parser.parse_args(argv)
    if args.repeat < 1 or args.repeat > 10:
        parser.error("--repeat must be 1..10")
    if args.external_services and not args.live:
        parser.error("--external-services requires --live")
    try:
        cases = load_cases(args.cases)
    except (ValueError, OSError) as exc:
        print(f"Invalid case file: {type(exc).__name__}")
        return 2
    if args.case:
        cases = [c for c in cases if c.id == args.case]
        if not cases:
            parser.error("Unknown case ID.")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    report_path = args.output or HERE.parent / "reports" / f"task3-{stamp}.json"
    if report_path.exists():
        print("Report already exists; choose a new output path.")
        return 2
    with ExitStack() as stack:
        if args.model_id:
            stack.enter_context(patch.object(settings, "bedrock_model_id", args.model_id))
        if args.baseline:
            stack.enter_context(patch.object(agent, "SYSTEM_PROMPT", (HERE / "baseline_prompt.txt").read_text(encoding="utf-8").strip()))
        if not args.external_services:
            for name, value in {
                "safe_browsing_api_key": None, "brave_search_api_key": None,
                "domain_intelligence_enabled": False,
            }.items():
                stack.enter_context(patch.object(settings, name, value))
        if args.live:
            if not settings.bedrock_model_id:
                print("Live evaluation unavailable: set BEDROCK_MODEL_ID or --model-id. Offline mode needs no key.")
                return 2
            # Do not probe instance metadata on a developer laptop.
            import boto3
            import os
            stack.enter_context(patch.dict(os.environ, {"AWS_EC2_METADATA_DISABLED": "true"}))
            try:
                if boto3.Session().get_credentials() is None:
                    print("Live evaluation unavailable: no AWS credentials. Ask a teammate with Bedrock access to run it.")
                    return 2
            except Exception as exc:
                print(f"AWS login unavailable ({type(exc).__name__}); refresh your team login.")
                return 2
        rows = evaluate(cases, args.repeat, agent.run_pipeline if args.live else None)
        summary = summarize(rows, args.live)
        requested_runs = len(cases) * (args.repeat if args.live else 1)
        report = {
            "timestamp_utc": stamp, "mode": "live" if args.live else "offline_evidence_only",
            "prompt": "baseline-ba10e73" if args.baseline else "current",
            "prompt_sha256": hashlib.sha256(agent.SYSTEM_PROMPT.encode()).hexdigest(),
            "cases_sha256": hashlib.sha256(args.cases.read_bytes()).hexdigest(),
            "git_commit": git_value("rev-parse", "HEAD"),
            "working_tree_dirty": bool(git_value("status", "--porcelain")),
            "model_id": settings.bedrock_model_id if args.live else None,
            "aws_region": settings.aws_region if args.live else None,
            "external_services_enabled": args.external_services,
            "requested_runs": requested_runs, "summary": summary, "results": rows,
            "acceptance": "PENDING: real-message dataset and human reason review required",
        }
        report_path.parent.mkdir(parents=True, exist_ok=True)
        with report_path.open("x", encoding="utf-8") as out:
            json.dump(report, out, indent=2, ensure_ascii=False)
        print(json.dumps(summary, indent=2, ensure_ascii=False))
        print(f"Report: {report_path.resolve()}")
        failed = summary["evidence_failures"] or len(rows) != requested_runs
        if args.live:
            failed = failed or summary["errors"] or summary["risk_mismatches"] or summary["empty_reasons"] or summary["unstable_case_ids"]
        return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
