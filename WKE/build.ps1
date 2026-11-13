[CmdletBinding()]
param(
    [string]$Compiler = (Join-Path $PSScriptRoot '..\build\voxel-faces\toolchains\zig-0.14.1\zig.exe'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\build\WKE\independent'),
    [switch]$Verify,
    [switch]$Renderer,
    [string]$VulkanHeaders = (Join-Path $PSScriptRoot '..\build\core\deps\Vulkan-Headers-1.3.296\include')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This build script targets Windows x64.'
}

$expectedCompilerSha = '1ddf230367e07738c4a769eae66c1db7469e37bd520e81c86356453d0db2b9fd'
$compilerItem = Get-Item -LiteralPath $Compiler -ErrorAction Stop
if ($compilerItem.PSIsContainer) { throw 'Compiler must name the verified Zig executable.' }
$compilerPath = $compilerItem.FullName
$compilerSha = (Get-FileHash -LiteralPath $compilerPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($compilerSha -ne $expectedCompilerSha) { throw 'Zig executable SHA-256 differs from the verified 0.14.1 compiler.' }
$versionLines = @(& $compilerPath 'version')
if ($LASTEXITCODE -ne 0) { throw 'Cannot query the verified compiler version.' }
$compilerVersion = ($versionLines -join [Environment]::NewLine).Trim()
if ($compilerVersion -ne '0.14.1') { throw "Unexpected Zig version: $compilerVersion" }

$sourceRoot = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd([char[]]"\/")
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
[IO.Directory]::CreateDirectory($outputPath) | Out-Null
$receiptPath = Join-Path $outputPath 'build-receipt.json'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Write-Receipt {
    param([object]$Data)
    $json = ConvertTo-Json -InputObject $Data -Depth 20
    [IO.File]::WriteAllText($receiptPath, $json + [Environment]::NewLine, $utf8)
}
function Invoke-Zig {
    param([string[]]$Arguments)
    Write-Host ('Zig: ' + ($Arguments -join ' '))
    & $compilerPath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Native compilation failed with exit code $LASTEXITCODE." }
}
function Source-Key {
    param([string]$Path)
    return $Path.Substring($sourceRoot.Length + 1).Replace('\', '/')
}

$coreSource = @(
    (Join-Path $sourceRoot 'core\src\core.cpp'),
    (Join-Path $sourceRoot 'core\src\format.cpp')
)
$voxelSource = Join-Path $sourceRoot 'kernels\voxel-faces\voxel_faces.cpp'
$checksSource = Join-Path $sourceRoot 'core\checks\cpp\checks.cpp'
$sourceFiles = @($coreSource) + @($voxelSource, $checksSource, $PSCommandPath)
if ($Renderer) {
    $headersItem = Get-Item -LiteralPath (Join-Path $VulkanHeaders 'vulkan\vulkan_core.h') -ErrorAction Stop
    $headersPath = [IO.Path]::GetFullPath($VulkanHeaders)
    $renderSource = Join-Path $sourceRoot 'runtime\embedded\wke_vulkan.cpp'
    $renderChecksSource = Join-Path $sourceRoot 'runtime\embedded\wke_vulkan_checks.cpp'
    $materialSource = Join-Path $sourceRoot 'runtime\embedded\material_mips.cpp'
    $materialChecksSource = Join-Path $sourceRoot 'runtime\embedded\material_mips_checks.cpp'
    $sourceFiles += @($materialSource, $materialChecksSource, (Join-Path $sourceRoot 'runtime\embedded\material_mips.h'), (Join-Path $sourceRoot 'runtime\src\material_mips.hpp'))
    $sourceFiles += @($renderSource, $renderChecksSource, (Join-Path $sourceRoot 'runtime\embedded\wke_vulkan.h'), (Join-Path $sourceRoot 'runtime\embedded\vulkan_world_commands.hpp'))
}
foreach ($directory in @('core\src', 'core\include', 'kernels\voxel-faces')) {
    $sourceFiles += @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot $directory) -File -Recurse |
        Where-Object { $_.Extension -in @('.h', '.hpp', '.inl') } |
        ForEach-Object { $_.FullName })
}
$sourceFiles = @($sourceFiles | Sort-Object -Unique)
$sourceHashes = [ordered]@{}
foreach ($path in $sourceFiles) {
    $sourceHashes[(Source-Key $path)] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$receipt = [ordered]@{
    schema = 1
    engine = 'WKE'
    completed = $false
    startedUtc = [DateTime]::UtcNow.ToString('o')
    sourceRoot = $sourceRoot
    outputDirectory = $outputPath
    target = 'x86_64-windows-gnu'
    compiler = [ordered]@{ path = $compilerPath; version = $compilerVersion; sha256 = $compilerSha }
    sourceSha256 = $sourceHashes
    artifactSha256 = [ordered]@{}
    verification = [ordered]@{ requested = [bool]$Verify; completed = $false }
    javaRequired = $false
    minecraftRequired = $false
    downloadsPerformed = $false
    rendererRequested = [bool]$Renderer
}
Write-Receipt $receipt

try {
    $flags = @('c++', '-std=c++20', '-O3', '-fno-fast-math', '-ffp-contract=off',
        '-Wall', '-Wextra', '-Wpedantic', '-pthread', '-target', 'x86_64-windows-gnu')
    $coreLibrary = Join-Path $outputPath 'wke_core.dll'
    $voxelLibrary = Join-Path $outputPath 'wke_voxel.dll'
    $checksBinary = Join-Path $outputPath 'wke_core_checks.exe'
    Invoke-Zig -Arguments ($flags + $coreSource + @('-shared', '-o', $coreLibrary))
    Invoke-Zig -Arguments ($flags + @($voxelSource, '-shared', '-o', $voxelLibrary))
    Invoke-Zig -Arguments ($flags + $coreSource + @($checksSource, '-o', $checksBinary))
    $artifacts = @($coreLibrary, $voxelLibrary, $checksBinary)
    if ($Renderer) {
        $renderLibrary = Join-Path $outputPath 'wke_render.dll'
        $renderChecksBinary = Join-Path $outputPath 'wke_render_checks.exe'
        $renderFlags = $flags + @('-I' + $headersPath)
        Invoke-Zig -Arguments ($renderFlags + @($renderSource, $materialSource, '-shared', '-o', $renderLibrary))
        Invoke-Zig -Arguments ($renderFlags + @($renderSource, $renderChecksSource, '-o', $renderChecksBinary))
        $materialChecksBinary = Join-Path $outputPath 'wke_material_checks.exe'
        Invoke-Zig -Arguments ($flags + @($materialSource, $materialChecksSource, '-o', $materialChecksBinary))
        $artifacts += @($renderLibrary, $renderChecksBinary, $materialChecksBinary)
    }

    if ($Verify) {
        $checkName = 'verify-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N')
        $checkDirectory = Join-Path $outputPath $checkName
        [IO.Directory]::CreateDirectory($checkDirectory) | Out-Null
        $checkOutput = @(& $checksBinary $checkDirectory)
        if ($LASTEXITCODE -ne 0) { throw "Native core verification failed with exit code $LASTEXITCODE." }
        $checkText = $checkOutput -join [Environment]::NewLine
        $checkReport = Join-Path $checkDirectory 'native-checks.txt'
        [IO.File]::WriteAllText($checkReport, $checkText + [Environment]::NewLine, $utf8)
        Write-Host $checkText
        if ($Renderer) {
            $renderOutput = @(& $renderChecksBinary)
            if ($LASTEXITCODE -ne 0) { throw 'Native renderer contract verification failed.' }
            $renderText = $renderOutput -join [Environment]::NewLine
            if (-not $renderText.Contains('WKE_RENDER_NATIVE_PASS')) { throw 'Native renderer verification marker missing.' }
            [IO.File]::WriteAllText((Join-Path $checkDirectory 'renderer-checks.txt'), $renderText + [Environment]::NewLine, $utf8)
            Write-Host $renderText
            $materialOutput = @(& $materialChecksBinary)
            if ($LASTEXITCODE -ne 0) { throw 'Native material verification failed.' }
            $materialText = $materialOutput -join [Environment]::NewLine
            if (-not $materialText.Contains('WKE_MATERIAL_MIPS_PASS')) { throw 'Native material verification marker missing.' }
            [IO.File]::WriteAllText((Join-Path $checkDirectory 'material-checks.txt'), $materialText + [Environment]::NewLine, $utf8)
            Write-Host $materialText
        }
        $receipt.verification = [ordered]@{
            requested = $true
            completed = $true
            exitCode = 0
            outputDirectory = $checkDirectory
            report = $checkReport
            reportSha256 = (Get-FileHash -LiteralPath $checkReport -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }

    foreach ($path in $sourceFiles) {
        $key = Source-Key $path
        $after = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($after -ne $sourceHashes[$key]) { throw "Source changed during compilation: $key" }
    }
    foreach ($path in $artifacts) {
        $receipt.artifactSha256[[IO.Path]::GetFileName($path)] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $receipt['finishedUtc'] = [DateTime]::UtcNow.ToString('o')
    $receipt.completed = $true
    Write-Receipt $receipt
    Write-Host ('WKE_INDEPENDENT_BUILD_PASS ' + $receiptPath)
} catch {
    $receipt['finishedUtc'] = [DateTime]::UtcNow.ToString('o')
    $receipt['error'] = $_.Exception.Message
    $receipt.completed = $false
    Write-Receipt $receipt
    throw
}
