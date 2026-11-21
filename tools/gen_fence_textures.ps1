# SurvivalPoints — генератор текстур оград 16x16 (strict_16, vanilla-plus).
# Палитра по спеке Fences v1.1.7-final §6.3: дерево #A88F5F, металл #4A444A, ржавчина #79513A.
#
# ВАЖНО: раскладки UV моделей сэмплят конкретные окна текстуры, поэтому
# каждый PNG строится по «карте регионов» — значимые пиксели кладутся ровно
# туда, откуда их читает соответствующая грань. Окна указаны в комментариях.
#
# Пишет PNG в src/main/resources/assets/survivalpoints/textures/block/
# и превью x16 в tools/previews/. Детерминирован.

Add-Type -AssemblyName System.Drawing

$root    = Split-Path -Parent $PSScriptRoot
$outDir  = Join-Path $root 'src\main\resources\assets\survivalpoints\textures\block'
$prevDir = Join-Path $root 'tools\previews'
New-Item -ItemType Directory -Force -Path $outDir   | Out-Null
New-Item -ItemType Directory -Force -Path $prevDir | Out-Null

$S = 16
$SCALE = 16

function HexToColor([string]$hex) {
    $h = $hex.TrimStart('#')
    return [System.Drawing.Color]::FromArgb(255,
        [Convert]::ToInt32($h.Substring(0,2),16),
        [Convert]::ToInt32($h.Substring(2,2),16),
        [Convert]::ToInt32($h.Substring(4,2),16))
}

$CLEAR = [System.Drawing.Color]::FromArgb(0,0,0,0)

function Hash2([int]$x, [int]$y, [int]$s) {
    $v = ($x * 73856093) -bxor ($y * 19349663) -bxor ($s * 83492791)
    return ($v -band 0x7fffffff)
}

