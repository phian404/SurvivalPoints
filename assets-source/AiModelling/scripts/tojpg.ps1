Add-Type -AssemblyName System.Drawing
$outDir = 'e:\Modding\Blockbench\AiModelling\references\zooms'
$enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$ep = New-Object System.Drawing.Imaging.EncoderParameters(1)
$ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]72)
Get-ChildItem (Join-Path $outDir 'cell*_tight.png') | ForEach-Object {
    $img = [System.Drawing.Image]::FromFile($_.FullName)
    $out = $_.FullName -replace '_tight\.png$', '_tight.jpg'
    $img.Save($out, $enc, $ep)
    $img.Dispose()
    Write-Output ("{0} -> {1} bytes" -f $out, (Get-Item $out).Length)
}
Write-Output 'DONE'
