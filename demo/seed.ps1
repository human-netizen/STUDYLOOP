# Phase 23.3 — put the demo corpus into a running StudyLoop, in one command.
#
# The files come from `DemoCorpusGenerator` (backend test sources), which writes seven documents
# covering every input format the second arc added. Generate them first:
#
#     cd backend
#     ./mvnw -o test -Dtest=DemoCorpusGenerator -Ddemo.corpus=true
#
# then run this from the repository root:
#
#     ./demo/seed.ps1
#     ./demo/seed.ps1 -ApiUrl "https://studyloop-api-vefq.onrender.com" -Email you@example.com
#
# **Why a script and not a SQL seed.** Chunks, embeddings, section paths and `vision_pages` are all
# produced by the ingestion pipeline, so a SQL fixture would have to hand-write vectors — which is
# both impossible to do honestly and a demo of nothing. Uploading through the real API means the
# demo corpus is built by the same code path a student uses, and the ingest is itself worth watching.
#
# PowerShell, because that is the shell this project is developed in. Note `curl.exe` rather than
# `curl`: in PowerShell `curl` is an alias for Invoke-WebRequest, which takes different arguments
# entirely. JSON bodies go through a temp file and `-d "@file"` because PowerShell strips the quotes
# out of an inline `-d` argument, and the result is a request the server rejects for reasons that
# look nothing like the cause.

[CmdletBinding()]
param(
    [string]$ApiUrl = "http://localhost:8080",
    [string]$Email = "demo@studyloop.local",
    [string]$Password = "demo-password-123",
    [string]$CourseName = "Data Structures (demo)",
    [string]$CorpusDir = "$PSScriptRoot/corpus"
)

$ErrorActionPreference = "Stop"

function Invoke-Json {
    param([string]$Method, [string]$Path, [hashtable]$Body, [string]$Token)

    $tmp = New-TemporaryFile
    try {
        ($Body | ConvertTo-Json -Compress -Depth 6) | Set-Content -Path $tmp -Encoding utf8 -NoNewline
        $curlArgs = @("-s", "-S", "--fail-with-body", "-X", $Method,
                  "-H", "Content-Type: application/json", "-d", "@$tmp")
        if ($Token) { $curlArgs += @("-H", "Authorization: Bearer $Token") }
        $curlArgs += "$ApiUrl$Path"
        $response = & curl.exe @curlArgs
        if ($LASTEXITCODE -ne 0) { throw "$Method $Path failed: $response" }
        if ([string]::IsNullOrWhiteSpace($response)) { return $null }
        return $response | ConvertFrom-Json
    }
    finally { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
}

if (-not (Test-Path $CorpusDir)) {
    throw "No corpus at $CorpusDir. Generate it first:`n" +
          "    cd backend`n" +
          "    ./mvnw -o test -Dtest=DemoCorpusGenerator -Ddemo.corpus=true"
}

Write-Host "StudyLoop demo seed -> $ApiUrl" -ForegroundColor Cyan

# A cold Render instance takes over three minutes to answer its first request, so say so rather than
# letting this look like a hang. Measured 2026-09-11: >180s cold, 0.12-0.19s warm.
Write-Host "  waking the API (a cold free-tier instance can take 3+ minutes)..." -NoNewline
& curl.exe -s -o $null --max-time 420 "$ApiUrl/actuator/health"
if ($LASTEXITCODE -ne 0) { throw "`nThe API did not answer at $ApiUrl." }
Write-Host " awake."

# Register, then log in regardless: a re-run of this script should top up an existing demo account
# rather than failing on the account it created last time.
try   { Invoke-Json POST "/api/v1/auth/register" @{ email = $Email; password = $Password; name = "Demo User" } | Out-Null
        Write-Host "  registered $Email" }
catch { Write-Host "  $Email already exists, signing in" }

$token = (Invoke-Json POST "/api/v1/auth/login" @{ email = $Email; password = $Password }).accessToken
if (-not $token) { throw "Login returned no access token." }

$course = Invoke-Json POST "/api/v1/courses" @{ name = $CourseName; description = "Every input format the pipeline supports, in one course." } $token
$courseId = $course.id
Write-Host "  course $courseId - $CourseName" -ForegroundColor Green

# Notes go to /notes (the handwriting reader); everything else is course material. The split matters:
# a photographed page is a vision call before any chunking starts, which is why Phase 16.3 gave it
# its own endpoint and its own place on the upload allowance.
$documents = Get-ChildItem $CorpusDir -File | Where-Object { $_.Extension -ne ".png" } | Sort-Object Name
$notes     = Get-ChildItem $CorpusDir -File -Filter *.png | Sort-Object Name

foreach ($file in $documents) {
    Write-Host ("  uploading {0} ({1:N0} KB)..." -f $file.Name, ($file.Length / 1KB)) -NoNewline
    $response = & curl.exe -s -S --fail-with-body -X POST `
        -H "Authorization: Bearer $token" `
        -F "file=@$($file.FullName)" `
        "$ApiUrl/api/v1/courses/$courseId/documents"
    if ($LASTEXITCODE -ne 0) { Write-Host " FAILED" -ForegroundColor Red; Write-Host "    $response"; continue }
    Write-Host " accepted." -ForegroundColor Green
}

foreach ($file in $notes) {
    Write-Host ("  uploading note {0}..." -f $file.Name) -NoNewline
    $response = & curl.exe -s -S --fail-with-body -X POST `
        -H "Authorization: Bearer $token" `
        -F "file=@$($file.FullName)" `
        "$ApiUrl/api/v1/courses/$courseId/notes"
    if ($LASTEXITCODE -ne 0) { Write-Host " FAILED" -ForegroundColor Red; Write-Host "    $response"; continue }
    Write-Host " accepted." -ForegroundColor Green
}

Write-Host ""
Write-Host "Uploads are asynchronous - watch the library page until every row reads READY." -ForegroundColor Cyan
Write-Host "Course: $ApiUrl/api/v1/courses/$courseId   (sign in as $Email)"
Write-Host ""
Write-Host "What each file is there to demonstrate:" -ForegroundColor Cyan
Write-Host "  01-scanned-chapter.pdf     PDFBox extracts nothing; only the vision router (Phase 15) reads it"
Write-Host "  02-broken-encoding.pdf     extraction *succeeds* and returns gibberish - the failure nothing downstream can see"
Write-Host "  03-figures-and-diagrams.pdf a drawn diagram with no image XObject, which image-coverage signals are blind to"
Write-Host "  04-lecture-deck.pptx       speaker notes, which a PDF export of the same deck would lose (Phase 16.1)"
Write-Host "  05-handout.docx            declared headings and a table, so the chunker gets the author's own boundaries"
Write-Host "  06-bangla-notes.docx       Bangla end to end (Phase 19) - the same prose the Bangla eval measures"
Write-Host "  07-notebook-page.png       the handwriting reader (Phase 16.3)"
Write-Host ""
Write-Host "Not covered, and it needs a real file: a Bangla PDF with a broken CMap." -ForegroundColor Yellow
