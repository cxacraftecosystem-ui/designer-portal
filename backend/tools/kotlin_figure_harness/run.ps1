<#
    Compile the three SHIPPING Kotlin rasteriser sources on a bare JVM and check every figure they
    draw against the dump `report_figure_oracle.py` took from the Python.

        pwsh backend/tools/kotlin_figure_harness/run.ps1 <repo-root> <oracle-dump-dir>

    NOT A GRADLE TASK, and not run in CI. It needs a JVM, and `backend/tests/test_report_parity.py`
    exists precisely so the everyday guard does not — the backend is deployed without the Android
    tree and the container has no JDK. This is the tool you run when you have TOUCHED one of the
    rasterisers, and the one that told the port it was right in the first place.

    The compiler is taken out of the Gradle cache rather than from a `kotlinc` on PATH, because there
    is no kotlinc on the machines this project is developed on and the Android build has already
    downloaded a compiler of exactly the right version. `kotlin-compiler-embeddable` is not
    self-contained: it needs the stdlib, coroutines, trove4j and the JetBrains annotations on its own
    classpath or it dies with a NoClassDefFoundError from inside IR lowering that says nothing about
    the missing jar.

    ReportModel.kt is compiled too, for its block types, and `@Serializable` is left in place: without
    the compiler plugin it is an ordinary annotation, and nothing here asks for a serializer.
#>
param(
    [Parameter(Mandatory = $true)][string] $RepoRoot,
    [Parameter(Mandatory = $true)][string] $OracleDir
)

$ErrorActionPreference = "Stop"

function Find-Jar([string] $Pattern) {
    $cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"
    $hit = Get-ChildItem -Path $cache -Recurse -Filter $Pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" } |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $hit) { throw "no jar matching $Pattern in the Gradle cache; run an Android build first" }
    return $hit.FullName
}

$compiler   = Find-Jar "kotlin-compiler-embeddable-*.jar"
$stdlib     = Find-Jar "kotlin-stdlib-2*.jar"
$serialCore = Find-Jar "kotlinx-serialization-core-jvm-*.jar"
$coroutines = Find-Jar "kotlinx-coroutines-core-jvm-*.jar"
$trove      = Find-Jar "trove4j-*.jar"
$annots     = Find-Jar "annotations-2*.jar"

$report = Join-Path $RepoRoot "android\app\src\main\java\com\designprototype\workshop\report"
$here   = $PSScriptRoot
$out    = Join-Path $here "out"

Write-Output "== compiling the SHIPPING sources plus the harness =="
& java "-Xmx2g" "-cp" "$compiler;$stdlib;$coroutines;$trove;$annots" `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -nowarn -no-stdlib -classpath "$stdlib;$serialCore" -d $out `
    (Join-Path $report "ReportRaster.kt") `
    (Join-Path $report "ReportChart.kt") `
    (Join-Path $report "ReportMap.kt") `
    (Join-Path $report "ReportModel.kt") `
    (Join-Path $here "Harness.kt")
if ($LASTEXITCODE -ne 0) { throw "the Kotlin compile failed" }

Write-Output "== running the harness against the oracle dump =="
& java "-Xmx2g" "-cp" "$out;$stdlib;$serialCore" `
    com.designprototype.workshop.report.HarnessKt $OracleDir $RepoRoot
exit $LASTEXITCODE
