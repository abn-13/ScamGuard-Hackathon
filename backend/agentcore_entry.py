"""Deployable entrypoint for AWS Bedrock AgentCore Runtime.

This is a separate process from the FastAPI app (app/main.py) -- it's the container
the `agentcore` CLI builds and deploys. It runs the exact same run_pipeline() the
FastAPI app runs in-process by default, just inside AWS's managed agent runtime.

Not needed for local dev or for running the backend normally (`uvicorn app.main:app`).
Only used once someone deploys it -- see backend/README.md "AgentCore deployment".
Run from the `backend/` directory so the `app` package import below resolves.
"""

from bedrock_agentcore.runtime import BedrockAgentCoreApp

from app.agent import run_pipeline
from app.schemas import IncomingMessage

app = BedrockAgentCoreApp()


@app.entrypoint
def invoke(payload: dict, context=None) -> dict:
    message = IncomingMessage.model_validate(payload)
    verdict = run_pipeline(message)
    return verdict.model_dump()


if __name__ == "__main__":
    app.run()
