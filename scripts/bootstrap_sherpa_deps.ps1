# Downloads sherpa-onnx runtime libs + models needed by SpeechService.
# Writes only into app/src/main/{assets,jniLibs} (gitignored) and expects
# bpe.vocab to be committed in the repo.

$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot ".."))
Set-Location $RootDir

$ZipformerModelDir = Join-Path $RootDir "app\src\main\assets\sherpa-onnx-zipformer-en-2023-04-01"
$AssetsDir = Join-Path $RootDir "app\src\main\assets"
$JniDir = Join-Path $RootDir "app\src\main\jniLibs\arm64-v8a"

$ZipformerTarballUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-en-2023-04-01.tar.bz2"
$SileroVadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
$SherpaAndroidTarballUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.23/sherpa-onnx-v1.12.23-android.tar.bz2"

New-Item -ItemType Directory -Force -Path $ZipformerModelDir | Out-Null
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null
New-Item -ItemType Directory -Force -Path $JniDir | Out-Null

# bpe.vocab is committed in the repo. Fail fast if it is missing.
$BpeVocabPath = Join-Path $ZipformerModelDir "bpe.vocab"
if (-not (Test-Path $BpeVocabPath) -or ((Get-Item $BpeVocabPath).Length -le 0)) {
  Write-Error "Missing $BpeVocabPath. This file should be committed in the repo. Re-clone or restore it."
}

$TmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null

function Cleanup {
  if (Test-Path $TmpDir) {
    Remove-Item -Recurse -Force $TmpDir
  }
}

