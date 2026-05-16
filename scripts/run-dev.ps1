param(
    [string]$Module = "stock-start"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"

if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) {
            return
        }
        $idx = $line.IndexOf("=")
        if ($idx -le 0) {
            throw "Invalid .env line: $line"
        }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        [Environment]::SetEnvironmentVariable($key, $value, "Process")
    }
}

if (-not $env:SPRING_PROFILES_ACTIVE) {
    $env:SPRING_PROFILES_ACTIVE = "dev"
}

Push-Location $repoRoot
try {
    & .\mvnw.cmd -pl $Module -am package "-DskipTests"
    if ($LASTEXITCODE -ne 0) {
        throw "package failed with exit code $LASTEXITCODE"
    }

    $targetDir = Join-Path (Join-Path $repoRoot $Module) "target"
    $jar = Get-ChildItem -LiteralPath $targetDir -Filter "*.jar" |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "No runnable jar found under $targetDir"
    }

    & java -jar $jar.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "java -jar failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
