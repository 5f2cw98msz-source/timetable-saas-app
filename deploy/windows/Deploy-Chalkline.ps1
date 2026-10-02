<#
    Chalkline Deployer: installs Chalkline on this computer so everyone on
    the school network can use it from a browser.

    Start it with Deploy-Chalkline.cmd, which asks for administrator rights.
    Everything it does is in ChalklineDeploy.psm1; this file is only the form.

    Written for Windows PowerShell 5.1, which every Windows 10 and 11 machine has.
#>

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

function Show-Fatal([string]$Message) {
    [void][System.Windows.Forms.MessageBox]::Show($Message, 'Chalkline Deployer',
        [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error)
}

# Files from a downloaded ZIP carry a "came from the internet" mark. Clear it
# on our own files so Windows does not refuse to load the engine module.
Get-ChildItem -LiteralPath $here -Recurse -File -ErrorAction SilentlyContinue |
    Unblock-File -ErrorAction SilentlyContinue

# Relaunch as administrator if needed. Creating scheduled tasks and firewall
# rules, and writing to ProgramData, all need it.
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    try {
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden',
            '-File', "`"$($MyInvocation.MyCommand.Path)`"")
    } catch {
        Show-Fatal 'The Chalkline Deployer needs administrator rights, and permission was not given.'
    }
    exit
}

$modulePath = Join-Path $here 'ChalklineDeploy.psm1'
try {
    Import-Module $modulePath -Force
} catch {
    Show-Fatal "Could not load ChalklineDeploy.psm1 from $here.`n`n$($_.Exception.Message)"
    exit 1
}

# Windows PowerShell does not declare itself DPI-aware, so at 125% scaling and
# above Windows would stretch the whole form as a picture and blur the text.
# Declaring it here, before any window exists, lets the form draw sharply; it
# is scaled up from its 96 DPI design further down. If this fails the form
# still works, it is just less sharp.
try {
    Add-Type -Namespace ChalklineDeployer -Name Display -MemberDefinition '[DllImport("user32.dll")] public static extern bool SetProcessDPIAware();'
    [void][ChalklineDeployer.Display]::SetProcessDPIAware()
} catch { }

# ---------------------------------------------------------------------------
#  State shared between the form and the background worker
# ---------------------------------------------------------------------------
$script:LogQueue = New-Object System.Collections.Concurrent.ConcurrentQueue[string]
$script:Worker = $null
$script:LastUrl = $null

$saved = Get-SavedChalklineSettings
$start = if ($saved) { $saved } else { New-ChalklineConfig }
# Whether the next deployment creates the institution, and so needs a password.
$freshInstall = -not ($saved -and $saved.SetupCompleted)
$addresses = @(Get-ChalklineAddresses)
$categories = @(Get-ChalklineNetworkCategory)

# ---------------------------------------------------------------------------
#  Look and feel
# ---------------------------------------------------------------------------
$violet = [System.Drawing.Color]::FromArgb(108, 75, 244)
$coral  = [System.Drawing.Color]::FromArgb(255, 107, 74)
$ink    = [System.Drawing.Color]::FromArgb(22, 25, 43)
$soft   = [System.Drawing.Color]::FromArgb(90, 96, 122)
$amber  = [System.Drawing.Color]::FromArgb(154, 98, 0)
$ok     = [System.Drawing.Color]::FromArgb(15, 122, 77)
$font   = New-Object System.Drawing.Font('Segoe UI', 9.5)
$bold   = New-Object System.Drawing.Font('Segoe UI', 9.5, [System.Drawing.FontStyle]::Bold)
$small  = New-Object System.Drawing.Font('Segoe UI', 8.5)
$mono   = New-Object System.Drawing.Font('Consolas', 9)

function New-Label([string]$Text, [System.Drawing.Font]$Font = $font, $Colour = $ink) {
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $Text; $l.Font = $Font; $l.ForeColor = $Colour
    $l.AutoSize = $true; $l.Margin = New-Object System.Windows.Forms.Padding(0, 6, 8, 2)
    return $l
}

function New-Hint([string]$Text) {
    $l = New-Label $Text $small $soft
    $l.MaximumSize = New-Object System.Drawing.Size(560, 0)
    $l.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 6)
    return $l
}

function New-TextBox([string]$Name, [string]$Value = '', [switch]$Password, [int]$Width = 340) {
    $t = New-Object System.Windows.Forms.TextBox
    $t.Name = $Name; $t.Text = $Value; $t.Font = $font; $t.Width = $Width
    if ($Password) { $t.UseSystemPasswordChar = $true }
    $t.Margin = New-Object System.Windows.Forms.Padding(0, 3, 0, 3)
    return $t
}

function New-CheckBox([string]$Name, [string]$Text, [bool]$Checked) {
    $c = New-Object System.Windows.Forms.CheckBox
    $c.Name = $Name; $c.Text = $Text; $c.Checked = $Checked; $c.Font = $font
    $c.AutoSize = $true; $c.Margin = New-Object System.Windows.Forms.Padding(0, 6, 0, 0)
    return $c
}

function New-Button([string]$Name, [string]$Text, [switch]$Primary) {
    $b = New-Object System.Windows.Forms.Button
    $b.Name = $Name; $b.Text = $Text; $b.Font = $font; $b.AutoSize = $true
    # Buttons only grow by default, so after scaling to the screen's DPI they
    # would keep the doubled size instead of fitting their text again.
    $b.AutoSizeMode = 'GrowAndShrink'; $b.MinimumSize = New-Object System.Drawing.Size(75, 0)
    $b.Padding = New-Object System.Windows.Forms.Padding(10, 3, 10, 3)
    $b.Margin = New-Object System.Windows.Forms.Padding(0, 4, 8, 4)
    if ($Primary) {
        $b.BackColor = $violet; $b.ForeColor = [System.Drawing.Color]::White
        $b.FlatStyle = 'Flat'; $b.FlatAppearance.BorderSize = 0; $b.Font = $bold
    }
    return $b
}

# A vertical stack for each tab. Laid out by flow rather than by pixel
# position, so it does not clip on lab PCs running at 125% or 150% scaling.
function New-TabPage([string]$Title) {
    $page = New-Object System.Windows.Forms.TabPage
    $page.Text = $Title; $page.Font = $font; $page.BackColor = [System.Drawing.Color]::White
    $page.Padding = New-Object System.Windows.Forms.Padding(18, 12, 18, 12)
    $flow = New-Object System.Windows.Forms.FlowLayoutPanel
    $flow.Dock = 'Fill'; $flow.FlowDirection = 'TopDown'; $flow.WrapContents = $false; $flow.AutoScroll = $true
    $page.Controls.Add($flow)
    return @{ Page = $page; Flow = $flow }
}

function Add-Row($Flow, [System.Windows.Forms.Control[]]$Controls) {
    $row = New-Object System.Windows.Forms.FlowLayoutPanel
    $row.FlowDirection = 'LeftToRight'; $row.AutoSize = $true; $row.WrapContents = $false
    $row.Margin = New-Object System.Windows.Forms.Padding(0)
    foreach ($c in $Controls) { $row.Controls.Add($c) }
    $Flow.Controls.Add($row)
}

# ---------------------------------------------------------------------------
#  The form
# ---------------------------------------------------------------------------
$form = New-Object System.Windows.Forms.Form
$form.Name = 'ChalklineDeployer'
$form.Text = 'Chalkline Deployer'
$form.Size = New-Object System.Drawing.Size(820, 700)
$form.MinimumSize = New-Object System.Drawing.Size(720, 600)
$form.StartPosition = 'CenterScreen'
$form.Font = $font
$form.AutoScaleMode = 'None'   # scaled once, explicitly, after the controls are added
$form.BackColor = [System.Drawing.Color]::White

# Header with the product's violet-to-coral gradient.
$header = New-Object System.Windows.Forms.Panel
$header.Dock = 'Top'; $header.Height = 70
$header.Add_Paint({
    param($s, $e)
    $rect = $s.ClientRectangle
    if ($rect.Width -le 0) { return }
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $violet, $coral, 0.0)
    $e.Graphics.FillRectangle($brush, $rect); $brush.Dispose()
})
$title = New-Label 'Chalkline Deployer' (New-Object System.Drawing.Font('Segoe UI Semibold', 15)) ([System.Drawing.Color]::White)
$title.BackColor = [System.Drawing.Color]::Transparent; $title.Location = New-Object System.Drawing.Point(18, 9)
$subtitle = New-Label ("Installs Chalkline on this computer (" + [System.Net.Dns]::GetHostName() +
    ") for everyone on the school network.") $font ([System.Drawing.Color]::White)
$subtitle.BackColor = [System.Drawing.Color]::Transparent; $subtitle.Location = New-Object System.Drawing.Point(20, 42)
$header.Controls.AddRange(@($title, $subtitle))

$tabs = New-Object System.Windows.Forms.TabControl
$tabs.Name = 'Tabs'; $tabs.Dock = 'Fill'; $tabs.Font = $font

# ----- 1. Institution -------------------------------------------------------
$t1 = New-TabPage '1. Institution'
$t1.Flow.Controls.Add((New-Label 'Institution name' $bold))
$txtInstitution = New-TextBox 'InstitutionName' $start.InstitutionName -Width 420
$t1.Flow.Controls.Add($txtInstitution)
$t1.Flow.Controls.Add((New-Hint 'Shown to staff, and on any timetable you publish for students.'))

$t1.Flow.Controls.Add((New-Label 'Administrator' $bold))
$t1.Flow.Controls.Add((New-Label 'Full name'))
$txtAdminName = New-TextBox 'AdminName' $start.AdminName
$t1.Flow.Controls.Add($txtAdminName)
$t1.Flow.Controls.Add((New-Label 'Work email (used to sign in)'))
$txtAdminEmail = New-TextBox 'AdminEmail' $start.AdminEmail
$t1.Flow.Controls.Add($txtAdminEmail)
$t1.Flow.Controls.Add((New-Label 'Password'))
$txtPassword = New-TextBox 'AdminPassword' '' -Password
$t1.Flow.Controls.Add($txtPassword)
$t1.Flow.Controls.Add((New-Label 'Confirm password'))
$txtPassword2 = New-TextBox 'AdminPasswordConfirm' '' -Password
$t1.Flow.Controls.Add($txtPassword2)
$lblPasswordHint = New-Hint ''
$lblPasswordHint.Name = 'PasswordHint'
$t1.Flow.Controls.Add($lblPasswordHint)
function Update-PasswordHint {
    $lblPasswordHint.Text = if ($script:freshInstall) {
        'At least 8 characters. Used once, to create the account; the administrator chooses their own at first sign-in.'
    } else {
        'Already set up. Leave the password blank: it is only used to create the institution the first time.'
    }
}
Update-PasswordHint
$t1.Flow.Controls.Add((New-Label 'Support email (optional)'))
$txtSupport = New-TextBox 'SupportEmail' $start.SupportEmail
$t1.Flow.Controls.Add($txtSupport)
$t1.Flow.Controls.Add((New-Hint 'Shown to staff who need help. Defaults to the administrator''s email.'))

# ----- 2. Network -----------------------------------------------------------
$t2 = New-TabPage '2. Network'
$t2.Flow.Controls.Add((New-Label 'Address lecturers will use to reach this computer' $bold))
$cmbAddress = New-Object System.Windows.Forms.ComboBox
$cmbAddress.Name = 'ServerAddress'; $cmbAddress.Width = 340; $cmbAddress.Font = $font; $cmbAddress.DropDownStyle = 'DropDown'
foreach ($a in $addresses) { [void]$cmbAddress.Items.Add($a.Address) }
$cmbAddress.Text = if ($start.ServerAddress) { $start.ServerAddress } elseif ($addresses.Count -gt 0) { $addresses[0].Address } else { '' }
$t2.Flow.Controls.Add($cmbAddress)
$t2.Flow.Controls.Add((New-Hint ('The computer name keeps working if the IP address changes. Use an IP address if lecturers ' +
    'cannot reach the name. Detected: ' + (($addresses | ForEach-Object { "$($_.Address) ($($_.Kind))" }) -join ', ') + '.')))

$t2.Flow.Controls.Add((New-Label 'Port' $bold))
$numPort = New-Object System.Windows.Forms.NumericUpDown
$numPort.Name = 'Port'; $numPort.Minimum = 1024; $numPort.Maximum = 65535; $numPort.Value = [int]$start.Port; $numPort.Width = 100
$t2.Flow.Controls.Add($numPort)

$lblUrl = New-Label '' $bold $violet
$lblUrl.Name = 'UrlPreview'
$lblUrl.Margin = New-Object System.Windows.Forms.Padding(0, 10, 0, 8)
$t2.Flow.Controls.Add($lblUrl)

$t2.Flow.Controls.Add((New-Label 'Firewall' $bold))
$chkFirewall = New-CheckBox 'OpenFirewall' 'Let other computers on the network connect (opens the Windows firewall for this port)' $start.OpenFirewall
$t2.Flow.Controls.Add($chkFirewall)
$chkDomain  = New-CheckBox 'ProfileDomain'  'Domain'  (@($start.FirewallProfiles) -contains 'Domain')
$chkPrivate = New-CheckBox 'ProfilePrivate' 'Private' (@($start.FirewallProfiles) -contains 'Private')
$chkPublic  = New-CheckBox 'ProfilePublic'  'Public'  (@($start.FirewallProfiles) -contains 'Public' -or $categories -contains 'Public')
Add-Row $t2.Flow @((New-Label 'Apply to network types:'), $chkDomain, $chkPrivate, $chkPublic)
$catText = if ($categories.Count -gt 0) { 'Windows currently classifies this network as: ' + ($categories -join ', ') + '.' } else { 'Could not tell how Windows classifies this network.' }
$lblCategory = New-Hint $catText
$t2.Flow.Controls.Add($lblCategory)
if ($categories -contains 'Public') {
    $warn = New-Hint ('This network is classified as Public, which is common on computers not joined to a domain. ' +
        'Public is ticked above so lecturers can connect; without it the firewall rule would not apply.')
    $warn.ForeColor = $amber
    $t2.Flow.Controls.Add($warn)
}
$chkSubnet = New-CheckBox 'LocalSubnetOnly' 'Only allow computers on this network segment (leave off if staff are on a different subnet)' $start.LocalSubnetOnly
$t2.Flow.Controls.Add($chkSubnet)

# ----- 3. Database ----------------------------------------------------------
$t3 = New-TabPage '3. Database'
$radH2 = New-Object System.Windows.Forms.RadioButton
$radH2.Name = 'DatabaseH2'; $radH2.Text = 'Built-in database (recommended for one school)'; $radH2.AutoSize = $true; $radH2.Font = $bold
$radH2.Checked = ($start.DatabaseType -ne 'PostgreSQL')
$t3.Flow.Controls.Add($radH2)
$t3.Flow.Controls.Add((New-Hint 'Stored in the install folder on this computer, with nothing else to install. Backed up every night if you choose that under Options.'))
$radPg = New-Object System.Windows.Forms.RadioButton
$radPg.Name = 'DatabasePostgres'; $radPg.Text = 'An existing PostgreSQL server'; $radPg.AutoSize = $true; $radPg.Font = $bold
$radPg.Checked = ($start.DatabaseType -eq 'PostgreSQL')
$radPg.Margin = New-Object System.Windows.Forms.Padding(0, 12, 0, 0)
$t3.Flow.Controls.Add($radPg)
$t3.Flow.Controls.Add((New-Hint 'For a university with a database team. Chalkline creates its own tables in the database you give it.'))

$pgPanel = New-Object System.Windows.Forms.TableLayoutPanel
$pgPanel.ColumnCount = 2; $pgPanel.AutoSize = $true
$txtDbHost = New-TextBox 'DbHost' $start.DbHost -Width 260
$numDbPort = New-Object System.Windows.Forms.NumericUpDown
$numDbPort.Name = 'DbPort'; $numDbPort.Minimum = 1; $numDbPort.Maximum = 65535; $numDbPort.Value = [int]$start.DbPort; $numDbPort.Width = 90
$txtDbName = New-TextBox 'DbName' $start.DbName -Width 260
$txtDbUser = New-TextBox 'DbUser' $start.DbUser -Width 260
$txtDbPass = New-TextBox 'DbPassword' '' -Password -Width 260
foreach ($pair in @(@('Server', $txtDbHost), @('Port', $numDbPort), @('Database', $txtDbName), @('User', $txtDbUser), @('Password', $txtDbPass))) {
    $pgPanel.Controls.Add((New-Label $pair[0]))
    $pgPanel.Controls.Add($pair[1])
}
$t3.Flow.Controls.Add($pgPanel)
$btnTestDb = New-Button 'TestDatabase' 'Test that the server can be reached'
$lblDb = New-Hint ''
$lblDb.Name = 'DatabaseResult'
Add-Row $t3.Flow @($btnTestDb, $lblDb)

# ----- 4. Options -----------------------------------------------------------
$t4 = New-TabPage '4. Options'
$chkPremium = New-CheckBox 'IncludePremium' 'Include every Premium feature (recommended for your own institution)' $start.IncludePremium
$t4.Flow.Controls.Add($chkPremium)
$t4.Flow.Controls.Add((New-Hint 'Public timetables for students, calendar subscriptions, the API and no limit on staff. No payment is involved.'))
$chkSamples = New-CheckBox 'SeedSamples' 'Add a few sample rooms and courses to start with' $start.SeedSampleCatalogue
$t4.Flow.Controls.Add($chkSamples)
$chkSignUp = New-CheckBox 'AllowSignUp' 'Let other institutions sign up (leave off when this serves one school)' $start.AllowSignUp
$t4.Flow.Controls.Add($chkSignUp)
$chkStartup = New-CheckBox 'StartWithWindows' 'Start Chalkline automatically when this computer starts' $start.StartWithWindows
$t4.Flow.Controls.Add($chkStartup)

$chkBackup = New-CheckBox 'NightlyBackup' 'Back up the database every night' $start.NightlyBackup
$txtBackupTime = New-TextBox 'BackupTime' $start.BackupTime -Width 60
$numKeep = New-Object System.Windows.Forms.NumericUpDown
$numKeep.Name = 'BackupKeep'; $numKeep.Minimum = 1; $numKeep.Maximum = 365; $numKeep.Value = [int]$start.BackupKeep; $numKeep.Width = 60
Add-Row $t4.Flow @($chkBackup, (New-Label '  at'), $txtBackupTime, (New-Label 'keeping the last'), $numKeep)
$t4.Flow.Controls.Add((New-Hint 'Chalkline stops for a few seconds while the copy is taken, so the backup is never half-written. Built-in database only.'))

$t4.Flow.Controls.Add((New-Label 'Install folder' $bold))
$txtRoot = New-TextBox 'InstallRoot' $start.InstallRoot -Width 420
$btnBrowseRoot = New-Button 'BrowseRoot' 'Browse...'
Add-Row $t4.Flow @($txtRoot, $btnBrowseRoot)
$t4.Flow.Controls.Add((New-Hint 'Holds the application, its settings, the database and the backups. Must be on a local disk.'))

$t4.Flow.Controls.Add((New-Label 'Where Chalkline comes from' $bold))
$sourceDir = try { Get-ChalklineSourceDir } catch { '' }
$hasSource = $sourceDir -and (Test-Path -LiteralPath (Join-Path $sourceDir 'mvnw.cmd'))
$radBuild = New-Object System.Windows.Forms.RadioButton
$radBuild.Name = 'JarBuild'; $radBuild.AutoSize = $true; $radBuild.Font = $font
$radBuild.Text = 'Build it from the source code next to this tool (needs the internet the first time)'
$radBuild.Checked = ($start.JarSource -ne 'File') -and $hasSource
$radBuild.Enabled = $hasSource
$t4.Flow.Controls.Add($radBuild)
$radFile = New-Object System.Windows.Forms.RadioButton
$radFile.Name = 'JarFile'; $radFile.AutoSize = $true; $radFile.Font = $font
$radFile.Text = 'Use a chalkline.jar I already have (for a computer with no internet)'
$radFile.Checked = -not $radBuild.Checked
$t4.Flow.Controls.Add($radFile)
$txtJar = New-TextBox 'JarPath' $start.JarPath -Width 420
$btnBrowseJar = New-Button 'BrowseJar' 'Browse...'
Add-Row $t4.Flow @($txtJar, $btnBrowseJar)

# ----- 5. Deploy ------------------------------------------------------------
$t5 = New-TabPage '5. Deploy'
$t5.Flow.Dispose(); $t5.Page.Controls.Clear()
$deployLayout = New-Object System.Windows.Forms.TableLayoutPanel
$deployLayout.Dock = 'Fill'; $deployLayout.ColumnCount = 1; $deployLayout.RowCount = 4
[void]$deployLayout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('AutoSize')))
[void]$deployLayout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('AutoSize')))
[void]$deployLayout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Percent', 100)))
[void]$deployLayout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('AutoSize')))

$actions = New-Object System.Windows.Forms.FlowLayoutPanel
$actions.AutoSize = $true; $actions.Dock = 'Fill'
$btnCheck   = New-Button 'Check'   'Check this computer'
$btnPreview = New-Button 'Preview' 'Preview the changes'
$btnDeploy  = New-Button 'Deploy'  'Deploy Chalkline' -Primary
$actions.Controls.AddRange(@($btnCheck, $btnPreview, $btnDeploy))
$deployLayout.Controls.Add($actions)

$resultRow = New-Object System.Windows.Forms.FlowLayoutPanel
$resultRow.AutoSize = $true; $resultRow.Dock = 'Fill'
$lblResult = New-Label '' $bold $ok
$lblResult.Name = 'Result'
$btnOpen = New-Button 'OpenChalkline' 'Open Chalkline'
$btnCopy = New-Button 'CopyAddress' 'Copy the address'
$btnOpen.Visible = $false; $btnCopy.Visible = $false
$resultRow.Controls.AddRange(@($lblResult, $btnOpen, $btnCopy))
$deployLayout.Controls.Add($resultRow)

$txtLog = New-Object System.Windows.Forms.TextBox
$txtLog.Name = 'Log'; $txtLog.Multiline = $true; $txtLog.ReadOnly = $true; $txtLog.ScrollBars = 'Vertical'
$txtLog.Dock = 'Fill'; $txtLog.Font = $mono; $txtLog.BackColor = [System.Drawing.Color]::FromArgb(250, 249, 246)
$txtLog.WordWrap = $true
$deployLayout.Controls.Add($txtLog)

$manage = New-Object System.Windows.Forms.FlowLayoutPanel
$manage.AutoSize = $true; $manage.Dock = 'Fill'
$btnStart  = New-Button 'StartApp'  'Start'
$btnStop   = New-Button 'StopApp'   'Stop'
$btnStatus = New-Button 'Status'    'Status'
$btnBackup = New-Button 'BackupNow' 'Back up now'
$btnRemove = New-Button 'Uninstall' 'Uninstall...'
$manage.Controls.AddRange(@((New-Label 'Manage:' $bold), $btnStart, $btnStop, $btnStatus, $btnBackup, $btnRemove))
$deployLayout.Controls.Add($manage)
$t5.Page.Controls.Add($deployLayout)

foreach ($t in @($t1, $t2, $t3, $t4, $t5)) { $tabs.TabPages.Add($t.Page) }

$status = New-Object System.Windows.Forms.StatusStrip
$statusLabel = New-Object System.Windows.Forms.ToolStripStatusLabel
$statusLabel.Name = 'StatusText'; $statusLabel.Spring = $true; $statusLabel.TextAlign = 'MiddleLeft'
$progress = New-Object System.Windows.Forms.ToolStripProgressBar
$progress.Style = 'Marquee'; $progress.Visible = $false
[void]$status.Items.Add($statusLabel); [void]$status.Items.Add($progress)

$form.Controls.Add($tabs)
$form.Controls.Add($header)
$form.Controls.Add($status)

# Every size above is for 100% scaling (96 DPI). Scale the whole form once to
# the screen's real DPI, read fresh from the screen; at 100% nothing changes.
# Scale() covers positions, sizes, margins and padding; the tab padding and the
# progress bar are not controls it reaches, so they are done by hand.
$screen = [System.Drawing.Graphics]::FromHwnd([IntPtr]::Zero)
$dpiScale = $screen.DpiX / 96.0
$screen.Dispose()
if ($dpiScale -gt 1.01) {
    $form.Scale((New-Object System.Drawing.SizeF($dpiScale, $dpiScale)))
}
$tabs.Padding = New-Object System.Drawing.Point([int](14 * $dpiScale), [int](5 * $dpiScale))
$progress.Width = [int](160 * $dpiScale)

# Never open larger than the screen: a 1080p laptop at 150% has room for only
# about 700 points of height once the taskbar is taken off. The tabs scroll.
$workArea = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$form.MinimumSize = New-Object System.Drawing.Size(
    [Math]::Min($form.MinimumSize.Width, $workArea.Width), [Math]::Min($form.MinimumSize.Height, $workArea.Height))
$form.Size = New-Object System.Drawing.Size(
    [Math]::Min($form.Width, $workArea.Width), [Math]::Min($form.Height, $workArea.Height))

# ---------------------------------------------------------------------------
#  Reading the form
# ---------------------------------------------------------------------------
function Get-FormConfig {
    $profiles = @()
    if ($chkDomain.Checked)  { $profiles += 'Domain' }
    if ($chkPrivate.Checked) { $profiles += 'Private' }
    if ($chkPublic.Checked)  { $profiles += 'Public' }
    return Merge-ChalklineConfig -Overrides @{
        InstallRoot         = $txtRoot.Text.Trim()
        InstitutionName     = $txtInstitution.Text.Trim()
        AdminName           = $txtAdminName.Text.Trim()
        AdminEmail          = $txtAdminEmail.Text.Trim()
        AdminPassword       = $txtPassword.Text
        SupportEmail        = $txtSupport.Text.Trim()
        ServerAddress       = $cmbAddress.Text.Trim()
        Port                = [int]$numPort.Value
        DatabaseType        = $(if ($radPg.Checked) { 'PostgreSQL' } else { 'H2' })
        DbHost              = $txtDbHost.Text.Trim()
        DbPort              = [int]$numDbPort.Value
        DbName              = $txtDbName.Text.Trim()
        DbUser              = $txtDbUser.Text.Trim()
        DbPassword          = $txtDbPass.Text
        IncludePremium      = $chkPremium.Checked
        AllowSignUp         = $chkSignUp.Checked
        SeedSampleCatalogue = $chkSamples.Checked
        OpenFirewall        = $chkFirewall.Checked
        FirewallProfiles    = $profiles
        LocalSubnetOnly     = $chkSubnet.Checked
        StartWithWindows    = $chkStartup.Checked
        NightlyBackup       = $chkBackup.Checked
        BackupTime          = $txtBackupTime.Text.Trim()
        BackupKeep          = [int]$numKeep.Value
        JarSource           = $(if ($radFile.Checked) { 'File' } else { 'Build' })
        JarPath             = $txtJar.Text.Trim()
        SourceDir           = $sourceDir
    }
}

function Update-Dependents {
    $lblUrl.Text = 'Lecturers will open:  ' + (Get-ChalklinePublicUrl -Config (Get-FormConfig))
    $pgPanel.Enabled = $radPg.Checked; $btnTestDb.Enabled = $radPg.Checked
    foreach ($c in @($chkDomain, $chkPrivate, $chkPublic, $chkSubnet)) { $c.Enabled = $chkFirewall.Checked }
    foreach ($c in @($txtBackupTime, $numKeep)) { $c.Enabled = $chkBackup.Checked -and $radH2.Checked }
    $chkBackup.Enabled = $radH2.Checked
    $txtJar.Enabled = $radFile.Checked; $btnBrowseJar.Enabled = $radFile.Checked
}

function Write-UiLog([string]$Message) {
    $txtLog.AppendText($Message + [Environment]::NewLine)
}

function Set-Busy([bool]$Busy, [string]$Text = '') {
    foreach ($b in @($btnCheck, $btnPreview, $btnDeploy, $btnStart, $btnStop, $btnStatus, $btnBackup, $btnRemove)) {
        $b.Enabled = -not $Busy
    }
    $progress.Visible = $Busy
    $statusLabel.Text = $Text
}

# Which tab each validation message belongs to, so the form can jump there.
function Get-TabForProblem([string]$Problem) {
    if ($Problem -match 'institution name|administrator|support email|password must|Enter a password') { return 0 }
    if ($Problem -match 'server address|localhost|port must|computer name') { return 1 }
    if ($Problem -match 'PostgreSQL|database') { return 2 }
    return 3
}

function Test-FormIsReady([bool]$Fresh) {
    $config = Get-FormConfig
    $problems = @(Test-ChalklineConfig -Config $config -FreshInstall:$Fresh)
    if ($txtPassword.Text -ne $txtPassword2.Text) { $problems = @('The two passwords do not match.') + $problems }
    if ($problems.Count -gt 0) {
        $tabs.SelectedIndex = Get-TabForProblem $problems[0]
        [void][System.Windows.Forms.MessageBox]::Show(("Before deploying:`n`n- " + ($problems -join "`n- ")),
            'Chalkline Deployer', 'OK', 'Warning')
        return $null
    }
    return $config
}

