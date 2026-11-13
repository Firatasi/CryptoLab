param(
    [string]$OutputDir = "deploy\.hf-staging"
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Target = Join-Path $RepoRoot $OutputDir

Write-Host "[hf-bundle] Preparing clean Hugging Face Space staging folder: $Target"

if (Test-Path $Target) {
    Remove-Item -Recurse -Force $Target
}
New-Item -ItemType Directory -Path $Target -Force | Out-Null

# 1. Space root config files
Copy-Item (Join-Path $PSScriptRoot "README.md") (Join-Path $Target "README.md")
Copy-Item (Join-Path $PSScriptRoot "Dockerfile") (Join-Path $Target "Dockerfile")
Copy-Item (Join-Path $PSScriptRoot ".dockerignore") (Join-Path $Target ".dockerignore")
Copy-Item (Join-Path $PSScriptRoot "start-all.sh") (Join-Path $Target "start-all.sh")

# Force LF line endings on start-all.sh before pushing
$shPath = Join-Path $Target "start-all.sh"
$shText = (Get-Content -Raw -Path $shPath) -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($shPath, $shText, (New-Object System.Text.UTF8Encoding($false)))

# Space repo .gitignore (keeps ai/artifacts/ bundled, excludes compiled targets & secrets)
$hfGitIgnore = @"
**/target/
__pycache__/
*.pyc
.pytest_cache/
.venv/
.env
.env.*
*.pem
*.key
"@
[System.IO.File]::WriteAllText((Join-Path $Target ".gitignore"), ($hfGitIgnore -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))

# 2. Mirror backend/ cleanly (without target/)
$BackendDst = Join-Path $Target "backend"
robocopy (Join-Path $RepoRoot "backend") $BackendDst /E /XD target .idea .github /XF *.jar *.log *.pem *.key | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy backend failed with code $LASTEXITCODE" }

# 3. Mirror ai/ app, configs, requirements-training.lock.txt, and serving artifacts (without heavy weights/ or >10MB models/)
$AiDst = Join-Path $Target "ai"
New-Item -ItemType Directory -Path $AiDst -Force | Out-Null
Copy-Item (Join-Path $RepoRoot "ai\requirements-training.lock.txt") (Join-Path $AiDst "requirements-training.lock.txt")
Copy-Item (Join-Path $RepoRoot "ai\requirements-serving.lock.txt") (Join-Path $AiDst "requirements-serving.lock.txt")

robocopy (Join-Path $RepoRoot "ai\app") (Join-Path $AiDst "app") /E /XD __pycache__ .pytest_cache /XF *.pyc | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy ai/app failed with code $LASTEXITCODE" }

robocopy (Join-Path $RepoRoot "ai\configs") (Join-Path $AiDst "configs") /E | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy ai/configs failed with code $LASTEXITCODE" }

robocopy (Join-Path $RepoRoot "ai\artifacts") (Join-Path $AiDst "artifacts") /E /XD models __pycache__ | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy ai/artifacts failed with code $LASTEXITCODE" }

# Sanity check: ensure zero files > 9.5 MB in Space repo (HF Git non-LFS limit is 10 MB)
$Oversize = Get-ChildItem -Path $Target -Recurse -File | Where-Object { $_.Length -gt 9.5MB }
if ($Oversize) {
    $Oversize | Select-Object FullName, Length | Format-Table -AutoSize
    throw "Files exceeding 9.5 MB found in HF staging directory; exclude or use Git LFS."
}

Write-Host "[hf-bundle] Ready! Next steps inside ${Target}:"
Write-Host "  git init -b main"
Write-Host "  git add ."
Write-Host "  git commit -m 'Deploy CryptoLab backend + AI'"
Write-Host "  git remote add space https://huggingface.co/spaces/<HF_KULLANICI>/<SPACE_ADI>"
Write-Host "  git push space main --force"
