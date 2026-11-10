$ErrorActionPreference = 'Stop'
$configPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'vibe-music-backend/vibe-music-server/src/main/resources/application-dev.yml'
$email = (Read-Host 'QQ sender email (example: 123456@qq.com)').Trim()
if ($email -notmatch '^[^\s@]+@qq\.com$') { throw 'Enter a complete @qq.com email address.' }
$secureCode = Read-Host 'QQ SMTP authorization code (not your QQ password)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureCode)
try {
    $code = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer).Trim()
    if ([string]::IsNullOrWhiteSpace($code)) { throw 'Authorization code cannot be empty.' }
    $text = [IO.File]::ReadAllText($configPath)
    $pattern = '(?ms)^  mail:\r?\n.*?(?=^  ai:)'
    if (-not [regex]::IsMatch($text, $pattern)) { throw 'Mail configuration block not found; no changes written.' }
    $block = "  mail:`n    host: smtp.qq.com`n    username: " + (ConvertTo-Json -InputObject $email -Compress) + "`n    password: " + (ConvertTo-Json -InputObject $code -Compress) + "`n"
    $text = [regex]::Replace($text, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $block })
    [IO.File]::WriteAllText($configPath, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'Saved in ignored application-dev.yml. Restart the backend to apply.'
    Write-Host 'This script does not send any email.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $code = $null
    $block = $null
    $text = $null
    $secureCode.Dispose()
}