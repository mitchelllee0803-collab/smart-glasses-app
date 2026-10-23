# Fetches the llama.cpp sources compiled into the :llm module (on-device AI translation), pinned to
# release b11102 and checksum-verified.
#   powershell -ExecutionPolicy Bypass -File scripts/fetch-llama-cpp.ps1
# Development-time only; the app itself downloads nothing.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tag = 'b11102'
$sha = '256EC7AADA0F57CA1D38809B6C095FF426F58778D510610BF88F819B452D38FF'
$dest = Join-Path $root 'llm\src\main\cpp\third_party'
$target = Join-Path $dest 'llama.cpp'

if (Test-Path (Join-Path $target 'CMakeLists.txt')) {
  Write-Host "[skip] llama.cpp already present in $target"
  exit 0
}
New-Item -ItemType Directory -Force $dest | Out-Null
$archive = Join-Path $env:TEMP "llama.cpp-$tag.tar.gz"
Invoke-WebRequest -UseBasicParsing -OutFile $archive -Uri "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$tag.tar.gz"
$got = (Get-FileHash $archive -Algorithm SHA256).Hash
if ($got -ne $sha) { Remove-Item $archive; throw "checksum mismatch for llama.cpp $tag ($got)" }
tar -xzf $archive -C $dest
Rename-Item (Join-Path $dest "llama.cpp-$tag") 'llama.cpp'
Remove-Item $archive
Write-Host "[ok] llama.cpp $tag -> $target"
