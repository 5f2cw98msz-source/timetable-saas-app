<#
    ChalklineDeploy.psm1: the engine behind the Chalkline Windows deployer.

    Everything that changes the computer lives here, with no user interface,
    so it can be tested on its own and driven from the GUI, from a script, or
    by an IT department's own tooling.

    Written for Windows PowerShell 5.1, because that is what every Windows 10
    and 11 machine has. Nothing here needs PowerShell 7.

    What a deployment produces, under one folder (default C:\ProgramData\Chalkline):

        app\chalkline.jar              the application
        config\application.properties  its settings; readable by the service only
        data\                          the database (when using the built-in one)
        logs\                          application and deployment logs
        backups\                       nightly copies of data\
        tools\backup-chalkline.ps1     what the nightly backup task runs
        deploy-settings.json           the form's answers, minus passwords
        Open Chalkline.url             a shortcut to hand out to lecturers

    And two scheduled tasks, "Chalkline" (starts with Windows) and
    "Chalkline Backup" (nightly), plus one inbound firewall rule.

    Scheduled tasks rather than a Windows service because a Java application
    cannot be a service on its own; it needs a wrapper such as WinSW or NSSM,
    which would mean downloading and trusting another executable. Task
    Scheduler is built into every Windows machine and does the same job.
#>

Set-StrictMode -Version Latest

# Convention for collections, used throughout: functions let their results
# unroll, and every caller that wants a list wraps the call in @( ).
# Do not "protect" a return with the comma operator: a caller who also
# writes @( ) then gets an array nested inside an array, which is how
# "System.Object[]" once appeared in the form and a firewall warning could
# never fire. An extra @( ) around an unrolled result is harmless.

$script:TaskName       = 'Chalkline'
$script:BackupTaskName = 'Chalkline Backup'
$script:FirewallName   = 'Chalkline-Inbound'
$script:MinJavaMajor   = 21

# Well-known SIDs. Names like "Administrators" are translated on non-English
# Windows ("Administrateurs", "Administradores"), but SIDs never are.
$script:Sid = @{
    System         = 'S-1-5-18'
    LocalService   = 'S-1-5-19'
    Administrators = 'S-1-5-32-544'
}

# =============================================================================
#  Configuration: defaults, validation, and turning it into a properties file
#  (pure functions, no side effects, safe to test anywhere)
# =============================================================================

function Join-ChalklinePath {
    <#
        Joins Windows paths as plain text. Join-Path insists the drive already
        exists, so it throws for an install folder on a drive that is not there,
        which should be a validation message rather than a crash. It also cannot
        build Windows paths anywhere else for testing. This tool only ever
        deploys to Windows, so backslashes are always right.
    #>
    param([string]$Base, [string]$Child)
    return ($Base.TrimEnd('\', '/') + '\' + $Child.TrimStart('\', '/'))
}

function New-ChalklineConfig {
    <# The settings a deployment needs, with defaults for everything that has a sensible one. #>
    [CmdletBinding()]
    param()

    $programData = if ($env:ProgramData) { $env:ProgramData } else { 'C:\ProgramData' }

    return @{
        InstallRoot         = (Join-ChalklinePath $programData 'Chalkline')

        InstitutionName     = ''
        AdminName           = ''
        AdminEmail          = ''
        AdminPassword       = ''
        SupportEmail        = ''

        ServerAddress       = ''
        Port                = 8080

        DatabaseType        = 'H2'          # H2 (built in) or PostgreSQL
        DbHost              = ''
        DbPort              = 5432
        DbName              = 'chalkline'
        DbUser              = ''
        DbPassword          = ''

        IncludePremium      = $true         # a school hosting its own copy gets everything
        AllowSignUp         = $false        # one institution, so nobody else may create one
        SeedSampleCatalogue = $false        # no fake courses on a real system

        OpenFirewall        = $true
        FirewallProfiles    = @('Domain', 'Private')
        LocalSubnetOnly     = $false

        StartWithWindows    = $true
        NightlyBackup       = $true
        BackupTime          = '02:00'
        BackupKeep          = 14

        JarSource           = 'Build'       # Build from source, or File
        JarPath             = ''
        SourceDir           = ''
        JavaPath            = ''
        MaxHeapMb           = 768
    }
}

function Merge-ChalklineConfig {
    <# Overlays supplied values on the defaults, so a partial config is always complete. #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Overrides)

    $config = New-ChalklineConfig
    foreach ($key in @($Overrides.Keys)) {
        $config[$key] = $Overrides[$key]
    }
    return $config
}

function Test-ChalklineEmail {
    param([string]$Value)
    return ($Value -match '^[^@\s]+@[^@\s]+\.[^@\s]{2,}$')
}

function Test-ChalklineConfig {
    <#
        Returns a list of problems, in plain English, or an empty list.
        -FreshInstall requires the administrator password, which is only used
        to create the institution on the very first start.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [switch]$FreshInstall
    )

    $problems = New-Object System.Collections.Generic.List[string]

    if ([string]::IsNullOrWhiteSpace($Config.InstitutionName)) {
        $problems.Add('Enter the institution name.')
    }
    if ([string]::IsNullOrWhiteSpace($Config.AdminEmail)) {
        $problems.Add('Enter the administrator''s email address.')
    } elseif (-not (Test-ChalklineEmail $Config.AdminEmail)) {
        $problems.Add('The administrator email does not look like an email address.')
    }
    if (-not [string]::IsNullOrWhiteSpace($Config.SupportEmail) -and -not (Test-ChalklineEmail $Config.SupportEmail)) {
        $problems.Add('The support email does not look like an email address.')
    }
    if ($FreshInstall) {
        if ([string]::IsNullOrEmpty($Config.AdminPassword)) {
            $problems.Add('Enter a password for the administrator. They will be asked to change it at first sign-in.')
        } elseif ($Config.AdminPassword.Length -lt 8) {
            $problems.Add('The administrator password must be at least 8 characters.')
        }
    }

    if ([string]::IsNullOrWhiteSpace($Config.ServerAddress)) {
        $problems.Add('Choose the address lecturers will use to reach this computer.')
    } elseif ($Config.ServerAddress -match '^(localhost|127\.|::1$)') {
        # The single most likely way to deploy something nobody else can use.
        $problems.Add('The server address cannot be localhost: lecturers on other computers could not reach it. Use this computer''s name or network address.')
    } elseif ($Config.ServerAddress -notmatch '^[A-Za-z0-9.\-]+$') {
        $problems.Add('The server address should be a computer name or IP address, with no http:// or slashes.')
    }

    $port = 0
    if (-not [int]::TryParse([string]$Config.Port, [ref]$port) -or $port -lt 1024 -or $port -gt 65535) {
        $problems.Add('The port must be a number between 1024 and 65535.')
    }

    if ($Config.DatabaseType -eq 'PostgreSQL') {
        if ([string]::IsNullOrWhiteSpace($Config.DbHost)) { $problems.Add('Enter the PostgreSQL server name.') }
        if ([string]::IsNullOrWhiteSpace($Config.DbName)) { $problems.Add('Enter the PostgreSQL database name.') }
        if ([string]::IsNullOrWhiteSpace($Config.DbUser)) { $problems.Add('Enter the PostgreSQL user name.') }
        $dbPort = 0
        if (-not [int]::TryParse([string]$Config.DbPort, [ref]$dbPort) -or $dbPort -lt 1 -or $dbPort -gt 65535) {
            $problems.Add('The PostgreSQL port must be a number between 1 and 65535.')
        }
    } elseif ($Config.DatabaseType -ne 'H2') {
        $problems.Add('Choose a database: the built-in one, or PostgreSQL.')
    }

    if ($Config.NightlyBackup -and $Config.DatabaseType -eq 'H2') {
        if ($Config.BackupTime -notmatch '^([01]\d|2[0-3]):[0-5]\d$') {
            $problems.Add('The backup time must look like 02:00 (24-hour clock).')
        }
        $keep = 0
        if (-not [int]::TryParse([string]$Config.BackupKeep, [ref]$keep) -or $keep -lt 1 -or $keep -gt 365) {
            $problems.Add('Keep between 1 and 365 backups.')
        }
    }

    if ($Config.JarSource -eq 'File') {
        if ([string]::IsNullOrWhiteSpace($Config.JarPath)) {
            $problems.Add('Choose the chalkline.jar file to install.')
        }
    } elseif ($Config.JarSource -ne 'Build') {
        $problems.Add('Choose whether to build Chalkline from source or install an existing file.')
    }

    if ([string]::IsNullOrWhiteSpace($Config.InstallRoot)) {
        $problems.Add('Choose an install folder.')
    } elseif ($Config.InstallRoot -match '^\\\\') {
        $problems.Add('Install to a folder on this computer, not a network share: the database must be on a local disk.')
    } elseif ($Config.InstallRoot -notmatch '^[A-Za-z]:\\') {
        $problems.Add('The install folder must be a full path, such as C:\ProgramData\Chalkline.')
    } elseif ($env:OS -eq 'Windows_NT' -and -not (Test-Path -LiteralPath ($Config.InstallRoot.Substring(0, 3)))) {
        $problems.Add("There is no $($Config.InstallRoot.Substring(0, 2)) drive on this computer.")
    }

    return $problems
}

function Get-ChalklinePaths {
    <# The folder layout under the install root, in one place so nothing hard-codes it. #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Root)

    $j = { param($a, $b) Join-ChalklinePath $a $b }
    return [ordered]@{
        Root       = $Root
        App        = & $j $Root 'app'
        Jar        = & $j (& $j $Root 'app') 'chalkline.jar'
        Config     = & $j $Root 'config'
        Properties = & $j (& $j $Root 'config') 'application.properties'
        Data       = & $j $Root 'data'
        Logs       = & $j $Root 'logs'
        AppLog     = & $j (& $j $Root 'logs') 'chalkline.log'
        DeployLog  = & $j (& $j $Root 'logs') 'deploy.log'
        Backups    = & $j $Root 'backups'
        Tools      = & $j $Root 'tools'
        BackupPs1  = & $j (& $j $Root 'tools') 'backup-chalkline.ps1'
        Settings   = & $j $Root 'deploy-settings.json'
        Shortcut   = & $j $Root 'Open Chalkline.url'
    }
}

function Get-ChalklinePublicUrl {
    <# The address lecturers type. Share links and calendar feeds are built from this. #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Config)

    $address = ([string]$Config.ServerAddress).Trim()
    if ([int]$Config.Port -eq 80) { return "http://$address" }
    return "http://${address}:$($Config.Port)"
}

function ConvertTo-JavaPath {
    <# C:\a\b -> C:/a/b. Java accepts forward slashes on Windows and they need no escaping. #>
    param([string]$Path)
    return ($Path -replace '\\', '/')
}

function Get-ChalklineJdbcUrl {
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Config)

    if ($Config.DatabaseType -eq 'PostgreSQL') {
        return "jdbc:postgresql://$($Config.DbHost):$($Config.DbPort)/$($Config.DbName)"
    }
    $paths = Get-ChalklinePaths -Root $Config.InstallRoot
    return 'jdbc:h2:file:' + (ConvertTo-JavaPath (Join-ChalklinePath $paths.Data 'chalkline-db'))
}

