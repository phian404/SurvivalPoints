# tools/check_face_sampling.ps1
# Deterministic check: for every face of every fence block model, sample the
# pixels the UV window actually covers in the assigned texture and report the
# share of fully transparent pixels. A large face that samples mostly
# transparent pixels reads as a hole in 3D.
#
# Notes:
#  - UV "rotation" changes the mapping orientation, not WHICH pixels the rect
#    covers, so the covered rectangle alone answers the transparency question.
#  - Textures are 16x16; UV windows may legitimately be shorter (sub-region).

param(
    [string]$ModelDir = "src/main/resources/assets/survivalpoints/models/block",
    [string]$TexDir   = "src/main/resources/assets/survivalpoints/textures/block"
)

Add-Type -AssemblyName System.Drawing

$models = @(
    "post_core.json",
    "section_planks.json", "section_planks_tongue.json",
    "section_wire.json",   "section_wire_tongue.json", "section_wire_brace.json",
    "section_mesh.json",   "section_mesh_tongue.json",
    "section_mesh_top.json", "section_mesh_tongue_top.json",
    "section_mesh_bottom.json", "section_mesh_tongue_bottom.json",
    "section_mesh_middle.json", "section_mesh_tongue_middle.json",
    "section_sheet.json",  "section_sheet_tongue.json",
    "section_sheet_top.json", "section_sheet_tongue_top.json",
    "section_sheet_bottom.json", "section_sheet_tongue_bottom.json",
    "section_sheet_middle.json", "section_sheet_tongue_middle.json"
)

$texCache = @{}
function Get-TexInfo([string]$name) {
    if ($script:texCache.ContainsKey($name)) { return $script:texCache[$name] }
    $path = Join-Path $TexDir "$name.png"
    if (-not (Test-Path $path)) { return $null }
    $bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $path))
    $w = $bmp.Width; $h = $bmp.Height
    $alpha = New-Object 'bool[,]' $w, $h
    for ($y = 0; $y -lt $h; $y++) {
        for ($x = 0; $x -lt $w; $x++) {
            $alpha[$x, $y] = ($bmp.GetPixel($x, $y).A -gt 8)
        }
    }
    $totalPx = $w * $h
    $opaquePx = 0
    for ($y = 0; $y -lt $h; $y++) {
        for ($x = 0; $x -lt $w; $x++) { if ($alpha[$x, $y]) { $opaquePx++ } }
    }
    $bmp.Dispose()
    $dense = ($opaquePx / $totalPx) -ge 0.90
    $info = @{ W = $w; H = $h; Alpha = $alpha; Dense = $dense; OpaqueShare = ($opaquePx / $totalPx) }
    $script:texCache[$name] = $info
    return $info
}

$issues = 0