# ---------------------------------------------------------------------------
#  Background work. Deploying can take several minutes (downloading Java,
#  building), and running it on the form's own thread would freeze the window
#  and Windows would report it as not responding. The worker runs in its own
#  runspace and passes log lines back through a thread-safe queue, which a
#  timer on the form drains.
# ---------------------------------------------------------------------------
function Start-Worker([string]$Description, [scriptblock]$Work, [hashtable]$Arguments, [scriptblock]$OnDone) {
    if ($script:Worker) { return }
    Set-Busy $true $Description
    Write-UiLog ''
    Write-UiLog ("=== " + $Description + " (" + (Get-Date -Format 'HH:mm:ss') + ") ===")

    $rs = [runspacefactory]::CreateRunspace()
    $rs.ApartmentState = 'MTA'; $rs.Open()
    $rs.SessionStateProxy.SetVariable('LogQueue', $script:LogQueue)
    $rs.SessionStateProxy.SetVariable('ModulePath', $modulePath)
    $rs.SessionStateProxy.SetVariable('Arguments', $Arguments)

    $ps = [powershell]::Create()
    $ps.Runspace = $rs
    [void]$ps.AddScript({
        $ErrorActionPreference = 'Stop'
        $ProgressPreference = 'SilentlyContinue'
        Import-Module $ModulePath -Force
        # Defined here, inside the worker, so it belongs to this runspace.
        $log = { param($m) $LogQueue.Enqueue([string]$m) }
        try {
            $result = & ([scriptblock]::Create($Arguments.Work)) $Arguments $log
            return @{ Ok = $true; Result = $result }
        } catch {
            return @{ Ok = $false; Error = $_.Exception.Message }
        }
    })
    $Arguments.Work = $Work.ToString()
    $script:Worker = @{ PS = $ps; Runspace = $rs; Handle = $ps.BeginInvoke(); OnDone = $OnDone }
}

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 150
$timer.Add_Tick({
    $line = $null
    while ($script:LogQueue.TryDequeue([ref]$line)) { Write-UiLog $line }
    if ($script:Worker -and $script:Worker.Handle.IsCompleted) {
        $w = $script:Worker; $script:Worker = $null
        $outcome = $null
        try {
            $output = $w.PS.EndInvoke($w.Handle)
            $outcome = if ($output.Count -gt 0) { $output[$output.Count - 1] } else { @{ Ok = $false; Error = 'No result.' } }
        } catch {
            $outcome = @{ Ok = $false; Error = $_.Exception.Message }
        } finally {
            $w.PS.Dispose(); $w.Runspace.Dispose()
        }
        while ($script:LogQueue.TryDequeue([ref]$line)) { Write-UiLog $line }
        Set-Busy $false ''
        if (-not $outcome.Ok) {
            Write-UiLog ''
            Write-UiLog ('PROBLEM: ' + $outcome.Error)
            $statusLabel.Text = 'Stopped with a problem. See the log above.'
        }
        if ($w.OnDone) { & $w.OnDone $outcome }
    }
})