function ConvertTo-PropertiesValue {
    <#
        Escapes a value for a Java .properties file.

        Spring Boot reads .properties as ISO-8859-1, not UTF-8. Tested: a raw
        UTF-8 e-acute comes out as two garbage characters. So every non-ASCII character is written as
        a \uXXXX escape, which Java decodes correctly whatever the file's
        encoding, and the file itself is written as plain ASCII. That also
        rules out the byte-order mark Windows PowerShell 5.1 adds to UTF-8
        files, which would otherwise corrupt the first key in the file.

        Deliberately an if/elseif chain, not a switch: "continue" inside a
        PowerShell switch only leaves the switch, so a switch here falls
        through and appends a backslash twice.
    #>
    param([AllowEmptyString()][AllowNull()][string]$Value)

    if ([string]::IsNullOrEmpty($Value)) { return '' }

    $sb = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $Value.Length; $i++) {
        $c = $Value[$i]
        $code = [int]$c

        if ($c -eq [char]'\')          { [void]$sb.Append('\\') }
        elseif ($code -eq 9)           { [void]$sb.Append('\t') }
        elseif ($code -eq 10)          { [void]$sb.Append('\n') }
        elseif ($code -eq 13)          { [void]$sb.Append('\r') }
        elseif ($code -eq 12)          { [void]$sb.Append('\f') }
        elseif ($i -eq 0 -and $code -eq 32) {
            # Java strips leading whitespace from a value unless it is escaped.
            [void]$sb.Append('\ ')
        }
        elseif ($code -lt 32 -or $code -gt 126) {
            [void]$sb.Append(('\u{0:x4}' -f $code))
        }
        else { [void]$sb.Append($c) }
    }
    return $sb.ToString()
}

function Get-ChalklineSettingList {
    <#
        Every setting the deployment writes, in order, as Key / Value / Secret /
        Comment records. The single place that maps the form onto Chalkline's
        configuration keys.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [switch]$OmitAdminPassword
    )

    $paths = Get-ChalklinePaths -Root $Config.InstallRoot
    $list = New-Object System.Collections.Generic.List[object]
    $add = {
        param([string]$Key, $Value, [bool]$Secret = $false, [string]$Comment = '')
        $list.Add([pscustomobject]@{ Key = $Key; Value = [string]$Value; Secret = $Secret; Comment = $Comment })
    }

    $supportEmail = if ([string]::IsNullOrWhiteSpace($Config.SupportEmail)) { $Config.AdminEmail } else { $Config.SupportEmail }

    & $add 'server.port' $Config.Port $false 'Network'
    & $add 'app.public-url' (Get-ChalklinePublicUrl -Config $Config) $false `
        'What lecturers type. Share links and calendar feeds are built from it, so it must not be localhost.'
    & $add 'server.servlet.session.cookie.secure' 'false' $false `
        'Plain http:// on the school network. A secure cookie would be dropped by the browser and nobody could sign in. Set true if you put HTTPS in front.'
    & $add 'app.security.allow-sign-up' ($(if ($Config.AllowSignUp) { 'true' } else { 'false' })) $false `
        'false = this copy serves one institution and nobody can create another.'
    & $add 'app.support-email' $supportEmail $false 'Shown to staff who need help'

    & $add 'spring.datasource.url' (Get-ChalklineJdbcUrl -Config $Config) $false 'Database'
    if ($Config.DatabaseType -eq 'PostgreSQL') {
        & $add 'spring.datasource.username' $Config.DbUser $false
        & $add 'spring.datasource.password' $Config.DbPassword $true
    } else {
        & $add 'spring.datasource.username' 'sa' $false
        & $add 'spring.datasource.password' '' $false
    }

    & $add 'app.setup.organisation-name' $Config.InstitutionName $false `
        'First-run setup. Used once, only when the database is empty; ignored after that.'
    & $add 'app.setup.admin-name' $(if ([string]::IsNullOrWhiteSpace($Config.AdminName)) { 'Administrator' } else { $Config.AdminName }) $false
    & $add 'app.setup.admin-email' $Config.AdminEmail $false
    if (-not $OmitAdminPassword) {
        & $add 'app.setup.admin-password' $Config.AdminPassword $true `
            'Removed from this file automatically once the institution has been created.'
    }
    & $add 'app.setup.seed-sample-catalogue' $(if ($Config.SeedSampleCatalogue) { 'true' } else { 'false' }) $false
    & $add 'app.setup.plan' $(if ($Config.IncludePremium) { 'PREMIUM' } else { 'FREE' }) $false `
        'PREMIUM = every feature, no payment involved. Right for an institution hosting its own copy.'
    & $add 'app.demo.email' '' $false 'Demo data is never created on a deployed system'

    & $add 'logging.file.name' (ConvertTo-JavaPath $paths.AppLog) $false 'Logs, rotated so they cannot fill the disk'
    & $add 'logging.logback.rollingpolicy.max-file-size' '10MB' $false
    & $add 'logging.logback.rollingpolicy.max-history' '14' $false

    return $list
}

