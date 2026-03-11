# framework-res.apk 내 config_webview_packages.xml 교체 스크립트
#
# 패치 모드:
#   powershell -File patch_framework_res.ps1 <framework-res.apk> <patched.bin> <output.apk>
#
# 체크 모드 (이미 패치됐는지 확인, xml 크기 출력):
#   powershell -File patch_framework_res.ps1 -Check <framework-res.apk>
param(
    [Parameter(Position=0)][string]$ApkPath,
    [Parameter(Position=1)][string]$PatchBin,
    [Parameter(Position=2)][string]$OutputPath,
    [switch]$Check
)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ── 체크 모드 ──
if ($Check) {
    if (-not (Test-Path $ApkPath)) {
        Write-Output "0"
        exit 0
    }
    try {
        $z = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
        $entry = $z.Entries | Where-Object { $_.FullName -eq 'res/xml/config_webview_packages.xml' }
        if ($entry) { Write-Output $entry.Length } else { Write-Output "0" }
        $z.Dispose()
    } catch {
        Write-Output "0"
    }
    exit 0
}

# ── 패치 모드 ──
if (-not $PatchBin -or -not $OutputPath) {
    Write-Error "Usage: patch_framework_res.ps1 <apk> <patch.bin> <output.apk>"
    Write-Error "       patch_framework_res.ps1 -Check <apk>"
    exit 1
}

if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found: $ApkPath"
    exit 1
}
if (-not (Test-Path $PatchBin)) {
    Write-Error "Patch file not found: $PatchBin"
    exit 1
}

$patchData = [System.IO.File]::ReadAllBytes($PatchBin)
Copy-Item $ApkPath $OutputPath -Force

$zip = [System.IO.Compression.ZipFile]::Open($OutputPath, 'Update')
$entry = $zip.Entries | Where-Object { $_.FullName -eq 'res/xml/config_webview_packages.xml' }

if ($entry) {
    $origSize = $entry.Length
    $stream = $entry.Open()
    $stream.SetLength(0)
    $stream.Write($patchData, 0, $patchData.Length)
    $stream.Close()
    Write-Output "  REPLACED: res/xml/config_webview_packages.xml"
    Write-Output "  original size: $origSize bytes"
    Write-Output "  patched size: $($patchData.Length) bytes"
} else {
    $zip.Dispose()
    Write-Error "config_webview_packages.xml not found in APK"
    exit 1
}

$zip.Dispose()
Write-Output "  Output: $OutputPath"