# ---------------------------------------------------------------------------
#  Buttons
# ---------------------------------------------------------------------------
$btnCheck.Add_Click({
    try {
        $tabs.SelectedIndex = 4
        Write-UiLog ''
        Write-UiLog '=== Checking this computer ==='
        Write-UiLog ("  Running as administrator: yes")
        $java = Find-ChalklineJava
        if ($java) { Write-UiLog "  Java: version $($java.Major) at $($java.Path)" }
        else { Write-UiLog "  Java: not installed. The deployer will install Java 21 ($(Get-JavaArchitecture)) for you." }
        Write-UiLog ("  Network: " + $(if ($categories.Count) { $categories -join ', ' } else { 'unknown' }))
        Write-UiLog ("  Addresses: " + (($addresses | ForEach-Object { $_.Address }) -join ', '))
        $config = Get-FormConfig
        $listening = Get-NetTCPConnection -LocalPort $config.Port -State Listen -ErrorAction SilentlyContinue
        $status0 = Get-ChalklineStatus -Root $config.InstallRoot -Port $config.Port
        if ($listening -and -not $status0.Installed) {
            Write-UiLog "  WARNING: something else is already using port $($config.Port). Choose another port under Network."
        } elseif ($status0.Installed) {
            Write-UiLog "  Chalkline is already installed here ($($status0.TaskState)); deploying again updates it and keeps its data."
        } else {
            Write-UiLog "  Port $($config.Port) is free."
        }
        Write-UiLog ("  Source code: " + $(if ($hasSource) { "found at $sourceDir" } else { 'not found next to this tool; choose a chalkline.jar under Options' }))
    } catch { Write-UiLog ('PROBLEM: ' + $_.Exception.Message) }
})

