<#
    Tests for the deployer engine. Run with:  Invoke-Pester .\tests

    These cover everything that decides WHAT gets written (the settings
    file, validation, the command line), which is where a mistake would
    produce a deployment that starts but does not work. They run anywhere
    PowerShell runs. What the Windows-only functions DO to the machine is
    tested by deploying for real; see the README.
#>

BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\ChalklineDeploy.psm1') -Force

    function New-ValidConfig {
        Merge-ChalklineConfig -Overrides @{
            InstallRoot     = 'C:\ProgramData\Chalkline'
            InstitutionName = 'Abetifi Technical University'
            AdminName       = 'Kwame Asante'
            AdminEmail      = 'it@northfield.example'
            AdminPassword   = 'first-run-pass'
            ServerAddress   = 'ATU-LAB-01'
            Port            = 8080
        }
    }

    # Parses properties text with the JVM's own parser, which reads files the
    # same way Spring Boot does (ISO-8859-1 with \u escapes).
    function Read-WithJava([string]$Text) {
        $java = Get-Command java -ErrorAction SilentlyContinue
        if (-not $java) { return $null }
        $dir = Join-Path ([IO.Path]::GetTempPath()) ('ckprops-' + [guid]::NewGuid())
        $null = New-Item -ItemType Directory -Path $dir
        $props = Join-Path $dir 'test.properties'
        [IO.File]::WriteAllText($props, $Text, (New-Object Text.ASCIIEncoding))
        $src = Join-Path $dir 'Dump.java'
        $out = Join-Path $dir 'parsed.txt'
        # The result goes through a UTF-8 file, not stdout: Windows PowerShell
        # decodes a program's output in the console's OEM codepage (IBM437),
        # which turned a correctly parsed e-acute into a Greek capital theta.
        Set-Content -LiteralPath $src -Value @'
import java.io.*; import java.util.*; import java.nio.file.*; import java.nio.charset.StandardCharsets;
public class Dump { public static void main(String[] a) throws Exception {
  Properties p = new Properties(); try (InputStream in = new FileInputStream(a[0])) { p.load(in); }
  StringBuilder sb = new StringBuilder();
  for (String k : new TreeSet<>(p.stringPropertyNames())) {
    sb.append(k).append('\u0000').append(p.getProperty(k)).append('\u0001'); }
  Files.write(Paths.get(a[1]), sb.toString().getBytes(StandardCharsets.UTF_8)); } }
'@
        $null = & $java.Source $src $props $out
        $raw = Get-Content -LiteralPath $out -Raw -Encoding UTF8
        Remove-Item -LiteralPath $dir -Recurse -Force
        $map = @{}
        foreach ($pair in ([string]$raw) -split [char]1) {
            if ($pair) { $kv = $pair -split [char]0, 2; $map[$kv[0]] = $kv[1] }
        }
        return $map
    }
}

Describe 'Escaping values for application.properties' {

    It 'doubles a backslash exactly once' {
        # A switch with "continue" falls through in PowerShell and doubled it twice.
        ConvertTo-PropertiesValue 'a\b' | Should -Be 'a\\b'
    }

    It 'writes non-ASCII as a \u escape, because Spring reads properties as ISO-8859-1' {
        ConvertTo-PropertiesValue 'Université' | Should -Be 'Universit\u00e9'
    }

    It 'writes characters outside the Basic Multilingual Plane as a surrogate pair' {
        ConvertTo-PropertiesValue ([char]::ConvertFromUtf32(0x1F393)) | Should -Be '\ud83c\udf93'
    }

    It 'protects a leading space, which Java would otherwise strip' {
        ConvertTo-PropertiesValue ' padded' | Should -Be '\ padded'
    }

    It 'escapes control characters' {
        ConvertTo-PropertiesValue "a`tb`nc" | Should -Be 'a\tb\nc'
    }

    It 'leaves ordinary ASCII alone, including = and : which are fine inside a value' {
        ConvertTo-PropertiesValue 'p@ss=w:rd#1!' | Should -Be 'p@ss=w:rd#1!'
    }

    It 'returns an empty string for empty or null' {
        ConvertTo-PropertiesValue '' | Should -Be ''
        ConvertTo-PropertiesValue $null | Should -Be ''
    }

    It 'round-trips awkward values through the real Java properties parser' {
        $nasty = @{
            InstitutionName = "Université Félix Houphouët-Boigny"
            AdminPassword   = 'p\a=s:s w0rd#!'
            AdminName       = " Leading Space"
        }
        $config = Merge-ChalklineConfig -Overrides ((New-ValidConfig) + @{} )
        foreach ($k in $nasty.Keys) { $config[$k] = $nasty[$k] }

        $parsed = Read-WithJava (ConvertTo-ChalklineProperties -Config $config)
        if ($null -eq $parsed) { Set-ItResult -Skipped -Because 'java is not on the PATH'; return }

        $parsed['app.setup.organisation-name'] | Should -BeExactly $nasty.InstitutionName
        $parsed['app.setup.admin-password']    | Should -BeExactly $nasty.AdminPassword
        $parsed['app.setup.admin-name']        | Should -BeExactly $nasty.AdminName
    }
}

