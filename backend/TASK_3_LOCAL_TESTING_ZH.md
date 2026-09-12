# Task 3 本地实现与测试指南

本次改动位于本地分支 `task3-local-completion`，基于 `main` 的 `ba10e73`。
没有推送、创建远程 PR 或修改远程内容。原有未跟踪 3B 文档保留。

## 完成状态

| 项目 | 本地结果 | 仍需团队验收 |
|---|---|---|
| 3A 提示词 | 明确 low/medium/high、正常 OTP 与索要 OTP 的区别、熟人付款上下文、证据冲突、安全建议；消息以 JSON 转义 | 真实 Bedrock 输出与脱敏真实样本 |
| 3A 评测 | 24 条合成样本、离线证据检查、真实模型模式、重复运行和基线比较 | 合成样本不能宣称真实准确率；解释需要人工检查 |
| 3B 认证解析 | 接收服务 ID 检查、多 DKIM、注释/折行/引号处理、缺失/冲突证据降级 | 接收服务 ID 本身不能证明头的来源 |
| Gmail 认证头 | 仅传递单个匹配的外层接收服务头，多个匹配时省略 | 真实 Gmail/转发邮件端到端验证 |
| 后端测试 | **132 passed，2 条依赖弃用提示** | 离线模型测试只证明程序行为 |
| Android 测试 | **4 项通过；:app:testDebugUnitTest 构建成功** | 不等于手机上真实 Gmail 测试 |
| 公开域名情报 | paypal.com 的 DNS、RDAP、证书查询真实请求成功 | 只证明本次连通性，不代表邮件安全 |
| Brave / Safe Browsing | 现有工具保留，模型评测默认禁用外部服务 | 相应 Key 配置后才能验收真实查询 |

本次验证日期：2026-09-13（北京时间）。Android 使用已有 SDK，
补装了项目需要的 Build Tools 36.0.0 与 Platform 36.1；没有更改项目 SDK 版本。
原有 `scamguard.db` 与 `test_scamguard.db` 均保留，不做迁移或删除。