$btnPreview.Add_Click({
    try {
        $config = Test-FormIsReady $freshInstall
        if (-not $config) { return }
        $tabs.SelectedIndex = 4
        Start-Worker 'Preview: nothing will be changed' {
            param($a, $log) Invoke-ChalklineDeploy -Config $a.Config -Preview -Log $log
        } @{ Config = $config } $null
    } catch { Write-UiLog ('PROBLEM: ' + $_.Exception.Message) }
})

$btnDeploy.Add_Click({
    try {
        $config = Test-FormIsReady $freshInstall
        if (-not $config) { return }
        $url = Get-ChalklinePublicUrl -Config $config
        $confirm = [System.Windows.Forms.MessageBox]::Show(
            ("Install Chalkline on this computer?`n`n" +
             "Lecturers will open:  $url`n" +
             "Install folder:  $($config.InstallRoot)`n" +
             "Database:  " + $(if ($config.DatabaseType -eq 'H2') { 'built in' } else { "PostgreSQL on $($config.DbHost)" }) + "`n`n" +
             "This can take several minutes the first time, while Java and the build tools download."),
            'Chalkline Deployer', 'OKCancel', 'Question')
        if ($confirm -ne 'OK') { return }
        $tabs.SelectedIndex = 4
        $lblResult.Text = ''; $btnOpen.Visible = $false; $btnCopy.Visible = $false
        Start-Worker 'Deploying Chalkline' {
            param($a, $log) Invoke-ChalklineDeploy -Config $a.Config -Log $log
        } @{ Config = $config } {
            param($outcome)
            if ($outcome.Ok -and $outcome.Result.Healthy) {
                $script:LastUrl = $outcome.Result.Url
                $lblResult.Text = 'Deployed. Lecturers open  ' + $script:LastUrl
                $btnOpen.Visible = $true; $btnCopy.Visible = $true
                $statusLabel.Text = 'Chalkline is running.'
                $script:freshInstall = -not $outcome.Result.SetupCompleted
                Update-PasswordHint
                $txtPassword.Text = ''; $txtPassword2.Text = ''
            }
        }
    } catch { Write-UiLog ('PROBLEM: ' + $_.Exception.Message) }
})