Describe 'The settings file' {

    It 'is pure ASCII, so no byte-order mark or encoding can corrupt it' {
        $config = New-ValidConfig
        $config.InstitutionName = 'École Normale Supérieure'
        $text = ConvertTo-ChalklineProperties -Config $config
        [Text.Encoding]::ASCII.GetString([Text.Encoding]::ASCII.GetBytes($text)) | Should -BeExactly $text
    }

    It 'points share links and calendar feeds at the network address, never localhost' {
        ConvertTo-ChalklineProperties -Config (New-ValidConfig) |
            Should -Match '(?m)^app\.public-url=http://ATU-LAB-01:8080$'
    }

    It 'turns off the secure cookie, or nobody could sign in over plain http' {
        ConvertTo-ChalklineProperties -Config (New-ValidConfig) |
            Should -Match '(?m)^server\.servlet\.session\.cookie\.secure=false$'
    }

    It 'closes sign-up by default, so nobody can create a second institution' {
        ConvertTo-ChalklineProperties -Config (New-ValidConfig) |
            Should -Match '(?m)^app\.security\.allow-sign-up=false$'
    }

    It 'unlocks every feature for a school hosting its own copy' {
        ConvertTo-ChalklineProperties -Config (New-ValidConfig) | Should -Match '(?m)^app\.setup\.plan=PREMIUM$'
    }

    It 'adds no sample courses unless asked' {
        ConvertTo-ChalklineProperties -Config (New-ValidConfig) |
            Should -Match '(?m)^app\.setup\.seed-sample-catalogue=false$'
    }

    It 'never creates demo data on a deployed system' {
        ConvertTo-ChalklineProperties -Config (New-ValidConfig) | Should -Match '(?m)^app\.demo\.email=$'
    }

    It 'uses an absolute, forward-slashed path for the built-in database' {
        ConvertTo-ChalklineProperties -Config (New-ValidConfig) |
            Should -Match '(?m)^spring\.datasource\.url=jdbc:h2:file:C:/ProgramData/Chalkline/data/chalkline-db$'
    }

    It 'builds a PostgreSQL URL when PostgreSQL is chosen' {
        $config = New-ValidConfig
        $config.DatabaseType = 'PostgreSQL'; $config.DbHost = 'db.atu.local'; $config.DbPort = 5433
        $config.DbName = 'chalk'; $config.DbUser = 'chalk'; $config.DbPassword = 'dbsecret'
        $text = ConvertTo-ChalklineProperties -Config $config
        $text | Should -Match '(?m)^spring\.datasource\.url=jdbc:postgresql://db\.atu\.local:5433/chalk$'
        $text | Should -Match '(?m)^spring\.datasource\.username=chalk$'
    }

    It 'masks every password in a preview' {
        $config = New-ValidConfig
        $config.DatabaseType = 'PostgreSQL'; $config.DbHost = 'db'; $config.DbUser = 'u'; $config.DbPassword = 'dbsecret'
        $preview = ConvertTo-ChalklineProperties -Config $config -MaskSecrets
        $preview | Should -Not -Match 'first-run-pass'
        $preview | Should -Not -Match 'dbsecret'
        $preview | Should -Match '(?m)^app\.setup\.admin-password=\*{8}$'
    }

    It 'can leave the administrator password out entirely' {
        ConvertTo-ChalklineProperties -Config (New-ValidConfig) -OmitAdminPassword |
            Should -Not -Match 'admin-password'
    }

    It 'falls back to the administrator''s email for support' {
        ConvertTo-ChalklineProperties -Config (New-ValidConfig) |
            Should -Match '(?m)^app\.support-email=it@northfield\.example$'
    }
}