认证实现参考 [RFC 8601](https://www.rfc-editor.org/rfc/rfc8601.html)，
外层消息数据来自 [Gmail messages API](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages)。
本地规则不重新计算 SPF、DKIM 或 DMARC。`header.from` 必须与可见 From
的规范化域名完全一致；这是关联接收方报告，不是重做 DMARC 的组织域对齐。
只有单个、关联成功的 `dmarc=fail` 可触发认证风险下限。认证错误、缺少关联信息、
多个 DMARC 结果，以及单独 SPF/DKIM 失败均不强制提高风险。

## 1. 打开 PowerShell，进入后端

~~~powershell
cd D:\workSpace\NTU\AWS\ScamGuard-Hackathon\backend
~~~

当前机器已有 `.venv`，直接使用下面的命令即可，不必激活虚拟环境。
换一台机器时，先创建环境并安装依赖（安装需要网络，离线测试不需要）：

~~~powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
~~~

## 2. 不需要密钥：运行完整测试

~~~powershell
.\.venv\Scripts\python.exe -m pytest -q
~~~

本次结果为 **132 passed**。覆盖原有域名工具、认证异常、API 数据库隔离、
风险下限、消息转义和评测脚本。模型相关单元测试使用模拟输出，只验证程序行为，
不代表 Claude 分类质量。

测试会阻止对外 socket 连接，并给每个 API 测试单独建立临时数据库。Windows
事件循环需要的本机回环连接仍允许。无需删除 `scamguard.db` 或 `test_scamguard.db`。

只运行本次重点测试：

~~~powershell
.\.venv\Scripts\python.exe -m pytest -q tests/test_email_auth.py tests/test_agent_domain_integration.py tests/test_task3_evaluation.py tests/test_main.py
~~~

## 3. 不需要密钥：检查 24 条回归案例

~~~powershell
.\.venv\Scripts\python.exe -m evaluations.task3_eval
~~~

应看到 `evidence_failures: 0`、`model_tested: false`、
`classification_accuracy: null`。这里检查实际域名/认证/Reply-To 输出和案例格式，
不输出伪造的“AI 判断”。SMS 案例没有域名证据，离线模式只校验其格式。
报告路径打印在最后，默认写入 `backend/reports/`，该目录被 Git 忽略。

只检查一条：

~~~powershell
.\.venv\Scripts\python.exe -m evaluations.task3_eval --case mixed_dkim
~~~

直接试域名工具：

~~~powershell
.\.venv\Scripts\python.exe -m app.tools.domain_check "PayPal <verify@paypa1-verify.com>"
.\.venv\Scripts\python.exe -m app.tools.domain_check "PayPal <notice@paypal.com>"
~~~

分别应得到 `suspicious` 与 `official`。后者仅表示域名匹配，
不代表整封邮件一定安全。

## 4. 有团队 AWS 权限时：真实模型评测

你本人不必取得密钥，可以让已经登录团队 AWS 的队友运行这一节。
使用团队确定的 SSO/profile 或临时凭据，不要把凭据写进源码或报告。

~~~powershell
$env:AWS_REGION = "us-east-1"
$env:BEDROCK_MODEL_ID = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
.\.venv\Scripts\python.exe -m evaluations.task3_eval --live --case otp_delivery
~~~

这是仓库原来配置的模型，是否仍可访问以团队账户实际调用为准。
未配置模型或没有 AWS 凭据时，脚本明确提示原因并以退出码 2 结束。
有凭据但无模型权限或登录过期时，记录错误，不将错误计为正确结果。

单条成功后，对比修改前后提示词，各运行 3 次：

~~~powershell
.\.venv\Scripts\python.exe -m evaluations.task3_eval --live --baseline --repeat 3
.\.venv\Scripts\python.exe -m evaluations.task3_eval --live --repeat 3
~~~

每条命令计划评测 72 次，会产生真实模型用量；一次流水线可能有多次模型调用。
两组使用当前解析器和消息格式，`--baseline` 只替换为保存的旧提示词，
便于比较提示词本身。报告记录模型、区域、提示词和案例哈希、Git 版本及工作区状态。
命令直接调用判断流水线，不写应用数据库，也不触发 Telegram。

默认即使本地有 Key，也禁用 Safe Browsing、Brave、DNS/RDAP/证书查询，
因此这里测的是外部服务不可用时的模型表现。需要联调已配置的真实工具时：

~~~powershell
.\.venv\Scripts\python.exe -m evaluations.task3_eval --live --external-services --repeat 1
~~~

`--external-services` 仅保留操作员现有配置，不会自动补 Key 或启用
`DOMAIN_INTELLIGENCE_ENABLED`。未配置时工具返回中性缺失结果。

重点查看报告：

- `risk_mismatches`：实际等级不在预先定义的允许范围内。
- `legitimate_flag_rate`：正常样本被标为 medium/high 的比例。
- `scam_low_risk_rate`：诈骗样本被标为 low 的比例；不包含所有 high/medium 分级错误。
- `unstable_case_ids`：重复运行出现不同等级的案例。
- `reason` 与 `review_checklist`：需人工逐条检查，非空文字不等于好解释。
- `errors`：技术失败单独列出，不计入成功样本比例的分母。

退出码：0 表示该模式自动检查通过；1 表示不匹配、调用错误、空解释或等级不稳定；
2 表示配置、参数或样本文件问题。任何退出码都不会自动批准模型验收。
解释不得泄漏验证码、复述危险链接或声称已经拦截/通知。

## 5. 加入真实脱敏案例并验收 3A

`evaluations/task3_cases.json` 中的 24 条是本次编写的合成案例，不是收集的真实短信。
建议由队友先审阅预期等级，再准备脱敏真实消息，覆盖正常通知、诈骗、第三方代发、
转发、陌生发件人、熟人普通付款与异常付款等。

复制案例结构到 Git 忽略的 `reports/real-cases.json`，将来源填写为
`de-identified-real`，保留三类 `category`、预期等级 `expected_risk`、
人工检查点 `review` 和 `expected_evidence`。先离线检查，再真实评测：

~~~powershell
.\.venv\Scripts\python.exe -m evaluations.task3_eval --cases reports/real-cases.json
.\.venv\Scripts\python.exe -m evaluations.task3_eval --live --cases reports/real-cases.json --repeat 3
~~~

真实样本不要提交到公共仓库。`--live` 会将待测内容发送到团队配置的 Bedrock。
调优后应另用一组未参与调优的样本复核，避免只适配这些例子。模糊案例的允许等级
由团队预先确定，不要看到结果后再改标签。验收人还需逐条检查解释，记录日期和报告路径。
真实地区误报/漏报、Gmail 真机联调仍待这些数据与访问条件就绪后验收。

## 6. 无密钥的公开域名情报检查（联网，可选）

~~~powershell
$env:DOMAIN_INTELLIGENCE_ENABLED = "true"
.\.venv\Scripts\python.exe -m app.tools.domain_intelligence paypal.com
Remove-Item Env:DOMAIN_INTELLIGENCE_ENABLED
~~~

本次实测：DNS `resolved`，RDAP `found`，证书透明度 `found`。
查询只发送公开域名，不发送消息正文。可用性会随网络和配额变化；
有证书、注册时间久等信号不能证明邮件安全。

## 7. Android 认证头测试

从仓库根目录运行；当前机器的 SDK 路径如下：

~~~powershell
cd D:\workSpace\NTU\AWS\ScamGuard-Hackathon
$env:ANDROID_HOME = "C:\Users\Logan\AppData\Local\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest --console=plain
~~~

本次实际构建成功，新增 `GmailAuthenticationHeadersTest` 的 4 项通过。
检查非接收方头、字段名大小写、重复接收方头降级、ARC/正文及伪装域名、版本和缺失头。
测试报告在 `app/build/reports/tests/testDebugUnitTest/index.html`。

真实 Gmail 头的来源、转发与手机界面仍需真机验证。字符串匹配不能证明任意 API
调用者提供了真实 Gmail 头；现有 API 的访问控制不属于 Task 3 本轮工作。

## 8. 手动测试 HTTP 接口

无 AWS 权限时优先用第 2、3 节。真实 `/check-message` 依赖模型，
未配置时返回 503 是预期行为，不能当成诈骗判断结果。

有权限后，用新的演示数据库启动，避免旧表结构不兼容。
第一个 PowerShell 窗口：

~~~powershell
cd D:\workSpace\NTU\AWS\ScamGuard-Hackathon\backend
$env:DATABASE_URL = "sqlite:///./local-task3-demo.db"
$env:TELEGRAM_BOT_TOKEN = ""
$env:AWS_REGION = "us-east-1"
$env:BEDROCK_MODEL_ID = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
~~~

不要给这个演示用户登记家属。第二个 PowerShell 窗口，注册虚构测试用户并提交消息：

~~~powershell
$userBody = @{
    username = "task3_demo"
    phone_number = "+15550009999"
    gmail = "task3-demo@example.org"
} | ConvertTo-Json
$demoUser = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8000/users" -ContentType "application/json" -Body $userBody
$messageBody = @{
    user_id = $demoUser.id
    source = "sms"
    sender = "+15550002001"
    body_text = "Bank support: reply with your password and one-time code immediately."
    is_known_sender = $false
    received_at = [DateTime]::UtcNow.ToString("o")
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8000/check-message" -ContentType "application/json" -Body $messageBody
~~~

这条预期为 high，并给出不要提供秘密信息、通过独立官方渠道确认的简短建议。
服务通过 Ctrl+C 停止；演示数据库不会提交到 Git。

## 最终验收边界

本地代码与离线检查已完成。Task 3A 的真实模型质量、真实邮件误报/漏报和 Gmail
端到端效果，仍保持待验收。不要只因删除 TODO 或离线通过就把这些效果标为完成。