function ConvertTo-ChalklineProperties {
    <#
        Renders the settings as the text of application.properties.
        -MaskSecrets is for previews and logs: passwords never appear on screen.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [switch]$MaskSecrets,
        [switch]$OmitAdminPassword
    )

    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('# Chalkline settings, written by the Chalkline Windows deployer.').Append("`n")
    [void]$sb.Append('# Re-run the deployer to change these rather than editing by hand:').Append("`n")
    [void]$sb.Append('# it validates them, and keeps this file readable by the service only.').Append("`n")
    [void]$sb.Append('#').Append("`n")
    [void]$sb.Append('# Generated ' + (Get-Date -Format 'yyyy-MM-dd HH:mm')).Append("`n")

    foreach ($s in @(Get-ChalklineSettingList -Config $Config -OmitAdminPassword:$OmitAdminPassword)) {
        if ($s.Comment) {
            [void]$sb.Append('').Append("`n")
            [void]$sb.Append('# ' + $s.Comment).Append("`n")
        }
        $value = if ($MaskSecrets -and $s.Secret -and $s.Value) { '********' } else { ConvertTo-PropertiesValue $s.Value }
        [void]$sb.Append($s.Key + '=' + $value).Append("`n")
    }
    return $sb.ToString()
}

function Get-ChalklineJavaArgument {
    <# The command line the scheduled task runs, after the java executable. #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Config)

    $paths = Get-ChalklinePaths -Root $Config.InstallRoot
    $configDir = (ConvertTo-JavaPath $paths.Config).TrimEnd('/') + '/'

    # Profile and config location go on the command line rather than in the
    # file: both are proven there, and the file can then be read by eye.
    return @(
        "-Xmx$($Config.MaxHeapMb)m"
        '-Dfile.encoding=UTF-8'
        '-jar'
        "`"$($paths.Jar)`""
        '--spring.profiles.active=prod'
        "--spring.config.additional-location=file:$configDir"
    ) -join ' '
}

function Get-JavaArchitecture {
    <#
        Adoptium's name for this processor. Handles 32-bit PowerShell on 64-bit
        Windows, where PROCESSOR_ARCHITECTURE lies and says x86.
    #>
    [CmdletBinding()]
    param(
        [string]$Architecture = $env:PROCESSOR_ARCHITECTURE,
        [string]$Wow64Architecture = $env:PROCESSOR_ARCHITEW6432
    )

    $arch = if ($Wow64Architecture) { $Wow64Architecture } else { $Architecture }
    switch -Regex ($arch) {
        '^ARM64$'           { return 'aarch64' }
        '^(AMD64|x64|IA64)$' { return 'x64' }
        default             { return $null }
    }
}

function Get-JavaMajorVersion {
    <#
        Reads the major version out of a JDK's "release" file
        (JAVA_VERSION="21.0.5"), or out of "java -version" output.
        Returns 0 when it cannot tell.
    #>
    [CmdletBinding()]
    param([AllowEmptyString()][string]$Text)

    if ($Text -match 'JAVA_VERSION="(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        # Java 8 and earlier report "1.8"; anything that old is useless here anyway.
        if ($major -eq 1 -and $Matches[2]) { return [int]$Matches[2] }
        return $major
    }
    if ($Text -match 'version "(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[2]) { return [int]$Matches[2] }
        return $major
    }
    return 0
}

function Remove-PropertiesKey {
    <# Returns properties text with one key's line (and its comment) removed. #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Text, [Parameter(Mandatory)][string]$Key)

    $lines = $Text -split "`r?`n"
    $out = New-Object System.Collections.Generic.List[string]
    $escaped = [regex]::Escape($Key)
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^\s*$escaped\s*[=:]") {
            # Drop the comment that introduced it, and the blank line before that.
            while ($out.Count -gt 0 -and $out[$out.Count - 1] -match '^\s*#') { $out.RemoveAt($out.Count - 1) }
            if ($out.Count -gt 0 -and $out[$out.Count - 1] -eq '') { $out.RemoveAt($out.Count - 1) }
            continue
        }
        $out.Add($lines[$i])
    }
    return ($out -join "`n")
}

function Test-SetupLogged {
    <# True when the application log shows the first-run setup created the institution, or found one already there. #>
    [CmdletBinding()]
    param([AllowEmptyString()][string]$LogText)
    return ($LogText -match "Initial setup: (institution '.+' created|an institution already exists)")
}

function Wait-ChalklineSetup {
    <#
        Waits for Chalkline to report the first-run setup. Chalkline runs it just
        after its web server starts answering, so the health check can pass a
        moment before the institution exists, and reading the log once at that
        point is a race. Only the log written after -FromOffset counts, so a line
        left by an earlier attempt is never mistaken for this one.
        Returns Created; Exists (there was one already); Refused (the values were
        rejected and nothing was created); or Unknown if nothing came in time.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$LogPath,
        [int]$FromOffset = 0,
        [int]$TimeoutSeconds = 60
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        $text = if (Test-Path -LiteralPath $LogPath) { Get-Content -LiteralPath $LogPath -Raw -Encoding UTF8 } else { $null }
        if (-not $text) { $text = '' }
        # A log that has shrunk was rotated, so all of it is new.
        $new = if ($text.Length -ge $FromOffset) { $text.Substring($FromOffset) } else { $text }
        if ($new -match "Initial setup: institution '.+' created") {
            return [pscustomobject]@{ Outcome = 'Created'; Detail = '' }
        }
        if ($new -match 'Initial setup: an institution already exists') {
            return [pscustomobject]@{ Outcome = 'Exists'; Detail = '' }
        }
        $refused = [regex]::Match($new, 'Initial setup: ([^\r\n]*Nothing created\.)')
        if ($refused.Success) {
            return [pscustomobject]@{ Outcome = 'Refused'; Detail = $refused.Groups[1].Value }
        }
        if ((Get-Date) -ge $deadline) {
            return [pscustomobject]@{ Outcome = 'Unknown'; Detail = '' }
        }
        Start-Sleep -Seconds 1
    }
}

function ConvertTo-Hashtable {
    <# ConvertFrom-Json gives a PSCustomObject in 5.1, and -AsHashtable does not exist there. #>
    param($InputObject)
    $h = @{}
    if ($null -eq $InputObject) { return $h }
    foreach ($p in $InputObject.PSObject.Properties) {
        $v = $p.Value
        if ($v -is [System.Object[]]) { $v = @($v) }
        $h[$p.Name] = $v
    }
    return $h
}

function Get-SavedChalklineSettings {
    <# The answers from the last deployment, so a re-run does not mean retyping everything. #>
    [CmdletBinding()]
    param([string]$Root = (New-ChalklineConfig).InstallRoot)

    $file = (Get-ChalklinePaths -Root $Root).Settings
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    try {
        $saved = ConvertTo-Hashtable (Get-Content -LiteralPath $file -Raw | ConvertFrom-Json)
        return (Merge-ChalklineConfig -Overrides $saved)
    } catch {
        return $null
    }
}

function ConvertTo-SavedSettingsJson {
    <# The form's answers with every password removed. #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Config, [bool]$SetupCompleted = $false)

    $copy = @{}
    foreach ($k in @($Config.Keys)) {
        if ($k -in @('AdminPassword', 'DbPassword')) { continue }
        $copy[$k] = $Config[$k]
    }
    $copy['SetupCompleted'] = $SetupCompleted
    $copy['SavedAt'] = (Get-Date).ToString('s')
    return (ConvertTo-AsciiJson ($copy | ConvertTo-Json -Depth 4))
}

# =============================================================================
#  Windows: detection
# =============================================================================