Describe 'Removing the administrator password after first start' {

    It 'removes the key and the comment that introduced it, and nothing else' {
        $text = ConvertTo-ChalklineProperties -Config (New-ValidConfig)
        $after = Remove-PropertiesKey -Text $text -Key 'app.setup.admin-password'
        $after | Should -Not -Match 'admin-password'
        $after | Should -Not -Match 'first-run-pass'
        $after | Should -Not -Match 'Removed from this file automatically'
        $after | Should -Match '(?m)^app\.setup\.admin-email=it@northfield\.example$'
        $after | Should -Match '(?m)^app\.setup\.seed-sample-catalogue=false$'
    }

    It 'recognises the line the application logs when it creates the institution' {
        Test-SetupLogged "INFO c.c.config.DataSeeder : Initial setup: institution 'ATU' created with administrator x" |
            Should -BeTrue
        Test-SetupLogged 'Started TimetableApplication in 9.1 seconds' | Should -BeFalse
    }

    It 'counts an institution that was already there as set up' {
        Test-SetupLogged 'INFO c.c.config.DataSeeder : Initial setup: an institution already exists, so nothing was created.' |
            Should -BeTrue
    }
}

Describe 'Validating the form' {

    It 'accepts a complete configuration' {
        @(Test-ChalklineConfig -Config (New-ValidConfig) -FreshInstall).Count | Should -Be 0
    }

    It 'reports a single problem as exactly one item' {
        $config = New-ValidConfig; $config.InstitutionName = ''
        $problems = @(Test-ChalklineConfig -Config $config -FreshInstall)
        $problems.Count | Should -Be 1
        $problems[0] | Should -Match 'institution name'
    }

    It 'refuses localhost, which nobody else could reach' {
        foreach ($bad in @('localhost', '127.0.0.1')) {
            $config = New-ValidConfig; $config.ServerAddress = $bad
            @(Test-ChalklineConfig -Config $config) -join ' ' | Should -Match 'cannot be localhost'
        }
    }

    It 'refuses a URL where a computer name belongs' {
        $config = New-ValidConfig; $config.ServerAddress = 'http://ATU-LAB-01/'
        @(Test-ChalklineConfig -Config $config) -join ' ' | Should -Match 'no http://'
    }

    It 'refuses ports outside 1024-65535' {
        foreach ($bad in @(80, 0, 70000, 'eighty')) {
            $config = New-ValidConfig; $config.Port = $bad
            @(Test-ChalklineConfig -Config $config) -join ' ' | Should -Match 'port must be'
        }
    }

    It 'needs the administrator password only for a new installation' {
        $config = New-ValidConfig; $config.AdminPassword = ''
        @(Test-ChalklineConfig -Config $config -FreshInstall) -join ' ' | Should -Match 'Enter a password'
        @(Test-ChalklineConfig -Config $config).Count | Should -Be 0
    }

    It 'refuses a short administrator password' {
        $config = New-ValidConfig; $config.AdminPassword = 'short'
        @(Test-ChalklineConfig -Config $config -FreshInstall) -join ' ' | Should -Match 'at least 8'
    }

    It 'refuses a malformed email' {
        $config = New-ValidConfig; $config.AdminEmail = 'not-an-email'
        @(Test-ChalklineConfig -Config $config) -join ' ' | Should -Match 'does not look like'
    }

    It 'needs the PostgreSQL details when PostgreSQL is chosen' {
        $config = New-ValidConfig; $config.DatabaseType = 'PostgreSQL'; $config.DbUser = ''
        $problems = @(Test-ChalklineConfig -Config $config) -join ' '
        $problems | Should -Match 'PostgreSQL server'
        $problems | Should -Match 'PostgreSQL user'
    }

    It 'refuses to put the database on a network share' {
        $config = New-ValidConfig; $config.InstallRoot = '\\fileserver\share\chalkline'
        @(Test-ChalklineConfig -Config $config) -join ' ' | Should -Match 'not a network share'
    }

    It 'checks the backup time looks like a time' {
        $config = New-ValidConfig; $config.BackupTime = '2am'
        @(Test-ChalklineConfig -Config $config) -join ' ' | Should -Match 'backup time'
    }

    It 'needs a jar file when installing from a file' {
        $config = New-ValidConfig; $config.JarSource = 'File'; $config.JarPath = ''
        @(Test-ChalklineConfig -Config $config) -join ' ' | Should -Match 'chalkline\.jar'
    }
}

