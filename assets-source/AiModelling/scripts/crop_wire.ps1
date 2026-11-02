Add-Type -AssemblyName System.Drawing
$srcPath = 'e:\Modding\Blockbench\AiModelling\references\Isometric_view_wire.png'
$outDir  = 'e:\Modding\Blockbench\AiModelling\references\zooms'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$src = [System.Drawing.Image]::FromFile($srcPath)

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

# Bottom-left cell (front view) = x 0..467, y 561..1122
# wire/swag band between posts: x 90..377, y 640..860
Crop-Zoom -x 90  -y 640 -w 287 -h 220 -scale 2 -name 'c_front_wire'
# top of a post with attachment, front view
Crop-Zoom -x 20  -y 561 -w 180 -h 160 -scale 2 -name 'c_front_posttop'
# Top-left cell (iso view) = x 0..467, y 0..561
# central wire band in iso
Crop-Zoom -x 110 -y 60 -w 260 -h 220 -scale 2 -name 'c_iso_wire'
# iso post top with attachment
Crop-Zoom -x 20  -y 0  -w 180 -h 170 -scale 2 -name 'c_iso_posttop'

$src.Dispose()
Write-Output 'DONE'
