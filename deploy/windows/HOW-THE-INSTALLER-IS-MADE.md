# How the Chalkline installer is made

This explains how the Windows deployer in this folder works, and why it is
built the way it is, so you can change it, fix it, and build something like it
yourself. You do not need to know PowerShell to follow it. If you know Java,
most of it will feel familiar, and the comparisons below lean on that.

If you only want to use the deployer, read [README.md](README.md) instead.

**Contents**

1. [The idea](#1-the-idea)
2. [The files, and the one rule behind them](#2-the-files-and-the-one-rule-behind-them)
3. [Why PowerShell and WinForms](#3-why-powershell-and-winforms)
4. [What happens when you press Deploy](#4-what-happens-when-you-press-deploy)
5. [The Windows ideas worth learning](#5-the-windows-ideas-worth-learning)
6. [How the app and the installer talk to each other](#6-how-the-app-and-the-installer-talk-to-each-other)
7. [The form](#7-the-form)
8. [How it is tested](#8-how-it-is-tested)
9. [Traps found while building it](#9-traps-found-while-building-it)
10. [Try it yourself: add a setting to the form](#10-try-it-yourself-add-a-setting-to-the-form)
11. [Where to take it next](#11-where-to-take-it-next)
12. [Words you will meet](#12-words-you-will-meet)

---

## 1. The idea

A school wants Chalkline on one Windows PC so that every lecturer can open it in
a browser. Doing that by hand means installing Java, building the jar, writing a
settings file, giving the right accounts the right permissions, opening the
firewall, making it start with Windows and setting up backups. That is an
afternoon of work, and easy to get slightly wrong in a way nobody notices until
term starts.

The deployer does all of it from a form, the same way every time, and can show
exactly what it will do before it does anything.

---

## 2. The files, and the one rule behind them

| File | What it is |
|---|---|
| `Deploy-Chalkline.cmd` | What you double-click. One command that starts the form. |
| `Deploy-Chalkline.ps1` | The form: five tabs, buttons, and a log window. |
| `ChalklineDeploy.psm1` | The engine. Everything that changes the computer is in here. |
| `tests/ChalklineDeploy.Tests.ps1` | Automated tests for the engine. |
| `README.md` | The user guide. |

**The rule: the form only asks; the engine does.** The form collects answers and
shows progress. Apart from unblocking its own downloaded files when it opens, it
changes nothing itself: every change to the computer happens in a function in
`ChalklineDeploy.psm1`. That split is what makes the rest possible:

- The engine can be tested with no screen at all, on any computer.
- An IT department can read one file to see everything it does, and can run it
  from their own scripts without the form (the user guide shows how).
- Each rule lives in exactly one place. If the form and a script both need to
  check an email address, they call the same function.

It is the same idea as the app itself, where controllers handle the request and
services hold the rules.

The launcher is worth a look, because it is the whole trick for "double-click to
run a PowerShell script":

```bat
powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0Deploy-Chalkline.ps1"
```

- `%~dp0` means "the folder this .cmd file is in", so it works wherever you
  extracted the ZIP.
- `-ExecutionPolicy Bypass` lets this one PowerShell process run the script.
  It changes nothing permanent on the computer.
- `-WindowStyle Hidden` hides the black console window, so only the form shows.

---

## 3. Why PowerShell and WinForms

**Windows PowerShell 5.1 is on every Windows 10 and 11 computer**, and WinForms
(Windows' built-in desktop UI toolkit, roughly what Swing is to Java) comes with
it. So the deployer needs nothing installed, nothing compiled, and a careful IT
technician can read every line before running it.

The obvious alternative is a proper installer: an `.msi` built with a tool such
as WiX, or an `.exe` from Inno Setup. Those are what IT departments push out
centrally, but they need a build toolchain, and to avoid Windows warnings they
need a code-signing certificate, which has to be paid for. For a first
version, a readable script that anyone can change was the better trade.

What it costs:

- **Windows may warn about it** the first time ("Windows protected your PC"),
  because it is downloaded from the internet and not signed.
- **Windows PowerShell 5.1 is old**, and has quirks that PowerShell 7 does not.
  Several of the traps in [section 9](#9-traps-found-while-building-it) only
  happen on 5.1, which is why everything here is tested on it.
- **The scripts must stay plain ASCII.** Windows PowerShell 5.1 reads a script
  without a byte-order mark in the computer's old local code page, so an `é` in
  the source can turn into rubbish. If a script ever needs an accented
  character, build it from its code, for example `'Universit' + [char]0xE9`, or
  save that file with a byte-order mark, as the test file is.

---

## 4. What happens when you press Deploy

`Invoke-ChalklineDeploy` in the engine runs nine steps, and the log window
shows each one as it goes:

| Step | What it does |
|---|---|
| **1/9 Checking the settings** | `Test-ChalklineConfig` checks every answer and returns problems in plain English. Nothing is touched until they are fixed. It also checks it is running as administrator. |
| **2/9 Java** | Looks for Java 21 or newer. If it is missing, downloads it from Adoptium, checks it against the published checksum and installs it silently. |
| **3/9 Chalkline** | Builds `chalkline.jar` with the Maven wrapper, or uses a jar you supplied (for a computer with no internet). |
| **4/9 Folders and permissions** | Creates the install folder (normally `C:\ProgramData\Chalkline`) and the folders inside it, and sets who may read each one. |
| **5/9 Settings** | Writes `config\application.properties`, the settings Chalkline reads. |
| **6/9 Firewall** | Opens the port for the network types you chose, and warns if the network is one the rule would not cover. |
| **7/9 Scheduled tasks** | Registers "Chalkline", which starts with Windows, and "Chalkline Backup", which runs nightly. |
| **8/9 Starting** | Starts Chalkline and waits for its health check to answer. |
| **9/9 Finishing** | On a new install, waits for Chalkline to confirm it created the institution, then deletes the starting password from the settings file. Saves your answers (never passwords) for next time. |

Once the install folder exists, every deployment is also appended to
`logs\deploy.log`, including the reason if it stopped.

### Preview is the same code, not a copy

"Preview the changes" does not run a separate, simulated deployment. It runs
these same nine steps with a `-Preview` switch, and each step checks it before
changing anything. This is step 4, exactly as it is in the engine:

```powershell
    & $say '4/9  Folders and permissions'
    if ($Preview) {
        & $say "  WOULD create $($paths.Root) with app, config, data, logs, backups and tools folders."
        & $say '  WOULD make the config folder readable by the LOCAL SERVICE account and administrators only.'
    } else {
        Stop-Chalkline -Port ([int]$(if ($previous) { $previous.Port } else { $Config.Port })) -Log $Log
        $null = Set-ChalklineFolders -Config $Config -Log $Log
        Copy-Item -LiteralPath $jarToInstall -Destination $paths.Jar -Force
    }
```

Because the preview and the real thing share one path, the preview can never
drift out of date: if someone adds a step, the preview shows it too. When you
add a step, follow the same pattern: describe it with `WOULD` when previewing,
do it otherwise.

---

## 5. The Windows ideas worth learning

### Starting with Windows: a scheduled task, not a service

The usual way to run a server on Windows is a *service*. But a Java program
cannot be a Windows service on its own; it needs a wrapper program such as WinSW
or NSSM, which would mean downloading and trusting another executable. Task
Scheduler is built into every Windows computer and can do the same job: start a
program when Windows starts, and restart it if it stops.

```powershell
    # ExecutionTimeLimit is zero on purpose. The default is 72 hours, after
    # which Windows kills the task. That only applies to runs started by a
    # trigger; a task started by hand is exempt, so testing by hand never shows it.
    $settings = New-ScheduledTaskSettingsSet `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -MultipleInstances IgnoreNew
```

Read that comment twice. Left at the default, Chalkline would have been stopped
three days after every restart, and testing it by hand would never have shown
that, because starting the task by hand does not apply the limit.

### Least privilege: the LOCAL SERVICE account

The task runs Chalkline as `LOCAL SERVICE`, a built-in Windows account with
very few rights, not as an administrator or as `SYSTEM`. Chalkline is a web
server that anyone on the network can talk to. If someone ever found a way to
make it run their code, it should not hold the keys to the whole computer.

The same thinking decides the folder permissions. The settings file can contain
the database password, so only the service account, administrators and Windows
itself can read it, not the lecturers who sign in to that PC.

### Permissions that work in every language

Folder permissions are set with `icacls`, Windows' built-in permissions tool.
The obvious way to refer to a group is by its name, such as `Administrators`,
but on French Windows that group is called `Administrateurs`, and on Spanish
Windows `Administradores`. Every group also has a **SID**, an identifier that never
changes with the language, so the engine uses those:

```powershell
$script:Sid = @{
    System         = 'S-1-5-18'
    LocalService   = 'S-1-5-19'
    Administrators = 'S-1-5-32-544'
}
```

```powershell
    # Config: passwords. Read-only for the service, nothing for anyone else.
    Invoke-Icacls $paths.Config @('/inheritance:r', '/grant:r', "*${sys}:(OI)(CI)F", "*${adm}:(OI)(CI)F", "*${svc}:(OI)(CI)R")
```

`/inheritance:r` stops a folder inheriting permissions from the one above it.
The engine does this on the Chalkline folder itself, because `C:\ProgramData`
lets every user read what is inside it, and again on `config`, so that it gets
exactly the three permissions listed and nothing else. `F` is full control, `R`
is read, and `(OI)(CI)` makes it apply to everything inside.

### The firewall, and networks Windows calls Public

Windows puts every network connection into one of three *profiles*: Domain
(joined to an organisation's network), Private (home) or Public (a café). A
firewall rule only applies to the profiles it names:

```powershell
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
```

The trap: a PC that is not joined to a domain often has its school network
classified as **Public**. A rule for Domain and Private only would then do
nothing, and nobody could connect, with no error anywhere. So the form shows
how Windows has classified the network, ticks Public when it needs to, and
explains why.

### Writing a settings file Spring Boot can read

Chalkline is configured by `config\application.properties`, written by the
engine. The jar itself is never changed. The scheduled task starts Java with
this command line (from the preview):

```
-Xmx768m -Dfile.encoding=UTF-8 -jar "C:\ProgramData\Chalkline\app\chalkline.jar" --spring.profiles.active=prod --spring.config.additional-location=file:C:/ProgramData/Chalkline/config/
```

`--spring.config.additional-location` tells Spring Boot to read that folder as
well as the settings packaged inside the jar, and values in the folder win.
That is how one jar serves every school with different settings.

The interesting part is how values are written. **Spring Boot reads `.properties`
files as ISO-8859-1, not UTF-8.** An institution called "Université" written as
UTF-8 comes back as "UniversitÃ©". So every character outside plain ASCII is
written as a `\u` escape, which Java decodes correctly whatever the file's
encoding:

```powershell
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
```

The general lesson: when one program writes a file for another, find out exactly
how the *reader* reads it, then test with the reader itself
([section 8](#8-how-it-is-tested) shows that test).

### Never run a download you have not checked

The deployer asks Adoptium's API for the newest Java 21 for this processor (x64
or ARM), downloads the installer, and compares its SHA-256 hash with the one
Adoptium publishes before running it as administrator:

```powershell
        $actual = (Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash
        if ($actual -ne $installer.checksum) {
            Remove-Item -LiteralPath $msi -Force -ErrorAction SilentlyContinue
            throw "The Java download did not match its published checksum, so it was deleted and not installed."
        }
```

Running an installer with administrator rights without that check would mean
trusting whatever the network handed back.

---

## 6. How the app and the installer talk to each other

The installer would be much harder to write if the app did not help it. Three
small changes to Chalkline make a deployment possible with no browser step.

### First-run setup

`application.yml` has a `setup` section. The installer fills it in by writing
`app.setup.*` values to the settings file; on a server, the environment
variables shown would do the same job:

```yaml
  setup:
    organisation-name: ${INITIAL_ORG_NAME:}
    admin-name: ${INITIAL_ADMIN_NAME:Administrator}
    admin-email: ${INITIAL_ADMIN_EMAIL:}
    admin-password: ${INITIAL_ADMIN_PASSWORD:}
    seed-sample-catalogue: ${INITIAL_SEED_SAMPLES:false}
    # PREMIUM unlocks every feature with no billing involved: right for an
    # institution hosting its own copy. FREE keeps the five-account cap.
    plan: ${INITIAL_PLAN:FREE}
```

When Chalkline starts, `DataSeeder` creates that institution and its
administrator, but only in a database with no institution in it yet. It also
makes the administrator choose a new password at first sign-in, because the
starting password was typed into a tool, so whoever ran it has seen it:

```java
        if (organisations.count() > 0) {
            if (setup) {
                // Said out loud so the Windows deployer can tell this apart from
                // a setup that never ran: the institution is there already.
                log.info("Initial setup: an institution already exists, so nothing was created.");
            }
            return; // Never modify a database that is already in use.
        }
```

### Log lines are a contract

The installer needs to know whether setup worked, so it can delete the starting
password. Chalkline says so in its log, and the installer reads it:

- `Initial setup: institution '...' created ...` means it worked.
- `Initial setup: an institution already exists ...` means there was nothing to do.
- `Initial setup: ... Nothing created.` means the values were refused.

Those exact words are now an interface between two programs. If you reword
those log messages in `DataSeeder.java`, change `Wait-ChalklineSetup` and
`Test-SetupLogged` in the engine to match, or the installer will stop
recognising them.

This was also the most subtle bug. Chalkline's web server starts answering
**before** `DataSeeder` runs, because Spring Boot runs `ApplicationRunner`
beans after the server is up. So "the health check says UP" did not mean "the
institution exists". The first version checked the log at that moment, found
nothing, and left the starting password in the file. Now the installer waits
for one of the three lines, reading only the part of the log written since this
start:

```powershell
        if ($new -match "Initial setup: institution '.+' created") {
            return [pscustomobject]@{ Outcome = 'Created'; Detail = '' }
        }
        if ($new -match 'Initial setup: an institution already exists') {
            return [pscustomobject]@{ Outcome = 'Exists'; Detail = '' }
        }
```

The lesson is worth keeping: **wait for the thing you need, not for something
that usually happens just before it.**

### Signing in over plain http

In the cloud, Chalkline sits behind HTTPS, so its session cookie is marked
*secure*: the browser only sends it over HTTPS. On a school network reached at
`http://LAB-PC-01:8080`, the browser would silently throw that cookie away and
every sign-in would fail. So the setting can now be switched off, and the
installer does that:

```yaml
        secure: ${SESSION_COOKIE_SECURE:true}
```

---

## 7. The form

### Asking for administrator rights

Creating scheduled tasks and firewall rules needs administrator rights. The
form checks, and if it does not have them it starts itself again with
`-Verb RunAs`, which is what makes Windows ask "Do you want to allow this app
to make changes to your device?":

```powershell
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
```

### Laying it out without pixel positions

The first four tabs are each a `FlowLayoutPanel`, which stacks controls top to
bottom, so nothing is placed at a fixed pixel position. (The Deploy tab uses a
`TableLayoutPanel` instead, so the log can fill the space.) Small helper
functions (`New-Label`, `New-TextBox`, `New-CheckBox`, `New-Button`, `Add-Row`)
keep every control consistent. Every input and button also gets a `Name`, which
is how the automated GUI tests found each one. A row in the Options tab looks
like this:

```powershell
$numKeep = New-Object System.Windows.Forms.NumericUpDown
$numKeep.Name = 'BackupKeep'; $numKeep.Minimum = 1; $numKeep.Maximum = 365; $numKeep.Value = [int]$start.BackupKeep; $numKeep.Width = 60
Add-Row $t4.Flow @($chkBackup, (New-Label '  at'), $txtBackupTime, (New-Label 'keeping the last'), $numKeep)
```

### Never freezing the window

A deployment can take several minutes. If the form did that work itself, the
window would stop responding and Windows would offer to close it. So the work
runs in a **runspace**, a second PowerShell engine on its own thread, and sends
its log lines back through a thread-safe queue:

```powershell
    $rs = [runspacefactory]::CreateRunspace()
    $rs.ApartmentState = 'MTA'; $rs.Open()
    $rs.SessionStateProxy.SetVariable('LogQueue', $script:LogQueue)
```

A timer on the form empties the queue every 150 milliseconds and adds the lines
to the log window:

```powershell
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 150
$timer.Add_Tick({
    $line = $null
    while ($script:LogQueue.TryDequeue([ref]$line)) { Write-UiLog $line }
```

It is the same reason Swing has `SwingWorker`: never do slow work on the UI
thread.

### Sharp on high-resolution screens

Windows PowerShell does not tell Windows it can handle high-resolution screens,
so at 125% scaling and above Windows stretches the whole form like a photo, and
the text goes blurry. The form declares itself DPI-aware before creating any
window:

```powershell
try {
    Add-Type -Namespace ChalklineDeployer -Name Display -MemberDefinition '[DllImport("user32.dll")] public static extern bool SetProcessDPIAware();'
    [void][ChalklineDeployer.Display]::SetProcessDPIAware()
} catch { }
```

Then, once every control exists, it scales the whole layout to the screen:

```powershell
$screen = [System.Drawing.Graphics]::FromHwnd([IntPtr]::Zero)
$dpiScale = $screen.DpiX / 96.0
$screen.Dispose()
if ($dpiScale -gt 1.01) {
    $form.Scale((New-Object System.Drawing.SizeF($dpiScale, $dpiScale)))
}
```

Doing only the first half was worse than doing nothing: the text came out sharp
but twice the size, inside a layout that had not grown, so it was clipped.
Buttons needed one more fix, because by default they can grow but never
shrink, so after scaling they stayed doubled:

```powershell
    $b.AutoSizeMode = 'GrowAndShrink'; $b.MinimumSize = New-Object System.Drawing.Size(75, 0)
```

---

## 8. How it is tested

### Unit tests with Pester

Pester is PowerShell's test framework, the equivalent of JUnit. The tests cover
everything that decides *what* gets written: escaping, validation, the settings
file, the command line, the preview, and reading logs and health checks. They
run anywhere, because they never change the computer.

To run them on Windows, open PowerShell in the `deploy\windows` folder. Windows
comes with a very old Pester, so install a current one once (say Yes if it asks
to install NuGet):

```powershell
Install-Module Pester -MinimumVersion 5.0 -Scope CurrentUser -Force -SkipPublisherCheck
Invoke-Pester .\tests
```

If `Install-Module` says it cannot find the package, Windows PowerShell is
probably using an old version of TLS that the PowerShell Gallery refuses. Run
this in the same window, then try again:

```powershell
[Net.ServicePointManager]::SecurityProtocol = 'Tls12'
```

Two tests show the techniques worth copying.

**Test against the real consumer.** Instead of checking the escaping by eye,
this test writes awkward values to a properties file and has Java's own
`Properties` parser read it back (`Read-WithJava` is a helper at the top of the
test file, and the test is skipped if Java is not installed):

```powershell
    It 'round-trips awkward values through the real Java properties parser' {
        $nasty = @{
            InstitutionName = "Université Félix Houphouët-Boigny"
            AdminPassword   = 'p\a=s:s w0rd#!'
            AdminName       = " Leading Space"
        }
```

**Make the timing problem happen on demand.** The setup race from
[section 6](#6-how-the-app-and-the-installer-talk-to-each-other) depends on
timing, which is hard to reproduce. So the test replaces `Start-Sleep` with a
*mock* that writes the log line during the first wait, which is exactly when
Chalkline gets round to it in real life:

```powershell
        Mock -ModuleName ChalklineDeploy Start-Sleep { Add-Content -LiteralPath $log -Value $script:created -Encoding UTF8 }
        $r = Wait-ChalklineSetup -LogPath $log -TimeoutSeconds 10
        $r.Outcome | Should -Be 'Created'
```

### Testing on real Windows

Unit tests cannot show what Windows actually does. So the deployer was also
tested on a Windows 11 computer with Windows PowerShell 5.1:

- a fresh install, then signing in and being made to change the password
- deploying again over an existing install, which must keep the data
- uninstalling and keeping the data, then deploying again
- uninstalling and deleting the data, then a fresh install
- restarting the computer, then checking Chalkline came back with nobody signed in
- the form at 200% display scaling, and at the 96 DPI a 100% screen uses

If you change something that touches Windows itself (tasks, firewall,
permissions), run through the relevant items on a spare PC or a Windows virtual
machine before anyone else uses it.

### The habit that matters most

When you fix a bug, first write a test that fails because of the bug, then fix
it and watch the test pass. The tests for the setup race, the deploy log, the
network name and the Preview switch were all seen to fail before their fixes
went in. A test that has never failed has not proved anything.

---

## 9. Traps found while building it

Each of these was found while testing or reading the documentation, before
anyone used the deployer. Several only happen on Windows PowerShell 5.1, so they
never showed up when the tests ran on a Mac with PowerShell 7.

| What went wrong | Why | The lesson |
|---|---|---|
| The starting password stayed in the settings file after a fresh install | Chalkline answers its health check just before it creates the institution | Wait for the thing you need, not for a sign that usually comes first |
| A local variable `$preview` replaced the `-Preview` switch | PowerShell variable names ignore case | Choose names that cannot collide, and test the whole path end to end |
| The form showed "System.Object[]" | PowerShell unrolls lists returned from functions, and a workaround nested them | Pick one convention and keep to it: here, callers wrap results in `@( )` |
| A healthy server looked dead | 5.1 returns the health check's body as bytes, not text, for that content type | Test on the version your users have |
| Maven said BUILD SUCCESS, but the deployer reported a failed build | 5.1 loses a process's exit code unless its handle is read straight away | Do not rely on one signal when it can go missing |
| Chalkline would have been stopped three days after every restart | Task Scheduler's default 72-hour limit, which manual starts skip | Read the defaults of anything you configure |
| "é" turned into "Ã©" | Spring Boot reads `.properties` as ISO-8859-1 | Find out how the reader reads the file |
| "Université" came back as "Universit?" | .NET's ASCII encoder silently replaces what it cannot write | Make failures loud: the file writer now refuses instead |
| A Domain and Private firewall rule would not have applied on the test PC | Windows had classified its network as Public | Check the environment and explain the problem in plain English |
| Signing in would have failed over plain http | Browsers drop secure cookies on non-HTTPS sites | A setting that is right in the cloud can be wrong on a school network |
| The form was blurry on high-resolution screens | Windows PowerShell is not DPI-aware | Look at it on a real screen at 150% or 200% |
| Group names differ by language | "Administrators" is "Administrateurs" on French Windows | Use SIDs, which never change |

Most of these share one cause: an assumption that was true where the code was
written and not where it runs. The cure is the same each time: test on the real
target, and when something fails quietly, make it fail loudly.

---

## 10. Try it yourself: add a setting to the form

A good first change touches every layer once. The engine already has a memory
limit for Java, `MaxHeapMb`, defaulting to 768 MB, and it ends up on the
command line as `-Xmx768m`. The form has no box for it. Adding one is a
complete exercise.

**1. The default already exists.** Find `MaxHeapMb = 768` in
`New-ChalklineConfig` in `ChalklineDeploy.psm1`, and see how
`Get-ChalklineJavaArgument` uses it.

**2. Write the test first.** In `tests/ChalklineDeploy.Tests.ps1`, inside
`Describe 'Validating the form'`, add:

```powershell
    It 'refuses a memory limit too small to run Chalkline' {
        $config = New-ValidConfig; $config.MaxHeapMb = 64
        @(Test-ChalklineConfig -Config $config) | Should -Contain 'The memory limit must be between 256 and 8192 MB.'
    }
```

Run `Invoke-Pester .\tests` and watch it fail. That proves the test works.

**3. Add the check** to `Test-ChalklineConfig`, next to the others:

```powershell
    $heap = 0
    if (-not [int]::TryParse([string]$Config.MaxHeapMb, [ref]$heap) -or $heap -lt 256 -or $heap -gt 8192) {
        $problems.Add('The memory limit must be between 256 and 8192 MB.')
    }
```

Run the tests again. They should all pass.

**4. Add the box to the form.** In `Deploy-Chalkline.ps1`, find the Options tab
section (it starts at `# ----- 4. Options`) and add this after the backup row,
following the same pattern as `BackupKeep`:

```powershell
$numHeap = New-Object System.Windows.Forms.NumericUpDown
$numHeap.Name = 'MaxHeapMb'; $numHeap.Minimum = 256; $numHeap.Maximum = 8192; $numHeap.Increment = 256
$numHeap.Value = [int]$start.MaxHeapMb; $numHeap.Width = 80
Add-Row $t4.Flow @((New-Label 'Memory for Chalkline (MB)'), $numHeap)
```

Set `Minimum` and `Maximum` before `Value`, or WinForms refuses a value outside
the default range.

**5. Read it back.** In `Get-FormConfig`, add `MaxHeapMb = [int]$numHeap.Value`
to the list. This step matters: the form builds its settings from the defaults
plus its own fields, so a setting it does not read would go back to 768 on
every deployment.

**6. Nothing else to do for saving.** The answers file keeps every setting
except passwords, so your new one is remembered automatically, and a problem
with it opens the Options tab, because that is where unrecognised problems go.

**7. Try it on Windows.** Open the deployer, choose 1024, and click *Preview
the changes*. Step 7 shows the command line, which should now say `-Xmx1024m`.

---

## 11. Where to take it next

- **Sign it.** A code-signing certificate lets Windows show who the deployer is
  from, which makes the download warnings less alarming.
- **Build an `.msi`.** Many universities install software centrally, with tools
  such as Microsoft Intune or Configuration Manager, and those expect an `.msi`.
  WiX can wrap the same engine.
- **Add HTTPS.** Put a small web server such as Caddy in front of Chalkline,
  using the university's certificate, and switch the secure cookie back on.
  The user guide explains the manual version.
- **Check for updates.** The form could compare its version with the newest one
  on GitHub and offer to download it.
- **Back up PostgreSQL too.** Today the nightly backup covers the built-in
  database only.

---

## 12. Words you will meet

| Word | Meaning |
|---|---|
| **PowerShell module** (`.psm1`) | A file of functions that other scripts import, like a Java library. |
| **WinForms** | The desktop UI toolkit built into .NET on Windows. |
| **Runspace** | A separate PowerShell engine, used here to work on another thread. |
| **Scheduled task** | An entry in Task Scheduler that runs a program on a trigger, such as Windows starting. |
| **LOCAL SERVICE** | A built-in Windows account with very few rights, meant for services. |
| **SID** | The fixed identifier behind a user or group name, the same in every language. |
| **icacls** | Windows' built-in tool for setting who may read or change a file or folder. |
| **Firewall profile** | Domain, Private or Public. A rule only applies to the profiles it names. |
| **Execution policy** | PowerShell's setting for which scripts may run. The launcher relaxes it for one process only. |
| **Byte-order mark** | A few invisible bytes at the start of a file that say it is UTF-8. |
| **Pester** | PowerShell's test framework, like JUnit. |
| **Mock** | A stand-in for a real command during a test, so the test controls what it does. |
| **MSI** | The Windows Installer package format that IT departments deploy centrally. |