Describe 'Addresses and versions' {

    It 'leaves the port out of the address when it is 80' {
        $config = New-ValidConfig; $config.Port = 80
        Get-ChalklinePublicUrl -Config $config | Should -Be 'http://ATU-LAB-01'
    }

    It 'includes any other port' {
        Get-ChalklinePublicUrl -Config (New-ValidConfig) | Should -Be 'http://ATU-LAB-01:8080'
    }

    It 'maps the processor to Adoptium''s name for it' {
        Get-JavaArchitecture -Architecture 'AMD64' -Wow64Architecture '' | Should -Be 'x64'
        Get-JavaArchitecture -Architecture 'ARM64' -Wow64Architecture '' | Should -Be 'aarch64'
    }

    It 'sees through 32-bit PowerShell on 64-bit Windows' {
        Get-JavaArchitecture -Architecture 'x86' -Wow64Architecture 'AMD64' | Should -Be 'x64'
    }

    It 'gives up on a 32-bit-only machine rather than guessing' {
        Get-JavaArchitecture -Architecture 'x86' -Wow64Architecture '' | Should -BeNullOrEmpty
    }

    It 'reads the major version from a JDK release file' {
        Get-JavaMajorVersion 'JAVA_VERSION="21.0.5"' | Should -Be 21
        Get-JavaMajorVersion 'JAVA_VERSION="17.0.12"' | Should -Be 17
    }

    It 'reads it from java -version output too, including the old 1.8 style' {
        Get-JavaMajorVersion 'openjdk version "21.0.5" 2024-10-15 LTS' | Should -Be 21
        Get-JavaMajorVersion 'java version "1.8.0_421"' | Should -Be 8
    }

    It 'returns 0 when it cannot tell' {
        Get-JavaMajorVersion 'nothing useful here' | Should -Be 0
    }
}

Describe 'The command line the scheduled task runs' {

    BeforeAll { $script:argLine = Get-ChalklineJavaArgument -Config (New-ValidConfig) }

    It 'activates the production profile' {
        $script:argLine | Should -Match '--spring\.profiles\.active=prod'
    }

    It 'points Spring at the config folder, with the trailing slash that makes it a folder' {
        $script:argLine | Should -Match '--spring\.config\.additional-location=file:C:/ProgramData/Chalkline/config/(\s|$)'
    }

    It 'quotes the jar path, so an install folder with spaces still works' {
        $config = New-ValidConfig; $config.InstallRoot = 'D:\School Systems\Chalkline'
        Get-ChalklineJavaArgument -Config $config | Should -Match '-jar "D:\\School Systems\\Chalkline[\\/]app[\\/]chalkline\.jar"'
    }

    It 'caps the memory, so it cannot take over a lab PC' {
        $script:argLine | Should -Match '-Xmx768m'
    }
}

Describe 'Saved answers' {

    It 'never saves a password' {
        $config = New-ValidConfig
        $config.DatabaseType = 'PostgreSQL'; $config.DbPassword = 'dbsecret'
        $json = ConvertTo-SavedSettingsJson -Config $config -SetupCompleted $true
        $json | Should -Not -Match 'first-run-pass'
        $json | Should -Not -Match 'dbsecret'
        $json | Should -Not -Match 'Password'
        ($json | ConvertFrom-Json).SetupCompleted | Should -BeTrue
    }
}

