param([Parameter(Mandatory=$true)][string]$Serial,[string]$Adb='adb',[string]$OutputDirectory='output/about-smoke')
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
function ReadUi {
    & $Adb -s $Serial shell uiautomator dump /sdcard/cupbox-about.xml | Out-Null
    [xml]$document=(& $Adb -s $Serial shell cat /sdcard/cupbox-about.xml) -join "`n"
    return $document
}
function FindText([string]$Label,[switch]$Scroll) {
    for($i=0;$i -lt 18;$i++){
        $doc=ReadUi
        $node=$doc.SelectSingleNode("//*[@text='$Label']")
        if($node){return $node}
        if(!$Scroll){break}
        & $Adb -s $Serial shell input swipe 540 1530 540 720 250
    }
    throw "界面缺少：$Label"
}
function Tap([string]$Label,[switch]$Scroll) {
    $node=FindText $Label -Scroll:$Scroll
    $bounds=[regex]::Matches($node.bounds,'\d+') | ForEach-Object {[int]$_.Value}
    & $Adb -s $Serial shell input tap ([int](($bounds[0]+$bounds[2])/2)) ([int](($bounds[1]+$bounds[3])/2))
}
function Shot([string]$Name) {
    & $Adb -s $Serial shell screencap -p /sdcard/cupbox-about.png
    & $Adb -s $Serial pull /sdcard/cupbox-about.png "$OutputDirectory/$Name.png" | Out-Null
}
& $Adb -s $Serial shell am start -n cn.cupbox.qbot/.MainActivity | Out-Null
Tap '设置'
Tap '关于 Cupbox · 属性与更新' -Scroll
FindText '关于 Cupbox' | Out-Null
Shot 'about'
Tap '检查更新'
$deadline=(Get-Date).AddSeconds(65)
$finished=$false
while((Get-Date) -lt $deadline){
    $doc=ReadUi
    $result=$doc.SelectSingleNode("//*[@text='仓库尚未发布正式版本' or @text='已是仓库最新正式版本' or @text='当前安装版本领先于仓库' or starts-with(@text,'发现新版本')]")
    if($result){Write-Output "真实更新检查：$($result.text)";$finished=$true;break}
    if($doc.SelectSingleNode("//*[@text='检查未完成']")){throw $doc.OuterXml}
    Start-Sleep -Seconds 2
}
if(!$finished){throw '检查更新未在预期时间内结束'}
Shot 'update-result'
FindText '应用属性' -Scroll | Out-Null
& $Adb -s $Serial shell input swipe 540 1500 540 900 250
Shot 'properties'
FindText '638500178' -Scroll | Out-Null
Shot 'contact'
Tap '复制 QQ 群号' -Scroll
Tap '查看许可证与组件声明' -Scroll
FindText '许可证与组件声明' | Out-Null
Shot 'licenses'
Tap 'Cupbox 许可与素材声明'
FindText 'Cupbox 许可与素材声明' | Out-Null
Tap '关闭'
Write-Output '关于页、应用属性、QQ群、真实更新查询与许可证界面检查通过。'