$btnOpen.Add_Click({ if ($script:LastUrl) { Start-Process $script:LastUrl } })
$btnCopy.Add_Click({
    if ($script:LastUrl) { [System.Windows.Forms.Clipboard]::SetText($script:LastUrl); $statusLabel.Text = 'Address copied.' }
})

$btnTestDb.Add_Click({
    try {
        $lblDb.Text = 'Trying...'; $form.Refresh()
        $client = New-Object System.Net.Sockets.TcpClient
        $task = $client.ConnectAsync($txtDbHost.Text.Trim(), [int]$numDbPort.Value)
        $reached = $task.Wait(5000) -and $client.Connected
        $client.Close()
        if ($reached) {
            $lblDb.Text = "Reached $($txtDbHost.Text):$($numDbPort.Value). The user name and password are checked when Chalkline starts."
            $lblDb.ForeColor = $ok
        } else {
            $lblDb.Text = "Could not reach $($txtDbHost.Text):$($numDbPort.Value) within 5 seconds."
            $lblDb.ForeColor = $amber
        }
    } catch {
        $lblDb.Text = 'Could not reach the server: ' + $_.Exception.InnerException.Message
        $lblDb.ForeColor = $amber
    }
})

$btnBrowseRoot.Add_Click({
    $d = New-Object System.Windows.Forms.FolderBrowserDialog
    $d.Description = 'Choose where Chalkline keeps its application, database and backups'
    if ($d.ShowDialog() -eq 'OK') { $txtRoot.Text = Join-Path $d.SelectedPath 'Chalkline' }
})
$btnBrowseJar.Add_Click({
    $d = New-Object System.Windows.Forms.OpenFileDialog
    $d.Filter = 'Chalkline (chalkline.jar)|*.jar'; $d.Title = 'Choose chalkline.jar'
    if ($d.ShowDialog() -eq 'OK') { $txtJar.Text = $d.FileName; $radFile.Checked = $true }
})

