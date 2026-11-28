<#
.SYNOPSIS
    Biên dịch Hồ sơ PTTK DevFlow (docs/pttk/main.tex) sang PDF bằng XeLaTeX và kiểm tra ràng buộc 30-65 trang.
#>
param(
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$pttkDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDir = Join-Path $pttkDir "build"

if ($Clean -and (Test-Path $buildDir)) {
    Remove-Item -Recurse -Force $buildDir
    Write-Host "Đã xóa thư mục build/." -ForegroundColor Yellow
}

if (-not (Test-Path $buildDir)) {
    New-Item -ItemType Directory -Path $buildDir | Out-Null
}

$xelatex = Get-Command xelatex -ErrorAction SilentlyContinue
if (-not $xelatex) {
    Write-Error "Không tìm thấy xelatex trong PATH. Vui lòng cài đặt MiKTeX hoặc TeX Live."
    exit 1
}

Push-Location $pttkDir
try {
    Write-Host "=== [1/2] Biên dịch XeLaTeX lượt 1 ===" -ForegroundColor Cyan
    & xelatex -interaction=nonstopmode -halt-on-error -output-directory=build main.tex | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Lỗi biên dịch ở lượt 1. 40 dòng cuối của build/main.log:" -ForegroundColor Red
        Get-Content (Join-Path $buildDir "main.log") -Tail 40
        exit $LASTEXITCODE
    }

    Write-Host "=== [2/2] Biên dịch XeLaTeX lượt 2 (cập nhật Mục lục & Tham chiếu) ===" -ForegroundColor Cyan
    & xelatex -interaction=nonstopmode -halt-on-error -output-directory=build main.tex | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Lỗi biên dịch ở lượt 2. 40 dòng cuối của build/main.log:" -ForegroundColor Red
        Get-Content (Join-Path $buildDir "main.log") -Tail 40
        exit $LASTEXITCODE
    }

    $srcPdf = Join-Path $buildDir "main.pdf"
    $outPdf = Join-Path $buildDir "DevFlow_HoSo_PTTK.pdf"
    Copy-Item -Force $srcPdf $outPdf

    # Đọc số trang từ file log của XeLaTeX (hỗ trợ dòng bị ngắt 79 ký tự)
    $logContent = Get-Content (Join-Path $buildDir "main.log") -Raw
    $pageMatch = [regex]::Match($logContent, "(?s)Output written on .*?\((\d+)\s+pages?\)")
    $pages = if ($pageMatch.Success) { [int]$pageMatch.Groups[1].Value } else { -1 }

    Write-Host ""
    Write-Host "Biên dịch thành công: $outPdf" -ForegroundColor Green
    if ($pages -gt 0) {
        Write-Host "Tổng số trang hiện tại: $pages trang (Ràng buộc: 30 - 65 trang)" -ForegroundColor Cyan
        if ($pages -lt 30) {
            Write-Host "[CẢNH BÁO] Chưa đạt tối thiểu 30 trang (thiếu $(30 - $pages) trang)." -ForegroundColor Yellow
        } elseif ($pages -gt 65) {
            Write-Host "[CẢNH BÁO] Vượt quá giới hạn tối đa 65 trang (dư $($pages - 65) trang)." -ForegroundColor Red
        } else {
            Write-Host "[ĐẠT] Độ dài PDF nằm gọn trong khung 30 - 65 trang." -ForegroundColor Green
        }
    }
}
finally {
    Pop-Location
}