Describe 'The nightly backup script' {

    It 'is valid PowerShell' {
        $script = Get-ChalklineBackupScript -Config (New-ValidConfig)
        $errors = $null
        $null = [System.Management.Automation.Language.Parser]::ParseInput($script, [ref]$null, [ref]$errors)
        $errors.Count | Should -Be 0
    }

    It 'stops Chalkline before copying, and starts it again whatever happens' {
        $script = Get-ChalklineBackupScript -Config (New-ValidConfig)
        $script | Should -Match 'Stop-ScheduledTask'
        $script | Should -Match '(?s)finally\s*\{.*Start-ScheduledTask'
    }

    It 'keeps only the number of backups asked for' {
        $config = New-ValidConfig; $config.BackupKeep = 7
        Get-ChalklineBackupScript -Config $config | Should -Match '\$keep\s*=\s*7'
    }
}

Describe 'Previewing a deployment' {

    # The whole deployment path, end to end, with -Preview. This is the test
    # that would have caught a local $preview variable silently overwriting
    # the -Preview switch: PowerShell variable names ignore case.
    It 'reports every step, masks passwords, and changes nothing' {
        $jar = Join-Path $TestDrive 'chalkline.jar'
        Set-Content -LiteralPath $jar -Value 'stand-in for the real jar'
        $root = 'C:\ChalklinePreviewTest-' + [guid]::NewGuid().ToString('N').Substring(0, 8)

        $config = New-ValidConfig
        $config.InstallRoot = $root
        $config.JarSource = 'File'
        $config.JarPath = $jar

        $lines = New-Object System.Collections.Generic.List[string]
        $result = Invoke-ChalklineDeploy -Config $config -Preview -Log { param($m) $lines.Add([string]$m) }
        $all = $lines -join "`n"

        $result.Preview | Should -BeTrue
        foreach ($step in 1..8) { $all | Should -Match "$step/9" }
        $all | Should -Match 'Nothing on this computer has been changed'
        $all | Should -Match 'app\.setup\.admin-password=\*{8}'
        $all | Should -Not -Match 'first-run-pass'
        Test-Path -LiteralPath $root | Should -BeFalse
    }

    It 'stops before changing anything when the settings are wrong' {
        $config = New-ValidConfig
        $config.ServerAddress = 'localhost'
        $lines = New-Object System.Collections.Generic.List[string]
        { Invoke-ChalklineDeploy -Config $config -Preview -Log { param($m) $lines.Add([string]$m) } } |
            Should -Throw '*Fix the settings*'
        ($lines -join "`n") | Should -Match 'cannot be localhost'
        ($lines -join "`n") | Should -Not -Match '2/9'
    }
}

Describe 'Reading the health check' {

    # Windows PowerShell 5.1 returns the body as bytes for Spring's
    # application/vnd.spring-boot.actuator.v3+json. Found by deploying on
    # Windows 11: the server was up and every check said it was not.
    It 'understands a body that arrives as bytes' {
        $bytes = [Text.Encoding]::UTF8.GetBytes('{"status":"UP"}')
        Test-HealthResponse -StatusCode 200 -Content $bytes | Should -BeTrue
    }

    It 'understands a body that arrives as text' {
        Test-HealthResponse -StatusCode 200 -Content '{"status":"UP"}' | Should -BeTrue
    }

    It 'treats anything but 200 as down' {
        Test-HealthResponse -StatusCode 503 -Content '{"status":"UP"}' | Should -BeFalse
    }

    It 'treats a DOWN status as down' {
        Test-HealthResponse -StatusCode 200 -Content '{"status":"DOWN"}' | Should -BeFalse
    }
}