function Test-ChalklineAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-ChalklineAddresses {
    <#
        The names and addresses other computers might use to reach this one.
        The computer name is listed first because it survives the IP address
        changing; the IPs are there for networks where names do not resolve.
    #>
    [CmdletBinding()]
    param()

    $result = New-Object System.Collections.Generic.List[object]
    $hostName = [System.Net.Dns]::GetHostName()
    $result.Add([pscustomobject]@{ Address = $hostName; Kind = 'Computer name' })

    try {
        $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
            Sort-Object -Property InterfaceMetric
        foreach ($ip in $ips) {
            $result.Add([pscustomobject]@{ Address = $ip.IPAddress; Kind = "IP address ($($ip.InterfaceAlias))" })
        }
    } catch {
        foreach ($a in [System.Net.Dns]::GetHostAddresses($hostName)) {
            if ($a.AddressFamily -eq 'InterNetwork' -and -not $a.ToString().StartsWith('127.')) {
                $result.Add([pscustomobject]@{ Address = $a.ToString(); Kind = 'IP address' })
            }
        }
    }
    return $result
}

function Get-ChalklineNetworkCategory {
    <#
        Domain, Private or Public for each connected network. Matters because a
        firewall rule only applies to the profiles it names: a school network
        that Windows has classified as Public, which is common on machines not
        joined to a domain, would silently ignore a Domain/Private-only rule.
        Windows calls a domain network "DomainAuthenticated"; it is reported as
        Domain, the name of the firewall profile that covers it.
    #>
    try {
        return @(Get-NetConnectionProfile -ErrorAction Stop | ForEach-Object {
            $category = [string]$_.NetworkCategory
            if ($category -eq 'DomainAuthenticated') { 'Domain' } else { $category }
        } | Select-Object -Unique)
    } catch {
        return @()
    }
}

function Find-ChalklineJava {
    <#
        A java.exe of version 21 or later, or $null. Reads each candidate's
        "release" file instead of running java -version, which writes to
        stderr and trips $ErrorActionPreference = 'Stop' in Windows PowerShell.
    #>
    [CmdletBinding()]
    param([string]$Preferred)

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Preferred) { $candidates.Add($Preferred) }
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }

    foreach ($base in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java",
                        "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Zulu",
                        "$env:ProgramFiles\Amazon Corretto")) {
        if ($base -and (Test-Path -LiteralPath $base)) {
            Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
                Sort-Object -Property Name -Descending |
                ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin\java.exe')) }
        }
    }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates.Add($onPath.Source) }

    foreach ($java in $candidates) {
        if (-not (Test-Path -LiteralPath $java)) { continue }
        $jdkHome = Split-Path (Split-Path $java -Parent) -Parent
        $release = Join-Path $jdkHome 'release'
        $major = 0
        if (Test-Path -LiteralPath $release) {
            $major = Get-JavaMajorVersion (Get-Content -LiteralPath $release -Raw)
        }
        if ($major -ge $script:MinJavaMajor) {
            return [pscustomobject]@{ Path = $java; Home = $jdkHome; Major = $major }
        }
    }
    return $null
}

# =============================================================================
#  Windows: making changes
# =============================================================================

function Write-ChalklineLog {
    param([scriptblock]$Log, [string]$Message)
    if ($Log) { & $Log $Message } else { Write-Host $Message }
}

function Add-ChalklineDeployLog {
    <#
        Appends one deployment's messages to logs\deploy.log, so there is a record
        after the window is closed. Skipped when the logs folder does not exist,
        which only happens when a deployment stops before creating it.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][datetime]$Started,
        [AllowEmptyCollection()][string[]]$Lines = @()
    )
    $folder = [System.IO.Path]::GetDirectoryName($Path)
    if (-not $folder -or -not (Test-Path -LiteralPath $folder)) { return }
    $block = New-Object System.Text.StringBuilder
    [void]$block.Append('===== Deployment started ' + $Started.ToString('yyyy-MM-dd HH:mm:ss') + " =====`r`n")
    foreach ($line in $Lines) { [void]$block.Append($line).Append("`r`n") }
    [void]$block.Append("`r`n")
    # UTF-8, so an institution name with accents survives. The byte order mark
    # is written only when the file is created, and lets Notepad read it right.
    [System.IO.File]::AppendAllText($Path, $block.ToString(), (New-Object System.Text.UTF8Encoding($true)))
}

function Enable-Tls12 {
    # Windows PowerShell 5.1 can default to TLS 1.0, which Adoptium and GitHub refuse.
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

function Install-ChalklineJava {
    <#
        Downloads and installs Eclipse Temurin JDK 21 for this processor, after
        checking the download against the checksum Adoptium publishes. Running
        an installer as administrator without that check would be trusting
        whatever the network handed back.
    #>
    [CmdletBinding()]
    param([scriptblock]$Log)

    $arch = Get-JavaArchitecture
    if (-not $arch) { throw "This processor ($env:PROCESSOR_ARCHITECTURE) is not supported by Java 21 for Windows." }

    Enable-Tls12
    $oldProgress = $ProgressPreference
    # The progress bar slows Invoke-WebRequest in Windows PowerShell by an order of magnitude.
    $ProgressPreference = 'SilentlyContinue'
    try {
        Write-ChalklineLog $Log "  Asking Adoptium for the latest Java 21 JDK ($arch)..."
        $api = "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=$arch&image_type=jdk&os=windows&vendor=eclipse"
        $assets = Invoke-RestMethod -Uri $api -UseBasicParsing -TimeoutSec 60
        $installer = $null
        foreach ($a in @($assets)) {
            if ($a.binary.PSObject.Properties.Name -contains 'installer' -and $a.binary.installer) {
                $installer = $a.binary.installer; $version = $a.version.semver; break
            }
        }
        if (-not $installer) { throw 'Adoptium did not return a Windows installer for this processor.' }

        $msi = Join-Path $env:TEMP $installer.name
        $mb = [math]::Round($installer.size / 1MB)
        Write-ChalklineLog $Log "  Downloading $($installer.name) ($mb MB)..."
        Invoke-WebRequest -Uri $installer.link -OutFile $msi -UseBasicParsing -TimeoutSec 1800

        $actual = (Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash
        if ($actual -ne $installer.checksum) {
            Remove-Item -LiteralPath $msi -Force -ErrorAction SilentlyContinue
            throw "The Java download did not match its published checksum, so it was deleted and not installed."
        }
        Write-ChalklineLog $Log "  Checksum verified. Installing Java $version..."

        $msiLog = Join-Path $env:TEMP 'chalkline-java-install.log'
        $msiArgs = @('/i', "`"$msi`"", '/qn', '/norestart',
                  'ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJavaHome', '/l*v', "`"$msiLog`"")
        $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList $msiArgs -Wait -PassThru
        # 3010 means "installed, restart recommended", which is still a success here.
        if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) {
            throw "The Java installer failed with code $($p.ExitCode). Its log is at $msiLog"
        }
        Remove-Item -LiteralPath $msi -Force -ErrorAction SilentlyContinue
    } finally {
        $ProgressPreference = $oldProgress
    }

    $java = Find-ChalklineJava
    if (-not $java) { throw 'Java installed, but could not be found afterwards.' }
    return $java
}

function Get-ChalklineSourceDir {
    <# The repository root: two levels up from this module (deploy\windows). #>
    $here = Split-Path -Parent $PSCommandPath
    if (-not $here) { $here = $PSScriptRoot }
    return (Resolve-Path (Join-Path $here '..\..')).Path
}

function Invoke-ChalklineBuild {
    <#
        Builds chalkline.jar with the Maven wrapper in the repository, which
        downloads Maven itself, so nothing else needs installing. The first
        build downloads around 80 MB and takes a few minutes.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$SourceDir,
        [Parameter(Mandatory)][string]$JavaHome,
        [scriptblock]$Log
    )

    $buildDir = $SourceDir
    # cmd.exe cannot use a network path as its working directory, so a source
    # tree on a share (or a VM's shared folder) is copied somewhere local first.
    if ($SourceDir -match '^\\\\') {
        $buildDir = Join-Path $env:TEMP 'chalkline-src'
        Write-ChalklineLog $Log "  The source is on a network path; copying it to $buildDir first..."
        if (Test-Path -LiteralPath $buildDir) { Remove-Item -LiteralPath $buildDir -Recurse -Force }
        $null = New-Item -ItemType Directory -Path $buildDir
        foreach ($item in @('pom.xml', 'mvnw.cmd', '.mvn', 'src')) {
            $from = Join-Path $SourceDir $item
            if (-not (Test-Path -LiteralPath $from)) { throw "The source folder is missing $item." }
            Copy-Item -LiteralPath $from -Destination $buildDir -Recurse -Force
        }
    }

    $mvnw = Join-Path $buildDir 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mvnw)) { throw "Could not find mvnw.cmd in $buildDir." }

    $out = Join-Path $env:TEMP 'chalkline-build.out.log'
    $err = Join-Path $env:TEMP 'chalkline-build.err.log'
    Remove-Item -LiteralPath $out, $err -ErrorAction SilentlyContinue

    $oldJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $JavaHome
    try {
        Write-ChalklineLog $Log '  Building with the Maven wrapper (the first build downloads Maven and its libraries)...'
        $p = Start-Process -FilePath $mvnw -ArgumentList @('-B', '-DskipTests', 'package') `
            -WorkingDirectory $buildDir -NoNewWindow -PassThru `
            -RedirectStandardOutput $out -RedirectStandardError $err
        # Windows PowerShell 5.1: unless the handle is read now, a process that
        # is polled with HasExited never keeps one, and ExitCode later comes
        # back empty. Found on Windows 11: Maven printed BUILD SUCCESS and the
        # deployer reported a failed build with no exit code.
        $null = $p.Handle

        # Stream the build log as it happens, a few lines at a time.
        $shown = 0
        while (-not $p.HasExited) {
            Start-Sleep -Milliseconds 700
            $shown = Show-NewLogLines -File $out -Shown $shown -Log $Log
        }
        $p.WaitForExit()
        $null = Show-NewLogLines -File $out -Shown $shown -Log $Log

        $exitCode = $p.ExitCode
        $succeeded = if ($null -ne $exitCode) { $exitCode -eq 0 } else {
            # Still no exit code: decide from what Maven itself said.
            (Test-Path -LiteralPath $out) -and ((Get-Content -LiteralPath $out -Raw) -cmatch 'BUILD SUCCESS')
        }
        if (-not $succeeded) {
            $tail = if (Test-Path $err) { (Get-Content -LiteralPath $err -Tail 15) -join "`n" } else { '' }
            if (-not $tail -and (Test-Path $out)) { $tail = (Get-Content -LiteralPath $out -Tail 15) -join "`n" }
            throw "The build failed (exit code $exitCode). $tail"
        }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
    }

    $jar = Join-Path $buildDir 'target\chalkline.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'The build reported success but produced no target\chalkline.jar.' }
    return $jar
}

