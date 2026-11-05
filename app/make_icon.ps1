# =====================================================================
# SesameOpen (zhi ma kai men) launcher icon generator
# Rasterizes the mdi/lock padlock vector (Iconify / Material Design Icons)
# via WPF UIElement rendering, composites neon-cyan padlock + dark gradient.
# Outputs: adaptive foreground (432px) + all mipmap densities (sq + round)
# ASCII-only comments to avoid PS5.1 GBK codepage parsing issues.
# =====================================================================
Add-Type -AssemblyName PresentationCore
Add-Type -AssemblyName PresentationFramework
Add-Type -AssemblyName WindowsBase

# ---- padlock vector path (mdi/lock, viewBox 0 0 24 24) ----
$glyphPath = "M12 17a2 2 0 0 0 2-2a2 2 0 0 0-2-2a2 2 0 0 0-2 2a2 2 0 0 0 2 2m6-9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2h1V6a5 5 0 0 1 5-5a5 5 0 0 1 5 5v2zm-6-5a3 3 0 0 0-3 3v2h6V6a3 3 0 0 0-3-3"
$glyphGeom = [System.Windows.Media.Geometry]::Parse($glyphPath)

# ---- colors ----
$cTop  = [System.Windows.Media.Color]::FromArgb(255, 64, 240, 255)   # bright cyan
$cBot  = [System.Windows.Media.Color]::FromArgb(255, 0, 150, 190)    # deep cyan
$cGlow = [System.Windows.Media.Color]::FromArgb(255, 0, 229, 255)    # neon cyan
$bg1   = [System.Windows.Media.Color]::FromArgb(255, 18, 36, 62)     # deep blue
$bg2   = [System.Windows.Media.Color]::FromArgb(255, 11, 12, 14)     # brand dark

# glyph vertical gradient
$grad = [System.Windows.Media.LinearGradientBrush]::new()
$grad.StartPoint = [System.Windows.Point]::new(0, 0)
$grad.EndPoint   = [System.Windows.Point]::new(0, 1)
[void]$grad.GradientStops.Add([System.Windows.Media.GradientStop]::new($cTop, 0.0))
[void]$grad.GradientStops.Add([System.Windows.Media.GradientStop]::new($cBot, 1.0))

# background diagonal gradient
$bgGrad = [System.Windows.Media.LinearGradientBrush]::new()
$bgGrad.StartPoint = [System.Windows.Point]::new(0, 0)
$bgGrad.EndPoint   = [System.Windows.Point]::new(1, 1)
[void]$bgGrad.GradientStops.Add([System.Windows.Media.GradientStop]::new($bg1, 0.0))
[void]$bgGrad.GradientStops.Add([System.Windows.Media.GradientStop]::new($bg2, 1.0))

# ---- render one icon to a BitmapSource ----
function Render-Icon([int]$sizePx, [double]$glyphFrac, [bool]$bg, [bool]$roundMask) {
    # 自然包围盒（4,1,16,21）——几何体保持未变换，用 PushTransform 变换画布（决定性，避免 WPF 惰性求值）
    $b = $glyphGeom.Bounds
    $scale = ($sizePx * $glyphFrac) / $b.Height
    $tx = ($sizePx - $b.Width * $scale) / 2 - $b.X * $scale
    $ty = ($sizePx - $b.Height * $scale) / 2 - $b.Y * $scale
    $penNull = [System.Windows.Media.Pen]$null
    # 画布变换下的描边宽（笔宽会被 scale 放大，除以 scale 保持最终像素厚）
    $glyphPenW = [double]($sizePx * 0.02) / $scale
    $glowPenW  = [double]($sizePx * 0.07) / $scale

    $dv = [System.Windows.Media.DrawingVisual]::new()
    $dc = $dv.RenderOpen()
    if ($bg) {
        $cx = [double]($sizePx / 2)
        if ($roundMask) {
            $dc.DrawEllipse($bgGrad, $penNull, [System.Windows.Point]::new($cx, $cx), $cx, $cx)
        } else {
            $dc.DrawRectangle($bgGrad, $penNull, [System.Windows.Rect]::new(0, 0, $sizePx, $sizePx))
        }
    }
    # 光晕：半透明青色粗描边（分层模拟柔和光）
    $glowBrush = [System.Windows.Media.SolidColorBrush]::new([System.Windows.Media.Color]::FromArgb(80, 0, 229, 255))
    $dc.PushTransform([System.Windows.Media.MatrixTransform]::new([System.Windows.Media.Matrix]::new($scale, 0, 0, $scale, $tx, $ty)))
    $dc.DrawGeometry($penNull, [System.Windows.Media.Pen]::new($glowBrush, $glowPenW), $glyphGeom)
    $dc.DrawGeometry($grad, [System.Windows.Media.Pen]::new($grad, $glyphPenW), $glyphGeom)
    $dc.Pop()
    $dc.Close()

    $rtb = [System.Windows.Media.Imaging.RenderTargetBitmap]::new($sizePx, $sizePx, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $rtb.Render($dv)
    return $rtb
}

function Save-Png($source, [string]$path) {
    $enc = [System.Windows.Media.Imaging.PngBitmapEncoder]::new()
    [void]$enc.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($source))
    $fs = [System.IO.File]::Create($path)
    $enc.Save($fs)
    $fs.Close()
}

$resDir = Join-Path $PSScriptRoot "res"

# ---- 1. adaptive foreground: 432x432 (108dp @4x), glyph centered, transparent ----
$fg = Render-Icon 432 0.56 $false $false
Save-Png $fg "$resDir\drawable\ic_launcher_foreground.png"
Write-Host "foreground: 432x432 written"

# ---- 2. legacy mipmaps (square + round) ----
$densities = @(
    @{ name="mdpi";    size=48 },
    @{ name="hdpi";    size=72 },
    @{ name="xhdpi";   size=96 },
    @{ name="xxhdpi";  size=144 },
    @{ name="xxxhdpi"; size=192 }
)
foreach ($d in $densities) {
    $dir = "$resDir\mipmap-$($d.name)"
    $square = Render-Icon $d.size 0.68 $true $false
    Save-Png $square "$dir\ic_launcher.png"
    $circle = Render-Icon $d.size 0.68 $true $true
    Save-Png $circle "$dir\ic_launcher_round.png"
    Write-Host "mipmap-$($d.name): $($d.size)x$($d.size) written (square+round)"
}
Write-Host "DONE"
