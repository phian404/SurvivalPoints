Add-Type -AssemblyName System.Drawing
$srcPath = 'e:\Modding\Blockbench\AiModelling\references\Isometric_view_wire.png'
$outDir  = 'e:\Modding\Blockbench\AiModelling\references\tiles'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$src = [System.Drawing.Image]::FromFile($srcPath)
$cols = 3
$rows = 2
$cw = [int]($src.Width / $cols)
$ch = [int]($src.Height / $rows)

$enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$ep = New-Object System.Drawing.Imaging.EncoderParameters(1)
$ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]85)

$i = 0
for ($r = 0; $r -lt $rows; $r++) {
    for ($c = 0; $c -lt $cols; $c++) {
        $i++
        $x = [int]($c * $cw)
        $y = [int]($r * $ch)
        $rect = New-Object System.Drawing.Rectangle($x, $y, $cw, $ch)
        $bmp = New-Object System.Drawing.Bitmap($cw, $ch)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $dst = New-Object System.Drawing.Rectangle(0, 0, $cw, $ch)
        $g.DrawImage($src, $dst, $rect, [System.Drawing.GraphicsUnit]::Pixel)
        $out = Join-Path $outDir ("tile{0}.jpg" -f $i)
        $bmp.Save($out, $enc, $ep)
        $g.Dispose()
        $bmp.Dispose()
        Write-Output ("{0} -> {1} bytes" -f $out, (Get-Item $out).Length)
    }
}
$src.Dispose()
Write-Output 'DONE'
