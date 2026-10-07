# Cria no TLauncher a versão separada "kingdoms-modpack": NeoForge 1.21.1 + Kingdoms AI + os mods de mods.json.
# Não mexe no perfil "kingdoms" nem no "mine". Baixa os arquivos do Modrinth (API pública, sem chave);
# o que não estiver lá vai para a lista de downloads manuais (coloque os arquivos em extras\ e rode de novo).
param(
    [string]$Fonte = "",
    [string]$Nome = "kingdoms-modpack"
)
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$utf8 = New-Object System.Text.UTF8Encoding $false
$mcVersion = "1.21.1"
$headers = @{ "User-Agent" = "kingdomsai-modpack-installer/1.0 (github.com/andrausp/kingdomai)" }

# 1) versão base: o instalador normal cria a pasta com o NeoForge + Kingdoms AI
$base = Join-Path $PSScriptRoot "..\instalar-tlauncher.ps1"
if ($Fonte -eq "") { & $base -Nome $Nome } else { & $base -Fonte $Fonte -Nome $Nome }

$dst = Join-Path (Join-Path $env:APPDATA ".minecraft\versions") $Nome
$pastas = @{
    "mod"          = Join-Path $dst "mods"
    "shader"       = Join-Path $dst "shaderpacks"
    "resourcepack" = Join-Path $dst "resourcepacks"
}
foreach ($p in $pastas.Values) { New-Item -ItemType Directory -Force -Path $p | Out-Null }

function Api([string]$path) {
    Invoke-RestMethod -Uri ("https://api.modrinth.com/v2/" + $path) -Headers $headers
}

function Filtro([string]$tipo) {
    switch ($tipo) {
        "mod"    { return '["neoforge"]' }
        "shader" { return '["iris"]' }
        default  { return '["minecraft"]' }
    }
}

# Melhor versão de um projeto: primeiro exata para 1.21.1; para shader/resourcepack aceita a mais recente.
function MelhorVersao([string]$projeto, [string]$tipo) {
    $loaders = [Uri]::EscapeDataString((Filtro $tipo))
    $gv = [Uri]::EscapeDataString("[`"$mcVersion`"]")
    try { $vs = @(Api "project/$projeto/version?loaders=$loaders&game_versions=$gv") } catch { return $null }
    if ($vs.Count -eq 0 -and $tipo -ne "mod") {
        try { $vs = @(Api "project/$projeto/version") } catch { return $null }
    }
    if ($vs.Count -eq 0) { return $null }
    $rel = @($vs | Where-Object { $_.version_type -eq "release" })
    if ($rel.Count -gt 0) { return $rel[0] }
    return $vs[0]
}

function BuscarProjeto([string]$texto, [string]$tipo) {
    $facets = "[[`"project_type:$tipo`"]"
    if ($tipo -eq "mod") { $facets += ",[`"categories:neoforge`"],[`"versions:$mcVersion`"]" }
    $facets += "]"
    try { $r = Api ("search?limit=1&query=" + [Uri]::EscapeDataString($texto) + "&facets=" + [Uri]::EscapeDataString($facets)) } catch { return $null }
    if ($r.hits.Count -gt 0) { return $r.hits[0].slug }
    return $null
}

$feitos = @{}
$manuais = New-Object System.Collections.ArrayList
$baixados = @{}

function Instalar($versao, [string]$tipo, [string]$nome) {
    if ($feitos.ContainsKey($versao.project_id)) { return }
    $feitos[$versao.project_id] = $true
    $arq = @($versao.files | Where-Object { $_.primary })
    if ($arq.Count -eq 0) { $arq = @($versao.files) }
    $f = $arq[0]
    $alvo = Join-Path $pastas[$tipo] $f.filename
    if (Test-Path $alvo) {
        Write-Host "  = $nome já está instalado ($($f.filename))" -ForegroundColor DarkGray
    } else {
        Write-Host "  + $nome  ->  $($f.filename)" -ForegroundColor Green
        Invoke-WebRequest -Uri $f.url -OutFile $alvo -Headers $headers -UseBasicParsing
    }
    $baixados[$nome] = $f.filename
    # dependências obrigatórias (ex.: Sophisticated Core)
    foreach ($dep in @($versao.dependencies | Where-Object { $_.dependency_type -eq "required" })) {
        if ($dep.project_id -and $feitos.ContainsKey($dep.project_id)) { continue }
        $dv = $null
        if ($dep.version_id) { try { $dv = Api "version/$($dep.version_id)" } catch { } }
        if (-not $dv -and $dep.project_id) { $dv = MelhorVersao $dep.project_id "mod" }
        if ($dv) {
            $dn = $dep.project_id
            try { $dn = (Api "project/$($dep.project_id)").title } catch { }
            Instalar $dv "mod" ("dependência: " + $dn)
        }
    }
}

