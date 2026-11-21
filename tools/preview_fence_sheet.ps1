# Контактный лист текстур оград + тест тайлинга 2x2.
# Складывает 7 текстур в один лист и проверяет бесшовность по обеим осям.

Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$texDir = Join-Path $root 'src\main\resources\assets\survivalpoints\textures\block'
$outDir = Join-Path $root 'tools\previews'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$names = @('plank.png','post_wood.png','post_wood_top.png','wire.png','mesh.png','sheet.png','metal.png')
$S = 16
$PAD = 8
$SC = 6

# --------- контактный лист: 7 текстур в ряд ---------
$cellW = $S * $SC
$W = ($cellW + $PAD) * $names.Count + $PAD
$H = $cellW + $PAD * 2
$sheet = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($sheet)
$g.Clear([System.Drawing.Color]::FromArgb(255, 32, 32, 38))
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half

$i = 0
foreach ($n in $names) {
    $p = Join-Path $texDir $n
    $bmp = New-Object System.Drawing.Bitmap($p)
    $g.DrawImage($bmp, ($PAD + $i * ($cellW + $PAD)), $PAD, $cellW, $cellW)
    $bmp.Dispose()
    $i++
}
$g.Dispose()
$sheet.Save((Join-Path $outDir 'fence_contact_sheet.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()
Write-Host 'contact sheet -> tools/previews/fence_contact_sheet.png'

# --------- тест тайлинга 2x2 ---------
$tile = New-Object System.Drawing.Bitmap(($S*2*$SC), ($S*2*$SC), [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g2 = [System.Drawing.Graphics]::FromImage($tile)
$g2.Clear([System.Drawing.Color]::FromArgb(255, 32, 32, 38))
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half

foreach ($n in @('plank.png','mesh.png','sheet.png')) {
    $p = Join-Path $texDir $n
    $bmp = New-Object System.Drawing.Bitmap($p)
    $t = $S * $SC
    # 2x2 мощение
    for ($yy = 0; $yy -lt 2; $yy++) {
        for ($xx = 0; $xx -lt 2; $xx++) {
            $g2.DrawImage($bmp, ($xx*$t), ($yy*$t), $t, $t)
        }
    }
    $bmp.Dispose()
}
$g2.Dispose()
$tile.Save((Join-Path $outDir 'fence_tiling_2x2.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$tile.Dispose()
Write-Host 'tiling test -> tools/previews/fence_tiling_2x2.png'