Describe 'Writing files without losing characters' {

    # Found on Windows: the saved answers were written with an encoder that
    # silently turns every accented letter into "?", so the form came back
    # pre-filled with "Universit? de Test Abetifi".
    It 'refuses to write non-ASCII as ASCII, instead of silently writing "?"' {
        $file = Join-Path $TestDrive 'strict.txt'
        { Write-ChalklineTextFile -Path $file -Text 'Universit' + [char]0xE9 } | Should -Throw
    }

    It 'writes ASCII content exactly' {
        $file = Join-Path $TestDrive 'ascii.txt'
        Write-ChalklineTextFile -Path $file -Text "a=b`nc=d"
        [IO.File]::ReadAllText($file) | Should -BeExactly "a=b`r`nc=d"
    }

    It 'saves and reloads the answers with accents intact' {
        $root = Join-Path $TestDrive 'root'
        $null = New-Item -ItemType Directory -Path $root
        $config = New-ValidConfig
        $config.InstallRoot = $root
        $config.InstitutionName = 'Universit' + [char]0xE9 + ' F' + [char]0xE9 + 'lix Houphou' + [char]0xEB + 't-Boigny'
        $json = ConvertTo-SavedSettingsJson -Config $config -SetupCompleted $true

        # Pure ASCII, so no encoding a later reader picks can damage it.
        [Text.Encoding]::ASCII.GetString([Text.Encoding]::ASCII.GetBytes($json)) | Should -BeExactly $json
        Write-ChalklineTextFile -Path (Join-Path $root 'deploy-settings.json') -Text $json

        $loaded = Get-SavedChalklineSettings -Root $root
        $loaded.InstitutionName | Should -BeExactly $config.InstitutionName
    }

    It 'writes PowerShell scripts as UTF-8 with a byte-order mark, which Windows PowerShell needs' {
        $file = Join-Path $TestDrive 'script.ps1'
        Write-ChalklineTextFile -Path $file -Text ('# Universit' + [char]0xE9) -Encoding Utf8Bom
        $bytes = [IO.File]::ReadAllBytes($file)
        ($bytes[0..2] -join ',') | Should -Be '239,187,191'
        [Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3) | Should -BeExactly ('# Universit' + [char]0xE9)
    }
}

Describe 'Network classification' {

    BeforeAll {
        # Get-NetConnectionProfile only exists on Windows; give the mock
        # something to replace everywhere else.
        if (-not (Get-Command Get-NetConnectionProfile -ErrorAction SilentlyContinue)) {
            function global:Get-NetConnectionProfile { }
        }
    }

    # One category, "Public", is exactly what the test VM had. Returned
    # with the comma operator AND wrapped in @( ) by the caller, it nested,
    # and the form printed "System.Object[]".
    It 'reports a single network category as one plain string' {
        Mock -ModuleName ChalklineDeploy Get-NetConnectionProfile { [pscustomobject]@{ NetworkCategory = 'Public' } }
        $c = @(Get-ChalklineNetworkCategory)
        $c.Count | Should -Be 1
        $c[0] | Should -BeOfType [string]
        ($c -join ', ') | Should -Be 'Public'
    }

    It 'reports several categories as separate strings' {
        Mock -ModuleName ChalklineDeploy Get-NetConnectionProfile {
            [pscustomobject]@{ NetworkCategory = 'Private' }; [pscustomobject]@{ NetworkCategory = 'Public' } }
        ((@(Get-ChalklineNetworkCategory)) -join ', ') | Should -Be 'Private, Public'
    }

    # What a domain-joined school PC reports. The form shows it next to the
    # Domain, Private and Public firewall boxes, so it must use the same name.
    It 'reports a domain network as Domain, the firewall profile name' {
        Mock -ModuleName ChalklineDeploy Get-NetConnectionProfile {
            [pscustomobject]@{ NetworkCategory = 'DomainAuthenticated' }; [pscustomobject]@{ NetworkCategory = 'Private' } }
        ((@(Get-ChalklineNetworkCategory)) -join ', ') | Should -Be 'Domain, Private'
    }

    It 'warns when the network is Public and the firewall rule would not cover it' {
        Mock -ModuleName ChalklineDeploy Get-NetConnectionProfile { [pscustomobject]@{ NetworkCategory = 'Public' } }
        $jar = Join-Path $TestDrive 'w.jar'; Set-Content $jar 'x'
        $config = New-ValidConfig; $config.JarSource = 'File'; $config.JarPath = $jar
        $config.InstallRoot = 'C:\ChalklineWarnTest'; $config.FirewallProfiles = @('Domain', 'Private')
        $lines = New-Object System.Collections.Generic.List[string]
        $null = Invoke-ChalklineDeploy -Config $config -Preview -Log { param($m) $lines.Add([string]$m) }
        ($lines -join "`n") | Should -Match 'classified this network as Public'
    }

    It 'does not warn once Public is included' {
        Mock -ModuleName ChalklineDeploy Get-NetConnectionProfile { [pscustomobject]@{ NetworkCategory = 'Public' } }
        $jar = Join-Path $TestDrive 'w2.jar'; Set-Content $jar 'x'
        $config = New-ValidConfig; $config.JarSource = 'File'; $config.JarPath = $jar
        $config.InstallRoot = 'C:\ChalklineWarnTest'; $config.FirewallProfiles = @('Domain', 'Private', 'Public')
        $lines = New-Object System.Collections.Generic.List[string]
        $null = Invoke-ChalklineDeploy -Config $config -Preview -Log { param($m) $lines.Add([string]$m) }
        ($lines -join "`n") | Should -Not -Match 'classified this network as Public'
    }
}

