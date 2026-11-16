param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$KeepDocument
)

$ErrorActionPreference = "Stop"

function Invoke-CurlJson {
    param([string[]]$Arguments)

    $output = & curl.exe -sS @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "curl 요청이 실패했습니다: $($Arguments -join ' ')"
    }
    return $output | ConvertFrom-Json
}

$samplePath = (Resolve-Path "src/main/resources/sample-documents/company.txt").Path
$updatedPath = Join-Path $env:TEMP "aipractice-company-updated.txt"
$original = Get-Content -LiteralPath $samplePath -Raw -Encoding UTF8
Set-Content -LiteralPath $updatedPath -Encoding UTF8 -Value ($original + "`n실습 추가 규정: 문서 수정은 새 버전으로 기록합니다.`n")

Write-Host "[1/7] 새 문서 업로드"
$created = Invoke-CurlJson -Arguments @(
    "-X", "POST",
    "-F", "file=@$samplePath",
    "-F", "title=회사 규정 실습",
    "-F", "category=practice",
    "-F", "chunkSize=500",
    "-F", "overlap=50",
    "$BaseUrl/api/rag/documents"
)
$created | ConvertTo-Json -Depth 6
$documentId = $created.documentId
if (-not $documentId) {
    throw "업로드 응답에 documentId가 없습니다."
}

Write-Host "[2/7] 같은 문서 재업로드 - unchanged=true 확인"
$duplicate = Invoke-CurlJson -Arguments @(
    "-X", "POST",
    "-F", "file=@$samplePath",
    "-F", "title=회사 규정 실습",
    "-F", "category=practice",
    "-F", "chunkSize=500",
    "-F", "overlap=50",
    "$BaseUrl/api/rag/documents"
)
$duplicate | ConvertTo-Json -Depth 6
if (-not $duplicate.unchanged) {
    throw "중복 업로드가 unchanged로 처리되지 않았습니다."
}

Write-Host "[3/7] 내용 수정 - version 증가 확인"
$updated = Invoke-CurlJson -Arguments @(
    "-X", "PUT",
    "-F", "file=@$updatedPath;filename=company.txt",
    "-F", "title=회사 규정 실습",
    "-F", "category=practice",
    "$BaseUrl/api/rag/documents/$documentId"
)
$updated | ConvertTo-Json -Depth 6
if ($updated.version -le $created.version) {
    throw "수정 후 version이 증가하지 않았습니다."
}

Write-Host "[4/7] chunk profile을 바꿔 재색인"
$reindexed = Invoke-CurlJson -Arguments @(
    "-X", "POST",
    "-H", "Content-Type: application/json",
    "-d", '{"chunkSize":300,"overlap":30}',
    "$BaseUrl/api/rag/documents/$documentId/reindex"
)
$reindexed | ConvertTo-Json -Depth 6

Write-Host "[5/7] 문서와 전체 버전 이력 조회"
$details = Invoke-CurlJson -Arguments @(
    "$BaseUrl/api/rag/documents/$documentId"
)
$details | ConvertTo-Json -Depth 8

Write-Host "[6/7] RAG custom metric 확인"
$metrics = & curl.exe -sS "$BaseUrl/actuator/prometheus"
$metrics | Select-String "rag_(ingestion|documents|search|llm)"

if ($KeepDocument) {
    Write-Host "[7/7] -KeepDocument가 지정되어 삭제를 생략했습니다: $documentId"
}
else {
    Write-Host "[7/7] 문서와 모든 vector chunk 삭제"
    & curl.exe -sS -o NUL -w "HTTP %{http_code}`n" -X DELETE `
        "$BaseUrl/api/rag/documents/$documentId"
    if ($LASTEXITCODE -ne 0) {
        throw "삭제 요청이 실패했습니다."
    }
}

Remove-Item -LiteralPath $updatedPath -ErrorAction SilentlyContinue
Write-Host "문서 lifecycle 실습을 완료했습니다."
