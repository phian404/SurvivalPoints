# tools/report_texture_palette.ps1
# Reports, per fence texture: distinct colour count, luminance range and how
# far the actual pixels sit from the palette mandated by spec §6.3
# (wood #A88F5F, metal #4A444A, rust #79513A).
# Vanilla Minecraft textures use a deliberate hue-shifted ramp with edge
# shading; a texture whose luminance span is tiny reads as a flat plastic fill.

Add-Type -AssemblyName System.Drawing

$spec = @{
    wood = @(0xA8, 0x8F, 0x5F)
    metal = @(0x4A, 0x44, 0x4A)
    rust = @(0x79, 0x51, 0x3A)
}

$files = @(
    @{ File = 'plank.png'; Family = 'wood' },
    @{ File = 'wire.png'; Family = 'metal' },
    @{ File = 'mesh.png'; Family = 'metal' },
    @{ File = 'sheet.png'; Family = 'metal' },
    @{ File = 'metal.png'; Family = 'metal' }
)

$dir = 'src/main/resources/assets/survivalpoints/textures/block'

foreach ($f in $files) {
    $path = Join-Path $dir $f.File
    if (-not (Test-Path $path)) { Write-Host "MISSING $($f.File)"; continue }
    $bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $path))
    $colours = @{}
    $lums = @()
    $opaque = 0
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            $c = $bmp.GetPixel($x, $y)
            if ($c.A -le 8) { continue }
            $opaque++
            $key = '{0:X2}{1:X2}{2:X2}' -f $c.R, $c.G, $c.B
            $colours[$key] = $true
            $lums += (0.299 * $c.R + 0.587 * $c.G + 0.114 * $c.B)
        }
    }
    $bmp.Dispose()

    if ($opaque -eq 0) { Write-Host "$($f.File): fully transparent"; continue }

    $min = ($lums | Measure-Object -Minimum).Minimum
    $max = ($lums | Measure-Object -Maximum).Maximum
    $span = [math]::Round($max - $min, 1)

    $target = $spec[$f.Family]
    $targetKey = '{0:X2}{1:X2}{2:X2}' -f $target[0], $target[1], $target[2]
    $hasSpec = $colours.ContainsKey($targetKey)

    # nearest actual colour to the mandated palette entry
    $best = 999.0; $bestKey = ''
    foreach ($k in $colours.Keys) {
        $r = [Convert]::ToInt32($k.Substring(0, 2), 16)
        $g = [Convert]::ToInt32($k.Substring(2, 2), 16)
        $b = [Convert]::ToInt32($k.Substring(4, 2), 16)
        $d = [math]::Sqrt([math]::Pow($r - $target[0], 2) + [math]::Pow($g - $target[1], 2) + [math]::Pow($b - $target[2], 2))
        if ($d -lt $best) { $best = $d; $bestKey = $k }
    }

    Write-Host ''
    Write-Host "$($f.File)  [$($f.Family)]"
    Write-Host ("  opaque px      : {0}" -f $opaque)
    Write-Host ("  distinct colours: {0}" -f $colours.Count)
    Write-Host ("  luminance span : {0}  ({1} .. {2})" -f $span, [math]::Round($min, 1), [math]::Round($max, 1))
    Write-Host ("  spec colour    : #{0} present={1}" -f $targetKey, $hasSpec)
    Write-Host ("  nearest actual : #{0}  distance={1}" -f $bestKey, [math]::Round($best, 1))
}
