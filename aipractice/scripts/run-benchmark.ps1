param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$SkipAnswerEvaluation
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$documents = @(
    @{ File = "company.txt"; Title = "회사 연차 규정"; Category = "hr" },
    @{ File = "remote-work.txt"; Title = "재택근무 운영 규정"; Category = "work" },
    @{ File = "benefits.txt"; Title = "임직원 복지 제도"; Category = "benefits" }
)
$profiles = @(
    @{ ChunkSize = 300; Overlap = 30 },
    @{ ChunkSize = 700; Overlap = 100 }
)

foreach ($profile in $profiles) {
    foreach ($document in $documents) {
        $path = Join-Path $projectRoot "src/main/resources/sample-documents/$($document.File)"
        $form = @{
            file = Get-Item -LiteralPath $path
            title = $document.Title
            category = $document.Category
            chunkSize = $profile.ChunkSize
            overlap = $profile.Overlap
        }
        $result = Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/rag/documents" `
            -Form $form
        Write-Host "ingested $($result.filename) profile=$($result.profile) chunks=$($result.chunkCount)"
    }
}

$datasetPath = Join-Path $projectRoot "src/main/resources/evaluation/questions.json"
$dataset = Get-Content -LiteralPath $datasetPath -Raw | ConvertFrom-Json
if ($SkipAnswerEvaluation) {
    $dataset.evaluateAnswers = $false
}

$report = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/rag/evaluations" `
    -ContentType "application/json; charset=UTF-8" `
    -Body ($dataset | ConvertTo-Json -Depth 20)

$reportDirectory = Join-Path $projectRoot "build/reports"
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$jsonPath = Join-Path $reportDirectory "rag-evaluation.json"
$csvPath = Join-Path $reportDirectory "rag-evaluation.csv"
$markdownPath = Join-Path $reportDirectory "rag-evaluation.md"
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $jsonPath -Encoding utf8

$csvRows = foreach ($configuration in $report.comparison) {
    foreach ($question in $configuration.questions) {
        $gold = $dataset.questions | Where-Object { $_.id -eq $question.id } | Select-Object -First 1
        [PSCustomObject]@{
            configuration = $configuration.name
            mode = $configuration.mode
            promptProfile = $configuration.promptProfile
            questionId = $question.id
            question = $question.question
            expectedAnswerable = $gold.answerable
            expectedAnswer = $gold.expectedAnswer
            relevantSources = ($gold.relevantSources -join ";")
            recallAt3 = $question.recallAt3
            recallAt5 = $question.recallAt5
            firstRelevantRank = $question.firstRelevantRank
            retrievalMillis = $question.retrievalMillis
            generationMillis = $question.generationMillis
            abstained = $question.abstained
            abstentionCorrect = $question.abstentionCorrect
            faithfulnessProxy = $question.faithfulnessProxy
            answerRelevancyProxy = $question.answerRelevancyProxy
            retrievedSources = ($question.retrievedSources -join ";")
            answer = $question.answer
            error = $question.error
        }
    }
}
$csvRows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8

function Format-Metric($value) {
    if ($null -eq $value) { return "-" }
    return [string]$value
}

$lines = @(
    "# RAG 자동 평가 결과",
    "",
    "- 질문 수: $($report.questionCount) (답변 가능 30, 답변 불가 6)",
    "- 답변 평가 실행: $($report.answerEvaluationEnabled)",
    "- 생성 시각: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')",
    "",
    "## 요약",
    "",
    "| 설정 | 검색 | 프롬프트 | Recall@3 | Recall@5 | MRR | 검색 ms | LLM ms | Faithfulness* | Relevancy* | 보류 정확도 | 오류율 |",
    "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
)
foreach ($item in $report.comparison) {
    $lines += "| $($item.name) | $($item.mode) | $($item.promptProfile) | $(Format-Metric $item.recallAt3) | $(Format-Metric $item.recallAt5) | $(Format-Metric $item.mrr) | $(Format-Metric $item.averageRetrievalMillis) | $(Format-Metric $item.averageGenerationMillis) | $(Format-Metric $item.faithfulnessProxy) | $(Format-Metric $item.answerRelevancyProxy) | $(Format-Metric $item.abstentionAccuracy) | $(Format-Metric $item.errorRate) |"
}

$baseline = $report.comparison[0]
$lines += @(
    "",
    "## 직전 설정 대비 변화",
    "",
    "첫 기준: **$($baseline.name)**. 각 행은 바로 앞 설정과 비교하므로 검색 변경과 프롬프트 변경 효과가 분리된다.",
    "",
    "| 이전 → 현재 | Δ Recall@3 | Δ Recall@5 | Δ Faithfulness* | Δ Relevancy* | Δ 검색 ms | Δ LLM ms |",
    "|---|---:|---:|---:|---:|---:|---:|"
)
for ($index = 1; $index -lt $report.comparison.Count; $index++) {
    $previous = $report.comparison[$index - 1]
    $item = $report.comparison[$index]
    $deltaRecall3 = if ($null -ne $item.recallAt3) { [math]::Round($item.recallAt3 - $previous.recallAt3, 4) } else { "-" }
    $deltaRecall5 = if ($null -ne $item.recallAt5) { [math]::Round($item.recallAt5 - $previous.recallAt5, 4) } else { "-" }
    $deltaFaithfulness = if ($null -ne $item.faithfulnessProxy) { [math]::Round($item.faithfulnessProxy - $previous.faithfulnessProxy, 4) } else { "-" }
    $deltaRelevancy = if ($null -ne $item.answerRelevancyProxy) { [math]::Round($item.answerRelevancyProxy - $previous.answerRelevancyProxy, 4) } else { "-" }
    $deltaRetrieval = [math]::Round($item.averageRetrievalMillis - $previous.averageRetrievalMillis, 4)
    $deltaGeneration = if ($null -ne $item.averageGenerationMillis) { [math]::Round($item.averageGenerationMillis - $previous.averageGenerationMillis, 4) } else { "-" }
    $lines += "| $($previous.name) → $($item.name) | $deltaRecall3 | $deltaRecall5 | $deltaFaithfulness | $deltaRelevancy | $deltaRetrieval | $deltaGeneration |"
}

$errors = @($csvRows | Where-Object { $_.error })
$lines += @(
    "",
    "## 해석 가이드",
    "",
    "- Recall@3/5와 MRR은 **검색 품질**이다. 답변을 생성하기 전 단계만 측정한다.",
    "- Faithfulness*는 각 문장에 유효한 인용이 있는지 보는 결정적 proxy다. 의미적 사실성의 완전한 증명은 아니다.",
    "- Relevancy*는 사람이 작성한 핵심 문구가 답에 포함된 비율이다. 표현이 크게 달라지는 답은 낮게 측정될 수 있다.",
    "- 보류 정확도에는 답변 가능한 질문에서 잘못 보류한 경우와 답변 불가 질문에 억지로 답한 경우가 모두 반영된다.",
    "- 개선 판단은 품질 상승뿐 아니라 검색/LLM 지연시간과 오류율의 회귀 여부를 함께 본다.",
    "",
    "오류 행 수: $($errors.Count). 질문별 원문 결과는 `rag-evaluation.csv`와 `rag-evaluation.json`에서 확인한다."
)
$lines | Set-Content -LiteralPath $markdownPath -Encoding utf8

Write-Host "`nSaved:"
Write-Host $jsonPath
Write-Host $csvPath
Write-Host $markdownPath
$report.comparison | Format-Table name, mode, promptProfile, recallAt3, recallAt5, mrr, faithfulnessProxy, answerRelevancyProxy, errorRate