$btnStart.Add_Click({
    Start-Worker 'Starting Chalkline' { param($a, $log)
        Start-Chalkline -Log $log
        if (Wait-ChalklineHealthy -Port $a.Port -Log $log) { & $log '  Chalkline is answering.' } else { throw 'It did not answer within four minutes. Check logs\chalkline.log.' }
    } @{ Port = [int]$numPort.Value } $null
})
$btnStop.Add_Click({
    Start-Worker 'Stopping Chalkline' { param($a, $log) Stop-Chalkline -Port $a.Port -Log $log } @{ Port = [int]$numPort.Value } $null
})
$btnStatus.Add_Click({
    Start-Worker 'Status' { param($a, $log)
        $s = Get-ChalklineStatus -Root $a.Root -Port $a.Port
        & $log ("  Installed: " + $(if ($s.Installed) { 'yes' } else { 'no' }))
        & $log ("  Scheduled task: " + $s.TaskState)
        & $log ("  Answering on port $($s.Port): " + $(if ($s.Healthy) { 'yes' } else { 'no' }))
    } @{ Root = $txtRoot.Text.Trim(); Port = [int]$numPort.Value } $null
})
$btnBackup.Add_Click({
    Start-Worker 'Backing up now' { param($a, $log) Invoke-ChalklineBackup -Log $log } @{} $null
})
$btnRemove.Add_Click({
    $answer = [System.Windows.Forms.MessageBox]::Show(
        ("Remove Chalkline from this computer?`n`n" +
         "The scheduled tasks, firewall rule, application and settings are removed.`n" +
         "The database and backups are KEPT, so deploying again picks them back up.`n`n" +
         "Choose Yes to uninstall and keep the data, No to also delete the data, or Cancel."),
        'Uninstall Chalkline', 'YesNoCancel', 'Warning')
    if ($answer -eq 'Cancel') { return }
    $removeData = $false
    if ($answer -eq 'No') {
        $really = [System.Windows.Forms.MessageBox]::Show(
            "This permanently deletes every timetable and every backup in $($txtRoot.Text).`n`nThere is no undo. Delete them?",
            'Delete all Chalkline data', 'YesNo', 'Stop', 'Button2')
        if ($really -ne 'Yes') { return }
        $removeData = $true
    }
    # Read back when it finishes. Keeping the data keeps the institution, so a
    # redeploy needs no password; only deleting it makes the next one fresh.
    $script:UninstallRemovesData = $removeData
    Start-Worker 'Uninstalling Chalkline' { param($a, $log)
        Uninstall-Chalkline -Root $a.Root -RemoveData:$a.RemoveData -Log $log
    } @{ Root = $txtRoot.Text.Trim(); RemoveData = $removeData } {
        param($outcome)
        if ($outcome.Ok) {
            $statusLabel.Text = 'Chalkline removed.'
            if ($script:UninstallRemovesData) { $script:freshInstall = $true; Update-PasswordHint }
        }
    }
})

