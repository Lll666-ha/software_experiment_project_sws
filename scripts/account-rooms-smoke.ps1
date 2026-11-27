param([string]$BaseUrl = 'http://localhost:8080/studyroom/api/account-rooms')

$ErrorActionPreference = 'Stop'
$webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$token = (Invoke-RestMethod -Uri "$BaseUrl/token" -WebSession $webSession).csrf
$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
$username = "test_$suffix"
$phone = '139' + $suffix.Substring([Math]::Max(0, $suffix.Length - 8)).PadLeft(8, '0')
$password = 'Test@123456'

function Invoke-Post([string]$Path, [hashtable]$Body) {
    $Body.csrf = $token
    Invoke-RestMethod -Method Post -Uri "$BaseUrl$Path" -WebSession $webSession -Body $Body
}

$rooms = Invoke-RestMethod -Uri "$BaseUrl/rooms?min_capacity=6" -WebSession $webSession
if ($rooms.rooms.Count -lt 1) { throw 'AR-08 失败：没有返回容量不少于 6 的可用房间' }
if ($rooms.rooms | Where-Object { $_.capacity -lt 6 -or $_.listing_status -ne 'LISTED' -or $_.maintenance_status -ne 'NORMAL' }) {
    throw 'AR-08 失败：返回了不满足条件的房间'
}

$code = (Invoke-Post '/register/code' @{ contact = $phone }).demo_code
Invoke-Post '/register' @{ username = $username; contact = $phone; password = $password; password_confirm = $password; code = $code } | Out-Null
$identity = Invoke-RestMethod -Uri "$BaseUrl/session" -WebSession $webSession
if (-not $identity.authenticated -or $identity.role -ne 'USER') { throw 'AR-01 失败：注册后没有普通用户会话' }

try {
    Invoke-RestMethod -Uri "$BaseUrl/admin/rooms" -WebSession $webSession | Out-Null
    throw 'AR-11 失败：普通用户可以访问房管接口'
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 403) { throw }
}

Invoke-Post '/logout' @{} | Out-Null
$token = (Invoke-RestMethod -Uri "$BaseUrl/token" -WebSession $webSession).csrf
Invoke-Post '/login' @{ username = $username; password = $password } | Out-Null
Write-Host "冒烟测试通过；临时账号：$username，手机号：$phone" -ForegroundColor Green

