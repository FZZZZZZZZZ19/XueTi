$ErrorActionPreference = "Continue"
$px = "http://127.0.0.1:7897"
$root = "C:\Users\lzega\Desktop\XueTi\app\src\main\assets\render"
New-Item -ItemType Directory -Force -Path "$root\fonts" | Out-Null

function Get-File($url, $out) {
    for ($i = 1; $i -le 4; $i++) {
        & curl.exe -x $px --ssl-no-revoke -sS -m 90 -L -o $out $url 2>$null
        if ((Test-Path $out) -and ((Get-Item $out).Length -gt 200)) {
            Write-Output ("OK   {0,9}  {1}" -f (Get-Item $out).Length, (Split-Path $out -Leaf))
            return $true
        }
        Start-Sleep -Seconds 3
    }
    Write-Output ("FAIL               {0}" -f (Split-Path $out -Leaf))
    return $false
}

$base = "https://cdn.jsdelivr.net/npm/katex@0.16.11/dist"
Get-File "$base/katex.min.css" "$root/katex.min.css" | Out-Null
Get-File "$base/katex.min.js" "$root/katex.min.js" | Out-Null
Get-File "$base/contrib/auto-render.min.js" "$root/auto-render.min.js" | Out-Null

$fonts = @(
    "KaTeX_AMS-Regular", "KaTeX_Caligraphic-Bold", "KaTeX_Caligraphic-Regular",
    "KaTeX_Fraktur-Bold", "KaTeX_Fraktur-Regular", "KaTeX_Main-Bold", "KaTeX_Main-BoldItalic",
    "KaTeX_Main-Italic", "KaTeX_Main-Regular", "KaTeX_Math-BoldItalic", "KaTeX_Math-Italic",
    "KaTeX_SansSerif-Bold", "KaTeX_SansSerif-Italic", "KaTeX_SansSerif-Regular",
    "KaTeX_Script-Regular", "KaTeX_Size1-Regular", "KaTeX_Size2-Regular", "KaTeX_Size3-Regular",
    "KaTeX_Size4-Regular", "KaTeX_Typewriter-Regular"
)
foreach ($f in $fonts) {
    Get-File "$base/fonts/$f.woff2" "$root/fonts/$f.woff2" | Out-Null
}
Get-File "https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js" "$root/marked.min.js" | Out-Null

Write-Output "---- summary ----"
Get-ChildItem $root -File | ForEach-Object { "{0,10}  {1}" -f $_.Length, $_.Name }
Write-Output ("fonts: " + (Get-ChildItem "$root\fonts" -File).Count + " files, total " + ((Get-ChildItem "$root\fonts" -File | Measure-Object Length -Sum).Sum) + " bytes")