try {
  function Fetch($Url, $OutFile) {
    if (Test-Path $OutFile -and (Get-Item $OutFile).Length -gt 0) {
      Write-Host "OK  : cached" (Split-Path $OutFile -Leaf)
      return
    }
    Write-Host "GET :" $Url
    Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing
  }

  function Ensure-Tar {
    if (-not (Get-Command tar -ErrorAction SilentlyContinue)) {
      throw "Missing 'tar' command. Install tar (bsdtar) or use Git Bash/WSL."
    }
  }

  Ensure-Tar

  $ZipformerTarball = Join-Path $TmpDir "zipformer.tar.bz2"
  $SherpaAndroidTarball = Join-Path $TmpDir "sherpa-android.tar.bz2"

  function Extract-TarPaths([string]$Tarball, [string[]]$Paths) {
    & tar -xjf $Tarball -C $TmpDir @Paths
    return ($LASTEXITCODE -eq 0)
  }

  $ZipformerRequiredA = @(
    "sherpa-onnx-zipformer-en-2023-04-01/encoder-epoch-99-avg-1.int8.onnx",
    "sherpa-onnx-zipformer-en-2023-04-01/decoder-epoch-99-avg-1.int8.onnx",
    "sherpa-onnx-zipformer-en-2023-04-01/joiner-epoch-99-avg-1.int8.onnx",
    "sherpa-onnx-zipformer-en-2023-04-01/tokens.txt",
    "sherpa-onnx-zipformer-en-2023-04-01/bpe.model"
  )
  $ZipformerRequiredB = @(
    "encoder-epoch-99-avg-1.int8.onnx",
    "decoder-epoch-99-avg-1.int8.onnx",
    "joiner-epoch-99-avg-1.int8.onnx",
    "tokens.txt",
    "bpe.model"
  )

  $NeedZipformer = $false
  foreach ($Rel in $ZipformerRequiredA) {
    $OutPath = Join-Path $ZipformerModelDir (Split-Path $Rel -Leaf)
    if (-not (Test-Path $OutPath) -or ((Get-Item $OutPath).Length -le 0)) { $NeedZipformer = $true }
  }

  if ($NeedZipformer) {
    Fetch $ZipformerTarballUrl $ZipformerTarball
    Write-Host "EXTR: zipformer model files"
    $Extracted = $ZipformerRequiredA
    if (-not (Extract-TarPaths $ZipformerTarball $ZipformerRequiredA)) {
      if (-not (Extract-TarPaths $ZipformerTarball $ZipformerRequiredB)) {
        throw "Failed to extract Zipformer model files from archive"
      }
      $Extracted = $ZipformerRequiredB
    }

    foreach ($Rel in $Extracted) {
      Copy-Item -Force (Join-Path $TmpDir $Rel) (Join-Path $ZipformerModelDir (Split-Path $Rel -Leaf))
    }
  } else {
    Write-Host "OK  : Zipformer model files already present"
  }

	  $SileroVadPath = Join-Path $AssetsDir "silero_vad.onnx"
	  if (-not (Test-Path $SileroVadPath) -or ((Get-Item $SileroVadPath).Length -le 0)) {
	    Fetch $SileroVadUrl $SileroVadPath
	  } else {
	    Write-Host "OK  : silero_vad.onnx already present"
	  }

	  $JniRequiredA = @(
	    "jniLibs/arm64-v8a/libonnxruntime.so",
	    "jniLibs/arm64-v8a/libsherpa-onnx-jni.so",
	    "jniLibs/arm64-v8a/libsherpa-onnx-c-api.so",
	    "jniLibs/arm64-v8a/libsherpa-onnx-cxx-api.so"
	  )
	  $JniRequiredB = @(
	    "sherpa-onnx-v1.12.23-android/jniLibs/arm64-v8a/libonnxruntime.so",
	    "sherpa-onnx-v1.12.23-android/jniLibs/arm64-v8a/libsherpa-onnx-jni.so",
	    "sherpa-onnx-v1.12.23-android/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so",
	    "sherpa-onnx-v1.12.23-android/jniLibs/arm64-v8a/libsherpa-onnx-cxx-api.so"
	  )

	  $NeedJni = $false
	  foreach ($Rel in $JniRequiredA) {
	    $OutPath = Join-Path $JniDir (Split-Path $Rel -Leaf)
	    if (-not (Test-Path $OutPath) -or ((Get-Item $OutPath).Length -le 0)) { $NeedJni = $true }
	  }

	  if ($NeedJni) {
	    Fetch $SherpaAndroidTarballUrl $SherpaAndroidTarball
	    Write-Host "EXTR: android jni libs"
	    $Extracted = $JniRequiredA
	    if (-not (Extract-TarPaths $SherpaAndroidTarball $JniRequiredA)) {
	      if (-not (Extract-TarPaths $SherpaAndroidTarball $JniRequiredB)) {
	        throw "Failed to extract Android JNI libs from archive"
	      }
	      $Extracted = $JniRequiredB
	    }

	    foreach ($Rel in $Extracted) {
	      Copy-Item -Force (Join-Path $TmpDir $Rel) (Join-Path $JniDir (Split-Path $Rel -Leaf))
	    }
	  } else {
	    Write-Host "OK  : JNI libs already present"
	  }

  Write-Host ""
  Write-Host "Done. Verified files:"
  Get-Item $SileroVadPath,
    (Join-Path $ZipformerModelDir "encoder-epoch-99-avg-1.int8.onnx"),
    (Join-Path $ZipformerModelDir "decoder-epoch-99-avg-1.int8.onnx"),
    (Join-Path $ZipformerModelDir "joiner-epoch-99-avg-1.int8.onnx"),
    (Join-Path $ZipformerModelDir "tokens.txt"),
    (Join-Path $ZipformerModelDir "bpe.model"),
    (Join-Path $ZipformerModelDir "bpe.vocab"),
    (Join-Path $JniDir "libonnxruntime.so"),
    (Join-Path $JniDir "libsherpa-onnx-jni.so"),
    (Join-Path $JniDir "libsherpa-onnx-c-api.so"),
    (Join-Path $JniDir "libsherpa-onnx-cxx-api.so") | Format-Table Name, Length

} finally {
  Cleanup
}
