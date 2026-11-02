param(
    [string]$Bbmodel = 'e:\Modding\Blockbench\AiModelling\barbed_wire_fence.bbmodel',
    [string]$OutDir  = 'e:\Modding\Blockbench\AiModelling\export\textures\block'
)
$j = Get-Content $Bbmodel -Raw | ConvertFrom-Json
foreach ($t in $j.textures) {
    if ($t.source -and $t.source.StartsWith('data:image/png;base64,')) {
        $b64 = $t.source.Substring('data:image/png;base64,'.Length)
        $bytes = [System.Convert]::FromBase64String($b64)
        $out = Join-Path $OutDir ($t.name + '.png')
        [System.IO.File]::WriteAllBytes($out, $bytes)
        Write-Output ("{0} -> {1} bytes" -f $out, $bytes.Length)
    }
}
Write-Output 'DONE'