function Show-NewLogLines {
    param([string]$File, [int]$Shown, [scriptblock]$Log)
    if (-not (Test-Path -LiteralPath $File)) { return $Shown }
    $lines = @(Get-Content -LiteralPath $File -ErrorAction SilentlyContinue)
    for ($i = $Shown; $i -lt $lines.Count; $i++) {
        # Maven is chatty; show only the lines that mean something to a person.
        # -cmatch, not -match: case-insensitive "ERROR" matched every download
        # of Google's error_prone library and flooded the log.
        if ($lines[$i] -cmatch 'BUILD (SUCCESS|FAILURE)|\[ERROR\]|Building Chalkline|Total time') {
            Write-ChalklineLog $Log ('    ' + ($lines[$i] -replace '^\[INFO\]\s*', ''))
        }
    }
    return $lines.Count
}

function Set-ChalklineFolders {
    <#
        Creates the folders and sets who may read them. The config folder holds
        passwords, so it is readable by the service account and administrators
        only, never by the lecturers who sign in to the computer.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Config, [scriptblock]$Log)

    $paths = Get-ChalklinePaths -Root $Config.InstallRoot
    foreach ($dir in @($paths.Root, $paths.App, $paths.Config, $paths.Data, $paths.Logs, $paths.Backups, $paths.Tools)) {
        if (-not (Test-Path -LiteralPath $dir)) { $null = New-Item -ItemType Directory -Path $dir -Force }
    }

    $sys = $script:Sid.System; $adm = $script:Sid.Administrators; $svc = $script:Sid.LocalService

    # Root: administrators and SYSTEM own it; the service may read and run.
    Invoke-Icacls $paths.Root   @('/inheritance:r', '/grant:r', "*${sys}:(OI)(CI)F", "*${adm}:(OI)(CI)F", "*${svc}:(OI)(CI)RX")
    # Data and logs: the service writes here.
    Invoke-Icacls $paths.Data   @('/grant:r', "*${svc}:(OI)(CI)M")
    Invoke-Icacls $paths.Logs   @('/grant:r', "*${svc}:(OI)(CI)M")
    # Config: passwords. Read-only for the service, nothing for anyone else.
    Invoke-Icacls $paths.Config @('/inheritance:r', '/grant:r', "*${sys}:(OI)(CI)F", "*${adm}:(OI)(CI)F", "*${svc}:(OI)(CI)R")

    Write-ChalklineLog $Log "  Folders ready under $($paths.Root), settings readable by the service only."
    return $paths
}

function Invoke-Icacls {
    param([string]$Path, [string[]]$Arguments)
    $output = & icacls.exe $Path @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not set permissions on ${Path}: $output" }
}

function Write-ChalklineTextFile {
    <#
        Writes a text file with Windows line endings.

        Ascii (the default) is for files whose content is already escaped, like
        application.properties. It REFUSES anything that is not ASCII rather
        than writing it: .NET's ASCII encoder otherwise replaces each such
        character with "?" without a word, which is how "Universite" with an
        accent once reached the form as "Universit?".

        Utf8Bom is for PowerShell scripts, which Windows PowerShell 5.1 only
        reads as UTF-8 when they start with a byte-order mark.
    #>
    param(
        [string]$Path,
        [string]$Text,
        [ValidateSet('Ascii', 'Utf8Bom')][string]$Encoding = 'Ascii'
    )
    $normalised = ($Text -replace "`r?`n", "`r`n")
    $enc = if ($Encoding -eq 'Utf8Bom') {
        New-Object System.Text.UTF8Encoding($true)
    } else {
        [System.Text.Encoding]::GetEncoding('us-ascii',
            [System.Text.EncoderFallback]::ExceptionFallback, [System.Text.DecoderFallback]::ExceptionFallback)
    }
    [System.IO.File]::WriteAllText($Path, $normalised, $enc)
}

function ConvertTo-AsciiJson {
    <#
        JSON with every non-ASCII character written as \uXXXX, which JSON
        allows and every reader decodes. Windows PowerShell 5.1's ConvertTo-Json
        has no option for this (-EscapeHandling arrived in PowerShell 6.2), and
        an escaped file reads back identically whatever encoding it is read in.
    #>
    param([Parameter(Mandatory)][string]$Json)
    return [regex]::Replace($Json, '[^\x00-\x7F]', { param($m) '\u{0:x4}' -f [int][char]$m.Value })
}

function Set-ChalklineFirewall {
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Config, [scriptblock]$Log)

    Remove-NetFirewallRule -Name $script:FirewallName -ErrorAction SilentlyContinue
    if (-not $Config.OpenFirewall) {
        Write-ChalklineLog $Log '  Firewall left closed, as chosen. Other computers will not be able to connect.'
        return
    }

    $rule = @{
        Name        = $script:FirewallName
        DisplayName = "Chalkline (TCP $($Config.Port))"
        Description = 'Lets lecturers on the school network reach Chalkline. Added by the Chalkline deployer.'
        Direction   = 'Inbound'
        Protocol    = 'TCP'
        LocalPort   = [string]$Config.Port
        Action      = 'Allow'
        Profile     = @($Config.FirewallProfiles)
    }
    if ($Config.LocalSubnetOnly) { $rule.RemoteAddress = 'LocalSubnet' }
    $null = New-NetFirewallRule @rule
    Write-ChalklineLog $Log ("  Firewall open on TCP $($Config.Port) for the " + (@($Config.FirewallProfiles) -join ', ') + ' network profile(s).')
}