Describe 'Waiting for first-run setup' {

    # Chalkline creates the institution just after its web server starts
    # answering, so the health check passes first. Reading the log once at that
    # moment found nothing on a real fresh install, and the starting password
    # was left in the settings file. These tests make the line arrive late.

    BeforeAll {
        $script:started = '2026-10-02T11:24:46.123+02:00  INFO 4321 --- [Chalkline] [main] com.chalkline.ChalklineApplication : Started ChalklineApplication in 21.9 seconds'
        $script:created = "2026-10-02T11:24:46.456+02:00  INFO 4321 --- [Chalkline] [main] com.chalkline.config.DataSeeder : Initial setup: institution 'Abetifi Technical University' created with administrator it@northfield.example on the Premium plan (must choose a new password at first sign-in)"
        $script:refused = '2026-10-02T11:24:46.456+02:00  WARN 4321 --- [Chalkline] [main] com.chalkline.config.DataSeeder : Initial setup: the administrator password is missing or shorter than 8 characters. Nothing created.'
        $script:exists = '2026-10-02T11:24:46.456+02:00  INFO 4321 --- [Chalkline] [main] com.chalkline.config.DataSeeder : Initial setup: an institution already exists, so nothing was created.'
    }

    It 'finds the institution when the line appears after the first look' {
        $log = Join-Path $TestDrive 'late.log'
        Set-Content -LiteralPath $log -Value $script:started -Encoding UTF8
        # The first wait is when Chalkline gets round to writing it.
        Mock -ModuleName ChalklineDeploy Start-Sleep { Add-Content -LiteralPath $log -Value $script:created -Encoding UTF8 }
        $r = Wait-ChalklineSetup -LogPath $log -TimeoutSeconds 10
        $r.Outcome | Should -Be 'Created'
        Should -Invoke -ModuleName ChalklineDeploy Start-Sleep -Times 1 -Exactly
    }

    It 'reports an institution that was already there' {
        $log = Join-Path $TestDrive 'exists.log'
        Set-Content -LiteralPath $log -Value @($script:started, $script:exists) -Encoding UTF8
        (Wait-ChalklineSetup -LogPath $log -TimeoutSeconds 5).Outcome | Should -Be 'Exists'
    }

    It 'reports a refusal straight away, with the reason' {
        $log = Join-Path $TestDrive 'refused.log'
        Set-Content -LiteralPath $log -Value @($script:started, $script:refused) -Encoding UTF8
        $r = Wait-ChalklineSetup -LogPath $log -TimeoutSeconds 5
        $r.Outcome | Should -Be 'Refused'
        $r.Detail | Should -Be 'the administrator password is missing or shorter than 8 characters. Nothing created.'
    }

    It 'ignores what an earlier attempt left in the log' {
        $log = Join-Path $TestDrive 'earlier.log'
        Set-Content -LiteralPath $log -Value $script:refused -Encoding UTF8
        $offset = (Get-Content -LiteralPath $log -Raw -Encoding UTF8).Length
        Mock -ModuleName ChalklineDeploy Start-Sleep { }
        (Wait-ChalklineSetup -LogPath $log -FromOffset $offset -TimeoutSeconds 1).Outcome | Should -Be 'Unknown'

        Add-Content -LiteralPath $log -Value $script:created -Encoding UTF8
        (Wait-ChalklineSetup -LogPath $log -FromOffset $offset -TimeoutSeconds 1).Outcome | Should -Be 'Created'
    }

    It 'reads all of a log that was rotated while it waited' {
        $log = Join-Path $TestDrive 'rotated.log'
        Set-Content -LiteralPath $log -Value $script:created -Encoding UTF8
        (Wait-ChalklineSetup -LogPath $log -FromOffset 999999 -TimeoutSeconds 1).Outcome | Should -Be 'Created'
    }

    It 'gives up, rather than hanging, when there is no log at all' {
        Mock -ModuleName ChalklineDeploy Start-Sleep { }
        (Wait-ChalklineSetup -LogPath (Join-Path $TestDrive 'missing.log') -TimeoutSeconds 1).Outcome | Should -Be 'Unknown'
    }
}

