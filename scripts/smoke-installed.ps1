param([Parameter(Mandatory=$true)][string]$Serial,[string]$Adb='adb',[string]$OutputDirectory='output/release-smoke')
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
function Read-AppUi {
    & $Adb -s $Serial shell uiautomator dump /sdcard/cupbox-ui.xml | Out-Null
    [xml]$document=(& $Adb -s $Serial shell cat /sdcard/cupbox-ui.xml) -join "`n"
    return $document
}
function Tap-Text([string]$Label) {
    $document=Read-AppUi
    $node=$document.SelectSingleNode("//*[@text='$Label']")
    if($null -eq $node){throw "界面缺少：$Label"}
    $numbers=[regex]::Matches($node.bounds,'\d+') | ForEach-Object { [int]$_.Value }
    $x=[int](($numbers[0]+$numbers[2])/2)
    $y=[int](($numbers[1]+$numbers[3])/2)
    & $Adb -s $Serial shell input tap $x $y
}
$tabs=@('首页','人格','设置')
$titles=@('今天，也有回应。','选一个聊天搭子','设置')
& $Adb -s $Serial shell am start -n cn.cupbox.qbot/.MainActivity | Out-Null
for($index=0;$index -lt $tabs.Count;$index++) {
    Tap-Text $tabs[$index]
    $document=Read-AppUi
    if($null -eq $document.SelectSingleNode("//*[@text='$($titles[$index])']")){throw "页面标题验证失败：$($tabs[$index])"}
    & $Adb -s $Serial shell screencap -p /sdcard/cupbox-screen.png
    & $Adb -s $Serial pull /sdcard/cupbox-screen.png "$OutputDirectory/page-$index-light.png" | Out-Null
}
Tap-Text '首页'
Tap-Text '深色'
$document=Read-AppUi
if($null -eq $document.SelectSingleNode("//*[@text='浅色']")){throw '深色主题切换失败'}
& $Adb -s $Serial shell screencap -p /sdcard/cupbox-screen.png
& $Adb -s $Serial pull /sdcard/cupbox-screen.png "$OutputDirectory/overview-dark.png" | Out-Null
Tap-Text '浅色'
Write-Output '安装包界面冒烟通过：三个主页面和深浅主题。'
