Add-Type -AssemblyName System.Drawing
$srcPath = 'e:\Modding\Blockbench\AiModelling\references\Isometric_view_wire.png'
$outDir  = 'e:\Modding\Blockbench\AiModelling\references\zooms'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$src = [System.Drawing.Image]::FromFile($srcPath)
Write-Output ("Full size: {0}x{1}" -f $src.Width, $src.Height)

$enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$ep = New-Object System.Drawing.Imaging.EncoderParameters(1)
$ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]88)

function Crop-Zoom {
    param([int]$x, [int]$y, [int]$w, [int]$h, [int]$scale, [string]$name)
    $rect = [System.Drawing.Rectangle]::new($x, $y, $w, $h)
    $bmp = [System.Drawing.Bitmap]::new([int]($w * $scale), [int]($h * $scale))
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $dst = [System.Drawing.Rectangle]::new(0, 0, [int]($w * $scale), [int]($h * $scale))
    $g.DrawImage($src, $dst, $rect, [System.Drawing.GraphicsUnit]::Pixel)
    $out = Join-Path $outDir ($name + '.png')
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    Write-Output ("{0} -> {1} bytes" -f $out, (Get-Item $out).Length)
}

# Grid 3x2, full 1402x1122 -> cell 467x561
Crop-Zoom -x 0   -y 0   -w 467 -h 561 -scale 1 -name 'z_iso_left'
Crop-Zoom -x 0   -y 561 -w 467 -h 561 -scale 1 -name 'z_front'
Crop-Zoom -x 467 -y 561 -w 467 -h 561 -scale 1 -name 'z_single_post'
Crop-Zoom -x 934 -y 561 -w 467 -h 561 -scale 1 -name 'z_side'

$src.Dispose()
Write-Output 'DONE'