foreach ($m in $models) {
    $path = Join-Path $ModelDir $m
    if (-not (Test-Path $path)) { Write-Host "  MISSING $m"; $script:issues++; continue }
    $json = Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json

    $resolved = @{}
    foreach ($p in $json.textures.PSObject.Properties) {
        $v = [string]$p.Value
        if ($v.StartsWith("#")) { continue }
        if ($v -match '^survivalpoints:block/(.+)$') { $resolved[$p.Name] = $Matches[1] }
        elseif ($v -match '^(.+)$') { $resolved[$p.Name] = $v }
    }

    Write-Host ""
    Write-Host "== $m"

    foreach ($el in $json.elements) {
        $dx = [double]$el.to[0] - [double]$el.from[0]
        $dy = [double]$el.to[1] - [double]$el.from[1]
        $dz = [double]$el.to[2] - [double]$el.from[2]

        foreach ($f in $el.faces.PSObject.Properties) {
            $faceName = $f.Name
            $face = $f.Value
            if ($null -eq $face.uv) { continue }
            $key = ([string]$face.texture).TrimStart('#')
            if (-not $resolved.ContainsKey($key)) { continue }
            $texName = $resolved[$key]
            $tex = Get-TexInfo $texName
            if ($null -eq $tex) { Write-Host ("  {0,-6} texture missing: {1}" -f $faceName, $texName); continue }

            # Face dimensions in px (model units == px at 16 density)
            switch ($faceName) {
                "north" { $fw = $dx; $fh = $dy }
                "south" { $fw = $dx; $fh = $dy }
                "east"  { $fw = $dz; $fh = $dy }
                "west"  { $fw = $dz; $fh = $dy }
                "up"    { $fw = $dx; $fh = $dz }
                "down"  { $fw = $dx; $fh = $dz }
                default { $fw = 0; $fh = 0 }
            }
            $rot = 0
            if ($face.rotation) { $rot = [int]$face.rotation }
            if ($rot -eq 90 -or $rot -eq 270) { $tmp = $fw; $fw = $fh; $fh = $tmp }

            $u1 = [int]$face.uv[0]; $v1 = [int]$face.uv[1]
            $u2 = [int]$face.uv[2]; $v2 = [int]$face.uv[3]
            # A UV window may be written in reverse order (u1 > u2 and/or
            # v1 > v2): vanilla accepts it and mirrors the texture. For pixel
            # sampling and the aspect check only the rectangle matters, so the
            # bounds are normalised. Without this the sampling loop below never
            # ran and every mirrored face was reported as SAMPLES NOTHING.
            $uMin = [math]::Min($u1, $u2); $uMax = [math]::Max($u1, $u2)
            $vMin = [math]::Min($v1, $v2); $vMax = [math]::Max($v1, $v2)
            $uw = $uMax - $uMin; $vh = $vMax - $vMin

            # UV rect must cover the face proportionally (guideline 4.2)
            # A flat panel is modelled as a zero-thickness cube (from x to x), so
            # its north/south/up/down faces are 0-wide and have no aspect ratio
            # to compare against. Skipping them is not a pass: the transparency
            # check below still runs on the pixels the UV window covers.
            $ratioOk = $true
            if ($fw -gt 0 -and $fh -gt 0) {
                $modelRatio = [math]::Round($fw / $fh, 3)
                $uvRatio = [math]::Round($uw / $vh, 3)
                if ([math]::Abs($modelRatio - $uvRatio) -gt 0.01) { $ratioOk = $false }
            }

            $total = 0; $opaque = 0
            for ($py = $vMin; $py -lt $vMax; $py++) {
                for ($px = $uMin; $px -lt $uMax; $px++) {
                    if ($px -lt 0 -or $py -lt 0 -or $px -ge $tex.W -or $py -ge $tex.H) { continue }
                    $total++
                    if ($tex.Alpha[$px, $py]) { $opaque++ }
                }
            }

            $faceArea = $fw * $fh
            $share = 0.0
            if ($total -gt 0) { $share = [math]::Round(100.0 * $opaque / $total, 1) }

            # A "hole" is only a defect on a DENSE texture: wire / mesh are
            # perforated by design, so a low opaque share there is expected.
            $flag = ""
            if ($total -eq 0) { $flag = "  <== SAMPLES NOTHING"; $script:issues++ }
            elseif (-not $ratioOk) { $flag = "  <== ASPECT MISMATCH"; $script:issues++ }
            elseif ($tex.Dense -and $share -lt 90.0) { $flag = "  <== HOLE IN DENSE TEXTURE"; $script:issues++ }

            $texKind = "perforated"
            if ($tex.Dense) { $texKind = "dense" }
            Write-Host ("  {0,-6} {1,2}x{2,-2} uv[{3},{4},{5},{6}] rot{7,-3} opaque {8,5:N1}% of {9}px  ({10}){11}" -f `
                $faceName, $fw, $fh, $u1, $v1, $u2, $v2, $rot, $share, $total, $texKind, $flag)
        }
    }
}

Write-Host ""
Write-Host "TOTAL ISSUES: $issues"
if ($issues -gt 0) { exit 1 } else { exit 0 }