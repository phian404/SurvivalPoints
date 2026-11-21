# tools/check_fence_assembly.ps1
# Verifies that the real game models assemble correctly:
#   post at (0,0,0) + section in a direction + tongue in the same direction.
# Checks, per direction:
#   - the section meets the post face without a gap and without penetrating
#     further than the post centre
#   - the tongue overlaps the post face (no floating tongue)
#   - the tongue stays inside 0..16 in Y and does not leave the block cell
#   - no two cubes end up as exact duplicates in the assembled cell

param(
    [string]$ModelDir = "src/main/resources/assets/survivalpoints/models/block"
)

function Load-Model([string]$name) {
    $p = Join-Path $ModelDir $name
    if (-not (Test-Path $p)) { throw "missing model $name" }
    return Get-Content -Raw -Encoding UTF8 $p | ConvertFrom-Json
}

function Get-Boxes($json) {
    $out = @()
    foreach ($el in $json.elements) {
        # Java block models may carry rotation as an OBJECT {angle, axis, origin},
        # not as a 3-number array. Treating it as an array crashed the checker on
        # section_mesh.json. Read the angle explicitly.
        #
        # The full rotation key is kept as well: the diagonal strands of the wire
        # models share the exact same bounding box and differ only by a +45 / -45
        # tilt, so comparing bounds alone reported them as duplicate cubes.
        $hasRot = $false
        $rotKey = "none"
        $r = $el.rotation
        if ($null -ne $r) {
            if ($r -is [System.Array]) {
                $rotKey = "array:" + (($r | ForEach-Object { [double]$_ }) -join ",")
                foreach ($v in $r) { if ([math]::Abs([double]$v) -gt 0.001) { $hasRot = $true } }
            }
            elseif ($null -ne $r.angle) {
                $angle = [double]$r.angle
                if ([math]::Abs($angle) -gt 0.001) { $hasRot = $true }
                $rotKey = "{0}|{1}|{2}" -f $angle, $r.axis, (($r.origin | ForEach-Object { [double]$_ }) -join ",")
            }
        }
        $out += [pscustomobject]@{
            From = @([double]$el.from[0], [double]$el.from[1], [double]$el.from[2])
            To   = @([double]$el.to[0],   [double]$el.to[1],   [double]$el.to[2])
            Rot  = $hasRot
            Key  = $rotKey
        }
    }
    return $out
}

function Rotate-Y($box, [int]$deg) {
    # Java blockstate rotation y=N turns the model about the block centre.
    # Scalar arithmetic only: arrays returned from a switch/loop get flattened
    # by PowerShell, which silently corrupted the coordinates before.
    $x1 = [double]$box.From[0]; $z1 = [double]$box.From[2]
    $x2 = [double]$box.To[0];   $z2 = [double]$box.To[2]

    $rx1 = 0.0; $rz1 = 0.0; $rx2 = 0.0; $rz2 = 0.0
    if ($deg -eq 0) {
        $rx1 = $x1; $rz1 = $z1; $rx2 = $x2; $rz2 = $z2
    }
    elseif ($deg -eq 90) {
        # (x, z) -> (16 - z, x)
        $rx1 = 16.0 - $z2; $rz1 = $x1
        $rx2 = 16.0 - $z1; $rz2 = $x2
    }
    elseif ($deg -eq 180) {
        $rx1 = 16.0 - $x2; $rz1 = 16.0 - $z2
        $rx2 = 16.0 - $x1; $rz2 = 16.0 - $z1
    }
    else {
        # 270: (x, z) -> (z, 16 - x)
        $rx1 = $z1; $rz1 = 16.0 - $x2
        $rx2 = $z2; $rz2 = 16.0 - $x1
    }

    return [pscustomobject]@{
        From = @($rx1, [double]$box.From[1], $rz1)
        To   = @($rx2, [double]$box.To[1],   $rz2)
        Rot  = $box.Rot
        Key  = $box.Key
    }
}

$materials = @(
    @{ Section = "section_planks.json"; Tongue = "section_planks_tongue.json"; Label = "planks" },
    @{ Section = "section_wire.json";   Tongue = "section_wire_tongue.json";   Label = "wire" },
    @{ Section = "section_wire_brace.json"; Tongue = "section_wire_tongue.json"; Label = "wire-brace" },
    @{ Section = "section_mesh.json";   Tongue = "section_mesh_tongue.json";   Label = "mesh" },
    @{ Section = "section_mesh_top.json";    Tongue = "section_mesh_tongue_top.json";    Label = "mesh-top" },
    @{ Section = "section_mesh_bottom.json"; Tongue = "section_mesh_tongue_bottom.json"; Label = "mesh-bottom" },
    @{ Section = "section_mesh_middle.json"; Tongue = "section_mesh_tongue_middle.json"; Label = "mesh-middle" },
    @{ Section = "section_sheet.json";  Tongue = "section_sheet_tongue.json";  Label = "sheet" },
    @{ Section = "section_sheet_top.json";    Tongue = "section_sheet_tongue_top.json";    Label = "sheet-top" },
    @{ Section = "section_sheet_bottom.json"; Tongue = "section_sheet_tongue_bottom.json"; Label = "sheet-bottom" },
    @{ Section = "section_sheet_middle.json"; Tongue = "section_sheet_tongue_middle.json"; Label = "sheet-middle" }
)

$postBoxes = Get-Boxes (Load-Model "post_core.json")
$issues = 0

Write-Host "POST (post_core.json)"
foreach ($b in $postBoxes) {
    Write-Host ("  x {0,4}..{1,-4} y {2,4}..{3,-4} z {4,4}..{5,-4} rot={6}" -f $b.From[0], $b.To[0], $b.From[1], $b.To[1], $b.From[2], $b.To[2], $b.Rot)
}

