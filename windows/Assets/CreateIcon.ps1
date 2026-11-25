$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$dir = $PSScriptRoot
$icon = Join-Path $dir 'Messenger.ico'
$sizes = @(16,24,32,48,64,128,256)
$frames = @()
foreach ($n in $sizes) {
    $bmp = [System.Drawing.Bitmap]::new($n,$n,[System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.ScaleTransform($n/108.0,$n/108.0)
    $r = [System.Drawing.RectangleF]::new(3,3,102,102)
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $path.AddArc(3,3,40,40,180,90); $path.AddArc(65,3,40,40,270,90); $path.AddArc(65,65,40,40,0,90); $path.AddArc(3,65,40,40,90,90); $path.CloseFigure()
    $bg = [System.Drawing.Drawing2D.LinearGradientBrush]::new($r,[System.Drawing.Color]::FromArgb(36,211,173),[System.Drawing.Color]::FromArgb(0,61,60),45)
    $g.FillPath($bg,$path)
    $bubble = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $bubble.StartFigure(); $bubble.AddBezier(54,24,34,24,22,37,22,53)
    $bubble.AddBezier(22,53,22,61,25,68,30,73)
    $bubble.AddLine(30,73,26,84);$bubble.AddLine(26,84,42,78)
    $bubble.AddBezier(42,78,46,80,50,81,55,81)
    $bubble.AddBezier(55,81,74,81,87,68,87,51)
    $bubble.AddBezier(87,51,87,35,73,24,54,24);$bubble.CloseFigure()
    $g.TranslateTransform(0,4);$shadow=[System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(90,0,40,36));$g.FillPath($shadow,$bubble);$g.TranslateTransform(0,-4)
    $fg=[System.Drawing.Drawing2D.LinearGradientBrush]::new([System.Drawing.RectangleF]::new(22,24,65,60),[System.Drawing.Color]::FromArgb(242,255,252),[System.Drawing.Color]::FromArgb(87,205,176),55)
    $g.FillPath($fg,$bubble)
    $dots=[System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(6,123,105))
    foreach($x in @(39,54,69)){$g.FillEllipse($dots,($x-4.5),47,9,9)}
    $g.Dispose();$path.Dispose();$bubble.Dispose();$bg.Dispose();$shadow.Dispose();$fg.Dispose();$dots.Dispose()
    $stream=[System.IO.MemoryStream]::new();$bmp.Save($stream,[System.Drawing.Imaging.ImageFormat]::Png);$frames+=,@{size=$n;bytes=$stream.ToArray()};$stream.Dispose();$bmp.Dispose()
}
$fs=[System.IO.File]::Create($icon);$bw=[System.IO.BinaryWriter]::new($fs)
try {
    $bw.Write([uint16]0);$bw.Write([uint16]1);$bw.Write([uint16]$frames.Count)
    $offset=6+16*$frames.Count
    foreach($frame in $frames){
        $dim=if($frame.size -eq 256){0}else{$frame.size}
        $bw.Write([byte]$dim);$bw.Write([byte]$dim);$bw.Write([byte]0);$bw.Write([byte]0);$bw.Write([uint16]1);$bw.Write([uint16]32);$bw.Write([uint32]$frame.bytes.Length);$bw.Write([uint32]$offset)
        $offset += $frame.bytes.Length
    }
    foreach($frame in $frames){$bw.Write([byte[]]$frame.bytes)}
}finally{$bw.Dispose();$fs.Dispose()}