$manifest = Get-Content (Join-Path $PSScriptRoot "mods.json") -Raw -Encoding UTF8 | ConvertFrom-Json
Write-Host ""
Write-Host "# Baixando mods do Modrinth para $Nome" -ForegroundColor Cyan
foreach ($it in $manifest.itens) {
    $v = MelhorVersao $it.modrinth $it.tipo
    if (-not $v -and $it.busca) {
        $slug = BuscarProjeto $it.busca $it.tipo
        if ($slug) { $v = MelhorVersao $slug $it.tipo }
    }
    if ($v) {
        try { Instalar $v $it.tipo $it.nome } catch { Write-Host "  x $($it.nome): falha no download ($($_.Exception.Message))" -ForegroundColor Red; [void]$manuais.Add($it) }
    } else {
        Write-Host "  ! $($it.nome): sem versão NeoForge $mcVersion no Modrinth" -ForegroundColor Yellow
        [void]$manuais.Add($it)
    }
}

# 2) extras\ : arquivos baixados à mão (CurseForge) entram aqui
foreach ($t in @("mod", "shader", "resourcepack")) {
    $sub = Join-Path $PSScriptRoot ("extras\" + @{ "mod" = "mods"; "shader" = "shaderpacks"; "resourcepack" = "resourcepacks" }[$t])
    if (Test-Path $sub) {
        foreach ($f in Get-ChildItem $sub -File | Where-Object { $_.Extension -in ".jar", ".zip" }) {
            Copy-Item $f.FullName $pastas[$t] -Force
            Write-Host "  + extras: $($f.Name)" -ForegroundColor Green
        }
    }
}

# 3) liga o resource pack e o shader padrão (só se o jogador ainda não escolheu)
$opt = Join-Path $dst "options.txt"
foreach ($it in @($manifest.itens | Where-Object { $_.tipo -eq "resourcepack" -and $_.ativar -and $baixados.ContainsKey($_.nome) })) {
    $entry = "file/" + $baixados[$it.nome]
    if (Test-Path $opt) {
        $linhas = @(Get-Content $opt -Encoding UTF8)
        $i = 0; $achou = $false
        for (; $i -lt $linhas.Count; $i++) {
            if ($linhas[$i] -like "resourcePacks:*") {
                $achou = $true
                if ($linhas[$i] -notlike "*$entry*") { $linhas[$i] = $linhas[$i] -replace '\]\s*$', ",`"$entry`"]" -replace '\[,', '[' }
            }
        }
        if (-not $achou) { $linhas += "resourcePacks:[`"vanilla`",`"$entry`"]" }
        [System.IO.File]::WriteAllLines($opt, $linhas, $utf8)
    } else {
        [System.IO.File]::WriteAllLines($opt, @("resourcePacks:[`"vanilla`",`"$entry`"]"), $utf8)
    }
}
$shader = @($manifest.itens | Where-Object { $_.tipo -eq "shader" -and $_.padrao -and $baixados.ContainsKey($_.nome) }) | Select-Object -First 1
$irisCfg = Join-Path $dst "config\iris.properties"
if ($shader -and -not (Test-Path $irisCfg)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $irisCfg) | Out-Null
    [System.IO.File]::WriteAllLines($irisCfg, @("enableShaders=true", "shaderPack=" + $baixados[$shader.nome]), $utf8)
}

Write-Host ""
if ($manuais.Count -gt 0) {
    Write-Host "# Download manual necessário" -ForegroundColor Yellow
    foreach ($it in $manuais) {
        Write-Host "  - $($it.nome): $($it.curseforge)"
        if ($it.aviso) { Write-Host "    $($it.aviso)" -ForegroundColor DarkYellow }
    }
    Write-Host "  Baixe o arquivo NeoForge $mcVersion, coloque em modpack\extras\mods (ou resourcepacks/shaderpacks) e rode de novo."
    Write-Host "  Atalhos para as páginas: pasta modpack\links"
}
Write-Host ""
Write-Host "Pronto! Escolha a versão '$Nome' no TLauncher. Shaders: Opções > Vídeo > Shader Packs (tecla O)." -ForegroundColor Green