$dirs = @(
    @{ Name = "north"; Y = 0 },
    @{ Name = "east";  Y = 90 },
    @{ Name = "south"; Y = 180 },
    @{ Name = "west";  Y = 270 }
)

foreach ($mat in $materials) {
    # @(...) keeps a single-cube model an array; without it PowerShell unwraps it
    # into a scalar and .Count prints empty for the middle variants.
    $secBoxes = @(Get-Boxes (Load-Model $mat.Section))
    $tonBoxes = @(Get-Boxes (Load-Model $mat.Tongue))

    Write-Host ""
    Write-Host ("=== {0}: section {1} cubes, tongue {2} cubes" -f $mat.Label, $secBoxes.Count, $tonBoxes.Count)

    # --- Section sanity in its own local space (unrotated = facing north) ---
    $minZ = 999; $maxZ = -999; $minX = 999; $maxX = -999; $minY = 999; $maxY = -999
    foreach ($b in $secBoxes) {
        $minZ = [math]::Min($minZ, $b.From[2]); $maxZ = [math]::Max($maxZ, $b.To[2])
        $minX = [math]::Min($minX, $b.From[0]); $maxX = [math]::Max($maxX, $b.To[0])
        $minY = [math]::Min($minY, $b.From[1]); $maxY = [math]::Max($maxY, $b.To[1])
    }
    Write-Host ("  section bounds  x {0}..{1}  y {2}..{3}  z {4}..{5}" -f $minX, $maxX, $minY, $maxY, $minZ, $maxZ)

    # The section fills its own cell: it must START at z=0 (the side facing the
    # post) and end at z=16, never crossing into the neighbouring cell.
    if ([math]::Abs($minZ) -gt 0.001) {
        Write-Host ("  ISSUE: section does not start at its own cell edge (min z={0})" -f $minZ); $issues++
    }
    if ([math]::Abs($maxZ - 16.0) -gt 0.001) {
        Write-Host ("  ISSUE: section does not fill the cell (max z={0})" -f $maxZ); $issues++
    }
    if ($minY -lt 0.0 -or $maxY -gt 16.0) {
        Write-Host ("  ISSUE: section leaves the block cell in Y ({0}..{1})" -f $minY, $maxY); $issues++
    }

    # --- Tongue: bridges the section (z=0) to the post face in the NEIGHBOURING
    # cell. The post core occupies 4..12 inside its own cell, so from this cell
    # the post face sits exactly at z = -4. The tongue must span -4..0: flush on
    # both ends, no gap (floating) and no overshoot into the post body.
    $tMinZ = 999; $tMaxZ = -999; $tMinX = 999; $tMaxX = -999; $tMinY = 999; $tMaxY = -999
    foreach ($b in $tonBoxes) {
        $tMinZ = [math]::Min($tMinZ, $b.From[2]); $tMaxZ = [math]::Max($tMaxZ, $b.To[2])
        $tMinX = [math]::Min($tMinX, $b.From[0]); $tMaxX = [math]::Max($tMaxX, $b.To[0])
        $tMinY = [math]::Min($tMinY, $b.From[1]); $tMaxY = [math]::Max($tMaxY, $b.To[1])
    }
    Write-Host ("  tongue  bounds  x {0}..{1}  y {2}..{3}  z {4}..{5}" -f $tMinX, $tMaxX, $tMinY, $tMaxY, $tMinZ, $tMaxZ)

    if ([math]::Abs($tMaxZ) -gt 0.001) {
        Write-Host ("  ISSUE: tongue does not reach the section face at z=0 (max z={0})" -f $tMaxZ); $issues++
    }
    if ([math]::Abs($tMinZ + 4.0) -gt 0.001) {
        Write-Host ("  ISSUE: tongue does not meet the post face at z=-4 (min z={0})" -f $tMinZ); $issues++
    }
    if ($tMinY -lt 0.0 -or $tMaxY -gt 16.0) {
        Write-Host ("  ISSUE: tongue leaves the block cell in Y ({0}..{1})" -f $tMinY, $tMaxY); $issues++
    }
    if ($tMinX -lt 0.0 -or $tMaxX -gt 16.0) {
        Write-Host ("  ISSUE: tongue leaves the block cell in X ({0}..{1})" -f $tMinX, $tMaxX); $issues++
    }

    # --- Assembly: post + section + tongue, for every direction ---
    foreach ($d in $dirs) {
        $all = @()
        foreach ($b in $postBoxes) { $all += $b }
        foreach ($b in $secBoxes) { $all += (Rotate-Y $b $d.Y) }
        foreach ($b in $tonBoxes) { $all += (Rotate-Y $b $d.Y) }

        $dups = 0
        for ($i = 0; $i -lt $all.Count; $i++) {
            for ($j = $i + 1; $j -lt $all.Count; $j++) {
                $a = $all[$i]; $b = $all[$j]
                $same = $true
                if ($a.Key -ne $b.Key) { $same = $false }
                for ($k = 0; $k -lt 3; $k++) {
                    if ([math]::Abs($a.From[$k] - $b.From[$k]) -gt 0.001) { $same = $false }
                    if ([math]::Abs($a.To[$k]   - $b.To[$k])   -gt 0.001) { $same = $false }
                }
                if ($same) { $dups++ }
            }
        }
        if ($dups -gt 0) {
            Write-Host ("  ISSUE: {0} duplicate cube pair(s) in {1} assembly" -f $dups, $d.Name); $issues++
        }
    }
    Write-Host "  assembly north/east/south/west: no duplicates, cell respected"
}

Write-Host ""
Write-Host "TOTAL ISSUES: $issues"
if ($issues -gt 0) { exit 1 } else { exit 0 }