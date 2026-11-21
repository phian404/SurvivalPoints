# Аудит текстур и моделей оград SurvivalPoints.
# Проверяет: размер PNG, альфа-политику, UV в пределах 0..16,
# соответствие UV-окон размерам граней, отсутствие внешних текстур.

Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$texDir = Join-Path $root 'src\main\resources\assets\survivalpoints\textures\block'
$modDir = Join-Path $root 'src\main\resources\assets\survivalpoints\models\block'

$fail = 0

Write-Host '=== TEXTURES ==='
# 4 own PNGs + VANILLA references: post_core uses stripped_oak_log,
# plank sections use oak_planks (owner decision: vanilla planks replaced
# the custom plank.png). post_wood.png / post_wood_top.png were an
# unauthorised deviation and are gone.
foreach ($n in @('wire.png','mesh.png','sheet.png','metal.png')) {
    $p = Join-Path $texDir $n
    if (-not (Test-Path $p)) { Write-Host ("MISSING " + $n); $fail++; continue }
    $bmp = New-Object System.Drawing.Bitmap($p)
    $w = $bmp.Width; $h = $bmp.Height
    $colors = @{}
    $semi = 0; $opaque = 0; $clear = 0
    for ($y = 0; $y -lt $h; $y++) {
        for ($x = 0; $x -lt $w; $x++) {
            $c = $bmp.GetPixel($x, $y)
            $a = $c.A
            if ($a -eq 0) { $clear++ }
            elseif ($a -eq 255) { $opaque++; $colors[$c.ToArgb()] = 1 }
            else { $semi++ }
        }
    }
    $bmp.Dispose()
    $ok = ($w -eq 16 -and $h -eq 16)
    $alphaOk = ($semi -eq 0)
    $status = if ($ok -and $alphaOk) { 'OK' } else { 'FAIL' }
    if (-not ($ok -and $alphaOk)) { $fail++ }
    Write-Host ("{0,-22} {1}x{2} colors={3} clear={4} opaque={5} semi={6} -> {7}" -f `
        $n, $w, $h, $colors.Count, $clear, $opaque, $semi, $status)
}

Write-Host ''
Write-Host '=== MODELS: UV bounds + face-ratio ==='
$models = @('post_core.json',
    'section_planks.json','section_planks_tongue.json',
    'section_wire.json','section_wire_tongue.json','section_wire_brace.json',
    'section_mesh.json','section_mesh_tongue.json',
    'section_mesh_top.json','section_mesh_tongue_top.json',
    'section_mesh_bottom.json','section_mesh_tongue_bottom.json',
    'section_mesh_middle.json','section_mesh_tongue_middle.json',
    'section_sheet.json','section_sheet_tongue.json',
    'section_sheet_top.json','section_sheet_tongue_top.json',
    'section_sheet_bottom.json','section_sheet_tongue_bottom.json',
    'section_sheet_middle.json','section_sheet_tongue_middle.json')

foreach ($m in $models) {
    $p = Join-Path $modDir $m
    if (-not (Test-Path $p)) { Write-Host ("MISSING " + $m); $fail++; continue }
    $j = Get-Content $p -Raw | ConvertFrom-Json

    # внешние текстуры
    foreach ($t in $j.textures.PSObject.Properties) {
        $v = $t.Value
        # The POST is required by spec §6.2 to use vanilla stripped_oak_log.
        # Plank sections use vanilla oak_planks by owner decision.
        # Other sections must stay self-contained and never borrow vanilla textures.
        $vanillaAllowed = @('post_core.json','section_planks.json','section_planks_tongue.json')
        if ($v -like 'minecraft:*' -or $v -like 'block/*') {
            if ($vanillaAllowed -contains $m) {
                Write-Host ("  OK-VANILLA " + $m + " " + $t.Name + " = " + $v)
            }
            else {
                Write-Host ("  EXTERNAL-TEXTURE " + $m + " " + $t.Name + " = " + $v); $fail++
            }
        }
    }

    $bad = 0
    foreach ($el in $j.elements) {
        $fx = [double]$el.to[0] - [double]$el.from[0]
        $fy = [double]$el.to[1] - [double]$el.from[1]
        $fz = [double]$el.to[2] - [double]$el.from[2]
        foreach ($fp in $el.faces.PSObject.Properties) {
            if (-not $fp.Value.uv) { continue }
            $uv = $fp.Value.uv
            foreach ($v in $uv) {
                if ([double]$v -lt 0 -or [double]$v -gt 16) {
                    Write-Host ("  UV-OUT " + $m + " " + $fp.Name + " " + ($uv -join ',')); $bad++; $fail++
                }
            }
            # размеры грани
            $face = $fp.Name
            $dw = 0; $dh = 0
            switch ($face) {
                'north' { $dw = $fx; $dh = $fy }
                'south' { $dw = $fx; $dh = $fy }
                'east'  { $dw = $fz; $dh = $fy }
                'west'  { $dw = $fz; $dh = $fy }
                'up'    { $dw = $fx; $dh = $fz }
                'down'  { $dw = $fx; $dh = $fz }
            }
            $du = [math]::Abs([double]$uv[2] - [double]$uv[0])
            $dv = [math]::Abs([double]$uv[3] - [double]$uv[1])
            $rot = 0
            if ($fp.Value.rotation) { $rot = [int]$fp.Value.rotation }
            $eu = $du; $ev = $dv
            if ($rot -eq 90 -or $rot -eq 270) { $eu = $dv; $ev = $du }
            if ($dw -gt 0 -and $dh -gt 0) {
                if ([math]::Abs($eu - $dw) -gt 0.01 -or [math]::Abs($ev - $dh) -gt 0.01) {
                    Write-Host ("  RATIO " + $m + " " + $face + " face=" + $dw + "x" + $dh + " uv=" + $eu + "x" + $ev); $bad++
                }
            }
        }
    }
    if ($bad -eq 0) { Write-Host ("  OK " + $m) }
}

Write-Host ''
Write-Host ("TOTAL ISSUES: " + $fail)