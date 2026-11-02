Add-Type -AssemblyName System.Drawing
$srcPath = 'e:\Modding\Blockbench\AiModelling\references\Isometric_view_wire.png'
$outDir  = 'e:\Modding\Blockbench\AiModelling\references\zooms'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$bmp = [System.Drawing.Bitmap]::new($srcPath)
$W = $bmp.Width; $H = $bmp.Height
Write-Output ("Image {0}x{1}" -f $W, $H)

function IsContent([System.Drawing.Bitmap]$b, [int]$x, [int]$y) {
    $p = $b.GetPixel($x, $y)
    return ($p.R -lt 245 -or $p.G -lt 245 -or $p.B -lt 245)
}

function FindBox([System.Drawing.Bitmap]$b, [int]$x0, [int]$y0, [int]$x1, [int]$y1) {
    $minX = $x1; $minY = $y1; $maxX = $x0; $maxY = $y0
    for ($y = $y0; $y -lt $y1; $y += 2) {
        for ($x = $x0; $x -lt $x1; $x += 2) {
            if (IsContent $b $x $y) {
                if ($x -lt $minX) { $minX = $x }
                if ($x -gt $maxX) { $maxX = $x }
                if ($y -lt $minY) { $minY = $y }
                if ($y -gt $maxY) { $maxY = $y }
            }
        }
    }
    return @($minX, $minY, $maxX, $maxY)
}

function SaveCrop([System.Drawing.Bitmap]$b, [int]$x, [int]$y, [int]$w, [int]$h, [int]$scale, [string]$name) {
    $rect = [System.Drawing.Rectangle]::new($x, $y, $w, $h)
    $nb = [System.Drawing.Bitmap]::new([int]($w * $scale), [int]($h * $scale))
    $g = [System.Drawing.Graphics]::FromImage($nb)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $dst = [System.Drawing.Rectangle]::new(0, 0, [int]($w * $scale), [int]($h * $scale))
    $g.DrawImage($b, $dst, $rect, [System.Drawing.GraphicsUnit]::Pixel)
    $out = Join-Path $outDir ($name + '.png')
    $nb.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $nb.Dispose()
    Write-Output ("{0}: box({1},{2})-({3},{4}) {5}x{6} -> {7} bytes" -f $name, $x, $y, ($x+$w), ($y+$h), $w, $h, (Get-Item $out).Length)
}

$cols = 3; $rows = 2
$cw = [int]($W / $cols); $ch = [int]($H / $rows)
$i = 0
for ($r = 0; $r -lt $rows; $r++) {
    for ($c = 0; $c -lt $cols; $c++) {
        $i++
        $x0 = $c * $cw; $y0 = $r * $ch; $x1 = $x0 + $cw - 1; $y1 = $y0 + $ch - 1
        $box = FindBox $bmp $x0 $y0 $x1 $y1
        Write-Output ("cell{0}: content box = ({1},{2})-({3},{4})" -f $i, $box[0], $box[1], $box[2], $box[3])
        if ($box[2] -gt $box[0]) {
            $w = $box[2] - $box[0] + 1
            $h = $box[3] - $box[1] + 1
            SaveCrop $bmp $box[0] $box[1] $w $h 2 ("cell{0}_tight" -f $i)
        }
    }
}
$bmp.Dispose()
Write-Output 'DONE'