function New-Bitmap([string]$name, [scriptblock]$fill) {
    $bmp = New-Object System.Drawing.Bitmap($S, $S, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $S; $y++) {
        for ($x = 0; $x -lt $S; $x++) {
            $bmp.SetPixel($x, $y, (& $fill $x $y))
        }
    }
    $bmp.Save((Join-Path $outDir $name), [System.Drawing.Imaging.ImageFormat]::Png)

    $big = New-Object System.Drawing.Bitmap(($S*$SCALE), ($S*$SCALE), [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($big)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $g.DrawImage($bmp, 0, 0, ($S*$SCALE), ($S*$SCALE))
    $g.Dispose()
    $big.Save((Join-Path $prevDir ('fence_' + $name)), [System.Drawing.Imaging.ImageFormat]::Png)
    $big.Dispose()
    $bmp.Dispose()
    Write-Host ("wrote " + $name)
}

# ============================================================== палитры
$wDeep = HexToColor '#6A5634'
$wDark = HexToColor '#8A7346'
$wBase = HexToColor '#A88F5F'
$wLite = HexToColor '#B9A272'
$wHigh = HexToColor '#C8B182'

$mDeep = HexToColor '#332F35'
$mDark = HexToColor '#3E3941'
$mBase = HexToColor '#4A444A'
$mLite = HexToColor '#585259'
$mHigh = HexToColor '#65606A'

$rDark = HexToColor '#5E3E2C'
$rBase = HexToColor '#79513A'
$rLite = HexToColor '#8E6248'

# =============================================================== 1. plank
# Регионы:
#   u 0..15, v 0..5   — длинная грань доски (UV [0,0,16,5]); волокно вдоль U.
#   u 0..2,  v 6..11  — торец доски, end-grain (UV [0,6,2,11]).
#   остальное         — база (страховка от случайного сэмпла).
New-Bitmap 'plank.png' {
    param($x, $y)

    # --- торец (end grain): вертикальные волокна-кольца 2 px шириной
    if ($y -ge 6 -and $y -le 11 -and $x -le 2) {
        switch ($x) {
            0 { return $wDark }
            1 { return $wLite }
            2 { return $wDark }
        }
    }

    # --- длинная грань
    if ($y -le 5) {
        $seg = [math]::Floor($x / 4)      # сегменты волокна по длине
        switch ($y) {
            0 { return $wLite }           # верхняя кромка: блик
            1 {
                if ($seg % 3 -eq 1) { return $wBase }   # разрыв волокна
                return $wDark
            }
            2 {
                if ($x -eq 11) { return $wDeep }        # сучок
                if ($seg % 4 -eq 3) { return $wLite }
                return $wBase
            }
            3 {
                if ($seg % 5 -eq 2) { return $wBase }
                return $wLite
            }
            4 { return $wBase }
            5 { return $wDark }           # нижняя кромка: тень
        }
    }

    return $wBase
}

# =========================================================== 2. post_wood
# Регион: u 0..7, v 0..15 (UV [0,0,8,16]). Вертикальное волокно, 2 жёлоба.
New-Bitmap 'post_wood.png' {
    param($x, $y)
    $h = Hash2 $x $y 2
    if ($x -gt 7) { return $wBase }

    # вертикальные жёлоба с разрывами
    if (($x -eq 2 -or $x -eq 5) -and ($y % 7 -ne 6)) { return $wDark }
    # светлая жила
    if ($x -eq 7 -and ($y % 9 -lt 4)) { return $wLite }
    # редкие тёмные точки
    if ($h % 61 -eq 0) { return $wDeep }
    # ровная база с лёгкой неоднородностью по колонкам
    switch ($x) {
        0 { return $wDark }
        3 { return $wLite }
        default { return $wBase }
    }
}

# ======================================================= 3. post_wood_top
# Регион: u 0..7, v 0..7 (UV [0,0,8,8]). Годовые кольца от центра 3.5/3.5.
$topCore = HexToColor '#6A5634'
$topRing1 = HexToColor '#8A7346'
$topRing2 = HexToColor '#A88F5F'
$topRing3 = HexToColor '#B9A272'

New-Bitmap 'post_wood_top.png' {
    param($x, $y)
    if ($x -gt 7 -or $y -gt 7) { return $topRing2 }
    $dx = $x - 3.5
    $dy = $y - 3.5
    $r = [math]::Sqrt($dx*$dx + $dy*$dy)
    if ($r -lt 1.1) { return $topCore }
    switch ([math]::Floor($r) % 3) {
        0 { return $topRing1 }
        1 { return $topRing2 }
        default { return $topRing3 }
    }
}

# ================================================================ 4. wire
# Регионы:
#   u 0..15, v 0..1 — нить (UV [0,0,16,2]); v0 блик, v1 тень.
#   шипы на x = 3 и x = 11 (шаг 8 → тайлинг по U бесшовен).
#   остальное — прозрачно (cutout).
New-Bitmap 'wire.png' {
    param($x, $y)
    if ($y -gt 1) { return $CLEAR }
    $h = Hash2 $x $y 4

    foreach ($cx in @(3, 11)) {
        if ($x -eq $cx) { return $mHigh }                     # ось шипа
        if ($x -eq ($cx - 1) -or ($x -eq ($cx + 1))) {        # лучи шипа
            if ($h % 4 -ne 0) { return $mLite }
        }
    }

    if ($h % 11 -eq 0) { return $rBase }    # ржавое звено
    if ($h % 17 -eq 0) { return $rDark }
    if ($y -eq 0) { return $mHigh }
    return $mDark
}

# ================================================================ 5. mesh
# Весь лист: рабица, шаг 8 px по обеим осям → бесшовно и по U, и по V.
New-Bitmap 'mesh.png' {
    param($x, $y)
    $d1 = ($x + $y) % 8
    $d2 = (($x - $y) + 128) % 8
    if (-not ($d1 -eq 0 -or $d2 -eq 0)) { return $CLEAR }
    $h = Hash2 $x $y 5
    if ($d1 -eq 0 -and $d2 -eq 0) { return $mHigh }   # перекрестье
    if ($h % 11 -eq 0) { return $rBase }
    if ($h % 19 -eq 0) { return $rDark }
    if ($d1 -eq 0) { return $mBase }
    return $mDark
}

# =============================================================== 6. sheet
# Весь лист: вертикальный гофр шаг 4 px + ржавые подтёки сверху вниз.
New-Bitmap 'sheet.png' {
    param($x, $y)
    $h = Hash2 $x $y 6
    # подтёк: узкая колонка, обрывается по высоте
    if (($x -eq 6 -or $x -eq 14) -and ($y -ge ($h % 10))) {
        if ($h % 2 -eq 0) { return $rBase } else { return $rDark }
    }
    if ($x -eq 2 -and $y -lt ($h % 4)) { return $rLite }
    switch ($x % 4) {
        0 { return $mHigh }
        1 { return $mBase }
        2 { return $mDark }
        default { return $mDeep }
    }
}

# =============================================================== 7. metal
# Регионы:
#   u 0..15, v 0..1 — длинная грань рейки (UV [0,0,16,2]).
#   u 0..3,  v 0..3 — срез для up/down (UV [0,0,4,4]).
New-Bitmap 'metal.png' {
    param($x, $y)
    $h = Hash2 $x $y 7
    if ($h % 43 -eq 0) { return $rDark }    # редкая ржавая точка
    switch ($y) {
        0 { return $mBase }
        1 { return $mDeep }
        default {
            if ($x -le 3 -and $y -le 3) { return $mLite }
            return $mBase
        }
    }
}

Write-Host 'done'