Describe 'Recording a deployment in deploy.log' {

    It 'appends each deployment as its own block, with one byte order mark at the start' {
        $logs = Join-Path $TestDrive 'logs'; $null = New-Item -ItemType Directory -Path $logs
        $path = Join-Path $logs 'deploy.log'
        Add-ChalklineDeployLog -Path $path -Started (Get-Date '2026-10-02 09:00:00') -Lines @('1/9  Checking the settings', 'Deployed.')
        Add-ChalklineDeployLog -Path $path -Started (Get-Date '2026-10-02 10:00:00') -Lines @('1/9  Checking the settings', 'PROBLEM: it broke')

        $bytes = [IO.File]::ReadAllBytes($path)
        $bytes[0..2] | Should -Be @(0xEF, 0xBB, 0xBF)
        $text = [Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
        $text | Should -Not -Match ([char]0xFEFF)
        $text | Should -Match '===== Deployment started 2026-10-02 09:00:00 ====='
        $text | Should -Match '===== Deployment started 2026-10-02 10:00:00 ====='
        $text | Should -Match 'PROBLEM: it broke'
    }

    It 'keeps an accented institution name intact' {
        $logs = Join-Path $TestDrive 'logs2'; $null = New-Item -ItemType Directory -Path $logs
        $path = Join-Path $logs 'deploy.log'
        $name = 'Universit' + [char]0xE9 + ' F' + [char]0xE9 + 'lix Houphou' + [char]0xEB + 't-Boigny'
        Add-ChalklineDeployLog -Path $path -Started (Get-Date) -Lines @("  Created '$name' with administrator x")
        (Get-Content -LiteralPath $path -Raw -Encoding UTF8) | Should -Match ([regex]::Escape($name))
    }

    It 'does nothing when the logs folder does not exist yet' {
        $path = Join-Path $TestDrive 'not-created\deploy.log'
        { Add-ChalklineDeployLog -Path $path -Started (Get-Date) -Lines @('x') } | Should -Not -Throw
        Test-Path -LiteralPath $path | Should -BeFalse
    }

    It 'a preview writes nothing to it' {
        Mock -ModuleName ChalklineDeploy Add-ChalklineDeployLog { }
        $jar = Join-Path $TestDrive 'p.jar'; Set-Content -LiteralPath $jar -Value 'x'
        $config = New-ValidConfig; $config.JarSource = 'File'; $config.JarPath = $jar
        $config.InstallRoot = 'C:\ChalklineLogPreviewTest'
        $null = Invoke-ChalklineDeploy -Config $config -Preview -Log { param($m) }
        Should -Invoke -ModuleName ChalklineDeploy Add-ChalklineDeployLog -Times 0 -Exactly
    }

    It 'a deployment that stops is still recorded, with the reason' {
        Mock -ModuleName ChalklineDeploy Add-ChalklineDeployLog { }
        $config = New-ValidConfig; $config.InstitutionName = ''
        $shown = New-Object System.Collections.Generic.List[string]
        { Invoke-ChalklineDeploy -Config $config -Log { param($m) $shown.Add([string]$m) } } | Should -Throw
        # What the form showed is exactly what was recorded, plus the reason it stopped.
        $shown | Should -Contain '1/9  Checking the settings'
        Should -Invoke -ModuleName ChalklineDeploy Add-ChalklineDeployLog -Times 1 -Exactly -ParameterFilter {
            ($Lines -contains '1/9  Checking the settings') -and
            (($Lines -join "`n") -match 'PROBLEM: Fix the settings above, then deploy again\.')
        }
    }
}
