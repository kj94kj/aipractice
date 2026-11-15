param(
    [string]$BaseUrl = "http://localhost:8080"
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
$report = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/rag/evaluations" `
    -ContentType "application/json; charset=UTF-8" `
    -Body (Get-Content -LiteralPath $datasetPath -Raw)

$reportDirectory = Join-Path $projectRoot "build/reports"
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$jsonPath = Join-Path $reportDirectory "rag-evaluation.json"
$markdownPath = Join-Path $reportDirectory "rag-evaluation.md"
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jsonPath -Encoding utf8

$lines = @(
    "# RAG 검색 평가 결과",
    "",
    "질문 수: $($report.questionCount)",
    "",
    "| 설정 | 방식 | Profile | Top-K | Threshold | Recall@K | HitRate@K | MRR |",
    "|---|---|---|---:|---:|---:|---:|---:|"
)
foreach ($item in $report.comparison) {
    $lines += "| $($item.name) | $($item.mode) | $($item.profile) | $($item.topK) | $($item.similarityThreshold) | $($item.recallAtK) | $($item.hitRateAtK) | $($item.mrr) |"
}
$lines | Set-Content -LiteralPath $markdownPath -Encoding utf8

Write-Host "`nSaved:"
Write-Host $jsonPath
Write-Host $markdownPath
$report.comparison | Format-Table name, mode, profile, topK, similarityThreshold, recallAtK, hitRateAtK, mrr