foreach ($c in @($cmbAddress, $numPort, $radH2, $radPg, $chkFirewall, $chkBackup, $radBuild, $radFile)) {
    if ($c -is [System.Windows.Forms.ComboBox]) { $c.Add_TextChanged({ Update-Dependents }) }
    elseif ($c -is [System.Windows.Forms.NumericUpDown]) { $c.Add_ValueChanged({ Update-Dependents }) }
    else { $c.Add_CheckedChanged({ Update-Dependents }) }
}

$form.Add_Shown({
    Update-Dependents
    $timer.Start()
    if ($saved) {
        $statusLabel.Text = 'Loaded the settings from the last deployment. Passwords are never saved.'
    } else {
        $statusLabel.Text = 'Fill in each tab, then Deploy. Nothing changes until you do.'
    }
    $form.Activate()
})
$form.Add_FormClosing({
    param($s, $e)
    if ($script:Worker) {
        $r = [System.Windows.Forms.MessageBox]::Show('A deployment is still running. Close anyway?',
            'Chalkline Deployer', 'YesNo', 'Warning')
        if ($r -ne 'Yes') { $e.Cancel = $true }
    }
})

try {
    [void]$form.ShowDialog()
} catch {
    Show-Fatal ("The deployer stopped unexpectedly:`n`n" + $_.Exception.Message)
} finally {
    $timer.Stop()
}
