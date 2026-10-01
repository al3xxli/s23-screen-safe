param(
    [Parameter(Mandatory=$true)][string]$Jdk,
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$BuildTools,
    [Parameter(Mandatory=$true)][string]$R8,
    [Parameter(Mandatory=$true)][string]$BuildDirectory
)
$ErrorActionPreference='Stop'
$taskOutput=Split-Path $PSScriptRoot -Parent
foreach($taskName in @('app-classes','app-dex','backend-classes','backend-dex')) { New-Item -ItemType Directory -Force (Join-Path $BuildDirectory $taskName) | Out-Null }
foreach($taskName in @('app','backend')) {
    $taskSources=Get-ChildItem (Join-Path $PSScriptRoot $taskName) -Filter *.java | Select-Object -ExpandProperty FullName
    & "$Jdk\bin\javac.exe" -encoding UTF-8 --release 8 -classpath $AndroidJar -d "$BuildDirectory\$taskName-classes" $taskSources
    if($LASTEXITCODE -ne 0){throw "$taskName compilation failed"}
    $taskClasses=Get-ChildItem "$BuildDirectory\$taskName-classes" -Filter *.class -Recurse | Select-Object -ExpandProperty FullName
    & "$Jdk\bin\java.exe" -cp $R8 com.android.tools.r8.D8 --min-api 36 --lib $AndroidJar --output "$BuildDirectory\$taskName-dex" $taskClasses
    if($LASTEXITCODE -ne 0){throw "$taskName dex conversion failed"}
}
& "$BuildTools\aapt2.exe" compile --dir "$PSScriptRoot\app\res" -o "$BuildDirectory\resources.zip"
if($LASTEXITCODE -ne 0){throw 'Resource compilation failed'}
& "$BuildTools\aapt2.exe" link -I $AndroidJar --manifest "$PSScriptRoot\app\AndroidManifest.xml" -o "$BuildDirectory\unsigned.apk" "$BuildDirectory\resources.zip"
if($LASTEXITCODE -ne 0){throw 'Resource linking failed'}
$taskZip=[IO.Compression.ZipFile]::Open("$BuildDirectory\unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip,"$BuildDirectory\app-dex\classes.dex",'classes.dex')|Out-Null}finally{$taskZip.Dispose()}
& "$BuildTools\zipalign.exe" -f -p 4 "$BuildDirectory\unsigned.apk" "$BuildDirectory\aligned.apk"
if($LASTEXITCODE -ne 0){throw 'Alignment failed'}
if(-not(Test-Path "$BuildDirectory\screensafe.keystore")){
    & "$Jdk\bin\keytool.exe" -genkeypair -keystore "$BuildDirectory\screensafe.keystore" -storepass android -keypass android -alias screensafe-debug -keyalg RSA -validity 3650 -dname 'CN=Screen Safe Development'
    if($LASTEXITCODE -ne 0){throw 'Development key generation failed'}
}
& "$Jdk\bin\java.exe" -jar "$BuildTools\lib\apksigner.jar" sign --ks "$BuildDirectory\screensafe.keystore" --ks-pass pass:android --key-pass pass:android --out "$taskOutput\ScreenSafe.apk" "$BuildDirectory\aligned.apk"
if($LASTEXITCODE -ne 0){throw 'Signing failed'}
& "$Jdk\bin\java.exe" -jar "$BuildTools\lib\apksigner.jar" verify "$taskOutput\ScreenSafe.apk"
if($LASTEXITCODE -ne 0){throw 'APK verification failed'}
Copy-Item -LiteralPath "$BuildDirectory\backend-dex\classes.dex" -Destination "$taskOutput\screensafe-backend.dex" -Force
Write-Output 'Screen Safe APK and controller built and verified.'
