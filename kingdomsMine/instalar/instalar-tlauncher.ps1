# Cria no TLauncher um perfil limpo "kingdoms" (NeoForge 1.21.1) só com o Kingdoms AI.
# Reaproveita uma versão NeoForge 1.21.1 que você já tem (por padrão "mine"), sem mexer nela.
param(
    [string]$Fonte = "",
    [string]$Nome = "kingdoms"
)
$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding $false
$mc = Join-Path $env:APPDATA ".minecraft"
$versions = Join-Path $mc "versions"
# a versão mais nova que estiver na pasta (0.3.0 ganha de 0.2.0)
$jar = Get-ChildItem -Path $PSScriptRoot -Filter "kingdomsai-*.jar" |
    Sort-Object { try { [version]($_.BaseName -replace '^kingdomsai-', '') } catch { [version]'0.0' } } -Descending |
    Select-Object -First 1
if (-not $jar) { throw "Não achei kingdomsai-*.jar ao lado deste script." }

if ($Fonte -eq "") {
    foreach ($cand in @("mine", "modcerto", "atm", "atm2", "atm.2")) {
        $j = Join-Path $versions "$cand\$cand.json"
        if ((Test-Path $j) -and ((Get-Content $j -Raw) -match '"21\.1\.\d+"') -and ((Get-Content $j -Raw) -match 'fml\.neoForgeVersion')) { $Fonte = $cand; break }
    }
    if ($Fonte -eq "") {
        foreach ($d in Get-ChildItem $versions -Directory) {
            $j = Join-Path $d.FullName "$($d.Name).json"
            if ((Test-Path $j) -and ((Get-Content $j -Raw) -match 'fml\.neoForgeVersion') -and ((Get-Content $j -Raw) -match '"1\.21\.1"')) { $Fonte = $d.Name; break }
        }
    }
}
if ($Fonte -eq "") { throw "Nenhuma versão NeoForge 1.21.1 encontrada em $versions. Instale 'NeoForge 1.21.1' pelo TLauncher e rode de novo." }
Write-Host "Usando como base: $Fonte" -ForegroundColor Cyan

$src = Join-Path $versions $Fonte
$dst = Join-Path $versions $Nome
New-Item -ItemType Directory -Force -Path $dst | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $dst "mods") | Out-Null

# version json com o id novo (só a primeira ocorrência de "id", que é a do topo)
$json = Get-Content (Join-Path $src "$Fonte.json") -Raw
$rx = [regex]'"id"\s*:\s*"[^"]*"'
$json = $rx.Replace($json, "`"id`": `"$Nome`"", 1)
[System.IO.File]::WriteAllText((Join-Path $dst "$Nome.json"), $json, $utf8)
if (Test-Path (Join-Path $src "$Fonte.jar")) { Copy-Item (Join-Path $src "$Fonte.jar") (Join-Path $dst "$Nome.jar") -Force }

# metadados do TLauncher: mesmo formato de modpack, sem a lista de mods da fonte
$addPath = Join-Path $src "TLauncherAdditional.json"
if (Test-Path $addPath) {
    $add = Get-Content $addPath -Raw | ConvertFrom-Json
    if ($add.modpack) {
        $add.modpack.name = $Nome
        $add.modpack.id = - (Get-Random -Minimum 100000000 -Maximum 999999999)
        if ($add.modpack.version) {
            $add.modpack.version.mods = @()
            $add.modpack.version.resourcePacks = @()
            $add.modpack.version.shaderpacks = @()
            $add.modpack.version.maps = @()
            $add.modpack.version.id = - (Get-Random -Minimum 100000000 -Maximum 999999999)
        }
    }
    $add.additionalFiles = @($add.additionalFiles)
    [System.IO.File]::WriteAllText((Join-Path $dst "TLauncherAdditional.json"), ($add | ConvertTo-Json -Depth 100), $utf8)
}
if (Test-Path (Join-Path $src "options.txt")) { Copy-Item (Join-Path $src "options.txt") (Join-Path $dst "options.txt") -Force }

# remove versões antigas do mod e copia a nova
Get-ChildItem (Join-Path $dst "mods") -Filter "kingdomsai-*.jar" | Remove-Item -Force
Copy-Item $jar.FullName (Join-Path $dst "mods") -Force

Write-Host ""
Write-Host "Pronto! Perfil '$Nome' criado em $dst" -ForegroundColor Green
Write-Host "Abra o TLauncher, escolha a versão '$Nome' na lista e clique em Entrar."
Write-Host "Crie um mundo NOVO: você já começa como rei. Tecla M = Manager Mode, /k help = comandos."