function Register-ChalklineTask {
    <#
        Runs Chalkline as the built-in LOCAL SERVICE account, at startup,
        restarting if it stops. LOCAL SERVICE rather than SYSTEM because this is
        a web server listening on the network: if it were ever compromised, it
        should not hold the keys to the whole computer.
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Config, [Parameter(Mandatory)][string]$JavaPath, [scriptblock]$Log)

    $paths = Get-ChalklinePaths -Root $Config.InstallRoot
    Unregister-ScheduledTask -TaskName $script:TaskName -Confirm:$false -ErrorAction SilentlyContinue

    $action = New-ScheduledTaskAction -Execute $JavaPath -Argument (Get-ChalklineJavaArgument -Config $Config) `
        -WorkingDirectory $paths.Root
    $principal = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\LOCALSERVICE' -LogonType ServiceAccount -RunLevel Limited

    # ExecutionTimeLimit is zero on purpose. The default is 72 hours, after
    # which Windows kills the task. That only applies to runs started by a
    # trigger; a task started by hand is exempt, so testing by hand never shows it.
    $settings = New-ScheduledTaskSettingsSet `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -MultipleInstances IgnoreNew

    $triggers = @()
    if ($Config.StartWithWindows) { $triggers += New-ScheduledTaskTrigger -AtStartup }

    $task = @{
        TaskName    = $script:TaskName
        Description = "Chalkline timetabling, serving $(Get-ChalklinePublicUrl -Config $Config). Managed by the Chalkline deployer."
        Action      = $action
        Principal   = $principal
        Settings    = $settings
    }
    if ($triggers.Count -gt 0) { $task.Trigger = $triggers }
    $null = Register-ScheduledTask @task

    $when = if ($Config.StartWithWindows) { 'starts with Windows and restarts if it stops' } else { 'starts only when you start it' }
    Write-ChalklineLog $Log "  Scheduled task '$($script:TaskName)' registered: runs as LOCAL SERVICE, $when."
}

function Get-ChalklineBackupScript {
    <#
        The script the nightly backup task runs. It stops Chalkline, zips the
        data folder, and starts it again. Stopping first is what makes the copy
        consistent: copying a database file while it is being written can give
        a backup that will not open. A few seconds of downtime at 2am is the
        price, and it is the right trade for a school system.
    #>
    param([Parameter(Mandatory)][hashtable]$Config)
    $paths = Get-ChalklinePaths -Root $Config.InstallRoot
    return @"
# Chalkline nightly backup. Written by the Chalkline deployer.
`$ErrorActionPreference = 'Stop'
`$data    = '$($paths.Data)'
`$backups = '$($paths.Backups)'
`$keep    = $([int]$Config.BackupKeep)
`$port    = $([int]$Config.Port)
`$log     = '$($paths.Logs)\backup.log'
function Say(`$m) { Add-Content -LiteralPath `$log -Value ((Get-Date -Format 's') + '  ' + `$m) }

try {
    `$wasRunning = (Get-ScheduledTask -TaskName '$($script:TaskName)').State -eq 'Running'
    if (`$wasRunning) {
        Stop-ScheduledTask -TaskName '$($script:TaskName)'
        for (`$i = 0; `$i -lt 60; `$i++) {
            if (-not (Get-NetTCPConnection -LocalPort `$port -State Listen -ErrorAction SilentlyContinue)) { break }
            Start-Sleep -Seconds 1
        }
    }
    `$zip = Join-Path `$backups ('chalkline-' + (Get-Date -Format 'yyyyMMdd-HHmm') + '.zip')
    Compress-Archive -Path (Join-Path `$data '*') -DestinationPath `$zip -Force
    Say "Backed up to `$zip"

    Get-ChildItem -LiteralPath `$backups -Filter 'chalkline-*.zip' |
        Sort-Object Name -Descending | Select-Object -Skip `$keep |
        ForEach-Object { Remove-Item -LiteralPath `$_.FullName -Force; Say "Removed old backup `$(`$_.Name)" }
} catch {
    Say ("BACKUP FAILED: " + `$_.Exception.Message)
    throw
} finally {
    if (`$wasRunning) { Start-ScheduledTask -TaskName '$($script:TaskName)' }
}
"@
}

function Register-ChalklineBackupTask {
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Config, [scriptblock]$Log)

    Unregister-ScheduledTask -TaskName $script:BackupTaskName -Confirm:$false -ErrorAction SilentlyContinue
    if (-not $Config.NightlyBackup -or $Config.DatabaseType -ne 'H2') {
        if ($Config.DatabaseType -eq 'PostgreSQL') {
            Write-ChalklineLog $Log '  No nightly backup: with PostgreSQL, back up the database server itself (pg_dump or your provider''s backups).'
        }
        return
    }

    $paths = Get-ChalklinePaths -Root $Config.InstallRoot
    Write-ChalklineTextFile -Path $paths.BackupPs1 -Text (Get-ChalklineBackupScript -Config $Config) -Encoding Utf8Bom

    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$($paths.BackupPs1)`""
    $trigger = New-ScheduledTaskTrigger -Daily -At $Config.BackupTime
    # SYSTEM because it has to stop and start the other task.
    $principal = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 1) -StartWhenAvailable `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

    $null = Register-ScheduledTask -TaskName $script:BackupTaskName -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings `
        -Description 'Nightly copy of the Chalkline database. Managed by the Chalkline deployer.'
    Write-ChalklineLog $Log "  Nightly backup at $($Config.BackupTime), keeping the last $($Config.BackupKeep), in $($paths.Backups)."
}

function Get-ChalklineStatus {
    [CmdletBinding()]
    param([string]$Root = (New-ChalklineConfig).InstallRoot, [int]$Port = 0)

    $task = Get-ScheduledTask -TaskName $script:TaskName -ErrorAction SilentlyContinue
    if ($Port -eq 0) {
        $saved = Get-SavedChalklineSettings -Root $Root
        $Port = if ($saved) { [int]$saved.Port } else { 8080 }
    }
    $healthy = Test-ChalklineHealth -Port $Port
    return [pscustomobject]@{
        Installed = [bool]$task
        TaskState = if ($task) { [string]$task.State } else { 'Not installed' }
        Healthy   = $healthy
        Port      = $Port
    }
}

function Test-ChalklineHealth {
    param([int]$Port)
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -UseBasicParsing -TimeoutSec 4
        return (Test-HealthResponse -StatusCode ([int]$r.StatusCode) -Content $r.Content)
    } catch {
        return $false
    }
}

function Test-HealthResponse {
    <#
        Whether a health-check response means "up".

        Windows PowerShell 5.1 hands back .Content as a byte array, not text,
        for content types it does not recognise, and Spring's health endpoint
        replies with application/vnd.spring-boot.actuator.v3+json. Matching
        text against the bytes silently fails, so a perfectly healthy server
        looked dead. Tested on Windows 11 with PowerShell 5.1; PowerShell 7
        returns a string, which is why it never showed up elsewhere.
    #>
    param([int]$StatusCode, $Content)
    if ($StatusCode -ne 200) { return $false }
    $text = if ($Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($Content) } else { [string]$Content }
    return ($text -match '"status"\s*:\s*"UP"')
}

function Wait-ChalklineHealthy {
    <# Waits for the health check. The first start creates the database, which takes a while on a lab PC. #>
    [CmdletBinding()]
    param([int]$Port, [int]$TimeoutSeconds = 240, [scriptblock]$Log)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $nextNote = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        if (Test-ChalklineHealth -Port $Port) { return $true }
        if ((Get-Date) -gt $nextNote) {
            Write-ChalklineLog $Log '  Still starting...'
            $nextNote = (Get-Date).AddSeconds(20)
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Stop-Chalkline {
    [CmdletBinding()]
    param([int]$Port = 8080, [scriptblock]$Log)
    $task = Get-ScheduledTask -TaskName $script:TaskName -ErrorAction SilentlyContinue
    if ($task -and $task.State -eq 'Running') {
        Stop-ScheduledTask -TaskName $script:TaskName
        for ($i = 0; $i -lt 60; $i++) {
            if (-not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) { break }
            Start-Sleep -Seconds 1
        }
        Write-ChalklineLog $Log '  Chalkline stopped.'
    }
}

function Start-Chalkline {
    [CmdletBinding()]
    param([scriptblock]$Log)
    Start-ScheduledTask -TaskName $script:TaskName
    Write-ChalklineLog $Log '  Chalkline started.'
}

function Invoke-ChalklineBackup {
    <# Runs the nightly backup now. #>
    [CmdletBinding()]
    param([scriptblock]$Log)
    if (-not (Get-ScheduledTask -TaskName $script:BackupTaskName -ErrorAction SilentlyContinue)) {
        throw 'There is no backup task. Backups are only set up for the built-in database.'
    }
    Start-ScheduledTask -TaskName $script:BackupTaskName
    for ($i = 0; $i -lt 300; $i++) {
        Start-Sleep -Seconds 1
        if ((Get-ScheduledTask -TaskName $script:BackupTaskName).State -ne 'Running') { break }
    }
    $info = Get-ScheduledTaskInfo -TaskName $script:BackupTaskName
    if ($info.LastTaskResult -ne 0) { throw "The backup failed (code $($info.LastTaskResult)). See logs\backup.log." }
    Write-ChalklineLog $Log '  Backup complete.'
}

function Uninstall-Chalkline {
    <#
        Removes the tasks, the firewall rule, the application and its settings.
        Keeps the database and the backups unless -RemoveData is given, because
        "uninstall" is not usually meant to mean "delete a year of timetables".
    #>
    [CmdletBinding()]
    param([string]$Root = (New-ChalklineConfig).InstallRoot, [switch]$RemoveData, [scriptblock]$Log)

    $paths = Get-ChalklinePaths -Root $Root
    $saved = Get-SavedChalklineSettings -Root $Root
    $port = if ($saved) { [int]$saved.Port } else { 8080 }

    Stop-Chalkline -Port $port -Log $Log
    foreach ($name in @($script:TaskName, $script:BackupTaskName)) {
        if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) {
            Unregister-ScheduledTask -TaskName $name -Confirm:$false
            Write-ChalklineLog $Log "  Removed scheduled task '$name'."
        }
    }
    if (Get-NetFirewallRule -Name $script:FirewallName -ErrorAction SilentlyContinue) {
        Remove-NetFirewallRule -Name $script:FirewallName
        Write-ChalklineLog $Log '  Removed the firewall rule.'
    }
    foreach ($dir in @($paths.App, $paths.Config, $paths.Tools)) {
        if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
    }
    foreach ($file in @($paths.Shortcut)) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
    }
    if ($RemoveData) {
        if (Test-Path -LiteralPath $paths.Root) { Remove-Item -LiteralPath $paths.Root -Recurse -Force }
        Write-ChalklineLog $Log "  Deleted $($paths.Root), including the database and every backup."
    } else {
        Write-ChalklineLog $Log "  Kept the database and backups in $($paths.Root). Deploying again picks them up."
    }
}

# =============================================================================
#  The whole deployment, in order
# =============================================================================

function Invoke-ChalklineDeploySteps {
    <# The steps of Invoke-ChalklineDeploy, which also records what they report. #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [switch]$Preview,
        [scriptblock]$Log
    )

    $Config = Merge-ChalklineConfig -Overrides $Config
    $paths = Get-ChalklinePaths -Root $Config.InstallRoot
    $previous = Get-SavedChalklineSettings -Root $Config.InstallRoot
    $existingData = (Test-Path -LiteralPath $paths.Data) -and
                    @(Get-ChildItem -LiteralPath $paths.Data -ErrorAction SilentlyContinue).Count -gt 0
    $appLogText = if (Test-Path -LiteralPath $paths.AppLog) { Get-Content -LiteralPath $paths.AppLog -Raw -Encoding UTF8 } else { $null }
    # Whether the institution already exists, on the best evidence available:
    #   the last deployment recorded it; or the application logged creating it;
    #   or there is a database but no log at all (restored from a backup).
    # A database WITH a log that never mentions setup means Chalkline stopped
    # before creating the institution. Treating that as done would lock
    # everyone out, so setup runs again instead. That is safe, because
    # Chalkline only ever creates the institution in an empty database.
    $setupAlreadyDone =
        ($previous -and $previous.ContainsKey('SetupCompleted') -and $previous.SetupCompleted) -or
        (Test-SetupLogged $appLogText) -or
        ($existingData -and $Config.DatabaseType -eq 'H2' -and $null -eq $appLogText)
    $fresh = -not $setupAlreadyDone
    $url = Get-ChalklinePublicUrl -Config $Config
    $say = { param($m) Write-ChalklineLog $Log $m }

    & $say '1/9  Checking the settings'
    $problems = @(Test-ChalklineConfig -Config $Config -FreshInstall:$fresh)
    if ($problems.Count -gt 0) {
        foreach ($p in $problems) { & $say "  - $p" }
        throw 'Fix the settings above, then deploy again.'
    }
    & $say ($(if ($fresh) { '  New installation.' } else { '  Updating an existing installation. Its data is kept.' }))

    if (-not $Preview -and -not (Test-ChalklineAdmin)) {
        throw 'The deployer needs to run as administrator. Close it and start Deploy-Chalkline.cmd again.'
    }

    & $say '2/9  Java'
    $java = Find-ChalklineJava -Preferred $Config.JavaPath
    if ($java) {
        & $say "  Found Java $($java.Major) at $($java.Path)"
    } elseif ($Preview) {
        & $say "  WOULD download and install Eclipse Temurin JDK 21 ($(Get-JavaArchitecture)) from adoptium.net, checking its checksum."
    } else {
        & $say '  Java 21 is not installed. Installing it now.'
        $java = Install-ChalklineJava -Log $Log
        & $say "  Installed Java $($java.Major) at $($java.Path)"
    }

    & $say '3/9  Chalkline'
    $jarToInstall = $null
    if ($Config.JarSource -eq 'File') {
        if (-not (Test-Path -LiteralPath $Config.JarPath)) { throw "Cannot find $($Config.JarPath)." }
        $jarToInstall = $Config.JarPath
        & $say "  Using $jarToInstall"
    } else {
        $source = if ($Config.SourceDir) { $Config.SourceDir } else { Get-ChalklineSourceDir }
        if ($Preview) {
            & $say "  WOULD build chalkline.jar from $source with the Maven wrapper."
        } else {
            $jarToInstall = Invoke-ChalklineBuild -SourceDir $source -JavaHome $java.Home -Log $Log
            & $say '  Built chalkline.jar.'
        }
    }

    & $say '4/9  Folders and permissions'
    if ($Preview) {
        & $say "  WOULD create $($paths.Root) with app, config, data, logs, backups and tools folders."
        & $say '  WOULD make the config folder readable by the LOCAL SERVICE account and administrators only.'
    } else {
        Stop-Chalkline -Port ([int]$(if ($previous) { $previous.Port } else { $Config.Port })) -Log $Log
        $null = Set-ChalklineFolders -Config $Config -Log $Log
        Copy-Item -LiteralPath $jarToInstall -Destination $paths.Jar -Force
    }

    & $say '5/9  Settings'
    $maskedText = ConvertTo-ChalklineProperties -Config $Config -MaskSecrets -OmitAdminPassword:(-not $fresh)
    if ($Preview) {
        & $say "  WOULD write $($paths.Properties):"
        foreach ($line in ($maskedText -split "`r?`n")) { if ($line) { & $say "    $line" } }
    } else {
        Write-ChalklineTextFile -Path $paths.Properties `
            -Text (ConvertTo-ChalklineProperties -Config $Config -OmitAdminPassword:(-not $fresh))
        & $say "  Wrote $($paths.Properties)"
    }

    & $say '6/9  Firewall'
    $categories = @(Get-ChalklineNetworkCategory)
    if ($Config.OpenFirewall -and $categories.Count -gt 0) {
        $missing = @($categories | Where-Object { @($Config.FirewallProfiles) -notcontains $_ })
        if ($missing -contains 'Public') {
            & $say '  WARNING: Windows has classified this network as Public, and the firewall rule does not include'
            & $say '           the Public profile, so other computers will NOT be able to connect. Tick Public in'
            & $say '           Network, or ask IT to mark this network as Private.'
        }
    }
    if ($Preview) {
        $where = if ($Config.OpenFirewall) { 'open TCP ' + $Config.Port + ' for ' + (@($Config.FirewallProfiles) -join ', ') } else { 'leave the firewall closed' }
        & $say "  WOULD $where."
    } else {
        Set-ChalklineFirewall -Config $Config -Log $Log
    }

    & $say '7/9  Scheduled tasks'
    if ($Preview) {
        & $say "  WOULD register task '$($script:TaskName)' running java $(Get-ChalklineJavaArgument -Config $Config)"
        & $say '        as LOCAL SERVICE, at startup, restarting every minute if it stops, with no time limit.'
        if ($Config.NightlyBackup -and $Config.DatabaseType -eq 'H2') {
            & $say "  WOULD register task '$($script:BackupTaskName)' daily at $($Config.BackupTime), keeping $($Config.BackupKeep)."
        }
    } else {
        Register-ChalklineTask -Config $Config -JavaPath $java.Path -Log $Log
        Register-ChalklineBackupTask -Config $Config -Log $Log
    }

    & $say '8/9  Starting'
    if ($Preview) {
        & $say "  WOULD start Chalkline and wait for $url to answer."
        & $say ''
        & $say 'Preview only. Nothing on this computer has been changed.'
        return [pscustomobject]@{ Url = $url; Preview = $true; Healthy = $false; SetupCompleted = $false }
    }

    # Where this run's part of the application log begins, so that only what
    # this start writes is read back for the setup result.
    $previousLog = if (Test-Path -LiteralPath $paths.AppLog) { Get-Content -LiteralPath $paths.AppLog -Raw -Encoding UTF8 } else { $null }
    $logOffset = if ($previousLog) { $previousLog.Length } else { 0 }

    Start-Chalkline -Log $Log
    if (-not (Wait-ChalklineHealthy -Port ([int]$Config.Port) -Log $Log)) {
        $tail = if (Test-Path -LiteralPath $paths.AppLog) { (Get-Content -LiteralPath $paths.AppLog -Tail 25 -Encoding UTF8) -join "`n" } else { '(no log yet)' }
        throw "Chalkline did not start within four minutes. The end of its log:`n$tail"
    }
    & $say "  Chalkline is answering on port $($Config.Port)."

    & $say '9/9  Finishing'
    $setupCompleted = $setupAlreadyDone
    if ($fresh) {
        $setup = Wait-ChalklineSetup -LogPath $paths.AppLog -FromOffset $logOffset -TimeoutSeconds 60
        if ($setup.Outcome -eq 'Refused') {
            throw "Chalkline started but did not create the institution: $($setup.Detail) Correct it on the Institution tab and deploy again."
        }
        if ($setup.Outcome -eq 'Created' -or $setup.Outcome -eq 'Exists') {
            $setupCompleted = $true
            # The administrator password has done its job. Take it out of the file.
            $text = Get-Content -LiteralPath $paths.Properties -Raw
            Write-ChalklineTextFile -Path $paths.Properties -Text (Remove-PropertiesKey -Text $text -Key 'app.setup.admin-password')
            if ($setup.Outcome -eq 'Created') {
                & $say "  Created '$($Config.InstitutionName)' with administrator $($Config.AdminEmail)."
            } else {
                & $say '  This database already holds an institution, so it was kept exactly as it was.'
            }
            & $say '  Removed the administrator password from the settings file now that it has been used.'
        } else {
            & $say '  WARNING: Chalkline is running, but its log does not show the institution being created.'
            & $say "           Check $($paths.AppLog) before handing out the address."
        }
    }

    Write-ChalklineTextFile -Path $paths.Settings -Text (ConvertTo-SavedSettingsJson -Config $Config -SetupCompleted $setupCompleted)
    Write-ChalklineTextFile -Path $paths.Shortcut -Text "[InternetShortcut]`r`nURL=$url`r`n"

    & $say ''
    & $say 'Deployed.'
    & $say "Lecturers open:  $url"
    if ($fresh -and $setupCompleted) {
        & $say "Sign in as $($Config.AdminEmail) with the password you chose. You will be asked to set a new one."
    }
    return [pscustomobject]@{ Url = $url; Preview = $false; Healthy = $true; SetupCompleted = $setupCompleted }
}

function Invoke-ChalklineDeploy {
    <#
        Runs every step of a deployment. With -Preview it changes nothing and
        reports what it would do, including the settings file with passwords
        masked. That is what to show an IT department before they agree.
        A real deployment's messages are also appended to logs\deploy.log.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Config,
        [switch]$Preview,
        [scriptblock]$Log
    )

    $Config = Merge-ChalklineConfig -Overrides $Config
    $started = Get-Date
    $transcript = New-Object System.Collections.Generic.List[string]
    $caller = $Log
    # Each message still goes wherever the caller wanted it, and is kept for the log file.
    $record = { param($m) $transcript.Add([string]$m); if ($caller) { & $caller $m } else { Write-Host $m } }.GetNewClosure()
    try {
        Invoke-ChalklineDeploySteps -Config $Config -Preview:$Preview -Log $record
    } catch {
        $transcript.Add('PROBLEM: ' + $_.Exception.Message)
        throw
    } finally {
        if (-not $Preview) {
            # A log that cannot be written must never turn a deployment into a failure.
            try {
                Add-ChalklineDeployLog -Path (Get-ChalklinePaths -Root $Config.InstallRoot).DeployLog -Started $started -Lines $transcript.ToArray()
            } catch { }
        }
    }
}

Export-ModuleMember -Function @(
    'New-ChalklineConfig', 'Merge-ChalklineConfig', 'Test-ChalklineConfig', 'Test-ChalklineEmail',
    'Get-ChalklinePaths', 'Get-ChalklinePublicUrl', 'Get-ChalklineJdbcUrl',
    'ConvertTo-PropertiesValue', 'Get-ChalklineSettingList', 'ConvertTo-ChalklineProperties',
    'Get-ChalklineJavaArgument', 'Get-JavaArchitecture', 'Get-JavaMajorVersion',
    'Remove-PropertiesKey', 'Test-SetupLogged', 'Wait-ChalklineSetup', 'Add-ChalklineDeployLog',
    'Get-SavedChalklineSettings', 'ConvertTo-SavedSettingsJson',
    'ConvertTo-AsciiJson', 'Write-ChalklineTextFile',
    'Get-ChalklineBackupScript',
    'Test-ChalklineAdmin', 'Get-ChalklineAddresses', 'Get-ChalklineNetworkCategory', 'Find-ChalklineJava',
    'Install-ChalklineJava', 'Invoke-ChalklineBuild', 'Get-ChalklineSourceDir',
    'Get-ChalklineStatus', 'Test-ChalklineHealth', 'Test-HealthResponse', 'Wait-ChalklineHealthy',
    'Start-Chalkline', 'Stop-Chalkline', 'Invoke-ChalklineBackup', 'Uninstall-Chalkline',
    'Invoke-ChalklineDeploy'
)
